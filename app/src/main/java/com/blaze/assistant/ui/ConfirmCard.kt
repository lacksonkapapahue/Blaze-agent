package com.blaze.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.Agent

/** Blaze's mid-task question: tap an option, or type your own answer. */
@Composable
fun QuestionCard() {
    val c = LocalBlaze.current
    val p by Agent.pendingQuestion.collectAsState()
    val q = p ?: return
    var typing by remember(q) { mutableStateOf(false) }
    var text by remember(q) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().background(c.paper).hairlineTop(c.accent).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(14.dp).background(c.accent))
            Spacer(Modifier.width(8.dp))
            Label(if (q.chat.isNotBlank()) "Question · ${q.chat.take(30)}" else "Blaze is asking", color = c.accent, size = 10.sp, maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
        Text(q.question, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 23.sp, color = c.ink))
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().hairline(c.hair)) {
            q.options.forEachIndexed { i, o ->
                Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).clickable { Agent.answerQuestion(o) }.padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Label("%d".format(i + 1), color = c.accent, size = 10.sp)
                    Spacer(Modifier.width(12.dp))
                    Text(o, modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.ink))
                }
            }
            if (!typing) Row(Modifier.fillMaxWidth().clickable { typing = true }.padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Label("%d".format(q.options.size + 1), color = c.weak, size = 10.sp)
                Spacer(Modifier.width(12.dp))
                Text("Type my own answer…", style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.sub))
            } else Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).padding(vertical = 13.dp)) {
                    if (text.isEmpty()) Text("Your answer", style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.weak))
                    BasicTextField(text, { text = it }, textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.ink),
                        cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
                }
                Box(Modifier.height(46.dp).width(72.dp).background(if (text.isBlank()) c.faint else c.accent)
                    .clickable { if (text.isNotBlank()) Agent.answerQuestion(text.trim()) }, contentAlignment = Alignment.Center) {
                    Label("Send", color = if (text.isBlank()) c.weak else c.paper, size = 11.sp)
                }
            }
        }
    }
}

/** "Send a text to Mary?" with Allow / Don't. Shown wherever Blaze is on screen. */
@Composable
fun ConfirmCard() {
    val c = LocalBlaze.current
    val p by Agent.pendingConfirm.collectAsState()
    val pc = p ?: return
    Column(Modifier.fillMaxWidth().background(c.paper).hairlineTop(c.accent).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(14.dp).background(c.accent))
            Spacer(Modifier.width(8.dp))
            Label("Needs your OK", color = c.accent, size = 10.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(pc.title, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 18.sp, color = c.ink))
        if (pc.detail.isNotBlank()) Text(pc.detail, modifier = Modifier.padding(top = 4.dp),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 20.sp, color = c.body))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f).height(46.dp).hairline(c.hair).clickable { Agent.answerConfirm(false) },
                contentAlignment = Alignment.Center) { Label("Don't", color = c.ink, size = 11.sp) }
            Box(Modifier.weight(1f).height(46.dp).background(c.accent).clickable { Agent.answerConfirm(true) },
                contentAlignment = Alignment.Center) { Label(pc.action, color = c.paper, size = 11.sp) }
        }
    }
}
