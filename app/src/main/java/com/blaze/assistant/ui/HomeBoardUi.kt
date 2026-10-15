package com.blaze.assistant.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.blaze.assistant.home.Ago
import com.blaze.assistant.home.HomeBoard
import com.blaze.assistant.home.Interests
import com.blaze.assistant.home.StackState
import com.blaze.assistant.home.Stacks
import com.blaze.assistant.home.Widget
import com.blaze.assistant.home.WidgetData
import com.blaze.assistant.home.WidgetIntent
import com.blaze.assistant.home.WidgetLine
import com.blaze.assistant.home.WidgetSize
import com.blaze.assistant.home.WidgetStack
import com.blaze.assistant.home.WidgetTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * v0.29.0 Home board. [HomePane] is the stateful screen (reads HomeBoard's flows); everything under it is stateless so the
 * screenshot tests draw it with sample data. Kit-rounded surfaces only (NoSquareTest), TalkBack labels on every control,
 * start / end paddings (RTL safe), no fixed text heights (big fonts), no remote images (low-RAM phones).
 */

// ---------------------------------------------------------------- looks

/** Accent themes for the board. */
object HomeLook {
    data class Accent(val id: String, val name: String, val light: Color, val dark: Color)
    val accents = listOf(
        Accent("", "Blaze", Color(0xFF1F4BD8), Color(0xFF6B8CFF)),
        Accent("orange", "Orange", Color(0xFFD9480F), Color(0xFFFF9A3C)),
        Accent("green", "Green", Color(0xFF2B8A3E), Color(0xFF69DB7C)),
        Accent("purple", "Purple", Color(0xFF6741D9), Color(0xFFB197FC)),
        Accent("pink", "Pink", Color(0xFFC2255C), Color(0xFFF783AC)),
        Accent("teal", "Teal", Color(0xFF0B7285), Color(0xFF66D9E8)),
    )
    fun accent(id: String, dark: Boolean): Color? = accents.firstOrNull { it.id == id && id.isNotBlank() }?.let { if (dark) it.dark else it.light }

    /** Solid / gradient swatches: (light, dark) pairs; index 0 = the app's own paper. */
    val solids = listOf(Color.Unspecified to Color.Unspecified, Color(0xFFF1F3F8) to Color(0xFF1E2230), Color(0xFFF6F1EA) to Color(0xFF2A241F),
        Color(0xFFEDF5EF) to Color(0xFF1C2620), Color(0xFFF4EEF8) to Color(0xFF261F2C))
    val gradients = listOf(listOf(Color(0xFFE8EEFF), Color(0xFFFFF1E6)) to listOf(Color(0xFF1B2140), Color(0xFF2E2230)),
        listOf(Color(0xFFE6FCF5), Color(0xFFE7F5FF)) to listOf(Color(0xFF14302A), Color(0xFF172A3A)),
        listOf(Color(0xFFFFF0F6), Color(0xFFF3F0FF)) to listOf(Color(0xFF34182A), Color(0xFF211B3A)),
        listOf(Color(0xFFFFF9DB), Color(0xFFFFE8CC)) to listOf(Color(0xFF332B10), Color(0xFF3A2414)))
}

// ---------------------------------------------------------------- the screen

/**
 * The Home tab. [onAsk] hands a plain-words request to Blaze (the main chat runs it with the home_widget_* tools).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomePane(onAsk: (String) -> Unit) {
    val base = LocalBlaze.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val widgets by HomeBoard.widgets.collectAsState()
    val data by HomeBoard.data.collectAsState()
    val loading by HomeBoard.loading.collectAsState()
    val offline by HomeBoard.offline.collectAsState()
    val refreshing by HomeBoard.refreshing.collectAsState()
    var onboarded by remember { mutableStateOf(HomeBoard.onboarded) }
    var stackState by remember { mutableStateOf(StackState()) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var customise by rememberSaveable { mutableStateOf(false) }
    var create by rememberSaveable { mutableStateOf(false) }
    var cityFor by remember { mutableStateOf<String?>(null) }
    var askChange by remember { mutableStateOf<Widget?>(null) }
    var toast by remember { mutableStateOf("") }
    var look by remember { mutableIntStateOf(0) }   // bumps when the look changes
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    LaunchedEffect(toast) { if (toast.isNotBlank()) { delay(3_500); toast = "" } }

    // Coming back from a permission / Usage access screen: retry the cards that asked for it.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) HomeBoard.data.value.forEach { (id, d) -> if (d.action in setOf("usage", "location", "calendar")) HomeBoard.refresh(id) }
        }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val accent = remember(look, base.dark) { HomeLook.accent(HomeBoard.accent, base.dark) }
    val c = if (accent != null) base.copy(accent = accent) else base
    val bgImage = rememberHomeBackground(look)

    fun open(url: String, w: Widget?) {
        if (w != null) { HomeBoard.tapped(w.id); if (w.type == WidgetTypes.CREATORS) HomeBoard.markSeen(w.id) }
        openHomeLink(ctx, url)
    }

    fun action(w: Widget, a: String) {
        when {
            a == "location" -> scope.launch {
                com.blaze.assistant.device.Perms.ensure(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
                HomeBoard.refresh(w.id)
            }
            a == "calendar" -> scope.launch { com.blaze.assistant.device.Perms.ensure(ctx, android.Manifest.permission.READ_CALENDAR); HomeBoard.refresh(w.id) }
            a == "city" -> cityFor = w.id
            a == "usage" -> openUsageAccess(ctx)
            a == "interests" -> { onboarded = false }
            a.startsWith("ask:") -> { onAsk(a.removePrefix("ask:")); toast = "Asked Blaze. The answer is in Chat." }
        }
    }

    CompositionLocalProvider(LocalBlaze provides c) {
        Box(Modifier.fillMaxSize().homeBackground(c, HomeBoard.background, HomeBoard.bgColor, look)) {
            if (bgImage != null && HomeBoard.background == "image") {
                Image(bgImage, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (c.dark) 0.35f else 0.45f })
            }
            if (!onboarded) {
                InterestPicker(onDone = { chosen, subs, typed ->
                    HomeBoard.finishOnboarding(chosen, subs, typed, skipped = false); onboarded = true
                }, onSkip = { HomeBoard.finishOnboarding(emptySet(), emptySet(), "", skipped = true); onboarded = true })
            } else {
                val stacks = remember(widgets) { Stacks.build(widgets) }
                LaunchedEffect(stacks) { stackState = stackState.prune(stacks) }
                PullToRefreshBox(isRefreshing = refreshing, onRefresh = { HomeBoard.pullToRefresh() }, modifier = Modifier.fillMaxSize()) {
                    HomeBoardView(stacks, data, loading, offline, now, stackState,
                        stamp = Ago.of(maxOf(HomeBoard.lastRefresh, data.values.maxOfOrNull { it.fetched } ?: 0L), now),
                        onToggle = { stackState = stackState.toggle(it) },
                        onMenu = { menuFor = it.id },
                        onLine = { w, l -> open(l.url, w) },
                        onOpen = { w, d -> if (d.link.isNotBlank()) open(d.link, w) },
                        onAction = { w, a -> action(w, a) },
                        onVisible = { HomeBoard.visible(it) },
                        onReorder = { ids -> HomeBoard.reorder(ids) },
                        onMove = { id, d -> HomeBoard.move(id, d) },
                        onRemove = { HomeBoard.remove(it) },
                        onCustomise = { customise = true },
                        onCreate = { create = true })
                }
            }
            if (toast.isNotBlank()) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp).navigationBarsPadding()) { HomeToast(toast) }

            menuFor?.let { id ->
                val w = widgets.firstOrNull { it.id == id }
                if (w == null) menuFor = null else {
                    androidx.compose.ui.window.Dialog(onDismissRequest = { menuFor = null }) {
                        WidgetMenuCard(w, onPick = { act ->
                            menuFor = null
                            when (act) {
                                "up" -> HomeBoard.move(w.id, -1)
                                "down" -> HomeBoard.move(w.id, 1)
                                "S", "M", "L" -> HomeBoard.resize(w.id, act)
                                "hide" -> { HomeBoard.setHidden(w.id, true); toast = "Hidden. Show it again in Customise." }
                                "remove" -> { HomeBoard.remove(w.id); toast = "Removed \u201c${w.title}\u201d." }
                                "refresh" -> HomeBoard.refresh(w.id)
                                "ask" -> askChange = w
                            }
                        })
                    }
                }
            }
            if (customise) {
                SheetScrim { customise = false }
                Box(Modifier.align(Alignment.BottomCenter)) {
                    CustomiseSheet(widgets, HomeBoard.background, HomeBoard.bgColor, HomeBoard.accent, HomeBoard.wifiOnly, HomeBoard.refreshHours,
                        onBackground = { HomeBoard.background = it; look++ }, onBgColor = { HomeBoard.bgColor = it; look++ },
                        onPickImage = { /* set below */ }, onAccent = { HomeBoard.accent = it; look++ },
                        onWifiOnly = { HomeBoard.wifiOnly = it; look++ }, onHours = { HomeBoard.refreshHours = it; look++ },
                        onSize = { id, s -> HomeBoard.resize(id, s) }, onHidden = { id, h -> HomeBoard.setHidden(id, h) },
                        onMove = { id, d -> HomeBoard.move(id, d) }, onClose = { customise = false },
                        imagePicker = { BackgroundPicker { look++ } })
                }
                androidx.activity.compose.BackHandler { customise = false }
            }
            if (create) {
                SheetScrim { create = false }
                Box(Modifier.align(Alignment.BottomCenter)) {
                    CreateWidgetSheet(onAdd = { text ->
                        create = false
                        val spec = WidgetIntent.parse(text)
                        if (spec != null) { HomeBoard.add(spec); toast = "Added \u201c${spec.title}\u201d." }
                        else { onAsk("Add a widget to my Home board: $text"); toast = "Asked Blaze. It appears here when it's ready." }
                    }, onClose = { create = false })
                }
                androidx.activity.compose.BackHandler { create = false }
            }
            cityFor?.let { id ->
                var city by remember { mutableStateOf("") }
                androidx.compose.ui.window.Dialog(onDismissRequest = { cityFor = null }) {
                    BlazeDialogCard("Which city?", "I'll show the weather there.", confirm = "Show weather", onConfirm = {
                        if (city.isNotBlank()) HomeBoard.setConfig(id, mapOf("city" to city.trim(), "use_location" to false))
                        cityFor = null
                    }, dismiss = "Cancel", onDismiss = { cityFor = null }) {
                        BlazeTextField(city, { city = it }, "e.g. Lagos, Lima, Manila")
                    }
                }
            }
            askChange?.let { w ->
                var t by remember { mutableStateOf("") }
                androidx.compose.ui.window.Dialog(onDismissRequest = { askChange = null }) {
                    BlazeDialogCard("Change \u201c${w.title}\u201d", "Say it in plain words, e.g. \u201cshow Chelsea instead\u201d or \u201cmake it euros\u201d.", confirm = "Ask Blaze", onConfirm = {
                        if (t.isNotBlank()) { onAsk("Change my Home widget \u201c${w.title}\u201d (id ${w.id}): ${t.trim()}"); toast = "Asked Blaze. The answer is in Chat." }
                        askChange = null
                    }, dismiss = "Cancel", onDismiss = { askChange = null }) {
                        BlazeTextField(t, { t = it }, "What should change?", singleLine = false)
                    }
                }
            }
        }
    }
}

