package com.blaze.assistant.mediaapps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.blaze.assistant.mediaapps.store.Choice
import com.blaze.assistant.mediaapps.store.JamendoSource
import com.blaze.assistant.mediaapps.store.StoreDetail
import com.blaze.assistant.mediaapps.store.StoreItem
import com.blaze.assistant.mediaapps.store.StoreSource
import com.blaze.assistant.mediaapps.store.StoreSources
import com.blaze.assistant.ui.BlazeBrush
import com.blaze.assistant.ui.BlazeShapes
import com.blaze.assistant.ui.BlazeTextField
import com.blaze.assistant.ui.DialogColors
import com.blaze.assistant.ui.EmptyState
import com.blaze.assistant.ui.LocalBlaze
import com.blaze.assistant.ui.MotionTokens
import com.blaze.assistant.ui.PillButton
import com.blaze.assistant.ui.Type
import com.blaze.assistant.ui.rememberHomeImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.30.1: pieces shared by Blaze Music and Blaze Video: the Player | Library | Store pill (the same look as the
 * Chat | Projects | Home pill), cover art, rows, and the Store (search any StoreSource as a plain thumbnail grid with
 * licences, preview, quality choice, download queue). Never a web view.
 */

/** The same pill as Chat | Projects | Home (ui/Shell.kt): faint track, the chosen tab filled with ink, fading across. */
@Composable
fun AppPill(tabs: List<Pair<String, String>>, value: String, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    val c = LocalBlaze.current
    Row(modifier.clip(RoundedCornerShape(20.dp)).background(c.faint).padding(3.dp)) {
        tabs.forEach { (id, label) ->
            val on = value == id
            val fill by animateColorAsState(if (on) c.ink else Color.Transparent, MotionTokens.medium(), label = "tab")
            val ink by animateColorAsState(if (on) c.paper else c.sub, MotionTokens.medium(), label = "tabText")
            Box(Modifier.clip(RoundedCornerShape(17.dp)).background(fill).clickable(onClickLabel = "Switch to $label") { onPick(id) }
                .semantics { selected = on }.heightIn(min = 34.dp).padding(horizontal = 14.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                Text(label, maxLines = 1, softWrap = false, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = ink))
            }
        }
    }
}

@Composable
fun MText(text: String, size: TextUnit = 14.sp, color: Color = LocalBlaze.current.ink, weight: FontWeight = FontWeight.Normal,
          maxLines: Int = 1, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        style = TextStyle(fontFamily = Type.Sans, fontWeight = weight, fontSize = size, lineHeight = size * 1.3f, color = color))
}

/** A round icon button (40 dp in a 44 dp target). */
@Composable
fun RoundIcon(icon: ImageVector, desc: String, modifier: Modifier = Modifier, size: Int = 44, tint: Color = LocalBlaze.current.ink,
              fill: Color = Color.Transparent, onClick: () -> Unit) {
    Box(modifier.size(size.dp).clip(BlazeShapes.pill).background(fill, BlazeShapes.pill).clickable(onClickLabel = desc, onClick = onClick)
        .semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size * 0.52f).dp))
    }
}

// ---------------- cover art ----------------

object ArtCache {
    private val mem = object : android.util.LruCache<String, ImageBitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    private fun sample(w: Int, target: Int): Int { var s = 1; while (w / (s * 2) >= target) s *= 2; return s }

    /** A local cover: the saved thumbnail, the song's embedded picture or a video frame. Null when there's none. */
    fun load(ctx: Context, art: String, uri: String, kind: String, target: Int = 320): ImageBitmap? {
        val key = "$art|$uri|$target"
        mem.get(key)?.let { return it }
        val bmp: Bitmap? = try {
            when {
                art.isNotBlank() && !art.startsWith("http") && java.io.File(art).isFile -> {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(art, o)
                    BitmapFactory.decodeFile(art, BitmapFactory.Options().apply { inSampleSize = sample(o.outWidth, target) })
                }
                uri.isNotBlank() -> {
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(ctx, Uri.parse(uri))
                        if (kind == "video") {
                            if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(2_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, target, target * 9 / 16)
                            else r.getFrameAtTime(2_000_000)
                        } else r.embeddedPicture?.let { b ->
                            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(b, 0, b.size, o)
                            BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = sample(o.outWidth, target) })
                        }
                    } finally { try { r.release() } catch (_: Exception) { } }
                }
                else -> null
            }
        } catch (_: Throwable) { null }
        return bmp?.asImageBitmap()?.also { mem.put(key, it) }
    }
}

