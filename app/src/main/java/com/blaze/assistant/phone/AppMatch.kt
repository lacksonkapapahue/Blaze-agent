package com.blaze.assistant.phone

/**
 * v0.28.9: "open it for me" (about a gallery picture) opened the game "Juno: New Origins Complete Edition",
 * because the fast lane asked open_app for "it" and "Ed-it-ion" contains "it". Two rules now:
 * a command that points back at something earlier ("it", "that", "the picture", "again") never takes the
 * fast lane (the full agent knows what "it" is), and app names only match on whole words.
 */
object AppMatch {
    private val pronoun = Regex("""^(?:it|that|this|them|those|these|one|that one|this one|the one|same|the same|again|him|her|me|you|there|here)$""")
    private val referent = Regex("""\b(it|that|this|them|those|these|that one|this one|the one|same one|the picture|the image|the photo|the file|the link|the pdf|the video|the doc(ument)?|again)\b""", RegexOption.IGNORE_CASE)
    private val filler = Regex("""\b(for me|for us|please|pls|now|up|real quick|quickly|right now|again|app|application)\b""")

    /** True when a short command leans on what came before ("open it for me", "send that", "show the picture"). */
    fun refersBack(text: String): Boolean = referent.containsMatchIn(text.trim())

    /** The app name with filler dropped, or "" when nothing nameable is left ("it for me" → ""). */
    fun clean(name: String): String {
        val q = filler.replace(name.lowercase(), " ").replace(Regex("""^(the|my)\s+"""), "").replace(Regex("\\s+"), " ").trim()
        return if (q.length < 2 || pronoun.matches(q)) "" else q
    }

    private fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9+]+")).filter { it.isNotEmpty() }

    /** 0 exact, 1 label starts with it, 2 whole words inside the label, 3 label's words inside the ask, 4 package, null no match. */
    fun score(name: String, label: String, pkg: String): Int? {
        val q = clean(name); if (q.isEmpty()) return null
        val l = label.lowercase().trim()
        val qw = words(q); val lw = words(l)
        if (qw.isEmpty() || lw.isEmpty()) return null
        return when {
            l == q || lw == qw -> 0
            lw.take(qw.size) == qw -> 1
            lw.windowed(qw.size).any { it == qw } -> 2
            lw.joinToString("").length >= 3 && qw.windowed(lw.size).any { it == lw } -> 3
            q.replace(" ", "").length >= 4 && pkg.lowercase().split('.').any { it == q.replace(" ", "") } -> 4
            else -> null
        }
    }
}
