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
                    Box(Modifier.widthIn(max = 330.dp).fillMaxWidth().heightIn(min = 120.dp).background(c.hair.copy(alpha = 0.3f))
                        .clickable { try { ctx.startActivity(Media.viewIntent(f)) } catch (_: Exception) { } }) {
                        if (bmp != null) Image(bmp, contentDescription = f.nameWithoutExtension, modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                        else Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { Label("Loading picture\u2026", size = 10.sp) }
                    }
                }
                "audio" -> Row(Modifier.widthIn(max = 330.dp).fillMaxWidth().hairline(c.hair).clickable { Player.toggle(p) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).background(c.ink), contentAlignment = Alignment.Center) {
                        Text(if (playing == p) "\u275A\u275A" else "\u25B6", style = TextStyle(fontSize = 13.sp, color = c.paper))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(f.nameWithoutExtension.replace('_', ' '), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.ink))
                }
                else -> Row(Modifier.hairline(c.hair).clickable { try { ctx.startActivity(Media.viewIntent(f)) } catch (_: Exception) { } }.padding(12.dp)) {
                    Label("\u25B6  " + f.name, color = c.ink, size = 10.sp)
                }
            }
            Row(Modifier.padding(top = 4.dp, bottom = 8.dp)) {
                Label("Save", color = c.accent, size = 9.sp, modifier = Modifier.clickable { Toast.makeText(ctx, Media.saveToPhone(f), Toast.LENGTH_SHORT).show() }.padding(end = 14.dp, top = 4.dp, bottom = 4.dp))
                Label("Share", color = c.accent, size = 9.sp, modifier = Modifier.clickable { try { ctx.startActivity(Media.shareIntent(f)) } catch (_: Exception) { } }.padding(end = 14.dp, top = 4.dp, bottom = 4.dp))
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
        Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clickable { if (open != null) open = null else onDismiss() }, contentAlignment = Alignment.Center) {
                Text("\u2190", style = TextStyle(fontFamily = Type.Display, fontSize = 22.sp, color = c.ink))
            }
            Text(open?.title?.take(30) ?: "Gallery", modifier = Modifier.weight(1f),
                style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 20.sp, color = c.ink))
            Label("${items.size}", color = c.weak, size = 10.sp, modifier = Modifier.padding(end = 12.dp))
        }
        val sel = open
        if (sel != null) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                MediaStrip(listOf(sel.file.absolutePath))
                Label("Delete", color = c.accent, size = 10.sp, modifier = Modifier.clickable { Media.delete(sel.file); open = null }.padding(vertical = 10.dp))
            }
        } else if (items.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(28.dp)) {
                Text("Nothing here yet.", style = TextStyle(fontFamily = Type.Display, fontSize = 22.sp, color = c.ink))
                Spacer(Modifier.height(8.dp))
                Text("Ask Blaze to \u201cdraw a lion in sunglasses\u201d or \u201cmake me an afro beat\u201d. Everything it makes lands here.",
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, lineHeight = 22.sp, color = c.sub))
            }
        } else {
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().padding(2.dp)) {
                items(items, key = { it.file.absolutePath }) { it ->
                    Box(Modifier.padding(2.dp).aspectRatio(1f).background(c.hair.copy(alpha = 0.3f)).clickable { open = it }, contentAlignment = Alignment.Center) {
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
