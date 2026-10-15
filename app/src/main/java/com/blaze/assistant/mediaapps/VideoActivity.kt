package com.blaze.assistant.mediaapps

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.blaze.assistant.mediaapps.store.StoreDetail
import com.blaze.assistant.mediaapps.store.StoreItem
import com.blaze.assistant.mediaapps.store.StoreSource
import com.blaze.assistant.ui.BackButton
import com.blaze.assistant.ui.BlazeBrush
import com.blaze.assistant.ui.BlazeDialog
import com.blaze.assistant.ui.BlazeMenu
import com.blaze.assistant.ui.BlazeShapes
import com.blaze.assistant.ui.BlazeTheme
import com.blaze.assistant.ui.DialogColors
import com.blaze.assistant.ui.EmptyState
import com.blaze.assistant.ui.KitSearch
import com.blaze.assistant.ui.LocalBlaze
import com.blaze.assistant.ui.MotionTokens
import com.blaze.assistant.ui.PillButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.30.1 Blaze Video: its own full-screen app inside Blaze with the Player | Library | Store pill. ExoPlayer with
 * swipe gestures (left/right = seek, left edge up/down = brightness, right edge = volume, double-tap ±10 s),
 * fullscreen with rotation, picture-in-picture, speed, subtitle and audio-track choice, and resume where you left off.
 */