@Composable
fun rememberArt(t: Track?, target: Int = 320): ImageBitmap? {
    if (t == null) return null
    if (t.art.startsWith("http")) return rememberHomeImage(t.art)
    val ctx = LocalContext.current
    val img by produceState<ImageBitmap?>(null, t.key, t.art) { value = withContext(Dispatchers.IO) { ArtCache.load(ctx, t.art, t.uri, t.kind, target) } }
    return img
}

/** Cover art, or a soft gradient with a note / film mark when there's none. */
@Composable
fun Art(t: Track?, modifier: Modifier, shape: androidx.compose.ui.graphics.Shape = BlazeShapes.small, target: Int = 320) {
    val img = rememberArt(t, target)
    val c = LocalBlaze.current
    Box(modifier.clip(shape).background(Brush.linearGradient(listOf(BlazeBrush.blue.copy(alpha = 0.55f), BlazeBrush.orange.copy(alpha = 0.45f)))),
        contentAlignment = Alignment.Center) {
        if (img != null) Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(if (t?.kind == "video") Icons.Rounded.Movie else Icons.Rounded.MusicNote, contentDescription = null,
            tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.fillMaxSize(0.42f))
        if (c.dark && img != null) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.06f)))
    }
}

@Composable
fun NetThumb(url: String, modifier: Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    val img = rememberHomeImage(url)
    val c = LocalBlaze.current
    Box(modifier.background(c.faint)) {
        if (img != null) Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        content()
    }
}

/** The small dark duration badge in a thumbnail's corner. */
@Composable
fun BoxScope.DurationBadge(text: String) {
    if (text.isBlank()) return
    Box(Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(BlazeShapes.xs).background(Color.Black.copy(alpha = 0.78f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, color = Color.White))
    }
}

// ---------------- Store ----------------

/** One Store's state, kept while the app is open (switching tabs doesn't lose results). */
class StoreState(kind: String) {
    var source by mutableStateOf(StoreSources.forKind(kind).firstOrNull()?.id.orEmpty())
    var query by mutableStateOf("")
    var results by mutableStateOf<List<StoreItem>>(emptyList())
    var next by mutableStateOf("")
    var loading by mutableStateOf(false)
    var error by mutableStateOf("")
    var preview by mutableStateOf<StoreItem?>(null)
    var searched by mutableStateOf(false)
    var gen by mutableStateOf(0)
}

/**
 * The Store tab: pick a source (Jamendo, Internet Archive, public-domain films, Blender open movies, and YouTube when
 * the plug-in is in this build), search, see results as a neat grid (picture, title, author, length, licence), the
 * download queue on top. Tapping a result opens [PreviewSheet]. [onPlay] streams it right away.
 */
