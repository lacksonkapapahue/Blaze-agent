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
import kotlinx.coroutines.async
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
                       val thinking: String = "", val followups: List<String> = emptyList(), val meta: String = "",
                       /** Pictures, beats and other media Blaze made for this reply (absolute paths). */
                       val media: List<String> = emptyList())

/** Splits a raw reply into what's shown, hidden reasoning (<think> tags) and follow-up suggestions. */
object ReplyParts {
    private val thinkRe = Regex("<(think|thought|thinking)>([\\s\\S]*?)(</\\1>|$)", RegexOption.IGNORE_CASE)
    // Tolerant: "<<follow-ups: a | b>>", "<</follow-ups: a | b>>", "<follow ups: a|b>", "<<followups:a|b" at the end.
    private val fuRe = Regex("<{1,2}\\s*/?\\s*follow[\\s_-]?ups?\\s*:([\\s\\S]*?)(>{1,2}|$)", RegexOption.IGNORE_CASE)

    data class Parts(val text: String, val thinking: String, val followups: List<String>)

    fun split(raw: String, final: Boolean = false): Parts {
        val thinking = thinkRe.findAll(raw).joinToString("\n\n") { it.groupValues[2].trim() }
        var text = thinkRe.replace(raw, "")
        val fu = fuRe.find(text)
        val followups = fu?.takeIf { it.groupValues[2].isNotEmpty() || (final && it.groupValues[1].contains('|')) }?.groupValues?.get(1)
            ?.split('|')?.map { it.trim().trim('"', '-', '•', ' ', '<', '>', '/') }?.filter { it.isNotEmpty() }?.take(3) ?: emptyList()
        if (fu != null) text = text.substring(0, fu.range.first)
        // Hide a half-typed "<<follow-ups" / "</follow" while streaming.
        val cut = text.lastIndexOf('<')
        if (cut >= 0 && text.length - cut < 18 && Regex("^<{1,2}\\s*/?\\s*(f(o(l(l(o(w[\\s_-]?(u(ps?)?)?)?)?)?)?)?)?$", RegexOption.IGNORE_CASE).matches(text.substring(cut).trimEnd()))
            text = text.substring(0, cut)
        return Parts(text.trim(), thinking, followups)
    }
}

enum class Mood { IDLE, THINKING, ACTING }

/** An action waiting for the user's OK (send a text, place a call, tap "Pay"). */
data class PendingConfirm(val title: String, val detail: String, val action: String,
                          val answer: kotlinx.coroutines.CompletableDeferred<Boolean>)

/** A question Blaze asks mid-task, with tap-to-answer [options] (the UI always adds "type my own"). */
data class PendingQuestion(val question: String, val options: List<String>, val chat: String,
                           val answer: kotlinx.coroutines.CompletableDeferred<String>)

/** Something the user attached: [parts] are OpenAI content parts (image_url / text). */
data class Attachment(val name: String, val parts: List<JSONObject>)