class VideoActivity : ComponentActivity() {
    companion object {
        fun open(ctx: Context, tab: String = "") = ctx.startActivity(Intent(ctx, VideoActivity::class.java)
            .putExtra(MusicActivity.EXTRA_TAB, tab).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    lateinit var player: ExoPlayer
    private val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
    val tab = mutableStateOf("library")
    val pip = mutableStateOf(false)
    val fullscreen = mutableStateOf(false)
    /** The video loaded now (library track, or a Store stream). */
    val current = mutableStateOf<Track?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intent?.getStringExtra(MusicActivity.EXTRA_TAB)?.takeIf { it.isNotBlank() }?.let { tab.value = it }
        com.blaze.assistant.mediaapps.store.JamendoSource.init(this)
        lifecycleScope.launch(Dispatchers.IO) { MediaLib.ensure(this@VideoActivity); Downloads.resumePending(this@VideoActivity) }
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000)
            .build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); savePos() }
                if (Build.VERSION.SDK_INT >= 26) runCatching { setPictureInPictureParams(pipParams()) }
            }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) current.value?.let { MediaLib.savePosition(it.key, 0, 1) } }
        })
        StoragePermission.asker = null
        setContent { BlazeTheme { VideoApp(this) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(MusicActivity.EXTRA_TAB)?.takeIf { it.isNotBlank() }?.let { tab.value = it }
    }

    fun savePos() {
        val t = current.value ?: return
        if (t.source == "stream") return
        MediaLib.savePosition(t.key, player.currentPosition, player.duration.coerceAtLeast(0))
    }

    /** Plays a library video from where it was left. */
    fun play(t: Track) {
        savePos()
        current.value = t
        MediaLib.played(t)
        player.setMediaItem(MediaItem.Builder().setMediaId(t.key).setUri(t.uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).build()).build(), ResumeLogic.resumeAt(MediaLib.position(t.key), t.durationMs))
        player.prepare(); player.play()
        tab.value = "player"
    }

    /** Watch a Store result without downloading: one progressive file, or video + audio merged while playing. */
    fun watch(item: StoreItem, d: StoreDetail, src: StoreSource) {
        val picks = src.streamPick("video", d)
        if (picks.isEmpty()) return
        savePos()
        http.setUserAgent(src.userAgent)
        val t = Track("stream:${item.source}:${item.id}", picks[0].url, d.title.ifBlank { item.title }, d.author.ifBlank { item.author }, "",
            d.lengthSec * 1000L, item.thumb, "stream", 0, item.id, "video", d.licence.ifBlank { item.licence }, item.source)
        current.value = t
        val factory = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(this, http))
        if (picks.size == 1) player.setMediaItem(MediaItem.Builder().setMediaId(t.key).setUri(picks[0].url).build())
        else player.setMediaSource(MergingMediaSource(*picks.map { factory.createMediaSource(MediaItem.fromUri(it.url)) }.toTypedArray()))
        player.prepare(); player.play()
        tab.value = "player"
    }

    fun pipParams(): PictureInPictureParams {
        val vs = player.videoSize
        val r = if (vs.width > 0 && vs.height > 0) Rational(vs.width, vs.height).let { if (it.toFloat() > 2.39f) Rational(239, 100) else if (it.toFloat() < 0.42f) Rational(42, 100) else it } else Rational(16, 9)
        val b = PictureInPictureParams.Builder().setAspectRatio(r)
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(player.isPlaying && tab.value == "player").setSeamlessResizeEnabled(true)
        return b.build()
    }

    fun enterPip() {
        if (Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE))
            runCatching { enterPictureInPictureMode(pipParams()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 8–11: go to picture-in-picture by hand when leaving a playing video (12+ does it via auto-enter).
        if (Build.VERSION.SDK_INT in 26..30 && player.isPlaying && tab.value == "player") enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pip.value = isInPictureInPictureMode
    }

    fun setFullscreen(on: Boolean) {
        fullscreen.value = on
        val w = window
        val ctl = WindowCompat.getInsetsController(w, w.decorView)
        if (on) {
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
            val vs = player.videoSize
            requestedOrientation = if (vs.height > vs.width) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ctl.show(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onStop() {
        super.onStop()
        savePos()
        // Leaving the app (not into picture-in-picture): pause, like any video app.
        if (!pip.value) player.pause()
    }

    override fun onDestroy() {
        savePos()
        player.release()
        super.onDestroy()
    }
}

@Composable
private fun VideoApp(a: VideoActivity) {
    val c = LocalBlaze.current
    val tab by a.tab
    val pip by a.pip
    val full by a.fullscreen
    val store = remember { StoreState("video") }
    BackHandler(full) { a.setFullscreen(false) }
    BackHandler(!full && tab == "player") { a.player.pause(); a.tab.value = "library" }

    if (pip) { PlayerSurface(a, controls = false); return }
    if (full) { Box(Modifier.fillMaxSize().background(Color.Black)) { PlayerSurface(a, controls = true) }; return }

    Column(Modifier.fillMaxSize().background(c.paper).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton({ a.finish() }, desc = "Close Video")
            Spacer(Modifier.weight(1f))
            AppPill(listOf("player" to "Player", "library" to "Library", "store" to "Store"), tab, onPick = {
                if (it != "player") a.player.pause()
                a.tab.value = it
            })
            Spacer(Modifier.weight(1f)); Spacer(Modifier.width(48.dp))
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                "player" -> PlayerTab(a)
                "store" -> StoreView("video", store) { item, d, src -> a.watch(item, d, src) }
                else -> VideoLibrary(a)
            }
        }
    }
}

@Composable
private fun PlayerTab(a: VideoActivity) {
    val c = LocalBlaze.current
    val t by a.current
    if (t == null) { EmptyState("Nothing playing", "Pick a video in Library, or find one in the Store."); return }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) { PlayerSurface(a, controls = true) }
        Column(Modifier.padding(16.dp)) {
            MText(t!!.title, 18.sp, c.ink, FontWeight.SemiBold, maxLines = 3)
            Spacer(Modifier.height(4.dp))
            MText(listOf(t!!.artist, t!!.licence).filter { it.isNotBlank() }.joinToString(" \u00b7 "), 13.sp, c.sub)
            Spacer(Modifier.height(10.dp))
            MText("Swipe sideways to seek \u00b7 up/down on the left for brightness, on the right for volume \u00b7 double-tap to skip 10 s", 12.sp, c.weak, maxLines = 3)
        }
    }
}

