package com.blaze.assistant.agent

import android.content.Context
import com.blaze.assistant.notify.Notifier
import com.blaze.assistant.phone.PhoneTools
import com.blaze.assistant.voice.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

enum class Role { USER, BLAZE, STEP, ERROR }

/** [files] are attachment names shown under a user message. */
data class ChatMessage(val id: Long, val role: Role, val text: String, val files: List<String> = emptyList(),
                       val thinking: String = "", val followups: List<String> = emptyList(), val meta: String = "")

/** Splits a raw reply into what's shown, hidden reasoning (<think> tags) and follow-up suggestions. */
object ReplyParts {
    private val thinkRe = Regex("<(think|thought|thinking)>([\\s\\S]*?)(</\\1>|$)", RegexOption.IGNORE_CASE)
    private val fuRe = Regex("<<\\s*follow-?ups?\\s*:([\\s\\S]*?)(>>|$)", RegexOption.IGNORE_CASE)

    data class Parts(val text: String, val thinking: String, val followups: List<String>)

    fun split(raw: String): Parts {
        val thinking = thinkRe.findAll(raw).joinToString("\n\n") { it.groupValues[2].trim() }
        var text = thinkRe.replace(raw, "")
        val fu = fuRe.find(text)
        val followups = fu?.takeIf { it.groupValues[2] == ">>" }?.groupValues?.get(1)
            ?.split('|')?.map { it.trim().trim('"', '-', '•', ' ') }?.filter { it.isNotEmpty() }?.take(3) ?: emptyList()
        if (fu != null) text = text.substring(0, fu.range.first)
        // Hide a half-typed "<<follow-ups" while streaming.
        val cut = text.lastIndexOf("<<")
        if (cut >= 0 && text.length - cut < 16) text = text.substring(0, cut)
        return Parts(text.trim(), thinking, followups)
    }
}

enum class Mood { IDLE, THINKING, ACTING }

/** An action waiting for the user's OK (send a text, place a call, tap "Pay"). */
data class PendingConfirm(val title: String, val detail: String, val action: String,
                          val answer: kotlinx.coroutines.CompletableDeferred<Boolean>)

/** Something the user attached: [parts] are OpenAI content parts (image_url / text). */
data class Attachment(val name: String, val parts: List<JSONObject>)

/** The brain: keeps the conversation and runs the think → act → look loop. */
object Agent {
    private const val MAX_STEPS = 30
    private const val SHOT_NOTE = "[Screenshot of the phone screen]"
    private const val SCREEN_MARK = "Foreground app:"
    private val screenActions = setOf("tap", "type_text", "scroll", "press_button", "open_app", "open_url")
    private val ids = AtomicLong(1)
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _mood = MutableStateFlow(Mood.IDLE)
    val mood: StateFlow<Mood> = _mood

    /** The model's reasoning so far in the current turn (shown live under the brain). */
    private val _thinking = MutableStateFlow("")
    val thinking: StateFlow<String> = _thinking

    private val _confirm = MutableStateFlow<PendingConfirm?>(null)
    val pendingConfirm: StateFlow<PendingConfirm?> = _confirm

    /**
     * Ask the user to approve something risky. Shows a card in the chat and the orb panel,
     * plus a notification with Allow / Don't when Blaze isn't on screen. Times out as "no".
     */
    suspend fun confirm(title: String, detail: String, action: String): Boolean {
        val d = kotlinx.coroutines.CompletableDeferred<Boolean>()
        _confirm.value = PendingConfirm(title, detail, action, d)
        if (!inForeground) Notifier.confirm(title, detail, action)
        val ok = kotlinx.coroutines.withTimeoutOrNull(120_000) { d.await() } ?: false
        _confirm.value = null
        Notifier.clearConfirm()
        add(Role.STEP, if (ok) "You approved: $title" else "Not approved: $title")
        return ok
    }

    fun answerConfirm(ok: Boolean) { _confirm.value?.answer?.complete(ok) }

    private val risky = Regex("\\b(send|pay|buy|purchase|order|checkout|check out|delete|remove|post|publish|transfer|confirm|subscribe|place order|book)\\b", RegexOption.IGNORE_CASE)

    private val _chats = MutableStateFlow<List<ChatMeta>>(emptyList())
    val chats: StateFlow<List<ChatMeta>> = _chats

