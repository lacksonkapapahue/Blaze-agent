package com.blaze.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.ChatMeta
import com.blaze.assistant.agent.ChatStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Archive (one mind, v0.18.0): every chat that isn't the main conversation or a project thread, read-only.
 * Their words are also in the journal, so Blaze finds them with search_chat. Nothing here edits or deletes.
 * [initial] opens one archived chat straight away.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatsSheet(onDismiss: () -> Unit, initial: String? = null) {
    val c = LocalBlaze.current
    val all by Agent.chats.collectAsState()
    var query by remember { mutableStateOf("") }
    var viewing by remember { mutableStateOf(initial) }
    val archived = remember(all) { all.filter { !Agent.isLive(it.id) } }
    val shown = remember(archived, query) { if (query.isBlank()) archived else ChatStore.search(query).filter { !Agent.isLive(it.id) } }
    BackHandler { if (viewing != null && initial == null) viewing = null else onDismiss() }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (c.dark) 0.5f else 0.25f))
        .clickable(remember { MutableInteractionSource() }, null) { onDismiss() }) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.86f)
                .background(c.paper).hairlineTop(c.ink)
                .clickable(remember { MutableInteractionSource() }, null) { }
                .navigationBarsPadding().imePadding().padding(20.dp)
        ) {
            val open = viewing
            if (open != null) ArchivedChat(open, onBack = { if (initial == null) viewing = null else onDismiss() }) else {
            Label("Read-only")
            Spacer(Modifier.height(10.dp))
            Text("Archive", style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium,
                fontSize = 32.sp, letterSpacing = (-0.03).em, color = c.ink))
            Text("Chats from before Blaze had one conversation. Blaze still finds anything in them when it looks back.",
                modifier = Modifier.padding(top = 6.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.sub))
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().hairline(c.hair).padding(14.dp)) {
                if (query.isEmpty()) Text("Search the archive", style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.weak))
                BasicTextField(query, { query = it }, singleLine = true, cursorBrush = SolidColor(c.accent),
                    textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.ink), modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
            if (shown.isEmpty()) {
                Text(if (query.isBlank()) "Nothing archived. Every conversation now happens in one place." else "Nothing matches \"$query\".",
                    modifier = Modifier.padding(vertical = 24.dp),
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.sub))
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(shown, key = { it.id }) { m ->
                    ChatRow(m, false, false, onOpen = { viewing = m.id }, onMore = { viewing = m.id })
                }
            }
            }
        }
    }
}

/** One archived chat's words, read-only. */
@Composable
private fun ArchivedChat(id: String, onBack: () -> Unit) {
    val c = LocalBlaze.current
    val doc = remember(id) { ChatStore.load(id) }
    val lines = remember(doc) {
        val ms = doc?.optJSONArray("messages")
        (0 until (ms?.length() ?: 0)).mapNotNull { i -> ms!!.optJSONObject(i) }
            .filter { it.optString("role") == "USER" || it.optString("role") == "BLAZE" }
            .map { (it.optString("role") == "USER") to it.optString("text") }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Label("\u2190 Archive", color = c.accent, size = 11.sp, modifier = Modifier.clickable(onClick = onBack).padding(vertical = 6.dp))
        Spacer(Modifier.weight(1f))
        Label("Read-only", size = 9.sp)
    }
    Spacer(Modifier.height(8.dp))
    Text(doc?.optString("title").orEmpty().ifBlank { "Chat" }, maxLines = 2, overflow = TextOverflow.Ellipsis,
        style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 24.sp, color = c.ink))
    Spacer(Modifier.height(12.dp))
    if (lines.isEmpty()) Text("This chat is empty or couldn't be read.", style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.sub))
    LazyColumn(Modifier.fillMaxWidth()) {
        items(lines.size) { i ->
            val (mine, text) = lines[i]
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Label(if (mine) "You" else "Blaze", color = if (mine) c.ink else c.accent, size = 9.sp)
                Text(text, modifier = Modifier.padding(top = 3.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 20.sp, color = c.body))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(m: ChatMeta, current: Boolean, open: Boolean, onOpen: () -> Unit, onMore: () -> Unit) {
    val c = LocalBlaze.current
    Row(
        Modifier.fillMaxWidth().hairlineBottom(c.hair).combinedClickable(onClick = onOpen, onLongClick = onMore).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).background(if (current) c.accent else if (m.pinned) c.ink else Color.Transparent))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.title, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = c.ink))
                if (m.pinned) { Spacer(Modifier.width(8.dp)); Label("Pinned", color = c.accent, size = 9.sp) }
            }
            Text(m.preview, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Label(when_(m.updated), size = 9.sp)
            Label(if (open) "▲" else "···", modifier = Modifier.clickable(onClick = onMore).padding(top = 6.dp, start = 10.dp), size = 12.sp)
        }
    }
}

@Composable
private fun RowAction(label: String, color: Color = LocalBlaze.current.ink, onClick: () -> Unit) {
    Box(Modifier.clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp)) { Label(label, color = color, size = 11.sp) }
}

private fun when_(t: Long): String {
    val d = System.currentTimeMillis() - t
    return when {
        d < 60_000 -> "Now"
        d < 86_400_000 && SimpleDateFormat("d", Locale.US).format(Date(t)) == SimpleDateFormat("d", Locale.US).format(Date()) ->
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
        d < 6 * 86_400_000L -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(t))
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(t))
    }
}