/** The Customise sheet's "Pick image" button with its own photo picker (only in the live app, not in screenshots). */
@Composable
private fun BackgroundPicker(onPicked: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { saveBackground(ctx, uri) }
            if (ok) { HomeBoard.background = "image"; onPicked() }
        }
    }
    KitButton("Pick image", Btn.LINE) { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

private fun bgFile(ctx: Context) = java.io.File(ctx.filesDir, "home_bg.jpg")

/** Copies the picked picture into app storage, scaled down to ≤ 1080 px on its long side (small and fast on any phone). */
private fun saveBackground(ctx: Context, uri: Uri): Boolean = try {
    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
    var sample = 1
    while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= 1080) sample *= 2
    val bmp = ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) }
    if (bmp == null) false else {
        bgFile(ctx).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it) }
        bmp.recycle(); true
    }
} catch (_: Throwable) { false }

@Composable
private fun rememberHomeBackground(look: Int): ImageBitmap? {
    val ctx = LocalContext.current
    var img by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(look) {
        img = if (HomeBoard.background != "image") null else withContext(Dispatchers.IO) {
            try {
                val f = bgFile(ctx)
                if (!f.isFile) null else android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 1 })?.asImageBitmap()
            } catch (_: Throwable) { null }
        }
    }
    return img
}

