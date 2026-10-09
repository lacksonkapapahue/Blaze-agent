package com.blaze.assistant.agent

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.blaze.assistant.proactive.Triggers
import com.blaze.assistant.routine.Routines
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Process registry: everything Blaze creates that keeps living after the chat (alarms, timers,
 * routines, triggers, calendar events, background jobs) gets a short unique ID like R4 or A12.
 *
 * The model works by ID only. `processes` returns one short line per item (ID, kind, title, when),
 * and `process` acts on an ID (pause, resume, delete, rename, reschedule). The full stored details
 * are only read with action=details, when the user actually asks for specifics, so tracking costs
 * a handful of tokens no matter how much each process holds.
 *
 * Routines and triggers keep their own stores; the registry mirrors them (sync) so anything the
 * user deletes in the UI disappears here too.
 */
object Processes {
    data class P(
        val id: String, val kind: String, val title: String, val ref: String,
        val created: Long, val due: Long = 0, val status: String = "active", val props: JSONObject = JSONObject()
    )

    private val prefix = mapOf("alarm" to "A", "timer" to "T", "routine" to "R", "trigger" to "G",
        "event" to "E", "gevent" to "C", "job" to "J", "watcher" to "W")
    val kinds get() = prefix.keys

    private lateinit var app: Context
    private lateinit var file: File
    private val counters = HashMap<String, Int>()
    private val _all = MutableStateFlow<List<P>>(emptyList())
    val all: StateFlow<List<P>> = _all

    fun init(ctx: Context) {
        app = ctx.applicationContext
        file = File(app.filesDir, "processes.json")
        try {
            val o = JSONObject(file.readText())
            o.optJSONObject("next")?.let { n -> for (k in n.keys()) counters[k] = n.getInt(k) }
            val a = o.optJSONArray("items") ?: JSONArray()
            _all.value = (0 until a.length()).map { a.getJSONObject(it) }.map {
                P(it.getString("id"), it.getString("kind"), it.optString("title"), it.optString("ref"), it.optLong("created"),
                    it.optLong("due"), it.optString("status", "active"), it.optJSONObject("props") ?: JSONObject())
            }
        } catch (_: Exception) { }
    }

    private fun ok() = ::file.isInitialized

    @Synchronized private fun save(list: List<P>) {
        _all.value = list
        if (!ok()) return
        try {
            file.writeText(JSONObject()
                .put("next", JSONObject(counters as Map<*, *>))
                .put("items", JSONArray(list.map {
                    JSONObject().put("id", it.id).put("kind", it.kind).put("title", it.title).put("ref", it.ref)
                        .put("created", it.created).put("due", it.due).put("status", it.status).put("props", it.props)
                })).toString())
        } catch (_: Exception) { }
    }

    /** Reserve the next ID for a kind (so it can go into an alarm label before the alarm exists). */
    @Synchronized fun nextId(kind: String): String {
        val n = (counters[kind] ?: 0) + 1
        counters[kind] = n
        return (prefix[kind] ?: "X") + n
    }

    /** Record something Blaze created. Returns its ID. */
    @Synchronized fun register(kind: String, title: String, ref: String, due: Long = 0, props: JSONObject = JSONObject(), id: String? = null): String {
        if (!ok()) return ""
        val pid = id ?: nextId(kind)
        save(_all.value.filterNot { it.kind == kind && it.ref == ref && ref.isNotBlank() } +
            P(pid, kind, title.take(60), ref, System.currentTimeMillis(), due, "active", props))
        return pid
    }

    fun idFor(kind: String, ref: String): String? { sync(); return _all.value.firstOrNull { it.kind == kind && it.ref == ref }?.id }

    private fun update(id: String, f: (P) -> P) = save(_all.value.map { if (it.id == id) f(it) else it })
    private fun drop(id: String) = save(_all.value.filterNot { it.id == id })