@Composable
fun StoreView(kind: String, state: StoreState, onPlay: (StoreItem, StoreDetail, StoreSource) -> Unit) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val jobs by Downloads.jobs.collectAsState()
    val mine = jobs.filter { it.kind == kind && it.status != DlStatus.DONE && it.status != DlStatus.CANCELED }
    val sources = remember(kind) { StoreSources.forKind(kind) }
    val src = sources.firstOrNull { it.id == state.source } ?: sources.firstOrNull()
    var setupTick by remember { mutableStateOf(0) }
    val setup = remember(src?.id, setupTick) { src?.setupNeeded() }

    fun search(more: Boolean = false) {
        val s = src ?: return
        val q = state.query.trim()
        if ((q.isEmpty() && !s.browsable) || state.loading || setup != null) return
        state.loading = true; state.error = ""; state.searched = true
        val gen = state.gen
        scope.launch {
            try {
                val p = s.search(kind, q, if (more) state.next else "")
                if (gen != state.gen) return@launch
                state.results = if (more) (state.results + p.items).distinctBy { it.source + it.id } else p.items
                state.next = p.next
                if (!more && p.items.isEmpty()) state.error = if (q.isEmpty()) "Nothing here right now." else "Nothing found for \u201c$q\u201d."
            } catch (e: Exception) {
                if (gen == state.gen) state.error = e.message ?: "Search didn't work. Check the connection."
            } finally { if (gen == state.gen) state.loading = false }
        }
    }
    // Free sources list something before the first search (popular / the whole catalogue).
    LaunchedEffect(src?.id, setup) { if (!state.searched && src?.browsable == true && setup == null) search() }

    val square = src?.id != null && state.results.firstOrNull()?.square == true
    LazyVerticalGrid(GridCells.Fixed(if (square) 3 else 2), Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (sources.size > 1) item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                sources.forEach { s ->
                    val on = s.id == src?.id
                    Box(Modifier.clip(BlazeShapes.pill).background(if (on) c.ink else c.faint).clickable {
                        if (!on) { state.source = s.id; state.results = emptyList(); state.next = ""; state.error = ""; state.searched = false; state.loading = false; state.gen++ }
                    }.semantics { selected = on }.padding(horizontal = 12.dp, vertical = 7.dp)) {
                        MText(s.name, 12.5.sp, if (on) c.paper else c.sub, FontWeight.Medium)
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BlazeTextField(state.query, { state.query = it }, (if (kind == "video") "Search " else "Search ") + (src?.name ?: ""),
                    Modifier.weight(1f), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }))
                Spacer(Modifier.width(8.dp))
                RoundIcon(Icons.Rounded.Search, "Search", fill = c.faint) { search() }
            }
        }
        if (setup != null && src is JamendoSource) item(span = { GridItemSpan(maxLineSpan) }) { JamendoSetup(setup) { setupTick++ } }
        else if (setup != null) item(span = { GridItemSpan(maxLineSpan) }) { MText(setup, 13.sp, c.sub, maxLines = 4) }
        if (mine.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { DownloadsList(mine) }
        if (state.error.isNotBlank()) item(span = { GridItemSpan(maxLineSpan) }) {
            MText(state.error, 13.sp, c.sub, maxLines = 4, modifier = Modifier.padding(vertical = 8.dp))
        }
        if (state.results.isEmpty() && !state.loading && state.error.isBlank() && setup == null) item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyState(if (kind == "video") "Find a video" else "Find music",
                "Search, then tap a result to see it, its licence and the qualities. Downloads go to " +
                    MediaFiles.relDir(kind) + " and show up in your Library and the Gallery.")
        }
        items(state.results, key = { it.source + ":" + it.id }) { r ->
            Column(Modifier.clip(BlazeShapes.small).clickable { state.preview = r }) {
                NetThumb(r.thumb, Modifier.fillMaxWidth().aspectRatio(if (r.square) 1f else 16f / 9f).clip(BlazeShapes.small)) { DurationBadge(r.durationText) }
                Spacer(Modifier.height(6.dp))
                MText(r.title, 13.sp, c.ink, FontWeight.Medium, maxLines = 2)
                if (r.author.isNotBlank()) MText(r.author, 12.sp, c.sub)
                if (r.licence.isNotBlank()) MText(r.licence, 11.sp, c.accent, FontWeight.Medium)
            }
        }
        if (state.loading || state.next.isNotBlank() && state.results.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                if (state.loading) MText("Searching\u2026", 13.sp, c.sub)
                else {
                    LaunchedEffect(state.results.size) { search(more = true) }   // reached the end: load the next page
                    MText("Loading more\u2026", 13.sp, c.sub)
                }
            }
        }
    }

    val s = src
    state.preview?.let { item -> if (s != null) PreviewSheet(kind, item, s, onDismiss = { state.preview = null }, onPlay = { d -> state.preview = null; onPlay(item, d, s) },
        onDownload = { d, ch ->
            state.preview = null
            if (Build.VERSION.SDK_INT < 29) StoragePermission.ask(ctx)
            Downloads.add(ctx, item, d, ch, kind)
            Toast.makeText(ctx, "Downloading \u201c${(ch.title.ifBlank { item.title }).take(40)}\u201d (${ch.label.take(30)})", Toast.LENGTH_SHORT).show()
        }) }
}

