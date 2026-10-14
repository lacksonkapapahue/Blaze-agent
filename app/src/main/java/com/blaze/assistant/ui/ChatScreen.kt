package com.blaze.assistant.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.Attachment
import com.blaze.assistant.agent.Attachments
import com.blaze.assistant.agent.ChatMessage
import com.blaze.assistant.agent.Mood
import com.blaze.assistant.agent.Role
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.phone.BlazeAccessibilityService
import com.blaze.assistant.voice.Voice
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar

/** What the message list draws: a message, or a folded run of steps. [pos] = its place in a run from one side (v0.24.0). */
private sealed class Item(val key: String) {
    var pos: GroupPos = GroupPos.SINGLE
    abstract val side: Side
    abstract val at: Long
    class Msg(val m: ChatMessage) : Item("m${m.id}") {
        override val side: Side get() = if (m.role == Role.USER) Side.ME else Side.BLAZE
        override val at: Long get() = m.at
    }
    class Steps(val steps: List<ChatMessage>, val live: Boolean, val endAt: Long, val startAt: Long) : Item("s${steps.first().id}") {
        override val side: Side get() = Side.BLAZE
        override val at: Long get() = steps.first().at
    }
}

private fun buildItems(messages: List<ChatMessage>, working: Boolean): List<Item> {
    val out = mutableListOf<Item>()
    var run = mutableListOf<ChatMessage>()
    var prevAt = 0L
    fun flush(live: Boolean, endAt: Long) { if (run.isNotEmpty()) { out += Item.Steps(run, live, endAt, prevAt.takeIf { it > 0 } ?: run.first().at); run = mutableListOf() } }
    messages.forEach { m -> if (m.role == Role.STEP) run += m else { flush(false, m.at); out += Item.Msg(m); prevAt = m.at } }
    flush(working, 0L)
    Bubbles.positions(out.map { it.side }, out.map { it.at }).forEachIndexed { i, p -> out[i].pos = p }
    return out
}

/** Text selection menu with Copy · Quote · Select all, drawn in Blaze's style. */
private class BlazeToolbar(val onShow: (Rect, (() -> Unit)?, (() -> Unit)?) -> Unit, val onHide: () -> Unit) : TextToolbar {
    override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        private set
    override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
                          onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
        status = TextToolbarStatus.Shown; onShow(rect, onCopyRequested, onSelectAllRequested)
    }
    override fun hide() { status = TextToolbarStatus.Hidden; onHide() }
}

