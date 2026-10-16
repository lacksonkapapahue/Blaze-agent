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
                       val media: List<String> = emptyList(),
                       /** When it was added (ms); 0 for chats saved before v1.13. */
                       val at: Long = System.currentTimeMillis(),
                       /** v0.26.1: how long the model's reasoning streamed (ms), for "Thought it through · 4 s"; 0 = not measured. */
                       val thinkMs: Long = 0L,
                       /** v0.28.4: on the first step of a turn, when that turn really started (ms; a retry or a wake starts a new one); 0 = unknown. */
                       val startAt: Long = 0L)

/** Splits a raw reply into what's shown, hidden reasoning (<think> tags) and follow-up suggestions. */
object ReplyParts {
    private val thinkRe = Regex("<(think|thought|thinking)>([\\s\\S]*?)(</\\1>|$)", RegexOption.IGNORE_CASE)
    // Tolerant: "<<follow-ups: a | b>>", "<</follow-ups: a | b>>", "<follow ups: a|b>", "<<followups:a|b" at the end.
    private val fuRe = Regex("<{1,2}\\s*/?\\s*follow[\\s_-]?ups?\\s*:([\\s\\S]*?)(>{1,2}|$)", RegexOption.IGNORE_CASE)

    data class Parts(val text: String, val thinking: String, val followups: List<String>)

