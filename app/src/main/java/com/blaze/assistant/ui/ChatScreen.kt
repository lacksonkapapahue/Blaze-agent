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

/** What the message list draws: a message, or a folded run of steps. */
private sealed class Item(val key: String) {
    class Msg(val m: ChatMessage) : Item("m${m.id}")
    class Steps(val steps: List<ChatMessage>, val live: Boolean) : Item("s${steps.first().id}")
}

private fun buildItems(messages: List<ChatMessage>, working: Boolean): List<Item> {
    val out = mutableListOf<Item>()
    var run = mutableListOf<ChatMessage>()
    fun flush(live: Boolean) { if (run.isNotEmpty()) { out += Item.Steps(run, live); run = mutableListOf() } }
    messages.forEach { m -> if (m.role == Role.STEP) run += m else { flush(false); out += Item.Msg(m) } }
    flush(working)
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
    var onboard by rememberSaveable { mutableStateOf(!Settings.bool("onboarded", false) && !com.blaze.assistant.agent.Brains.hasAny() && !hasMessages) }
    var setupPage by rememberSaveable { mutableStateOf(0) }
    val galleryOpen by GalleryUi.open.collectAsState()
    LaunchedEffect(Unit) { if (!onboard) Settings.setBool("onboarded", true) }
    // Blaze opening one of its own settings pages (setup help).
    val askedPage by com.blaze.assistant.agent.SelfSetup.openPage.collectAsState()
    LaunchedEffect(askedPage) { askedPage?.let { settingsPage = it; com.blaze.assistant.agent.SelfSetup.openPage.value = null } }
    var showChats by rememberSaveable { mutableStateOf(false) }
    var drawer by rememberSaveable { mutableStateOf(false) }
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var quote by rememberSaveable { mutableStateOf("") }

    var pending by remember { mutableStateOf(listOf<Attachment>()) }
    var loadingFiles by remember { mutableStateOf(false) }
    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        loadingFiles = true
        scope.launch {
            val loaded = uris.take(5).mapNotNull { Attachments.load(ctx, it) }
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
        val clipData = try { sysClip.primaryClip } catch (_: Exception) { null } ?: return
        val uris = (0 until clipData.itemCount).mapNotNull { clipData.getItemAt(it).uri }
        if (uris.isEmpty()) Toast.makeText(ctx, "That image can't be pasted here.", Toast.LENGTH_SHORT).show()
        addUris(uris); clipHasImage = false
    }

    var a11yOn by remember { mutableStateOf(BlazeAccessibilityService.isEnabled(ctx)) }
    LaunchedEffect(Unit) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { a11yOn = BlazeAccessibilityService.isEnabled(ctx); delay(2500) }
        }
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

    fun send(raw: String) {
        val text = raw.trim()
        if (callWords.matches(text)) { input = TextFieldValue(""); startCall(); return }
        val cmd = CommandRunner.run(ctx, text, openSettings = { settingsPage = it }, openChats = { Agent.refreshChats(); showChats = true }, listen = { onMic() })
        if (cmd == "") { input = TextFieldValue(""); return }
        val body = cmd ?: text
        val full = if (quote.isNotBlank()) quote.lines().joinToString("\n") { "> $it" } + "\n\n" + body else body
        if (full.isBlank() && pending.isEmpty()) return
        if (mood != Mood.IDLE) return
        Agent.send(full, attachments = pending)
        pending = emptyList(); quote = ""; input = TextFieldValue("")
    }

    // Selection toolbar state.
    var tbRect by remember { mutableStateOf<Rect?>(null) }
    var tbCopy by remember { mutableStateOf<(() -> Unit)?>(null) }
    var tbAll by remember { mutableStateOf<(() -> Unit)?>(null) }
    val toolbar = remember { BlazeToolbar({ r, cp, all -> tbRect = r; tbCopy = cp; tbAll = all }, { tbRect = null }) }

    if (inCall) { CallScreen(onHangUp = { inCall = false }); return }
    Box(Modifier.fillMaxSize().background(c.paper)) {
        ParticleField(mood)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            TopBar(mood, controlOn = Settings.phoneControl && a11yOn, onMenu = { drawer = true }, onNew = { Agent.clear(); quote = "" }, onCall = { startCall() })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (!hasMessages) {
                    Welcome(needsSetup = !com.blaze.assistant.agent.Brains.hasAny(), onSetup = { setupPage = 0; onboard = true }) { Agent.send(it) }
                } else {
                    val dens = LocalDensity.current
                    CompositionLocalProvider(LocalTextToolbar provides toolbar,
                        LocalDensity provides androidx.compose.ui.unit.Density(dens.density, dens.fontScale * Settings.textScale)) {
                        MessageList(mood,
                            onQuote = { quote = it },
                            onFollowup = { send(it) })
                    }
                }
            }
            if (voiceState == Voice.State.LISTENING) ListeningStrip()
            LaunchedEffect(a11yOn) { if (a11yOn) Settings.setBool("a11y_seen", true) }
            if (!a11yOn && Settings.phoneControl && Settings.bool("a11y_seen", false)) Row(Modifier.fillMaxWidth().background(c.paper).hairlineTop(c.accent)
                .padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Label("Blaze lost phone control", size = 10.sp, modifier = Modifier.weight(1f))
                Label("Turn it back on", color = c.accent, size = 10.sp, modifier = Modifier.clickable {
                    ComeBack.watch(ctx) { BlazeAccessibilityService.isEnabled(ctx) }
                    ctx.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.padding(8.dp))
            }
            ConfirmCard()
            QuestionCard()
            val sugg = remember(input.text) { CommandRunner.suggest(input.text) }
            if (sugg.isNotEmpty()) SuggestionList(sugg) { s ->
                if (s.runNow) send(s.fill) else input = TextFieldValue(s.fill, TextRange(s.fill.length))
            }
            if (quote.isNotBlank()) QuoteStrip(quote) { quote = "" }
            if (pending.isNotEmpty() || loadingFiles) AttachmentStrip(pending, loadingFiles) { a -> pending = pending - a }
            if (clipHasImage && pending.isEmpty()) Row(Modifier.fillMaxWidth().background(c.paper).hairlineTop(c.hair)
                .padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Label("Image on your clipboard", size = 10.sp, modifier = Modifier.weight(1f))
                Label("Paste image", color = c.accent, size = 10.sp, modifier = Modifier.clickable { pasteImage() }.padding(8.dp))
                Label("×", size = 12.sp, modifier = Modifier.clickable { clipHasImage = false }.padding(8.dp))
            }
            InputBar(input, { input = it }, busy = mood != Mood.IDLE, voice = voiceState, hasFiles = pending.isNotEmpty(),
                onCamera = { openCamera() },
                onPhotos = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onFiles = { files.launch(arrayOf("image/*", "application/pdf", "text/*")) },
                onPasteImage = if (clipHasImage) ({ pasteImage() }) else null,
                onMic = { onMic() }, onSend = { send(input.text) }, onStop = { Agent.stop() })
        }

        // Copy · Quote · Select all, above the selected words.
        tbRect?.let { r ->
            val d = LocalDensity.current
            val y = (r.top - with(d) { 52.dp.toPx() }).coerceAtLeast(with(d) { 40.dp.toPx() })
            Row(Modifier.offset { IntOffset(r.left.toInt().coerceAtMost((r.right - with(d) { 230.dp.toPx() }).toInt().coerceAtLeast(0)).coerceAtLeast(12), y.toInt()) }
                .background(c.ink), verticalAlignment = Alignment.CenterVertically) {
                listOf("Copy" to { tbCopy?.invoke(); toolbar.hide() },
                    "Quote" to {
                        tbCopy?.invoke()
                        val t = clip.getText()?.text.orEmpty()
                        if (t.isNotBlank()) quote = t.trim()
                        toolbar.hide()
                    },
                    "Select all" to { tbAll?.invoke() }).forEach { (label, act) ->
                    Box(Modifier.clickable { act() }.padding(horizontal = 14.dp, vertical = 12.dp)) { Label(label, color = c.paper, size = 11.sp) }
                }
            }
        }

        // Sidebar
        AnimatedVisibility(drawer, enter = fadeIn(tween(180)), exit = fadeOut(tween(180))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable(remember { MutableInteractionSource() }, null) { drawer = false })
        }
        AnimatedVisibility(drawer, enter = slideInHorizontally(tween(220)) { -it }, exit = slideOutHorizontally(tween(200)) { -it }) {
            Sidebar(
                onClose = { drawer = false },
                onNew = { Agent.clear(); quote = ""; drawer = false },
                onAllChats = { Agent.refreshChats(); showChats = true; drawer = false },
                onPage = { settingsPage = it; drawer = false },
                onOpenChat = { Agent.open(it); drawer = false }
            )
        }
        if (drawer) BackHandler { drawer = false }

        if (showChats) ChatsSheet(onDismiss = { showChats = false })
        if (galleryOpen) GalleryScreen(onDismiss = { GalleryUi.open.value = false })
        if (onboard) SetupFlow(setupPage) { prompt ->
            onboard = false; setupPage = 0
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

@Composable
private fun TopBar(mood: Mood, controlOn: Boolean, onMenu: () -> Unit, onNew: () -> Unit, onCall: () -> Unit = {}) {
    val c = LocalBlaze.current
    Column(Modifier.fillMaxWidth().background(c.paper).hairlineBottom(c.hair)) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clickable(onClick = onMenu), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(18.dp, 14.dp)) {
                    val h = 2.dp.toPx()
                    listOf(0f, size.height / 2 - h / 2, size.height - h).forEachIndexed { i, y ->
                        drawRect(c.ink, Offset(0f, y), Size(if (i == 1) size.width * 0.7f else size.width, h))
                    }
                }
            }
            BrandMark(mood, 26.dp)
            Spacer(Modifier.width(10.dp))
            Text("Blaze", modifier = Modifier.weight(1f),
                style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 18.sp, letterSpacing = (-0.015).em, color = c.ink))
            Box(Modifier.padding(end = 8.dp).size(38.dp).hairline(c.hair).clickable(onClick = onCall), contentAlignment = Alignment.Center) {
                PhoneGlyph(c.ink, 16.dp)
            }
            Row(Modifier.hairline(c.hair).clickable(onClick = onNew).padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("+", style = TextStyle(fontFamily = Type.Display, fontSize = 15.sp, color = c.ink))
                Spacer(Modifier.width(6.dp))
                Label("New", color = c.ink, size = 11.sp)
            }
        }
        Row(Modifier.fillMaxWidth().hairlineTop(c.hair).padding(horizontal = 20.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(if (controlOn) c.accent else c.weak))
            Spacer(Modifier.width(8.dp))
            Label(when (mood) {
                Mood.ACTING -> "Working on your phone"
                Mood.THINKING -> "Thinking"
                Mood.IDLE -> if (controlOn) "Phone control on" else "Chat only"
            }, size = 10.sp, modifier = Modifier.weight(1f))
            // Show what really answers: Auto, the picked model, and a separate steps model if one is set.
            val main = if (Settings.providerId == com.blaze.assistant.agent.Providers.AUTO) "AUTO (FREE)" else Settings.model.substringAfterLast('/').take(24)
            val steps = Settings.stepsProvider.takeIf { it.isNotBlank() && it != Settings.providerId }
                ?.let { if (it == com.blaze.assistant.agent.Providers.AUTO) " + steps: auto" else " + steps: " + Settings.stepsModel.substringAfterLast('/').take(12) }.orEmpty()
            Label(main + steps, color = c.weak, size = 9.sp)
        }
    }
}