@Composable
fun ChatScreen() {
    val c = LocalBlaze.current
    // Only "is the chat empty?" lives up here, so streamed words don't redraw the whole screen.
    val hasMessages by remember { Agent.messages.map { it.isNotEmpty() }.distinctUntilChanged() }
        .collectAsState(Agent.messages.value.isNotEmpty())
    val mood by Agent.mood.collectAsState()
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current

    var settingsPage by rememberSaveable { mutableStateOf<String?>(null) }
    // First run only: people who already set Blaze up never see it.
    var onboard by rememberSaveable { mutableStateOf(!Settings.bool("onboarded", false) && !Settings.ready && !hasMessages) }
    var setupPage by rememberSaveable { mutableStateOf(0) }
    val galleryOpen by GalleryUi.open.collectAsState()
    LaunchedEffect(Unit) { if (!onboard) Settings.setBool("onboarded", true) }
    // v0.24.4: the dependency gate. Upgraded (and new) users finish whatever required setup is missing; never again once done.
    var gate by remember { mutableStateOf(!onboard && com.blaze.assistant.setup.SetupGate.shouldGate(ctx)) }
    // Blaze opening one of its own settings pages (setup help).
    val askedPage by com.blaze.assistant.agent.SelfSetup.openPage.collectAsState()
    LaunchedEffect(askedPage) { askedPage?.let { settingsPage = it; com.blaze.assistant.agent.SelfSetup.openPage.value = null } }
    // v0.24.0 /clear: when the last clear happened (0 = no undo bar).
    var clearedAt by remember { mutableStateOf(0L) }
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var quote by rememberSaveable { mutableStateOf("") }

    var pending by remember { mutableStateOf(listOf<Attachment>()) }
    var loadingFiles by remember { mutableStateOf(false) }
    fun addUris(uris: List<Uri>, mimeHint: String = "") {
        if (uris.isEmpty()) return
        loadingFiles = true
        scope.launch {
            val loaded = uris.take(5).mapNotNull { Attachments.load(ctx, it, mimeHint) }
            if (loaded.size < uris.take(5).size) Toast.makeText(ctx, "Some files couldn't be read (photos, PDFs and text work).", Toast.LENGTH_SHORT).show()
            pending = pending + loaded; loadingFiles = false
        }
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { addUris(it) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { addUris(it) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraUri?.let { addUris(listOf(it)) } }
    fun openCamera() {
        val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
        val f = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        val u = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        cameraUri = u
        try { camera.launch(u) } catch (_: Exception) { Toast.makeText(ctx, "No camera app found.", Toast.LENGTH_SHORT).show() }
    }

    // An image on the clipboard shows a "Paste image" chip.
    val sysClip = remember { ctx.getSystemService(android.content.ClipboardManager::class.java) }
    var clipHasImage by remember { mutableStateOf(false) }
    fun checkClip() {
        clipHasImage = try { sysClip.primaryClipDescription?.let { d -> (0 until d.mimeTypeCount).any { d.getMimeType(it).startsWith("image/") } } == true } catch (_: Exception) { false }
    }
    DisposableEffect(Unit) {
        val l = android.content.ClipboardManager.OnPrimaryClipChangedListener { checkClip() }
        sysClip.addPrimaryClipChangedListener(l); checkClip()
        onDispose { sysClip.removePrimaryClipChangedListener(l) }
    }
    fun pasteImage() {
        clipHasImage = false
        val clipData = try { sysClip.primaryClip } catch (_: Exception) { null }
        if (clipData == null) { Toast.makeText(ctx, "Android didn't let Blaze read the clipboard. Copy the image again, then paste.", Toast.LENGTH_SHORT).show(); return }
        val d = clipData.description
        // Some keyboards and the Samsung clipboard don't say the file type when asked, so pass on what the clip declared.
        val hint = (0 until d.mimeTypeCount).map { d.getMimeType(it) }.firstOrNull { it.startsWith("image/") }.orEmpty()
        val uris = (0 until clipData.itemCount).mapNotNull { clipData.getItemAt(it).uri }
        if (uris.isEmpty()) { Toast.makeText(ctx, "That image can't be pasted here.", Toast.LENGTH_SHORT).show(); return }
        addUris(uris, hint)
    }

    var a11yOn by remember { mutableStateOf(BlazeAccessibilityService.isEnabled(ctx)) }
    var control by remember { mutableStateOf(com.blaze.assistant.phone.PhoneControl.state(ctx)) }
    LaunchedEffect(Unit) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                a11yOn = BlazeAccessibilityService.isEnabled(ctx)
                control = com.blaze.assistant.phone.PhoneControl.state(ctx).also { com.blaze.assistant.phone.PhoneControl.note(it) }
                delay(2500)
            }
        }
    }

    // v0.24.1: the composer's rotating placeholder, rebuilt from on-device data each time Blaze comes to the front.
    var hints by remember { mutableStateOf(emptyList<String>()) }
    var reducedMotion by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            reducedMotion = ComposerHintsSource.reducedMotion(ctx)
            hints = try { ComposerHintsSource.load(ctx) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { emptyList() }
        }
    }

    // Shared from another app: lands in the message box for the user to send, never runs by itself
    // (any app can share text, so it could carry instructions the user never wrote).
    val shared by Agent.sharedDraft.collectAsState()
    LaunchedEffect(shared) {
        val d = shared ?: return@LaunchedEffect
        Agent.sharedDraft.value = null
        if (d.text.isNotBlank()) input = TextFieldValue(d.text, androidx.compose.ui.text.TextRange(d.text.length))
        d.attachment?.let { pending = pending + it }
    }

    val voiceState by Voice.state.collectAsState()
    val listenReq by Voice.listenRequests.collectAsState()
    fun startVoice() {
        Voice.listen(onText = { if (callWords.matches(it.trim())) Voice.callRequests.value++ else if (it.isNotBlank()) Agent.send(it, byVoice = true) },
            onError = { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() })
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startVoice() else Toast.makeText(ctx, "Blaze needs the microphone to hear you.", Toast.LENGTH_SHORT).show()
    }
    fun onMic() {
        when {
            voiceState == Voice.State.LISTENING -> Voice.cancelListening()
            voiceState == Voice.State.SPEAKING -> Voice.stopSpeaking()
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> startVoice()
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(listenReq) { if (listenReq > 0 && mood == Mood.IDLE) onMic() }

    // Call Blaze: full-screen voice call.
    var inCall by remember { mutableStateOf(false) }
    val callPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) inCall = true else Toast.makeText(ctx, "Blaze needs the microphone for calls.", Toast.LENGTH_SHORT).show()
    }
    fun startCall() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) inCall = true
        else callPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    val callReq by Voice.callRequests.collectAsState()
    LaunchedEffect(callReq) { if (callReq > 0) startCall() }

    // ---- 0.12 shell state ----
    var shellMode by rememberSaveable { mutableStateOf("chat") }
    var projectOpen by rememberSaveable { mutableStateOf("") }
    var showViews by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var avatarMenu by remember { mutableStateOf(false) }
    var plusOpen by remember { mutableStateOf(false) }
    val showing by Views.showing.collectAsState()
    val designTarget by Views.designTarget.collectAsState()
    val viewOffer by Views.offer.collectAsState()
    val work by com.blaze.assistant.agent.Modes.work.collectAsState()
    val approval by com.blaze.assistant.agent.Modes.approval.collectAsState()
    val bgAgents by com.blaze.assistant.agent.BgAgents.all.collectAsState()
    val account by com.blaze.assistant.connect.BlazeAccount.view.collectAsState()
    val projects by com.blaze.assistant.agent.Projects.all.collectAsState()
    val allChats by Agent.chats.collectAsState()
    val busyChats by Agent.busyChats.collectAsState()
    val question by Agent.pendingQuestion.collectAsState()
    val currentChat by Agent.chatId.collectAsState()
    val liveStatus by Agent.status.collectAsState()
    val routerState by com.blaze.assistant.agent.BlazeRouter.state.collectAsState()

    fun exitView() { Views.showing.value = "" }
    fun openView(v: BlazeView, target: String = "") {
        showViews = false; showSearch = false; plusOpen = false; avatarMenu = false
        when (v.special) {
            "gallery" -> GalleryUi.open.value = true
            "projects" -> { shellMode = "projects"; projectOpen = "" }
            "browser" -> com.blaze.assistant.browser.BrowserTools.show(ctx)
            "design" -> {
                if (target.isNotBlank()) Views.designTarget.value = target
                else if (Views.designTarget.value.isBlank()) try { com.blaze.assistant.agent.Designs.current()?.path?.let { Views.designTarget.value = it } } catch (_: Exception) { }
                shellMode = "chat"; Views.showing.value = "design"
            }
            else -> settingsPage = v.page ?: ""
        }
    }
    val viewReq by Views.request.collectAsState()
    LaunchedEffect(viewReq) {
        val r = viewReq ?: return@LaunchedEffect
        Views.request.value = null
        Views.find(r.id)?.let { openView(it, r.target) }
    }

    fun clearScreen() {
        when (Agent.clearScreen()) {
            com.blaze.assistant.agent.ClearScreen.Result.BUSY -> Toast.makeText(ctx, "Blaze is working. Stop it first, then /clear.", Toast.LENGTH_SHORT).show()
            com.blaze.assistant.agent.ClearScreen.Result.EMPTY -> {}
            com.blaze.assistant.agent.ClearScreen.Result.CLEARED -> { quote = ""; exitView(); clearedAt = System.currentTimeMillis() }
        }
    }
    fun send(raw: String) {
        val text = raw.trim()
        if (com.blaze.assistant.agent.ClearScreen.isCommand(text)) { input = TextFieldValue(""); clearScreen(); return }
        if (callWords.matches(text)) { input = TextFieldValue(""); startCall(); return }
        val cmdName = text.removePrefix("/").substringBefore(' ').lowercase()
        if (text.startsWith("/") && !text.startsWith("//")) {
            if (cmdName == "views") { input = TextFieldValue(""); showViews = true; return }
            if (cmdName == "search") { input = TextFieldValue(""); showSearch = true; return }
            if (cmdName == "projects") { input = TextFieldValue(""); shellMode = "projects"; return }
            if (cmdName in setOf("jobs", "schedule", "accounts", "doctor", "design", "router", "skills", "gallery", "browser") && !text.contains(' ')) {
                input = TextFieldValue(""); Views.find(cmdName)?.let { openView(it) }; return
            }
        }
        val cmd = CommandRunner.run(ctx, text, openSettings = { settingsPage = it }, listen = { onMic() })
        if (cmd == "") { input = TextFieldValue(""); return }
        val body = cmd ?: text
        val full = if (quote.isNotBlank()) quote.lines().joinToString("\n") { "> $it" } + "\n\n" + body else body
        if (full.isBlank() && pending.isEmpty()) return
        if (mood != Mood.IDLE) return
        clearedAt = 0L
        val pid = com.blaze.assistant.agent.Projects.current.value
        if (pid.isNotBlank() && Agent.chatId.value != com.blaze.assistant.agent.Mind.MAIN && com.blaze.assistant.agent.Projects.of(Agent.chatId.value) == null) com.blaze.assistant.agent.Projects.assign(Agent.chatId.value, pid)
        Agent.send(full, attachments = pending)
        pending = emptyList(); quote = ""; input = TextFieldValue("")
    }
    fun newChat(projectId: String = "") {
        Agent.clear(projectId.isNotBlank()); quote = ""; com.blaze.assistant.agent.Projects.current.value = projectId
        exitView(); shellMode = "chat"
    }
    fun openChat(id: String) {
        // One mind: only the main thread and project threads open (v0.24.0: no chat list or archive screen).
        if (!Agent.isLive(id)) return
        Agent.open(id); com.blaze.assistant.agent.Projects.current.value = com.blaze.assistant.agent.Projects.of(id)?.id.orEmpty()
        showSearch = false; exitView(); shellMode = "chat"
    }

    // Selection toolbar state.
    var tbRect by remember { mutableStateOf<Rect?>(null) }
    var tbCopy by remember { mutableStateOf<(() -> Unit)?>(null) }
    var tbAll by remember { mutableStateOf<(() -> Unit)?>(null) }
    val toolbar = remember { BlazeToolbar({ r, cp, all -> tbRect = r; tbCopy = cp; tbAll = all }, { tbRect = null }) }

    if (inCall) { CallScreen(onHangUp = { inCall = false }); return }
    val me = account.user
    // v0.24.4 private chat: confirm before leaving (the chat is thrown away).
    val isPrivate by Agent.privateChat.collectAsState()
    var confirmLeavePrivate by remember { mutableStateOf(false) }
    BackHandler(enabled = isPrivate && !confirmLeavePrivate) { confirmLeavePrivate = true }
    Box(Modifier.fillMaxSize().background(c.paper)) {
        ParticleField(mood)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            ShellTopBar(mode = shellMode,
                // v0.24.0: the toggle only switches tabs; there is no conversation list behind it.
                onMode = { m -> shellMode = m; if (m == "projects") projectOpen = "" },
                onLogo = { exitView(); shellMode = "chat"; showViews = false; plusOpen = false },
                onSearch = { showSearch = true },
                routerOk = Settings.ready,
                onRouter = { settingsPage = "ai" },
                avatar = me?.name?.ifBlank { me.email }?.ifBlank { null } ?: Settings.userName.ifBlank { "B" },
                onAvatar = { avatarMenu = true }, mood = mood,
                viewChip = if (showing.isNotEmpty() && shellMode == "chat") (Views.find(showing)?.name ?: showing) else "",
                onExit = { exitView() },
                isPrivate = isPrivate,
                onGhost = { if (isPrivate) confirmLeavePrivate = true else { exitView(); shellMode = "chat"; Agent.enterPrivate() } })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (shellMode == "projects") {
                    val needs = remember(question, allChats) { question?.let { q -> allChats.filter { it.title == q.chat }.map { it.id }.toSet() } ?: emptySet() }
                    ProjectsPane(projects, allChats, busyChats, needs, projectOpen,
                        onOpen = { projectOpen = it }, onBack = { projectOpen = "" },
                        onCreate = { projectOpen = com.blaze.assistant.agent.Projects.create(it).id },
                        onChat = { openChat(it) }, onNewChat = { newChat(it) },
                        onInstructions = { id, t -> com.blaze.assistant.agent.Projects.setInstructions(id, t) },
                        onBrief = { id, t -> com.blaze.assistant.agent.Projects.setBrief(id, t) },
                        onDelete = { com.blaze.assistant.agent.Projects.delete(it); projectOpen = "" })
                    LaunchedEffect(Unit) { Agent.refreshChats() }
                } else if (showing == "design") {
                    DesignView(designTarget, busy = mood != Mood.IDLE, status = liveStatus,
                        onAsk = { send(it) }, onOpenInBrowser = { u ->
                            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            catch (_: Exception) { com.blaze.assistant.browser.BrowserTools.show(ctx) } },
                        home = try { com.blaze.assistant.terminal.Terminal.home() } catch (_: Exception) { null })
                    BackHandler { exitView() }
                } else if (!hasMessages) {
                    Welcome(needsSetup = !Settings.ready, onSetup = { setupPage = 0; onboard = true }) { Agent.send(it) }
                } else {
                    val dens = LocalDensity.current
                    CompositionLocalProvider(LocalTextToolbar provides toolbar,
                        LocalDensity provides androidx.compose.ui.unit.Density(dens.density, dens.fontScale * Settings.textScale)) {
                        MessageList(mood,
                            onQuote = { quote = it },
                            onFollowup = { send(it) })
                    }
                }
                if (clearedAt > 0L && shellMode == "chat") {
                    LaunchedEffect(clearedAt) { delay(com.blaze.assistant.agent.ClearScreen.UNDO_MS - 3_000); clearedAt = 0L }
                    UndoBar("Chat cleared \u00b7 nothing is forgotten", onUndo = { Agent.undoClear(); clearedAt = 0L }, onDismiss = { clearedAt = 0L })
                }
                // Dim the chat while a sheet (+ menu, /views) is open, so half-cut lines at its edge read as background.
                if (shellMode == "chat" && showing.isEmpty() && (plusOpen || showViews)) SheetScrim { plusOpen = false; showViews = false }
            }
            if (shellMode == "chat" && showing.isEmpty()) {
                if (voiceState == Voice.State.LISTENING) ListeningStrip()
                LaunchedEffect(a11yOn) { if (a11yOn) Settings.setBool("a11y_seen", true) }
                LaunchedEffect(control) { if (control == com.blaze.assistant.phone.PhoneControl.State.CONNECTED) Settings.setBool("a11y_seen", true) }
                // v0.20.0: say which way it dropped and fix it in one tap (Blaze's own switch, not the whole list).
                if (control != com.blaze.assistant.phone.PhoneControl.State.CONNECTED && Settings.phoneControl && Settings.bool("a11y_seen", false))
                    PhoneControlBanner(control) {
                        ComeBack.watch(ctx) { BlazeAccessibilityService.instance != null }
                        try { ctx.startActivity(com.blaze.assistant.phone.PhoneControl.fixIntent(ctx)) }
                        catch (_: Exception) { ctx.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
            }
            ConfirmCard()
            QuestionCard()
            SecureFormCard()
            // v0.24.6: "Open in Design?" instead of switching views on its own.
            if (shellMode == "chat") viewOffer?.let { o -> if (showing != o.id) ViewOfferChip(Views.offerLabel(o.id),
                onOpen = { Views.offer.value = null; Views.find(o.id)?.let { openView(it, o.target) } }, onDismiss = { Views.offer.value = null }) }
            if (shellMode == "chat" && showing.isEmpty()) {
                val sugg = remember(input.text) { fileSuggestions(input.text).ifEmpty { CommandRunner.suggest(input.text) } }
                if (sugg.isNotEmpty() && !showViews) SuggestionList(sugg) { s ->
                    if (s.runNow) send(s.fill) else input = TextFieldValue(s.fill, TextRange(s.fill.length))
                }
                if (quote.isNotBlank()) QuoteStrip(quote) { quote = "" }
                if (pending.isNotEmpty() || loadingFiles) AttachmentThumbs(pending, loadingFiles) { a -> pending = pending - a }
                if (clipHasImage && pending.isEmpty() && !plusOpen) Row(Modifier.fillMaxWidth().background(c.paper)
                    .padding(horizontal = 14.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Label("Image on your clipboard", size = 10.sp, modifier = Modifier.weight(1f))
                    ActionText("Paste image", color = c.accent, size = 10.sp, modifier = Modifier.clickable { pasteImage() }.padding(8.dp))
                    ActionText("×", size = 12.sp, modifier = Modifier.clickable { clipHasImage = false }.padding(8.dp))
                }
                if (showViews) {
                    val badges = mapOf("agents" to bgAgents.count { it.live }.let { if (it > 0) "$it LIVE" else "" })
                    ViewsSheet(badges, onPick = { openView(it) }, onDismiss = { showViews = false })
                    BackHandler { showViews = false }
                }
                if (plusOpen) {
                    val model = ""
                    PlusMenu(plusItems(work, approval, model, clipHasImage, com.blaze.assistant.agent.Projects.of(currentChat) != null)) { id ->
                        plusOpen = false
                        when (id) {
                            "camera" -> openCamera()
                            "photos" -> photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            "files" -> files.launch(arrayOf("image/*", "application/pdf", "text/*"))
                            "atfile" -> { val t = input.text.let { if (it.isEmpty() || it.endsWith(" ")) it else "$it " } + "@"; input = TextFieldValue(t, TextRange(t.length)) }
                            "paste" -> pasteImage()
                            "voice" -> onMic()
                            "call" -> startCall()
                            "plan" -> com.blaze.assistant.agent.Modes.toggleWork()
                            "approval" -> com.blaze.assistant.agent.Modes.toggleApproval()
                            "model" -> settingsPage = "ai"
                            "views" -> showViews = true
                            "new" -> newChat(com.blaze.assistant.agent.Projects.of(Agent.chatId.value)?.id.orEmpty())
                            "clear" -> clearScreen()
                        }
                    }
                    BackHandler { plusOpen = false }
                }
                // v0.24.6: pictures straight from the keyboard (Samsung clipboard, GIFs, stickers) attach like picked photos.
                AcceptImages(onImage = { uri, mime -> addUris(listOf(uri), mime) }) {
                Composer(input, { input = it }, busy = mood != Mood.IDLE, canSend = input.text.isNotBlank() || pending.isNotEmpty(),
                    plusOpen = plusOpen, chip = modeChip(work, approval), agents = bgAgents.count { it.live },
                    listening = voiceState == Voice.State.LISTENING, speaking = voiceState == Voice.State.SPEAKING,
                    onPlus = { plusOpen = !plusOpen; showViews = false }, onMic = { onMic() }, onSend = { send(input.text) }, onStop = { Agent.stop() },
                    onChip = { plusOpen = true }, hints = hints, animateHints = !reducedMotion)
                }
            }
        }

        // Copy · Quote · Select all, above the selected words.
        tbRect?.let { r ->
            val d = LocalDensity.current
            val y = (r.top - with(d) { 52.dp.toPx() }).coerceAtLeast(with(d) { 40.dp.toPx() })
            Row(Modifier.offset { IntOffset(r.left.toInt().coerceAtMost((r.right - with(d) { 230.dp.toPx() }).toInt().coerceAtLeast(0)).coerceAtLeast(12), y.toInt()) }
                .shadow(8.dp, BlazeShapes.pill).clip(BlazeShapes.pill).background(c.ink, BlazeShapes.pill).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("Copy" to { tbCopy?.invoke(); toolbar.hide() },
                    "Quote" to {
                        tbCopy?.invoke()
                        val t = clip.getText()?.text.orEmpty()
                        if (t.isNotBlank()) quote = t.trim()
                        toolbar.hide()
                    },
                    "Select all" to { tbAll?.invoke() }).forEach { (label, act) ->
                    Box(Modifier.clip(BlazeShapes.pill).clickable { act() }.padding(horizontal = 14.dp, vertical = 12.dp)) { ActionText(label, color = c.paper, size = 11.sp) }
                }
            }
        }

        if (avatarMenu) {
            val dark = c.dark
            val themeSub = when (ThemeMode.mode.value) { "dark" -> "Dark"; "light" -> "Light"; else -> "System" }
            val items = buildList {
                add(MenuItem("account", "Account", if (me != null) "Signed in" else "Sign in"))
                add(MenuItem("settings", "Settings"))
                add(MenuItem("views", "All views", "/VIEWS"))
                add(MenuItem("control", "Phone control", if (Settings.phoneControl && a11yOn) "ON" else "OFF"))
                add(MenuItem("theme", if (dark) "Light theme" else "Dark theme", themeSub))
                add(MenuItem("update", "Check for updates", "v" + runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrDefault("")))
                if (me != null) add(MenuItem("signout", "Sign out"))
            }
            AvatarMenu(me?.name.orEmpty().ifBlank { Settings.userName }, me?.email.orEmpty(), items,
                onPick = { id ->
                    avatarMenu = false
                    when (id) {
                        "account" -> settingsPage = "account"
                        "settings" -> settingsPage = ""
                        "router" -> settingsPage = "ai"
                        "views" -> showViews = true
                        "control" -> settingsPage = "control"
                        "theme" -> ThemeMode.set(if (dark) "light" else "dark")
                        "update" -> com.blaze.assistant.update.Updater.start(ctx)
                        "signout" -> scope.launch { com.blaze.assistant.connect.BlazeAccount.signOut() }
                    }
                },
                onLongPick = { id -> if (id == "theme") { avatarMenu = false; ThemeMode.set("system"); Toast.makeText(ctx, "Following your phone's theme", Toast.LENGTH_SHORT).show() } },
                onDismiss = { avatarMenu = false })
            BackHandler { avatarMenu = false }
        }
        if (showSearch) {
            SearchScreen(onView = { openView(it) }, onAsk = { showSearch = false; send(it) }, onDismiss = { showSearch = false })
            BackHandler { showSearch = false }
        }
        if (galleryOpen) GalleryScreen(onDismiss = { GalleryUi.open.value = false })
        if (confirmLeavePrivate) BlazeDialog(
            title = com.blaze.assistant.agent.PrivateChat.LEAVE_TITLE, body = com.blaze.assistant.agent.PrivateChat.LEAVE_TEXT,
            confirm = "Leave", onConfirm = { confirmLeavePrivate = false; Agent.leavePrivate() }, warm = true,
            dismiss = "Stay", onDismiss = { confirmLeavePrivate = false })
        if (!onboard && gate) SetupGateScreen(onOpenPage = { settingsPage = it }, onDone = { gate = false })
        if (onboard) SetupFlow(setupPage) { prompt ->
            onboard = false; setupPage = 0
            gate = com.blaze.assistant.setup.SetupGate.shouldGate(ctx)
            when {
                prompt == null -> {}
                prompt.startsWith("/settings") -> settingsPage = "ai"
                else -> Agent.send(prompt)
            }
        }
        settingsPage?.let { start ->
            androidx.compose.runtime.key(start) {
                SettingsScreen(start, a11yOn,
                    onOpenAccessibility = { ctx.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    onDismiss = { settingsPage = null })
            }
        }
    }
}

/** "@ind" → files in Blaze's home whose path matches, to mention in the message. */
private fun fileSuggestions(text: String): List<Suggestion> {
    val tok = text.substringAfterLast(' ').substringAfterLast('\n')
    if (!tok.startsWith("@") || text.startsWith("/")) return emptyList()
    val q = tok.removePrefix("@").lowercase()
    val home = try { com.blaze.assistant.terminal.Terminal.home() } catch (_: Exception) { return emptyList() }
    val found = home.walkTopDown().maxDepth(3).onEnter { !it.name.startsWith(".") || it == home }
        .filter { it.isFile && !it.name.startsWith(".") }
        .map { it.relativeTo(home).path }
        .filter { q.isEmpty() || it.lowercase().contains(q) }
        .take(12).toList()
    val head = text.removeSuffix(tok)
    return found.map { p -> Suggestion("@$p", "file in ~/", "$head@~/$p ", runNow = false) }
}

@Composable
private fun MessageList(mood: Mood, onQuote: (String) -> Unit, onFollowup: (String) -> Unit) {
    val c = LocalBlaze.current
    val messages by Agent.messages.collectAsState()
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val items = remember(messages, mood) { buildItems(messages, mood != Mood.IDLE) }
    val total = items.size + 1 // + working row
    val atBottom by remember { derivedStateOf { !state.canScrollForward } }
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(state.isScrollInProgress) { if (state.isScrollInProgress) follow = atBottom }
    LaunchedEffect(atBottom) { if (atBottom) follow = true }

    suspend fun toEnd(animated: Boolean) {
        if (animated) state.animateScrollToItem(total - 1) else state.scrollToItem(total - 1)
        state.scrollBy(100_000f)
    }
    // Open at the latest message, and jump back there whenever you come back to Blaze.
    LaunchedEffect(Unit) { toEnd(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) scope.launch { delay(50); toEnd(false); follow = true } }
        lifecycle.addObserver(obs); onDispose { lifecycle.removeObserver(obs) }
    }
    // New messages and streamed words keep you at the bottom unless you scrolled up to read.
    val lastLen = messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(messages.size, lastLen, mood) { if (follow) toEnd(false) }
    // Your own new message always brings you down.
    val lastUser = messages.lastOrNull { it.role == Role.USER }?.id
    LaunchedEffect(lastUser) { if (lastUser != null) { follow = true; toEnd(true) } }

    val lastBlazeId = messages.lastOrNull()?.takeIf { it.role == Role.BLAZE }?.id
    val meter by Agent.meter.collectAsState()
    val lastStepsKey = items.lastOrNull { it is Item.Steps }?.key
    Box(Modifier.fillMaxSize()) {
        // v0.24.0: a messaging thread. No uniform gaps: runs from one side sit 3dp apart, groups 16dp.
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)) {
            itemsIndexed(items, key = { _, it -> it.key }) { i, item ->
                Box(Modifier.fillMaxWidth().padding(top = Bubbles.gapAbove(item.pos, i == 0))) {
                    when (item) {
                        is Item.Steps -> {
                            val st by Agent.status.collectAsState()
                            // v0.24.4: the token meter is a technical line; shown only with Show technical details on.
                            val m = if (Settings.longChat && (com.blaze.assistant.agent.ReplyChips.showTech() || meter?.high == true) && (item.live || item.key == lastStepsKey)) meter else null
                            WorkCard(item.steps, item.live, item.startAt, item.endAt, status = if (item.live) st else "", exact = Settings.showSteps,
                                meter = m?.text.orEmpty(), meterHigh = m?.high == true)
                        }
                        is Item.Msg -> Entry(item.m, item.pos, canAct = mood == Mood.IDLE, onQuote = onQuote,
                            followups = if (item.m.id == lastBlazeId && mood == Mood.IDLE && Settings.followups) item.m.followups else emptyList(),
                            onFollowup = onFollowup)
                    }
                }
            }
            item(key = "working") {
                val live by Agent.thinking.collectAsState()
                val status by Agent.status.collectAsState()
                // One live card per turn: before the first step it's an empty "Working · 3s" card.
                val hasLiveSteps = (items.lastOrNull() as? Item.Steps)?.live == true
                AnimatedVisibility(mood != Mood.IDLE, enter = fadeIn(), exit = fadeOut()) {
                    Column(Modifier.padding(top = if (hasLiveSteps || items.lastOrNull()?.side == Side.BLAZE) 3.dp else 16.dp)) {
                        if (!hasLiveSteps) WorkCard(emptyList(), live = true,
                            startAt = messages.lastOrNull { it.role == Role.USER }?.at ?: 0L, endAt = 0L,
                            status = status.ifBlank { com.blaze.assistant.agent.Friendly.between(null) })
                        if (live.isNotBlank() && Settings.showReasoning) ReasoningBlock(live, liveNow = true)
                    }
                }
                // Past 80% of the meter: offer a fresh chat that opens with this one's summary (v0.15.0).
                if (mood == Mood.IDLE && Settings.longChat && meter?.high == true) FreshChatOffer { Agent.freshChat() }
            }
        }
        AnimatedVisibility(!atBottom, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(c.ink).clickable { scope.launch { follow = true; toEnd(true) } }
                .semantics { contentDescription = "Jump to the newest message" }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(14.dp)) {
                    val u = size.width / 7f
                    // v0.24.8: a smooth rounded down-arrow (was pixel squares)
                    val sw = 1.8.dp.toPx(); val cap = androidx.compose.ui.graphics.StrokeCap.Round
                    drawLine(c.paper, Offset(3.5f * u, 0.5f * u), Offset(3.5f * u, 6.3f * u), sw, cap)
                    drawLine(c.paper, Offset(0.8f * u, 3.6f * u), Offset(3.5f * u, 6.3f * u), sw, cap)
                    drawLine(c.paper, Offset(6.2f * u, 3.6f * u), Offset(3.5f * u, 6.3f * u), sw, cap)
                }
            }
        }
    }
}

