package com.blaze.assistant.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.Settings

/**
 * Answer cards: long tables, lists, code and reports fold into a clipped card with a fade.
 * Tap to expand; Copy puts that block's Markdown on the clipboard. Re-parsed on every streamed
 * word, so a block folds as soon as it grows long. Pref fold_answers (default on).
 */
object AnswerCards {
    sealed class Seg {
        data class Text(val md: String) : Seg()
        data class Code(val lang: String, val code: String, val raw: String) : Seg()
        data class Table(val rows: List<List<String>>, val raw: String) : Seg()
        data class Lists(val md: String, val items: Int) : Seg()
    }

    const val CODE_LINES = 12
    const val TABLE_ROWS = 6
    const val LIST_ITEMS = 8
    const val REPORT_CHARS = 2400

    private val listRe = Regex("^\\s*([-*+•]|\\d+[.)])\\s+")
    private val sepRe = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    fun cells(line: String): List<String> = line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    fun parse(src: String): List<Seg> {
        val lines = src.replace("\r", "").split('\n')
        val out = mutableListOf<Seg>()
        val text = StringBuilder()
        fun flushText() {
            if (text.isEmpty()) return
            // Pull long list runs out of the text so they can fold on their own.
            val tl = text.toString().trimEnd('\n').split('\n')
            val buf = StringBuilder(); var run = mutableListOf<String>(); var items = 0
            fun flushRun() {
                if (run.isEmpty()) return
                if (items > LIST_ITEMS) {
                    if (buf.isNotBlank()) out += Seg.Text(buf.toString().trimEnd()); buf.clear()
                    out += Seg.Lists(run.joinToString("\n"), items)
                } else run.forEach { buf.append(it).append('\n') }
                run = mutableListOf(); items = 0
            }
            for (l in tl) {
                when {
                    listRe.containsMatchIn(l) -> { run += l; items++ }
                    run.isNotEmpty() && l.isNotBlank() && l.startsWith("  ") -> run += l
                    else -> { flushRun(); buf.append(l).append('\n') }
                }
            }
            flushRun()
            if (buf.isNotBlank()) out += Seg.Text(buf.toString().trimEnd())
            text.clear()
        }
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.trimStart().startsWith("```")) {
                flushText()
                val lang = l.trimStart().removePrefix("```").trim()
                val body = mutableListOf<String>()
                var j = i + 1
                while (j < lines.size && !lines[j].trimStart().startsWith("```")) { body += lines[j]; j++ }
                val raw = lines.subList(i, minOf(j + 1, lines.size)).joinToString("\n")
                out += Seg.Code(lang, body.joinToString("\n"), raw)
                i = j + 1; continue
            }
            if (l.trimStart().startsWith("|") && i + 1 < lines.size && sepRe.matches(lines[i + 1])) {
                flushText()
                val rows = mutableListOf(cells(l))
                var j = i + 2
                while (j < lines.size && lines[j].trimStart().startsWith("|")) { rows += cells(lines[j]); j++ }
                out += Seg.Table(rows, lines.subList(i, j).joinToString("\n"))
                i = j; continue
            }
            text.append(l).append('\n'); i++
        }
        flushText()
        return out
    }

    fun isReport(src: String): Boolean =
        src.length > REPORT_CHARS && src.lines().count { Regex("^\\s{0,3}#{1,6}\\s").containsMatchIn(it) } >= 2
}

