package com.blaze.assistant.ui

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.media.Media
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.draw.clip

object GalleryUi { val open = MutableStateFlow(false) }

/** Decodes a picture at roughly [px] wide, off the main thread. */
@Composable
private fun rememberThumb(path: String, px: Int): ImageBitmap? {
    val v by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            try {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, o)
                var s = 1; while (o.outWidth / (s * 2) >= px) s *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
            } catch (_: Throwable) { null }
        }
    }
    return v
}

/** One shared player so only one beat plays at a time. */
private object Player {
    var mp: MediaPlayer? = null
    val playing = MutableStateFlow("")
    fun toggle(path: String) {
        if (playing.value == path) { stop(); return }
        stop()
        try {
            mp = MediaPlayer().apply {
                setDataSource(path); prepare(); start()
                setOnCompletionListener { stop() }
            }
            playing.value = path
        } catch (_: Exception) { stop() }
    }
    fun stop() { try { mp?.release() } catch (_: Exception) { }; mp = null; playing.value = "" }
}

/** Pictures and beats under a Blaze reply. */
@Composable
fun MediaStrip(paths: List<String>) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val playing by Player.playing.collectAsState()
    Column(Modifier.padding(top = 8.dp)) {
        paths.forEach { p ->
            val f = File(p)
            when (Media.kindOf(f)) {
                "image" -> {
                    val bmp = rememberThumb(p, 720)
                    Box(Modifier.widthIn(max = 330.dp).fillMaxWidth().heightIn(min = 120.dp).clip(BlazeShapes.medium).background(c.hair.copy(alpha = 0.3f))
                        .clickable { try { ctx.startActivity(Media.viewIntent(f)) } catch (_: Exception) { } }) {
                        if (bmp != null) Image(bmp, contentDescription = f.nameWithoutExtension, modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                        else Box(Modifier.fillMaxWidth().height(200.dp).shimmer(c.hair.copy(alpha = 0.3f), c.paper.copy(alpha = 0.3f), BlazeShapes.medium),
                            contentAlignment = Alignment.Center) { Label("Loading picture\u2026", size = 10.sp) }
                    }
                }
                "audio" -> Row(Modifier.widthIn(max = 330.dp).fillMaxWidth().hairline(c.hair).clickable { Player.toggle(p) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(BlazeShapes.pill).background(c.ink), contentAlignment = Alignment.Center) {
                        Text(if (playing == p) "\u275A\u275A" else "\u25B6", style = TextStyle(fontSize = 13.sp, color = c.paper))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(f.nameWithoutExtension.replace('_', ' '), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.ink))
                }
                else -> Row(Modifier.hairline(c.hair).clickable { try { ctx.startActivity(Media.viewIntent(f)) } catch (_: Exception) { } }.padding(12.dp)) {
                    Text("\u25B6  " + f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.ink))
                }
            }
            Row(Modifier.padding(top = 4.dp, bottom = 8.dp)) {
                ActionText("Save", color = c.accent, size = 9.sp, modifier = Modifier.clickable { Toast.makeText(ctx, Media.saveToPhone(f), Toast.LENGTH_SHORT).show() }.padding(end = 14.dp, top = 4.dp, bottom = 4.dp))
                ActionText("Share", color = c.accent, size = 9.sp, modifier = Modifier.clickable { try { ctx.startActivity(Media.shareIntent(f)) } catch (_: Exception) { } }.padding(end = 14.dp, top = 4.dp, bottom = 4.dp))
            }
        }
    }
}

/** Everything Blaze has made, newest first. */
@Composable
fun GalleryScreen(onDismiss: () -> Unit) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val items by Media.items.collectAsState()
    var open by remember { mutableStateOf<Media.Item?>(null) }
    LaunchedEffect(Unit) { Media.refresh() }
    DisposableEffect(Unit) { onDispose { Player.stop() } }
    BackHandler { if (open != null) open = null else onDismiss() }
    Column(Modifier.fillMaxSize().background(c.paper).statusBarsPadding().navigationBarsPadding()) {
        // v0.28.3: the shared round BackButton (was a thin "←" glyph).
        BackTitle(open?.title?.take(30) ?: "Gallery", onBack = { if (open != null) open = null else onDismiss() },
            modifier = Modifier.hairlineBottom(c.hair).padding(vertical = 2.dp)) {
            Label("${items.size}", color = c.weak, size = 10.sp, modifier = Modifier.padding(end = 12.dp))
        }
        val sel = open
        if (sel != null) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                MediaStrip(listOf(sel.file.absolutePath))
                ActionText("Delete", color = c.accent, size = 10.sp, modifier = Modifier.clickable { Media.delete(sel.file); open = null }.padding(vertical = 10.dp))
            }
        } else if (items.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Rows { EmptyState("Nothing here yet", "Ask Blaze to \u201cdraw a lion in sunglasses\u201d or \u201cmake me an afro beat\u201d. Everything it makes lands here.") }
            }
        } else {
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().padding(2.dp)) {
                items(items, key = { it.file.absolutePath }) { it ->
                    Box(Modifier.padding(3.dp).aspectRatio(1f).clip(BlazeShapes.small).background(c.hair.copy(alpha = 0.3f)).clickable { open = it }, contentAlignment = Alignment.Center) {
                        if (it.kind == "image") {
                            val bmp = rememberThumb(it.file.absolutePath, 240)
                            if (bmp != null) Image(bmp, contentDescription = it.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                            Text(if (it.kind == "audio") "\u266B" else "\u25B6", style = TextStyle(fontSize = 26.sp, color = c.ink))
                            Text(it.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontSize = 11.sp, color = c.sub))
                        }
                    }
                }
            }
        }
    }
}


/** Your own attached photos/PDF pages, shown as pictures in your message. Tap to open full size. */
@Composable
fun AttachedThumbs(paths: List<String>) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        paths.take(5).forEach { p ->
            val bmp = rememberThumb(p, 360)
            val single = paths.size == 1
            Box(Modifier.then(if (single) Modifier.widthIn(max = 220.dp) else Modifier.size(84.dp)).clip(BlazeShapes.small).background(c.hair.copy(alpha = 0.3f)).border(1.dp, c.hair, BlazeShapes.small)
                .clickable { try { ctx.startActivity(Media.viewIntent(File(p))) } catch (_: Exception) { } }) {
                if (bmp != null) Image(bmp, contentDescription = "Attached picture",
                    modifier = if (single) Modifier.widthIn(max = 220.dp).heightIn(max = 300.dp) else Modifier.fillMaxSize(),
                    contentScale = if (single) ContentScale.Fit else ContentScale.Crop)
                else Box(Modifier.size(84.dp))
            }
        }
    }
}