/** "14 steps" folded into one line. Tap to see them all. While working, the newest step shows. */
@Composable
private fun StepsGroup(steps: List<ChatMessage>, live: Boolean) {
    val c = LocalBlaze.current
    var open by rememberSaveable(steps.first().id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(if (live) c.accent else c.weak, BlazeShapes.pill))
            Spacer(Modifier.width(10.dp))
            Label(if (steps.size == 1) "1 step" else "${steps.size} steps", color = c.sub, size = 10.sp)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.width(14.dp).height(1.dp).background(c.hair))
            Spacer(Modifier.width(10.dp))
            Label(if (open) "Hide" else steps.last().text.take(38), color = if (live) c.accent else c.weak, size = 10.sp, modifier = Modifier.weight(1f))
            val rot by animateFloatAsState(if (open) 180f else 0f, label = "chev")
            Text("⌄", modifier = Modifier.padding(start = 6.dp).graphicsLayerRotation(rot), style = TextStyle(fontSize = 14.sp, color = c.sub))
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(start = 3.dp).hairlineStart(c.hair).padding(start = 13.dp, top = 2.dp, bottom = 2.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                steps.forEachIndexed { i, s ->
                    Row(verticalAlignment = Alignment.Top) {
                        Label("%02d".format(i + 1), color = c.weak, size = 10.sp)
                        Spacer(Modifier.width(10.dp))
                        Label(s.text, color = c.sub, size = 10.5.sp)
                    }
                }
            }
        }
    }
}