/** Jamendo's "not configured" card: where to get a client_id, and a field to paste it. */
@Composable
fun JamendoSetup(text: String, onSaved: () -> Unit) {
    val c = LocalBlaze.current
    var id by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().clip(BlazeShapes.medium).background(c.faint).padding(14.dp)) {
        MText("Jamendo isn't configured", 14.sp, c.ink, FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        MText(text, 12.5.sp, c.sub, maxLines = 5)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BlazeTextField(id, { id = it }, "Jamendo client_id", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            PillButton("Save", primary = true) { if (id.isNotBlank()) { JamendoSource.setClientId(id); onSaved() } }
        }
    }
}

/** Android 8–9 need the storage permission to save into Music/ and Movies/ (Android 10+ don't). */
object StoragePermission {
    var asker: (() -> Unit)? = null
    fun ask(ctx: Context) { asker?.invoke() }
}

@Composable
fun DownloadsList(jobs: List<DlJob>) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().clip(BlazeShapes.medium).background(c.faint).padding(vertical = 6.dp)) {
        MText("Downloads", 12.sp, c.sub, FontWeight.Medium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
        jobs.forEach { j ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                NetThumb(j.thumb, Modifier.size(44.dp).clip(BlazeShapes.xs))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    MText(j.title, 13.sp, c.ink, FontWeight.Medium)
                    val line = when (j.status) {
                        DlStatus.RUNNING -> "${(j.progress * 100).toInt()}% \u00b7 ${TimeText.size(j.done)} of ${TimeText.size(j.total)}"
                        DlStatus.QUEUED -> if (j.attempts > 0) "Retrying soon \u00b7 ${j.error}" else "Waiting \u00b7 ${j.quality}"
                        DlStatus.PAUSED -> "Paused \u00b7 ${(j.progress * 100).toInt()}%"
                        DlStatus.FAILED -> "Failed \u00b7 ${j.error}"
                        else -> j.quality
                    }
                    MText(line, 11.5.sp, if (j.status == DlStatus.FAILED) BlazeBrush.orange else c.sub)
                    if (j.active || j.status == DlStatus.PAUSED) {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(progress = { j.progress }, modifier = Modifier.fillMaxWidth().height(3.dp).clip(BlazeShapes.pill),
                            color = c.accent, trackColor = c.hair)
                    }
                }
                when (j.status) {
                    DlStatus.RUNNING, DlStatus.QUEUED -> RoundIcon(Icons.Rounded.Pause, "Pause", size = 38) { Downloads.pause(ctx, j.id) }
                    DlStatus.PAUSED -> RoundIcon(Icons.Rounded.PlayArrow, "Resume", size = 38) { Downloads.resume(ctx, j.id) }
                    DlStatus.FAILED -> RoundIcon(Icons.Rounded.Refresh, "Try again", size = 38) { Downloads.resume(ctx, j.id) }
                    else -> {}
                }
                RoundIcon(Icons.Rounded.Close, if (j.active || j.status == DlStatus.PAUSED) "Cancel" else "Remove", size = 38) { Downloads.dismiss(ctx, j.id) }
            }
        }
    }
}