    private val _chatId = MutableStateFlow("")
    val chatId: StateFlow<String> = _chatId
    private var title = ""
    private var pinned = false
    /** One-line summary written by the learning review (used by search_past_chats). */
    private var summary = ""

    /** Phone actions taken this turn (tool, args, element label) for skills / task replay. */
    private val trace = JSONArray()
    @Volatile private var currentStep = 0

    /** Conversation as sent to the model (OpenAI chat format). */
    private val history = JSONArray()

    /** history.length() at the moment each user message was added, so a retry can rewind. */
    private val historyMark = HashMap<Long, Int>()

    fun init(ctx: Context) {
        app = ctx.applicationContext
        ChatStore.init(app)
        refreshChats()
        // Reopen the chat you were in.
        val last = Settings.lastChatId
        if (last.isNotEmpty() && ChatStore.load(last) != null) open(last) else newChatId()
    }

    private fun newChatId() {
        _chatId.value = UUID.randomUUID().toString().take(12)
        title = ""; pinned = false; summary = ""
        Settings.lastChatId = _chatId.value
    }

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ioLock = kotlinx.coroutines.sync.Mutex()

    /** Reads chat files in the background so the UI never waits on disk. */
    fun refreshChats() { io.launch { val l = ChatStore.list(); _chats.value = l } }

    /** A line from the app itself (command results, help). Shown, not sent to the model. */
    fun note(text: String, asReply: Boolean = false) { add(if (asReply) Role.BLAZE else Role.STEP, text) }

    /** The whole visible chat as plain text. */
    fun transcript(): String = _messages.value.filter { it.role != Role.STEP }.joinToString("\n\n") {
        (if (it.role == Role.USER) "You: " else "Blaze: ") + it.text }

    /** Retry the newest answer. */
    fun retryLast() { _messages.value.lastOrNull { it.role == Role.BLAZE || it.role == Role.ERROR }?.let { retry(it.id) } }

    private fun add(role: Role, text: String, files: List<String> = emptyList()): Long {
        val id = ids.getAndIncrement()
        _messages.value = _messages.value + ChatMessage(id, role, text, files)
        if (role == Role.STEP && !inForeground && _mood.value != Mood.IDLE) Notifier.working(if (currentStep > 0) "Step $currentStep · $text" else text)
        return id
    }

    private fun replace(id: Long, role: Role, text: String) {
        _messages.value = _messages.value.map { if (it.id == id) it.copy(role = role, text = text) else it }
    }

    private fun addReply(liveId: Long, raw: String, thinking: String, fu: List<String> = emptyList(), meta: String = "") {
        val p = ReplyParts.split(raw).let { if (it.followups.isEmpty() && fu.isNotEmpty()) it.copy(followups = fu) else it }
        val think = listOf(thinking, p.thinking).filter { it.isNotBlank() }.joinToString("\n\n")
        val text = p.text.ifEmpty { "Done." }
        if (liveId >= 0) _messages.value = _messages.value.map {
            if (it.id == liveId) it.copy(role = Role.BLAZE, text = text, thinking = think, followups = p.followups, meta = meta) else it }
        else {
            val id = ids.getAndIncrement()
            _messages.value = _messages.value + ChatMessage(id, Role.BLAZE, text, thinking = think, followups = p.followups, meta = meta)
        }
    }

    /** Adds a chip (e.g. "Memory updated") to a reply's footer. */
    private fun addMeta(id: Long, chip: String) {
        _messages.value = _messages.value.map { if (it.id == id) it.copy(meta = listOf(it.meta, chip).filter { s -> s.isNotBlank() }.joinToString(" · ")) else it }
    }

    // ---------- Chats ----------