private fun Modifier.homeBackground(c: BlazeColors, kind: String, idx: Int, @Suppress("UNUSED_PARAMETER") look: Int): Modifier = when (kind) {
    "gradient" -> HomeLook.gradients.getOrNull(idx)?.let { (l, d) -> background(Brush.verticalGradient(if (c.dark) d else l)) } ?: background(c.paper)
    else -> HomeLook.solids.getOrNull(idx)?.let { (l, d) -> val col = if (c.dark) d else l; background(if (col == Color.Unspecified) c.paper else col) } ?: background(c.paper)
}

fun openHomeLink(ctx: Context, url: String) {
    try {
        when {
            url.startsWith("app:") -> ctx.packageManager.getLaunchIntentForPackage(url.removePrefix("app:"))?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            url.startsWith("calendar:") -> {
                val t = url.removePrefix("calendar:").toLongOrNull() ?: return
                val u = android.provider.CalendarContract.CONTENT_URI.buildUpon().appendPath("time").also { android.content.ContentUris.appendId(it, t) }.build()
                ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            url.startsWith("https://") || url.startsWith("http://") -> ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    } catch (_: Exception) { }
}

private fun openUsageAccess(ctx: Context) {
    try { ctx.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (_: Exception) { try { ctx.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { } }
}

// ---------------------------------------------------------------- the board (stateless)

private const val PAGE = 12

/**
 * The scrolling board: header (title, "Updated … ago", Customise, Create), then one item per category stack. Items are
 * lazy (only cards on screen are composed and fetched) and paged in [PAGE] at a time as you scroll (infinite scroll).
 * Drag a card's grip to reorder whole stacks; the long-press menu moves single widgets.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeBoardView(
    stacks: List<WidgetStack>, data: Map<String, WidgetData>, loading: Set<String>, offline: Boolean, now: Long, stackState: StackState,
    stamp: String, onToggle: (String) -> Unit, onMenu: (Widget) -> Unit, onLine: (Widget, WidgetLine) -> Unit, onOpen: (Widget, WidgetData) -> Unit,
    onAction: (Widget, String) -> Unit, onVisible: (String) -> Unit, onReorder: (List<String>) -> Unit, onMove: (String, Int) -> Unit,
    onRemove: (String) -> Unit, onCustomise: () -> Unit, onCreate: () -> Unit, listState: LazyListState = rememberLazyListState(),
) {
    val c = LocalBlaze.current
    val order = remember { mutableStateListOf<WidgetStack>() }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(stacks) { if (dragKey == null) { order.clear(); order.addAll(stacks) } }
    if (order.isEmpty() && stacks.isNotEmpty() && dragKey == null) { order.addAll(stacks) }
    var shown by remember { mutableIntStateOf(PAGE) }
    LaunchedEffect(listState, order.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last -> if (last >= shown - 1 && shown < order.size) shown += PAGE }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "head") { BoardHeader(stamp, offline, onCustomise, onCreate) }
        if (order.isEmpty()) item(key = "empty") {
            EmptyState("Your board is empty", "Add a widget in plain words, like \u201ctrack Bitcoin\u201d or \u201cArsenal scores\u201d.", action = "Create a widget", onAction = onCreate)
        }
        itemsIndexed(order.take(shown), key = { _, s -> s.key }) { i, s ->
            val dragging = dragKey == s.key
            Box(Modifier.zIndex(if (dragging) 1f else 0f).graphicsLayer {
                translationY = if (dragging) dragY else 0f
                if (dragging) { scaleX = 1.02f; scaleY = 1.02f; shadowElevation = 12f }
            }.let { if (dragging || !MotionTokens.enabled) it else it.animateItemPlacement(MotionTokens.drag()) }) {
                val grip = Modifier.pointerInput(s.key) {
                    detectVerticalDragGestures(
                        onDragStart = { dragKey = s.key; dragY = 0f },
                        onVerticalDrag = { ch, dy ->
                            ch.consume(); dragY += dy
                            val info = listState.layoutInfo.visibleItemsInfo
                            val me = info.firstOrNull { it.key == s.key } ?: return@detectVerticalDragGestures
                            val mid = me.offset + dragY + me.size / 2f
                            val target = info.firstOrNull { it.key != s.key && it.key != "head" && it.key != "empty" && mid.toInt() in it.offset..(it.offset + it.size) }
                            if (target != null) {
                                val from = order.indexOfFirst { it.key == s.key }; val to = order.indexOfFirst { it.key == target.key }
                                if (from >= 0 && to >= 0) { order.add(to, order.removeAt(from)); dragY += (me.offset - target.offset).toFloat() }
                            }
                        },
                        onDragEnd = { dragKey = null; dragY = 0f; onReorder(order.flatMap { st -> st.widgets.map { it.id } }) },
                        onDragCancel = { dragKey = null; dragY = 0f })
                }
                StackItem(s, stackState.isOpen(s.key), data, loading, offline, now, grip,
                    onToggle = { onToggle(s.key) }, onMenu = onMenu, onLine = onLine, onOpen = onOpen, onAction = onAction, onVisible = onVisible,
                    canUp = i > 0, canDown = i < order.size - 1, onMove = onMove, onRemove = onRemove)
            }
        }
        if (shown < order.size) item(key = "more") {
            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { Label("Loading more\u2026", size = 11.sp) }
        }
    }
}

@Composable
private fun BoardHeader(stamp: String, offline: Boolean, onCustomise: () -> Unit, onCreate: () -> Unit) {
    val c = LocalBlaze.current
    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Home", modifier = Modifier.semantics { heading() },
                    style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, color = c.ink))
                Text(if (offline) "Offline \u00b7 showing saved cards" else stamp, maxLines = 2,
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 12.5.sp, color = c.sub))
            }
            RoundIcon(Icons.Rounded.Tune, "Customise board", onCustomise)
            Spacer(Modifier.width(6.dp))
            RoundIcon(Icons.Rounded.Add, "Create a widget", onCreate, filled = true)
        }
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit, filled: Boolean = false, size: Int = 40) {
    val c = LocalBlaze.current
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = desc, role = Role.Button, onClick = onClick).semantics { contentDescription = desc },
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(size.dp).clip(CircleShape).background(if (filled) c.accent else c.raised).border(1.dp, if (filled) c.accent else c.hair2, CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = if (filled) c.onAccent else c.ink, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun HomeToast(text: String) {
    Box(Modifier.widthIn(max = 340.dp).clip(BlazeShapes.pill).background(DialogColors.surface).padding(horizontal = 18.dp, vertical = 11.dp)) {
        Text(text, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = DialogColors.title))
    }
}

/** Spring for the stack (none with Remove animations). */
private fun <T> stackSpring() = if (MotionTokens.enabled) spring<T>(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow) else androidx.compose.animation.core.snap()

/**
 * One category stack. Collapsed: the main card with up to two cards peeking underneath (offset, scaled, dimmed) and a
 * "+N" chip; a tap expands it. Expanded: the other cards slide out below; tapping the main card or "Show less" folds it.
 */
@Composable
fun StackItem(
    s: WidgetStack, open: Boolean, data: Map<String, WidgetData>, loading: Set<String>, offline: Boolean, now: Long, grip: Modifier,
    onToggle: () -> Unit, onMenu: (Widget) -> Unit, onLine: (Widget, WidgetLine) -> Unit, onOpen: (Widget, WidgetData) -> Unit,
    onAction: (Widget, String) -> Unit, onVisible: (String) -> Unit, canUp: Boolean = true, canDown: Boolean = true,
    onMove: (String, Int) -> Unit = { _, _ -> }, onRemove: (String) -> Unit = {},
) {
    val c = LocalBlaze.current
    LaunchedEffect(s.main.id) { onVisible(s.main.id) }
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            if (s.stacked && !open) {
                StackState.peeks(s.widgets.size).asReversed().forEach { p ->
                    Box(Modifier.matchParentSize().graphicsLayer {
                        translationY = p.offsetDp.dp.toPx() * 1.6f; scaleX = p.scale; alpha = p.alpha
                    }.clip(BlazeShapes.large).background(c.raised).border(1.dp, c.hair2, BlazeShapes.large))
                }
            }
            WidgetCard(s.main, data[s.main.id], s.main.id in loading, offline, now, grip,
                stackCount = if (s.stacked) s.widgets.size else 0, stackOpen = open,
                onClick = { if (s.stacked) onToggle() else data[s.main.id]?.let { onOpen(s.main, it) } },
                onMenu = { onMenu(s.main) }, onLine = { onLine(s.main, it) }, onAction = { onAction(s.main, it) },
                canUp = canUp, canDown = canDown, onMove = onMove, onRemove = onRemove)
        }
        if (s.stacked && !open) Spacer(Modifier.height(26.dp))
        AnimatedVisibility(s.stacked && open,
            enter = if (MotionTokens.enabled) expandVertically(stackSpring()) + fadeIn(stackSpring()) + slideInVertically(stackSpring()) { -it / 4 } else EnterTransition.None,
            exit = if (MotionTokens.enabled) shrinkVertically(stackSpring()) + fadeOut(stackSpring()) + slideOutVertically(stackSpring()) { -it / 4 } else ExitTransition.None) {
            Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                s.rest.forEach { w ->
                    LaunchedEffect(w.id) { onVisible(w.id) }
                    WidgetCard(w, data[w.id], w.id in loading, offline, now, Modifier, onClick = { data[w.id]?.let { onOpen(w, it) } },
                        onMenu = { onMenu(w) }, onLine = { onLine(w, it) }, onAction = { onAction(w, it) }, onMove = onMove, onRemove = onRemove)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Box(Modifier.clip(BlazeShapes.pill).background(c.raised).border(1.dp, c.hair2, BlazeShapes.pill)
                        .clickable(onClickLabel = "Fold the ${s.category} stack", onClick = onToggle).heightIn(min = 40.dp).padding(horizontal = 16.dp, vertical = 9.dp),
                        contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.KeyboardArrowUp, null, tint = c.ink, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Show less", style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = c.ink))
                        }
                    }
                }
            }
        }
    }
}

