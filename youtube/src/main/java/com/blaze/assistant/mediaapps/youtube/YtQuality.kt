package com.blaze.assistant.mediaapps.youtube

import com.blaze.assistant.mediaapps.store.StoreText
import com.blaze.assistant.mediaapps.store.Choice
import com.blaze.assistant.mediaapps.store.StoreFormat

/**
 * v0.30.1: which streams a "Download" choice fetches. Pure (QualityTest).
 *
 * Audio: every audio-only stream, labelled Low / Medium / High by bitrate, AAC (.m4a) and Opus (.opus) as the video
 * has them. Video: 360p / 480p / 720p / 1080p. A progressive (audio+video in one file) stream is used when YouTube
 * still has one at that height (usually only 360p); otherwise the video-only stream plus the best matching audio
 * stream, merged on the phone (H.264 + AAC into .mp4 preferred; VP9 + Opus into .webm only when there's no H.264 at
 * that height). 60 fps is preferred only when the 30 fps stream is missing, to keep files small on low-end phones.
 */
object YtQuality {
    val heights = listOf(360, 480, 720, 1080)

    fun tier(kbps: Int): String = when { kbps <= 64 -> "Low"; kbps <= 100 -> "Medium"; else -> "High" }

    private fun codecName(f: StoreFormat) = when {
        f.codecs.startsWith("opus") -> "Opus"
        f.codecs.startsWith("mp4a") -> "AAC"
        f.codecs.startsWith("vorbis") -> "Vorbis"
        else -> f.codecs.substringBefore('.').uppercase()
    }

    /** Estimated size when YouTube doesn't say (bitrate × length). */
    fun estimate(f: StoreFormat, lengthSec: Int): Long = if (f.contentLength > 0) f.contentLength else f.bitrate.toLong() * lengthSec / 8

    fun audioChoices(formats: List<StoreFormat>, lengthSec: Int): List<Choice> =
        formats.filter { it.hasAudio && !it.hasVideo && (it.codecs.startsWith("mp4a") || it.codecs.startsWith("opus")) }
            .sortedWith(compareBy<StoreFormat> { it.bitrate }.thenBy { if (it.codecs.startsWith("opus")) 0 else 1 })
            .map { f ->
                val ext = if (f.codecs.startsWith("opus")) "opus" else "m4a"
                val bytes = estimate(f, lengthSec)
                Choice("a${f.key}", "${tier(f.kbps)} · ${codecName(f)} ${f.kbps} kbps", listOf(ext, StoreText.size(bytes)).filter { it.isNotEmpty() }.joinToString(" · "),
                    listOf(f.key), ext, bytes, kbps = f.kbps)
            }

    /** The default audio pick: High AAC if there is one (plays everywhere), else the best stream. */
    fun defaultAudio(choices: List<Choice>): Choice? =
        choices.filter { it.ext == "m4a" }.maxByOrNull { it.kbps } ?: choices.maxByOrNull { it.kbps }

    /** Best audio stream to merge with a video-only stream in [container]. */
    fun audioFor(formats: List<StoreFormat>, container: String): StoreFormat? {
        val audio = formats.filter { it.hasAudio && !it.hasVideo }
        val want = if (container == "webm") "opus" else "mp4a"
        return audio.filter { it.codecs.startsWith(want) }.maxByOrNull { it.bitrate }
    }

    fun videoChoices(formats: List<StoreFormat>, lengthSec: Int): List<Choice> {
        val out = ArrayList<Choice>()
        for (h in heights) {
            val prog = formats.filter { it.muxed && it.height == h && it.container == "mp4" }.maxByOrNull { it.bitrate }
            if (prog != null) {
                val b = estimate(prog, lengthSec)
                out += Choice("p${prog.key}", "${h}p", listOf("mp4", StoreText.size(b), "one file").filter { it.isNotEmpty() }.joinToString(" · "),
                    listOf(prog.key), "mp4", b, height = h)
                continue
            }
            val only = formats.filter { it.hasVideo && !it.hasAudio && it.height == h }
            fun best(c: String) = only.filter { it.codecs.startsWith(c) }.sortedWith(compareBy<StoreFormat> { if (it.fps > 30) 1 else 0 }.thenByDescending { it.bitrate }).firstOrNull()
            val v = best("avc1") ?: best("vp09")?.takeIf { audioFor(formats, "webm") != null } ?: best("vp9")?.takeIf { audioFor(formats, "webm") != null } ?: continue
            val container = if (v.codecs.startsWith("avc1")) "mp4" else "webm"
            val a = audioFor(formats, container) ?: continue
            val b = estimate(v, lengthSec) + estimate(a, lengthSec)
            out += Choice("v${v.key}+${a.key}", "${h}p" + (if (v.fps > 30) "60" else ""),
                listOf(container, StoreText.size(b)).filter { it.isNotEmpty() }.joinToString(" · "),
                listOf(v.key, a.key), container, b, height = h)
        }
        return out
    }

    /** The default video pick: 720p if there, else the biggest at or under 720p, else the smallest. */
    fun defaultVideo(choices: List<Choice>): Choice? =
        choices.firstOrNull { it.height == 720 } ?: choices.filter { it.height <= 720 }.maxByOrNull { it.height } ?: choices.minByOrNull { it.height }

    /** For streaming (Watch / Play now): progressive if any, else the video-only + audio pair at ≤ [maxHeight]. */
    fun streamPick(formats: List<StoreFormat>, maxHeight: Int = 720): List<StoreFormat> {
        val v = videoChoices(formats, 0).filter { it.height <= maxHeight }.maxByOrNull { it.height } ?: return emptyList()
        return v.keys.mapNotNull { t -> formats.firstOrNull { it.key == t } }
    }
}