    fun split(raw0: String, final: Boolean = false, keepCalls: Boolean = false): Parts {
        // v0.23.2: leaked channel / template tokens and reasoning leads go first (shared with streaming and storage).
        val clean = ReplyClean.clean(raw0, final)
        // v0.28.1: tool calls written as text ("[/find_tool need=…]") never reach the screen, speech or history;
        // only the gatekeeper reads them (keepCalls) to run them as real calls.
        val raw = if (keepCalls) clean.text else ToolLeak.strip(clean.text, final)
        val thinking = (listOf(clean.thinking) + thinkRe.findAll(raw).map { it.groupValues[2].trim() }).filter { it.isNotBlank() }.joinToString("\n\n")
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
data class Attachment(val name: String, val parts: List<JSONObject>, val thumb: String? = null)

/** The brain: keeps the conversation and runs the think → act → look loop. */object Agent {
    private const val MAX_STEPS = 30
    private const val SHOT_NOTE = "[Screenshot of the phone screen]"
    // v0.30.4: screen readouts are found by FastActions.isScreen (the old "Foreground app:" mark hadn't matched any
    // readout since v1.9.7, so old screens were never dropped from the prompt).
    /** Read-only tools that are safe to run side by side. */
    private val parallelSafe = setOf("web_search", "fetch_page", "recall_memory", "memory_search", "search_past_chats", "gmail_search", "gmail_read",
        "gcal_events", "calendar_events", "drive_search", "gcontacts_search", "find_contact", "recent_notifications", "read_messages",
        "recent_calls", "device_status", "battery_level", "github_issues", "github_my_prs", "github_notifications", "github_search_repos",
        "notion_search", "slack_read", "slack_channels", "search_screen_memory", "spotify_now_playing", BlazeDocs.TOOL_SEARCH, BlazeDocs.TOOL_READ,
        ProjectDocs.TOOL_SEARCH, ProjectDocs.TOOL_READ, com.blaze.assistant.home.HomeTools.LIST)

    private val screenActions = setOf("tap", "type_text", "scroll", "press_button", "open_app", "open_url", "search_in_app")
    private val ids = AtomicLong(1)
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + com.blaze.assistant.device.CrashLog.guard)

    /** Text or a file shared into Blaze from another app, waiting in the message box for the user's Send. */
    data class SharedDraft(val text: String, val attachment: Attachment? = null)
    val sharedDraft = MutableStateFlow<SharedDraft?>(null)

    /** Messages of the chat on screen. */
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _mood = MutableStateFlow(Mood.IDLE)
    val mood: StateFlow<Mood> = _mood

    /** The model's reasoning so far in the current turn (shown live under the brain). */
    private val _thinking = MutableStateFlow("")
    val thinking: StateFlow<String> = _thinking
    /** What Blaze is doing right now, in plain words ("Searching the web for …"); "" when idle. Never a command. */
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    /** Token meter of the chat on screen (v0.15.0): what the last model call sent and got back. */
    data class Meter(val inTok: Int, val outTok: Int, val limit: Int) {
        val text get() = LongChat.meter(inTok, outTok)
        val high get() = LongChat.high(inTok, limit)
    }
    private val _meter = MutableStateFlow<Meter?>(null)
    val meter: StateFlow<Meter?> = _meter

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
    suspend fun confirm(title0: String, detail0: String, action: String, always: Boolean = false): Boolean {
        val tag = kotlin.coroutines.coroutineContext[BgAgents.Tag]
        val title = if (tag != null) "${tag.id} · $title0" else title0
        // v0.28.2 (ConfirmGate): [always] = outbound in the user's name (texts, calls, messages, email, posts, money,
        // deletes, installs, phone settings): the card always shows. A turn that read untrusted content (web, messages,
        // notifications, the screen, shared files…) also loses standing OKs and Autopilot for destructive actions.
        val source = kotlin.coroutines.coroutineContext[ConfirmGate.Taint]?.source
        when (ConfirmGate.decide(always, Modes.destructive(title0, detail0), !always && Autonomy.preApproved(title0, detail0), Modes.autopilot, source != null)) {
            ConfirmGate.Decision.STANDING_OK -> { cur.note("Standing OK: $title", false); return true }
            // Autopilot: the user is away, so ordinary confirms pass. Destructive ones still wait for their tap.
            ConfirmGate.Decision.AUTOPILOT_OK -> { (if (tag != null) null else cur)?.note("Autopilot approved: $title0", false); return true }
            ConfirmGate.Decision.SHOW -> { }
        }
        val detail = ConfirmGate.detail(detail0, source)
        val d = kotlinx.coroutines.CompletableDeferred<Boolean>()
        tag?.let { BgAgents.setWaiting(it.id, true) }
        _confirm.value = PendingConfirm(title, detail, action, d)
        if (!inForeground) Notifier.confirm(title, detail, action)
        val ok = kotlinx.coroutines.withTimeoutOrNull(120_000) { d.await() } ?: false
        tag?.let { BgAgents.setWaiting(it.id, false) }
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
    suspend fun askUser(question0: String, options: List<String>, chat: String): String = askLock.withLock {
        val tag = kotlin.coroutines.coroutineContext[BgAgents.Tag]
        val question = if (tag != null) "${tag.id} asks: $question0" else question0
        val d = kotlinx.coroutines.CompletableDeferred<String>()
        tag?.let { BgAgents.setWaiting(it.id, true) }
        _question.value = PendingQuestion(question, options.filter { it.isNotBlank() }.take(5), chat, d)
        var popped = false
        if (!inForeground && !overlayOpen) {
            if (com.blaze.assistant.ui.Overlay.canDrawOverApps(app)) {
                kotlinx.coroutines.withContext(Dispatchers.Main) { com.blaze.assistant.ui.Overlay.openPanel(app, false) }; popped = true
            } else Notifier.question(question)
        }
        if (Voice.lastTurnByVoice && Voice.shouldSpeak()) Voice.speak(question)
        // Autopilot: the card stays up for a minute in case they're there, then Blaze decides itself.
        val ap = Modes.autopilot
        val a = kotlinx.coroutines.withTimeoutOrNull(if (ap) 60_000L else 15 * 60_000L) { d.await() }
        tag?.let { BgAgents.setWaiting(it.id, false) }
        _question.value = null
        Notifier.clearQuestion()
        if (popped && a != null) kotlinx.coroutines.withContext(Dispatchers.Main) { com.blaze.assistant.ui.Overlay.closePanel() }
        a ?: if (ap) Modes.AUTO_PREFIX + Modes.AUTO_REPLY else "(The user didn't answer within 15 minutes. Stop and say what you need.)"
    }

    fun answerQuestion(text: String) { _question.value?.answer?.complete(text) }

    // ---------- v0.20.0: secure forms for the background browser ----------
    private val _form = MutableStateFlow<com.blaze.assistant.browser.SecureInput.Request?>(null)
    val pendingForm: StateFlow<com.blaze.assistant.browser.SecureInput.Request?> = _form
    private val formLock = kotlinx.coroutines.sync.Mutex()
    @Volatile private var formSession: Session? = null

    /**
     * Shows the secure form card and waits (15 minutes at most). When Blaze isn't on screen an
     * "Action required" notification opens the chat at the card. Null = cancelled or no answer.
     */
    suspend fun askSecure(req: com.blaze.assistant.browser.SecureInput.Request, from: Session?): com.blaze.assistant.browser.SecureInput.Answer? = formLock.withLock {
        val tag = kotlin.coroutines.coroutineContext[BgAgents.Tag]
        tag?.let { BgAgents.setWaiting(it.id, true) }
        formSession = from
        _form.value = req
        if (!inForeground) Notifier.actionRequired(req.title, req.site)
        // v0.30.5 (Luke: "the form wouldn't close until I force quit"): the task being stopped while the card was up
        // cancelled this wait, and the clean-up below never ran, so the card stayed with a Cancel that answered nobody.
        // The clean-up is now in finally, and Cancel / Stop remove the card themselves.
        try {
            kotlinx.coroutines.withTimeoutOrNull(15 * 60_000L) { req.answer.await() }
        } finally {
            tag?.let { BgAgents.setWaiting(it.id, false) }
            clearForm(req)
        }
    }

    private fun clearForm(req: com.blaze.assistant.browser.SecureInput.Request) {
        if (_form.value?.id == req.id) { _form.value = null; formSession = null; Notifier.clearAction() }
    }

    /** Fill in / Cancel. The card goes at once, even if nothing is waiting on it any more. */
    fun answerForm(a: com.blaze.assistant.browser.SecureInput.Answer?) {
        val r = _form.value ?: return
        r.answer.complete(a)
        clearForm(r)
    }

    /** Stopping a chat also takes down the form it was waiting on. */
    private fun dropFormOf(s: Session) {
        val r = _form.value ?: return
        if (formSession === s || formSession == null) { r.answer.complete(null); clearForm(r) }
    }

    /** The "Action required" notification was tapped: show the chat that's waiting on the form. */
    fun showFormChat() { formSession?.let { s -> if (s !== cur) show(s) } }

    private val risky = Regex("\\b(send|pay|buy|purchase|order|checkout|check out|delete|remove|post|publish|transfer|confirm|subscribe|place order|book)\\b", RegexOption.IGNORE_CASE)

    private val _chats = MutableStateFlow<List<ChatMeta>>(emptyList())
    val chats: StateFlow<List<ChatMeta>> = _chats

    private val _chatId = MutableStateFlow("")
    val chatId: StateFlow<String> = _chatId

    /** Every chat that's open or still working, by id. */
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, Session>()
    @Volatile private var cur: Session = Session("boot")

    /** v0.28.4: when the open chat's current turn started (the live work card's clock before its first step). */
    val turnStartAt: Long get() = cur.turnAt

    private fun show(s: Session) {
        cur = s
        sessions[s.id] = s
        _chatId.value = s.id
        _messages.value = s.msgs
        _mood.value = s.mood
        _thinking.value = s.thinking
        _status.value = s.status
        _meter.value = s.meter
        // v0.28.3: only the main thread is remembered as the chat to reopen (project chats live in Projects).
        if (!s.isPrivate) Settings.lastChatId = Mind.MAIN
        _private.value = s.isPrivate
        // Forget idle chats in the background; working ones stay until they finish.
        sessions.values.removeAll { it !== s && !it.busy }
    }

    fun init(ctx: Context) {
        app = ctx.applicationContext
        // v0.26.2: the built-in specialists' field playbooks ship in assets/specialists/<id>.md.
        Playbooks.loader = { id -> runCatching { app.assets.open("specialists/$id.md").bufferedReader().use { it.readText() } }.getOrNull() }
        com.blaze.assistant.browser.Vault.dir = app.filesDir
        ChatStore.init(app)
        LongChat.init(app)
        try { com.blaze.assistant.media.Media.init(app) } catch (_: Exception) { }
        refreshChats()
        // One mind (v0.18.0): one continuous main conversation. v0.28.3: main Chat always opens on the main thread.
        // Builds before saved the last project chat as the chat to reopen, which put a project chat in main Chat;
        // that pref is repaired here (project chats now open only inside Projects, see ChatNav). Nothing is deleted.
        if (Settings.lastChatId.let { it.isNotEmpty() && it != Mind.MAIN }) Settings.lastChatId = Mind.MAIN
        show(mainSession())
        // One-time, idempotent import of every old chat into the journal (chat files are never changed).
        io.launch {
            val d = Mind.db() ?: return@launch
            try { Mind.migrate(d, ChatStore.list().map { it.id }, { ChatStore.load(it) }, emptyList()) } catch (_: Exception) { }
        }
    }

    /** The one main conversation (id "main"), live or from disk, created the first time. */
    private fun mainSession(): Session {
        val s = load(Mind.MAIN) ?: Session(Mind.MAIN)
        s.title = "Blaze"; s.titleSource = Titles.MANUAL; s.keep = true
        return s
    }

    /** True for the main conversation or a project thread; everything else is archived (read-only). */
    fun isLive(id: String): Boolean = id == Mind.MAIN || Projects.of(id) != null

    private fun newId() = UUID.randomUUID().toString().take(12)

    /** The application context (for code outside the agent that needs one, e.g. Blaze Link sends). */
    fun appContext(): Context = app

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.blaze.assistant.device.CrashLog.guard)
    private val ioLock = kotlinx.coroutines.sync.Mutex()

    /** Reads chat files in the background so the UI never waits on disk. */
    fun refreshChats() { io.launch { val l = ChatStore.list(); _chats.value = l } }

    // ---------- v0.24.4: private chat ----------
    private val _private = MutableStateFlow(false)
    /** True while the visible chat is a private one. */
    val privateChat: StateFlow<Boolean> = _private
    /** The thread to go back to when the private chat closes. */
    @Volatile private var beforePrivate: String = Mind.MAIN

    /** Opens a new private chat (memory only). The chat you were in is saved and stays as it was. */
    fun enterPrivate() {
        if (cur.isPrivate) return
        cur.persist()
        beforePrivate = cur.id
        val s = Session(PrivateChat.PREFIX + newId())
        s.title = "Private"; s.titleSource = Titles.MANUAL
        s.add(Role.STEP, "Private chat \u00b7 nothing here is saved or learned, and it disappears when you leave")
        show(s)
    }

    /** Closes the private chat (stops it if it's working) and throws it away. Nothing was ever saved. */
    fun leavePrivate() {
        val p = cur
        if (!p.isPrivate) return
        p.stop()
        p.msgs = emptyList()
        sessions.remove(p.id)
        val back = beforePrivate
        val s = (if (back != Mind.MAIN && !PrivateChat.isPrivateId(back)) load(back) else null) ?: mainSession()
        show(s)
        sessions.remove(p.id)
    }

    /** A card drawn by the app (e.g. the update card), marked by its meta. Not sent to the model. */
    fun card(text: String, meta: String) {
        val s = cur
        val id = s.add(Role.BLAZE, text)
        s.msgs = s.msgs.map { if (it.id == id) it.copy(meta = meta) else it }
    }

    /** v0.24.4: true when the visible chat already has a card with this meta (one update card at a time). */
    fun hasCard(meta: String): Boolean = cur.msgs.any { it.meta == meta }

    /** v0.24.4: removes app cards with this meta from every open chat (the update card when it finishes). */
    fun removeCard(meta: String) {
        val all = (sessions.values + cur).toSet()
        all.forEach { s -> if (s.msgs.any { it.meta == meta }) { s.msgs = s.msgs.filterNot { it.meta == meta }; try { s.persist() } catch (_: Throwable) { } } }
    }

    /** A line from the app itself (command results, help). Shown, not sent to the model. */
    fun note(text: String, asReply: Boolean = false) { cur.note(text, asReply) }

    /** The whole visible chat as plain text. */
    fun transcript(): String = cur.msgs.filter { it.role != Role.STEP }.joinToString("\n\n") {
        (if (it.role == Role.USER) "You: " else "Blaze: ") + it.text }

    /** Retry the newest answer. */
    fun retryLast() { cur.msgs.lastOrNull { it.role == Role.BLAZE || it.role == Role.ERROR }?.let { retry(it.id) } }

    /** [untrusted]: where outside content in [text] came from (Magic Hand's screen text), see ConfirmGate. */
    fun send(text: String, byVoice: Boolean = false, attachments: List<Attachment> = emptyList(), untrusted: String? = null) {
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
        // v0.28.x Blaze Camera: "scan this document", "read this sign", "look at this", "escanea este documento"… open the
        // camera in that mode at once (no model guess); the capture comes back into this chat as the answer (fromCamera).
        if (attachments.isEmpty() && untrusted == null && cur.job?.isActive != true) {
            CameraIntent.match(text)?.let { mode ->
                val s = cur
                if (com.blaze.assistant.device.BlazeCamera.open(app, mode, com.blaze.assistant.device.CameraModes.Origin.REQUEST, s.id, text.trim())) {
                    com.blaze.assistant.device.Haptics.tick(app)
                    s.selfTurn(text.trim(), com.blaze.assistant.device.CameraModes.openingLine(mode), byVoice, "\uD83D\uDCF7 Blaze Camera")
                    return
                }
            }
        }
        cur.send(text, byVoice, attachments, untrusted = untrusted)
    }

    /**
     * v0.28.x Blaze Camera: a capture (photo / document / text read / scanned code) goes into the chat that asked for it as
     * the next message, so Blaze answers about it in that turn's thread. Falls back to the chat on screen.
     */
    fun fromCamera(chatId: String?, text: String, attachments: List<Attachment>) {
        scope.launch {
            val s = chatId?.takeIf { it.isNotBlank() }?.let { sessions[it] ?: load(it) } ?: cur
            sessions[s.id] = s
            if (s !== cur) show(s)
            if (s.busy) s.job?.join()
            s.send(text, attachments = attachments)
        }
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

    /**
     * v0.28.3: put [id] on screen for the shell (ChatNav.visibleChat): the main thread on the Chat tab, or a project
     * chat inside Projects. A project chat that has no messages yet (just made with + New chat) is created fresh.
     * A private chat on screen is never thrown away by a switch back to main (leaving it has its own confirm).
     */
    fun showChat(id: String) {
        if (id.isBlank() || id == cur.id) return
        if (id == Mind.MAIN) { if (!cur.isPrivate) show(mainSession()); return }
        if (cur.isPrivate) return
        cur.persist()
        show(load(id) ?: Session(id))
    }

    /** v0.28.3: a new, empty project chat's id (it joins [projectId] with its first message; see ChatScreen.send). */
    fun newChatId(): String = newId()

    /** v0.28.3: a deep link / notification asked for this chat: only the main thread or a project chat open. */
    fun openFromLink(id: String) { if (id.isNotBlank() && isLive(id)) open(id) }

    /** A chat's session: the live one if it's open or working, else read from disk. */
    private fun load(id: String): Session? {
        sessions[id]?.let { return it }
        val o = ChatStore.load(id) ?: return null
        val s = Session(id)
        s.title = o.optString("title"); s.pinned = o.optBoolean("pinned"); s.summary = o.optString("summary")
        s.lcSummary = o.optString("lc_summary"); s.lcUpto = o.optInt("lc_upto"); s.ctxFrom = o.optInt("ctx_from")
        if (o.has("meter_in")) s.meter = Meter(o.optInt("meter_in"), o.optInt("meter_out"), o.optInt("meter_limit", 32_768))
        s.titleSource = o.optString("title_source"); s.titleTurns = o.optInt("title_turns"); s.keep = o.optBoolean("keep")
        o.optJSONArray("tips_pending")?.let { a -> for (i in 0 until a.length()) s.unseenTips += a.getString(i) }
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
            list += ChatMessage(mid, Role.valueOf(m.getString("role")), m.getString("text"), files, m.optString("thinking"), fu, m.optString("meta"), media, at = m.optLong("at"), thinkMs = m.optLong("think_ms"), startAt = m.optLong("start_at"))
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

    /**
     * In a project: start a new thread there (the one you leave keeps working if it was busy). Everywhere else
     * (one mind): a fresh context window on the main conversation; nothing is deleted and Blaze looks things up.
     */
    fun clear(inProject: Boolean = Projects.of(cur.id) != null) {
        cur.persist()
        if (inProject) { show(Session(newId())); return }
        val m = if (cur.id == Mind.MAIN) cur else mainSession()
        show(m)
        m.freshContext(null)
    }

    /** v0.24.0 /clear: the last clear of the visible thread, kept in memory only so the snackbar can undo it. */
    @Volatile private var lastClear: ClearScreen.Snapshot? = null

    /**
     * /clear: empties the visible thread so the chat starts empty. Nothing is forgotten: the history stays in the
     * chat file, the journal (search_chat) and the long-chat index; Blaze starts a new context window there.
     */
    fun clearScreen(): ClearScreen.Result {
        val s = cur
        if (s.busy) return ClearScreen.Result.BUSY
        if (ClearScreen.isEmpty(s.msgs)) return ClearScreen.Result.EMPTY
        lastClear = ClearScreen.Snapshot(s.id, s.msgs, s.ctxFrom, s.lcSummary, s.lcUpto, s.history.length(), System.currentTimeMillis())
        val w = ClearScreen.window(s.history.length())
        s.ctxFrom = w.ctxFrom; s.lcSummary = w.lcSummary; s.lcUpto = w.lcUpto
        s.msgs = emptyList()
        s.persist(force = true)
        return ClearScreen.Result.CLEARED
    }

    /** True while the last /clear can still be undone (same chat, nothing sent since, within a few seconds). */
    fun canUndoClear(): Boolean = ClearScreen.canUndo(lastClear, cur.id, cur.msgs, cur.history.length(), System.currentTimeMillis())

    /** Puts the cleared messages and context window back. */
    fun undoClear(): Boolean {
        val snap = lastClear ?: return false
        val s = cur
        if (!ClearScreen.canUndo(snap, s.id, s.msgs, s.history.length(), System.currentTimeMillis())) return false
        s.ctxFrom = snap.ctxFrom; s.lcSummary = snap.lcSummary; s.lcUpto = snap.lcUpto
        s.msgs = ClearScreen.restored(snap, s.msgs)
        lastClear = null
        s.persist(force = true)
        return true
    }

    /** The model's context window (Settings › context_tokens, else a guess from its name; 0 = unknown). */
    fun ctxWindow(): Int = LongChat.contextWindow(Settings.model, Settings.str("context_tokens", "0").toIntOrNull() ?: 0)
    /** When older turns fold into the rolling summary. */
    fun budget(): Int = LongChat.budget(ctxWindow())

    fun deleteChat(id: String) {
        io.launch { try { LongChat.db()?.forget(id) } catch (_: Exception) { } }
        sessions[id]?.stop()
        sessions.remove(id)
        ChatStore.delete(id)
        // v0.28.3: a deleted project chat leaves its project, and the screen falls back to the main thread (never a blank chat).
        if (Projects.of(id) != null) Projects.assign(id, "")
        if (id == cur.id) show(mainSession())
        refreshChats()
    }

    fun renameChat(id: String, t: String) {
        ChatStore.rename(id, t)
        sessions[id]?.let { it.title = t.trim().ifEmpty { "Chat" }; it.titleSource = Titles.MANUAL }
        refreshChats()
    }
    fun pinChat(id: String, p: Boolean) { ChatStore.setPinned(id, p); sessions[id]?.pinned = p; refreshChats() }

    /**
     * A reply typed into a notification's Reply box. It goes back to the conversation that notification
     * came from, so Blaze knows what it said: the chat that posted it ([chatId]; heads-ups live in the
     * Heads-ups chat since v0.8.0), or, for an old heads-up with no chat, a new chat that opens with
     * Blaze's [context] message. The model is always told which message is being answered.
     */
    fun replyFromNotification(chatId: String?, context: String?, text: String) {
        val t = text.trim(); if (t.isEmpty()) return
        val ctx = context?.takeIf { it.isNotBlank() }
        scope.launch {
            val s = chatId?.takeIf { it.isNotBlank() }?.let { load(it) } ?: run {
                if (ctx == null) { send(t); return@launch }
                Session(newId()).also { n ->
                    n.title = "Heads-up \u00b7 " + ctx.lineSequence().first().take(30)
                    n.add(Role.BLAZE, ctx)
                }
            }
            sessions[s.id] = s
            if (!inForeground && s !== cur) show(s)
            if (s.busy) s.job?.join()
            s.send(t, notificationContext = ctx)
        }
    }

    /** The one chat every heads-up (tips, briefs, routine and job alerts) is kept in, so replies have context. */
    private fun headsUpChatId(): String {
        val saved = Settings.str("headsup_chat")
        if (saved.isNotBlank() && (sessions.containsKey(saved) || ChatStore.load(saved) != null)) return saved
        return newId().also { Settings.setStr("headsup_chat", it) }
    }

    /**
     * Records a heads-up Blaze posted as a notification (v0.8.0): it shows in the Heads-ups chat and the
     * model is told about it with the next message there, so replying from the notification (or later in
     * the app) continues that conversation instead of starting a blank one. Returns the chat's id.
     */
    fun recordHeadsUp(text: String): String {
        val id = headsUpChatId()
        val stamp = java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        scope.launch {
            val s = load(id) ?: Session(id).also { it.title = "Heads-ups"; it.titleSource = Titles.MANUAL }
            s.keep = true
            sessions[s.id] = s
            val mid = s.add(Role.BLAZE, text)
            s.addMeta(mid, "Heads-up \u00b7 $stamp")
            s.unseenTips += "$stamp: $text"
            while (s.unseenTips.size > 20) s.unseenTips.removeAt(0)
            s.persist()
            refreshChats()
        }
        return id
    }

    /** The one-tap suggestion on a heads-up: runs in the Heads-ups chat, with the heads-up as context. */
    fun doFromHeadsUp(chatId: String?, context: String?, prompt: String) = replyFromNotification(chatId ?: headsUpChatId(), context, prompt)

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

    fun lastStep(): String = cur.status.ifBlank { cur.msgs.lastOrNull { it.role == Role.STEP }?.text ?: Friendly.between(null) }

    // ---------- Background agents ----------

    /** An agent's own chat: live if it's working, else read back from disk. */
    fun agentSession(bg: BgAgents.Bg): Session {
        val s = sessions[bg.chat] ?: load(bg.chat) ?: Session(bg.chat)
        s.agentId = bg.id; s.agentPlan = bg.plan; s.ctxTag = BgAgents.Tag(bg.id)
        s.specialistId = bg.specialist.ifBlank { null }
        s.title = "\u2699 ${bg.id} · " + (BgAgents.spec(bg)?.let { "${it.name} · " } ?: "") + bg.title
        sessions[s.id] = s
        return s
    }

    fun stopAgent(bg: BgAgents.Bg) { sessions[bg.chat]?.stop() }

    /** "/agent <task>": the card goes in the open chat, and the result wakes it. */
    fun startAgentFromUser(task: String) {
        val s = cur
        val bg = BgAgents.start(task, "", s.id)
        s.add(Role.USER, "/agent $task")
        s.history.put(JSONObject().put("role", "user").put("content", "[I handed this to background agent ${bg.id}: $task]"))
        s.history.put(JSONObject().put("role", "assistant").put("content", "Started background agent ${bg.id}."))
        val mid = s.add(Role.BLAZE, "Background agent ${bg.id}: ${bg.title}")
        s.msgs = s.msgs.map { if (it.id == mid) it.copy(meta = BgAgents.CARD + bg.id) else it }
        s.persist()
    }

    /** An agent finished: its result wakes the chat that started it (queued behind a turn in progress). */
    fun agentFinished(bg: BgAgents.Bg) {
        refreshChats()
        val p = sessions[bg.parent] ?: load(bg.parent) ?: return
        sessions[p.id] = p
        // v0.26.2: a specialist's result posts as it lands: its card (now showing the result) moves to the bottom of the chat,
        // then the main Blaze writes a short follow-up from it.
        if (bg.specialist.isNotBlank()) {
            p.msgs = Dispatch.moveToEnd(p.msgs, { it.meta == BgAgents.CARD + bg.id }, { it.copy(at = System.currentTimeMillis()) })
            p.persist()
        }
        p.wake(BgAgents.resultMessage(bg))
    }

    // ---------- Specialists (v0.26.2) ----------

    /** One review call: the specialist's draft against its playbook's Self-check. Any failure of the call itself counts as a pass. */
    suspend fun reviewDraft(spec: Specialists.Spec, playbook: String, task: String, draft: String): Playbooks.Review = try {
        val r = LlmClient.complete(Playbooks.reviewMessages(spec.name, playbook, task, draft), null, null, null, LlmClient.Lane.BACKGROUND)
        Playbooks.parseReview(r.optString("content"))
    } catch (e: CancellationException) { throw e } catch (_: Exception) { Playbooks.Review(true, emptyList()) }

    private val playbookLock = kotlinx.coroutines.sync.Mutex()

    /**
     * A custom specialist without a playbook gets one written by the model, validated like create_specialist's
     * (a few tries), and saved with it. Returns the playbook ("" if none passed; it's tried again next time).
     */
    suspend fun ensurePlaybook(spec: Specialists.Spec): String = playbookLock.withLock {
        Specialists.byId(spec.id)?.playbook?.takeIf { it.isNotBlank() }?.let { return@withLock it }
        val others = Specialists.builtIn.map { Playbooks.builtIn(it.id) } + Specialists.custom().filter { it.id != spec.id }.map { it.playbook }
        var problems = emptyList<String>(); var prev = ""
        repeat(Playbooks.MAX_TRIES) {
            val raw = try { LlmClient.complete(Playbooks.generateMessages(spec.name, spec.prompt, problems, prev), null, null, null, LlmClient.Lane.BACKGROUND).optString("content") }
                catch (e: CancellationException) { throw e } catch (_: Exception) { return@withLock "" }
            val pb = Playbooks.cleanGenerated(raw)
            val c = Playbooks.validate(pb, "${spec.name} ${spec.id} ${spec.prompt}", others)
            if (c.ok) {
                val now = Specialists.byId(spec.id) ?: spec
                if (now.custom) Specialists.upsert(now.copy(playbook = pb))
                return@withLock pb
            }
            problems = c.problems; prev = pb
        }
        ""
    }

    // ---------- Prompt ----------

    private fun personaText(): String =
        if (Settings.personaId == Personas.CUSTOM && Settings.customPersona.isNotBlank())
            "You are Blaze, an AI assistant on the user's Android phone. " + Settings.customPersona
        else Personas.byId(Settings.personaId).prompt

    private fun systemPrompt(userText: String, skipSkills: Set<String> = emptySet(), chatId: String = _chatId.value): String {
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
            append(AnswerStyle.RULE)
            append("\nYou are Blaze and you can control yourself with blaze_self (close/go quiet, stop, orb, wake word, read-aloud, model, personality, settings, your setup and crash log). When the user wants help setting you up, or asks what you can do or what's installed, call blaze_self setup_check and guide them one step at a time. If the user asks you to shut down, close, go away or stop, do that with blaze_self, never a phone setting. ")
            append("\nWhen the user tells you something lasting about themselves, save it quietly with the remember tool. ")
            append("\nIf the user says \"again\", \"do it again\" or \"same for X\", repeat your last actions from this chat (adapted to X) without asking. ")
            append("\nIf you're unsure what the user wants, or there are several options (accounts, contacts, items, times), don't guess and don't end your turn: call ask_user with short options, then carry on with the answer. ")
            if (control) {
                append("""
BACKGROUND FIRST: never take over the user's screen when a background tool can do it. Web questions → web_search/fetch_page; sites you must click, fill or log in to → browser_open/browser_click/browser_type (Blaze's own background browser; browser_show only when the user must see or take over). When a page needs the user's email, phone, username + password, API key or a one-time code, call browser_secure_input (a secure form in the chat; you get {{secret:name}} placeholders, never the values) instead of asking in chat or showing the browser. If a page says the account can't sign in with a password, don't loop on the password: offer Continue with Google, a passkey or the live browser with ask_user. Browser tools never need phone control. Messaging someone → message_contact (never open WhatsApp on screen yourself); a picture Blaze makes → generate_image, then message_contact with image="last"; several contacts match → ask_user with the exact names; only a result starting "Sent to" means it was sent. Email → gmail_search/gmail_read. Calendar → gcal_events or calendar tools. Texts, calls, contacts → the SMS/call/contact tools. Notifications → recent_notifications. Music → spotify tools. Files/code → run_shell. Only if no background tool exists or it isn't connected, use the screen; when a background tool isn't connected, offer to connect it (blaze_self open "settings connections") instead of silently driving the app.
You can control the phone. SPEED MATTERS: the user is watching.
${Orient.RULE}
- For anything in an app (not Settings: use open_settings), call run_plan ONCE with the whole script, using the visible words on buttons and fields as targets (e.g. open_app YouTube → tap "Search" → type "lofi hip hop" submit → tap the first result's title). You know how common apps look; you don't need to read the screen first. Add optional:true for popups you might need to dismiss, and expect:"..." when a step must lead somewhere.
- Element numbers stay the same while the screen stays the same; after an action you often get only the changes ("Same screen… Changed or new"), so keep using the numbers you have.
- For flows with predictable pop-ups (sign-in, cookie banners, "Not now"), set watchers with watch (or a preset) so the phone reacts instantly instead of spending your steps.
- run_plan only comes back when it's done or stuck. If stuck, it returns the screen with element numbers: fix it with a short new run_plan, or tap/type_text by number.
- Use single tap/type_text/scroll only for one-off fixes. If read_screen shows little (games, maps, video, custom apps), call look_at_screen.
- Prefer direct tools when they fit: search_in_app for "play/search X on YouTube/Spotify/Play Store/Maps", set_alarm, set_timer, dial, compose_sms. open_url only for real web addresses; web_search to look something up yourself.
${FastActions.RULE.trimStart('\n')}
- Before anything irreversible or that spends money or contacts someone (sending, posting, paying, deleting, calling), confirm first unless the user clearly told you to do exactly that.
When you finish, say what you did in one short sentence.
""".trimIndent())
            } else {
                append("Phone control is turned off in settings, so you can only chat.")
            }
            append("\nYou can also read and send texts, check calls, read and add calendar events, look up contacts, toggle the flashlight and volume, and schedule routines with your tools. Sending a text or placing a call asks the user for approval automatically.")
            if (Settings.followups) append("\nAt the very end of each final answer (never when calling tools), add one line exactly like <<follow-ups: first | second | third>> with three short next requests (max 6 words each) written as the user would say them. Skip it for routines.")
            append("\nIf a tool you need isn't in your list, call load_tools with its group, then carry on.")
            // v0.28.1: models sometimes wrote "[/find_tool need=…]" as the reply instead of calling the tool.
            append("\n" + ToolLeak.PROMPT_RULE)
            // v0.28.4 blaze-docs: one static line + the repo URL; the docs themselves are looked up (blaze_docs_search), never pasted here.
            append(BlazeDocs.PROMPT_LINE)
            append("\nEverything you create that keeps running (alarms, timers, routines, triggers, calendar events, jobs) gets an ID like R4. To change, stop or delete one, call processes to find the ID, then process(id, action). Only use action=details when the user asks for specifics.")
            // v0.24.3: the core skills' pinned rules (voice, task workflow, views, specialists, workflows, coding), every turn,
            // read from the shipped assets and static, so they stay in the cached prefix.
            if (CoreSkills.loader == null) CoreSkills.loader = {
                CoreSkills.NAMES.mapNotNull { n -> runCatching { n to app.assets.open("skills/$n/SKILL.md").bufferedReader().use { it.readText() } }.getOrNull() }.toMap()
            }
            append(CoreSkills.promptBlock())
            // v0.23.0: the always-present VIEWS block (static, so it stays in the cached prefix).
            append(ViewRouter.PROMPT)
            // v0.24.9 rich replies / v0.26.1 visual answers (diagrams, charts, compare, tools, choices, forms; picked
            // automatically). Static text, so it stays in the cached prefix; left out only when Visual answers is off.
            if (Settings.bool("rich_replies", true)) append(com.blaze.assistant.ui.RichReplies.PROMPT)
            // Speed plan step 2: everything above stays identical turn after turn, so providers can
            // reuse their cached prefix. Everything that changes (clock, memory, device) goes last.
            append("\n\n")
            append("CURRENT LOCAL TIME: $now (${java.util.TimeZone.getDefault().id}). This is exact: answer any time, date or day question straight from it, never say you can't see the time. ")
            append(Memory.promptBlock(userText))
            append(Learn.rules(Settings.autoLearn))
            append(com.blaze.assistant.device.DeviceStatus.promptLine(app))
            append(PhoneState.RULE)
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
                "DO IT, DON'T DIRECT: when something needs a permission or a setting, YOU fix it: ask with the tool that triggers Android's own pop-up, open the exact screen with blaze_self open, and when phone control is on, tap through it yourself (find the switch, flip it, come back). " +
                "Never hand the user a numbered list of where to tap in Settings; only if you tried and truly can't, say the ONE thing they must tap. Never ask them to type terminal commands: run them yourself. " +
                "For terminal work use Blaze's own Linux (run_code / run_shell) first; Termux only if the user asks for it. " +
                "generate_image is ONLY for art and photos; charts, graphs, tables, PDFs and documents are always made with run_code. If the terminal fails, say so plainly; never substitute an AI picture.")
            append(SelfSetup.promptLine(app))
            // v0.26.2: learned skills are in the one skills lookup below (SkillMatch.lookupBlock), not a block of their own.
            Processes.countLine().takeIf { it.isNotBlank() }?.let { append("\nTracked now: $it (IDs via processes).") }
            append(com.blaze.assistant.link.DesktopLink.promptLine())
            append(SelfKnowledge.promptBlock(app)); append(Projects.promptLine(chatId))
            append("\nCALLS: \"let's get on a call\", \"call me\" or \"let's talk\" with no person named means a voice call with YOU: call_control start_blaze_call. Never phone a contact unless they name who. " +
                "Loudspeaker, mute, hang up, answer, hold, call volume and keypad presses during any call go through call_control, never the Settings app.")
            if (com.blaze.assistant.voice.Voice.inCall) append(" You are on a voice call with the user right now (Blaze's call screen): \"I can't hear you\" means call_control speaker on or louder.")
            append(Autonomy.promptBlock()); append(Autonomy.promptLine())
            // v0.30.4: no skills lookup for a plain device / settings action (it only ever pulled in unrelated skills).
            if (!FastActions.plainDevice(userText)) append(SkillMatch.lookupBlock(userText, skipSkills))
            // v0.30.5: tasks the user has Blaze do often, with no skill yet, come first for a skill.
            if (Settings.autoLearn) append(SkillGuard.Freq.promptLine(SkillGuard.Freq.load()))
        }
    }

    private suspend fun tools(): JSONArray {
        val t = JSONArray()
        Memory.tools().forEach { t.put(it) }
        if (Mind.ready) Mind.tools().forEach { t.put(it) }
        t.put(com.blaze.assistant.device.DeviceStatus.tool())
        t.put(com.blaze.assistant.mediaapps.store.JamendoSource.tool())
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
        if (Settings.bool("specialists_on", true)) { t.put(Specialists.tool()); t.put(Specialists.createTool()) }
        Learning.tools().forEach { t.put(it) }
        t.put(com.blaze.assistant.update.Updater.tool())
        t.put(com.blaze.assistant.ui.Views.tool())
        t.put(SelfKnowledge.tool())
        BlazeDocs.tools().forEach { t.put(it) }
        // v0.28.5: a project's full instructions / brief / notes as lookups; stripped from every chat that isn't in a project.
        ProjectDocs.tools().forEach { t.put(it) }
        // v0.29.0 Home board: Blaze adds / changes / removes / lists the user's widgets.
        com.blaze.assistant.home.HomeTools.tools().forEach { t.put(it) }
        t.put(BlazeRouter.tool())
        t.put(com.blaze.assistant.desktop.LinuxDesktop.tool())
        if (Settings.longChat || Mind.ready) LongChat.tools().forEach { t.put(it) }
        t.put(Flashcards.tool())
        if (Designs.templateLoader == null) Designs.templateLoader = { app.assets.open("skills/three-d-design/assets/mockup.html").bufferedReader().use { it.readText() } }
        t.put(Designs.tool())
        t.put(Seller.tool())
        if (Settings.screenMemory) t.put(ScreenMemory.tool())
        if (com.blaze.assistant.notify.NotificationBrain.instance != null) com.blaze.assistant.notify.NotificationBrain.tools().forEach { t.put(it) }
        if (com.blaze.assistant.notify.NotificationBrain.instance != null) com.blaze.assistant.notify.Conversations.tools().forEach { t.put(it) }
        t.put(com.blaze.assistant.life.LifeLogs.tool())
        t.put(com.blaze.assistant.phone.CallControl.tool())
        if (Settings.bool("bg_agents", true)) BgAgents.tools().forEach { t.put(it) }
        if (com.blaze.assistant.link.DesktopLink.paired) t.put(com.blaze.assistant.link.DesktopLink.tool())
        com.blaze.assistant.connect.Connectors.tools().forEach { t.put(it) }
        try { com.blaze.assistant.connect.Mcp.tools().forEach { t.put(it) } } catch (_: Exception) { }
        if (Settings.phoneControl) t.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "run_plan")
            .put("description", "FASTEST way to do anything in an app or settings page. Give the whole script at once; Blaze runs it on-device in a few seconds, finding things by their visible text (no element numbers, no need to read the screen first), checking each step on the phone, and only comes back when done or stuck. Steps can open a settings page by intent (do=open_settings page=… app=…).")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("steps", JSONObject().put("type", "array")
                .put("items", JSONObject().put("type", "object").put("properties", JSONObject()
                    .put("do", JSONObject().put("type", "string").put("enum", JSONArray(listOf("open_app", "open_settings", "tap", "long_press", "type", "enter", "scroll", "back", "home", "wait", "search_in_app"))))
                    .put("app", JSONObject().put("type", "string").put("description", "open_app / search_in_app: app name; open_settings: the app whose page it is (optional)"))
                    .put("page", JSONObject().put("type", "string").put("description", "open_settings: the page, same names as the open_settings tool (open_by_default, app_notifications, wifi, …)"))
                    .put("want", JSONObject().put("type", "string").put("enum", JSONArray(listOf("on", "off"))).put("description", "tap on a switch: the state it should end in; skipped when it already is"))
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
        // v0.30.4: straight to a settings page by intent (in no tool group, so it's always on hand).
        if (Settings.phoneControl) t.put(com.blaze.assistant.phone.SettingsJump.tool())
        return ToolGuide.apply(t).also { try { BlazeDocs.noteTools(it) } catch (_: Throwable) { } }
    }

    /** v0.28.4: the real tool list (for the blaze-docs tool reference, indexed at startup). */
    suspend fun toolCatalog(): JSONArray = tools()

    /** True while Blaze's own screen is visible. */
    @Volatile var inForeground = false

    /** True while the assistant overlay or floating orb is open (replies show there, not as notifications). */
    @Volatile var overlayOpen = false



    /**
     * One chat with its own agent. Several can run at once: leaving a chat (or starting a new
     * one) never stops it or sends its answer to the wrong place.
     */
    class Session(val id: String) {
        /** v0.24.4: a private chat (memory only: nothing saved or learned). */
        val isPrivate: Boolean get() = PrivateChat.isPrivateId(id)
        /** Set when this chat is a background agent's (B1…). */
        var agentId: String? = null
        /** v0.26.2: the specialist this background agent runs as (its prompt, playbook and tools). */
        var specialistId: String? = null
        val specialist: Specialists.Spec? get() = specialistId?.let { try { Specialists.byId(it) } catch (_: Throwable) { null } }
        /** create_specialist tries per id this session. */
        val createTries = HashMap<String, Int>()
        var agentPlan = false
        var ctxTag: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext
        /** Instructions the user (or the lead) sent while this chat works; read at the next step. */
        val steer = java.util.concurrent.ConcurrentLinkedQueue<String>()
        /** v0.28.2: what untrusted content this turn has read (ConfirmGate); rides in the turn's coroutine context. */
        val taint = ConfirmGate.Taint()
        /** Agent results waiting for the turn in progress to end. */
        val wakes = java.util.concurrent.ConcurrentLinkedQueue<String>()
        /** Speed plan step 11: text of the current step already handed to TTS while it streamed. */
        var spokenPrefix = ""
        var streamedVoice = false
        var title = ""
        /** "" (first prompt), "auto" (named by Titles) or "manual" (renamed by you: never retitled). */
        var titleSource = ""
        /** User turns when the title was last evaluated. */
        var titleTurns = 0
        var pinned = false
        /** One-line summary written with the title (used by search_past_chats). */
        var summary = ""
        /** Long-chat rolling summary and the first history index it doesn't cover (v0.15.0). */
        var lcSummary = ""
        var lcUpto = 0
        /** One mind (v0.18.0): first history index of this context window ("Fresh start" moves it; nothing is deleted). */
        var ctxFrom = 0
        /** The ladder rung the last request used (diagnostics, tests). */
        @Volatile var lastRung = Mind.Rung.RECENT
        /** v0.26.0 auto-compaction: tokens of the last request's system prompt + tools (an estimate until the first call). */
        @Volatile var overheadTok = 6_000
        /** A provider refused this chat as too long: send the summary + tail from now on (not saved; a refusal sets it again). */
        @Volatile var squeezed = false
        private var _meterV: Meter? = null
        var meter: Meter?
            get() = _meterV
            set(v) { _meterV = v; if (this === cur) _meter.value = v }
        /** Heads-ups posted into this chat as notifications that the model hasn't been told about yet. */
        val unseenTips = java.util.Collections.synchronizedList(mutableListOf<String>())
        /** Saved even before the user writes in it (the Heads-ups chat). */
        var keep = false
        /** Tool names called this turn, in order (for Learn.decide). */
        val turnTools = java.util.Collections.synchronizedList(mutableListOf<String>())
        /** v0.23.0: the view this turn must use (keyword classifier) and the skills looked up for it. */
        @Volatile var turnView: ViewRouter.Spec? = null
        @Volatile var turnSkills: SkillMatch.Result = SkillMatch.Result.NONE
        @Volatile var turnSpecs: List<Specialists.Spec> = emptyList()
        /** v0.23.2: tool calls that succeeded this turn (the claim guard checks replies against them). */
        val turnOk = java.util.Collections.synchronizedList(mutableListOf<ClaimGuard.Call>())
        /** v0.23.2: the last turn made, changed or opened a design (so a short edit next is a follow-on). */
        @Volatile var lastTurnDesign = false
        /** v0.23.2: the follow-on hint for this turn ("" = none). */
        @Volatile var turnDesignNote = ""
        val history = JSONArray()
        val historyMark = HashMap<Long, Int>()
        val trace = JSONArray()
        /** v0.30.7: this turn's phone steps that worked (plan memory), and whether the turn touched the screen yet. */
        val planRec = TapLoop.Recorder()
        @Volatile var screenActed = false
        @Volatile var currentStep = 0
        var job: Job? = null

        private var _msgs: List<ChatMessage> = emptyList()
        var msgs: List<ChatMessage>
            get() = _msgs
            set(v) { _msgs = v; if (this === cur) _messages.value = v }
        private var _mood = Mood.IDLE
        var mood: Mood
            get() = _mood
            set(v) {
                _mood = v; if (this === cur) Agent._mood.value = v; refreshBusy()
                if (v == Mood.IDLE) status = "" else if (status.isBlank()) status = Friendly.between(null)
            }
        private var _status = ""
        /** The plain-words status line for this chat (see [Agent.status]). */
        var status: String
            get() = _status
            set(v) { _status = v; if (this === cur) Agent._status.value = v }
        /** v0.26.1: times the reasoning for the "Thought it through · 4 s" line. */
        val thinkClock = ThinkingLabel.Clock()
        private var _thinking = ""
        var thinking: String
            get() = _thinking
            set(v) { if (v != _thinking) thinkClock.update(v); _thinking = v; if (this === cur) Agent._thinking.value = v }

        val busy get() = job?.isActive == true

        fun note(text: String, asReply: Boolean) { add(if (asReply) Role.BLAZE else Role.STEP, text) }

        fun lastUserText(): String = msgs.lastOrNull { it.role == Role.USER }?.text.orEmpty()

        fun stop() {
            if (this === cur) _confirm.value?.answer?.complete(false)
            dropFormOf(this)
            job?.cancel()
            job = null
            mood = Mood.IDLE
        }
        /** The generic step just added for the running tool; a tool's own step line replaces its text. */
        var autoStepId = -1L

        /**
         * v0.28.4 (Luke's "Worked · 4 steps · 97m 45s" for 33 s of steps): when the turn in progress started. The work card
         * used to time a turn from the message before its steps, so a Retry or a background agent's wake ran the clock
         * from a message sent long before. The first step of each turn now carries this ([ChatMessage.startAt]).
         */
        @Volatile var turnAt = 0L
        @Volatile private var turnStamped = true

        fun beginTurn(at: Long = System.currentTimeMillis()) { turnAt = at; turnStamped = false }

        fun add(role: Role, text: String, files: List<String> = emptyList()): Long {
            if (role == Role.STEP && autoStepId >= 0 && msgs.lastOrNull()?.id == autoStepId) {
                val sid = autoStepId; autoStepId = -1L
                msgs = msgs.map { if (it.id == sid) it.copy(text = text) else it }
                if (mood != Mood.IDLE) status = text
                return sid
            }
            autoStepId = -1L
            val id = ids.getAndIncrement()
            val start = if (role == Role.STEP && !turnStamped && turnAt > 0) { turnStamped = true; turnAt } else 0L
            msgs = msgs + ChatMessage(id, role, if (role == Role.ERROR) PlainError.of(text) else text, files, startAt = start)
            if (role == Role.STEP && mood != Mood.IDLE) status = text
            if (role == Role.STEP && !inForeground && mood != Mood.IDLE) Notifier.working(if (currentStep > 0) "Step $currentStep · $text" else text)
            return id
        }

        /** A step with its exact call kept for the expanded view. */
        fun addStep(text: String, exact: String): Long {
            val id = add(Role.STEP, text)
            msgs = msgs.map { if (it.id == id) it.copy(meta = exact) else it }
            autoStepId = id
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
            // v0.26.2: never a bare "Done." (the loop stops empty answers before this; a canned turn always has text).
            val text = p.text.ifEmpty { "I didn't get an answer that time. Tap Retry." }
            val tms = if (think.isNotBlank()) thinkClock.ms() else 0L
            if (liveId >= 0) msgs = msgs.map {
                if (it.id == liveId) it.copy(role = Role.BLAZE, text = text, thinking = think, followups = p.followups, meta = meta, media = media, thinkMs = tms) else it }
            else {
                val id = ids.getAndIncrement()
                msgs = msgs + ChatMessage(id, Role.BLAZE, text, thinking = think, followups = p.followups, meta = meta, media = media, thinkMs = tms)
            }
        }

        /** Adds a chip (e.g. "Memory updated") to a reply's footer. */
        fun addMeta(id: Long, chip: String) {
            msgs = msgs.map { if (it.id == id) it.copy(meta = listOf(it.meta, chip).filter { s -> s.isNotBlank() }.joinToString(" · ")) else it }
        }

        /** The note a fresh context window carries in its history (never shown as a bubble). */
        fun freshNote(note: String) = "[New context window. Where we were:]\n\n$note"

        /** One mind: a fresh context window on this thread. [note] (a state note) is carried into it. Nothing is deleted. */
        fun freshContext(note: String?) {
            if (busy) return
            ctxFrom = history.length(); lcSummary = ""; lcUpto = ctxFrom
            // v0.26.2: silent. No "Fresh start" bubble in the chat; the new context window and the carried note stay internal.
            if (!note.isNullOrBlank()) history.put(JSONObject().put("role", "assistant").put("content", freshNote(note)))
            persist()
        }

        /** Save the current chat to disk (screenshots are dropped to keep files small). */
        fun persist(force: Boolean = false) {
            // force (v0.24.0 /clear): an emptied thread is saved too, so cleared messages don't come back on restart.
            if (isPrivate) return   // v0.24.4: private chats are never written anywhere
            if (!force && msgs.none { it.role == Role.USER } && !(keep && msgs.isNotEmpty())) return
            // v0.30.5 (Luke: "sometimes when you send a new message the app kind of glitches and then unfreezes"): the whole
            // chat (every message, the full model history, pictures stripped by copying) was turned into JSON on the main
            // thread at every send, which grows with the one long main conversation. Only a cheap snapshot is taken here;
            // the JSON is built and written in the background, newest snapshot last (ioLock keeps saves in order).
            val snapMsgs = msgs
            val snapHist = ArrayList<JSONObject>(history.length()).apply { for (i in 0 until history.length()) add(history.getJSONObject(i)) }
            val marks = HashMap(historyMark)
            val firstUser = snapMsgs.firstOrNull { it.role == Role.USER }?.text ?: snapMsgs.firstOrNull()?.text ?: title
            if (title.isEmpty()) title = firstUser.lineSequence().first().take(48)
            val preview = snapMsgs.lastOrNull { it.role == Role.BLAZE }?.text ?: firstUser
            val head = JSONObject().put("id", id).put("title", title).put("pinned", pinned)
                .put("updated", System.currentTimeMillis()).put("preview", preview.take(90))
                .put("summary", summary)
                .put("lc_summary", lcSummary).put("lc_upto", lcUpto).put("ctx_from", ctxFrom)
                .apply { meter?.let { m -> put("meter_in", m.inTok).put("meter_out", m.outTok).put("meter_limit", m.limit) } }
                .put("title_source", titleSource).put("title_turns", titleTurns).put("keep", keep)
                .put("tips_pending", JSONArray(synchronized(unseenTips) { unseenTips.toList() }))
            if (this === cur && id == Mind.MAIN) Settings.lastChatId = id
            val cid = id
            val seq = persistSeq.incrementAndGet()
            io.launch {
                ioLock.withLock {
                    if (seq != persistSeq.get()) return@withLock   // a newer snapshot is queued: it writes everything
                    val arr = JSONArray()
                    for (m in snapMsgs) {
                        arr.put(JSONObject().put("id", m.id).put("role", m.role.name).put("text", m.text)
                            .put("files", JSONArray(m.files)).apply {
                                marks[m.id]?.let { put("mark", it) }
                                if (m.thinking.isNotEmpty()) put("thinking", m.thinking.take(20000))
                                if (m.followups.isNotEmpty()) put("followups", JSONArray(m.followups))
                                if (m.meta.isNotEmpty()) put("meta", m.meta)
                                if (m.at > 0) put("at", m.at)
                                if (m.media.isNotEmpty()) put("media", JSONArray(m.media))
                                if (m.thinkMs > 0) put("think_ms", m.thinkMs)
                                if (m.startAt > 0) put("start_at", m.startAt)
                            })
                    }
                    val hist = JSONArray()
                    // A history entry changed under us (rare): skip this save, the next one has it.
                    try { for (h in snapHist) hist.put(stripImages(h)) } catch (_: Exception) { return@withLock }
                    ChatStore.save(head.put("messages", arr).put("history", hist))
                    _chats.value = ChatStore.list()
                    try { LongChat.db()?.sync(cid, hist) } catch (_: Exception) { }
                    // One mind: every turn goes into the journal (searchable, queued for the idle review).
                    Mind.journal(cid, hist)
                }
            }
        }
        private val persistSeq = java.util.concurrent.atomic.AtomicLong(0)

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
                if (byVoice && Voice.shouldSpeak() && !reply.startsWith("Going quiet")) Voice.speak(reply)
            }
            persist()
        }

        fun send(text: String, byVoice: Boolean = false, attachments: List<Attachment> = emptyList(), notificationContext: String? = null,
                 untrusted: String? = null) {
            if ((text.isBlank() && attachments.isEmpty()) || job?.isActive == true) return
            // v0.28.2: a new turn starts clean; what came with the message (a shared file, Magic Hand's screen text) counts as read.
            taint.clear()
            taint.mark(untrusted ?: if (attachments.isNotEmpty()) ConfirmGate.SHARED_FILE else null)
            Voice.lastTurnByVoice = byVoice
            val shown = text.trim().ifEmpty { "Take a look at this." }
            LlmClient.quickTurn = attachments.isEmpty() && shown.length < 80
            // Files with a thumbnail first, so the bubble pairs thumbnails with their names (v0.24.6).
            val id = add(Role.USER, shown, attachments.sortedBy { if (it.thumb != null) 0 else 1 }.map { it.name })
            beginTurn(msgs.lastOrNull { it.id == id }?.at ?: System.currentTimeMillis())
            attachments.mapNotNull { it.thumb }.takeIf { it.isNotEmpty() }?.let { th -> msgs = msgs.map { if (it.id == id) it.copy(media = th) else it } }
            historyMark[id] = history.length()
            // Heads-ups Blaze posted into this chat as notifications, and the one being replied to: the model
            // sees what Blaze had said, so "yes", "remind me later" or "who's that?" make sense (v0.8.0).
            val tips = synchronized(unseenTips) { unseenTips.toList().also { unseenTips.clear() } }
            val said = buildString {
                if (tips.isNotEmpty()) append("[Heads-ups you sent the user as phone notifications since their last message here (they also show in this chat):\n" +
                    tips.takeLast(12).joinToString("\n") { "- ${it.take(600)}" } + "]\n")
                if (!notificationContext.isNullOrBlank()) append("[The user is replying from the notification of this message you sent them: \"${notificationContext.take(1500)}\"]\n")
                if (isNotEmpty()) append("\n")
                append(shown)
            }
            val content: Any = if (attachments.isEmpty()) said else JSONArray().apply {
                put(JSONObject().put("type", "text").put("text", said))
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
            job = scope.launch(ctxTag + taint) {
                // v0.27.x: "show me your memories" / "what do you know about me" is answered straight from the memory table:
                // grouped, with how to change or delete a line. No model call, no tool loop, no Design.
                if (attachments.isEmpty() && !routine && agentId == null && notificationContext.isNullOrBlank() && MemoryIntent.isListRequest(shown)) {
                    mood = Mood.ACTING
                    add(Role.STEP, "Looking through what I remember")
                    val reply = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        try { if (Mind.db() != null) MemoryIntent.format(Mind.all()) else MemoryIntent.formatPlain(Memory.facts.value) }
                        catch (_: Exception) { MemoryIntent.formatPlain(Memory.facts.value) }
                    }
                    history.put(JSONObject().put("role", "assistant").put("content", reply))
                    addReply(-1, reply, "")
                    mood = Mood.IDLE
                    persist(); announce()
                    return@launch
                }
                // Fast lane: direct commands run on-device in well under a second.
                val offline = !FastLane.online(app)
                // Plan mode and background agents skip the fast lane: it acts on the phone without the tool gate.
                if (attachments.isEmpty() && !routine && !Modes.plan && !agentPlan && agentId == null && notificationContext.isNullOrBlank()) {
                    mood = Mood.THINKING
                    val fast = try { kotlinx.coroutines.withContext(ConfirmGate.Direct) { FastLane.tryHandle(app, shown, offline) { t, d, a -> confirm(t, d, a) } } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    // "Draw a lion in sunglasses": straight to the free image service, no model call needed.
                    val pic = if (fast == null && !offline && !Settings.lowData) com.blaze.assistant.media.Media.directPrompt(shown) else null
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
            beginTurn()
            job = scope.launch(ctxTag + taint) { runLoop() }
        }

        /**
         * A turn started by the platform, not the user (a background agent's result): it goes into
         * the history as a hidden message and the model answers in this chat. One at a time.
         */
        fun wake(text: String) {
            if (job?.isActive == true) { wakes.add(text); return }
            history.put(JSONObject().put("role", "user").put("content", text))
            taint.clear(); taint.mark(ConfirmGate.AGENT_REPORT)
            mood = Mood.THINKING
            Voice.lastTurnByVoice = false
            beginTurn()
            job = scope.launch(ctxTag + taint) { runLoop() }
        }

        /** Only the newest screenshot is sent as an image; older ones become a note. */
        fun requestMessages(): JSONArray {
            val base = ctxFrom.coerceIn(0, history.length())
            val st = LongChat.State(lcSummary, maxOf(lcUpto, base))
            // One mind's context ladder: recent turns word for word; then the rolling summary + tail; then lookup
            // (pinned core + index + the last few turns, and Blaze looks the rest up).
            var rung = Mind.Rung.RECENT
            var windowed = false
            val from: Int
            if (Settings.longChat) {
                val sFrom = maxOf(LongChat.sendFrom(history, st), base)
                val histTok = LongChat.tokens(history, base)
                rung = Mind.rung(histTok, budget(), lcSummary.isNotBlank(), lcSummary.length / 4 + 40,
                    LongChat.tokens(history, sFrom), ctxWindow(), AutoCompact.pressure(overheadTok, histTok, ctxWindow(), squeezed))
                if (rung == Mind.Rung.LOOKUP && !Mind.ready) rung = if (lcSummary.isNotBlank()) Mind.Rung.SUMMARY else Mind.Rung.RECENT.also { windowed = true }
                from = when {
                    windowed -> maxOf(windowStart(), base)
                    rung == Mind.Rung.RECENT -> base
                    rung == Mind.Rung.SUMMARY -> sFrom
                    else -> Mind.lookupStart(history, base)
                }
            } else { from = maxOf(windowStart(), base); windowed = from > base }
            lastRung = rung
            val sp = specialist
            val sys = if (sp != null) Playbooks.specialistSystem(sp, Playbooks.forSpec(sp), SpecialistMatch.brief(sp, SkillMatch.cached()),
                    SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date()), Memory.promptBlock(lastUserText()), agentId) +
                    Modes.promptLine(agentPlan) + (if (Settings.longChat && rung == Mind.Rung.SUMMARY) LongChat.summaryBlock(st) else "")
                else systemPrompt(lastUserText(), turnSkills.full.map { it.name }.toSet(), id) + Modes.promptLine(agentPlan) + (if (isPrivate) PrivateChat.PROMPT_LINE else "") +
                (if (Settings.longChat) LongChat.PROMPT_LINE + (if (rung == Mind.Rung.SUMMARY) LongChat.summaryBlock(st) else "") else "") +
                (if (rung == Mind.Rung.LOOKUP) "\n" + Mind.LOOKUP_RULE + "\n" else "") +
                (agentId?.let { "\nYou are background agent $it, not the main chat. Work alone and finish with the result only." } ?: BgAgents.leadLine()) +
                (turnDesignNote.ifEmpty { turnView?.let { ViewRouter.hint(it) } ?: "" }) + SkillMatch.block(turnSkills) + SpecialistMatch.hint(turnSpecs) +
                // v0.27.x: "how do I install X" → lead with the action and end with one concrete offer to do it.
                AnswerStyle.turnHint(lastUserText()) +
                // v0.28.4: "take me to the blaze-agent repo" / "what version are you": look it up in Blaze's docs, never guess.
                (if (BlazeDocs.isAboutBlaze(lastUserText())) BlazeDocs.TURN_HINT else "") +
                // v0.26.1: an in-chat diagram / chart is drawn in the reply itself (no files, preview or use_skill).
                (if (turnView == null && turnDesignNote.isEmpty() && Settings.bool("rich_replies", true) && VisualRoute.inChat(lastUserText())) VisualRoute.HINT else "")
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sys))
            var lastShot = -1
            for (i in 0 until history.length()) if (isShot(history.getJSONObject(i))) lastShot = i
            if (windowed && from > base) msgs.put(JSONObject().put("role", "user").put("content",
                "[Earlier in this chat (older messages not shown): ${summary.ifBlank { "use search_chat if you need them" }}]"))
                .also { msgs.put(JSONObject().put("role", "assistant").put("content", "Got it.")) }
            // Only the newest screen (plus the full readout a diff refers to) is sent; older ones are stale.
            val keepScreens = FastActions.screensToKeep((0 until history.length()).map { i -> history.getJSONObject(i).takeIf { it.optString("role") == "tool" }?.optString("content") })
            for (i in from until history.length()) {
                val m = history.getJSONObject(i)
                msgs.put(when {
                    isShot(m) && i != lastShot -> JSONObject().put("role", "user").put("content", "$SHOT_NOTE (older, removed)")
                    // Only the newest screen readout is sent in full; older ones are stale anyway.
                    i !in keepScreens && m.optString("role") == "tool" && FastActions.isScreen(m.optString("content")) ->
                        JSONObject(m.toString()).put("content", FastActions.stripScreen(m.optString("content")))
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
            if (!(Voice.lastTurnByVoice && Voice.shouldSpeak() && Settings.bool("speak_streaming", true))) return
            if (!text.startsWith(spokenPrefix)) return
            val ends = Regex("[.!?](\\s|$)|\n").findAll(text, spokenPrefix.length).map { it.range.first + 1 }
                .filter { e -> text.substring(e).isNotBlank() }.toList()
            val end = ends.lastOrNull() ?: return
            if (end - spokenPrefix.length < 2) return
            Voice.speakPart(text.substring(spokenPrefix.length, end))
            spokenPrefix = text.substring(0, end)
            streamedVoice = true
        }

        /**
         * Long chats (v0.15.0), automatic compaction (v0.26.0): when the un-summarised part is over the budget, or the
         * whole request nears the model's context window, everything before the recent tail folds into the rolling
         * summary with one background call, in this same chat and without asking. The chat on screen is untouched;
         * only what is sent changes. The summary is saved with the chat (lc_summary / lc_upto), so it is updated,
         * never redone each turn. [force] = the provider just refused the request as too long: keep fewer messages
         * word for word and, if the summariser fails, fold a clipped transcript instead. True when something folded.
         */
        suspend fun compact(force: Boolean = false): Boolean {
            if (!Settings.longChat) return false
            if (lcUpto > history.length()) { lcUpto = 0; lcSummary = "" }
            if (lcUpto < ctxFrom && ctxFrom <= history.length()) { lcUpto = ctxFrom; lcSummary = "" }
            val st = LongChat.State(lcSummary, lcUpto)
            if (!AutoCompact.shouldCompact(history, st, budget(), overheadTok, ctxWindow(), force)) return false
            val r = AutoCompact.range(history, st, force)
            val req = LongChat.summariseRequest(lcSummary, history, r.first, r.last + 1)
            var txt = try {
                LlmClient.complete(req, null, null, null, LlmClient.Lane.BACKGROUND).optString("content").substringAfterLast("</think>").trim()
            } catch (e: CancellationException) { throw e } catch (_: Exception) { "" }
            if (txt.isBlank() && force) txt = AutoCompact.fallbackNote(lcSummary, history, r.first, r.last + 1)
            if (txt.isBlank()) return false
            val ns = LongChat.folded(history, txt, AutoCompact.keep(force))
            lcSummary = ns.summary; lcUpto = ns.upto
            persist()
            return true
        }

        private suspend fun longChatTool(name: String, args: JSONObject): String {
            val snap = JSONArray(history.toString())
            val me = id
            val load: (String) -> JSONArray? = { c -> if (c == me) snap else ChatStore.load(c)?.optJSONArray("history") }
            return when (name) {
                "search_chat" -> if (Mind.ready) { add(Role.STEP, "Looking back through your conversations for \u201c${args.optString("query").take(40)}\u201d")
                    kotlinx.coroutines.withContext(Dispatchers.IO) { Mind.journal(me, snap); Mind.searchChat(args.optString("query"), args.optInt("limit", 8)) } }
                else { add(Role.STEP, "Looking back through ${if (args.optBoolean("all_chats")) "your chats" else "this chat"} for “${args.optString("query").take(40)}”")
                    kotlinx.coroutines.withContext(Dispatchers.IO) { LongChat.searchText(args.optString("query"), me, args.optBoolean("all_chats"), args.optInt("limit", 8), load) { ChatStore.list().map { it.id } } } }
                "read_turn" -> { add(Role.STEP, "Reading an earlier message"); kotlinx.coroutines.withContext(Dispatchers.IO) { Mind.readTurn(args.optString("id")) ?: LongChat.readTurn(args.optString("id"), me, load) } }
                "read_artifact" -> { add(Role.STEP, "Reading the saved output"); kotlinx.coroutines.withContext(Dispatchers.IO) { LongChat.readArtifact(args.optString("id"), args.optInt("from", 1), args.optInt("lines", 200)) } }
                "update_brief" -> {
                    val p = Projects.of(me) ?: return "This chat isn't in a project, so there's no brief to update. Keep going without it."
                    add(Role.STEP, "Updating the project brief")
                    // v0.28.5: capped at 600 tokens (squeezed, then its top part; the whole text and the old brief are kept as notes).
                    Projects.updateBrief(p.id, args.optString("brief"))
                }
                else -> "Unknown tool"
            }
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
            val bytes0 = DataMeter.used()
            try {
                val allTools0 = tools().apply { put(ToolRouter.loadTool()) }.let { if (isPrivate) PrivateChat.filterTools(it) else it }
                    .let { if (Projects.of(id) == null) ProjectDocs.strip(it) else it }
                // Agents don't start or steer other agents; Plan keeps only read-only tools.
                val spNow = specialist
                val allTools = Modes.filterTools(if (agentId == null) allTools0 else JSONArray().apply {
                    // v0.26.2: a background agent running as a specialist gets only its groups' tools (like delegate's runs).
                    val src = if (spNow != null) Specialists.filter(allTools0, spNow) else allTools0
                    for (i in 0 until src.length()) src.getJSONObject(i).let { t -> if (t.optJSONObject("function")?.optString("name") !in BgAgents.leadOnly) put(t) } }, agentPlan)
                val routine = lastUserText().startsWith("[Scheduled routine")
                // v0.26.2: a specialist run has all its own (already filtered) tools on, no views and no chat lane.
                val bulk = routine || spNow != null
                // Speed plan step 1: core tools + the groups this request (and this chat) needs.
                val groupsOn = HashSet<String>().apply {
                    if (bulk) addAll(ToolRouter.groups.map { it.id })
                    addAll(ToolRouter.guess(lastUserText()))
                    addAll(ToolRouter.usedIn(history))
                }
                // v0.23.0 views + skills: the view this request needs (its tools on) and the skills that match it,
                // looked up before the first model call with no extra model call. Shown on the work card.
                val userNow = lastUserText()
                // v0.23.2 follow-on edits: right after a design (or from the Design view's own box), "show engine" edits that design.
                val curDesign = if (bulk) null else try { Designs.current() } catch (_: Exception) { null }
                val followOn = !routine && agentId == null && curDesign != null &&
                    (lastTurnDesign || com.blaze.assistant.ui.Views.showing.value == "design" || Designs.recent())
                turnView = if (bulk) null else ViewRouter.route(userNow, followOn)
                turnDesignNote = if (followOn && curDesign != null && turnView?.id == "design" && ViewRouter.route(userNow)?.id != "design")
                    ViewRouter.followOnHint(curDesign.title, curDesign.kind, curDesign.path) else ""
                turnOk.clear()
                var claimNudged = false
                var proofNudged = false
                var emptyNudged = false
                var leakNudged = false
                var lastOkStep = ""
                turnView?.let { groupsOn += it.groups }
                turnSkills = SkillMatch.forTurn(userNow)
                // v0.26.2: a phone-state question ("am I on mute?") is answered from the Phone now line: no skill packs, no tool loop.
                val stateQ = !routine && PhoneState.kind(userNow) != null
                // v0.28.4: a question about Blaze itself ("take me to blaze-agent repo in github") is a docs lookup: no coding skills
                // (≈ 6,000 chars of them were pulled in by "repo" / "github") and no Coder specialist.
                val selfQ = !routine && BlazeDocs.isAboutBlaze(userNow)
                if (stateQ || selfQ) turnSkills = SkillMatch.Result.NONE
                // v0.30.4 fast actions: a plain device / settings action ("stop GitHub opening links") gets no skill packs
                // (only a learned, replayable skill of the user's own on a strong match), no specialists, none of the heavy
                // tool groups its words happen to hit ("github" → terminal, apps), and the screen tools up front.
                val plainDev = !routine && !bulk && FastActions.plainDevice(userNow)
                if (plainDev) {
                    turnSkills = SkillMatch.learnedOnly(userNow)
                    groupsOn.removeAll(FastActions.heavyGroups - ToolRouter.usedIn(history) - (turnView?.groups ?: emptyList()).toSet())
                    groupsOn += "screen"; groupsOn += "device"
                }
                // v0.24.3 specialists: the matching specialist(s) bring their skill packs and the delegate tool.
                turnSpecs = if (routine || stateQ || selfQ || plainDev || agentId != null || !Settings.bool("specialists_on", true)) emptyList()
                    else try { SpecialistMatch.match(userNow, Specialists.all()) } catch (_: Exception) { emptyList() }
                if (turnSpecs.isNotEmpty()) {
                    turnSkills = SpecialistMatch.attach(turnSkills, turnSpecs, SkillMatch.cached())
                    groupsOn += "extras"
                    add(Role.STEP, SpecialistMatch.cardLine(turnSpecs))
                }
                SkillMatch.cardLine(turnSkills).takeIf { it.isNotBlank() }?.let { add(Role.STEP, it) }
                var learnNudged = false
                var tools = ToolRouter.select(allTools, groupsOn)
                orient.reset(); lastPlan = null
                planRec.reset(); screenActed = false
                // v0.30.7 plan memory: this request worked before → its steps go with the first call as one run_plan to send.
                val planHint = if (routine || bulk || agentId != null || isPrivate || !Settings.phoneControl) ""
                    else try { TapLoop.Plans.find(userNow)?.let { TapLoop.hint(it) } ?: "" } catch (_: Exception) { "" }
                if (planHint.isNotEmpty()) { groupsOn += "screen"; groupsOn += "device"; add(Role.STEP, "Using the steps that worked last time") }
                val orientNote = (if (!routine && "screen" in groupsOn) orientContext() else "").let { o ->
                    if (planHint.isEmpty()) o else if (o.isBlank()) planHint else "$o\n\n$planHint" }
                var orientSent = false
                // Chat lane: small talk and plain questions = one light call with no tools.
                val lastBlaze = msgs.lastOrNull { it.role == Role.BLAZE }?.text.orEmpty()
                var lite = !bulk && planHint.isEmpty() && history.optJSONObject(history.length() - 1)?.opt("content") is String &&
                    ChatLane.fits(lastUserText(), lastBlaze) && turnView == null && turnSkills.full.isEmpty()
                var firstMs = -1L
                streamedVoice = false
                while (trace.length() > 0) trace.remove(0)
                turnTools.clear()
                val turnThink = StringBuilder()
                val turnStart = System.currentTimeMillis()
                thinking = ""
                var gateLeft = 2
                var actedThisTurn = false
                // v0.27.x: any tool ran (lookups too), corrective re-prompts left this turn (all kinds), designs shown this turn.
                var lookedThisTurn = false
                var nudgesLeft = RetryGuard.MAX
                Designs.touched.clear()
                val lastResults = ArrayList<String>()
                // Autopilot: a bigger step budget and a clock; normal turns keep the old cap.
                val autopilot = Modes.autopilot && !routine
                val maxSteps = if (autopilot) Modes.apSteps else MAX_STEPS
                val deadline = if (autopilot) turnStart + Modes.apMinutes * 60_000L else Long.MAX_VALUE
                var autoReplies = 0
                for (step in 0 until maxSteps) {
                    currentStep = step + 1
                    // Steering: what the user added while this chat worked joins before the next model call.
                    generateSequence { steer.poll() }.toList().takeIf { it.isNotEmpty() }?.let { notes ->
                        notes.forEach { n -> add(Role.USER, n) }
                        history.put(JSONObject().put("role", "user").put("content", "[The user adds, while you work: ${notes.joinToString("\n")}]"))
                    }
                    if (System.currentTimeMillis() > deadline) {
                        add(Role.BLAZE, "Autopilot stopped at its time budget (${Modes.apMinutes} min). Say \"continue\" to keep going.")
                        break
                    }
                    mood = Mood.THINKING
                    status = Friendly.between(turnTools.lastOrNull())
                    var liveId = -1L
                    spokenPrefix = ""
                    val before = turnThink.toString()
                    var pendingFollowups = emptyList<String>()
                    val liteNow = lite
                    val onText: ((String) -> Unit)? = if (Settings.streamReplies) { partial ->
                        if (!(liteNow && partial.trimStart().let { it.startsWith("[") || it.startsWith("TOOLS", true) })) scope.launch {
                            val p = ReplyParts.split(partial)
                            if (p.thinking.isNotEmpty()) thinking = listOf(before, p.thinking).filter { it.isNotBlank() }.joinToString("\n\n")
                            // A tool call the model is writing as text never flashes up as a reply.
                            if (p.text.isNotEmpty() && !(liveId < 0 && FakeCall.callish(p.text))) {
                                if (liveId < 0) { liveId = add(Role.BLAZE, p.text); status = "Writing the reply" } else replace(liveId, Role.BLAZE, p.text)
                                speakAsItStreams(p.text)
                            }
                        }
                    } else null
                    val onThink: (String) -> Unit = { t -> scope.launch { thinking = listOf(before, t).filter { it.isNotBlank() }.joinToString("\n\n") } }

                    // v0.30.7: no blocking summarise call in the middle of a phone task (a context error below still folds).
                    if (!lite && TapLoop.compactNow(step, screenActed)) compact()
                    LlmClient.liteTurn = lite
                    var req = if (lite) ChatLane.messages(requestMessages(), lastUserText(), personaText(), Settings.userName.ifBlank { null }) else requestMessages()
                    // Orient loop, step 1 (context → orientation): where the phone is, sent with the first call only, never saved.
                    val orientNow = !lite && !orientSent && orientNote.isNotEmpty()
                    if (orientNow) { req.put(JSONObject().put("role", "user").put("content", orientNote)); orientSent = true }
                    tools = if (lite) JSONArray() else ToolRouter.select(allTools, groupsOn)
                    // v0.26.0 auto-compaction: what the system prompt and tools cost, so the next fold sees the whole request.
                    if (!lite) overheadTok = ((req.optJSONObject(0)?.optString("content")?.length ?: 0) + tools.toString().length) / 4
                    // Watchdog: only a model that goes SILENT (no text, thinking or tool-call chunks) for the
                    // limit is cut off and retried once on the same model. Long thinking is fine while it streams.
                    val lane = if (step == 0) LlmClient.Lane.MAIN else LlmClient.Lane.STEPS
                    val limit = Settings.str("model_timeout_s", "100").toLongOrNull()?.coerceIn(30, 600)?.times(1000) ?: 100_000L
                    suspend fun call(r: JSONArray): JSONObject = Agent.idleGuard(limit) { LlmClient.complete(r, tools, onText, onThink, lane) }
                        ?: run {
                            add(Role.STEP, "The model went silent, retrying")
                            if (liveId >= 0) { msgs = msgs.filterNot { it.id == liveId }; liveId = -1L }
                            Agent.idleGuard(limit) { LlmClient.complete(r, tools, onText, onThink, lane) }
                                ?: throw LlmClient.LlmException("${LlmClient.lastRoute.ifBlank { "The model" }} sent nothing for ${limit / 1000}s, twice. Try again, or pick a faster model in Settings → AI.")
                        }
                    val reply = try { call(req) } catch (e: LlmClient.LlmException) {
                        // v0.26.0: "too long for the model" → fold older turns into the summary (same chat, nothing
                        // asked) and retry once. Only if that still fails does the user see an error.
                        if (liteNow || !(LlmClient.tooLarge(e) || AutoCompact.isContextError(e.message, e.code))) throw e
                        if (liveId >= 0) { msgs = msgs.filterNot { it.id == liveId }; liveId = -1L }
                        squeezed = true
                        val folded = compact(force = true)
                        val retry = requestMessages()
                        if (!folded && LongChat.tokens(retry) >= LongChat.tokens(req)) throw e
                        if (orientNow) retry.put(JSONObject().put("role", "user").put("content", orientNote))
                        req = retry
                        call(req)
                    }
                    if (firstMs < 0 && LlmClient.lastFirstTokenAt > 0) firstMs = (LlmClient.lastFirstTokenAt - turnStart).coerceAtLeast(0)
                    reply.optString("_reasoning").takeIf { it.isNotBlank() }?.let { if (turnThink.isNotEmpty()) turnThink.append("\n\n"); turnThink.append(it) }
                    reply.remove("_reasoning")
                    try { Usage.record(req, reply, LlmClient.lastUsage) } catch (_: Exception) { }
                    try {
                        val u = LlmClient.lastUsage
                        val inT = u?.optInt("prompt_tokens")?.takeIf { it > 0 } ?: (LongChat.tokens(req) + tools.toString().length / 4)
                        val outT = u?.optInt("completion_tokens")?.takeIf { it > 0 } ?: (LongChat.text(reply).length / 4)
                        meter = Meter(inT, outT, LongChat.meterLimit(ctxWindow()))
                    } catch (_: Exception) { }
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
                            val seen = if (txt != null) ReplyParts.split(txt, keepCalls = true).text else ""
                            if (txt != null) (ToolCalls.fromText(seen, allTools) ?: ToolLeak.toCalls(seen, allTools))?.let { (c, rest) ->
                                reply.put("tool_calls", c); reply.put("content", if (rest.isBlank()) JSONObject.NULL else ToolLeak.strip(rest))
                                for (k in 0 until c.length()) ToolStats.add(c.getJSONObject(k).getJSONObject("function").getString("name"), "text_call")
                            }
                            // v0.28.1: tool syntax that maps to no real tool: strip it and ask the model once to answer properly
                            // (native tool call or plain words); after that, an honest plain line, never the raw syntax.
                            if (txt != null && (reply.optJSONArray("tool_calls")?.length() ?: 0) == 0 && ToolLeak.has(seen)) {
                                val left = ReplyParts.split(txt, final = true).text
                                if (!leakNudged && step < maxSteps - 2) {
                                    leakNudged = true
                                    if (liveId >= 0) { msgs = msgs.filterNot { it.id == liveId }; liveId = -1L }
                                    add(Role.STEP, "Getting a proper answer")
                                    if (left.isNotBlank()) history.put(JSONObject().put("role", "assistant").put("content", left))
                                    history.put(JSONObject().put("role", "user").put("content", RetryGuard.anchor(ToolLeak.NUDGE, lastUserText())))
                                    continue
                                }
                                reply.put("content", left.ifBlank { ToolLeak.HONEST })
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
                    var textOut = when (val content = reply.opt("content")) {
                        is String -> content
                        is JSONArray -> (0 until content.length()).joinToString("") {
                            content.optJSONObject(it)?.optString("text") ?: ""
                        }
                        else -> ""
                    }.trim()

                    // Autopilot: a reply that just asks the user something gets "decide yourself" (at most 8 times).
                    if ((calls == null || calls.length() == 0) && autopilot && autoReplies < 8 && step < maxSteps - 2 && Modes.asksUser(textOut)) {
                        autoReplies++
                        if (liveId < 0 && textOut.isNotBlank()) add(Role.BLAZE, ReplyParts.split(textOut, final = true).text)
                        add(Role.STEP, "Autopilot: deciding myself")
                        history.put(JSONObject().put("role", "user").put("content", Modes.AUTO_PREFIX + Modes.AUTO_REPLY))
                        continue
                    }
                    // Views first (v0.23.0): flashcards or a quiz written as text on a Learn turn become a Learn deck,
                    // or the model is asked once to make the deck itself.
                    if ((calls == null || calls.length() == 0) && !routine && turnView?.id == "learn") {
                        val called = synchronized(turnTools) { Flashcards.TOOL in turnTools }
                        when (ViewRouter.shouldConvert(lastUserText(), ReplyParts.split(textOut, final = true).text, called, learnNudged)) {
                            ViewRouter.Fix.CONVERT -> {
                                val cards = ViewRouter.cardsFrom(ReplyParts.split(textOut, final = true).text)
                                val deck = ViewRouter.deckName(lastUserText())
                                val n = kotlinx.coroutines.withContext(Dispatchers.IO) { Flashcards.add(deck, cards) }
                                add(Role.STEP, "Made the \u201c$deck\u201d deck in Learn")
                                turnTools.add(Flashcards.TOOL)
                                turnOk += ClaimGuard.Call(Flashcards.TOOL)
                                textOut = ViewRouter.converted(deck, n, cards.size)
                                reply.put("content", textOut)
                                if (agentId == null) kotlinx.coroutines.withContext(Dispatchers.Main) { com.blaze.assistant.ui.Views.openOrOffer("learn", "", lastUserText(), Modes.autopilot) }
                            }
                            ViewRouter.Fix.REPROMPT -> if (step < maxSteps - 3) {
                                learnNudged = true
                                if (liveId >= 0) { msgs = msgs.filterNot { it.id == liveId }; liveId = -1L }
                                add(Role.STEP, "Putting the cards in Learn")
                                history.put(JSONObject().put("role", "user").put("content", ViewRouter.NUDGE))
                                continue
                            }
                            ViewRouter.Fix.NONE -> {}
                        }
                    }
                    // v0.23.2 no fake claims: "I've updated the design" / "view it in the design section" with no
                    // design or open_view call that succeeded this turn → one re-prompt, then an honest line instead.
                    if ((calls == null || calls.length() == 0) && !routine) {
                        val shown = ReplyParts.split(textOut, final = true).text
                        val bad = synchronized(turnOk) { ClaimGuard.unbacked(shown, turnOk.toList()) }
                        if (bad != null) {
                            // v0.27.x: only a claim about what the user asked for is worth doing for real; a stray claim about
                            // another view ("show me your memories" → "Opened the solar diagram in Design") is dropped, never acted on.
                            val related = ClaimGuard.relevant(bad, lastUserText(), turnView?.id)
                            val rest = if (related) "" else ClaimGuard.drop(shown, bad)
                            if (!related && rest.isNotEmpty()) {
                                textOut = rest
                                reply.put("content", textOut)
                                pendingFollowups = emptyList()
                            } else if (!claimNudged && nudgesLeft > 0 && step < maxSteps - 3) {
                                claimNudged = true
                                nudgesLeft--
                                if (related) groupsOn += bad.groups
                                if (liveId >= 0) { msgs = msgs.filterNot { it.id == liveId }; liveId = -1L }
                                add(Role.STEP, if (related) RetryGuard.label(RetryGuard.Kind.CLAIM, bad.name) else RetryGuard.label(RetryGuard.Kind.OFF_TOPIC))
                                val n = if (related) ClaimGuard.nudge(bad, if (bad.id == "design" && (lastTurnDesign || Designs.recent())) Designs.current() else null) else RetryGuard.OFF_TOPIC
                                history.put(JSONObject().put("role", "user").put("content", RetryGuard.anchor(n, lastUserText())))
                                continue
                            } else {
                                textOut = ClaimGuard.honest(shown, bad)
                                reply.put("content", textOut)
                                pendingFollowups = emptyList()
                            }
                        }
                    }
                    // v0.28.8 proof for every action (v0.28.7 did sends): "Message sent", "Alarm set for 7", "I turned on Wi-Fi",
                    // "Saved to memory" with no tool of that kind succeeding this turn → one re-prompt to really do it, then the truth.
                    if ((calls == null || calls.length() == 0) && !routine) {
                        val shown = ReplyParts.split(textOut, final = true).text
                        val okNames = synchronized(turnOk) { turnOk.map { it.name } }
                        val miss = ActionProof.unbacked(shown, okNames, lastUserText())
                        if (miss != null) {
                            if (!proofNudged && nudgesLeft > 0 && step < maxSteps - 3) {
                                proofNudged = true
                                nudgesLeft--
                                if (liveId >= 0) { msgs = msgs.filterNot { it.id == liveId }; liveId = -1L }
                                add(Role.STEP, RetryGuard.label(RetryGuard.Kind.CLAIM))
                                history.put(JSONObject().put("role", "user").put("content", RetryGuard.anchor(ActionProof.nudge(miss), lastUserText())))
                                continue
                            }
                            textOut = ActionProof.honest(shown, miss); reply.put("content", textOut); pendingFollowups = emptyList()
                        }
                    }
                    if ((calls == null || calls.length() == 0) && step < maxSteps - 4 && gateLeft > 0 && nudgesLeft > 0 && !routine) {
                        val nudge = when {
                            FakeCall.looksFake(ReplyParts.split(textOut, final = true).text, ToolCalls.names(allTools), actedThisTurn, lastUserText(), lookedThisTurn) -> FakeCall.NUDGE
                            Autonomy.looksLikeRefusal(textOut) -> Autonomy.REFUSAL_NUDGE
                            actedThisTurn && Autonomy.lastFailed(lastResults) -> Autonomy.VERIFY_NUDGE
                            else -> null
                        }
                        if (nudge != null) {
                            gateLeft--
                            nudgesLeft--
                            if (liveId >= 0) msgs = msgs.filterNot { it.id == liveId }
                            add(Role.STEP, RetryGuard.label(if (nudge == FakeCall.NUDGE) RetryGuard.Kind.FAKE_CALL else RetryGuard.Kind.REFUSAL))
                            // v0.27.x: a retry stays on the user's latest request.
                            history.put(JSONObject().put("role", "user").put("content", RetryGuard.anchor(nudge, lastUserText())))
                            lastResults.clear()
                            continue
                        }
                    }
                    // v0.26.2: an empty final answer (reasoning only, or only tags) is never shown as "Done.": a specific line
                    // when the user asked for an action and a tool succeeded; else ask the model once more, then fail plainly.
                    if ((calls == null || calls.length() == 0) && ReplyParts.split(textOut, final = true).text.isBlank()) {
                        val done = EmptyReply.finalText(lastUserText(), lastOkStep)
                        if (done != null) { textOut = done; reply.put("content", done) }
                        else {
                            if (history.length() > 0 && history.optJSONObject(history.length() - 1) === reply) history.remove(history.length() - 1)
                            if (!emptyNudged && step < maxSteps - 2) {
                                emptyNudged = true
                                if (liveId >= 0) { msgs = msgs.filterNot { it.id == liveId }; liveId = -1L }
                                add(Role.STEP, "Asking for the answer again")
                                history.put(JSONObject().put("role", "user").put("content", EmptyReply.NUDGE))
                                continue
                            }
                            throw LlmClient.LlmException(EmptyReply.FAILED, EmptyReply.CODE)
                        }
                    }
                    if (calls == null || calls.length() == 0) {
                        // v0.27.x: the reply only describes what the design tool really produced (a flat diagram is not "3D").
                        if (Designs.touched.isNotEmpty()) {
                            val g = Designs.groundReply(textOut, Designs.touched.toList())
                            if (g != textOut) { textOut = g; reply.put("content", g) }
                        }
                        kotlinx.coroutines.delay(60)   // let the last streamed chunk land first
                        // v0.30.7 plan memory: the phone steps of a task that ended well are kept for this request.
                        if (!routine && !bulk && agentId == null && !isPrivate && !proofNudged && !claimNudged) try {
                            planRec.recipe()?.let { TapLoop.Plans.put(userNow, it) }
                        } catch (_: Exception) { }
                        val secs = (System.currentTimeMillis() - turnStart) / 1000.0
                    if (step > 0) { com.blaze.assistant.device.Haptics.done(app); com.blaze.assistant.device.Sounds.done() }
                        com.blaze.assistant.device.CrashLog.slow("turn ($step steps, ${LlmClient.lastRoute})", System.currentTimeMillis() - turnStart)
                        SpeedLog.add(SpeedLog.Entry(turnStart, firstMs.coerceAtLeast(0), System.currentTimeMillis() - turnStart, step + 1,
                            tools.length(), req.toString().length + tools.toString().length, LlmClient.lastRoute))
                        addReply(liveId, textOut, turnThink.toString(), pendingFollowups,
                            if (Settings.showRoute) "☁ " + listOf(LlmClient.lastRoute.ifBlank { "Blaze Cloud" }.replace(" · ", " / "),
                                if (firstMs > 0) "first word %.1f s".format(firstMs / 1000.0) else "",
                                "%.1f s".format(secs),
                                if (step > 0) "${step + 1} steps" else "").filter { it.isNotBlank() }.joinToString(" · ") else "")
                        break
                    }
                    // Text that came with tool calls is narration, shown as a step; its reasoning joins the turn's.
                    val narr = ReplyParts.split(textOut).let { it.copy(text = Friendly.plain(it.text)) }
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
                    val readAt = FastActions.screenReadAt((0 until calls.length()).map { callName(it) }, screenActions + "do_actions")
                    for (i in 0 until calls.length()) {
                        val call = calls.getJSONObject(i)
                        val fn = call.getJSONObject("function")
                        val name = fn.getString("name")
                        val args = try { JSONObject(fn.optString("arguments", "{}").ifBlank { "{}" }) }
                            catch (_: Exception) { JSONObject() }
                        if (name !in Friendly.selfNarrating && name !in unknownCalls) addStep(Friendly.say(name, args), Friendly.exact(name, args))
                        if (pre.containsKey(i)) autoStepId = -1L
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
                        if (name !in setOf("find_tool", "load_tools")) lookedThisTurn = true
                        lastResults += result
                        if (ClaimGuard.succeeded(result)) { turnOk += ClaimGuard.Call(name, args); lastOkStep = msgs.lastOrNull { it.role == Role.STEP }?.text.orEmpty() }
                        if (name == "run_plan" || name == "read_screen" || name == com.blaze.assistant.phone.SettingsJump.TOOL) groupsOn += "screen"
                        if (name in screenActions && !result.startsWith("Error") && !result.startsWith("Couldn't")) com.blaze.assistant.device.Haptics.tick(app)
                        // v0.30.4 batching: several screen actions in one reply run back to back with a short settle; the
                        // screen is read once, after the last of them.
                        if (name == "do_actions" || (name in screenActions && !FastActions.isScreen(result))) {
                            if (i in readAt || name == "do_actions") result += screenAfter(result)
                            else com.blaze.assistant.phone.BlazeAccessibilityService.instance?.settle(700, 150)
                        }
                        history.put(
                            JSONObject().put("role", "tool")
                                .put("tool_call_id", call.optString("id", "call_$step$i"))
                                .put("name", name)
                                .put("content", if (!Settings.longChat || name in LongChat.keepWhole || FastActions.isScreen(result)) result else LongChat.aside(result))
                        )
                        // v0.30.4: only the newest screen stays in the history; older readouts are dropped in place.
                        if (FastActions.isScreen(result)) FastActions.pruneScreens(history)
                    }
                    if (Settings.lowData) shots.clear()
                    shots.lastOrNull()?.let { url ->
                        history.put(JSONObject().put("role", "user").put("content", JSONArray()
                            .put(JSONObject().put("type", "text").put("text", SHOT_NOTE))
                            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url)))))
                    }
                    if (step == maxSteps - 1) add(Role.BLAZE, "I've taken a lot of steps on this one, so I'm pausing here. Tell me if you want me to keep going.")
                }
            } catch (e: CancellationException) {
                add(Role.STEP, "Stopped.")
                throw e
            } catch (e: LlmClient.UsedUp) {
                val id = add(Role.ERROR, e.message ?: "Credits used up.")
                msgs = msgs.map { if (it.id == id) it.copy(meta = "usedup:${e.pid}|${e.model}") else it }
            } catch (e: LlmClient.LlmException) {
                add(Role.ERROR, e.message ?: "Something went wrong.")
            } catch (e: Exception) {
                com.blaze.assistant.device.CrashLog.error("agent loop", e)
                add(Role.ERROR, e.message ?: "Something went wrong.")
            } finally {
                // Low-data mode: how much this task used (Blaze's own traffic: models, web, browser).
                if (Settings.lowData) try { DataMeter.chip(bytes0)?.let { chip -> msgs.lastOrNull { it.role == Role.BLAZE }?.let { addMeta(it.id, chip) } } } catch (_: Exception) { }
                LlmClient.liteTurn = false
                lastTurnDesign = ViewRouter.byId("design")?.let { d -> synchronized(turnOk) { turnOk.any { ClaimGuard.backs(d, it) } } } == true
                if (agentId == null) com.blaze.assistant.phone.Pointer.done()
                thinking = ""
                mood = Mood.IDLE
                currentStep = 0
                persist()
                announce()
                learnAfterTurn()
                if (wakes.isNotEmpty()) scope.launch { job?.join(); wakes.poll()?.let { wake(it) } }
            }
        }

        /**
         * v0.26.2 delegate: hands specialist tasks out WITHOUT blocking. Each brief becomes a background agent running as
         * that specialist (its card with Steer / Stop in the chat); the turn carries on and each result wakes this chat as it
         * lands. [wait] (or a private chat, whose work must never be written to disk) keeps the old blocking run.
         */
        suspend fun delegate(args: JSONObject): String {
            val tasks = args.optJSONArray("tasks") ?: JSONArray()
            val briefs = (0 until tasks.length()).mapNotNull { tasks.optJSONObject(it) }.map { o ->
                val spec = Specialists.byId(o.optString("specialist")) ?: Specialists.builtIn.first()
                Dispatch.Brief(spec.id, o.optString("task").trim(), o.optString("title").trim())
            }
            if (args.optBoolean("wait") || isPrivate || !Settings.bool("bg_agents", true)) return delegateAndWait(briefs)
            val live = BgAgents.all.value.filter { it.live && it.parent == id && it.specialist.isNotBlank() }.map { Dispatch.Brief(it.specialist, it.task) }
            val plan = Dispatch.plan(briefs, live)
            val specs = plan.accepted.map { Specialists.byId(it.specialist) ?: Specialists.builtIn.first() }
            val scope = Dispatch.scopeLine(args.optString("scope"), specs.map { it.name })
            if (scope.isNotBlank()) { val mid = add(Role.BLAZE, scope); addMeta(mid, "Specialists") }
            val started = plan.accepted.zip(specs).map { (b, sp) ->
                val bg = BgAgents.start(b.task, b.title.ifBlank { Dispatch.title(b.task) }, id, sp.id)
                val mid = add(Role.BLAZE, "${sp.name} ${bg.id}: ${bg.title}")
                msgs = msgs.map { if (it.id == mid) it.copy(meta = BgAgents.CARD + bg.id) else it }
                bg
            }
            persist()
            val queued = started.count { BgAgents.get(it.id)?.status == BgAgents.QUEUED }
            return Dispatch.startedLine(started.map { it.id to (BgAgents.spec(it)?.name ?: it.specialist) }, plan.rejected, queued, scope.isNotBlank())
        }

        /** The blocking run (delegate wait=true): non-phone specialists in parallel, phone ones one after another. */
        private suspend fun delegateAndWait(briefs: List<Dispatch.Brief>): String = kotlinx.coroutines.coroutineScope {
            val all = tools().let { if (isPrivate) PrivateChat.filterTools(it) else it }.let { if (Projects.of(id) == null) ProjectDocs.strip(it) else it }
            val jobs = briefs.take(Dispatch.MAX_TASKS).mapIndexed { i, b -> Triple(i, Specialists.byId(b.specialist) ?: Specialists.builtIn.first(), b.task) }
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

        private suspend fun runSpecialist(spec0: Specialists.Spec, task: String, all: JSONArray): String {
            // v0.26.2: its field playbook goes into its prompt (a custom one without a playbook gets one written first).
            val spec = Specialists.byId(spec0.id) ?: spec0
            val playbook = Playbooks.forSpec(spec).ifBlank { if (spec.custom && !isPrivate) Agent.ensurePlaybook(spec) else "" }
            val tools = Specialists.filter(all, spec)
            val msgs = JSONArray()
                .put(JSONObject().put("role", "system").put("content", Playbooks.specialistSystem(spec, playbook, SpecialistMatch.brief(spec, SkillMatch.cached()),
                    SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date()), Memory.promptBlock(task))))
                .put(JSONObject().put("role", "user").put("content", task))
            val shots = mutableListOf<String>()
            var reviewed = playbook.isBlank()
            for (step in 0 until 16) {
                val reply = LlmClient.complete(msgs, if (tools.length() > 0) tools else null, null, null, LlmClient.Lane.STEPS)
                reply.remove("_reasoning")
                try { Usage.record(msgs, reply, LlmClient.lastUsage) } catch (_: Exception) { }
                val unknown = try {
                    if ((reply.optJSONArray("tool_calls")?.length() ?: 0) == 0) (reply.opt("content") as? String)?.let { txt ->
                        (ToolCalls.fromText(txt, all) ?: ToolLeak.toCalls(txt, all))?.let { (c, rest) -> reply.put("tool_calls", c); reply.put("content", if (rest.isBlank()) JSONObject.NULL else ToolLeak.strip(rest)) } }
                    ToolCalls.normalize(reply, all).unknown
                } catch (_: Exception) { emptySet() }
                msgs.put(reply)
                val calls = reply.optJSONArray("tool_calls")
                if (calls == null || calls.length() == 0) {
                    val draft = ReplyParts.split(reply.optString("content")).text.ifBlank { "(no result)" }
                    // Self-check: one review against the playbook; if it fails, one redo with the exact fixes.
                    if (!reviewed) {
                        reviewed = true
                        add(Role.STEP, "${spec.name}: checking its work")
                        val r = Agent.reviewDraft(spec, playbook, task, draft)
                        if (!r.pass) {
                            add(Role.STEP, "${spec.name}: fixing ${r.fixes.size} thing(s)")
                            msgs.put(JSONObject().put("role", "user").put("content", Playbooks.redoPrompt(r.fixes)))
                            continue
                        }
                    }
                    return draft
                }
                for (i in 0 until calls.length()) {
                    val call = calls.getJSONObject(i)
                    val fn = call.getJSONObject("function")
                    val name = fn.getString("name")
                    val args = try { JSONObject(fn.optString("arguments", "{}").ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
                    var result = if (name in unknown) "Error: there is no tool called \"$name\". Use one of the tools you were given (exact name)." else runTool(name, args, shots)
                    if (name == "do_actions" || (name in screenActions && !FastActions.isScreen(result))) result += screenAfter(result)
                    msgs.put(JSONObject().put("role", "tool").put("tool_call_id", call.optString("id", "s$step$i")).put("name", name).put("content", result.take(12000)))
                }
                shots.lastOrNull()?.let { url -> shots.clear()
                    msgs.put(JSONObject().put("role", "user").put("content", JSONArray()
                        .put(JSONObject().put("type", "text").put("text", SHOT_NOTE))
                        .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url))))) }
            }
            return "Stopped after 16 steps without finishing."
        }

        /** create_specialist: validated playbook or a precise list of what to fix (a few tries per id). */
        private fun createSpecialist(args: JSONObject): String {
            val key = Specialists.slug(args.optString("id").ifBlank { args.optString("name") })
            val n = (createTries[key] ?: 0) + 1
            if (n > Playbooks.MAX_TRIES) return "Not saved: the playbook failed validation ${Playbooks.MAX_TRIES} times. Do the task yourself without a new specialist."
            createTries[key] = n
            val groups = args.optJSONArray("groups")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: args.optString("groups").split(',')
            val existing = Specialists.custom()
            val others = Specialists.builtIn.map { Playbooks.builtIn(it.id) } + existing.filter { it.id != key }.map { it.playbook }
            val m = Specialists.make(args.optString("id"), args.optString("name"), args.optString("prompt"), groups, args.optString("playbook"), existing, others, n)
            if (m.ok && m.spec != null) {
                Specialists.upsert(m.spec)
                createTries.remove(key)
                add(Role.STEP, "Made a new specialist: ${m.spec.name}")
            } else add(Role.STEP, "Specialist playbook needs work (try $n of ${Playbooks.MAX_TRIES})")
            return m.message
        }

        /** Fresh compact screen after an action, so the model doesn't spend a turn on read_screen (the orient loop's "look after"). */
        suspend fun screenAfter(result: String): String {
            val svc = com.blaze.assistant.phone.BlazeAccessibilityService.instance ?: return ""
            if (!Settings.phoneControl || result.startsWith("Error") || result.startsWith("No app") || result.startsWith("Phone control is off") || result.startsWith("Not done yet")) return ""
            svc.settle(1200, 200)
            val s = svc.readScreen(diff = true)
            orient.saw(svc.currentPkg())
            return "\n\nScreen now:\n" + s + com.blaze.assistant.phone.Watchers.drain()
        }

        /** v0.30.7: screen tools whose steps go into plan memory (run_plan records its own steps). */
        private val planTools = setOf("open_app", "tap", "type_text", "scroll", "press_button", "search_in_app", "open_settings", "read_screen", "look_at_screen")

        /** Orient loop state for this turn (v0.20.0). */
        val orient = Orient.State()
        /** v0.30.4: the last run_plan this turn (for resuming a failed script instead of restarting it). */
        @Volatile var lastPlan: FastActions.PlanRun? = null
        /** Secure-form asks per site this session, so a refusing site can't loop on the form. */
        private val formAsks = HashMap<String, Int>()

        /** browser_secure_input: secure card in the chat → values straight into the page; the model gets placeholders. */
        private suspend fun secureInput(args: JSONObject): String {
            val B = com.blaze.assistant.browser.BrowserEngine
            val S = com.blaze.assistant.browser.SecureInput
            val V = com.blaze.assistant.browser.Vault
            val host = kotlinx.coroutines.withContext(Dispatchers.Main) { B.host() }
            if (host.isBlank()) return "No page is open in Blaze's browser. Open the sign-in page with browser_open first, then call this again."
            // v0.28.2: no secure form or saved login on a plain-http page (except this phone / the LAN).
            val pageUrl = kotlinx.coroutines.withContext(Dispatchers.Main) { B.url() }
            if (!com.blaze.assistant.connect.UrlSafety.autofillAllowed(pageUrl)) return com.blaze.assistant.connect.UrlSafety.AUTOFILL_REFUSED
            val fields = S.parseFields(args.optJSONArray("fields"))
            if (fields.isEmpty()) return "Error: give fields, e.g. [{\"name\":\"email\",\"kind\":\"email\"},{\"name\":\"password\",\"kind\":\"password\"}]."
            if (fields.any { S.isCardField(it) }) return "Card details are never collected in the chat. Show the live browser with browser_show so the user types them on the page, or stop before paying."
            if (fields.any { it.kind == com.blaze.assistant.browser.SecureInput.Kind.PASSWORD }) B.passwordRefused()?.let { return it }
            val asks = (formAsks[host] ?: 0) + 1; formAsks[host] = asks
            if (asks > 50) return "The user cancelled the form for $host in this task. Don't ask again: stop this sign-in and say what you needed."
            if (asks > 3) return "I've already asked for this on $host 3 times. Don't ask again: use ask_user to offer Continue with Google, a passkey, or the user doing it themselves in the live browser (browser_show)."
            val names = fields.map { it.name }
            val fromVault = !args.optBoolean("fresh") && V.has(host, names)
            val title = args.optString("title").ifBlank { "Details for $host" }
            val answer = if (fromVault) {
                add(Role.STEP, "Using your saved details for $host")
                com.blaze.assistant.browser.SecureInput.Answer(names.associateWith { V.get(host, it).orEmpty() }, false)
            } else {
                add(Role.STEP, "Waiting for you: ${title.take(60)}")
                mood = Mood.IDLE
                val a = askSecure(com.blaze.assistant.browser.SecureInput.Request(ids.getAndIncrement(), host, title, args.optString("reason"), fields,
                    args.optBoolean("submit", true), kotlinx.coroutines.CompletableDeferred()), this)
                mood = Mood.ACTING
                a ?: run {
                    // v0.30.5: a cancel ends this sign-in; the next step of the same signup doesn't pop a new card.
                    formAsks[host] = 99
                    return "The user cancelled the form (or didn't answer). STOP this sign-in / sign-up now: don't call browser_secure_input again for $host in this task, and don't keep filling its steps. Say in one line what you needed and offer the live browser (browser_show) if they want to finish it themselves."
                }
            }
            answer.values.forEach { (n, v) -> S.put(n, v) }
            if (answer.save) fields.filter { it.kind.saveable }.forEach { f -> answer.values[f.name]?.takeIf { it.isNotEmpty() }?.let { V.save(host, f.name, it) } }
            add(Role.STEP, "Filling the page on $host")
            val missed = fields.filter { f -> val v = answer.values[f.name].orEmpty(); v.isEmpty() || !B.fillSecret(f, v) }
            var out = S.summary(fields, answer.save, fromVault)
            if (missed.isNotEmpty()) out += " Couldn't find the page field for ${missed.joinToString { it.label }}: call browser_read, then browser_type number=N text=\"${S.placeholder(missed.first().name)}\"."
            else if (args.optBoolean("submit", true)) out += "\n" + B.submitSecret()
            return S.scrub(out)
        }

        /** Every tool call goes through here: a crash or an empty result becomes a plain error with a fix. */
        suspend fun runTool(name: String, args: JSONObject, shots: MutableList<String>): String {
            // Plan mode is enforced here, at dispatch, not only in the prompt.
            Modes.block(name, args, agentPlan).takeIf { it.isNotEmpty() }?.let { add(Role.STEP, "Plan mode: skipped ${name.replace('_', ' ')}"); return it }
            if (agentId != null && name in BgAgents.leadOnly) return "Error: background agents can't start or steer other agents. Do the work yourself."
            if (isPrivate && PrivateChat.blocked(name)) return PrivateChat.REFUSAL
            // Visible pointer (v0.21.0): while the user has the pointer, screen actions wait for them.
            if (name in Orient.acting && com.blaze.assistant.phone.Pointer.enabled) pointerGate(name)?.let { return it }
            // Orient loop (v0.20.0): know where we are before acting on the screen.
            val (held, glance) = try { orientFirst(name, args) } catch (e: CancellationException) { throw e } catch (_: Throwable) { null to "" }
            if (held != null) return held
            // v0.30.7 plan memory: the element's words before it's acted on (numbers change, words replay).
            val recName = if (name == com.blaze.assistant.phone.SettingsJump.TOOL) "open_settings" else name
            val recSvc = com.blaze.assistant.phone.BlazeAccessibilityService.instance
            val recLabel = if ((name == "tap" || name == "type_text") && args.has("element") && args.optString("text").isBlank())
                try { recSvc?.labelOf(args.optInt("element")).orEmpty() } catch (_: Throwable) { "" } else ""
            recSvc?.lastToggle = null
            val r = try { runToolRaw(name, args, shots) }
                catch (e: CancellationException) { throw e }
                catch (e: Throwable) { com.blaze.assistant.device.CrashLog.error("tool $name", e); ToolGuide.crash(name, e) }
            if (recName in planTools) {
                screenActed = true
                if (TapLoop.failed(r)) planRec.fail()
                else if (recName !in TapLoop.lookOnly) planRec.ok(TapLoop.stepOf(recName, args, recLabel, if (name == "tap") recSvc?.lastToggle else null))
            }
            // v0.28.2: outside content is now in the context: later sends / deletes this turn always ask (ConfirmGate).
            ConfirmGate.sourceOf(name)?.let { taint.mark(it) }
            // v0.30.4: any result that carries the screen (open_settings, a stopped run_plan) counts as a look, so the next
            // numbered tap isn't bounced with "Looking at the screen first" (a whole extra model round trip).
            if ((name in Orient.looking || FastActions.isScreen(r)) && !r.startsWith("Phone control")) com.blaze.assistant.phone.BlazeAccessibilityService.instance?.let { orient.saw(it.currentPkg()) }
            return if (r.isBlank()) ToolGuide.empty(name) else glance + r
        }

        /**
         * Before a screen action: GO, a compact glance put in front of the result, or (for numbered taps
         * on a screen Blaze hasn't seen this turn) hold the action and return the fresh screen instead.
         */
        private suspend fun orientFirst(name: String, args: JSONObject): Pair<String?, String> {
            if (name !in Orient.acting || !Settings.phoneControl) return null to ""
            val svc = com.blaze.assistant.phone.BlazeAccessibilityService.instance ?: return null to ""
            val now = svc.currentPkg()
            return when (Orient.decide(name, args, orient, now)) {
                Orient.Decision.GO -> null to ""
                Orient.Decision.LOOK_FIRST -> {
                    orient.bounced++
                    add(Role.STEP, "Looking at the screen first")
                    val screen = svc.readScreen()
                    orient.saw(now)
                    Orient.lookFirst(name, screen) to ""
                }
                Orient.Decision.GLANCE -> {
                    val screen = svc.readScreen()
                    orient.saw(now)
                    null to Orient.glanceNote(Orient.compact(screen))
                }
            }
        }

        /** Paused → wait for the user: Resume goes on (and looks again), a double-tap clicks there, Stop ends the task. */
        private suspend fun pointerGate(name: String): String? {
            val P = com.blaze.assistant.phone.Pointer
            val wasPaused = P.mode == com.blaze.assistant.phone.Pointer.Mode.PAUSED
            if (wasPaused) { add(Role.STEP, "Paused: you have the pointer"); mood = Mood.IDLE }
            val e = P.gate()
            if (wasPaused) mood = Mood.ACTING
            return when (e) {
                null -> { if (wasPaused) orient.pkg = ""; null }
                is com.blaze.assistant.phone.Pointer.Event.Click -> {
                    // v0.24.4: check before clicking: read what's under the tip, show "Click '…'?" for ~1.5 s (Cancel stops it),
                    // or ask first when it's unreadable, ambiguous or risky (pay / send / delete…).
                    val svc = com.blaze.assistant.phone.BlazeAccessibilityService.instance
                    val (label, n) = try { svc?.labelAt(e.x, e.y) ?: (null to 0) } catch (_: Exception) { null to 0 }
                    val line = P.clickLine(label)
                    val go = when (P.checkClick(label, n)) {
                        com.blaze.assistant.phone.Pointer.ClickCheck.SHOW_THEN_CLICK -> P.showCheck(line)
                        com.blaze.assistant.phone.Pointer.ClickCheck.ASK -> Agent.confirm(line,
                            when { P.risky(label) -> "This looks like it pays, sends or deletes something."
                                n > 1 -> "There's more than one thing under the pointer."
                                else -> "I can't read what's under the pointer." }, "Click", always = P.risky(label))
                    }
                    if (!go) {
                        add(Role.STEP, "Didn't click: you cancelled")
                        orient.pkg = ""
                        return "The user double-tapped the pointer${label?.let { " on \u201c$it\u201d" } ?: ""} but cancelled the click, so nothing was clicked. " +
                            "Your planned $name was NOT done. Look at the screen below and carry on with the task from here."
                    }
                    add(Role.STEP, if (label != null) "Clicking \u201c${label.take(40)}\u201d where you pointed" else "Clicking where you pointed")
                    val ok = svc?.tapAt(e.x, e.y) ?: false
                    orient.pkg = ""
                    "The user paused Blaze, moved the pointer and double-tapped it, so Blaze clicked there (x=${e.x.toInt()}, y=${e.y.toInt()}${label?.let { ", \u201c$it\u201d" } ?: ""})" +
                        (if (ok) "" else " but the click didn't go through") + ". Your planned $name was NOT done. Look at the screen below and carry on with the task from here."
                }
                com.blaze.assistant.phone.Pointer.Event.Stop -> { add(Role.STEP, "You stopped me"); job?.cancel(); "Stopped by the user." }
                else -> null
            }
        }

        /** The "context → orientation" step at the start of a phone-control turn: a compact look, or "" when it wouldn't help. */
        private fun orientContext(): String {
            if (!Settings.phoneControl || agentId != null) return ""
            val svc = com.blaze.assistant.phone.BlazeAccessibilityService.instance ?: return ""
            val pkg = svc.currentPkg()
            if (pkg.isBlank() || pkg == app.packageName) return ""   // Blaze's own chat is on screen: nothing to orient on yet
            return try { Orient.contextNote(Orient.compact(svc.readScreen())).also { orient.saw(pkg) } } catch (_: Throwable) { "" }
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

        /**
         * An MCP tool (mcp__<server>__<tool>) with Desktop's approval rules: read-only tools run, tools the
         * user switched off are refused, others show the confirm card (Action tools on their default pass in
         * Autopilot; destructive ones always ask, see McpConfig.needsApproval).
         */
        private suspend fun mcpCall(name: String, args: JSONObject): String {
            val m = com.blaze.assistant.connect.Mcp
            val t = m.find(name) ?: return "That MCP tool isn't available any more (its server is off, removed or unreachable). Use find_tool or another way."
            val label = "${t.server.name} \u00b7 ${t.tool.name}"
            if (t.mode == com.blaze.assistant.connect.McpConfig.OFF) return "Refused: the user turned ${t.tool.name} off in Settings \u203a MCP servers."
            if (com.blaze.assistant.connect.McpConfig.needsApproval(t.override, t.tool.risk, Modes.autopilot)) {
                val shown = args.toString(2).let { if (it.length > 900) it.take(900) + "\n\u2026" else it }
                val warn = if (t.tool.risk == com.blaze.assistant.connect.McpConfig.Risk.WRITE) "This tool can delete or change data on ${t.server.name}.\n" else ""
                if (!confirm("Allow $label?", warn + shown, "Allow"))
                    return "The user did NOT approve ${t.tool.name}. Don't retry it; say what you were about to do and ask how they'd like to proceed."
            }
            add(Role.STEP, "Using $label")
            return m.call(t, args)
        }

        /**
         * v0.30.5 SkillGuard: use_skill / run_skill only for a skill about what the user is asking (their last few
         * messages, so "yes, do it" still carries the request). Null to go ahead, else the refusal the model reads.
         */
        private fun skillGate(name: String, args: JSONObject): String? {
            if (name != "use_skill" && name != "run_skill") return null
            if (lastUserText().startsWith("[Scheduled routine") || agentId != null) return null   // a routine / background agent names its own skills
            val request = msgs.filter { it.role == Role.USER }.takeLast(3).joinToString(" ") { it.text.take(600) }
            return try { SkillGuard.gate(args.optString("name"), request, args.optString("reason"), SkillMatch.cached()) } catch (_: Throwable) { null }
        }

        suspend fun runToolRaw(name: String, args: JSONObject, shots: MutableList<String>): String {
            turnTools.add(name)
            val svc = com.blaze.assistant.phone.BlazeAccessibilityService.instance
            // Record phone actions (with the element's label, since numbers change) for task replay.
            if (name in setOf("open_app", "tap", "type_text", "scroll", "press_button", "run_plan", "do_actions", "search_in_app", "read_screen")) com.blaze.assistant.phone.Watchers.taskTick()
            if (name in setOf("open_app", "tap", "type_text", "scroll", "press_button", "open_url", "search_in_app", com.blaze.assistant.phone.SettingsJump.TOOL) && trace.length() < 40) {
                val label = args.optString("text").takeIf { name == "tap" && it.isNotBlank() }
                    ?: if (name == "tap" || name == "type_text") svc?.labelOf(args.optInt("element")).orEmpty() else ""
                trace.put(JSONObject().put("tool", name).put("args", JSONObject(args.toString()).apply { remove("element") }).put("label", label))
            }
            return when (name) {
                "remember" -> { add(Role.STEP, "Remembering that"); Memory.add(args.optString("fact"), args.optString("replaces")) }
                "forget" -> { add(Role.STEP, "Forgetting that"); Memory.forget(args.optString("fact")) }
                in Mind.toolNames -> { add(Role.STEP, if (name == "memory_search") (if (MemoryIntent.meansAll(args.optString("query"))) "Looking through what I remember" else "Checking memory for \u201c${args.optString("query").take(40)}\u201d") else "Saving to memory")
                    kotlinx.coroutines.withContext(Dispatchers.IO) { Mind.run(name, args) } }
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
                        "delete" -> M.find(args.optString("path"))?.let { f -> if (confirm("Delete from Gallery?", f.name, "Delete", always = true)) { M.delete(f); "Deleted ${f.name}." } else "The user did NOT approve deleting it." } ?: "Not in the gallery."
                        else -> M.list()
                    }
                }
                "ask_pc" -> {
                    val L = com.blaze.assistant.link.DesktopLink
                    add(Role.STEP, "Asking ${L.pcName}: \u201c${args.optString("task").take(60)}\u201d")
                    L.askPc(args.optString("task"), args.optBoolean("new_conversation")) { line -> add(Role.STEP, "${L.pcName}: ${line.take(90)}") }
                }
                "web_search" -> { add(Role.STEP, "Searching the web for \u201c${args.optString("query").take(50)}\u201d"); Web.search(args.optString("query")) }
                "fetch_page" -> { add(Role.STEP, "Reading ${args.optString("url").removePrefix("https://").take(50)}"); Web.fetch(ToolLeak.fixBlazeUrl(args.optString("url"))) }
                "blazerouter" -> { add(Role.STEP, "BlazeRouter: " + args.optString("action")); BlazeRouter.run(app, args) }
                "desktop" -> {
                    // v0.25.5: the step line comes from Friendly.say (DesktopNarration), the same words as the live desktop card.
                    com.blaze.assistant.desktop.LinuxDesktop.run(app, args, shots,
                        confirm = { title, detail -> confirm(title, detail, "Allow") },
                        open = { com.blaze.assistant.ui.Views.open("linux") })
                }
                in com.blaze.assistant.home.HomeTools.NAMES -> { add(Role.STEP, com.blaze.assistant.home.HomeTools.step(name, args)); kotlinx.coroutines.withContext(Dispatchers.IO) { com.blaze.assistant.home.HomeBoard.runTool(name, args) } }
                BlazeDocs.TOOL_SEARCH -> { add(Role.STEP, "Looking it up in Blaze's docs"); kotlinx.coroutines.withContext(Dispatchers.IO) { BlazeDocs.search(args) } }
                BlazeDocs.TOOL_READ -> { add(Role.STEP, "Reading Blaze's docs"); kotlinx.coroutines.withContext(Dispatchers.IO) { BlazeDocs.read(args) } }
                ProjectDocs.TOOL_SEARCH -> { add(Role.STEP, "Checking the project's notes"); kotlinx.coroutines.withContext(Dispatchers.IO) { ProjectDocs.search(id, args) } }
                ProjectDocs.TOOL_READ -> { add(Role.STEP, "Reading the project's notes"); kotlinx.coroutines.withContext(Dispatchers.IO) { ProjectDocs.read(id, args) } }
                SelfKnowledge.TOOL -> { add(Role.STEP, "Checking my own setup"); kotlinx.coroutines.withContext(Dispatchers.IO) { SelfKnowledge.report(app, args.optString("section")) } }
                com.blaze.assistant.ui.Views.TOOL -> {
                    val v = com.blaze.assistant.ui.Views.find(args.optString("view"))
                    if (v == null) "Unknown view. Pick one of: " + com.blaze.assistant.ui.Views.all.joinToString { it.id }
                    else {
                        add(Role.STEP, com.blaze.assistant.ui.Views.step(args))
                        // v0.24.6: Blaze asks first with an "Open in <view>?" chip in the chat, unless the user asked for the view.
                        val tgt = args.optString("target").ifBlank { if (v.id == "design") kotlinx.coroutines.withContext(Dispatchers.IO) { Designs.current()?.path }.orEmpty() else "" }
                        val opened = if (agentId == null) kotlinx.coroutines.withContext(Dispatchers.Main) { com.blaze.assistant.ui.Views.openOrOffer(v.id, tgt, lastUserText(), Modes.autopilot) } else false
                        if (v.id == "design" && tgt.isBlank()) "There is no design to show yet: make one with the design tool (action=create)."
                        else if (!opened) "Offered to open ${v.name} with an \u201c${com.blaze.assistant.ui.Views.offerLabel(v.id)}\u201d chip in the chat; the user opens it with one tap. Don't say it's already open."
                        else "Opened ${v.name}" + (if (v.id == "design") " showing $tgt" else "") + ". The user can tap Exit to come back to the chat."
                    }
                }
                Designs.TOOL -> {
                    add(Role.STEP, Designs.step(args))
                    val out0 = kotlinx.coroutines.withContext(Dispatchers.IO) { Designs.run(args) }
                    // v0.26.2 Designer playbook, enforced: a page that renders as bare text / raw arrows / Mermaid-as-text is sent back.
                    val out = if (args.optString("action").lowercase() in setOf("create", "update", "edit") && out0.open != null) {
                        val note = kotlinx.coroutines.withContext(Dispatchers.IO) { try { DesignLint.note(java.io.File(out0.open).readText(), Designs.current()?.kind ?: "page") } catch (_: Exception) { "" } }
                        if (note.isNotEmpty()) out0.copy(text = out0.text + note) else out0
                    } else out0
                    val opened = out.open?.let { path -> if (agentId == null) kotlinx.coroutines.withContext(Dispatchers.Main) { com.blaze.assistant.ui.Views.openOrOffer("design", path, lastUserText(), Modes.autopilot) } else false }
                    if (opened == false) out.text + "\n(Design isn't open yet: the user has an \u201cOpen in Design?\u201d chip in the chat.)" else out.text
                }
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
                    if (com.blaze.assistant.phone.Ussd.risky(code) && !confirm("Dial $code?", "This looks like a mobile money or multi-step code.", "Dial", always = true))
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
                    if (name == "file_delete" && !confirm("Delete this?", args.optString("path").take(200), "Delete", always = true)) "The user did NOT approve deleting that."
                    else if (name == "run_code") withMediaScan { com.blaze.assistant.terminal.DevTools.run(app, name, args) }
                    else com.blaze.assistant.terminal.DevTools.run(app, name, args)
                }
                in com.blaze.assistant.phone.PimEdit.names -> {
                    val (title, detail) = com.blaze.assistant.phone.PimEdit.describe(name, args)
                    add(Role.STEP, title.removeSuffix("?"))
                    if (!confirm(title, detail.take(200), if (name == "delete_event") "Delete" else "Save", always = name.startsWith("delete"))) "The user did NOT approve that change."
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
                "delegate" -> delegate(args)
                "create_specialist" -> createSpecialist(args)
                "recall_memory" -> { add(Role.STEP, if (MemoryIntent.meansAll(args.optString("query"))) "Looking through what I remember" else "Checking memory"); Memory.recall(args.optString("query")) }
                "blaze_self" -> {
                    val act = args.optString("action")
                    add(Role.STEP, when (act) { "setup_check", "status" -> "Checking my setup"; "install" -> "Installing ${args.optString("what")}"; "open" -> "Opening ${args.optString("target")}"; else -> "Adjusting myself: $act" })
                    if (act == "install") SelfSetup.install(app, args.optString("what")) else SelfControl.run(app, act, args)
                }
                "device_status" -> { add(Role.STEP, "Checking the phone"); com.blaze.assistant.device.DeviceStatus.describe(app) }
                com.blaze.assistant.mediaapps.store.JamendoSource.TOOL -> { add(Role.STEP, "Setting up Jamendo"); com.blaze.assistant.mediaapps.store.JamendoSource.init(app); com.blaze.assistant.mediaapps.store.JamendoSource.runTool(args) }
                "look_at_screen" -> if (Settings.lowData) {
                    add(Role.STEP, "Reading the screen (low-data mode)")
                    "Low-data mode is on, so no screenshot was taken. Screen as text:\n" + (com.blaze.assistant.phone.BlazeAccessibilityService.instance?.readScreen() ?: "Phone control isn't connected.")
                } else {
                    add(Role.STEP, "Looking at a screenshot")
                    val shot = PhoneTools.screenshot()
                    if (shot.startsWith("data:")) { shots += shot; "Screenshot taken. It is attached in the next message." } else shot
                }
                "start_agent" -> {
                    val bg = BgAgents.start(args.optString("task"), args.optString("title"), id)
                    val mid = add(Role.BLAZE, "Background agent ${bg.id}: ${bg.title}")
                    msgs = msgs.map { if (it.id == mid) it.copy(meta = BgAgents.CARD + bg.id) else it }
                    persist()
                    val queued = BgAgents.get(bg.id)?.status == BgAgents.QUEUED
                    "Started ${bg.id} \u00ab${bg.title}\u00bb${if (queued) " (queued: ${BgAgents.runningCount()} already running)" else ""}. Its card is in the chat; its result reaches you automatically. Tell the user in one short line and carry on."
                }
                in BgAgents.toolNames -> BgAgents.run(name, args, id)
                "call_control" -> {
                    add(Role.STEP, when (args.optString("action")) { "speaker" -> if (args.optBoolean("on", true)) "Loudspeaker on" else "Loudspeaker off"; "mute" -> if (args.optBoolean("on", true)) "Muting" else "Unmuting"
                        "end" -> "Hanging up"; "answer" -> "Answering"; "decline" -> "Declining"; "start_blaze_call" -> "Starting a call"; "keypad" -> "Pressing ${args.optString("digits")}"; else -> "Call controls" })
                    com.blaze.assistant.phone.CallControl.run(app, args)
                }
                "clipboard" -> {
                    val C = com.blaze.assistant.phone.ClipAccess
                    when (args.optString("action")) {
                        "set" -> { add(Role.STEP, "Copying to your clipboard"); kotlinx.coroutines.withContext(Dispatchers.Main) { C.setText(app, args.optString("text")) }; "Copied to the clipboard." }
                        "set_image" -> {
                            val p = args.optString("path")
                            val f = com.blaze.assistant.media.Media.find(p) ?: com.blaze.assistant.terminal.DevTools.resolve(p)
                            if (!f.isFile) "No such file: $p" else { add(Role.STEP, "Copying ${f.name} to your clipboard"); kotlinx.coroutines.withContext(Dispatchers.Main) { C.setFile(app, f) } }
                        }
                        "clear" -> { kotlinx.coroutines.withContext(Dispatchers.Main) { C.clear(app) }; "Clipboard cleared." }
                        "history" -> C.history().take(15).mapIndexed { i, t -> "${i + 1}. ${t.take(300)}" }.joinToString("\n").ifBlank { "No clipboard history yet." }
                        "get_image" -> {
                            add(Role.STEP, "Looking at your clipboard")
                            val clip = C.read(app)
                            val u = clip?.uris?.firstOrNull()
                            if (u == null) (if (clip == null) "Couldn't read the clipboard." else "There's no image on the clipboard" + (if (clip.text.isNotEmpty()) ", only text: ${clip.text.take(500)}" else "."))
                            else Attachments.imageUrl(app, u.first)?.let { shots += it; "The copied image is attached in the next message." } ?: "There's a file on the clipboard (${u.second.ifBlank { "unknown type" }}) but it isn't a picture I can open."
                        }
                        else -> {
                            add(Role.STEP, "Reading your clipboard")
                            val clip = C.read(app)
                            when {
                                clip == null -> "Couldn't read the clipboard. Turn on Blaze's accessibility service or 'draw over apps' so it can read it in the background."
                                clip.text.isEmpty() && clip.uris.isEmpty() -> "The clipboard is empty."
                                else -> clip.text.take(4000) + (if (clip.uris.isNotEmpty()) "\n[Also ${clip.uris.size} file(s) on the clipboard (${clip.uris.first().second.ifBlank { "file" }}). Use clipboard get_image to look at a picture.]" else "")
                            }
                        }
                    }
                }
                in com.blaze.assistant.phone.AndroidControl.names -> {
                    add(Role.STEP, if (name == "blaze_camera") "Opening Blaze Camera" else name.replace('_', ' ').replaceFirstChar { it.uppercase() })
                    // v0.28.x: Blaze Camera brings its capture back into this chat, as an answer to the user's last message.
                    val a2 = if (name != "blaze_camera") args else JSONObject(args.toString()).put("_chat", id)
                        .put("_asked", msgs.lastOrNull { it.role == Role.USER }?.text.orEmpty())
                    try { kotlinx.coroutines.withContext(Dispatchers.Main) { com.blaze.assistant.phone.AndroidControl.run(app, name, a2) } } catch (e: CancellationException) { throw e } catch (e: Exception) { "Error: ${e.message}" }
                }
                in SkillPacks.names -> {
                    skillGate(name, args)?.let { return it }
                    add(Role.STEP, when (name) { "use_skill" -> "Reading skill \u201c${args.optString("name")}\u201d"; "install_skill" -> "Installing skills"; "write_skill" -> "Saving a skill"; "save_workflow" -> "Saving a workflow"; else -> "Reading a skill file" })
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
                com.blaze.assistant.update.Updater.TOOL -> {
                    if (this === cur) com.blaze.assistant.update.Updater.showCard()
                    com.blaze.assistant.update.Updater.fromTool(app, args.optBoolean("install", true))
                }
                in Learning.toolNames -> {
                    skillGate(name, args)?.let { return it }
                    add(Role.STEP, when (name) { "run_skill" -> "Replaying skill \"${args.optString("name")}\""; "skill_manage" -> "Saving a skill"; else -> "Searching past chats" })
                    try { Learning.run(app, name, args) } catch (e: CancellationException) { throw e } catch (e: Exception) { "Error: ${e.message}" }
                }
                "search_screen_memory" -> { add(Role.STEP, "Searching screen memory"); ScreenMemory.search(app, args.optString("query"), args.optString("app").ifBlank { null }) }
                "recent_notifications" -> { add(Role.STEP, "Checking notifications"); com.blaze.assistant.notify.NotificationBrain.list(args.optString("app").ifBlank { null }, args.optDouble("hours", 6.0)) }
                com.blaze.assistant.phone.Messaging.NAME -> { add(Role.STEP, "Messaging ${args.optString("to")}"); com.blaze.assistant.phone.Messaging.run(app, args) }
                com.blaze.assistant.browser.BrowserTools.SECURE -> secureInput(args)
                in com.blaze.assistant.browser.BrowserTools.names -> {
                    // v0.26.2: parallel specialists take turns with the one background browser (the first to use it holds it).
                    agentId?.let { me -> Dispatch.browser.take(me) { h -> BgAgents.get(h)?.live == true }?.let { return it } }
                    add(Role.STEP, com.blaze.assistant.browser.BrowserTools.describe(name, args))
                    com.blaze.assistant.browser.BrowserTools.run(app, name, args)
                }
                "reply_to_notification" -> { add(Role.STEP, "Replying"); com.blaze.assistant.notify.NotificationBrain.reply(app, args.optInt("number"), args.optString("text")) }
                "run_plan" -> runPlan(args)
                com.blaze.assistant.phone.SettingsJump.TOOL -> com.blaze.assistant.phone.SettingsJump.run(app, args)
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
                    // v0.30.7: want="on"/"off" on a switch: read it first and only tap when it isn't already that way.
                    val want = TapLoop.wantOf(args.optString("want"))
                    val wantId = if (want != null && svc != null) (if (args.optString("text").isNotBlank()) svc.reveal(args.optString("text")) else if (args.has("element")) args.optInt("element") else null) else null
                    val wantNow = wantId?.let { svc?.switchState(it) }
                    if (want != null && wantNow != null && !TapLoop.needsTap(want, wantNow))
                        "Already ${TapLoop.wantWord(want)}: \u201c${(wantId?.let { svc?.labelOf(it) } ?: label).ifBlank { label }.take(40)}\u201d. Not tapped, it's already how you wanted it."
                    else if (Settings.confirmRisky && risky.containsMatchIn(label) &&
                        !confirm("Tap \u201c${label.take(40)}\u201d?", "Blaze wants to tap this in ${svc?.currentApp() ?: "the app"}.", "Tap", always = true))
                        "The user did NOT approve tapping \"$label\". Don't retry; tell them it's ready for them to finish."
                    else PhoneTools.run(app, name, args)
                }
                else -> when {
                    name in LongChat.names -> longChatTool(name, args)
                    name == "notif_reply" -> { add(Role.STEP, "Replying to ${args.optString("conversation").take(40)}")
                        com.blaze.assistant.notify.Conversations.reply(app, args.optString("conversation"), args.optString("text")) }
                    name in com.blaze.assistant.notify.Conversations.toolNames -> { add(Role.STEP, if (name == "notif_read") "Reading the chat with ${args.optString("conversation").take(40)}" else "Checking your chats")
                        kotlinx.coroutines.withContext(Dispatchers.IO) { com.blaze.assistant.notify.Conversations.run(app, name, args) } }
                    name == com.blaze.assistant.life.LifeLogs.TOOL -> { add(Role.STEP, com.blaze.assistant.life.LifeLogs.describe(args))
                        com.blaze.assistant.life.LifeLogs.run(app, args) }
                    name == Seller.TOOL -> { add(Role.STEP, if (args.optString("action") == "set") "Saving the brand kit" else "Reading the brand kit")
                        kotlinx.coroutines.withContext(Dispatchers.IO) { Seller.run(args) } }
                    name == Flashcards.TOOL -> { add(Role.STEP, if (args.optString("action") == "add") "Making flashcards" else "Checking your flashcards")
                        kotlinx.coroutines.withContext(Dispatchers.IO) { Flashcards.run(args) } }
                    com.blaze.assistant.connect.Mcp.handles(name) -> mcpCall(name, args)
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
                "open_settings" -> "Open ${st.optString("app").let { if (it.isBlank()) "" else "$it\u2019s " }}${st.optString("page").replace('_', ' ')} settings"
                else -> st.optString("do")
            }
            val keys = (0 until n).map { FastActions.key(steps.getJSONObject(it)) }
            // v0.30.4: the same script sent again after it failed at step k skips the steps already done (the phone is
            // still on step k's screen), instead of reopening the app and starting over.
            val skip = FastActions.skipDone(keys, lastPlan, svc.currentPkg())
            val labels = (0 until n).map { desc(steps.getJSONObject(it)) + if (it < skip) " (already done)" else "" }.toMutableList()
            screenActed = true
            // v0.30.7: after a short settle the next step's own wait polls the new screen for its target (on-device check).
            var shortSettle = false
            val state = MutableList(n) { if (it < skip) 2 else 0 }   // 0 todo, 1 running, 2 done, 3 failed, 4 skipped
            fun board() = labels.mapIndexed { i, l -> (when (state[i]) { 2 -> "\u2713 "; 1 -> "\u2192 "; 3 -> "\u2715 "; 4 -> "\u2013 "; else -> "\u00b7 " }) + l }.joinToString("\n")
            val boardId = add(Role.STEP, board())
            val started = System.currentTimeMillis()
            for (i in skip until n) {
                val st = steps.getJSONObject(i)
                state[i] = 1; replace(boardId, Role.STEP, board())
                val t0 = System.currentTimeMillis()
                svc.lastToggle = null
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
                                val (omx, oq) = TapLoop.settleAfter("open_app", if (i + 1 < n) steps.optJSONObject(i + 1) else null)
                                svc.settle(omx, oq)
                            }
                        }
                        // v0.30.7: settings pages by intent inside a plan (open_settings + tap in ONE call).
                        "open_settings" -> {
                            val r = com.blaze.assistant.phone.SettingsJump.run(app, JSONObject().put("page", st.optString("page"))
                                .apply { st.optString("app").takeIf { it.isNotBlank() }?.let { put("app", it) } })
                            if (TapLoop.failed(r)) fail = r.substringBefore("\n").take(200)
                        }
                        "tap", "long_press" -> {
                            // v0.30.4: a short wait for a lazy screen, then synonyms / fuzzy / off-screen rows scrolled into view
                            // (LabelMatch + reveal) instead of 3 s of polling and a single blind scroll.
                            val id = svc.waitFor(target, TapLoop.waitMs(shortSettle)) ?: svc.reveal(target)
                            val want = TapLoop.wantOf(st.optString("want"))
                            val nowOn = if (id != null && want != null) svc.switchState(id) else null
                            if (id == null) fail = "couldn't find \u201c$target\u201d on screen, even after scrolling through it"
                            else if (!TapLoop.needsTap(want, nowOn)) {
                                // v0.30.7: the switch is already the way the step wants it: done, nothing tapped.
                                labels[i] = labels[i] + " (already ${TapLoop.wantWord(want == true)})"
                            }
                            else {
                                val label = svc.labelOf(id)
                                if (Settings.confirmRisky && risky.containsMatchIn(label) &&
                                    !confirm("Tap \u201c${label.take(40)}\u201d?", "Blaze wants to tap this in ${svc.currentApp() ?: "the app"}.", "Tap", always = true))
                                    return "The user did NOT approve tapping \"$label\" (step ${i + 1}). Don't retry; tell them it's ready for them to finish."
                                svc.flash(id)
                                val r = svc.tap(id, st.optString("do") == "long_press")
                                if (r.startsWith("Couldn't") || r.startsWith("No element")) fail = r
                                trace.put(JSONObject().put("tool", "tap").put("args", JSONObject().put("text", label)).put("label", label))
                            }
                        }
                        "type" -> {
                            val id = if (target.isNotBlank()) svc.waitFor(target, TapLoop.waitMs(shortSettle, field = true), wantField = true) ?: svc.reveal(target, wantField = true, maxScrolls = 3) ?: svc.anyField() else svc.anyField()
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
                    val next = if (i + 1 < n) steps.optJSONObject(i + 1) else null
                    val (mx, q) = TapLoop.settleAfter(st.optString("do"), next)
                    shortSettle = mx < 1000L
                    if (fail == null && st.optString("do") !in setOf("wait", "open_app", "open_settings")) svc.settle(mx, q)
                    val expect = st.optString("expect")
                    if (fail == null && expect.isNotBlank() && svc.waitFor(expect, 2500) == null) fail = "expected \u201c$expect\u201d to appear but it didn't"
                } catch (e: CancellationException) { throw e } catch (e: Exception) { fail = "error: ${e.message}" }
                if (!isPrivate) Traces.log(svc.currentPkg(), st, fail == null, System.currentTimeMillis() - t0, fail ?: "")
                if (fail != null && st.optBoolean("optional")) { state[i] = 4; replace(boardId, Role.STEP, board()); continue }
                if (fail != null) {
                    planRec.fail()
                    state[i] = 3; replace(boardId, Role.STEP, board())
                    lastPlan = FastActions.PlanRun(keys, i, true, svc.currentPkg())
                    return "Stopped at step ${i + 1} (${labels[i]}): $fail. " + FastActions.resumeHint(i + 1, n) + "\n\n" + svc.readScreen()
                }
                state[i] = 2; replace(boardId, Role.STEP, board())
                // v0.30.7 plan memory: the step as it worked (a switch tap remembers which way it ended up).
                planRec.ok(JSONObject(st.toString()).apply {
                    if (st.optString("do") == "tap" && !st.has("want")) svc.lastToggle?.let { put("want", TapLoop.wantWord(it)) } })
                com.blaze.assistant.device.Haptics.tick(app)
            }
            lastPlan = FastActions.PlanRun(keys, n, false, svc.currentPkg())
            val words = svc.visibleWords().filter { it.length in 2..60 }.take(25)
            return "All $n steps done in ${"%.1f".format((System.currentTimeMillis() - started) / 1000.0)} s. Now in ${svc.currentApp() ?: "?"}. On screen: ${words.joinToString(" · ")}\n(Call read_screen only if you need to act further on this screen.)"
        }

        /**
         * After a turn: the chat's title and summary (Titles), then memories and skills (Learning), both in
         * the background. Since v0.8.0 a cheap local check (Learn.decide, no model call) decides whether the
         * learning review is worth a call at all, like Blaze Desktop v0.7: small talk, turns that already
         * saved, and turns with nothing durable no longer cost a request.
         */
        fun learnAfterTurn() {
            if (isPrivate) return   // v0.24.4: nothing is learned from a private chat (no title, memories, skills)
            val reply = msgs.lastOrNull { it.role == Role.BLAZE } ?: return
            if (reply.meta.startsWith("\u26a1")) return
            val lastUser = msgs.indexOfLast { it.role == Role.USER }; if (lastUser < 0) return
            val failed = msgs.drop(lastUser).any { it.role == Role.ERROR }
            val cid = id
            // Titles
            val userTurns = msgs.count { it.role == Role.USER }
            val endsWithAnswer = !failed && msgs.lastOrNull()?.role == Role.BLAZE && reply.text.isNotBlank()
            if (agentId == null && Titles.needs(titleSource, titleTurns, userTurns, msgs[lastUser].text, endsWithAnswer)) {
                val lines = msgs.filter { it.role == Role.USER || it.role == Role.BLAZE }.map { (it.role == Role.USER) to it.text }
                val current = title.ifBlank { msgs[lastUser].text.lineSequence().first().take(48) }
                io.launch {
                    val r = Titles.ask(cid, current, lines) ?: return@launch
                    scope.launch {
                        if (titleSource == Titles.MANUAL) return@launch   // renamed by hand meanwhile
                        title = r.title; titleSource = Titles.AUTO; titleTurns = userTurns
                        if (r.summary.isNotBlank()) summary = r.summary
                        persist()
                    }
                }
            }
            // Learning
            if (!Settings.autoLearn || failed) return
            val tools = synchronized(turnTools) { turnTools.toList() }
            val userText = msgs[lastUser].text
            val (n, distinct) = Learn.steps(tools)
            val sig = Learn.signature(userText, tools)
            val repeat = Learn.repeatsAndRecord(sig, n >= 5 && distinct >= 2)
            val savedSkill = tools.any { it == "write_skill" || it == "skill_manage" || it == "save_workflow" }
            // v0.30.5: count every real task (≥ 2 work tool calls); one done often with no skill covering it gets a skill first.
            val usedSkill = tools.any { it == "use_skill" || it == "run_skill" }
            val frequent = if (agentId == null && n >= 2 && !Learn.trivial(userText)) {
                val covered = savedSkill || usedSkill || try { !SkillMatch.match(userText, SkillMatch.cached().filter { it.source == "learned" || it.source == "user" },
                    minScore = SkillMatch.STRONG, fullMin = SkillMatch.STRONG, fullWords = SkillMatch.FULL_WORDS).isEmpty } catch (_: Throwable) { false }
                SkillGuard.Freq.note(sig, userText, covered)
            } else false
            val d00 = Learn.decide(Learn.Turn(userText, reply.text, tools,
                savedMemory = tools.any { it == "remember" || it == "forget" || it == "memory_save" || it == "memory_update" },
                savedSkill = savedSkill), repeat || frequent)
            val d0 = if (frequent && !savedSkill && !d00.skill) d00.copy(reflect = true, skill = true, why = "a task done often has no skill yet") else d00
            // One mind: save as you go. One cheap extraction call per real turn writes or merges memories; the
            // learning review below then only looks for skills.
            if (Mind.ready && agentId == null && userText.trim().split(Regex("\\s+")).size >= 3) {
                val ex = msgs.drop(maxOf(0, lastUser - 4)).filter { it.role == Role.USER || it.role == Role.BLAZE }
                    .joinToString("\n") { (if (it.role == Role.USER) "[user] " else "[assistant] ") + it.text.take(2000) }
                io.launch {
                    val n = try { MindReview.afterTurn(ex) } catch (_: Exception) { 0 }
                    if (n > 0) scope.launch { addMeta(reply.id, "Memory updated"); persist() }
                }
            }
            val d = if (Mind.ready) d0.copy(memory = false) else d0
            if (!d.reflect || (!d.memory && !d.skill)) return
            val exchange = msgs.drop(maxOf(0, lastUser - 4)).joinToString("\n") { m ->
                when (m.role) { Role.USER -> "User: "; Role.BLAZE -> "Blaze: "; Role.STEP -> "  (step) "; Role.ERROR -> "  (error) " } + m.text.take(1500) }
            val t = JSONArray(trace.toString())
            io.launch {
                val chip = Learning.review(exchange, t, d, tools)
                if (chip != null) scope.launch { addMeta(reply.id, chip); persist() }
            }
        }

        /** When Blaze finishes while you're in another app, post its answer as a notification. */
        fun announce() {
            if (sessions.values.none { it !== this && it.job?.isActive == true }) Notifier.doneWorking()
            val last = msgs.lastOrNull() ?: return
            if (last.role != Role.BLAZE && last.role != Role.ERROR) return
            if (Voice.lastTurnByVoice && Voice.shouldSpeak()) {
                if (streamedVoice) {
                    val rest = if (spokenPrefix.isNotEmpty() && last.text.startsWith(spokenPrefix)) last.text.substring(spokenPrefix.length) else if (spokenPrefix.isEmpty()) last.text else ""
                    Voice.speak(rest.ifBlank { " " }, queue = true)
                } else Voice.speak(last.text)
            }
            if (!isPrivate) try { com.blaze.assistant.widget.BlazeWidget.update(app, last.text.take(240)) } catch (_: Exception) { }
            if (this === cur && (inForeground || overlayOpen)) return
            // v0.24.4: a private chat's words never land in the notification shade.
            if (isPrivate) { Notifier.reply("Blaze answered in your private chat.", last.role == Role.ERROR, id); return }
            // A chat you left kept working: say which one finished.
            Notifier.reply(if (this === cur) last.text else "${title.ifBlank { "A chat" }}: ${last.text}", last.role == Role.ERROR, id)
        }
    }
}
