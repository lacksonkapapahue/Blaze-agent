package com.blaze.assistant.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Projects on the phone: a name, optional instructions, and the chats that belong to it.
 * Kept in prefs ("projects_json") so updates never lose them. Chats themselves stay in ChatStore;
 * a project only lists their ids, so deleting a project never deletes a chat.
 */
data class Project(val id: String, val name: String, val instructions: String = "", val chats: List<String> = emptyList(), val updated: Long = 0L)

object Projects {
    private const val KEY = "projects_json"
    private val _all = MutableStateFlow<List<Project>>(emptyList())
    val all: StateFlow<List<Project>> = _all
    /** The project new chats go into ("" = plain chat). */
    val current = MutableStateFlow("")

    fun load() { _all.value = try { parse(Settings.str(KEY, "[]")) } catch (_: Exception) { emptyList() } }

    fun parse(json: String): List<Project> {
        val a = JSONArray(json.ifBlank { "[]" })
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val ch = o.optJSONArray("chats") ?: JSONArray()
            Project(o.optString("id"), o.optString("name"), o.optString("instructions"),
                (0 until ch.length()).map { ch.optString(it) }.filter { it.isNotBlank() }, o.optLong("updated"))
        }.filter { it.id.isNotBlank() }
    }

    fun toJson(list: List<Project>): String = JSONArray().apply {
        list.forEach { p -> put(JSONObject().put("id", p.id).put("name", p.name).put("instructions", p.instructions)
            .put("chats", JSONArray(p.chats)).put("updated", p.updated)) }
    }.toString()

    private fun save(list: List<Project>) { _all.value = list; try { Settings.setStr(KEY, toJson(list)) } catch (_: Exception) { } }

    fun create(name: String): Project {
        val p = Project("p" + System.currentTimeMillis().toString(36), name.trim().ifBlank { "New project" }, updated = System.currentTimeMillis())
        save(listOf(p) + _all.value); return p
    }
    fun rename(id: String, name: String) = save(_all.value.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it })
    fun setInstructions(id: String, text: String) = save(_all.value.map { if (it.id == id) it.copy(instructions = text) else it })
    fun delete(id: String) { save(_all.value.filter { it.id != id }); if (current.value == id) current.value = "" }

    /** Puts [chatId] in [projectId] ("" = take it out of every project). */
    fun assign(chatId: String, projectId: String) {
        if (chatId.isBlank()) return
        save(_all.value.map { p ->
            val without = p.chats - chatId
            if (p.id == projectId) p.copy(chats = listOf(chatId) + without, updated = System.currentTimeMillis()) else p.copy(chats = without)
        })
    }

    fun of(chatId: String): Project? = _all.value.firstOrNull { chatId in it.chats }
    fun get(id: String): Project? = _all.value.firstOrNull { it.id == id }

    /** One line for the system prompt when the open chat is in a project with instructions. */
    fun promptLine(chatId: String): String {
        val p = of(chatId) ?: return ""
        return "\nPROJECT: ${p.name}." + if (p.instructions.isNotBlank()) " Project instructions: ${p.instructions.take(1200)}" else ""
    }
}