private fun Modifier.graphicsLayerRotation(deg: Float) = this.rotate(deg)
private fun Modifier.hairlineStart(color: Color) = this.drawBehind {
    drawRect(color, Offset.Zero, Size(1.dp.toPx(), size.height))
}

/** Little pixel brain + "Reasoning"; tap to read how the model thought. */
@Composable
private fun ReasoningBlock(text: String, liveNow: Boolean = false) {
    val c = LocalBlaze.current
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.clickable { open = !open }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            BrainGlyph(if (liveNow) c.accent else c.sub)
            Spacer(Modifier.width(8.dp))
            Label(if (liveNow) "Reasoning" else if (open) "Hide reasoning" else "Reasoning", color = if (liveNow) c.accent else c.sub, size = 10.sp)
            Spacer(Modifier.width(6.dp))
            Text(if (open) "⌃" else "⌄", style = TextStyle(fontSize = 12.sp, color = c.sub))
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Box(Modifier.widthIn(max = 330.dp).hairlineStart(c.accent).padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
                .heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                SelectionContainer {
                    Text(text, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.sub))
                }
            }
        }
    }
}

@Composable
fun BrainGlyph(color: Color) {
    // 9x8 pixel brain, two lobes with folds.
    val rows = listOf(
        "..##.##..",
        ".#..#..#.",
        "#..#.#..#",
        "#.#..#.##",
        "#..#.#..#",
        ".#..#.#.#",
        "..##.##..",
        "....#....")
    Canvas(Modifier.size(15.dp, 13.dp)) {
        val u = size.width / 9f
        rows.forEachIndexed { y, r -> r.forEachIndexed { x, ch -> if (ch == '#') drawCircle(color, u * 0.5f, Offset(x * u + u / 2f, y * u + u / 2f)) } }
    }
}

