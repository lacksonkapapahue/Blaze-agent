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

/** Saved chats: search, open, rename, pin, delete. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatsSheet(onDismiss: () -> Unit) {
    val c = LocalBlaze.current
    val all by Agent.chats.collectAsState()
    val current by Agent.chatId.collectAsState()
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    val shown = remember(all, query) { if (query.isBlank()) all else ChatStore.search(query) }
    BackHandler { onDismiss() }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (c.dark) 0.5f else 0.25f))
        .clickable(remember { MutableInteractionSource() }, null) { onDismiss() }) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.86f)
                .background(c.paper).hairlineTop(c.ink)
                .clickable(remember { MutableInteractionSource() }, null) { }
                .navigationBarsPadding().imePadding().padding(20.dp)
        ) {
            Label("History")
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Chats", modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium,
                    fontSize = 32.sp, letterSpacing = (-0.03).em, color = c.ink))
                Box(Modifier.background(c.ink).clickable { Agent.clear(); onDismiss() }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Label("New chat", color = c.paper, size = 11.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().hairline(c.hair).padding(14.dp)) {
                if (query.isEmpty()) Text("Search chats", style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.weak))
                BasicTextField(query, { query = it }, singleLine = true, cursorBrush = SolidColor(c.accent),
                    textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.ink), modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
            if (shown.isEmpty()) {
                Text(if (query.isBlank()) "No saved chats yet. They appear here once you send a message." else "Nothing matches \"$query\".",
                    modifier = Modifier.padding(vertical = 24.dp),
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.sub))
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(shown, key = { it.id }) { m ->
                    ChatRow(m, m.id == current, expanded == m.id,
                        onOpen = { Agent.open(m.id); onDismiss() },
                        onMore = { expanded = if (expanded == m.id) null else m.id })
                    if (expanded == m.id) {
                        if (renaming == m.id) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f).hairline(c.accent).padding(12.dp)) {
                                    BasicTextField(renameText, { renameText = it }, singleLine = true, cursorBrush = SolidColor(c.accent),
                                        textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.ink), modifier = Modifier.fillMaxWidth())
                                }
                                Spacer(Modifier.width(8.dp))
                                Box(Modifier.background(c.ink).clickable { Agent.renameChat(m.id, renameText); renaming = null }
                                    .padding(horizontal = 14.dp, vertical = 12.dp)) { Label("Save", color = c.paper, size = 11.sp) }
                            }
                        } else {
                            Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                RowAction("Rename") { renaming = m.id; renameText = m.title }
                                RowAction(if (m.pinned) "Unpin" else "Pin") { Agent.pinChat(m.id, !m.pinned) }
                                RowAction("Delete", c.accent) { Agent.deleteChat(m.id); expanded = null }
                            }
                        }
                    }
                }
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