    /** Save the current chat to disk (screenshots are dropped to keep files small). */
    private fun persist() {
        if (_messages.value.none { it.role == Role.USER }) return
        val msgs = JSONArray()
        for (m in _messages.value) {
            msgs.put(JSONObject().put("id", m.id).put("role", m.role.name).put("text", m.text)
                .put("files", JSONArray(m.files)).apply {
                    historyMark[m.id]?.let { put("mark", it) }
                    if (m.thinking.isNotEmpty()) put("thinking", m.thinking.take(20000))
                    if (m.followups.isNotEmpty()) put("followups", JSONArray(m.followups))
                    if (m.meta.isNotEmpty()) put("meta", m.meta)
                })
        }
        val hist = JSONArray()
        for (i in 0 until history.length()) hist.put(stripImages(history.getJSONObject(i)))
        val firstUser = _messages.value.first { it.role == Role.USER }.text
        if (title.isEmpty()) title = firstUser.lineSequence().first().take(48)
        val preview = _messages.value.lastOrNull { it.role == Role.BLAZE }?.text ?: firstUser
        val doc = JSONObject().put("id", _chatId.value).put("title", title).put("pinned", pinned)
            .put("updated", System.currentTimeMillis()).put("preview", preview.take(90))
            .put("messages", msgs).put("history", hist).put("summary", summary)
        Settings.lastChatId = _chatId.value
        io.launch { ioLock.withLock { ChatStore.save(doc) }; _chats.value = ChatStore.list() }
    }

    /** Images become a short note in saved history; the words stay. */
    private fun stripImages(m: JSONObject): JSONObject {
        val c = m.opt("content") as? JSONArray ?: return m
        val out = JSONArray(); var dropped = 0
        for (i in 0 until c.length()) {
            val p = c.getJSONObject(i)
            if (p.optString("type") == "image_url") dropped++ else out.put(p)
        }
        if (dropped == 0) return m
        out.put(JSONObject().put("type", "text").put("text", "[$dropped image(s) were shared here earlier]"))
        return JSONObject(m.toString()).put("content", out)
    }

    fun open(id: String) {
        val o = ChatStore.load(id) ?: return
        stop()
        _chatId.value = id
        title = o.optString("title"); pinned = o.optBoolean("pinned"); summary = o.optString("summary")
        historyMark.clear()
        while (history.length() > 0) history.remove(0)
        val h = o.optJSONArray("history") ?: JSONArray()
        for (i in 0 until h.length()) history.put(h.get(i))
        val list = mutableListOf<ChatMessage>()
        val ms = o.optJSONArray("messages") ?: JSONArray()
        var maxId = 0L
        for (i in 0 until ms.length()) {
            val m = ms.getJSONObject(i)
            val mid = m.getLong("id"); maxId = maxOf(maxId, mid)
            val files = m.optJSONArray("files")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            val fu = m.optJSONArray("followups")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            list += ChatMessage(mid, Role.valueOf(m.getString("role")), m.getString("text"), files, m.optString("thinking"), fu, m.optString("meta"))
            if (m.has("mark")) historyMark[mid] = m.getInt("mark")
        }
        ids.set(maxOf(ids.get(), maxId + 1))
        _messages.value = list
        Settings.lastChatId = id
    }

    /** Start a fresh chat (the current one is already saved). */
    fun clear() {
        stop()
        persist()
        _messages.value = emptyList()
        historyMark.clear()
        while (history.length() > 0) history.remove(0)
        newChatId()
    }

    fun deleteChat(id: String) {
        ChatStore.delete(id)
        if (id == _chatId.value) { stop(); _messages.value = emptyList(); historyMark.clear()
            while (history.length() > 0) history.remove(0); newChatId() }
        refreshChats()
    }

    fun renameChat(id: String, t: String) { ChatStore.rename(id, t); if (id == _chatId.value) title = t; refreshChats() }
    fun pinChat(id: String, p: Boolean) { ChatStore.setPinned(id, p); if (id == _chatId.value) pinned = p; refreshChats() }

    /** A scheduled routine: runs in a fresh chat and reports by notification. */
    fun runRoutine(name: String, prompt: String) {
        clear()
        title = "⟳ $name"
        send("[Scheduled routine \"$name\". The user isn't watching; do it and reply in 1-3 short sentences that read well as a notification.]\n$prompt")
    }

    fun stop() {
        _confirm.value?.answer?.complete(false)
        job?.cancel()
        job = null
        _mood.value = Mood.IDLE
    }