/**
 * One message in the thread (v0.24.0): yours on the right in an ink bubble, Blaze's on the left in a soft bubble,
 * errors in Blaze's bubble with an accent wash. Only the last message of a group gets the quiet time + actions line.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Entry(m: ChatMessage, pos: GroupPos, canAct: Boolean, onQuote: (String) -> Unit, followups: List<String>, onFollowup: (String) -> Unit) {
    val time = remember(m.at) { Bubbles.time(m.at) }
    when (m.role) {
        Role.USER -> SwipeToQuote(true, onQuote = { onQuote(splitQuote(m.text).second) }) { Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            val (quoted, body) = splitQuote(m.text)
            // v0.24.6: pictures inside your bubble (tap to preview), other files as icon chips.
            SentBubble(body, quoted, m.media, m.files, pos)
            if (pos.ends) Actions(m, Side.ME, time, canAct, onQuote, allowQuote = false)
        } }
        Role.BLAZE -> if (m.meta == com.blaze.assistant.update.Updater.CARD) UpdateCard()
        else if (m.meta.startsWith(com.blaze.assistant.agent.BgAgents.CARD)) AgentCard(m.meta.removePrefix(com.blaze.assistant.agent.BgAgents.CARD))
        else SwipeToQuote(true, onQuote = { onQuote(m.text) }) { Column(Modifier.fillMaxWidth()) {
            if (m.thinking.isNotBlank() && Settings.showReasoning) ReasoningBlock(m.thinking)
            BlazeReply(m.id, m.text, pos)
            if (m.media.isNotEmpty()) MediaStrip(m.media)
            if (pos.ends || followups.isNotEmpty()) Actions(m, Side.BLAZE, time, canAct, onQuote, allowQuote = true)
            else if (com.blaze.assistant.agent.ReplyChips.visible(m.meta, com.blaze.assistant.agent.ReplyChips.showTech()).isNotBlank()) Actions(m, Side.BLAZE, "", canAct, onQuote, allowQuote = true, chipOnly = true)
            if (followups.isNotEmpty()) Followups(followups, onFollowup)
        } }
        Role.ERROR -> Column(Modifier.fillMaxWidth()) {
            ErrorBubble(m.text, pos)
            Actions(m, Side.BLAZE, time, canAct, onQuote, allowQuote = false)
            // Pick up where it broke instead of starting over: the history keeps the steps done so far.
            // (v0.25.0: no brain switcher on old "used up" errors; there is nothing to pick.)
            if (canAct) Followups(listOf("Continue where you stopped"), onFollowup)
        }
        Role.STEP -> {}
    }
}

private fun splitQuote(t: String): Pair<String, String> {
    val lines = t.lines()
    val q = lines.takeWhile { it.startsWith(">") }
    if (q.isEmpty()) return "" to t
    return q.joinToString("\n") { it.removePrefix(">").trim() } to lines.drop(q.size).joinToString("\n").trim()
}

@Composable
private fun Followups(list: List<String>, onPick: (String) -> Unit) {
    Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        list.forEach { s -> SoftPill(s, lead = "↳") { onPick(s) } }
    }
}

/** Time · Copy · Quote · Retry under the last message of a group, small and quiet. Long-press words to copy or quote just those. */
@Composable
private fun Actions(m: ChatMessage, side: Side, time: String, canAct: Boolean, onQuote: (String) -> Unit, allowQuote: Boolean, chipOnly: Boolean = false) {
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    val usedUp = m.meta.startsWith("usedup:")
    // v0.24.4: technical parts of the chip (timings, data, model ids) are hidden unless Show technical details is on.
    val chip = if (usedUp) "" else com.blaze.assistant.agent.ReplyChips.visible(m.meta, com.blaze.assistant.agent.ReplyChips.showTech())
    if (chipOnly && chip.isBlank()) return
    if (chipOnly) { MetaLine(side, "", emptyList(), chip, chip.startsWith("⚡")); return }
    val acts = buildList<Pair<String, (() -> Unit)?>> {
        add("Copy" to { clip.setText(AnnotatedString(if (m.role == Role.BLAZE) Md.plain(m.text) else m.text)); Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show() })
        if (allowQuote) add("Quote" to { onQuote(m.text) })
        add("Retry" to (if (canAct) ({ Agent.retry(m.id) }) else null))
    }
    MetaLine(side, time, acts, chip, chip.startsWith("⚡"))
}