/** The brain: keeps the conversation and runs the think → act → look loop. */object Agent {
    private const val MAX_STEPS = 30
    private const val SHOT_NOTE = "[Screenshot of the phone screen]"
    private const val SCREEN_MARK = "Foreground app:"
    /** Read-only tools that are safe to run side by side. */
    private val parallelSafe = setOf("web_search", "fetch_page", "recall_memory", "search_past_chats", "gmail_search", "gmail_read",
        "gcal_events", "calendar_events", "drive_search", "gcontacts_search", "find_contact", "recent_notifications", "read_messages",
        "recent_calls", "device_status", "battery_level", "github_issues", "github_my_prs", "github_notifications", "github_search_repos",
        "notion_search", "slack_read", "slack_channels", "search_screen_memory", "spotify_now_playing")

    private val screenActions = setOf("tap", "type_text", "scroll", "press_button", "open_app", "open_url", "search_in_app")
    private val ids = AtomicLong(1)
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + com.blaze.assistant.device.CrashLog.guard)

    /** Messages of the chat on screen. */
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _mood = MutableStateFlow(Mood.IDLE)
    val mood: StateFlow<Mood> = _mood

    /** The model's reasoning so far in the current turn (shown live under the brain). */
    private val _thinking = MutableStateFlow("")
    val thinking: StateFlow<String> = _thinking

    /** Ids of chats that are working right now (shown as a live dot in the chat list). */
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busyChats: StateFlow<Set<String>> = _busy
    private fun refreshBusy() { _busy.value = sessions.values.filter { it.mood != Mood.IDLE }.map { it.id }.toSet() }

    private val _confirm = MutableStateFlow<PendingConfirm?>(null)
    val pendingConfirm: StateFlow<PendingConfirm?> = _confirm

    /**
     * Ask the user to approve something risky. Shows a card in the chat and the orb panel,
     * plus a notification with Allow / Don't when Blaze isn't on screen. Times out as "no".
     */
    suspend fun confirm(title: String, detail: String, action: String): Boolean {
        if (Autonomy.preApproved(title, detail)) { cur.note("Standing OK: $title", false); return true }
        val d = kotlinx.coroutines.CompletableDeferred<Boolean>()
        _confirm.value = PendingConfirm(title, detail, action, d)
        if (!inForeground) Notifier.confirm(title, detail, action)
        val ok = kotlinx.coroutines.withTimeoutOrNull(120_000) { d.await() } ?: false
        _confirm.value = null
        Notifier.clearConfirm()
        cur.note(if (ok) "You approved: $title" else "Not approved: $title", false)
        return ok
    }

    fun answerConfirm(ok: Boolean) { _confirm.value?.answer?.complete(ok) }

    private val _question = MutableStateFlow<PendingQuestion?>(null)
    val pendingQuestion: StateFlow<PendingQuestion?> = _question
    private val askLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Pause the task and ask the user. In Blaze it's a card in the chat; over another app the
     * orb panel pops up with the question so the task continues right where it is.
     */
    suspend fun askUser(question: String, options: List<String>, chat: String): String = askLock.withLock {
        val d = kotlinx.coroutines.CompletableDeferred<String>()
        _question.value = PendingQuestion(question, options.filter { it.isNotBlank() }.take(5), chat, d)
        var popped = false
        if (!inForeground && !overlayOpen) {
            if (com.blaze.assistant.ui.Overlay.canDrawOverApps(app)) {
                kotlinx.coroutines.withContext(Dispatchers.Main) { com.blaze.assistant.ui.Overlay.openPanel(app, false) }; popped = true
            } else Notifier.question(question)
        }
        if (Voice.lastTurnByVoice && Settings.speakReplies) Voice.speak(question)
        val a = kotlinx.coroutines.withTimeoutOrNull(15 * 60_000) { d.await() }
        _question.value = null
        Notifier.clearQuestion()
        if (popped && a != null) kotlinx.coroutines.withContext(Dispatchers.Main) { com.blaze.assistant.ui.Overlay.closePanel() }
        a ?: "(The user didn't answer within 15 minutes. Stop and say what you need.)"
    }

    fun answerQuestion(text: String) { _question.value?.answer?.complete(text) }

    private val risky = Regex("\\b(send|pay|buy|purchase|order|checkout|check out|delete|remove|post|publish|transfer|confirm|subscribe|place order|book)\\b", RegexOption.IGNORE_CASE)

    private val _chats = MutableStateFlow<List<ChatMeta>>(emptyList())
    val chats: StateFlow<List<ChatMeta>> = _chats

    private val _chatId = MutableStateFlow("")
    val chatId: StateFlow<String> = _chatId

    /** Every chat that's open or still working, by id. */
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, Session>()
    @Volatile private var cur: Session = Session("boot")

    private fun show(s: Session) {
        cur = s
        sessions[s.id] = s
        _chatId.value = s.id
        _messages.value = s.msgs
        _mood.value = s.mood
        _thinking.value = s.thinking
        Settings.lastChatId = s.id
        // Forget idle chats in the background; working ones stay until they finish.
        sessions.values.removeAll { it !== s && !it.busy }
    }

    fun init(ctx: Context) {
        app = ctx.applicationContext
        ChatStore.init(app)
        try { com.blaze.assistant.media.Media.init(app) } catch (_: Exception) { }
        refreshChats()
        // Reopen the chat you were in.
        val last = Settings.lastChatId
        if (last.isNotEmpty() && ChatStore.load(last) != null) open(last) else show(Session(newId()))
    }

    private fun newId() = UUID.randomUUID().toString().take(12)

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.blaze.assistant.device.CrashLog.guard)
    private val ioLock = kotlinx.coroutines.sync.Mutex()

    /** Reads chat files in the background so the UI never waits on disk. */
    fun refreshChats() { io.launch { val l = ChatStore.list(); _chats.value = l } }

    /** A line from the app itself (command results, help). Shown, not sent to the model. */
    fun note(text: String, asReply: Boolean = false) { cur.note(text, asReply) }

    /** The whole visible chat as plain text. */
    fun transcript(): String = cur.msgs.filter { it.role != Role.STEP }.joinToString("\n\n") {
        (if (it.role == Role.USER) "You: " else "Blaze: ") + it.text }

    /** Retry the newest answer. */
    fun retryLast() { cur.msgs.lastOrNull { it.role == Role.BLAZE || it.role == Role.ERROR }?.let { retry(it.id) } }

    fun send(text: String, byVoice: Boolean = false, attachments: List<Attachment> = emptyList()) {
        // "Shut down", "stop", "be quiet"… are handled on-device, even mid-task.
        val self = if (attachments.isEmpty()) SelfControl.match(text) else null
        if (self != null) {
            val s = cur
            com.blaze.assistant.device.Haptics.tick(app)
            if (self != "quiet") s.selfTurn(text.trim(), SelfControl.run(app, self), byVoice)
            else SelfControl.run(app, self)
            return
        }
        // Time, date, battery: answered on the phone instantly.
        if (attachments.isEmpty() && _question.value == null) {
            SelfControl.quick(app, text)?.let { r -> com.blaze.assistant.device.Haptics.tick(app); cur.selfTurn(text.trim(), r, byVoice, "⚡ Instant"); return }
        }
        // Typing or speaking while Blaze waits on a question answers it.
        if (_question.value != null && text.isNotBlank()) { answerQuestion(text.trim()); return }
        cur.send(text, byVoice, attachments)
    }
    fun retry(messageId: Long) = cur.retry(messageId)
    fun stop() = cur.stop()

    /** Runs a model call and gives up (null) only if no streamed chunk arrives for [limitMs]. */
    private suspend fun idleGuard(limitMs: Long, block: suspend () -> JSONObject): JSONObject? {
        val start = System.currentTimeMillis()
        val d = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).async { block() }
        try {
            while (!d.isCompleted) {
                kotlinx.coroutines.delay(500)
                if (System.currentTimeMillis() - maxOf(start, LlmClient.lastChunkAt) > limitMs) { d.cancel(); return null }
            }
            return d.await()
        } finally { if (!d.isCompleted) d.cancel() }
    }
    fun stopAll() = sessions.values.forEach { it.stop() }

    /** A background failure becomes an error line in the chat instead of a crash. */
    fun reportError(text: String) {
        val s = cur
        s.add(Role.ERROR, text)
        try { s.mood = Mood.IDLE } catch (_: Throwable) { }
        try { s.persist() } catch (_: Throwable) { }
    }

    /** Open a chat. If it's still working in the background you see it live. */
    fun open(id: String) { load(id)?.let { show(it) } }

    /** A chat's session: the live one if it's open or working, else read from disk. */
    private fun load(id: String): Session? {
        sessions[id]?.let { return it }
        val o = ChatStore.load(id) ?: return null
        val s = Session(id)
        s.title = o.optString("title"); s.pinned = o.optBoolean("pinned"); s.summary = o.optString("summary")
        val h = o.optJSONArray("history") ?: JSONArray()
        for (i in 0 until h.length()) s.history.put(h.get(i))
        val list = mutableListOf<ChatMessage>()
        val ms = o.optJSONArray("messages") ?: JSONArray()
        var maxId = 0L
        for (i in 0 until ms.length()) {
            val m = ms.getJSONObject(i)
            val mid = m.getLong("id"); maxId = maxOf(maxId, mid)
            val files = m.optJSONArray("files")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            val fu = m.optJSONArray("followups")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            val media = m.optJSONArray("media")?.let { a -> (0 until a.length()).map { a.getString(it) } }?.filter { java.io.File(it).exists() } ?: emptyList()
            list += ChatMessage(mid, Role.valueOf(m.getString("role")), m.getString("text"), files, m.optString("thinking"), fu, m.optString("meta"), media)
            if (m.has("mark")) s.historyMark[mid] = m.getInt("mark")
        }
        ids.set(maxOf(ids.get(), maxId + 1))
        s.msgs = list
        return s
    }

    /**
     * Send [text] into chat [chatId] (or a new chat) without switching the screen, wait for the
     * answer and return (chatId, reply). Used by the dashboard.
     */
    suspend fun askIn(chatId: String?, text: String): Pair<String, String> {
        val s = chatId?.let { load(it) } ?: Session(newId()).also { it.title = "Dashboard · " + text.take(30) }
        sessions[s.id] = s
        if (s.busy) s.job?.join()
        val before = s.msgs.size
        kotlinx.coroutines.withContext(Dispatchers.Main) { s.send(text) }
        s.job?.join()
        val reply = s.msgs.drop(before).lastOrNull { it.role == Role.BLAZE || it.role == Role.ERROR }?.text.orEmpty()
        return s.id to reply
    }

    /** Start a fresh chat. The one you leave keeps working if it was busy. */
    fun clear() {
        cur.persist()
        show(Session(newId()))
    }

    fun deleteChat(id: String) {
        sessions[id]?.stop()
        sessions.remove(id)
        ChatStore.delete(id)
        if (id == cur.id) show(Session(newId()))
        refreshChats()
    }

    fun renameChat(id: String, t: String) { ChatStore.rename(id, t); sessions[id]?.title = t; refreshChats() }
    fun pinChat(id: String, p: Boolean) { ChatStore.setPinned(id, p); sessions[id]?.pinned = p; refreshChats() }

    /** A scheduled routine: runs in its own background chat and reports by notification. */
    fun runRoutine(name: String, prompt: String) {
        val s = Session(newId())
        s.title = "⟳ $name"
        sessions[s.id] = s
        s.send("[Scheduled routine \"$name\". The user isn't watching; do it and reply in 1-3 short sentences that read well as a notification.]\n$prompt")
    }

    /** Run a prompt in a hidden background chat and get the final answer (proactive checks, dashboard). */
    suspend fun ask(title: String, prompt: String, visible: Boolean = false): String {
        val s = Session(newId())
        s.title = title
        sessions[s.id] = s
        s.send(prompt)
        s.job?.join()
        val out = s.msgs.lastOrNull { it.role == Role.BLAZE || it.role == Role.ERROR }?.text.orEmpty()
        if (!visible) { ChatStore.delete(s.id); sessions.remove(s.id); refreshChats() }
        return out
    }

    /** Messages of any chat (for the dashboard). */
    fun messagesOf(id: String): List<ChatMessage>? = load(id)?.msgs

    fun lastStep(): String = cur.msgs.lastOrNull { it.role == Role.STEP }?.text ?: "Thinking"

    // ---------- Prompt ----------

    private fun personaText(): String =
        if (Settings.personaId == Personas.CUSTOM && Settings.customPersona.isNotBlank())
            "You are Blaze, an AI assistant on the user's Android phone. " + Settings.customPersona
        else Personas.byId(Settings.personaId).prompt

    private fun systemPrompt(userText: String): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date())
        val name = Settings.userName.ifBlank { null }
        val control = Settings.phoneControl
        val persona = if (Settings.personaId == Personas.CUSTOM && Settings.customPersona.isNotBlank())
            "You are Blaze, an AI assistant on the user's Android phone. " + Settings.customPersona
        else Personas.byId(Settings.personaId).prompt
        return buildString {
            append(persona).append(' ')
            if (name != null) append("The user's name is $name. ")
            append(com.blaze.assistant.connect.Connectors.promptBlock())
            append("\nHOW YOU TALK: short, warm and sure of yourself, like a sharp friend texting back. Lead with the answer. One or two sentences unless they ask for more. Never say \"let me know if there's anything else\", \"feel free to\", \"I'd be happy to\" or similar filler, and never ask the user to share personal details for your memory. ")
            append("\nYou are Blaze and you can control yourself with blaze_self (close/go quiet, stop, orb, wake word, read-aloud, model, personality, settings, your setup and crash log). When the user wants help setting you up, or asks what you can do or what's installed, call blaze_self setup_check and guide them one step at a time. If the user asks you to shut down, close, go away or stop, do that with blaze_self, never a phone setting. ")
            append("\nWhen the user tells you something lasting about themselves, save it quietly with the remember tool. ")
            append("\nIf the user says \"again\", \"do it again\" or \"same for X\", repeat your last actions from this chat (adapted to X) without asking. ")
            append("\nIf you're unsure what the user wants, or there are several options (accounts, contacts, items, times), don't guess and don't end your turn: call ask_user with short options, then carry on with the answer. ")
            if (control) {
                append("""
BACKGROUND FIRST: never take over the user's screen when a background tool can do it. Web questions → web_search/fetch_page; sites you must click, fill or log in to → browser_open/browser_click/browser_type (Blaze's own background browser; browser_show only when the user must see or take over). Messaging someone → message_contact (never open WhatsApp on screen yourself). Email → gmail_search/gmail_read. Calendar → gcal_events or calendar tools. Texts, calls, contacts → the SMS/call/contact tools. Notifications → recent_notifications. Music → spotify tools. Files/code → run_shell. Only if no background tool exists or it isn't connected, use the screen; when a background tool isn't connected, offer to connect it (blaze_self open "settings connections") instead of silently driving the app.
You can control the phone. SPEED MATTERS: the user is watching.
- For anything in an app, call run_plan ONCE with the whole script, using the visible words on buttons and fields as targets (e.g. open_app YouTube → tap "Search" → type "lofi hip hop" submit → tap the first result's title). You know how common apps look; you don't need to read the screen first. Add optional:true for popups you might need to dismiss, and expect:"..." when a step must lead somewhere.
- Element numbers stay the same while the screen stays the same; after an action you often get only the changes ("Same screen… Changed or new"), so keep using the numbers you have.
- For flows with predictable pop-ups (sign-in, cookie banners, "Not now"), set watchers with watch (or a preset) so the phone reacts instantly instead of spending your steps.
- run_plan only comes back when it's done or stuck. If stuck, it returns the screen with element numbers: fix it with a short new run_plan, or tap/type_text by number.
- Use single tap/type_text/scroll only for one-off fixes. If read_screen shows little (games, maps, video, custom apps), call look_at_screen.
- Prefer direct tools when they fit: search_in_app for "play/search X on YouTube/Spotify/Play Store/Maps", set_alarm, set_timer, dial, compose_sms. open_url only for real web addresses; web_search to look something up yourself.
- Before anything irreversible or that spends money or contacts someone (sending, posting, paying, deleting, calling), confirm first unless the user clearly told you to do exactly that.
When you finish, say what you did in one short sentence.
""".trimIndent())
            } else {
                append("Phone control is turned off in settings, so you can only chat.")
            }
            append("\nYou can also read and send texts, check calls, read and add calendar events, look up contacts, toggle the flashlight and volume, and schedule routines with your tools. Sending a text or placing a call asks the user for approval automatically.")
            if (Settings.followups) append("\nAt the very end of each final answer (never when calling tools), add one line exactly like <<follow-ups: first | second | third>> with three short next requests (max 6 words each) written as the user would say them. Skip it for routines.")
            append("\nIf a tool you need isn't in your list, call load_tools with its group, then carry on.")
            append("\nEverything you create that keeps running (alarms, timers, routines, triggers, calendar events, jobs) gets an ID like R4. To change, stop or delete one, call processes to find the ID, then process(id, action). Only use action=details when the user asks for specifics.")
            // Speed plan step 2: everything above stays identical turn after turn, so providers can
            // reuse their cached prefix. Everything that changes (clock, memory, device) goes last.
            append("\n\n")
            append("CURRENT LOCAL TIME: $now (${java.util.TimeZone.getDefault().id}). This is exact: answer any time, date or day question straight from it, never say you can't see the time. ")
            append(Memory.promptBlock(userText))
            append(com.blaze.assistant.device.DeviceStatus.promptLine(app))
            append("\nKNOW BEFORE YOU ASK: never ask the user something you can find out yourself. Their phone make, model, Android version and skin are in the line above; " +
                "check installed apps with list_apps (e.g. whether Secure Folder, Termux or a bank app is there), where they are with get_location, settings and screen state with your phone tools, time and battery from 'Phone now', " +
                "and facts about them from memory. Answer for THIS phone specifically (exact menu names for its make and Android version), not a list of options for every brand. " +
                "Ask only for things that live in their head: preferences, choices, consent.")
            append("\nTERMINAL: check terminal_status before assuming a tool exists. Write files with file_write/file_edit (never echo or heredocs), read with file_read, find with file_search. " +
                "Run programs with run_code (it installs Python/Node and packages itself). Anything over ~30 s or a server goes in background (run_shell background=true), then terminal_jobs id=N / job_input; preview web servers with preview_server; repeat scripts on a schedule with schedule_command. " +
                "Use the git tool for repos (already signed in to their GitHub). Call APIs with http_request. On an error, read it, fix the cause and retry (up to 3 times) before telling the user; a [hint] line tells you what to try. Give the user results with export_file.")
            append("\nMAKE THINGS IN THE BACKGROUND, NOT ON THE SCREEN: you CAN create images (generate_image, free) and you have a real Linux terminal with Python. " +
                "For anything that produces or processes content (pictures, beats and music, charts, documents, conversions, edits, calculations, data, downloads), do it in the terminal with run_code instead of opening apps and tapping. " +
                "Beats: run_code python `import sys; sys.path.insert(0,'/root/phone/blaze_lib'); from beats import make; print(make(style='trap', bpm=140, bars=8, key='A', out='/root/phone/beat.wav'))` (styles trap, boombap, afro, house, lofi; vary seed). " +
                "Save outputs under /root/phone/ (that is ~ for the file tools): new pictures, audio and video files there are added to the user's Gallery and shown in the chat automatically. " +
                "Only drive the screen when the task truly lives inside another app (sending in WhatsApp, a setting with no tool). Never tell the user you can't make images or music. " +
                "generate_image is ONLY for art and photos; charts, graphs, tables, PDFs and documents are always made with run_code. If the terminal fails, say so plainly; never substitute an AI picture.")
            append(SelfSetup.promptLine(app))
            append(Learning.promptBlock(userText))
            Processes.countLine().takeIf { it.isNotBlank() }?.let { append("\nTracked now: $it (IDs via processes).") }
            append(Autonomy.promptBlock()); append(Autonomy.promptLine()); append(SkillPacks.promptBlock())
        }
    }

    private suspend fun tools(): JSONArray {
        val t = JSONArray()
        Memory.tools().forEach { t.put(it) }
        t.put(com.blaze.assistant.device.DeviceStatus.tool())
        t.put(SelfControl.tool())
        t.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "ask_user")
            .put("description", "Pause and ask the user a question with 2-4 short tap-able options (an 'own answer' choice is added automatically). Use when a real choice is theirs (which account, which contact, which item) or the request is ambiguous. Never use it for facts you can check yourself (phone model, Android version, installed apps, settings, battery). The task waits right where it is and continues with the answer.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("question", JSONObject().put("type", "string"))
                .put("options", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))))
                .put("required", JSONArray(listOf("question", "options"))))))
        Web.tools().forEach { t.put(it) }
        com.blaze.assistant.media.Media.tools().forEach { t.put(it) }
        Processes.tools().forEach { t.put(it) }
        t.put(com.blaze.assistant.phone.Watchers.tool())
        SkillPacks.tools().forEach { t.put(it) }
        com.blaze.assistant.phone.AndroidControl.tools().forEach { t.put(it) }
        t.put(Autonomy.findToolDef()); t.put(Autonomy.goalTool()); t.put(Autonomy.permissionTool())
        com.blaze.assistant.proactive.Triggers.tools().forEach { t.put(it) }
        t.put(com.blaze.assistant.phone.Ussd.tool())
        t.put(com.blaze.assistant.phone.Messaging.tool())
        com.blaze.assistant.browser.BrowserTools.definitions().forEach { t.put(it) }
        if (Settings.bool("terminal_tools", true)) com.blaze.assistant.terminal.Terminal.tools(app).forEach { t.put(it) }
        if (Settings.bool("terminal_tools", true)) com.blaze.assistant.terminal.DevTools.tools().forEach { t.put(it) }
        if (Settings.bool("terminal_tools", true)) t.put(com.blaze.assistant.terminal.Git.tool())
        com.blaze.assistant.phone.PimEdit.tools().forEach { t.put(it) }
        t.put(com.blaze.assistant.device.SelfCheck.tool())
        t.put(com.blaze.assistant.phone.Location.tool())
        if (Settings.bool("specialists_on", true)) t.put(Specialists.tool())
        Learning.tools().forEach { t.put(it) }
        if (Settings.screenMemory) t.put(ScreenMemory.tool())
        if (com.blaze.assistant.notify.NotificationBrain.instance != null) com.blaze.assistant.notify.NotificationBrain.tools().forEach { t.put(it) }
        com.blaze.assistant.connect.Connectors.tools().forEach { t.put(it) }
        try { com.blaze.assistant.connect.Mcp.tools().forEach { t.put(it) } } catch (_: Exception) { }
        if (Settings.phoneControl) t.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "run_plan")
            .put("description", "FASTEST way to do anything in an app. Give the whole script at once; Blaze runs it on-device in a few seconds, finding things by their visible text (no element numbers, no need to read the screen first), checking each step, and only comes back when done or stuck.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("steps", JSONObject().put("type", "array")
                .put("items", JSONObject().put("type", "object").put("properties", JSONObject()
                    .put("do", JSONObject().put("type", "string").put("enum", JSONArray(listOf("open_app", "tap", "long_press", "type", "enter", "scroll", "back", "home", "wait", "search_in_app"))))
                    .put("app", JSONObject().put("type", "string").put("description", "open_app / search_in_app: app name"))
                    .put("target", JSONObject().put("type", "string").put("description", "tap/type: the visible text, label or hint of the element, e.g. Search, Send, Message"))
                    .put("text", JSONObject().put("type", "string").put("description", "type: what to type"))
                    .put("submit", JSONObject().put("type", "boolean").put("description", "type: press Enter/Search after"))
                    .put("query", JSONObject().put("type", "string")).put("play", JSONObject().put("type", "boolean"))
                    .put("direction", JSONObject().put("type", "string")).put("seconds", JSONObject().put("type", "number"))
                    .put("expect", JSONObject().put("type", "string").put("description", "optional text that should be visible after this step"))
                    .put("optional", JSONObject().put("type", "boolean").put("description", "skip instead of stopping if it fails (e.g. dismissing a popup)")))
                    .put("required", JSONArray(listOf("do")))))).put("required", JSONArray(listOf("steps"))))))
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
        return ToolGuide.apply(t)
    }

    /** True while Blaze's own screen is visible. */
    @Volatile var inForeground = false

    /** True while the assistant overlay or floating orb is open (replies show there, not as notifications). */
    @Volatile var overlayOpen = false



    /**
     * One chat with its own agent. Several can run at once: leaving a chat (or starting a new
     * one) never stops it or sends its answer to the wrong place.
     */
    class Session(val id: String) {
        /** Speed plan step 11: text of the current step already handed to TTS while it streamed. */
        var spokenPrefix = ""
        var streamedVoice = false
        var title = ""
        var pinned = false
        /** One-line summary written by the learning review (used by search_past_chats). */
        var summary = ""
        val history = JSONArray()
        val historyMark = HashMap<Long, Int>()
        val trace = JSONArray()
        @Volatile var currentStep = 0
        var job: Job? = null

        private var _msgs: List<ChatMessage> = emptyList()
        var msgs: List<ChatMessage>
            get() = _msgs
            set(v) { _msgs = v; if (this === cur) _messages.value = v }
        private var _mood = Mood.IDLE
        var mood: Mood
            get() = _mood
            set(v) { _mood = v; if (this === cur) Agent._mood.value = v; refreshBusy() }
        private var _thinking = ""
        var thinking: String
            get() = _thinking
            set(v) { _thinking = v; if (this === cur) Agent._thinking.value = v }

        val busy get() = job?.isActive == true

        fun note(text: String, asReply: Boolean) { add(if (asReply) Role.BLAZE else Role.STEP, text) }

        fun lastUserText(): String = msgs.lastOrNull { it.role == Role.USER }?.text.orEmpty()

        fun stop() {
            if (this === cur) _confirm.value?.answer?.complete(false)
            job?.cancel()
            job = null
            mood = Mood.IDLE
        }
        fun add(role: Role, text: String, files: List<String> = emptyList()): Long {
            val id = ids.getAndIncrement()
            msgs = msgs + ChatMessage(id, role, text, files)
            if (role == Role.STEP && !inForeground && mood != Mood.IDLE) Notifier.working(if (currentStep > 0) "Step $currentStep · $text" else text)
            return id
        }

        fun replace(id: Long, role: Role, text: String) {
            msgs = msgs.map { if (it.id == id) it.copy(role = role, text = text) else it }
        }

        /** Media made during this turn; shown under the final reply. */
        val pendingMedia = mutableListOf<String>()

        fun addReply(liveId: Long, raw: String, thinking: String, fu: List<String> = emptyList(), meta: String = "") {
            val media = synchronized(pendingMedia) { pendingMedia.toList().also { pendingMedia.clear() } }
            val p = ReplyParts.split(raw, final = true).let { if (it.followups.isEmpty() && fu.isNotEmpty()) it.copy(followups = fu) else it }
            val think = listOf(thinking, p.thinking).filter { it.isNotBlank() }.joinToString("\n\n")
            val text = p.text.ifEmpty { "Done." }
            if (liveId >= 0) msgs = msgs.map {
                if (it.id == liveId) it.copy(role = Role.BLAZE, text = text, thinking = think, followups = p.followups, meta = meta, media = media) else it }
            else {
                val id = ids.getAndIncrement()
                msgs = msgs + ChatMessage(id, Role.BLAZE, text, thinking = think, followups = p.followups, meta = meta, media = media)
            }
        }

        /** Adds a chip (e.g. "Memory updated") to a reply's footer. */
        fun addMeta(id: Long, chip: String) {
            msgs = msgs.map { if (it.id == id) it.copy(meta = listOf(it.meta, chip).filter { s -> s.isNotBlank() }.joinToString(" · ")) else it }
        }

        /** Save the current chat to disk (screenshots are dropped to keep files small). */
        fun persist() {
            if (msgs.none { it.role == Role.USER }) return
            val arr = JSONArray()
            for (m in msgs) {
                arr.put(JSONObject().put("id", m.id).put("role", m.role.name).put("text", m.text)
                    .put("files", JSONArray(m.files)).apply {
                        historyMark[m.id]?.let { put("mark", it) }
                        if (m.thinking.isNotEmpty()) put("thinking", m.thinking.take(20000))
                        if (m.followups.isNotEmpty()) put("followups", JSONArray(m.followups))
                        if (m.meta.isNotEmpty()) put("meta", m.meta)
                        if (m.media.isNotEmpty()) put("media", JSONArray(m.media))
                    })
            }
            val hist = JSONArray()
            for (i in 0 until history.length()) hist.put(stripImages(history.getJSONObject(i)))
            val firstUser = msgs.first { it.role == Role.USER }.text
            if (title.isEmpty()) title = firstUser.lineSequence().first().take(48)
            val preview = msgs.lastOrNull { it.role == Role.BLAZE }?.text ?: firstUser
            val doc = JSONObject().put("id", id).put("title", title).put("pinned", pinned)
                .put("updated", System.currentTimeMillis()).put("preview", preview.take(90))
                .put("messages", arr).put("history", hist).put("summary", summary)
            if (this === cur) Settings.lastChatId = id
            io.launch { ioLock.withLock { ChatStore.save(doc) }; _chats.value = ChatStore.list() }
        }

        /** Images become a short note in saved history; the words stay. */
        fun stripImages(m: JSONObject): JSONObject {
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

        // ---------- Turns ----------

        /** A self-command handled on-device: shown and remembered like a normal exchange. */
        fun selfTurn(text: String, reply: String, byVoice: Boolean, chip: String = "⚡ Blaze itself") {
            add(Role.USER, text)
            history.put(JSONObject().put("role", "user").put("content", text))
            if (reply.isNotBlank()) {
                val id = add(Role.BLAZE, reply)
                addMeta(id, chip)
                history.put(JSONObject().put("role", "assistant").put("content", reply))
                if (byVoice && Settings.speakReplies && !reply.startsWith("Going quiet")) Voice.speak(reply)
            }
            persist()
        }

        fun send(text: String, byVoice: Boolean = false, attachments: List<Attachment> = emptyList()) {
            if ((text.isBlank() && attachments.isEmpty()) || job?.isActive == true) return
            Voice.lastTurnByVoice = byVoice
            val shown = text.trim().ifEmpty { "Take a look at this." }
            LlmClient.quickTurn = attachments.isEmpty() && shown.length < 80
            val id = add(Role.USER, shown, attachments.map { it.name })
            historyMark[id] = history.length()
            val content: Any = if (attachments.isEmpty()) shown else JSONArray().apply {
                put(JSONObject().put("type", "text").put("text", shown))
                attachments.forEach { a -> a.parts.forEach { put(it) } }
            }
            history.put(JSONObject().put("role", "user").put("content", content))
            persist()   // so it shows in the chat list right away, even if you leave
            val routine = shown.startsWith("[Scheduled routine")
            // Instant acknowledgment: the orb and a tick react before any thinking happens.
            mood = Mood.THINKING
            LlmClient.prewarm()   // speed plan step 10: open the connection while the fast lane checks
            if (!routine) com.blaze.assistant.device.Haptics.tick(app)
            if (byVoice && !routine) scope.launch {
                // No-hang voice: say something within about a second, then narrate long work.
                kotlinx.coroutines.delay(1100)
                if (job?.isActive != true || Voice.state.value != Voice.State.IDLE) return@launch
                Voice.say(listOf("On it.", "Sure.", "One sec.").random())
                var said = ""
                var waited = 0
                while (job?.isActive == true && waited < 600) {
                    kotlinx.coroutines.delay(1000); waited++
                    if (waited % 9 != 0) continue
                    val step = msgs.lastOrNull { it.role == Role.STEP }?.text.orEmpty().take(60)
                    if (step.isNotBlank() && step != said && Voice.state.value == Voice.State.IDLE) { said = step; Voice.say(step) }
                }
            }
            job = scope.launch {
                // Fast lane: direct commands run on-device in well under a second.
                val offline = !FastLane.online(app)
                if (attachments.isEmpty() && !routine) {
                    mood = Mood.THINKING
                    val fast = try { FastLane.tryHandle(app, shown, offline) { t, d, a -> confirm(t, d, a) } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    // "Draw a lion in sunglasses": straight to the free image service, no model call needed.
                    val pic = if (fast == null && !offline) com.blaze.assistant.media.Media.directPrompt(shown) else null
                    if (pic != null) {
                        mood = Mood.ACTING
                        add(Role.STEP, "Drawing \u201c${pic.take(60)}\u201d")
                        val t0 = System.currentTimeMillis()
                        val r = com.blaze.assistant.media.Media.generate(pic)
                        val reply = r.fold({ f -> synchronized(pendingMedia) { pendingMedia += f.absolutePath }; "Here you go. It's saved in your Gallery." },
                            { "I couldn't reach the image service just now (${it.message}). Try again in a moment." })
                        history.put(JSONObject().put("role", "assistant").put("content", if (r.isSuccess) "[Generated an image of: $pic, shown to the user and saved in their Gallery]" else reply))
                        addReply(-1, reply, "", meta = "\uD83C\uDFA8 free image · %.1f s".format((System.currentTimeMillis() - t0) / 1000.0))
                        mood = Mood.IDLE
                        persist(); announce()
                        return@launch
                    }
                    if (fast != null) {
                        history.put(JSONObject().put("role", "assistant").put("content", fast.reply))
                        addReply(-1, fast.reply, "", meta = if (fast.ms < 60) "⚡ instant · ${fast.ms} ms" else "⚡ on-device · ${fast.ms} ms")
                        mood = Mood.IDLE
                        persist(); announce()
                        // Not a child of this job: a child would keep the turn "busy" for 5 minutes and block every new prompt.
                        scope.launch { kotlinx.coroutines.delay(5 * 60_000 + 5_000); FastLane.trimIfIdle() }
                        return@launch
                    }
                }
                if (offline && !Settings.customBase.contains("127.0.0.1") && !Settings.customBase.contains("localhost")) {
                    add(Role.ERROR, "You're offline. I can still do direct commands like \u201copen WhatsApp\u201d, \u201cflashlight on\u201d or \u201cset a timer for 5 minutes\u201d.")
                    mood = Mood.IDLE; persist(); return@launch
                }
                runLoop()
            }
        }

        /**
         * Retry from a message. On a user message: resend it. On a reply or error: regenerate
         * the answer to the prompt before it. Everything after that prompt is discarded.
         */
        fun retry(messageId: Long) {
            val list = msgs
            val idx = list.indexOfFirst { it.id == messageId }
            if (idx < 0) return
            val userIdx = (idx downTo 0).firstOrNull { list[it].role == Role.USER } ?: return
            val user = list[userIdx]
            val mark = historyMark[user.id] ?: return
            stop()
            // Keep the original user turn (with any attachments) and drop everything after it.
            while (history.length() > mark + 1) history.remove(history.length() - 1)
            list.drop(userIdx + 1).forEach { historyMark.remove(it.id) }
            msgs = list.take(userIdx + 1)
            Voice.lastTurnByVoice = false
            job = scope.launch { runLoop() }
        }

        /** Only the newest screenshot is sent as an image; older ones become a note. */
        fun requestMessages(): JSONArray {
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt(lastUserText())))
            var lastShot = -1
            for (i in 0 until history.length()) if (isShot(history.getJSONObject(i))) lastShot = i
            // Speed plan step 3 (sliding window): long chats send only the recent part. The cut moves
            // in big jumps, not every turn, so the cached prefix stays the same for many turns.
            val from = windowStart()
            if (from > 0) msgs.put(JSONObject().put("role", "user").put("content",
                "[Earlier in this chat (older messages not shown): ${summary.ifBlank { "use search_past_chats if you need them" }}]"))
                .also { msgs.put(JSONObject().put("role", "assistant").put("content", "Got it.")) }
            var lastScreen = -1
            for (i in 0 until history.length()) if (history.getJSONObject(i).let { it.optString("role") == "tool" && it.optString("content").contains(SCREEN_MARK) }) lastScreen = i
            for (i in from until history.length()) {
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

        /**
         * Speak whole sentences while the rest of the reply is still streaming in. The last sentence
         * is always held back for announce(), which speaks it as the final utterance.
         */
        fun speakAsItStreams(text: String) {
            if (!(Voice.lastTurnByVoice && Settings.speakReplies && Settings.bool("speak_streaming", true))) return
            if (!text.startsWith(spokenPrefix)) return
            val ends = Regex("[.!?](\\s|$)|\n").findAll(text, spokenPrefix.length).map { it.range.first + 1 }
                .filter { e -> text.substring(e).isNotBlank() }.toList()
            val end = ends.lastOrNull() ?: return
            if (end - spokenPrefix.length < 2) return
            Voice.speakPart(text.substring(spokenPrefix.length, end))
            spokenPrefix = text.substring(0, end)
            streamedVoice = true
        }

        /** First history index to send: 0 for short chats, else a user message 24 to 48 messages back. */
        fun windowStart(): Int {
            val n = history.length()
            val keep = Settings.str("history_window", "24").toIntOrNull()?.coerceIn(8, 400) ?: 24
            if (n <= keep * 2) return 0
            // Jump in blocks of [keep] so the start only moves once every [keep] messages.
            var cut = ((n - keep) / keep) * keep
            while (cut < n) {
                val m = history.getJSONObject(cut)
                if (m.optString("role") == "user" && !isShot(m)) return cut
                cut++
            }
            return 0
        }

        fun isShot(m: JSONObject): Boolean {
            val c = m.opt("content") as? JSONArray ?: return false
            return c.optJSONObject(0)?.optString("text") == SHOT_NOTE
        }

        private var repairedThisStep: List<String> = emptyList()

        /** Rough closeness of two tool names, for "did you mean" hints. */
        private fun levGuess(a: String, b: String): Int {
            val x = a.lowercase(); val y = b.lowercase()
            val common = x.split('_').count { it.isNotBlank() && y.contains(it) }
            return kotlin.math.abs(x.length - y.length) - common * 6
        }

        suspend fun runLoop() {
            try {
                val allTools = tools().apply { put(ToolRouter.loadTool()) }
                val routine = lastUserText().startsWith("[Scheduled routine")
                // Speed plan step 1: core tools + the groups this request (and this chat) needs.
                val groupsOn = HashSet<String>().apply {
                    if (routine) addAll(ToolRouter.groups.map { it.id })
                    addAll(ToolRouter.guess(lastUserText()))
                    addAll(ToolRouter.usedIn(history))
                }
                var tools = ToolRouter.select(allTools, groupsOn)
                // Chat lane: small talk and plain questions = one light call with no tools.
                val lastBlaze = msgs.lastOrNull { it.role == Role.BLAZE }?.text.orEmpty()
                var lite = !routine && history.optJSONObject(history.length() - 1)?.opt("content") is String &&
                    ChatLane.fits(lastUserText(), lastBlaze)
                var firstMs = -1L
                streamedVoice = false
                while (trace.length() > 0) trace.remove(0)
                val turnThink = StringBuilder()
                val turnStart = System.currentTimeMillis()
                thinking = ""
                var gateLeft = 2
                var actedThisTurn = false
                val lastResults = ArrayList<String>()
                for (step in 0 until MAX_STEPS) {
                    currentStep = step + 1
                    mood = Mood.THINKING
                    var liveId = -1L
                    spokenPrefix = ""
                    val before = turnThink.toString()
                    var pendingFollowups = emptyList<String>()
                    val liteNow = lite
                    val onText: ((String) -> Unit)? = if (Settings.streamReplies) { partial ->
                        if (!(liteNow && partial.trimStart().let { it.startsWith("[") || it.startsWith("TOOLS", true) })) scope.launch {
                            val p = ReplyParts.split(partial)
                            if (p.thinking.isNotEmpty()) thinking = listOf(before, p.thinking).filter { it.isNotBlank() }.joinToString("\n\n")
                            if (p.text.isNotEmpty()) {
                                if (liveId < 0) liveId = add(Role.BLAZE, p.text) else replace(liveId, Role.BLAZE, p.text)
                                speakAsItStreams(p.text)
                            }
                        }
                    } else null
                    val onThink: (String) -> Unit = { t -> scope.launch { thinking = listOf(before, t).filter { it.isNotBlank() }.joinToString("\n\n") } }

                    LlmClient.liteTurn = lite
                    val req = if (lite) ChatLane.messages(requestMessages(), lastUserText(), personaText(), Settings.userName.ifBlank { null }) else requestMessages()
                    tools = if (lite) JSONArray() else ToolRouter.select(allTools, groupsOn)
                    // Watchdog: only a model that goes SILENT (no text, thinking or tool-call chunks) for the
                    // limit is cut off and retried once on the same model. Long thinking is fine while it streams.
                    val lane = if (step == 0) LlmClient.Lane.MAIN else LlmClient.Lane.STEPS
                    val limit = Settings.str("model_timeout_s", "100").toLongOrNull()?.coerceIn(30, 600)?.times(1000) ?: 100_000L
                    val reply = Agent.idleGuard(limit) { LlmClient.complete(req, tools, onText, onThink, lane) }
                        ?: run {
                            add(Role.STEP, "The model went silent, retrying")
                            if (liveId >= 0) { msgs = msgs.filterNot { it.id == liveId }; liveId = -1L }
                            Agent.idleGuard(limit) { LlmClient.complete(req, tools, onText, onThink, lane) }
                                ?: throw LlmClient.LlmException("${LlmClient.lastRoute.ifBlank { "The model" }} sent nothing for ${limit / 1000}s, twice. Try again, or pick a faster model in Settings → AI.")
                        }
                    if (firstMs < 0 && LlmClient.lastFirstTokenAt > 0) firstMs = (LlmClient.lastFirstTokenAt - turnStart).coerceAtLeast(0)
                    reply.optString("_reasoning").takeIf { it.isNotBlank() }?.let { if (turnThink.isNotEmpty()) turnThink.append("\n\n"); turnThink.append(it) }
                    reply.remove("_reasoning")
                    try { Usage.record(req, reply, LlmClient.lastUsage) } catch (_: Exception) { }
                    if (lite) {
                        lite = false
                        LlmClient.liteTurn = false
                        val said = (reply.opt("content") as? String).orEmpty()
                        if (ChatLane.escaped(said) || (reply.optJSONArray("tool_calls")?.length() ?: 0) > 0) {
                            // It needs tools after all: rerun this turn on the full lane.
                            if (liveId >= 0) { msgs = msgs.filterNot { it.id == liveId }; liveId = -1L }
                            continue
                        }
                    }
                    // Gatekeeper: a call written as text becomes a real call; glued/typo'd names,
                    // broken JSON and wrong types are repaired before anything runs or is sent back.
                    var unknownCalls: Set<String> = emptySet()
                    try {
                        if ((reply.optJSONArray("tool_calls")?.length() ?: 0) == 0) {
                            val txt = reply.opt("content") as? String
                            if (txt != null) ToolCalls.fromText(ReplyParts.split(txt).text, allTools)?.let { (c, rest) ->
                                reply.put("tool_calls", c); reply.put("content", if (rest.isBlank()) JSONObject.NULL else rest)
                                for (k in 0 until c.length()) ToolStats.add(c.getJSONObject(k).getJSONObject("function").getString("name"), "text_call")
                            }
                        }
                        val rep = ToolCalls.normalize(reply, allTools)
                        unknownCalls = rep.unknown
                        repairedThisStep = rep.repaired
                    } catch (e: Exception) { com.blaze.assistant.device.CrashLog.error("tool gatekeeper", e) }
                    kotlinx.coroutines.yield()
                    history.put(reply)

                    // Keep reasoning and follow-up tags out of what's sent back next turn.
                    (reply.opt("content") as? String)?.let { raw ->
                        val p = ReplyParts.split(raw, final = true)
                        if (p.text != raw.trim()) {
                            reply.put("content", p.text)
                            val parts = ReplyParts.split(raw, final = true)
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

                    if ((calls == null || calls.length() == 0) && step < MAX_STEPS - 4 && gateLeft > 0 && !routine) {
                        val nudge = when {
                            Autonomy.looksLikeRefusal(textOut) -> Autonomy.REFUSAL_NUDGE
                            actedThisTurn && Autonomy.lastFailed(lastResults) -> Autonomy.VERIFY_NUDGE
                            else -> null
                        }
                        if (nudge != null) {
                            gateLeft--
                            if (liveId >= 0) msgs = msgs.filterNot { it.id == liveId }
                            add(Role.STEP, "Trying another way")
                            history.put(JSONObject().put("role", "user").put("content", nudge))
                            lastResults.clear()
                            continue
                        }
                    }
                    if (calls == null || calls.length() == 0) {
                        kotlinx.coroutines.delay(60)   // let the last streamed chunk land first
                        val secs = (System.currentTimeMillis() - turnStart) / 1000.0
                    if (step > 0) { com.blaze.assistant.device.Haptics.done(app); com.blaze.assistant.device.Sounds.done() }
                        com.blaze.assistant.device.CrashLog.slow("turn ($step steps, ${LlmClient.lastRoute})", System.currentTimeMillis() - turnStart)
                        SpeedLog.add(SpeedLog.Entry(turnStart, firstMs.coerceAtLeast(0), System.currentTimeMillis() - turnStart, step + 1,
                            tools.length(), req.toString().length + tools.toString().length, LlmClient.lastRoute))
                        addReply(liveId, textOut, turnThink.toString(), pendingFollowups,
                            if (Settings.showRoute) "☁ " + listOf(LlmClient.lastRoute.ifBlank { Settings.model },
                                if (firstMs > 0) "first word %.1f s".format(firstMs / 1000.0) else "",
                                "%.1f s".format(secs),
                                if (step > 0) "${step + 1} steps" else "").filter { it.isNotBlank() }.joinToString(" · ") else "")
                        break
                    }
                    // Text that came with tool calls is narration, shown as a step; its reasoning joins the turn's.
                    val narr = ReplyParts.split(textOut)
                    if (narr.thinking.isNotEmpty()) { if (turnThink.isNotEmpty()) turnThink.append("\n\n"); turnThink.append(narr.thinking) }
                    thinking = turnThink.toString()
                    if (liveId >= 0) {
                        if (narr.text.isNotEmpty()) replace(liveId, Role.STEP, narr.text)
                        else msgs = msgs.filterNot { it.id == liveId }
                    } else if (narr.text.isNotEmpty()) add(Role.STEP, narr.text)

                    mood = Mood.ACTING
                    val shots = mutableListOf<String>()
                    // Speed plan step 8: several read-only lookups in one step run at the same time.
                    fun callName(i: Int) = calls.getJSONObject(i).getJSONObject("function").getString("name")
                    fun callArgs(i: Int) = try { JSONObject(calls.getJSONObject(i).getJSONObject("function").optString("arguments", "{}").ifBlank { "{}" }) }
                        catch (_: Exception) { JSONObject() }
                    val pre: Map<Int, String> = if (calls.length() > 1 && (0 until calls.length()).all { callName(it) in parallelSafe })
                        (0 until calls.length()).map { i -> scope.async { i to runTool(callName(i), callArgs(i), shots) } }
                            .map { it.await() }.toMap()
                    else emptyMap()
                    for (i in 0 until calls.length()) {
                        val call = calls.getJSONObject(i)
                        val fn = call.getJSONObject("function")
                        val name = fn.getString("name")
                        val args = try { JSONObject(fn.optString("arguments", "{}").ifBlank { "{}" }) }
                            catch (_: Exception) { JSONObject() }
                        var result = if (pre.containsKey(i)) pre.getValue(i) else if (name in unknownCalls) {
                            val close = ToolCalls.names(allTools).sortedBy { n -> levGuess(n, name) }.take(4)
                            "Error: there is no tool called \"$name\". Closest real tools: ${close.joinToString(", ")}. Call one of those (exact name), or find_tool to search."
                        } else if (name == "find_tool") {
                            add(Role.STEP, "Looking for the right tool")
                            val (txt, g) = Autonomy.findTool(allTools, args.optString("need"))
                            groupsOn += g
                            txt
                        } else if (name == "load_tools") {
                            val g = args.optJSONArray("groups")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
                            groupsOn += g
                            add(Role.STEP, "Loading ${g.joinToString(", ")} tools")
                            "Loaded: ${g.joinToString(", ")}. Those tools are available now."
                        } else if (name == "ask_user") runTool(name, args, shots)
                        else kotlinx.coroutines.withTimeoutOrNull(Settings.str("tool_timeout_s", "180").toLongOrNull()?.coerceIn(30, 900)?.times(1000) ?: 180_000L) { runTool(name, args, shots) }
                            ?: "Error: $name took too long and was stopped. Try a quicker way or run it as a background job." 
                        ToolStats.add(name, when {
                            name in unknownCalls -> "unknown"
                            repairedThisStep.any { it.startsWith(name) || it.contains("→ $name") || (it.startsWith("split") && it.contains(name)) } -> "repaired"
                            result.startsWith("Error") || result.startsWith("Couldn't") -> "error"
                            else -> "ok"
                        })
                        // A tool from a group pulls in the rest of that group (e.g. a stuck run_plan brings tap/scroll).
                        ToolRouter.groupOf(name)?.let { groupsOn += it }
                        if (name !in parallelSafe && name !in setOf("find_tool", "load_tools", "goal", "processes")) actedThisTurn = true
                        lastResults += result
                        if (name == "run_plan" || name == "read_screen") groupsOn += "screen"
                        if (name in screenActions && !result.startsWith("Error") && !result.startsWith("Couldn't")) com.blaze.assistant.device.Haptics.tick(app)
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
                LlmClient.liteTurn = false
                thinking = ""
                mood = Mood.IDLE
                currentStep = 0
                persist()
                announce()
                learnAfterTurn()
            }
        }

        /** Runs specialists: non-phone ones in parallel, phone ones one after another. */
        suspend fun delegate(tasks: JSONArray): String = kotlinx.coroutines.coroutineScope {
            val all = tools()
            val jobs = (0 until minOf(tasks.length(), 6)).map { i ->
                val o = tasks.getJSONObject(i)
                val spec = Specialists.byId(o.optString("specialist")) ?: Specialists.builtIn.first()
                Triple(i, spec, o.optString("task"))
            }
            val phoneLock = kotlinx.coroutines.sync.Mutex()
            val results = jobs.map { (i, spec, task) ->
                async {
                    add(Role.STEP, "${spec.name} started: ${task.take(60)}")
                    val r = try {
                        if ("phone" in spec.groups) phoneLock.withLock { runSpecialist(spec, task, all) } else runSpecialist(spec, task, all)
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { "Error: ${e.message}" }
                    add(Role.STEP, "${spec.name} finished")
                    "## ${i + 1}. ${spec.name}\n$r"
                }
            }.map { it.await() }
            results.joinToString("\n\n")
        }

        private suspend fun runSpecialist(spec: Specialists.Spec, task: String, all: JSONArray): String {
            val tools = Specialists.filter(all, spec)
            val msgs = JSONArray()
                .put(JSONObject().put("role", "system").put("content", "You are Blaze's ${spec.name} specialist. ${spec.prompt}\nIt is ${SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date())}." +
                    Memory.promptBlock(task) + "\nWhen done, reply with the result only."))
                .put(JSONObject().put("role", "user").put("content", task))
            val shots = mutableListOf<String>()
            for (step in 0 until 14) {
                val reply = LlmClient.complete(msgs, if (tools.length() > 0) tools else null, null, null, LlmClient.Lane.STEPS)
                reply.remove("_reasoning")
                try { Usage.record(msgs, reply, LlmClient.lastUsage) } catch (_: Exception) { }
                val unknown = try {
                    if ((reply.optJSONArray("tool_calls")?.length() ?: 0) == 0) (reply.opt("content") as? String)?.let { txt ->
                        ToolCalls.fromText(txt, all)?.let { (c, rest) -> reply.put("tool_calls", c); reply.put("content", if (rest.isBlank()) JSONObject.NULL else rest) } }
                    ToolCalls.normalize(reply, all).unknown
                } catch (_: Exception) { emptySet() }
                msgs.put(reply)
                val calls = reply.optJSONArray("tool_calls")
                if (calls == null || calls.length() == 0) return ReplyParts.split(reply.optString("content")).text.ifBlank { "(no result)" }
                for (i in 0 until calls.length()) {
                    val call = calls.getJSONObject(i)
                    val fn = call.getJSONObject("function")
                    val name = fn.getString("name")
                    val args = try { JSONObject(fn.optString("arguments", "{}").ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
                    var result = if (name in unknown) "Error: there is no tool called \"$name\". Use one of the tools you were given (exact name)." else runTool(name, args, shots)
                    if (name == "do_actions" || (name in screenActions && !result.contains(SCREEN_MARK))) result += screenAfter(result)
                    msgs.put(JSONObject().put("role", "tool").put("tool_call_id", call.optString("id", "s$step$i")).put("name", name).put("content", result.take(12000)))
                }
                shots.lastOrNull()?.let { url -> shots.clear()
                    msgs.put(JSONObject().put("role", "user").put("content", JSONArray()
                        .put(JSONObject().put("type", "text").put("text", SHOT_NOTE))
                        .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url))))) }
            }
            return "Stopped after 14 steps without finishing."
        }

        /** Fresh compact screen after an action, so the model doesn't spend a turn on read_screen. */
        suspend fun screenAfter(result: String): String {
            val svc = com.blaze.assistant.phone.BlazeAccessibilityService.instance ?: return ""
            if (!Settings.phoneControl || result.startsWith("Error") || result.startsWith("No app") || result.startsWith("Phone control is off")) return ""
            svc.settle(1500, 250)
            return "\n\nScreen now:\n" + svc.readScreen(diff = true) + com.blaze.assistant.phone.Watchers.drain()
        }

        /** Every tool call goes through here: a crash or an empty result becomes a plain error with a fix. */
        suspend fun runTool(name: String, args: JSONObject, shots: MutableList<String>): String {
            val r = try { runToolRaw(name, args, shots) }
                catch (e: CancellationException) { throw e }
                catch (e: Throwable) { com.blaze.assistant.device.CrashLog.error("tool $name", e); ToolGuide.crash(name, e) }
            return if (r.isBlank()) ToolGuide.empty(name) else r
        }

        /** Runs terminal work, then puts any new pictures / audio / video it wrote into the Gallery and this reply. */
        private suspend fun withMediaScan(block: suspend () -> String): String {
            val started = System.currentTimeMillis() - 2000
            try { com.blaze.assistant.media.Media.installHelpers(com.blaze.assistant.terminal.Terminal.home()) } catch (_: Exception) { }
            val out = block()
            val found = kotlinx.coroutines.withContext(Dispatchers.IO) {
                try {
                    val roots = listOf(com.blaze.assistant.terminal.Terminal.home(), java.io.File(com.blaze.assistant.terminal.Linux.rootfs(), "root"))
                    roots.filter { it.isDirectory }.flatMap { r ->
                        r.walkTopDown().maxDepth(4).onEnter { d -> d.name !in setOf("node_modules", "blaze_lib", "site-packages", ".cache", ".git", "phone") || d == r }
                            .filter { it.isFile && it.lastModified() >= started && it.length() > 1000 && com.blaze.assistant.media.Media.isMedia(it) }.toList()
                    }.distinctBy { it.canonicalPath }.sortedBy { it.lastModified() }.takeLast(6)
                } catch (_: Exception) { emptyList() }
            }
            if (found.isEmpty()) return out
            val names = found.mapNotNull { f -> try { com.blaze.assistant.media.Media.add(f).also { g -> synchronized(pendingMedia) { if (g.absolutePath !in pendingMedia) pendingMedia += g.absolutePath } }.name } catch (_: Exception) { null } }
            if (names.isNotEmpty()) add(Role.STEP, "Added to your Gallery: " + names.joinToString())
            return out + "\n[Shown to the user in the chat and saved in their Gallery: ${names.joinToString()}. No need to export or link them.]"
        }

        suspend fun runToolRaw(name: String, args: JSONObject, shots: MutableList<String>): String {
            val svc = com.blaze.assistant.phone.BlazeAccessibilityService.instance
            // Record phone actions (with the element's label, since numbers change) for task replay.
            if (name in setOf("open_app", "tap", "type_text", "scroll", "press_button", "run_plan", "do_actions", "search_in_app", "read_screen")) com.blaze.assistant.phone.Watchers.taskTick()
            if (name in setOf("open_app", "tap", "type_text", "scroll", "press_button", "open_url", "search_in_app") && trace.length() < 40) {
                val label = args.optString("text").takeIf { name == "tap" && it.isNotBlank() }
                    ?: if (name == "tap" || name == "type_text") svc?.labelOf(args.optInt("element")).orEmpty() else ""
                trace.put(JSONObject().put("tool", name).put("args", JSONObject(args.toString()).apply { remove("element") }).put("label", label))
            }
            return when (name) {
                "remember" -> { add(Role.STEP, "Remembering that"); Memory.add(args.optString("fact")) }
                "forget" -> { add(Role.STEP, "Forgetting that"); Memory.forget(args.optString("fact")) }
                "generate_image" -> if (Regex("(?i)\\b(pie|bar|line)\\s*chart|\\bgraph\\b|\\bplot\\b|\\bdiagram\\b|\\btable\\b|\\bpdf\\b|\\binfographic|\\bspreadsheet").containsMatchIn(args.optString("prompt"))) {
                    "Refused: the AI picture tool can't draw exact charts, tables or documents (it makes a random look-alike). Make it with run_code (Python matplotlib / reportlab) and save under /root/phone/. " +
                        "If the terminal isn't installed yet, call linux_install first and wait for it, then run_code. Never send the user an AI picture instead of a real chart."
                } else {
                    add(Role.STEP, "Drawing \u201c${args.optString("prompt").take(60)}\u201d")
                    com.blaze.assistant.media.Media.generate(args.optString("prompt"), args.optInt("width", 1024), args.optInt("height", 1024),
                        if (args.has("seed")) args.optInt("seed") else null, args.optString("model")).fold(
                        { f -> synchronized(pendingMedia) { pendingMedia += f.absolutePath }
                            "Image made and shown to the user in the chat (saved in their Gallery as ${f.name}). Reply with one short line about it; don't describe it at length or paste a link." },
                        { e -> "Image service failed (${e.message}). Try once more with a simpler prompt, or draw it with run_code (Python + Pillow) and save it under /root/phone/." })
                }
                "gallery" -> {
                    val M = com.blaze.assistant.media.Media
                    when (args.optString("action")) {
                        "add" -> {
                            val f = com.blaze.assistant.terminal.DevTools.resolve(args.optString("path"))
                            if (!f.isFile) "No such file: ${args.optString("path")}"
                            else { val g = M.add(f, args.optString("title")); synchronized(pendingMedia) { if (g.absolutePath !in pendingMedia) pendingMedia += g.absolutePath }
                                add(Role.STEP, "Added ${g.name} to your Gallery"); "Added to the Gallery and shown in the chat as ${g.name}." }
                        }
                        "save" -> M.find(args.optString("path"))?.let { add(Role.STEP, "Saving ${it.name} to your phone"); M.saveToPhone(it) } ?: "Not in the gallery: ${args.optString("path")}. Call gallery list."
                        "share" -> M.find(args.optString("path"))?.let { app.startActivity(M.shareIntent(it)); "Opened the share sheet for ${it.name}." } ?: "Not in the gallery."
                        "delete" -> M.find(args.optString("path"))?.let { f -> if (confirm("Delete from Gallery?", f.name, "Delete")) { M.delete(f); "Deleted ${f.name}." } else "The user did NOT approve deleting it." } ?: "Not in the gallery."
                        else -> M.list()
                    }
                }
                "web_search" -> { add(Role.STEP, "Searching the web for \u201c${args.optString("query").take(50)}\u201d"); Web.search(args.optString("query")) }
                "fetch_page" -> { add(Role.STEP, "Reading ${args.optString("url").removePrefix("https://").take(50)}"); Web.fetch(args.optString("url")) }
                "ask_user" -> {
                    val opts = args.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
                    add(Role.STEP, "Asked: ${args.optString("question").take(80)}")
                    mood = Mood.IDLE
                    val a = askUser(args.optString("question"), opts, title)
                    mood = Mood.ACTING
                    add(Role.STEP, "You answered: ${a.take(80)}")
                    "The user answered: $a"
                }
                in com.blaze.assistant.proactive.Triggers.names -> { add(Role.STEP, if (name == "create_trigger") "Setting up \u201c${args.optString("name")}\u201d" else "Checking triggers")
                    com.blaze.assistant.proactive.Triggers.run(name, args) }
                "run_shell" -> {
                    val cmd = args.optString("command")
                    add(Role.STEP, "$ " + cmd.take(70))
                    if (com.blaze.assistant.terminal.Terminal.risky(cmd) && !confirm("Run this command?", cmd.take(200), "Run"))
                        "The user did NOT approve running that command."
                    else if (args.optBoolean("background")) kotlinx.coroutines.withContext(Dispatchers.IO) { com.blaze.assistant.terminal.Terminal.startJob(cmd, args.optBoolean("linux"), args.optInt("max_minutes", 0)) }
                    else withMediaScan { com.blaze.assistant.terminal.Terminal.run(cmd, args.optInt("timeout_seconds", 60), args.optBoolean("linux")) }
                }
                "run_ussd" -> {
                    val code = args.optString("code")
                    add(Role.STEP, "Dialling $code")
                    if (com.blaze.assistant.phone.Ussd.risky(code) && !confirm("Dial $code?", "This looks like a mobile money or multi-step code.", "Dial"))
                        "The user did NOT approve dialling $code."
                    else com.blaze.assistant.phone.Ussd.run(app, code)
                }
                in com.blaze.assistant.terminal.DevTools.names -> {
                    add(Role.STEP, when (name) {
                        "run_code" -> "Running ${args.optString("language", "code")}"
                        "file_write", "file_edit" -> "Writing ${args.optString("path").takeLast(50)}"
                        "file_read", "file_list" -> "Reading ${args.optString("path", "~").takeLast(50)}"
                        "http_request" -> "${args.optString("method", "GET").uppercase()} ${args.optString("url").removePrefix("https://").take(50)}"
                        "download_file" -> "Downloading ${args.optString("url").substringAfterLast('/').take(50)}"
                        else -> name.replace('_', ' ')
                    })
                    if (name == "file_delete" && !confirm("Delete this?", args.optString("path").take(200), "Delete")) "The user did NOT approve deleting that."
                    else if (name == "run_code") withMediaScan { com.blaze.assistant.terminal.DevTools.run(app, name, args) }
                    else com.blaze.assistant.terminal.DevTools.run(app, name, args)
                }
                in com.blaze.assistant.phone.PimEdit.names -> {
                    val (title, detail) = com.blaze.assistant.phone.PimEdit.describe(name, args)
                    add(Role.STEP, title.removeSuffix("?"))
                    if (!confirm(title, detail.take(200), if (name == "delete_event") "Delete" else "Save")) "The user did NOT approve that change."
                    else kotlinx.coroutines.withContext(Dispatchers.IO) { com.blaze.assistant.phone.PimEdit.run(app, name, args) }
                }
                com.blaze.assistant.terminal.Git.NAME -> {
                    add(Role.STEP, "git ${args.optString("action")} ${args.optString("repo").ifBlank { args.optString("branch") }}".trim())
                    if (com.blaze.assistant.terminal.Git.risky(args) && !confirm("Run this git command?", "git " + args.optString("args").take(200), "Run")) "The user did NOT approve that git command."
                    else com.blaze.assistant.terminal.Git.run(args)
                }
                com.blaze.assistant.phone.Location.NAME -> { add(Role.STEP, "Checking location"); kotlinx.coroutines.withContext(Dispatchers.IO) { com.blaze.assistant.phone.Location.run(app) } }
                "linux_install" -> { add(Role.STEP, "Installing Alpine Linux"); com.blaze.assistant.terminal.Linux.install() }
                "terminal_jobs" -> kotlinx.coroutines.withContext(Dispatchers.IO) {
                    if (args.optInt("id", 0) > 0) com.blaze.assistant.terminal.Terminal.jobLog(args.optInt("id"), args.optInt("lines", 80)) else com.blaze.assistant.terminal.Terminal.listJobs() }
                "preview_server" -> {
                    val port = args.optInt("port")
                    val url = "http://127.0.0.1:$port" + args.optString("path").let { if (it.isBlank()) "" else if (it.startsWith("/")) it else "/$it" }
                    add(Role.STEP, "Opening localhost:$port")
                    val r = try { com.blaze.assistant.browser.BrowserEngine.open(app, url, true) } catch (e: CancellationException) { throw e } catch (e: Exception) { "Error: ${e.message}" }
                    kotlinx.coroutines.withContext(Dispatchers.Main) { com.blaze.assistant.browser.BrowserTools.show(app) }
                    if (r.contains("ERR_CONNECTION_REFUSED", true) || r.contains("net::ERR", true)) "Nothing is answering on port $port yet. Check the job with terminal_jobs, or start the server with run_shell background=true.\n$r" else r
                }
                "self_test" -> { add(Role.STEP, "Running a self-check"); com.blaze.assistant.device.SelfCheck.report(app) }
                "terminal_stop" -> com.blaze.assistant.terminal.Terminal.stopJob(args.optInt("id"))
                "termux_run" -> { add(Role.STEP, "Termux: " + args.optString("command").take(60)); com.blaze.assistant.terminal.Terminal.runInTermux(app, args.optString("command"), args.optBoolean("background", true)) }
                "delegate" -> delegate(args.optJSONArray("tasks") ?: JSONArray())
                "recall_memory" -> { add(Role.STEP, "Checking memory"); Memory.recall(args.optString("query")) }
                "blaze_self" -> {
                    val act = args.optString("action")
                    add(Role.STEP, when (act) { "setup_check", "status" -> "Checking my setup"; "install" -> "Installing ${args.optString("what")}"; "open" -> "Opening ${args.optString("target")}"; else -> "Adjusting myself: $act" })
                    if (act == "install") SelfSetup.install(app, args.optString("what")) else SelfControl.run(app, act, args)
                }
                "device_status" -> { add(Role.STEP, "Checking the phone"); com.blaze.assistant.device.DeviceStatus.describe(app) }
                "look_at_screen" -> {
                    add(Role.STEP, "Looking at a screenshot")
                    val shot = PhoneTools.screenshot()
                    if (shot.startsWith("data:")) { shots += shot; "Screenshot taken. It is attached in the next message." } else shot
                }
                in com.blaze.assistant.phone.AndroidControl.names -> {
                    add(Role.STEP, name.replace('_', ' ').replaceFirstChar { it.uppercase() })
                    try { kotlinx.coroutines.withContext(Dispatchers.Main) { com.blaze.assistant.phone.AndroidControl.run(app, name, args) } } catch (e: CancellationException) { throw e } catch (e: Exception) { "Error: ${e.message}" }
                }
                in SkillPacks.names -> {
                    add(Role.STEP, when (name) { "use_skill" -> "Reading skill \u201c${args.optString("name")}\u201d"; "install_skill" -> "Installing skills"; "write_skill" -> "Saving a skill"; else -> "Reading a skill file" })
                    try { SkillPacks.run(name, args) } catch (e: CancellationException) { throw e } catch (e: Exception) { "Error: ${e.message}" }
                }
                "watch" -> { add(Role.STEP, "Setting a watcher"); com.blaze.assistant.phone.Watchers.run(args) }
                "goal" -> { add(Role.STEP, if (args.optString("action") == "add") "Planning \u201c${args.optString("title").take(40)}\u201d" else "Updating the plan"); Autonomy.goal(args) }
                "standing_permission" -> { add(Role.STEP, "Updating permissions"); Autonomy.setPermission(args) }
                in Processes.names -> {
                    add(Role.STEP, if (name == "processes") "Checking what's running" else "${args.optString("action").replaceFirstChar { it.uppercase() }} ${args.optString("id")}")
                    try { Processes.run(app, name, args) } catch (e: CancellationException) { throw e } catch (e: Exception) { "Error: ${e.message}" }
                }
                "create_routine", "list_routines", "delete_routine", "schedule_command" -> {
                    add(Role.STEP, if (name == "create_routine" || name == "schedule_command") "Scheduling \"${args.optString("name")}\"" else "Checking routines")
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
                com.blaze.assistant.phone.Messaging.NAME -> { add(Role.STEP, "Messaging ${args.optString("to")}"); com.blaze.assistant.phone.Messaging.run(app, args) }
                in com.blaze.assistant.browser.BrowserTools.names -> {
                    add(Role.STEP, com.blaze.assistant.browser.BrowserTools.describe(name, args))
                    com.blaze.assistant.browser.BrowserTools.run(app, name, args)
                }
                "reply_to_notification" -> { add(Role.STEP, "Replying"); com.blaze.assistant.notify.NotificationBrain.reply(app, args.optInt("number"), args.optString("text")) }
                "run_plan" -> runPlan(args)
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
                    val label = args.optString("text").ifBlank { svc?.labelOf(args.optInt("element")).orEmpty() }
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


        /**
         * v1.9 fast path: run a whole script of steps the model planned, addressed by visible text
         * instead of element numbers. Each step is checked locally (target found, screen changed,
         * expected text appeared) — no model call until the script finishes or gets stuck.
         */
        suspend fun runPlan(args: JSONObject): String {
            val svc = com.blaze.assistant.phone.BlazeAccessibilityService.instance
                ?: return "Phone control isn't connected. Ask the user to turn on Blaze in Accessibility settings."
            val steps = args.optJSONArray("steps") ?: return "Error: no steps."
            val n = minOf(steps.length(), 15)
            fun desc(st: JSONObject): String = when (st.optString("do")) {
                "open_app" -> "Open ${st.optString("app").ifBlank { st.optString("target") }}"
                "tap" -> "Tap \u201c${st.optString("target").take(30)}\u201d"
                "long_press" -> "Hold \u201c${st.optString("target").take(30)}\u201d"
                "type" -> "Type \u201c${st.optString("text").take(30)}\u201d" + if (st.optBoolean("submit")) " and search" else ""
                "scroll" -> "Scroll ${st.optString("direction", "down")}"
                "back" -> "Go back"; "home" -> "Go home"; "enter" -> "Press Enter"
                "wait" -> "Wait"
                "search_in_app" -> "Search ${st.optString("app")} for \u201c${st.optString("query").take(30)}\u201d"
                else -> st.optString("do")
            }
            val labels = (0 until n).map { desc(steps.getJSONObject(it)) }
            val state = MutableList(n) { 0 }   // 0 todo, 1 running, 2 done, 3 failed, 4 skipped
            fun board() = labels.mapIndexed { i, l -> (when (state[i]) { 2 -> "\u2713 "; 1 -> "\u2192 "; 3 -> "\u2715 "; 4 -> "\u2013 "; else -> "\u00b7 " }) + l }.joinToString("\n")
            val boardId = add(Role.STEP, board())
            val started = System.currentTimeMillis()
            for (i in 0 until n) {
                val st = steps.getJSONObject(i)
                state[i] = 1; replace(boardId, Role.STEP, board())
                val t0 = System.currentTimeMillis()
                val pkgBefore = svc.currentPkg()
                val target = st.optString("target")
                var fail: String? = null
                try {
                    when (st.optString("do")) {
                        "open_app" -> {
                            val r = PhoneTools.run(app, "open_app", JSONObject().put("app_name", st.optString("app").ifBlank { target }))
                            if (r.startsWith("No app") || r.startsWith("Error") || r.startsWith("Couldn't")) fail = r
                            else {
                                val end = System.currentTimeMillis() + 3000
                                while (svc.currentPkg() == pkgBefore && System.currentTimeMillis() < end) kotlinx.coroutines.delay(100)
                                svc.settle(1500, 250)
                            }
                        }
                        "tap", "long_press" -> {
                            var id = svc.waitFor(target, 3000)
                            if (id == null) { svc.scroll("down", null); svc.settle(600, 150); id = svc.waitFor(target, 1200) }
                            if (id == null) fail = "couldn't find \u201c$target\u201d on screen"
                            else {
                                val label = svc.labelOf(id)
                                if (Settings.confirmRisky && risky.containsMatchIn(label) &&
                                    !confirm("Tap \u201c${label.take(40)}\u201d?", "Blaze wants to tap this in ${svc.currentApp() ?: "the app"}.", "Tap"))
                                    return "The user did NOT approve tapping \"$label\" (step ${i + 1}). Don't retry; tell them it's ready for them to finish."
                                svc.flash(id)
                                val r = svc.tap(id, st.optString("do") == "long_press")
                                if (r.startsWith("Couldn't") || r.startsWith("No element")) fail = r
                                trace.put(JSONObject().put("tool", "tap").put("args", JSONObject().put("text", label)).put("label", label))
                            }
                        }
                        "type" -> {
                            val id = if (target.isNotBlank()) svc.waitFor(target, 3000, wantField = true) ?: svc.anyField() else svc.anyField()
                            if (id == null) fail = "no text field to type into"
                            else {
                                svc.flash(id)
                                val r = svc.type(id, st.optString("text"), st.optBoolean("submit"))
                                if (r.startsWith("Couldn't") || r.startsWith("No element")) fail = r
                                trace.put(JSONObject().put("tool", "type_text").put("args", JSONObject().put("text", st.optString("text")).put("submit", st.optBoolean("submit"))).put("label", svc.labelOf(id)))
                            }
                        }
                        "enter" -> if (!svc.pressEnter()) fail = "couldn't press Enter"
                        "scroll" -> { val r = svc.scroll(st.optString("direction", "down"), null); if (r.startsWith("Couldn't")) fail = r }
                        "back" -> svc.press("back")
                        "home" -> svc.press("home")
                        "wait" -> kotlinx.coroutines.delay((st.optDouble("seconds", 1.0).coerceIn(0.2, 10.0) * 1000).toLong())
                        "search_in_app" -> {
                            val r = com.blaze.assistant.phone.AppSearch.run(app, st.optString("app"), st.optString("query"), st.optBoolean("play"))
                            if (r.startsWith("Error") || r.startsWith("No app")) fail = r
                        }
                        else -> fail = "unknown step \u201c${st.optString("do")}\u201d"
                    }
                    if (fail == null && st.optString("do") !in setOf("wait", "open_app")) svc.settle(1200, 180)
                    val expect = st.optString("expect")
                    if (fail == null && expect.isNotBlank() && svc.waitFor(expect, 3500) == null) fail = "expected \u201c$expect\u201d to appear but it didn't"
                } catch (e: CancellationException) { throw e } catch (e: Exception) { fail = "error: ${e.message}" }
                Traces.log(svc.currentPkg(), st, fail == null, System.currentTimeMillis() - t0, fail ?: "")
                if (fail != null && st.optBoolean("optional")) { state[i] = 4; replace(boardId, Role.STEP, board()); continue }
                if (fail != null) {
                    state[i] = 3; replace(boardId, Role.STEP, board())
                    return "Stopped at step ${i + 1} (${labels[i]}): $fail. Steps 1-$i worked.\nUse the element numbers below to continue, or send a new run_plan from here.\n\n" + svc.readScreen()
                }
                state[i] = 2; replace(boardId, Role.STEP, board())
                com.blaze.assistant.device.Haptics.tick(app)
            }
            val words = svc.visibleWords().filter { it.length in 2..60 }.take(25)
            return "All $n steps done in ${"%.1f".format((System.currentTimeMillis() - started) / 1000.0)} s. Now in ${svc.currentApp() ?: "?"}. On screen: ${words.joinToString(" · ")}\n(Call read_screen only if you need to act further on this screen.)"
        }

        /** After a turn: summary, memories and skills in the background (see Learning). */
        fun learnAfterTurn() {
            if (!Settings.autoLearn) return
            val reply = msgs.lastOrNull { it.role == Role.BLAZE } ?: return
            if (reply.meta.startsWith("⚡")) return
            val all = msgs
            val lastUser = msgs.indexOfLast { it.role == Role.USER }; if (lastUser < 0) return
            val exchange = msgs.drop(maxOf(0, lastUser - 4)).joinToString("\n") { m ->
                when (m.role) { Role.USER -> "User: "; Role.BLAZE -> "Blaze: "; Role.STEP -> "  (step) "; Role.ERROR -> "  (error) " } + m.text.take(1500) }
            if (msgs.drop(lastUser).any { it.role == Role.ERROR }) return
            val t = JSONArray(trace.toString())
            val cid = id
            val hasSummary = summary.isNotBlank()
            io.launch {
                val chip = Learning.review(cid, exchange, t, hasSummary) { _, s -> scope.launch { summary = s; persist() } }
                if (chip != null) scope.launch { addMeta(reply.id, chip); persist() }
            }
        }

        /** When Blaze finishes while you're in another app, post its answer as a notification. */
        fun announce() {
            if (sessions.values.none { it !== this && it.job?.isActive == true }) Notifier.doneWorking()
            val last = msgs.lastOrNull() ?: return
            if (last.role != Role.BLAZE && last.role != Role.ERROR) return
            if (Voice.lastTurnByVoice && Settings.speakReplies) {
                if (streamedVoice) {
                    val rest = if (spokenPrefix.isNotEmpty() && last.text.startsWith(spokenPrefix)) last.text.substring(spokenPrefix.length) else if (spokenPrefix.isEmpty()) last.text else ""
                    Voice.speak(rest.ifBlank { " " }, queue = true)
                } else Voice.speak(last.text)
            }
            try { com.blaze.assistant.widget.BlazeWidget.update(app, last.text.take(240)) } catch (_: Exception) { }
            if (this === cur && (inForeground || overlayOpen)) return
            // A chat you left kept working: say which one finished.
            Notifier.reply(if (this === cur) last.text else "${title.ifBlank { "A chat" }}: ${last.text}", last.role == Role.ERROR)
        }
    }
}