@Composable
private fun Sidebar(onClose: () -> Unit, onNew: () -> Unit, onAllChats: () -> Unit, onPage: (String) -> Unit, onOpenChat: (String) -> Unit) {
    val c = LocalBlaze.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val chats by Agent.chats.collectAsState()
    val busy by Agent.busyChats.collectAsState()
    val current by Agent.chatId.collectAsState()
    var q by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { Agent.refreshChats() }
    Column(Modifier.fillMaxHeight().width(304.dp).background(c.paper).hairline(c.hair)
        .clickable(remember { MutableInteractionSource() }, null) { }
        .statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            BrandMark(Mood.IDLE, 26.dp)
            Spacer(Modifier.width(10.dp))
            Text("Blaze", modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 20.sp, color = c.ink))
            Label("×", size = 16.sp, modifier = Modifier.clickable(onClick = onClose).padding(8.dp))
        }
        Box(Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(48.dp).background(c.ink).clickable(onClick = onNew),
            contentAlignment = Alignment.Center) { Label("+  New chat", color = c.paper, size = 11.sp) }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            val nav = listOf("chats" to "Chats", "gallery" to "Gallery", "browser" to "Browser", "routines" to "Routines", "memory" to "Memory", "commands" to "Slash commands",
                "usage" to "Usage", "" to "Settings")
            nav.forEach { (id, label) ->
                Row(Modifier.fillMaxWidth().clickable { when (id) { "chats" -> onAllChats(); "gallery" -> { GalleryUi.open.value = true; onClose() }; "browser" -> com.blaze.assistant.browser.BrowserTools.show(ctx); else -> onPage(id) } }.padding(horizontal = 20.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(5.dp).background(c.weak))
                    Spacer(Modifier.width(14.dp))
                    Text(label, style = TextStyle(fontFamily = Type.Display, fontSize = 16.sp, color = c.ink))
                }
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(1.dp).background(c.hair))
            Spacer(Modifier.height(10.dp))
            Box(Modifier.padding(horizontal = 14.dp).fillMaxWidth().hairline(c.hair).padding(horizontal = 12.dp, vertical = 10.dp)) {
                if (q.isEmpty()) Text("Search chats", style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.weak))
                BasicTextField(q, { q = it }, singleLine = true, cursorBrush = SolidColor(c.accent),
                    textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.ink), modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(6.dp))
            Label("Recent", size = 9.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            val shown = (if (q.isBlank()) chats else chats.filter { it.title.contains(q, true) || it.preview.contains(q, true) }).take(40)
            shown.forEach { ch ->
                Row(Modifier.fillMaxWidth().background(if (ch.id == current) c.faint else Color.Transparent)
                    .clickable { onOpenChat(ch.id) }.padding(horizontal = 20.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (ch.pinned) { Box(Modifier.size(5.dp).background(c.accent)); Spacer(Modifier.width(8.dp)) }
                    Text(ch.title.ifBlank { "Untitled" }, maxLines = 1, modifier = Modifier.weight(1f),
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.body))
                    if (ch.id in busy) Label("Working", color = c.accent, size = 9.sp)
                }
            }
            if (shown.isEmpty()) Text("No chats yet", modifier = Modifier.padding(20.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
        }
        Row(Modifier.fillMaxWidth().hairlineTop(c.hair).clickable { onPage("ai") }.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label(Settings.provider.name, size = 9.sp)
                Text(Settings.model, maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.ink))
            }
            Label("Change", color = c.accent, size = 10.sp)
        }
    }
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
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(items, key = { it.key }) { item ->
                when (item) {
                    is Item.Steps -> StepsGroup(item.steps, item.live)
                    is Item.Msg -> Entry(item.m, canAct = mood == Mood.IDLE, onQuote = onQuote,
                        followups = if (item.m.id == lastBlazeId && mood == Mood.IDLE && Settings.followups) item.m.followups else emptyList(),
                        onFollowup = onFollowup)
                }
            }
            item(key = "working") {
                val live by Agent.thinking.collectAsState()
                AnimatedVisibility(mood != Mood.IDLE, enter = fadeIn(), exit = fadeOut()) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                            BrandMark(mood, 22.dp)
                            Spacer(Modifier.width(10.dp))
                            BlinkLabel(if (mood == Mood.ACTING) "Working" else "Thinking")
                        }
                        if (live.isNotBlank() && Settings.showReasoning) ReasoningBlock(live, liveNow = true)
                    }
                }
            }
        }
        AnimatedVisibility(!atBottom, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()) {
            Box(Modifier.size(44.dp).background(c.ink).clickable { scope.launch { follow = true; toEnd(true) } }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(14.dp)) {
                    val u = size.width / 7f
                    // pixel down-arrow
                    for (y in 0..3) drawRect(c.paper, Offset(3 * u, y * u), Size(u, u))
                    listOf(0 to 3, 1 to 4, 2 to 5, 3 to 6, 6 to 3, 5 to 4, 4 to 5).forEach { (x, y) -> drawRect(c.paper, Offset(x * u, y * u), Size(u, u)) }
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
            Box(Modifier.size(6.dp).background(if (live) c.accent else c.weak))
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
        rows.forEachIndexed { y, r -> r.forEachIndexed { x, ch -> if (ch == '#') drawRect(color, Offset(x * u, y * u), Size(u * 0.92f, u * 0.92f)) } }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Entry(m: ChatMessage, canAct: Boolean, onQuote: (String) -> Unit, followups: List<String>, onFollowup: (String) -> Unit) {
    val c = LocalBlaze.current
    when (m.role) {
        Role.USER -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            Label("You", color = c.weak, size = 10.sp)
            Spacer(Modifier.height(6.dp))
            val (quoted, body) = splitQuote(m.text)
            Column(Modifier.widthIn(max = 310.dp).background(c.ink).padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (quoted.isNotEmpty()) {
                    Text(quoted, maxLines = 4, modifier = Modifier.hairlineStart(c.paper.copy(alpha = 0.5f)).padding(start = 10.dp, bottom = 2.dp),
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.paper.copy(alpha = 0.65f)))
                    Spacer(Modifier.height(8.dp))
                }
                SelectionContainer {
                    Text(body, style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, lineHeight = 22.sp, color = c.paper))
                }
            }
            if (m.media.isNotEmpty()) AttachedThumbs(m.media)
            val unpictured = if (m.media.isNotEmpty()) m.files.drop(m.media.size) else m.files
            if (unpictured.isNotEmpty()) Label("Attached: " + unpictured.joinToString(", ") { it.take(24) }, size = 9.sp, modifier = Modifier.padding(top = 4.dp))
            Actions(m, canAct, onQuote, allowQuote = false)
        }
        Role.BLAZE -> Column(Modifier.fillMaxWidth()) {
            Label("Blaze", color = c.accent, size = 10.sp)
            if (m.thinking.isNotBlank() && Settings.showReasoning) ReasoningBlock(m.thinking)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.widthIn(max = 330.dp).background(c.paper).hairline(c.hair).padding(horizontal = 16.dp, vertical = 12.dp)) {
                SelectionContainer {
                    val shownText = remember(m.text, c.accent) { Md.render(m.text, c.accent, c.hair.copy(alpha = 0.35f)) }
                    Text(shownText, style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, lineHeight = 23.sp, color = c.body))
                }
            }
            if (m.media.isNotEmpty()) MediaStrip(m.media)
            Actions(m, canAct, onQuote, allowQuote = true)
            if (followups.isNotEmpty()) Followups(followups, onFollowup)
        }
        Role.ERROR -> Column(Modifier.fillMaxWidth()) {
            Label("Error", color = c.accent, size = 10.sp)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.widthIn(max = 330.dp).background(c.paper).hairline(c.accent)) {
                Box(Modifier.width(3.dp).height(48.dp).background(c.accent))
                SelectionContainer {
                    Text(m.text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 21.sp, color = c.body))
                }
            }
            Actions(m, canAct, onQuote, allowQuote = false)
            if (canAct && m.meta.startsWith("usedup:")) BrainSwitcher(m.meta.removePrefix("usedup:")) { onFollowup("Continue where you stopped") }
            // Pick up where it broke instead of starting over: the history keeps the steps done so far.
            else if (canAct) Followups(listOf("Continue where you stopped"), onFollowup)
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
    val c = LocalBlaze.current
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        list.forEach { s ->
            Row(Modifier.widthIn(max = 330.dp).hairline(c.hair).clickable { onPick(s) }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("↳", style = TextStyle(fontSize = 13.sp, color = c.accent))
                Spacer(Modifier.width(10.dp))
                Text(s, modifier = Modifier.weight(1f, fill = false), style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.ink))
            }
        }
    }
}