/** A type's small mark colour (on the accent). */
private fun typeGlyph(type: String): String = when (type) {
    WidgetTypes.WEATHER -> "\u2600"; WidgetTypes.NEWS -> "\u25A4"; WidgetTypes.CREATORS -> "\u25B6"; WidgetTypes.SPORTS -> "\u26BD"
    WidgetTypes.PRICES -> "\u2197"; WidgetTypes.CALENDAR -> "\u25F7"; WidgetTypes.ROUTINES -> "\u21BB"; WidgetTypes.DEADLINES -> "\u2691"
    WidgetTypes.GITHUB -> "\u2325"; WidgetTypes.APPS -> "\u25A6"; WidgetTypes.INTO -> "\u2726"; else -> "\u270E"
}

/** One widget card. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WidgetCard(
    w: Widget, d: WidgetData?, loading: Boolean, offline: Boolean, now: Long, grip: Modifier,
    stackCount: Int = 0, stackOpen: Boolean = false,
    onClick: () -> Unit, onMenu: () -> Unit, onLine: (WidgetLine) -> Unit, onAction: (String) -> Unit,
    canUp: Boolean = true, canDown: Boolean = true, onMove: (String, Int) -> Unit = { _, _ -> }, onRemove: (String) -> Unit = {},
) {
    val c = LocalBlaze.current
    val size = WidgetSize.norm(w.size)
    val stale = d != null && Ago.stale(d.fetched, now, offline, d.error)
    val summary = buildString {
        append(w.title); append(", "); append(WidgetTypes.label(w.type)); append(" widget")
        if (d != null) { if (d.value.isNotBlank()) append(", ").append(d.value); if (d.change.isNotBlank()) append(", ").append(d.change); if (d.error.isNotBlank()) append(". ").append(d.error) }
        if (stackCount > 1) append(". Stack of $stackCount, ").append(if (stackOpen) "expanded" else "collapsed")
    }
    Column(Modifier.fillMaxWidth().clip(BlazeShapes.large).background(c.raised).border(1.dp, c.hair2, BlazeShapes.large)
        .combinedClickable(onClickLabel = if (stackCount > 1) (if (stackOpen) "Fold the stack" else "Expand the stack") else "Open",
            onLongClickLabel = "Widget options", onClick = onClick, onLongClick = onMenu)
        .semantics {
            contentDescription = summary
            customActions = listOfNotNull(
                CustomAccessibilityAction("Widget options") { onMenu(); true },
                if (canUp) CustomAccessibilityAction("Move up") { onMove(w.id, -1); true } else null,
                if (canDown) CustomAccessibilityAction("Move down") { onMove(w.id, 1); true } else null,
                CustomAccessibilityAction("Remove") { onRemove(w.id); true })
        }
        .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
                Text(typeGlyph(w.type), style = TextStyle(fontSize = 12.sp, color = c.accent))
            }
            Spacer(Modifier.width(8.dp))
            Text(w.title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = c.ink))
            if (stackCount > 1 && !stackOpen) { Tag("+${stackCount - 1}", on = true); Spacer(Modifier.width(2.dp)) }
            if (stale) { Tag("Stale"); Spacer(Modifier.width(2.dp)) }
            Box(grip.size(36.dp).clip(CircleShape).semantics { contentDescription = "Drag to reorder" }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.DragIndicator, null, tint = c.weak, modifier = Modifier.size(18.dp))
            }
            Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClickLabel = "Widget options", onClick = onMenu).semantics { contentDescription = "Options for ${w.title}" },
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MoreHoriz, null, tint = c.sub, modifier = Modifier.size(20.dp))
            }
        }
        Column(Modifier.padding(end = 8.dp, top = 4.dp)) {
            when {
                d == null || (d.empty && d.error.isBlank() && loading) -> {
                    Box(Modifier.fillMaxWidth(0.55f).height(22.dp).shimmer(c.faint, c.hair))
                    if (size != WidgetSize.S) { Spacer(Modifier.height(8.dp)); Box(Modifier.fillMaxWidth(0.85f).height(12.dp).shimmer(c.faint, c.hair)) }
                    if (d == null && !loading) Label("Not updated yet", size = 11.sp, modifier = Modifier.padding(top = 6.dp))
                }
                else -> WidgetBody(w, d, size, onLine)
            }
            if (d != null && d.error.isNotBlank()) {
                Text(d.error, modifier = Modifier.padding(top = 6.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.5.sp, lineHeight = 17.sp, color = c.sub))
            }
            if (d != null && d.action.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                val label = when {
                    d.action == "location" -> "Allow location"
                    d.action == "city" -> "Type a city"
                    d.action == "calendar" -> "Allow calendar"
                    d.action == "usage" -> "Open Usage access"
                    d.action == "interests" -> "Pick my interests"
                    d.action.startsWith("ask:") -> "Ask Blaze"
                    else -> ""
                }
                if (label.isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KitButton(label, Btn.LINE) { onAction(d.action) }
                    if (d.action == "location") KitButton("Type a city", Btn.GHOST) { onAction("city") }
                }
            }
            if (d != null && d.fetched > 0L && size != WidgetSize.S) {
                Text(if (loading) "Updating\u2026" else Ago.of(d.fetched, now), modifier = Modifier.padding(top = 8.dp),
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 11.sp, color = c.weak))
            } else if (loading && d != null && !d.empty) Text("Updating\u2026", modifier = Modifier.padding(top = 6.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 11.sp, color = c.weak))
        }
    }
}

@Composable
private fun WidgetBody(w: Widget, d: WidgetData, size: String, onLine: (WidgetLine) -> Unit) {
    val c = LocalBlaze.current
    if (d.value.isNotBlank() || d.headline.isNotBlank()) {
        if (d.value.isNotBlank()) {
            Text(d.value, maxLines = if (size == WidgetSize.S) 1 else 2, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.SemiBold, fontSize = if (w.type == WidgetTypes.WEATHER || w.type == WidgetTypes.PRICES) 30.sp else 20.sp,
                    lineHeight = if (w.type == WidgetTypes.WEATHER || w.type == WidgetTypes.PRICES) 34.sp else 25.sp, color = c.ink))
            if (d.headline.isNotBlank() && d.headline != w.title) Text(d.headline, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Sans, fontSize = 12.5.sp, color = c.sub))
        } else if (d.headline != w.title && size != WidgetSize.S) {
            Text(d.headline, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.ink))
        }
    }
    if (d.change.isNotBlank()) {
        val up = d.change.startsWith("\u25B2"); val down = d.change.startsWith("\u25BC")
        Text(d.change, maxLines = if (size == WidgetSize.L) 6 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 12.5.sp, lineHeight = 17.sp,
                color = if (up) Color(0xFF2B8A3E).let { if (c.dark) Color(0xFF69DB7C) else it } else if (down) (if (c.dark) Color(0xFFFF8787) else Color(0xFFC92A2A)) else c.sub))
    }
    val n = WidgetSize.lines(size).let { if (size == WidgetSize.S) 0 else it }
    if (n > 0 && d.lines.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        d.lines.take(n).forEachIndexed { i, l ->
            Row(Modifier.fillMaxWidth().clip(BlazeShapes.small).let { if (l.url.isNotBlank()) it.clickable(onClickLabel = "Open ${l.title}") { onLine(l) } else it }
                .let { if (i > 0) it.hairlineTopInset(c.hair, 0.dp) else it }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(l.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.5.sp, lineHeight = 18.sp, color = c.ink))
                    if (l.sub.isNotBlank()) Text(l.sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontSize = 11.5.sp, color = c.sub))
                }
                if (l.note.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    if (l.note == "New" || l.note == "Next") Tag(l.note, on = true)
                    else Text(l.note, maxLines = 2, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 12.5.sp, color = c.body))
                }
            }
        }
        if (d.lines.size > n) Label("+${d.lines.size - n} more", size = 10.5.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

// ---------------------------------------------------------------- long-press menu

/** Move up / down, Resize S M L, Hide, Remove, Refresh, Ask Blaze to change. */
@Composable
fun WidgetMenuCard(w: Widget, onPick: (String) -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(Modifier.widthIn(min = 260.dp, max = 340.dp).clip(shape).background(DialogColors.surface).border(1.dp, DialogColors.border, shape).padding(vertical = 8.dp)) {
        Text(w.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 6.dp).semantics { heading() },
            style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = DialogColors.title))
        @Composable fun item(label: String, id: String, warm: Boolean = false) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(BlazeShapes.small).clickable(onClickLabel = label) { onPick(id) }
                .heightIn(min = 46.dp).padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                Text(label, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.5.sp, color = if (warm) DialogColors.primaryWarm else DialogColors.title))
            }
        }
        item("Move up", "up"); item("Move down", "down")
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Resize", modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Sans, fontSize = 14.5.sp, color = DialogColors.title))
            listOf("S", "M", "L").forEach { s ->
                val on = WidgetSize.norm(w.size) == s
                Box(Modifier.padding(start = 6.dp).size(40.dp).clip(CircleShape).background(if (on) DialogColors.primary else Color.Transparent)
                    .border(1.dp, DialogColors.border, CircleShape).clickable(onClickLabel = WidgetSize.label(s)) { onPick(s) }
                    .semantics { contentDescription = WidgetSize.label(s); selected = on }, contentAlignment = Alignment.Center) {
                    Text(s, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = DialogColors.title))
                }
            }
        }
        item("Refresh", "refresh"); item("Ask Blaze to change", "ask"); item("Hide", "hide"); item("Remove", "remove", warm = true)
    }
}