@Composable
private fun QuoteStrip(q: String, onClear: () -> Unit) {
    val c = LocalBlaze.current
    Row(Modifier.fillMaxWidth().background(c.paper).padding(start = 12.dp, end = 12.dp, top = 6.dp).clip(RoundedCornerShape(16.dp)).background(c.bubble)
        .padding(start = 12.dp, end = 2.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(34.dp).clip(RoundedCornerShape(2.dp)).background(c.accent))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Label("Replying to", color = c.accent, size = 9.sp)
            Text(q, maxLines = 2, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.body))
        }
        ActionText("×", size = 14.sp, modifier = Modifier.clip(CircleShape).clickable(onClick = onClear).semantics { contentDescription = "Cancel reply" }.padding(12.dp))
    }
}

@Composable
private fun SuggestionList(list: List<Suggestion>, onPick: (Suggestion) -> Unit) {
    val c = LocalBlaze.current
    Column(Modifier.fillMaxWidth().background(c.paper).padding(horizontal = 10.dp, vertical = 4.dp).clip(RoundedCornerShape(18.dp)).background(c.raised)
        .heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
        list.forEach { s ->
            Row(Modifier.fillMaxWidth().clickable { onPick(s) }.padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(s.title, maxLines = 1, style = TextStyle(fontFamily = Type.Mono, fontSize = 13.sp, color = c.ink))
                Spacer(Modifier.width(12.dp))
                Text(s.sub, maxLines = 1, modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
            }
        }
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
internal fun Welcome(needsSetup: Boolean, onSetup: () -> Unit, onPick: (String) -> Unit) {
    val c = LocalBlaze.current
    val name = Settings.userName
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 28.dp)
    ) {
        Label("AI assistant · On your phone")
        Spacer(Modifier.height(14.dp))
        Text(
            greeting() + if (name.isNotBlank()) ",\n$name." else ".",
            style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 46.sp,
                lineHeight = 48.sp, letterSpacing = (-0.045).em, color = c.ink)
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "Ask anything. Blaze answers, opens apps, taps and types for you, and checks with you before anything that matters.",
            style = TextStyle(fontFamily = Type.Sans, fontSize = 17.sp, lineHeight = 26.sp, color = c.body)
        )
        Spacer(Modifier.height(32.dp))

        if (needsSetup) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.ink).clickable(onClick = onSetup).padding(18.dp)) {
                ButtonText("Make me smarter · one tap  →", color = c.paper)
                Spacer(Modifier.height(4.dp))
                Text("I already work. Signing in makes me faster and smarter.", style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.paper.copy(alpha = 0.75f)))
            }
            Spacer(Modifier.height(24.dp))
        }
        run {
            Label("Try")
            Spacer(Modifier.height(10.dp))
            val items = listOf(
                "Set an alarm for 6:30",
                "Draw a lion wearing sunglasses",
                "Make me an afro beat",
                "Open YouTube and play lo-fi music"
            )
            // v0.24.0: quick replies, as bubbles you could send.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { s -> SoftPill(s, fill = c.bubble, ink = c.ink) { onPick(s) } }
            }
        }
    }
}