/** COPY · QUOTE · RETRY. Long-press words in a reply to copy or quote just those. */
@Composable
private fun Actions(m: ChatMessage, canAct: Boolean, onQuote: (String) -> Unit, allowQuote: Boolean) {
    val c = LocalBlaze.current
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Label("Copy", color = c.sub, size = 10.sp, modifier = Modifier.clickable {
            clip.setText(AnnotatedString(if (m.role == Role.BLAZE) Md.plain(m.text) else m.text)); Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
        }.padding(horizontal = 6.dp, vertical = 8.dp))
        if (allowQuote) {
            Label("·", color = c.weak, size = 10.sp)
            Label("Quote", color = c.sub, size = 10.sp, modifier = Modifier.clickable { onQuote(m.text) }.padding(horizontal = 6.dp, vertical = 8.dp))
        }
        Label("·", color = c.weak, size = 10.sp)
        Label("Retry", color = if (canAct) c.sub else c.weak, size = 10.sp, modifier = Modifier
            .clickable(enabled = canAct) { Agent.retry(m.id) }.padding(horizontal = 6.dp, vertical = 8.dp))
        if (m.meta.isNotBlank()) Label(m.meta, color = if (m.meta.startsWith("⚡")) c.accent else c.weak, size = 9.sp,
            modifier = Modifier.padding(start = 6.dp).weight(1f, fill = false), maxLines = 1)
    }
}

