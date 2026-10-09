package com.blaze.assistant.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withStyle

/**
 * Small Markdown renderer for chat replies: **bold**, *italic*, `code`, ```blocks```, # headings,
 * bullets, numbered lists, > quotes, [links](url), ~~strike~~ and --- rules. Symbols never show raw.
 */
object Md {
    private val inline = Regex("""\*\*(.+?)\*\*|__(.+?)__|(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?!\w)|(?<![\w_])_(?!\s)(.+?)(?<!\s)_(?![\w])|`([^`]+)`|~~(.+?)~~|\[([^\]]+)]\((https?://[^)\s]+)\)""")

    fun render(src: String, accent: Color, codeBg: Color): AnnotatedString = buildAnnotatedString {
        val lines = src.replace("\r", "").split('\n')
        var inCode = false
        lines.forEachIndexed { i, raw ->
            if (raw.trimStart().startsWith("```")) { inCode = !inCode; return@forEachIndexed }
            if (inCode) {
                withStyle(SpanStyle(fontFamily = Type.Mono, fontSize = 13.sp, background = codeBg)) { append(raw) }
                if (i < lines.lastIndex) append('\n'); return@forEachIndexed
            }
            var line = raw
            val heading = Regex("^\\s{0,3}(#{1,6})\\s+(.*)$").find(line)
            when {
                heading != null -> {
                    val level = heading.groupValues[1].length
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = if (level <= 2) 17.sp else 16.sp)) {
                        inlines(heading.groupValues[2].trimEnd('#', ' '), accent, codeBg) }
                }
                Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$").matches(line) -> append("──────────")
                else -> {
                    val bullet = Regex("^(\\s*)[-*+•]\\s+(.*)$").find(line)
                    val num = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$").find(line)
                    val quote = Regex("^\\s*>\\s?(.*)$").find(line)
                    when {
                        bullet != null -> { append("  ".repeat(bullet.groupValues[1].length / 2) + "•  "); inlines(bullet.groupValues[2], accent, codeBg) }
                        num != null -> { append("  ".repeat(num.groupValues[1].length / 2) + num.groupValues[2] + ".  "); inlines(num.groupValues[3], accent, codeBg) }
                        quote != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("│ "); inlines(quote.groupValues[1], accent, codeBg) }
                        else -> inlines(line, accent, codeBg)
                    }
                }
            }
            if (i < lines.lastIndex) append('\n')
        }
    }

    private fun AnnotatedString.Builder.inlines(s: String, accent: Color, codeBg: Color) {
        var last = 0
        for (m in inline.findAll(s)) {
            append(s.substring(last, m.range.first))
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { inlines(g[1], accent, codeBg) }
                g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { inlines(g[2], accent, codeBg) }
                g[3].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { inlines(g[3], accent, codeBg) }
                g[4].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { inlines(g[4], accent, codeBg) }
                g[5].isNotEmpty() -> withStyle(SpanStyle(fontFamily = Type.Mono, fontSize = 13.sp, background = codeBg)) { append(" " + g[5] + " ") }
                g[6].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(g[6]) }
                else -> {
                    pushStringAnnotation("URL", g[8])
                    withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) { append(g[7]) }
                    pop()
                }
            }
            last = m.range.last + 1
        }
        append(s.substring(last))
    }

    /** Plain text for copy, speech and notifications. */
    fun plain(src: String): String = render(src, Color.Unspecified, Color.Unspecified).text
        .replace("──────────", "").replace("│ ", "")
}