/** The video, its gesture layer and (with [controls]) the overlay controls. */
@Composable
private fun PlayerSurface(a: VideoActivity, controls: Boolean) {
    val ctx = LocalContext.current
    val p = a.player
    var playing by remember { mutableStateOf(p.isPlaying) }
    var pos by remember { mutableLongStateOf(p.currentPosition) }
    var dur by remember { mutableLongStateOf(p.duration.coerceAtLeast(0)) }
    var buffering by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(true) }
    var hint by remember { mutableStateOf("") }
    var speedMenu by remember { mutableStateOf(false) }
    var tracksOpen by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(p.playbackParameters.speed) }
    DisposableEffect(p) {
        val l = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                playing = player.isPlaying; dur = player.duration.coerceAtLeast(0); pos = player.currentPosition
                buffering = player.playbackState == Player.STATE_BUFFERING; speed = player.playbackParameters.speed
                error = player.playerError?.let { e ->
                    when (e.errorCode) {
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "No connection. Downloaded videos play offline."
                        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The stream link expired or was refused. Open it again from the Store."
                        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "This file was deleted or moved."
                        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "This phone can't decode this video. Try a lower quality."
                        else -> "Couldn't play this: " + (e.message ?: e.errorCodeName)
                    }
                }.orEmpty()
            }
        }
        p.addListener(l)
        onDispose { p.removeListener(l) }
    }
    LaunchedEffect(playing) { while (playing) { pos = p.currentPosition; delay(250) } }
    LaunchedEffect(show, playing) { if (show && playing) { delay(3500); show = false } }
    LaunchedEffect(hint) { if (hint.isNotEmpty()) { delay(900); hint = "" } }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { cx ->
            PlayerView(cx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                player = p
            }
        }, update = { it.player = p }, onRelease = { it.player = null }, modifier = Modifier.fillMaxSize())

        if (controls) {
            // Gestures: tap toggles controls, double-tap left/right skips 10 s, drags seek / change brightness / volume.
            Box(Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { show = !show }, onDoubleTap = { o ->
                        if (o.x < size.width / 2) { p.seekBack(); hint = "\u2212 10 s" } else { p.seekForward(); hint = "+ 10 s" }
                    })
                }
                .pointerInput(Unit) {
                    var mode = 0   // 1 seek, 2 brightness, 3 volume
                    var startX = 0f; var startPos = 0L; var acc = 0f
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    detectDragGestures(
                        onDragStart = { o -> mode = 0; startX = o.x; startPos = p.currentPosition; acc = 0f },
                        onDrag = { ch, d ->
                            ch.consume()
                            if (mode == 0) mode = if (kotlin.math.abs(d.x) > kotlin.math.abs(d.y)) 1 else if (startX < size.width / 2) 2 else 3
                            when (mode) {
                                1 -> {
                                    acc += d.x
                                    val target = (startPos + (acc / size.width) * 90_000).toLong().coerceIn(0, p.duration.coerceAtLeast(0))
                                    p.seekTo(target); hint = TimeText.clock(target)
                                }
                                2 -> {
                                    val lp = a.window.attributes
                                    val cur = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
                                    lp.screenBrightness = (cur - d.y / size.height).coerceIn(0.02f, 1f)
                                    a.window.attributes = lp
                                    hint = "Brightness ${(lp.screenBrightness * 100).toInt()}%"
                                }
                                3 -> {
                                    acc -= d.y
                                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                    val stepPx = size.height / (max * 1.2f)
                                    if (kotlin.math.abs(acc) >= stepPx) {
                                        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (acc > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
                                        acc = 0f
                                    }
                                    hint = "Volume ${am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max.coerceAtLeast(1)}%"
                                }
                            }
                        })
                })
        }
        if (buffering) LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp), color = BlazeBrush.blue, trackColor = Color.Transparent)
        if (hint.isNotEmpty()) Box(Modifier.align(Alignment.Center).clip(BlazeShapes.medium).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 16.dp, vertical = 10.dp)) {
            MText(hint, 15.sp, Color.White, FontWeight.Medium)
        }
        if (error.isNotBlank()) Column(Modifier.align(Alignment.Center).padding(24.dp).clip(BlazeShapes.medium).background(Color.Black.copy(alpha = 0.75f)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            MText(error, 13.5.sp, Color.White, maxLines = 4)
            Spacer(Modifier.height(10.dp))
            PillButton("Try again", primary = true) { p.prepare(); p.play() }
        }
        if (controls) AnimatedVisibility(show && error.isBlank(), Modifier.fillMaxSize(), enter = MotionTokens.fade(), exit = MotionTokens.fadeAway()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
                Row(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                    Box {
                        RoundIcon(Icons.Rounded.Speed, "Speed ${Speeds.label(speed)}", tint = Color.White) { speedMenu = true }
                        BlazeMenu(speedMenu, { speedMenu = false }, Speeds.all.map { s -> (Speeds.label(s) + if (s == speed) "  \u2713" else "") to { p.setPlaybackSpeed(s) } })
                    }
                    RoundIcon(Icons.Rounded.ClosedCaption, "Subtitles and audio", tint = Color.White) { tracksOpen = true }
                    if (Build.VERSION.SDK_INT >= 26) RoundIcon(Icons.Rounded.PictureInPictureAlt, "Picture in picture", tint = Color.White) { a.enterPip() }
                }
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundIcon(Icons.Rounded.Replay10, "Back 10 seconds", size = 52, tint = Color.White) { p.seekBack() }
                    RoundIcon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", size = 68, tint = Color.White,
                        fill = Color.White.copy(alpha = 0.16f)) {
                        if (p.isPlaying) p.pause() else { if (p.playbackState == Player.STATE_ENDED) p.seekTo(0); if (p.playbackState == Player.STATE_IDLE) p.prepare(); p.play() }
                    }
                    RoundIcon(Icons.Rounded.Forward10, "Forward 10 seconds", size = 52, tint = Color.White) { p.seekForward() }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 10.dp)) {
                    var drag by remember { mutableStateOf<Float?>(null) }
                    val frac = drag ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MText(TimeText.clock(if (drag != null) (frac * dur).toLong() else pos) + " / " + TimeText.clock(dur), 12.sp, Color.White, modifier = Modifier.padding(start = 6.dp))
                        Spacer(Modifier.weight(1f))
                        val full by a.fullscreen
                        RoundIcon(if (full) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, if (full) "Exit full screen" else "Full screen", tint = Color.White) { a.setFullscreen(!full) }
                    }
                    Slider(frac, { drag = it; show = true }, onValueChangeFinished = { drag?.let { f -> p.seekTo((f * dur).toLong()) }; drag = null },
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = BlazeBrush.blue, inactiveTrackColor = Color.White.copy(alpha = 0.3f)))
                }
            }
        }
    }
    if (tracksOpen) TracksDialog(p) { tracksOpen = false }
}

