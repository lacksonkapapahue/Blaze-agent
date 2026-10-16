package com.blaze.assistant.media

/**
 * v0.30.8: a picture Blaze is still making, kept in a reply's media list (ChatMessage.media) next to real file
 * paths, so it saves with the chat and is swapped in place for the file when it arrives. Pure (ImageSlotTest).
 *
 *   blaze-image:pending:<job>:<w>x<h>:<url-encoded prompt>
 *   blaze-image:failed:<job>:<w>x<h>:<url-encoded prompt>
 */
object ImageSlot {
    const val PREFIX = "blaze-image:"

    enum class State { PENDING, FAILED }

    data class Slot(val state: State, val job: String, val width: Int, val height: Int, val prompt: String) {
        /** Width / height for the placeholder, kept between 9:16 and 16:9 so a card never gets silly. */
        val aspect: Float get() = aspectOf(width, height)
        fun token(): String = PREFIX + (if (state == State.PENDING) "pending" else "failed") + ":" + job + ":" + width + "x" + height + ":" +
            java.net.URLEncoder.encode(prompt, "UTF-8")
        fun failed() = copy(state = State.FAILED)
    }

    fun pending(job: String, width: Int, height: Int, prompt: String) = Slot(State.PENDING, job.filter { it.isLetterOrDigit() || it == '-' }, width, height, prompt).token()

    fun isSlot(s: String) = s.startsWith(PREFIX)

    fun parse(s: String): Slot? {
        if (!isSlot(s)) return null
        val parts = s.removePrefix(PREFIX).split(':', limit = 4)
        if (parts.size < 4) return null
        val state = when (parts[0]) { "pending" -> State.PENDING; "failed" -> State.FAILED; else -> return null }
        val wh = parts[2].split('x')
        val w = wh.getOrNull(0)?.toIntOrNull() ?: 1024
        val h = wh.getOrNull(1)?.toIntOrNull() ?: 1024
        val prompt = try { java.net.URLDecoder.decode(parts[3], "UTF-8") } catch (_: Exception) { parts[3] }
        return Slot(state, parts[1], w, h, prompt)
    }

    fun aspectOf(width: Int, height: Int): Float {
        if (width <= 0 || height <= 0) return 1f
        return (width.toFloat() / height).coerceIn(9f / 16f, 16f / 9f)
    }

    /** [media] with the slot for [job] replaced by [with] (a file path, or a failed token). Unchanged if absent. */
    fun replace(media: List<String>, job: String, with: String): List<String> =
        media.map { m -> if (parse(m)?.job == job) with else m }

    fun holds(media: List<String>, job: String) = media.any { parse(it)?.job == job }

    /**
     * On loading a saved chat: a pending slot whose job isn't running any more (the app was closed mid-way) becomes
     * a failed one so it offers Try again instead of spinning forever. Real paths are kept only if [exists].
     */
    fun onLoad(media: List<String>, live: (String) -> Boolean, exists: (String) -> Boolean): List<String> = media.mapNotNull { m ->
        val s = parse(m)
        when {
            s == null -> m.takeIf(exists)
            s.state == State.PENDING && !live(s.job) -> s.failed().token()
            else -> m
        }
    }

    /**
     * How far the placeholder has "developed" after [elapsedMs]: eases towards [cap] (never finishes on its own, the
     * real picture does that). 0 → very soft, 1 → sharp.
     */
    fun reveal(elapsedMs: Long, cap: Float = 0.85f, tauMs: Float = 14_000f): Float {
        if (elapsedMs <= 0) return 0f
        return (cap * (1f - Math.exp(-(elapsedMs / tauMs).toDouble()).toFloat())).coerceIn(0f, cap)
    }

    /** Blur radius (dp) for a [reveal] value: [from] when fresh down to [to] at full reveal. */
    fun blurDp(reveal: Float, from: Float = 36f, to: Float = 6f): Float = from + (to - from) * reveal.coerceIn(0f, 1f)

    /** A done or failed picture gets a notification unless the user is looking at that chat right now. */
    fun shouldNotify(isCurrentChat: Boolean, inForeground: Boolean, overlayOpen: Boolean): Boolean =
        !(isCurrentChat && (inForeground || overlayOpen))

    /** The chat line Blaze says straight away (no service names, no steps). */
    const val ACK = "On it. I'll bring the picture here when it's ready."
    const val READY = "Your picture is ready."
    const val FAILED_TEXT = "Couldn't make this picture."
    const val FAILED_NOTE = "Your picture didn't come through. Open the chat to try again."
}
