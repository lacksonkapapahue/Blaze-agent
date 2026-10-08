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

    fun add(fact: String): String {
        val f = fact.trim().removeSuffix(".")
        if (f.isEmpty()) return "Nothing to remember."
        if (_facts.value.any { it.equals(f, true) }) return "Already remembered."
        persist(_facts.value + f); return "Remembered: $f"
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

    fun promptBlock(): String = if (_facts.value.isEmpty()) "" else
        "\nWhat you remember about the user (use quietly, don't recite):\n" + _facts.value.joinToString("\n") { "- $it" } + "\n"

    fun tools(): List<JSONObject> = listOf(
        JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "remember")
            .put("description", "Save a lasting fact or preference about the user (name, family, likes, routines, contacts) so you know it in future chats. Use when they tell you something worth keeping or say 'remember'.")
            .put("parameters", JSONObject().put("type", "object")
                .put("properties", JSONObject().put("fact", JSONObject().put("type", "string").put("description", "Short fact, e.g. 'Sister is Mary, lives in Ndola'")))
                .put("required", JSONArray(listOf("fact"))))),
        JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "forget")
            .put("description", "Delete a remembered fact when the user asks you to forget it or it's no longer true.")
            .put("parameters", JSONObject().put("type", "object")
                .put("properties", JSONObject().put("fact", JSONObject().put("type", "string").put("description", "Words from the fact to forget")))
                .put("required", JSONArray(listOf("fact")))))
    )
}