    /** Mirror routines/triggers, and age alarms/timers/events whose time has passed. */
    @Synchronized fun sync() {
        if (!ok()) return
        var list = _all.value.toMutableList()
        val now = System.currentTimeMillis()
        try {
            val routines = Routines.all.value
            list.removeAll { it.kind == "routine" && routines.none { r -> r.id.toString() == it.ref } }
            for (r in routines) {
                val st = if (r.enabled) "active" else "paused"
                val props = JSONObject().put("prompt", r.prompt).put("time", r.timeLabel()).put("days", r.daysLabel())
                val i = list.indexOfFirst { it.kind == "routine" && it.ref == r.id.toString() }
                if (i < 0) list += P(nextId("routine"), "routine", r.name, r.id.toString(), now, 0, st, props)
                else list[i] = list[i].copy(title = r.name, status = st, props = props)
            }
        } catch (_: Exception) { }
        try {
            val triggers = Triggers.all.value
            list.removeAll { it.kind == "trigger" && triggers.none { t -> t.id.toString() == it.ref } }
            for (t in triggers) {
                val st = if (t.enabled) "active" else "paused"
                val props = JSONObject().put("event", t.event).put("match", t.match).put("prompt", t.prompt)
                val i = list.indexOfFirst { it.kind == "trigger" && it.ref == t.id.toString() }
                if (i < 0) list += P(nextId("trigger"), "trigger", t.name, t.id.toString(), now, 0, st, props)
                else list[i] = list[i].copy(title = t.name, status = st, props = props)
            }
        } catch (_: Exception) { }
        try {
            val ws = com.blaze.assistant.phone.Watchers.states()
            list.removeAll { it.kind == "watcher" && it.ref !in ws }
            list = list.map { if (it.kind == "watcher") it.copy(status = if (ws[it.ref] == true) "active" else "paused") else it }.toMutableList()
        } catch (_: Exception) { }
        list = list.map {
            when {
                it.status == "active" && it.kind == "timer" && it.due in 1..now -> it.copy(status = "finished")
                it.status == "active" && it.kind == "alarm" && it.due in 1..now && !it.props.optBoolean("repeats") -> it.copy(status = "rang")
                it.status == "active" && (it.kind == "event" || it.kind == "gevent") && it.due in 1..now -> it.copy(status = "past")
                it.kind == "job" && it.status == "active" && !com.blaze.assistant.terminal.Terminal.jobAlive(it.ref.toIntOrNull() ?: -1) -> it.copy(status = "ended")
                else -> it
            }
        }.toMutableList()
        // Finished one-offs are kept a day so "what did you set earlier" still works, then pruned.
        list.removeAll { it.status in setOf("finished", "rang", "ended", "deleted") && now - maxOf(it.due, it.created) > 86_400_000L }
        list.removeAll { it.status == "past" && now - it.due > 7 * 86_400_000L }
        save(list)
    }