// ---------------------------------------------------------------- customise sheet

@Composable
fun CustomiseSheet(
    widgets: List<Widget>, background: String, bgColor: Int, accent: String, wifiOnly: Boolean, hours: Int,
    onBackground: (String) -> Unit, onBgColor: (Int) -> Unit, onPickImage: () -> Unit, onAccent: (String) -> Unit,
    onWifiOnly: (Boolean) -> Unit, onHours: (Int) -> Unit, onSize: (String, String) -> Unit, onHidden: (String, Boolean) -> Unit,
    onMove: (String, Int) -> Unit, onClose: () -> Unit, imagePicker: (@Composable () -> Unit)? = null,
) {
    val c = LocalBlaze.current
    var bg by remember(background) { mutableStateOf(background) }
    var bgc by remember(bgColor) { mutableIntStateOf(bgColor) }
    var acc by remember(accent) { mutableStateOf(accent) }
    var wifi by remember(wifiOnly) { mutableStateOf(wifiOnly) }
    var hrs by remember(hours) { mutableIntStateOf(hours) }
    Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(c.raised)
        .navigationBarsPadding().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Customise", modifier = Modifier.weight(1f).semantics { heading() }, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = c.ink))
            KitButton("Done", Btn.LINE, onClick = onClose)
        }
        GroupHead("Background")
        Segmented(listOf("plain" to "Solid", "gradient" to "Gradient", "image" to "Image"), bg) { bg = it; onBackground(it) }
        Spacer(Modifier.height(10.dp))
        when (bg) {
            "image" -> Row(verticalAlignment = Alignment.CenterVertically) {
                if (imagePicker != null) imagePicker() else KitButton("Pick image", Btn.LINE, onClick = onPickImage)
                Spacer(Modifier.width(10.dp)); Label("Shown softly behind your cards.", size = 11.sp)
            }
            "gradient" -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeLook.gradients.forEachIndexed { i, (l, d) -> Swatch(Brush.linearGradient(if (c.dark) d else l), bgc == i, "Gradient ${i + 1}") { bgc = i; onBgColor(i) } }
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeLook.solids.forEachIndexed { i, (l, d) -> val col = if (c.dark) d else l
                    Swatch(androidx.compose.ui.graphics.SolidColor(if (col == Color.Unspecified) c.paper else col), bgc == i, if (i == 0) "Default" else "Colour $i") { bgc = i; onBgColor(i) } }
            }
        }
        GroupHead("Accent", modifier = Modifier.padding(top = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeLook.accents.forEach { a -> Swatch(androidx.compose.ui.graphics.SolidColor(if (c.dark) a.dark else a.light), acc == a.id, a.name) { acc = a.id; onAccent(a.id) } }
        }
        GroupHead("Refresh", "Every few hours in the background, never more than once an hour.", modifier = Modifier.padding(top = 12.dp))
        Segmented(listOf("1" to "1 h", "3" to "3 h", "6" to "6 h", "12" to "12 h"), hrs.toString()) { hrs = it.toInt(); onHours(it.toInt()) }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Wi-Fi only", style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.ink))
                Text("Saves mobile data. Pull down on the board to refresh anytime.", style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
            }
            Box(Modifier.semantics { contentDescription = "Wi-Fi only"; stateDescription = if (wifi) "On" else "Off" }) { KitSwitch(wifi) { wifi = !wifi; onWifiOnly(wifi) } }
        }
        GroupHead("Widgets", "Order, size and what's shown.", modifier = Modifier.padding(top = 12.dp))
        if (widgets.isEmpty()) Label("No widgets yet.", size = 12.sp)
        widgets.forEachIndexed { i, w ->
            Row(Modifier.fillMaxWidth().let { if (i > 0) it.hairlineTopInset(c.hair, 0.dp) else it }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(w.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = if (w.hidden) c.weak else c.ink))
                    Text(w.category.ifBlank { WidgetTypes.label(w.type) }, maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontSize = 11.5.sp, color = c.sub))
                }
                SizeChips(w.size) { onSize(w.id, it) }
                SmallIcon(Icons.Rounded.KeyboardArrowUp, "Move ${w.title} up", enabled = i > 0) { onMove(w.id, -1) }
                SmallIcon(Icons.Rounded.KeyboardArrowDown, "Move ${w.title} down", enabled = i < widgets.size - 1) { onMove(w.id, 1) }
                Box(Modifier.semantics { contentDescription = "Show ${w.title}"; stateDescription = if (w.hidden) "Hidden" else "Shown" }) { KitSwitch(!w.hidden) { onHidden(w.id, !w.hidden) } }
            }
        }
    }
}