@Composable
private fun BlinkLabel(text: String) {
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { while (true) { delay(500); on = !on } }
    Label(text + if (on) " _" else "  ", color = LocalBlaze.current.sub)
}

@Composable
private fun ListeningStrip() {
    val c = LocalBlaze.current
    val partial by Voice.partial.collectAsState()
    val level by Voice.level.collectAsState()
    val w by animateFloatAsState(0.15f + level * 0.85f, label = "lvl")
    Column(Modifier.fillMaxWidth().background(c.paper).hairlineTop(c.hair).padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BlinkLabel("Listening")
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f).height(2.dp).clip(BlazeShapes.pill).background(c.faint)) {
                Box(Modifier.fillMaxWidth(w).height(2.dp).clip(BlazeShapes.pill).background(c.accent))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(partial.ifBlank { "Say \"Open YouTube\" or ask anything" },
            style = TextStyle(fontFamily = Type.Display, fontSize = 20.sp, color = if (partial.isBlank()) c.weak else c.ink))
    }
}

@Composable
private fun AttachmentStrip(files: List<com.blaze.assistant.agent.Attachment>, loading: Boolean, onRemove: (com.blaze.assistant.agent.Attachment) -> Unit) {
    val c = LocalBlaze.current
    Row(Modifier.fillMaxWidth().background(c.paper).padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        files.forEach { f ->
            Row(Modifier.clip(RoundedCornerShape(16.dp)).background(c.bubble).clickable { onRemove(f) }.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(f.name.take(18), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.body))
                Spacer(Modifier.width(6.dp)); Label("×", size = 12.sp)
            }
        }
        if (loading) Label("Reading…", color = c.accent, size = 10.sp)
    }
}