/** Tap a result: picture, title, author, licence, then the qualities / files on offer, Download and Play / Watch. */
@Composable
fun PreviewSheet(kind: String, item: StoreItem, source: StoreSource, onDismiss: () -> Unit, onPlay: (StoreDetail) -> Unit, onDownload: (StoreDetail, Choice) -> Unit) {
    val ctx = LocalContext.current
    var detail by remember(item.id) { mutableStateOf<StoreDetail?>(null) }
    var err by remember(item.id) { mutableStateOf("") }
    var pick by rememberSaveable(item.id) { mutableStateOf("") }
    var tries by remember { mutableStateOf(0) }
    LaunchedEffect(item.id, tries) {
        err = ""
        try { detail = source.detail(item, kind) } catch (e: Exception) { err = e.message ?: "Couldn't load this item." }
    }
    val d = detail
    val choices = remember(d) { if (d == null) emptyList() else source.choices(kind, d) }
    LaunchedEffect(choices) { if (pick.isEmpty() || choices.none { it.id == pick }) pick = source.defaultChoice(kind, choices)?.id.orEmpty() }
    val licence = d?.licence?.ifBlank { null } ?: item.licence
    val licenceUrl = d?.licenceUrl?.ifBlank { null } ?: item.licenceUrl

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(24.dp)
        Column(Modifier.padding(16.dp).widthIn(max = 460.dp).fillMaxWidth().clip(shape).background(DialogColors.surface, shape)
            .border(1.dp, DialogColors.border, shape).verticalScroll(rememberScrollState())) {
            NetThumb(item.thumb.replace("mqdefault", "hqdefault"), Modifier.fillMaxWidth().aspectRatio(if (item.square) 1.6f else 16f / 9f)) { DurationBadge(item.durationText) }
            Column(Modifier.padding(18.dp)) {
                MText(item.title, 17.sp, DialogColors.title, FontWeight.SemiBold, maxLines = 3)
                Spacer(Modifier.height(4.dp))
                MText(listOf(item.author, item.extra["album"].orEmpty(), item.views).filter { it.isNotBlank() }.joinToString(" \u00b7 "), 13.sp, DialogColors.body, maxLines = 2)
                if (licence.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MText("Licence: $licence", 12.5.sp, DialogColors.primary, FontWeight.Medium,
                            modifier = Modifier.let { m -> if (licenceUrl.startsWith("http")) m.clickable {
                                try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(licenceUrl)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
                            } else m })
                        Spacer(Modifier.width(6.dp))
                        MText("\u00b7 ${source.name}", 12.5.sp, DialogColors.body)
                    }
                }
                Spacer(Modifier.height(14.dp))
                when {
                    err.isNotBlank() -> {
                        MText(err, 13.sp, BlazeBrush.orange, maxLines = 5)
                        Spacer(Modifier.height(10.dp))
                        PillButton("Try again", primary = false) { tries++ }
                    }
                    d == null -> MText("Getting qualities\u2026", 13.sp, DialogColors.body)
                    choices.isEmpty() -> MText("No downloadable " + (if (kind == "video") "video" else "audio") + " file is offered for this one.", 13.sp, DialogColors.body, maxLines = 3)
                    else -> {
                        MText(if (kind == "video") "Video quality" else if (choices.any { it.title.isNotBlank() }) "Track and format" else "Audio quality", 12.sp, DialogColors.body, FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        choices.forEach { ch ->
                            val on = ch.id == pick
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(BlazeShapes.small)
                                .background(if (on) DialogColors.primary.copy(alpha = 0.18f) else Color.Transparent)
                                .border(1.dp, if (on) DialogColors.primary else DialogColors.border, BlazeShapes.small)
                                .clickable { pick = ch.id }.semantics { selected = on }.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                MText(ch.label, 14.sp, DialogColors.title, FontWeight.Medium, modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                MText(ch.detail, 12.sp, DialogColors.body)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    PillButton("Close", primary = false, onClick = onDismiss)
                    if (d != null && choices.isNotEmpty()) {
                        PillButton(if (kind == "video") "Watch" else "Play", primary = false) { onPlay(d) }
                        PillButton("Download", primary = true) { choices.firstOrNull { it.id == pick }?.let { onDownload(d, it) } }
                    }
                }
            }
        }
    }
}

/** A row in the music library: cover, title, artist · length, and a trailing slot. */
@Composable
fun TrackRow(t: Track, playing: Boolean, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val c = LocalBlaze.current
    Row(Modifier.fillMaxWidth().clip(BlazeShapes.small).background(if (playing) c.faint else Color.Transparent).clickable(onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Art(t, Modifier.size(46.dp), target = 140)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            MText(t.title, 14.sp, if (playing) c.accent else c.ink, FontWeight.Medium)
            MText(listOf(t.artist, if (t.durationMs > 0) TimeText.clock(t.durationMs) else "").filter { it.isNotBlank() }.joinToString(" \u00b7 "), 12.sp, c.sub)
        }
        trailing()
    }
}

/** Download icon used by the empty library. */
val DownloadIcon: ImageVector get() = Icons.Rounded.Download