@Composable
private fun QuoteStrip(q: String, onClear: () -> Unit) {
    val c = LocalBlaze.current
    Row(Modifier.fillMaxWidth().background(c.paper).hairlineTop(c.hair).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(34.dp).background(c.accent))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Label("Replying to", color = c.accent, size = 9.sp)
            Text(q, maxLines = 2, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.body))
        }
        Label("×", size = 14.sp, modifier = Modifier.clickable(onClick = onClear).padding(12.dp))
    }
}

@Composable
private fun SuggestionList(list: List<Suggestion>, onPick: (Suggestion) -> Unit) {
    val c = LocalBlaze.current
    Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).background(c.paper).hairlineTop(c.ink).verticalScroll(rememberScrollState())) {
        list.forEach { s ->
            Row(Modifier.fillMaxWidth().clickable { onPick(s) }.hairlineBottom(c.hair).padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(s.title, maxLines = 1, style = TextStyle(fontFamily = Type.Mono, fontSize = 13.sp, color = c.ink))
                Spacer(Modifier.width(12.dp))
                Text(s.sub, maxLines = 1, modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
            }
        }
    }
}

@Composable
private fun InputBar(value: TextFieldValue, onValue: (TextFieldValue) -> Unit, busy: Boolean, voice: Voice.State, hasFiles: Boolean,
                     onCamera: () -> Unit, onPhotos: () -> Unit, onFiles: () -> Unit, onPasteImage: (() -> Unit)?,
                     onMic: () -> Unit, onSend: () -> Unit, onStop: () -> Unit) {
    val c = LocalBlaze.current
    var menu by remember { mutableStateOf(false) }
    fun submit() { if ((value.text.isNotBlank() || hasFiles) && !busy) onSend() }
    Column(Modifier.fillMaxWidth().background(c.paper)) {
        AnimatedVisibility(menu, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Row(Modifier.fillMaxWidth().hairlineTop(c.hair).padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val opts = buildList {
                    add("Camera" to onCamera); add("Photos" to onPhotos); add("Files" to onFiles)
                    onPasteImage?.let { add("Paste" to it) }
                }
                opts.forEach { (label, act) ->
                    Column(Modifier.weight(1f).hairline(c.hair).clickable { menu = false; act() }.padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        AttachGlyph(label, c.ink)
                        Spacer(Modifier.height(6.dp))
                        Label(label, color = c.ink, size = 10.sp)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().hairlineTop(c.hair).padding(14.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.size(50.dp).background(if (menu) c.ink else c.paper).hairline(if (menu) c.ink else c.hair).clickable { menu = !menu },
                contentAlignment = Alignment.Center) {
                Text(if (menu) "×" else "+", style = TextStyle(fontFamily = Type.Display, fontSize = 24.sp, color = if (menu) c.paper else c.ink))
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f).hairline(c.hair).padding(horizontal = 14.dp, vertical = 14.dp)) {
                if (value.text.isEmpty()) Text("Ask Blaze, or type /", style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.weak))
                BasicTextField(value = value, onValueChange = onValue,
                    textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.ink),
                    cursorBrush = SolidColor(c.accent), maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.width(8.dp))
            val micOn = voice != Voice.State.IDLE
            Box(Modifier.size(50.dp).background(if (micOn) c.accent else c.paper).hairline(if (micOn) c.accent else c.hair).clickable(onClick = onMic),
                contentAlignment = Alignment.Center) { MicGlyph(if (micOn) c.paper else c.ink, speaking = voice == Voice.State.SPEAKING) }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.height(50.dp).background(if (busy) c.accent else c.ink).clickable { if (busy) onStop() else submit() }.padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center) { Label(if (busy) "Stop" else "Send", color = c.paper, size = 12.sp) }
        }
    }
}