/**
 * Shown under "credits used up": pick another provider + model in two taps, then Blaze carries on.
 * The model that just ran out (and any model whose keys are all resting) is left out of the list.
 */
@Composable
private fun BrainSwitcher(spent: String, onPicked: () -> Unit) {
    val c = LocalBlaze.current
    val (spentPid, spentModel) = spent.substringBefore('|') to spent.substringAfter('|')
    val P = com.blaze.assistant.agent.Providers
    val R = com.blaze.assistant.agent.KeyRing
    val providers = remember { P.all.filter { it.id != P.AUTO && R.all(it.id).isNotEmpty() } }
    var open by remember { mutableStateOf(false) }
    var pid by remember { mutableStateOf(spentPid.takeIf { s -> providers.any { it.id == s } } ?: providers.firstOrNull()?.id.orEmpty()) }
    var models by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(pid, open) {
        if (!open || pid.isBlank()) return@LaunchedEffect
        models = null
        val l = com.blaze.assistant.agent.ModelCatalog.modelsFor(pid)
        models = l.filter { !(pid == spentPid && it == spentModel) && !R.usedUp(pid, it) }
    }
    Column(Modifier.padding(top = 8.dp).widthIn(max = 330.dp).fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.ink).clickable { open = !open }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Label("Switch brain and continue", color = c.paper, size = 10.sp, modifier = Modifier.weight(1f))
            ButtonText(if (open) "\u25B2" else "\u25BC", color = c.paper)
        }
        if (open) Column(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.bubble).padding(12.dp)) {
            // Fastest option: hand it to the free brains.
            ActionText("Auto \u00b7 free brains", color = c.accent, size = 10.sp, modifier = Modifier.fillMaxWidth().clickable {
                Settings.providerId = P.AUTO; open = false; onPicked() }.padding(vertical = 10.dp))
            if (providers.isNotEmpty()) {
                Label("Provider", size = 9.sp, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    providers.forEach { p ->
                        val on = p.id == pid
                        Box(Modifier.padding(end = 6.dp).clip(RoundedCornerShape(14.dp)).background(if (on) c.ink else c.paper).clickable { pid = p.id }
                            .padding(horizontal = 10.dp, vertical = 8.dp)) {
                            ButtonText(p.name.substringBefore(" ("), color = if (on) c.paper else c.ink, size = 13.sp)
                        }
                    }
                }
                Label("Model", size = 9.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                val l = models
                when {
                    l == null -> Label("Loading models\u2026", size = 9.sp, modifier = Modifier.padding(vertical = 8.dp))
                    l.isEmpty() -> Label("No other models on this provider. Pick another provider or Auto.", size = 9.sp, modifier = Modifier.padding(vertical = 8.dp))
                    else -> Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        l.forEach { id ->
                            Text(id, modifier = Modifier.fillMaxWidth().clickable {
                                Settings.providerId = pid
                                Settings.setStr("model_$pid", id)
                                open = false; onPicked()
                            }.padding(vertical = 9.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.ink))
                        }
                    }
                }
            }
        }
    }
}


/** "call blaze", "let's talk", "voice call"… opens the call screen. */
// Also "let's get on a call", "can we have a call", "start a voice call with blaze". "call Sam" is NOT this.
private val callWords = Regex("""^(?:(?:hey |ok )?(?:blaze[, ]+)?)(?:(?:can we|could we|can you|let['’]?s|lets|i want to|i wanna|wanna|please)\s+)?(?:(?:get|hop|jump|be)\s+on\s+(?:a\s+)?(?:voice\s+)?call|(?:start|have|do|make)\s+a\s+(?:voice\s+)?call|call(?:\s+(?:blaze|me))?|talk(?:\s+to\s+me)?|voice\s+(?:call|chat|mode))(?:\s+(?:with\s+(?:me|blaze|you)|blaze|now|please))*[.!?]*$""", RegexOption.IGNORE_CASE)