/** A Blaze reply drawn as text plus cards. */
@Composable
fun AnswerBody(key: Long, src: String) {
    val c = LocalBlaze.current
    val fold = Settings.foldAnswers
    val segs = remember(src) { AnswerCards.parse(src) }
    val report = fold && AnswerCards.isReport(src)
    // v0.24.9 rich replies: steps, section pictures, image rows / decks and diagrams on top of the cards (pref rich_replies, default on;
    // "Visual answers" in Settings since v0.26.1, which also covers charts, choices, forms, tools and compare cards).
    val priv = com.blaze.assistant.agent.Agent.privateChat.value
    val rich = Settings.bool("rich_replies", true)
    val blocks = remember(src, priv, rich) { if (rich) RichReplies.expand(segs, priv) else segs.map { RichReplies.Block.Plain(it) } }
    val scale = animatorScale()
    // v0.26.1: a visual still streaming in shows a quiet placeholder only while Blaze is replying.
    val streaming = com.blaze.assistant.agent.Agent.mood.collectAsState().value != com.blaze.assistant.agent.Mood.IDLE
    val body: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            blocks.forEachIndexed { i, b ->
                val inner = fold && !report
                when (b) {
                    is RichReplies.Block.Steps -> RichSteps(b.steps) { MdText(it) }
                    is RichReplies.Block.Image -> RichPicture(b.img, Modifier.fillMaxWidth(), ratio = 16f / 10f)
                    is RichReplies.Block.ImageText -> RichImageText(b.img, b.md) { MdText(it) }
                    is RichReplies.Block.Images -> RichImageGroup(b.imgs, scale)
                    is RichReplies.Block.Diagram -> RichDiagram(b)
                    // v0.26.1 visual answers: interactive blocks.
                    is RichReplies.Block.Chart -> RichChart(b.chart)
                    is RichReplies.Block.Choices -> RichChoices(b.choices)
                    is RichReplies.Block.Form -> RichForm(b.form)
                    is RichReplies.Block.Tool -> RichTool(b.tool)
                    is RichReplies.Block.Compare -> RichCompare(b.compare)
                    is RichReplies.Block.Table -> TableCardView(b.table)
                    is RichReplies.Block.Pending -> if (streaming) RichPending(b.kind) else when (val f = b.fallback) {
                        is RichReplies.Block.Diagram -> RichDiagram(f)
                        else -> { val n = b.seg.code.lines().size; PlainCard((b.seg.lang.ifBlank { "Code" }) + " · $n lines", b.seg.raw) { CodeText(b.seg.code) } }
                    }
                    is RichReplies.Block.Plain -> when (val s = b.seg) {
                    is AnswerCards.Seg.Text -> MdText(s.md)
                    is AnswerCards.Seg.Lists ->
                        if (inner) FoldCard("$key-$i", "List · ${s.items} items", s.md) { MdText(s.md) } else MdText(s.md)
                    is AnswerCards.Seg.Code -> {
                        val n = s.code.lines().size
                        val title = (s.lang.ifBlank { "Code" }) + " · $n lines"
                        if (inner && n > AnswerCards.CODE_LINES) FoldCard("$key-$i", title, s.raw) { CodeText(s.code) }
                        else PlainCard(title, s.raw) { CodeText(s.code) }
                    }
                    is AnswerCards.Seg.Table -> {
                        val rows = (s.rows.size - 1).coerceAtLeast(0)
                        if (inner && rows > AnswerCards.TABLE_ROWS) FoldCard("$key-$i", "Table · $rows rows", s.raw) { TableGrid(s.rows) }
                        else TableGrid(s.rows)
                    }
                    }
                }
            }
        }
    }
    if (report) FoldCard("$key-r", "Report · ${src.lines().size} lines", src, maxH = 420.dp, card = false) { body() } else body()
}

@Composable
private fun MdText(md: String) {
    val c = LocalBlaze.current
    val shown = remember(md, c.accent) { Md.render(md, c.accent, c.hair.copy(alpha = 0.35f)) }
    SelectionContainer { Text(shown, style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, lineHeight = 23.sp, color = c.body)) }
}

@Composable
private fun CodeText(code: String) {
    val c = LocalBlaze.current
    SelectionContainer {
        Text(code, modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
            softWrap = false, style = TextStyle(fontFamily = Type.Mono, fontSize = 12.5.sp, lineHeight = 18.sp, color = c.ink))
    }
}