/** Subtitles and audio tracks the video carries (embedded tracks). Off = no subtitles. */
@Composable
private fun TracksDialog(p: ExoPlayer, onDismiss: () -> Unit) {
    val groups = remember { p.currentTracks.groups }
    val subs = groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
    val audio = groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
    val textOff = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
    fun name(g: Tracks.Group, i: Int, n: Int): String {
        val f = g.getTrackFormat(i)
        return listOfNotNull(f.label, f.language?.let { java.util.Locale(it).displayLanguage.ifBlank { it } }).firstOrNull()
            ?: ((if (g.type == C.TRACK_TYPE_TEXT) "Subtitles " else "Audio ") + (n + 1))
    }
    fun select(g: Tracks.Group, i: Int) {
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(g.type, false)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
        onDismiss()
    }
    BlazeDialog("Subtitles and audio", confirm = "Done", onConfirm = onDismiss, onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            MText("Subtitles", 12.sp, DialogColors.body, FontWeight.Medium)
            Option("Off", subs.isEmpty() || textOff) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build(); onDismiss()
            }
            var n = 0
            subs.forEach { g -> for (i in 0 until g.length) { val k = n++; Option(name(g, i, k), !textOff && g.isTrackSelected(i)) { select(g, i) } } }
            if (subs.isEmpty()) MText("This video has no subtitles.", 12.5.sp, DialogColors.body)
            Spacer(Modifier.height(10.dp))
            MText("Audio", 12.sp, DialogColors.body, FontWeight.Medium)
            n = 0
            audio.forEach { g -> for (i in 0 until g.length) { val k = n++; Option(name(g, i, k), g.isTrackSelected(i)) { select(g, i) } } }
        }
    }
}

