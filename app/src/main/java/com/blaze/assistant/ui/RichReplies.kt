package com.blaze.assistant.ui

/**
 * v0.24.9 rich replies (ChatGPT-style), pure part (RichRepliesTest): turns the Markdown of a Blaze reply into rich
 * blocks on top of [AnswerCards]: step-flow cards, a section image beside its text, image groups (up to 3 side by
 * side, 4 or more as a tilted deck you swipe through with a card flip) and inline diagrams drawn by free, keyless
 * renderers (mermaid.ink for Mermaid, kroki.io for Graphviz / PlantUML / D2).
 */
object RichReplies {
    data class Img(val url: String, val alt: String)

    sealed class Block {
        /** Anything AnswerCards already draws (text, lists, code, tables). */
        data class Plain(val seg: AnswerCards.Seg) : Block()
        data class Steps(val steps: List<Step>) : Block()
        data class Image(val img: Img) : Block()
        /** A section picture beside its paragraph (picture on the left). */
        data class ImageText(val img: Img, val md: String) : Block()
        data class Images(val imgs: List<Img>) : Block()
        data class Diagram(val kind: String, val code: String, val url: String) : Block()
    }

    data class Step(val title: String, val body: String)

    /** How an image group is laid out. */
    enum class Layout { ROW, DECK }
    const val ROW_MAX = 3
    fun layout(n: Int): Layout = if (n <= ROW_MAX) Layout.ROW else Layout.DECK

    /** The static system-prompt lines that tell the model these formats exist (kept short; cached prefix). */
    const val PROMPT = "\n\nRICH REPLIES: the chat draws some Markdown richly. Use it only when it really helps. " +
        "A how-to with 2+ steps: a numbered list where each item starts with a **bold title** (\"1. **Open Settings** then ...\"); it becomes a step-flow card. " +
        "A picture for a section: put ![alt](https url) on its own line right before that paragraph; it sits beside the text. " +
        "Several pictures: put the image lines one after another (up to 3 sit side by side; 4 or more become a swipeable deck). " +
        "Use only real image URLs you found (Wikimedia Commons, Openverse, the source page); never invent one. " +
        "A diagram (flow, sequence, mind map, org chart): a ```mermaid code block (or ```graphviz / ```plantuml); it's drawn inline."

    private val imgLine = Regex("""^\s*!\[([^\]]*)]\(\s*<?((?:https?://|/)[^\s)>]+)>?(?:\s+"[^"]*")?\s*\)\s*$""")
    private val stepRe = Regex("""^\s*(\d+)[.)]\s+\*\*(.+?)\*\*\s*[:.\u2014\u2013-]?\s*(.*)$""")
    private val diagramLangs = mapOf(
        "mermaid" to "mermaid", "graphviz" to "graphviz", "dot" to "graphviz", "plantuml" to "plantuml", "puml" to "plantuml", "d2" to "d2")

    fun image(line: String): Img? = imgLine.matchEntire(line)?.let { Img(it.groupValues[2], it.groupValues[1].trim()) }

    /** Splits AnswerCards' segments into rich blocks. Code blocks in a diagram language become diagrams (not in Private). */
    fun expand(segs: List<AnswerCards.Seg>, private: Boolean = false): List<Block> = segs.flatMap { s ->
        when (s) {
            is AnswerCards.Seg.Text -> text(s.md)
            is AnswerCards.Seg.Lists -> text(s.md, list = s)
            is AnswerCards.Seg.Code -> {
                val kind = diagramLangs[s.lang.trim().lowercase()]
                if (kind != null && !private && s.code.isNotBlank()) listOf(Block.Diagram(kind, s.code, diagramUrl(kind, s.code)))
                else listOf(Block.Plain(s))
            }
            else -> listOf(Block.Plain(s))
        }
    }

