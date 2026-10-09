package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Speed readout (speed plan step 14): every cloud turn logs time to first token, total time,
 * steps, how many tools were sent and how big the prompt was, so each speed change can be
 * measured on whatever phone Blaze runs on.
 */
object SpeedLog {
    data class Entry(val at: Long, val firstMs: Long, val totalMs: Long, val steps: Int, val tools: Int, val promptChars: Int, val route: String)

    private const val KEY = "speed_log"
    private const val MAX = 50

    fun add(e: Entry) = try {
        val a = try { JSONArray(Settings.str(KEY, "[]")) } catch (_: Exception) { JSONArray() }
        a.put(JSONObject().put("at", e.at).put("first", e.firstMs).put("total", e.totalMs).put("steps", e.steps)
            .put("tools", e.tools).put("chars", e.promptChars).put("route", e.route))
        while (a.length() > MAX) a.remove(0)
        Settings.setStr(KEY, a.toString())
    } catch (_: Exception) { }

    fun all(): List<Entry> = try {
        val a = JSONArray(Settings.str(KEY, "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Entry(it.optLong("at"), it.optLong("first"), it.optLong("total"), it.optInt("steps"), it.optInt("tools"), it.optInt("chars"), it.optString("route"))
        }
    } catch (_: Exception) { emptyList() }

    /** One line, e.g. "last 20 turns: first word 1.4 s, done 3.1 s, 18 tools, 9k chars". */
    fun summary(n: Int = 20): String {
        val l = all().takeLast(n)
        if (l.isEmpty()) return "No timed turns yet."
        fun med(xs: List<Long>) = xs.sorted()[xs.size / 2]
        return "last ${l.size} turns (median): first word %.1f s, done %.1f s, %d tools sent, %dk prompt chars".format(
            med(l.map { it.firstMs }) / 1000.0, med(l.map { it.totalMs }) / 1000.0,
            med(l.map { it.tools.toLong() }), med(l.map { it.promptChars.toLong() }) / 1000)
    }

    fun clear() = Settings.setStr(KEY, "[]")
}