@Composable
private fun SizeChips(size: String, onPick: (String) -> Unit) {
    val c = LocalBlaze.current
    Row {
        listOf("S", "M", "L").forEach { s ->
            val on = WidgetSize.norm(size) == s
            Box(Modifier.size(32.dp).clip(CircleShape).background(if (on) c.ink else Color.Transparent).clickable(onClickLabel = WidgetSize.label(s)) { onPick(s) }
                .semantics { contentDescription = WidgetSize.label(s); selected = on }, contentAlignment = Alignment.Center) {
                Text(s, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, color = if (on) c.paper else c.sub))
            }
        }
    }
}

@Composable
private fun SmallIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, enabled: Boolean, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Box(Modifier.size(36.dp).clip(CircleShape).clickable(enabled = enabled, onClickLabel = desc, onClick = onClick).semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (enabled) c.ink else c.weak, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Swatch(brush: Brush, on: Boolean, desc: String, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Box(Modifier.size(40.dp).clip(CircleShape).border(if (on) 2.dp else 1.dp, if (on) c.ink else c.hair2, CircleShape).padding(4.dp).clip(CircleShape).background(brush)
        .clickable(onClickLabel = desc, onClick = onClick).semantics { contentDescription = desc; selected = on })
}

// ---------------------------------------------------------------- create a widget

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateWidgetSheet(onAdd: (String) -> Unit, onClose: () -> Unit, initial: String = "") {
    val c = LocalBlaze.current
    var t by remember { mutableStateOf(initial) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(c.raised).navigationBarsPadding()
        .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 18.dp)) {
        Text("Create a widget", modifier = Modifier.semantics { heading() }, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = c.ink))
        Text("Say what you want to see, in plain words.", style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
        Spacer(Modifier.height(12.dp))
        BlazeTextField(t, { t = it }, "e.g. show me Arsenal scores")
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Show me Arsenal scores", "Track Bitcoin price", "New videos from MKBHD", "Weather in Tokyo", "USD to EUR", "Tech news").forEach { ex ->
                HomeChip(ex, false) { t = ex }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            KitButton("Cancel", Btn.GHOST, onClick = onClose)
            KitButton("Add to Home", Btn.ACCENT, enabled = t.isNotBlank()) { onAdd(t.trim()) }
        }
    }
}