@Composable
private fun Option(text: String, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(BlazeShapes.small).background(if (on) DialogColors.primary.copy(alpha = 0.18f) else Color.Transparent)
        .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        MText(text, 14.5.sp, DialogColors.title, if (on) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (on) MText("\u2713", 14.sp, DialogColors.primary)
    }
}

@Composable
private fun VideoLibrary(a: VideoActivity) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val videos by MediaLib.videos.collectAsState()
    val recent by MediaLib.recentVideos.collectAsState()
    var q by remember { mutableStateOf("") }
    var confirmDel by remember { mutableStateOf<Track?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { MediaLib.refresh() } }
    val byKey = remember(videos) { videos.associateBy { it.key } }
    // Continue watching: recently played with a saved position.
    val cont = recent.mapNotNull { byKey[it] }.filter { MediaLib.position(it.key) > 0 }.take(12)
    val shown = LibraryGroups.search(videos, q)

    LazyVerticalGrid(GridCells.Adaptive(160.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { KitSearch(q, { q = it }, "Search your videos") }
        if (q.isBlank() && cont.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { MText("Continue watching", 15.sp, c.ink, FontWeight.SemiBold) }
            item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(cont, key = { "c" + it.key }) { t ->
                        Column(Modifier.width(200.dp).clip(BlazeShapes.small).clickable { a.play(t) }) {
                            Box {
                                Art(t, Modifier.fillMaxWidth().aspectRatio(16f / 9f), target = 400)
                                val d = t.durationMs.takeIf { it > 0 }
                                if (d != null) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.3f))) {
                                    Box(Modifier.fillMaxWidth((MediaLib.position(t.key).toFloat() / d).coerceIn(0f, 1f)).height(3.dp).background(Color(0xFFE5484D)))
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            MText(t.title, 12.5.sp, c.ink, FontWeight.Medium)
                            MText("${TimeText.clock(MediaLib.position(t.key))} watched", 11.5.sp, c.sub)
                        }
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { MText(if (q.isBlank()) "All videos \u00b7 ${videos.size}" else "Results", 15.sp, c.ink, FontWeight.SemiBold) }
        if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyState(if (q.isBlank()) "No videos yet" else "No match",
                if (q.isBlank()) "Films you download in the Store, and videos in Blaze's Gallery, show up here and play offline." else "Nothing matches \u201c$q\u201d.")
        }
        items(shown, key = { it.key }) { t ->
            var menu by remember { mutableStateOf(false) }
            Column(Modifier.clip(BlazeShapes.small).clickable { a.play(t) }) {
                Box {
                    Art(t, Modifier.fillMaxWidth().aspectRatio(16f / 9f), target = 400)
                    Box(Modifier.align(Alignment.BottomEnd)) { DurationBadgeText(if (t.durationMs > 0) TimeText.clock(t.durationMs) else "") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(top = 4.dp)) {
                        MText(t.title, 13.sp, c.ink, FontWeight.Medium, maxLines = 2)
                        MText(listOf(t.artist, t.licence).filter { it.isNotBlank() }.joinToString(" \u00b7 "), 11.5.sp, c.sub)
                    }
                    if (t.source == "download") Box {
                        RoundIcon(Icons.Rounded.MoreVert, "More", size = 36, tint = c.sub) { menu = true }
                        BlazeMenu(menu, { menu = false }, listOf("Delete from phone" to { confirmDel = t }))
                    }
                }
            }
        }
    }
    confirmDel?.let { t ->
        BlazeDialog("Delete \u201c${t.title.take(40)}\u201d?", "It's removed from your phone.", confirm = "Delete", warm = true,
            onConfirm = { MediaLib.deleteDownload(ctx, t.key); confirmDel = null }, dismiss = "Cancel", onDismiss = { confirmDel = null })
    }
}

@Composable
private fun DurationBadgeText(text: String) {
    if (text.isBlank()) return
    Box(Modifier.padding(6.dp).clip(BlazeShapes.xs).background(Color.Black.copy(alpha = 0.78f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        MText(text, 11.sp, Color.White, FontWeight.Medium)
    }
}