    /** Paragraph-level pass over a text segment: image runs, image + paragraph, step lists, the rest as text. */
    fun text(md: String, list: AnswerCards.Seg.Lists? = null): List<Block> {
        val lines = md.split('\n')
        if (lines.none { image(it) != null } && lines.none { stepRe.matches(it) }) return listOf(Block.Plain(list ?: AnswerCards.Seg.Text(md)))
        val out = ArrayList<Block>()
        val buf = StringBuilder()
        fun flush() { val t = buf.toString().trim('\n'); if (t.isNotBlank()) out += Block.Plain(AnswerCards.Seg.Text(t)); buf.clear() }
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            val img = image(l)
            if (img != null) {
                flush()
                val run = arrayListOf(img)
                var j = i + 1
                while (j < lines.size) {
                    val n = image(lines[j])
                    if (n != null) { run += n; j++ }
                    else if (lines[j].isBlank() && j + 1 < lines.size && image(lines[j + 1]) != null) j++
                    else break
                }
                if (run.size == 1) {
                    // A lone picture right before a paragraph sits beside it.
                    var k = j
                    while (k < lines.size && lines[k].isBlank()) k++
                    val para = StringBuilder()
                    while (k < lines.size && lines[k].isNotBlank() && image(lines[k]) == null && !stepRe.matches(lines[k]) && !lines[k].trimStart().startsWith("#")) {
                        para.append(lines[k]).append('\n'); k++
                    }
                    if (para.isNotBlank()) { out += Block.ImageText(img, para.toString().trim()); i = k; continue }
                    out += Block.Image(img)
                } else out += Block.Images(run)
                i = j; continue
            }
            if (stepRe.matches(l)) {
                val steps = ArrayList<Step>()
                var j = i
                while (j < lines.size) {
                    val m = stepRe.matchEntire(lines[j])
                    if (m != null) { steps += Step(m.groupValues[2].trim(), m.groupValues[3].trim()); j++; continue }
                    // Indented continuation lines belong to the step above.
                    if (steps.isNotEmpty() && lines[j].startsWith("  ") && lines[j].isNotBlank()) {
                        val last = steps.removeAt(steps.size - 1)
                        steps += last.copy(body = (last.body + " " + lines[j].trim()).trim()); j++; continue
                    }
                    break
                }
                if (steps.size >= 2) { flush(); out += Block.Steps(steps); i = j; continue }
            }
            buf.append(l).append('\n'); i++
        }
        flush()
        return out
    }

    // ---------- diagrams (free, keyless renderers) ----------
    fun diagramUrl(kind: String, code: String): String = when (kind) {
        "mermaid" -> "https://mermaid.ink/img/" + b64url(code.trim().toByteArray()) + "?type=png&bgColor=!white"
        else -> "https://kroki.io/$kind/png/" + b64url(deflate(code.trim().toByteArray()))
    }

    private fun b64url(b: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    fun deflate(b: ByteArray): ByteArray {
        val d = java.util.zip.Deflater(9)
        d.setInput(b); d.finish()
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end(); return out.toByteArray()
    }

    // ---------- the deck ----------
    /** Each card under the top one tilts a little, alternating sides; the top card is straight. Degrees. */
    fun tilt(depth: Int): Float = if (depth <= 0) 0f else (if (depth % 2 == 1) -1f else 1f) * minOf(2.5f + depth * 1.5f, 7f)
    /** How far each card under the top one peeks out (dp, down and sideways). */
    fun peek(depth: Int): Float = minOf(depth, 3) * 7f
    /** Cards drawn under the top one (the rest are hidden). */
    const val DECK_SHOWN = 3
    /** A swipe of at least this fraction of the card width turns the card. */
    const val SWIPE_FRACTION = 0.22f
    const val FLIP_MS = 420

    fun next(index: Int, n: Int, dir: Int): Int = if (n <= 0) 0 else ((index + dir) % n + n) % n

    /** Did a horizontal drag of [dx] px on a card [widthPx] wide turn it? -1 back, +1 forward, 0 no. */
    fun swipeDir(dx: Float, widthPx: Float): Int = when {
        widthPx <= 0f -> 0
        dx <= -SWIPE_FRACTION * widthPx -> 1
        dx >= SWIPE_FRACTION * widthPx -> -1
        else -> 0
    }

    /**
     * Card flip over t = 0..1: the old card turns 0 → 90° (t ≤ 0.5), then the new one -90 → 0°. Returns (angle,
     * showingNew). [dir] = +1 turns to the left.
     */
    fun flip(t: Float, dir: Int): Pair<Float, Boolean> {
        val k = t.coerceIn(0f, 1f); val s = if (dir >= 0) -1f else 1f
        return if (k <= 0.5f) (s * 180f * k) to false else (s * (180f * k - 180f)) to true
    }

    /** Android's "Remove animations" (animator duration scale 0) turns the flip into an instant swap. */
    fun animationsOff(animatorScale: Float): Boolean = animatorScale <= 0f

    /** "2 / 5" for the deck's counter and TalkBack. */
    fun counter(index: Int, n: Int): String = "${index + 1} / $n"
}