@Composable
fun HomeChip(label: String, on: Boolean, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Box(Modifier.clip(BlazeShapes.pill).background(if (on) c.ink else c.raised).border(1.dp, if (on) c.ink else c.hair2, BlazeShapes.pill)
        .clickable(onClickLabel = label, role = Role.Checkbox, onClick = onClick).semantics { selected = on }
        .heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
        Text(label, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 13.5.sp, color = if (on) c.paper else c.ink))
    }
}

// ---------------------------------------------------------------- interest picker (first open)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InterestPicker(onDone: (Set<String>, Set<String>, String) -> Unit, onSkip: () -> Unit, initialChosen: Set<String> = emptySet(), initialSubs: Set<String> = emptySet()) {
    val c = LocalBlaze.current
    var chosen by remember { mutableStateOf(initialChosen) }
    var subs by remember { mutableStateOf(initialSubs) }
    var typed by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            PageHead("Your Home board", "What are you into?", "Pick a few and I'll fill your board with live cards. You can change everything later.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Interests.catalogue.forEach { ch ->
                    HomeChip(ch.label, ch.id in chosen) {
                        chosen = if (ch.id in chosen) chosen - ch.id else chosen + ch.id
                        if (ch.id !in chosen) subs = subs - ch.subs.toSet()
                    }
                }
            }
            Interests.catalogue.filter { it.id in chosen && it.subs.isNotEmpty() }.forEach { ch ->
                GroupHead(ch.label, modifier = Modifier.padding(top = 14.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ch.subs.forEach { s -> HomeChip(s, s in subs) { subs = if (s in subs) subs - s else subs + s } }
                }
            }
            GroupHead("Anything else?", "Optional. A team, a creator, a coin, a topic\u2026", modifier = Modifier.padding(top = 14.dp))
            BlazeTextField(typed, { typed = it }, "e.g. Kaizer Chiefs, Veritasium, Formula 1")
            Spacer(Modifier.height(16.dp))
        }
        Row(Modifier.fillMaxWidth().background(c.paper).padding(horizontal = 18.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            KitButton("Skip", Btn.GHOST, onClick = onSkip)
            Spacer(Modifier.weight(1f))
            KitButton("Set up my board", Btn.ACCENT, enabled = chosen.isNotEmpty() || subs.isNotEmpty() || typed.isNotBlank()) { onDone(chosen, subs, typed) }
        }
    }
}
