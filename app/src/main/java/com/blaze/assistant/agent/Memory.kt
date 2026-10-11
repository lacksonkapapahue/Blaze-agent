package com.blaze.assistant.agent

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Long-term facts Blaze keeps about the user, across chats. */
object Memory {
    private lateinit var prefs: SharedPreferences
    private val _facts = MutableStateFlow<List<String>>(emptyList())
    val facts: StateFlow<List<String>> = _facts

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("blaze", Context.MODE_PRIVATE)
        _facts.value = try {
            val a = JSONArray(prefs.getString("memory", "[]"))
            (0 until a.length()).map { a.getString(it) }
        } catch (_: Exception) { emptyList() }
    }

    private fun persist(list: List<String>) {
        _facts.value = list
        prefs.edit().putString("memory", JSONArray(list).toString()).apply()
    }

    /** Saves [fact]. [replaces] (words from an older fact) removes the fact it supersedes first. */
    fun add(fact: String, replaces: String = ""): String {
        val f = fact.trim().removeSuffix(".")
        if (f.isEmpty()) return "Nothing to remember."
        // Keys, passwords, codes and card/account numbers are never kept, even when asked (Desktop v0.7 rule).
        if (Learn.secret(f)) return "Not saved: that looks like a password, code or account number, which Blaze never keeps in memory."
        val gone = replaces.trim().takeIf { it.length >= 3 }?.let { r ->
            val q = r.lowercase(); val keep = _facts.value.filterNot { it.lowercase().contains(q) || q.contains(it.lowercase()) }
            (_facts.value.size - keep.size).also { if (it > 0) persist(keep) } } ?: 0
        if (_facts.value.any { it.equals(f, true) }) return "Already remembered."
        // A newer version of the same fact replaces the old one ("lives in Ndola" -> "lives in Lusaka").
        val w = words(f)
        val old = _facts.value.firstOrNull { o -> val ow = words(o)
            w.size >= 3 && ow.size >= 3 && (w intersect ow).size.toDouble() / (w union ow).size >= 0.6 }
        if (old != null) { persist(_facts.value.map { if (it == old) f else it }); return "Updated: $f" }
        persist(_facts.value + f); return if (gone > 0) "Updated: $f" else "Remembered: $f"
    }

    fun forget(query: String): String {
        val q = query.trim().lowercase()
        val keep = _facts.value.filterNot { it.lowercase().contains(q) || q.contains(it.lowercase()) }
        val n = _facts.value.size - keep.size
        persist(keep)
        return if (n == 0) "Nothing matched \"$query\"." else "Forgot $n item(s)."
    }

    fun remove(index: Int) { persist(_facts.value.filterIndexed { i, _ -> i != index }) }
    fun clear() = persist(emptyList())

    private fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()

    /**
     * Facts for the prompt. Small memories go in whole; big ones send only what fits this
     * request (word overlap) plus the newest few, which keeps prompts short and cheap.
     */
    fun promptBlock(query: String = ""): String {
        val all = _facts.value
        if (all.isEmpty()) return ""
        val chosen = if (all.size <= 30) all else {
            val q = words(query)
            val scored = all.withIndex().map { (i, f) -> Triple(i, f, (words(f) intersect q).size) }
            val top = scored.filter { it.third > 0 }.sortedByDescending { it.third }.take(18).map { it.first }.toSet()
            val recent = (all.size - 8 until all.size).toSet()
            all.filterIndexed { i, _ -> i in top || i in recent }
        }
        val more = all.size - chosen.size
        return "\nWhat you remember about the user (use quietly, don't recite):\n" + chosen.joinToString("\n") { "- $it" } +
            (if (more > 0) "\n($more more facts saved; call recall_memory to search them.)" else "") + "\n"
    }

    fun recall(query: String): String {
        val q = words(query)
        val hits = _facts.value.filter { (words(it) intersect q).isNotEmpty() || it.contains(query, true) }
        return if (hits.isEmpty()) "Nothing saved about \"$query\"." else hits.take(25).joinToString("\n") { "- $it" }
    }

    fun tools(): List<JSONObject> = listOf(
        JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "remember")
            .put("description", "Save a lasting fact or preference about the user (name, family, likes, routines, contacts) so you know it in future chats. Use when they tell you something worth keeping or say 'remember'.")
            .put("parameters", JSONObject().put("type", "object")
                .put("properties", JSONObject().put("fact", JSONObject().put("type", "string").put("description", "Short fact, e.g. 'Sister is Mary, lives in Ndola'"))
                    .put("replaces", JSONObject().put("type", "string").put("description", "Optional: words from an older saved fact this one replaces (it is removed)")))
                .put("required", JSONArray(listOf("fact"))))),
        JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "recall_memory")
            .put("description", "Search everything you've saved about the user (when the prompt says more facts are saved).")
            .put("parameters", JSONObject().put("type", "object")
                .put("properties", JSONObject().put("query", JSONObject().put("type", "string")))
                .put("required", JSONArray(listOf("query"))))),
        JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "forget")
            .put("description", "Delete a remembered fact when the user asks you to forget it or it's no longer true.")
            .put("parameters", JSONObject().put("type", "object")
                .put("properties", JSONObject().put("fact", JSONObject().put("type", "string").put("description", "Words from the fact to forget")))
                .put("required", JSONArray(listOf("fact")))))
    )
}