/** Square-edged icons for the attach menu. */
@Composable
private fun AttachGlyph(kind: String, color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val u = size.width / 10f
        val st = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.4f * u)
        when (kind) {
            "Camera" -> {
                drawRect(color, Offset(0.5f * u, 2.5f * u), Size(9f * u, 6.5f * u), style = st)
                drawRect(color, Offset(3f * u, 1f * u), Size(4f * u, 1.5f * u))
                drawCircle(color, 1.8f * u, Offset(5f * u, 5.8f * u), style = st)
            }
            "Photos" -> {
                drawRect(color, Offset(0.5f * u, 1f * u), Size(9f * u, 8f * u), style = st)
                drawCircle(color, 0.9f * u, Offset(3f * u, 3.5f * u))
                val p = androidx.compose.ui.graphics.Path().apply { moveTo(1f * u, 8.5f * u); lineTo(4f * u, 5f * u); lineTo(6f * u, 7f * u); lineTo(7.5f * u, 5.5f * u); lineTo(9f * u, 8.5f * u); close() }
                drawPath(p, color)
            }
            "Files" -> {
                val p = androidx.compose.ui.graphics.Path().apply { moveTo(0.5f * u, 2f * u); lineTo(4f * u, 2f * u); lineTo(5f * u, 3.2f * u); lineTo(9.5f * u, 3.2f * u); lineTo(9.5f * u, 9f * u); lineTo(0.5f * u, 9f * u); close() }
                drawPath(p, color, style = st)
            }
            else -> {
                drawRect(color, Offset(1.5f * u, 1.5f * u), Size(7f * u, 8f * u), style = st)
                drawRect(color, Offset(3.5f * u, 0.5f * u), Size(3f * u, 2f * u))
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
private fun Welcome(needsSetup: Boolean, onSetup: () -> Unit, onPick: (String) -> Unit) {
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
            Column(Modifier.fillMaxWidth().background(c.ink).clickable(onClick = onSetup).padding(18.dp)) {
                Label("Make me smarter · one tap  →", color = c.paper, size = 12.sp)
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
            Column(Modifier.fillMaxWidth().hairlineTop(c.hair)) {
                items.forEachIndexed { i, s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(s) }.hairlineBottom(c.hair)
                            .padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Label("%02d".format(i + 1), color = c.weak)
                        Spacer(Modifier.width(16.dp))
                        Text(s, modifier = Modifier.weight(1f),
                            style = TextStyle(fontFamily = Type.Display, fontSize = 17.sp, color = c.ink,
                                letterSpacing = (-0.01).em))
                        Text("→", color = c.sub, fontSize = 16.sp)
                    }
                }
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
            Box(Modifier.weight(1f).height(2.dp).background(c.faint)) {
                Box(Modifier.fillMaxWidth(w).height(2.dp).background(c.accent))
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
    Row(Modifier.fillMaxWidth().background(c.paper).hairlineTop(c.hair).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        files.forEach { f ->
            Row(Modifier.hairline(c.hair).clickable { onRemove(f) }.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(f.name.take(18), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.body))
                Spacer(Modifier.width(6.dp)); Label("×", size = 12.sp)
            }
        }
        if (loading) Label("Reading…", color = c.accent, size = 10.sp)
    }
}

/** Square-edged mic drawn on canvas (or a stop square while speaking). */
@Composable
private fun MicGlyph(color: androidx.compose.ui.graphics.Color, speaking: Boolean) {
    androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
        val u = size.width / 10f
        if (speaking) {
            drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(3 * u, 3 * u), size = androidx.compose.ui.geometry.Size(4 * u, 4 * u))
            return@Canvas
        }
        drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(3.5f * u, 0f), size = androidx.compose.ui.geometry.Size(3 * u, 6 * u))
        val st = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2f * u)
        drawArc(color, 0f, 180f, false, topLeft = androidx.compose.ui.geometry.Offset(1.8f * u, 1.5f * u),
            size = androidx.compose.ui.geometry.Size(6.4f * u, 6.4f * u), style = st)
        drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(4.4f * u, 7.9f * u), size = androidx.compose.ui.geometry.Size(1.2f * u, 1.6f * u))
        drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(3f * u, 9f * u), size = androidx.compose.ui.geometry.Size(4f * u, 1f * u))
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
        Row(Modifier.fillMaxWidth().background(c.ink).clickable { open = !open }.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Label("Switch brain and continue", color = c.paper, size = 10.sp, modifier = Modifier.weight(1f))
            Label(if (open) "\u25B2" else "\u25BC", color = c.paper, size = 10.sp)
        }
        if (open) Column(Modifier.fillMaxWidth().hairline(c.hair).padding(10.dp)) {
            // Fastest option: hand it to the free brains.
            Label("Auto \u00b7 free brains", color = c.accent, size = 10.sp, modifier = Modifier.fillMaxWidth().clickable {
                Settings.providerId = P.AUTO; open = false; onPicked() }.padding(vertical = 10.dp))
            if (providers.isNotEmpty()) {
                Label("Provider", size = 9.sp, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    providers.forEach { p ->
                        val on = p.id == pid
                        Box(Modifier.padding(end = 6.dp).background(if (on) c.ink else c.paper).hairline(c.hair).clickable { pid = p.id }
                            .padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Label(p.name.substringBefore(" ("), color = if (on) c.paper else c.ink, size = 9.sp)
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
private val callWords = Regex("""^(call( blaze)?|call me|let'?s talk|voice (call|chat|mode)|talk to me)[.!]?$""", RegexOption.IGNORE_CASE)