    // ---------- Prompt ----------

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date())
        val name = Settings.userName.ifBlank { null }
        val control = Settings.phoneControl
        val persona = if (Settings.personaId == Personas.CUSTOM && Settings.customPersona.isNotBlank())
            "You are Blaze, an AI assistant on the user's Android phone. " + Settings.customPersona
        else Personas.byId(Settings.personaId).prompt
        return buildString {
            append(persona).append(' ')
            if (name != null) append("The user's name is $name. ")
            append("It is $now. ")
            append(Memory.promptBlock(lastUserText()))
            append(com.blaze.assistant.device.DeviceStatus.promptLine(app))
            append(Learning.promptBlock(lastUserText()))
            append(com.blaze.assistant.connect.Connectors.promptBlock())
            append("\nWhen the user tells you something lasting about themselves, save it with the remember tool. ")
            if (control) {
                append("""
You can control the phone with tools. To do something in an app:
1. open_app (or press_button home), 2. tap / type_text / scroll using element numbers, and repeat until done.
After every open_app, tap, type_text, scroll or press_button the fresh screen is returned automatically, so you rarely need read_screen. Element numbers change after every action: always use the newest screen.
When you already know the next few steps on the current screen (tap a field, type, tap Search), do them in one do_actions call.
If read_screen shows little or nothing useful (games, maps, photos, video, custom apps), call look_at_screen to see a screenshot.
Prefer the direct tools (set_alarm, set_timer, dial, compose_sms, open_url) when they fit.
Before anything irreversible or that spends money or contacts someone (sending a message, posting, paying, deleting, calling), stop and ask the user to confirm in your reply, unless they clearly already told you to do exactly that.
When you finish, say in one or two sentences what you did.
""".trimIndent())
            } else {
                append("Phone control is turned off in settings, so you can only chat.")
            }
            append("\nYou can also read and send texts, check calls, read and add calendar events, look up contacts, toggle the flashlight and volume, and schedule routines with your tools. Sending a text or placing a call asks the user for approval automatically.")
            if (Settings.followups) append("\nAt the very end of each final answer (never when calling tools), add one line exactly like <<follow-ups: first | second | third>> with three short next requests (max 6 words each) written as the user would say them. Skip it for routines.")
        }
    }

    private fun lastUserText(): String = _messages.value.lastOrNull { it.role == Role.USER }?.text.orEmpty()

    // ---------- Turns ----------

    fun send(text: String, byVoice: Boolean = false, attachments: List<Attachment> = emptyList()) {
        if ((text.isBlank() && attachments.isEmpty()) || job?.isActive == true) return
        Voice.lastTurnByVoice = byVoice
        val shown = text.trim().ifEmpty { "Take a look at this." }
        val id = add(Role.USER, shown, attachments.map { it.name })
        historyMark[id] = history.length()
        val content: Any = if (attachments.isEmpty()) shown else JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", shown))
            attachments.forEach { a -> a.parts.forEach { put(it) } }
        }
        history.put(JSONObject().put("role", "user").put("content", content))
        val routine = shown.startsWith("[Scheduled routine")
        job = scope.launch {
            // Fast lane: direct commands run on-device in well under a second.
            val offline = !FastLane.online(app)
            if (attachments.isEmpty() && !routine && (FastLane.enabled(app) || IntentCache.size() > 0)) {
                _mood.value = Mood.THINKING
                val fast = try { FastLane.tryHandle(app, shown, offline) { t, d, a -> confirm(t, d, a) } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                if (fast != null) {
                    history.put(JSONObject().put("role", "assistant").put("content", fast.reply))
                    addReply(-1, fast.reply, "", meta = if (fast.ms < 60) "⚡ instant · ${fast.ms} ms" else "⚡ on-device · ${fast.ms} ms")
                    _mood.value = Mood.IDLE
                    persist(); announce()
                    launch { kotlinx.coroutines.delay(5 * 60_000 + 5_000); FastLane.trimIfIdle() }
                    return@launch
                }
            }
            if (offline && !Settings.customBase.contains("127.0.0.1") && !Settings.customBase.contains("localhost")) {
                add(Role.ERROR, if (FastLane.enabled(app)) "You're offline. I can still do direct commands like \u201copen WhatsApp\u201d, \u201cflashlight on\u201d or \u201cset a timer for 5 minutes\u201d." 
                    else "You're offline. Turn on the fast lane in Settings → AI & model to use direct commands without internet.")
                _mood.value = Mood.IDLE; persist(); return@launch
            }
            runLoop()
        }
    }

    /**
     * Retry from a message. On a user message: resend it. On a reply or error: regenerate
     * the answer to the prompt before it. Everything after that prompt is discarded.
     */
    fun retry(messageId: Long) {
        val list = _messages.value
        val idx = list.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        val userIdx = (idx downTo 0).firstOrNull { list[it].role == Role.USER } ?: return
        val user = list[userIdx]
        val mark = historyMark[user.id] ?: return
        stop()
        // Keep the original user turn (with any attachments) and drop everything after it.
        while (history.length() > mark + 1) history.remove(history.length() - 1)
        list.drop(userIdx + 1).forEach { historyMark.remove(it.id) }
        _messages.value = list.take(userIdx + 1)
        Voice.lastTurnByVoice = false
        job = scope.launch { runLoop() }
    }

    /** Only the newest screenshot is sent as an image; older ones become a note. */
    private fun requestMessages(): JSONArray {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt()))
        var lastShot = -1
        for (i in 0 until history.length()) if (isShot(history.getJSONObject(i))) lastShot = i
        var lastScreen = -1
        for (i in 0 until history.length()) if (history.getJSONObject(i).let { it.optString("role") == "tool" && it.optString("content").contains(SCREEN_MARK) }) lastScreen = i
        for (i in 0 until history.length()) {
            val m = history.getJSONObject(i)
            msgs.put(when {
                isShot(m) && i != lastShot -> JSONObject().put("role", "user").put("content", "$SHOT_NOTE (older, removed)")
                // Only the newest screen readout is sent in full; older ones are stale anyway.
                i != lastScreen && m.optString("role") == "tool" && m.optString("content").contains(SCREEN_MARK) ->
                    JSONObject(m.toString()).put("content", m.optString("content").substringBefore(SCREEN_MARK) + "[older screen omitted]")
                else -> m
            })
        }
        return msgs
    }

    private fun isShot(m: JSONObject): Boolean {
        val c = m.opt("content") as? JSONArray ?: return false
        return c.optJSONObject(0)?.optString("text") == SHOT_NOTE
    }

    private suspend fun tools(): JSONArray {
        val t = JSONArray()
        Memory.tools().forEach { t.put(it) }
        t.put(com.blaze.assistant.device.DeviceStatus.tool())
        Learning.tools().forEach { t.put(it) }
        if (Settings.screenMemory) t.put(ScreenMemory.tool())
        if (com.blaze.assistant.notify.NotificationBrain.instance != null) com.blaze.assistant.notify.NotificationBrain.tools().forEach { t.put(it) }
        com.blaze.assistant.connect.Connectors.tools().forEach { t.put(it) }
        try { com.blaze.assistant.connect.Mcp.tools().forEach { t.put(it) } } catch (_: Exception) { }
        if (Settings.phoneControl) t.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "do_actions")
            .put("description", "Run several phone actions in a row in one go (e.g. tap the search box, type, tap a result), using element numbers from the latest screen. Stops at the first one that fails. The new screen is returned at the end.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("steps", JSONObject().put("type", "array")
                .put("items", JSONObject().put("type", "object").put("properties", JSONObject()
                    .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("tap", "type_text", "scroll", "press_button", "wait"))))
                    .put("element", JSONObject().put("type", "integer")).put("text", JSONObject().put("type", "string"))
                    .put("direction", JSONObject().put("type", "string")).put("button", JSONObject().put("type", "string"))
                    .put("seconds", JSONObject().put("type", "number")))
                    .put("required", JSONArray(listOf("action")))))).put("required", JSONArray(listOf("steps"))))))
        com.blaze.assistant.routine.Routines.tools().forEach { t.put(it) }
        com.blaze.assistant.phone.Skills.definitions().forEach { t.put(it) }
        if (Settings.phoneControl) { val p = PhoneTools.definitions(); for (i in 0 until p.length()) t.put(p.get(i)) }
        return t
    }

    private suspend fun runLoop() {
        try {
            val tools = tools()
            while (trace.length() > 0) trace.remove(0)
            val turnThink = StringBuilder()
            val turnStart = System.currentTimeMillis()
            _thinking.value = ""
            for (step in 0 until MAX_STEPS) {
                currentStep = step + 1
                _mood.value = Mood.THINKING
                var liveId = -1L
                val before = turnThink.toString()
                var pendingFollowups = emptyList<String>()
                val onText: ((String) -> Unit)? = if (Settings.streamReplies) { partial ->
                    scope.launch {
                        val p = ReplyParts.split(partial)
                        if (p.thinking.isNotEmpty()) _thinking.value = listOf(before, p.thinking).filter { it.isNotBlank() }.joinToString("\n\n")
                        if (p.text.isNotEmpty()) {
                            if (liveId < 0) liveId = add(Role.BLAZE, p.text) else replace(liveId, Role.BLAZE, p.text)
                        }
                    }
                } else null
                val onThink: (String) -> Unit = { t -> scope.launch { _thinking.value = listOf(before, t).filter { it.isNotBlank() }.joinToString("\n\n") } }

                val req = requestMessages()
                val reply = LlmClient.complete(req, tools, onText, onThink, if (step == 0) LlmClient.Lane.MAIN else LlmClient.Lane.STEPS)
                reply.optString("_reasoning").takeIf { it.isNotBlank() }?.let { if (turnThink.isNotEmpty()) turnThink.append("\n\n"); turnThink.append(it) }
                reply.remove("_reasoning")
                try { Usage.record(req, reply, LlmClient.lastUsage) } catch (_: Exception) { }
                kotlinx.coroutines.yield()
                history.put(reply)

                // Keep reasoning and follow-up tags out of what's sent back next turn.
                (reply.opt("content") as? String)?.let { raw ->
                    val p = ReplyParts.split(raw)
                    if (p.text != raw.trim()) {
                        reply.put("content", p.text)
                        val parts = ReplyParts.split(raw)
                        if (parts.thinking.isNotEmpty()) { if (turnThink.isNotEmpty()) turnThink.append("\n\n"); turnThink.append(parts.thinking) }
                        if (parts.followups.isNotEmpty()) pendingFollowups = parts.followups
                    }
                }
                val calls = reply.optJSONArray("tool_calls")
                val textOut = when (val content = reply.opt("content")) {
                    is String -> content
                    is JSONArray -> (0 until content.length()).joinToString("") {
                        content.optJSONObject(it)?.optString("text") ?: ""
                    }
                    else -> ""
                }.trim()

                if (calls == null || calls.length() == 0) {
                    kotlinx.coroutines.delay(60)   // let the last streamed chunk land first
                    val secs = (System.currentTimeMillis() - turnStart) / 1000.0
                    com.blaze.assistant.device.CrashLog.slow("turn ($step steps, ${LlmClient.lastRoute})", System.currentTimeMillis() - turnStart)
                    addReply(liveId, textOut, turnThink.toString(), pendingFollowups,
                        if (Settings.showRoute) "☁ " + listOf(LlmClient.lastRoute.ifBlank { Settings.model }, "%.1f s".format(secs),
                            if (step > 0) "${step + 1} steps" else "").filter { it.isNotBlank() }.joinToString(" · ") else "")
                    break
                }
                // Text that came with tool calls is narration, shown as a step; its reasoning joins the turn's.
                val narr = ReplyParts.split(textOut)
                if (narr.thinking.isNotEmpty()) { if (turnThink.isNotEmpty()) turnThink.append("\n\n"); turnThink.append(narr.thinking) }
                _thinking.value = turnThink.toString()
                if (liveId >= 0) {
                    if (narr.text.isNotEmpty()) replace(liveId, Role.STEP, narr.text)
                    else _messages.value = _messages.value.filterNot { it.id == liveId }
                } else if (narr.text.isNotEmpty()) add(Role.STEP, narr.text)

                _mood.value = Mood.ACTING
                val shots = mutableListOf<String>()
                for (i in 0 until calls.length()) {
                    val call = calls.getJSONObject(i)
                    val fn = call.getJSONObject("function")
                    val name = fn.getString("name")
                    val args = try { JSONObject(fn.optString("arguments", "{}").ifBlank { "{}" }) }
                        catch (_: Exception) { JSONObject() }
                    var result = runTool(name, args, shots)
                    if (name == "do_actions" || (name in screenActions && !result.contains(SCREEN_MARK))) result += screenAfter(result)
                    history.put(
                        JSONObject().put("role", "tool")
                            .put("tool_call_id", call.optString("id", "call_$step$i"))
                            .put("name", name)
                            .put("content", result)
                    )
                }
                shots.lastOrNull()?.let { url ->
                    history.put(JSONObject().put("role", "user").put("content", JSONArray()
                        .put(JSONObject().put("type", "text").put("text", SHOT_NOTE))
                        .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url)))))
                }
                if (step == MAX_STEPS - 1) add(Role.BLAZE, "I've taken a lot of steps on this one, so I'm pausing here. Tell me if you want me to keep going.")
            }
        } catch (e: CancellationException) {
            add(Role.STEP, "Stopped.")
            throw e
        } catch (e: LlmClient.LlmException) {
            add(Role.ERROR, e.message ?: "Something went wrong.")
        } catch (e: Exception) {
            com.blaze.assistant.device.CrashLog.error("agent loop", e)
            add(Role.ERROR, "Something went wrong: ${e.message}")
        } finally {
            _thinking.value = ""
            _mood.value = Mood.IDLE
            currentStep = 0
            persist()
            announce()
            learnAfterTurn()
        }
    }

    /** Fresh compact screen after an action, so the model doesn't spend a turn on read_screen. */
    private suspend fun screenAfter(result: String): String {
        val svc = com.blaze.assistant.phone.BlazeAccessibilityService.instance ?: return ""
        if (!Settings.phoneControl || result.startsWith("Error") || result.startsWith("No app") || result.startsWith("Phone control is off")) return ""
        svc.settle(1500, 250)
        return "\n\nScreen now:\n" + svc.readScreen()
    }

    private suspend fun runTool(name: String, args: JSONObject, shots: MutableList<String>): String {
        val svc = com.blaze.assistant.phone.BlazeAccessibilityService.instance
        // Record phone actions (with the element's label, since numbers change) for task replay.
        if (name in setOf("open_app", "tap", "type_text", "scroll", "press_button", "open_url") && trace.length() < 40) {
            val label = if (name == "tap" || name == "type_text") svc?.labelOf(args.optInt("element")).orEmpty() else ""
            trace.put(JSONObject().put("tool", name).put("args", JSONObject(args.toString()).apply { remove("element") }).put("label", label))
        }
        return when (name) {
            "remember" -> { add(Role.STEP, "Remembering that"); Memory.add(args.optString("fact")) }
            "forget" -> { add(Role.STEP, "Forgetting that"); Memory.forget(args.optString("fact")) }
            "recall_memory" -> { add(Role.STEP, "Checking memory"); Memory.recall(args.optString("query")) }
            "device_status" -> { add(Role.STEP, "Checking the phone"); com.blaze.assistant.device.DeviceStatus.describe(app) }
            "look_at_screen" -> {
                add(Role.STEP, "Looking at a screenshot")
                val shot = PhoneTools.screenshot()
                if (shot.startsWith("data:")) { shots += shot; "Screenshot taken. It is attached in the next message." } else shot
            }
            "create_routine", "list_routines", "delete_routine" -> {
                add(Role.STEP, if (name == "create_routine") "Scheduling \"${args.optString("name")}\"" else "Checking routines")
                try { com.blaze.assistant.routine.Routines.run(name, args) } catch (e: Exception) { "Error: ${e.message}" }
            }
            in com.blaze.assistant.phone.Skills.names -> {
                add(Role.STEP, com.blaze.assistant.phone.Skills.describe(name, args))
                com.blaze.assistant.phone.Skills.run(app, name, args)
            }
            in Learning.toolNames -> {
                add(Role.STEP, when (name) { "run_skill" -> "Replaying skill \"${args.optString("name")}\""; "skill_manage" -> "Saving a skill"; else -> "Searching past chats" })
                try { Learning.run(app, name, args) } catch (e: CancellationException) { throw e } catch (e: Exception) { "Error: ${e.message}" }
            }
            "search_screen_memory" -> { add(Role.STEP, "Searching screen memory"); ScreenMemory.search(app, args.optString("query"), args.optString("app").ifBlank { null }) }
            "recent_notifications" -> { add(Role.STEP, "Checking notifications"); com.blaze.assistant.notify.NotificationBrain.list(args.optString("app").ifBlank { null }, args.optDouble("hours", 6.0)) }
            "reply_to_notification" -> { add(Role.STEP, "Replying"); com.blaze.assistant.notify.NotificationBrain.reply(app, args.optInt("number"), args.optString("text")) }
            "do_actions" -> {
                val steps = args.optJSONArray("steps") ?: JSONArray()
                val log = StringBuilder()
                for (i in 0 until minOf(steps.length(), 8)) {
                    val st = steps.getJSONObject(i)
                    val act = st.optString("action").let { if (it == "type") "type_text" else it }
                    val sub = JSONObject(st.toString()).apply { remove("action") }
                    val r = runTool(act, sub, shots)
                    log.append("${i + 1}. $act: ${r.take(200)}\n")
                    if (r.startsWith("Error") || r.startsWith("No element") || r.contains("did NOT approve") || r.startsWith("Unknown")) { log.append("Stopped at step ${i + 1}."); break }
                    if (i < steps.length() - 1 && act != "wait") svc?.settle(900, 200)
                }
                log.toString()
            }
            "tap" -> {
                add(Role.STEP, PhoneTools.describe(name, args))
                val label = svc?.labelOf(args.optInt("element")).orEmpty()
                if (Settings.confirmRisky && risky.containsMatchIn(label) &&
                    !confirm("Tap \u201c${label.take(40)}\u201d?", "Blaze wants to tap this in ${svc?.currentApp() ?: "the app"}.", "Tap"))
                    "The user did NOT approve tapping \"$label\". Don't retry; tell them it's ready for them to finish."
                else PhoneTools.run(app, name, args)
            }
            else -> when {
                com.blaze.assistant.connect.Mcp.handles(name) -> { add(Role.STEP, "Using ${name.removePrefix("mcp_").substringBefore('_')}"); com.blaze.assistant.connect.Mcp.call(name, args) }
                com.blaze.assistant.connect.Connectors.handles(name) -> { add(Role.STEP, com.blaze.assistant.connect.Connectors.describe(name)); com.blaze.assistant.connect.Connectors.run(app, name, args) }
                else -> { add(Role.STEP, PhoneTools.describe(name, args)); PhoneTools.run(app, name, args) }
            }
        }
    }

    /** After a turn: summary, memories and skills in the background (see Learning). */
    private fun learnAfterTurn() {
        if (!Settings.autoLearn) return
        val reply = _messages.value.lastOrNull { it.role == Role.BLAZE } ?: return
        if (reply.meta.contains("on-device")) return
        val msgs = _messages.value
        val lastUser = msgs.indexOfLast { it.role == Role.USER }; if (lastUser < 0) return
        val exchange = msgs.drop(maxOf(0, lastUser - 4)).joinToString("\n") { m ->
            when (m.role) { Role.USER -> "User: "; Role.BLAZE -> "Blaze: "; Role.STEP -> "  (step) "; Role.ERROR -> "  (error) " } + m.text.take(1500) }
        if (msgs.drop(lastUser).any { it.role == Role.ERROR }) return
        val t = JSONArray(trace.toString())
        val cid = _chatId.value
        val hasSummary = summary.isNotBlank()
        io.launch {
            val chip = Learning.review(cid, exchange, t, hasSummary) { id, s -> scope.launch { setSummary(id, s) } }
            if (chip != null) scope.launch { addMeta(reply.id, chip); persist() }
        }
    }

    private fun setSummary(id: String, s: String) {
        if (id == _chatId.value) { summary = s; persist() }
        else io.launch { ioLock.withLock { ChatStore.load(id)?.let { ChatStore.save(it.put("summary", s)) } } }
    }

    /** True while Blaze's own screen is visible. */
    @Volatile var inForeground = false

    /** True while the assistant overlay or floating orb is open (replies show there, not as notifications). */
    @Volatile var overlayOpen = false

    fun lastStep(): String = _messages.value.lastOrNull { it.role == Role.STEP }?.text ?: "Thinking"

    /** When Blaze finishes while you're in another app, post its answer as a notification. */
    private fun announce() {
        Notifier.doneWorking()
        val last = _messages.value.lastOrNull() ?: return
        if (last.role != Role.BLAZE && last.role != Role.ERROR) return
        if (Voice.lastTurnByVoice && Settings.speakReplies) Voice.speak(last.text)
        try { com.blaze.assistant.widget.BlazeWidget.update(app, last.text.take(240)) } catch (_: Exception) { }
        if (inForeground || overlayOpen) return
        Notifier.reply(last.text, last.role == Role.ERROR)
    }
}