/** v0.24.0: cards are soft rounded insets, no outline. Inside a Blaze bubble they're lighter than the bubble. */
@Composable
private fun cardFill(): Color {
    val c = LocalBlaze.current
    return if (LocalBubble.current != null) c.inset else c.faint
}

private val CardShape = RoundedCornerShape(12.dp)

@Composable
private fun TableGrid(rows: List<List<String>>) {
    val c = LocalBlaze.current
    val cols = rows.maxOfOrNull { it.size } ?: 0
    if (cols == 0) return
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column(Modifier.clip(CardShape).background(cardFill())) {
            rows.forEachIndexed { r, row ->
                Row(Modifier.background(if (r == 0) c.faint else Color.Transparent).then(if (r > 0) Modifier.hairlineTop(c.hair.copy(alpha = c.hair.alpha * 0.7f)) else Modifier)) {
                    for (k in 0 until cols) {
                        val cell = row.getOrElse(k) { "" }
                        val shown = remember(cell, c.accent) { Md.render(cell, c.accent, c.hair.copy(alpha = 0.35f)) }
                        Text(shown, modifier = Modifier.width(if (cols <= 2) 150.dp else 118.dp).padding(horizontal = 10.dp, vertical = 8.dp),
                            style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.body,
                                fontWeight = if (r == 0) FontWeight.SemiBold else FontWeight.Normal))
                    }
                }
            }
        }
    }
}

@Composable
private fun CopyLabel(raw: String) {
    val c = LocalBlaze.current
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    ActionText("Copy", color = c.accent, size = 9.sp, modifier = Modifier.clickable {
        clip.setText(AnnotatedString(raw)); Toast.makeText(ctx, "Copied as Markdown", Toast.LENGTH_SHORT).show()
    }.padding(horizontal = 6.dp, vertical = 6.dp))
}

/** A titled block that is short enough to show whole. */
@Composable
private fun PlainCard(title: String, raw: String, content: @Composable () -> Unit) {
    val c = LocalBlaze.current
    Column(Modifier.fillMaxWidth().clip(CardShape).background(cardFill())) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Label(title, color = c.sub, size = 9.sp, modifier = Modifier.weight(1f), maxLines = 1)
            CopyLabel(raw)
        }
        content()
    }
}

/** Clipped with a fade until tapped. */
@Composable
private fun FoldCard(key: String, title: String, raw: String, maxH: Dp = 220.dp, card: Boolean = true, content: @Composable () -> Unit) {
    val c = LocalBlaze.current
    var open by rememberSaveable(key) { mutableStateOf(false) }
    val fill = if (card) cardFill() else (LocalBubble.current ?: c.paper)
    Column(Modifier.fillMaxWidth().then(if (card) Modifier.clip(CardShape).background(fill) else Modifier)) {
        Row(Modifier.fillMaxWidth().padding(start = if (card) 12.dp else 0.dp, top = if (card) 2.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Label(title, color = c.sub, size = 9.sp, modifier = Modifier.weight(1f), maxLines = 1)
            CopyLabel(raw)
        }
        Box(Modifier.fillMaxWidth().then(if (open) Modifier else Modifier.heightIn(max = maxH).clipToBounds())) {
            Box(Modifier.padding(horizontal = if (card) 0.dp else 0.dp)) { content() }
            if (!open) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(56.dp)
                .background(Brush.verticalGradient(listOf(fill.copy(alpha = 0f), fill))))
        }
        Row(Modifier.fillMaxWidth().clickable { open = !open }
            .padding(horizontal = if (card) 12.dp else 0.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Label(if (open) "Show less" else "Show all", color = c.accent, size = 10.sp)
            Spacer(Modifier.width(6.dp))
            Text(if (open) "⌃" else "⌄", style = TextStyle(fontSize = 12.sp, color = c.accent))
        }
    }
}
