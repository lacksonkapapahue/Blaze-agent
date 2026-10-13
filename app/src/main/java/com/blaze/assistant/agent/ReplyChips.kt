package com.blaze.assistant.agent

/**
 * v0.24.4: the line under a reply shows only what matters to a person ("Memory updated", "Skill saved",
 * "Heads-up · 14:02"). Technical parts (timings, "instant/on-device", data used, token meters, model ids,
 * "New context") are hidden unless Settings › Advanced › Show technical details (`show_tech_lines`) is on.
 * The stored meta is untouched, so turning it on shows everything again.
 */
object ReplyChips {
    const val PREF = "show_tech_lines"

    private val tech = listOf(
        Regex("\\b\\d+(?:\\.\\d+)?\\s?(?:ms|s|sec|KB|MB|GB|kB|tok|tokens)\\b", RegexOption.IGNORE_CASE),
        Regex("^[\\u26a1\\uD83C\\uDFA8]"),                                  // ⚡ instant / on-device, 🎨 free image
        Regex("\\b(instant|on-device|new context|fresh start|via |model|provider|fallback|retry \\d|lane)\\b", RegexOption.IGNORE_CASE),
        Regex("[\\w.-]+/[\\w.:-]+"),                                          // provider/model ids
        Regex("^usedup:"),
    )

    fun isTechnical(part: String): Boolean = tech.any { it.containsMatchIn(part.trim()) }

    /** The chip text to show ("" = nothing). */
    fun visible(meta: String, showTech: Boolean): String {
        if (meta.isBlank() || showTech) return meta
        // "Heads-up · 14:02" is one human chip even though it has a dot.
        if (meta.startsWith("Heads-up")) return meta
        return meta.split(" \u00b7 ").map { it.trim() }.filter { it.isNotEmpty() && !isTechnical(it) }.joinToString(" \u00b7 ")
    }

    fun showTech(): Boolean = try { Settings.bool(PREF, false) } catch (_: Throwable) { false }
}