    private val fmt = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())
    private fun whenOf(p: P): String = when (p.kind) {
        "routine" -> "${p.props.optString("time")} ${p.props.optString("days")}"
        "trigger" -> "on ${p.props.optString("event")}"
        "watcher" -> "sees ${p.props.optString("when_visible")}"
        "job" -> ""
        else -> if (p.due > 0) fmt.format(Date(p.due)) else ""
    }

    /** One short line per process. This is all the model normally sees. */
    fun list(kind: String = "", status: String = ""): String {
        sync()
        val l = _all.value.filter { (kind.isBlank() || it.kind == kind) && (status.isBlank() || it.status == status || (status == "live" && it.status in setOf("active", "paused"))) }
        if (l.isEmpty()) return if (kind.isBlank()) "Nothing tracked." else "No ${kind}s tracked."
        return l.takeLast(60).joinToString("\n") { p ->
            listOf(p.id, p.kind, p.title, whenOf(p), p.status).filter { it.isNotBlank() }.joinToString(" · ")
        }
    }

    /** Short count line for the system prompt, e.g. "3 routines, 1 alarm". */
    fun countLine(): String = try {
        val live = _all.value.filter { it.status == "active" || it.status == "paused" }
        if (live.isEmpty()) "" else live.groupBy { it.kind }.entries.joinToString(", ") { "${it.value.size} ${it.key}${if (it.value.size > 1) "s" else ""}" }
    } catch (_: Exception) { "" }

    suspend fun act(ctx: Context, idRaw: String, action: String, value: String): String {
        sync()
        val id = idRaw.trim().uppercase().removePrefix("#")
        val p = _all.value.firstOrNull { it.id == id } ?: return "No process $id. Call processes to see the IDs."
        return when (action) {
            "details" -> JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("status", p.status)
                .put("created", fmt.format(Date(p.created))).apply { if (p.due > 0) put("when", fmt.format(Date(p.due))) }
                .put("details", p.props).toString(1)
            "delete" -> delete(ctx, p)
            "pause", "resume" -> pause(p, action == "pause")
            "rename" -> rename(p, value)
            "reschedule" -> reschedule(ctx, p, value)
            else -> "Unknown action $action."
        }
    }

    private suspend fun delete(ctx: Context, p: P): String {
        val msg = when (p.kind) {
            "routine" -> { Routines.delete(p.ref.toInt()); "Deleted routine ${p.id} \u201c${p.title}\u201d." }
            "trigger" -> { Triggers.remove(p.ref.toLong()); "Deleted trigger ${p.id} \u201c${p.title}\u201d." }
            "job" -> com.blaze.assistant.terminal.Terminal.stopJob(p.ref.toInt())
            "watcher" -> { com.blaze.assistant.phone.Watchers.remove(p.ref); "Deleted watcher ${p.id}." }
            "event" -> try {
                val n = ctx.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, p.ref.toLong()), null, null)
                if (n > 0) "Deleted calendar event ${p.id} \u201c${p.title}\u201d." else "That event was already gone from the calendar."
            } catch (e: Exception) { "Couldn't delete the event: ${e.message}" }
            "gevent" -> com.blaze.assistant.connect.Connectors.gcalDelete(p.ref)
            "alarm" -> dismissAlarm(ctx, p)
            "timer" -> {
                if (p.status == "active" && android.os.Build.VERSION.SDK_INT >= 28) try {
                    ctx.startActivity(Intent(AlarmClock.ACTION_DISMISS_TIMER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { }
                "Timer ${p.id} cancelled."
            }
            else -> "Removed ${p.id}."
        }
        if (!msg.startsWith("Couldn't") && !msg.startsWith("Error")) drop(p.id)
        return msg
    }

    private fun dismissAlarm(ctx: Context, p: P): String {
        val label = p.props.optString("label")
        return try {
            ctx.startActivity(Intent(AlarmClock.ACTION_DISMISS_ALARM)
                .putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Asked the clock app to delete alarm ${p.id} (label \u201c$label\u201d). If it shows a list, it's the one with that label."
        } catch (_: Exception) {
            try { ctx.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
            "This clock app can't delete alarms for other apps, so I opened the alarm list: delete the one labelled \u201c$label\u201d."
        }
    }

    private fun pause(p: P, off: Boolean): String = when (p.kind) {
        "routine" -> { Routines.setEnabled(p.ref.toInt(), !off); sync(); "Routine ${p.id} ${if (off) "paused" else "resumed"}." }
        "trigger" -> {
            val t = Triggers.all.value.firstOrNull { it.id.toString() == p.ref }
            if (t != null && t.enabled == off) Triggers.toggle(t.id)
            sync(); "Trigger ${p.id} ${if (off) "paused" else "resumed"}."
        }
        "watcher" -> { com.blaze.assistant.phone.Watchers.setEnabled(p.ref, !off); sync(); "Watcher ${p.id} ${if (off) "paused" else "resumed"}." }
        else -> "A ${p.kind} can't be paused, only deleted or rescheduled."
    }

    private fun rename(p: P, value: String): String {
        if (value.isBlank()) return "Give the new name in value."
        when (p.kind) {
            "routine" -> Routines.rename(p.ref.toInt(), value)
            "trigger" -> Triggers.rename(p.ref.toLong(), value)
        }
        update(p.id) { it.copy(title = value.take(60)) }
        return "Renamed ${p.id} to \u201c$value\u201d."
    }

    private suspend fun reschedule(ctx: Context, p: P, value: String): String {
        val m = Regex("""(\d{1,2})[:.](\d{2})""").find(value) ?: return "Give the new time as HH:mm in value."
        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
        if (h > 23 || mi > 59) return "That isn't a valid time."
        return when (p.kind) {
            "routine" -> { Routines.retime(p.ref.toInt(), h, mi); sync(); "Routine ${p.id} now runs at %02d:%02d.".format(h, mi) }
            "alarm" -> {
                dismissAlarm(ctx, p); drop(p.id)
                val r = com.blaze.assistant.phone.PhoneTools.run(ctx, "set_alarm", JSONObject().put("hour", h).put("minute", mi)
                    .put("label", p.props.optString("name").ifBlank { p.title }))
                "Moved: old alarm ${p.id} removed. $r"
            }
            else -> "Rescheduling a ${p.kind} isn't supported yet; delete it and create a new one."
        }
    }

    /** Next time a clock alarm at h:m will ring. */
    fun nextAt(h: Int, m: Int): Long {
        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    // ---------- Tools ----------

    val names = setOf("processes", "process")

    fun tools(): List<JSONObject> = listOf(
        JSONObject().put("type", "function").put("function", JSONObject().put("name", "processes")
            .put("description", "List the things you created that are still tracked (alarms, timers, routines, triggers, calendar events, background jobs) as one line each: ID · kind · title · when · status. Use it to find an ID before changing or deleting something.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("kind", JSONObject().put("type", "string").put("enum", JSONArray(prefix.keys.toList())))
                .put("status", JSONObject().put("type", "string").put("description", "live (active or paused), active, paused, finished…"))))),
        JSONObject().put("type", "function").put("function", JSONObject().put("name", "process")
            .put("description", "Act on one tracked process by its ID (e.g. R4, A12). details returns everything stored about it: only use it when the user asks for specifics.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("id", JSONObject().put("type", "string"))
                .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("delete", "pause", "resume", "rename", "reschedule", "details"))))
                .put("value", JSONObject().put("type", "string").put("description", "New name for rename, new time HH:mm for reschedule")))
                .put("required", JSONArray(listOf("id", "action")))))
    )

    suspend fun run(ctx: Context, name: String, a: JSONObject): String = when (name) {
        "processes" -> list(a.optString("kind"), a.optString("status"))
        "process" -> act(ctx, a.optString("id"), a.optString("action"), a.optString("value"))
        else -> "Unknown tool"
    }
}
