package com.blaze.assistant.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ChatMeta(val id: String, val title: String, val updated: Long, val pinned: Boolean, val preview: String)

/** Saved conversations, one JSON file each in filesDir/chats. Survives app updates. */
object ChatStore {
    private lateinit var dir: File

    fun init(ctx: Context) { dir = File(ctx.filesDir, "chats").apply { mkdirs() } }

    private fun file(id: String) = File(dir, "$id.json")

    fun list(): List<ChatMeta> = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
        .mapNotNull { f ->
            try {
                val o = JSONObject(f.readText())
                ChatMeta(o.getString("id"), o.optString("title", "Chat"), o.optLong("updated"),
                    o.optBoolean("pinned"), o.optString("preview"))
            } catch (_: Exception) { null }
        }
        .sortedWith(compareByDescending<ChatMeta> { it.pinned }.thenByDescending { it.updated })

    fun search(q: String): List<ChatMeta> {
        val needle = q.trim().lowercase()
        if (needle.isEmpty()) return list()
        return list().filter { m ->
            m.title.lowercase().contains(needle) || try {
                val msgs = JSONObject(file(m.id).readText()).optJSONArray("messages") ?: JSONArray()
                (0 until msgs.length()).any { msgs.getJSONObject(it).optString("text").lowercase().contains(needle) }
            } catch (_: Exception) { false }
        }
    }

    fun load(id: String): JSONObject? = try { JSONObject(file(id).readText()) } catch (_: Exception) { null }

    fun save(o: JSONObject) {
        try {
            val f = file(o.getString("id"))
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(o.toString()); tmp.renameTo(f)
        } catch (_: Exception) { }
    }

    fun rename(id: String, title: String) = load(id)?.let { save(it.put("title", title.trim().ifEmpty { "Chat" })) }
    fun setPinned(id: String, pinned: Boolean) = load(id)?.let { save(it.put("pinned", pinned)) }
    fun delete(id: String) { file(id).delete() }
    fun deleteAll() { dir.listFiles()?.forEach { it.delete() } }
}
