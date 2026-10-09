package com.blaze.assistant.routine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** [days] bitmask: Mon=1 … Sun=64. 0 = run once. */
data class Routine(val id: Int, val name: String, val prompt: String, val hour: Int, val minute: Int,
                   val days: Int, val enabled: Boolean = true,
                   /** Shell job instead of a prompt: runs the command directly, no AI involved. */
                   val command: String = "", val linux: Boolean = false, val notify: String = "always", val everyMinutes: Int = 0) {
    fun toJson() = JSONObject().put("id", id).put("name", name).put("prompt", prompt).put("hour", hour)
        .put("minute", minute).put("days", days).put("enabled", enabled)
        .put("command", command).put("linux", linux).put("notify", notify).put("every_minutes", everyMinutes)
    val isCommand get() = command.isNotBlank()

    fun daysLabel(): String = if (everyMinutes > 0) "Every ${if (everyMinutes % 60 == 0) "${everyMinutes / 60} h" else "$everyMinutes min"}" else when (days) {
        0 -> "Once"; 127 -> "Every day"; 31 -> "Weekdays"; 96 -> "Weekends"
        else -> listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").filterIndexed { i, _ -> days and (1 shl i) != 0 }.joinToString(" ")
    }
    fun timeLabel() = "%02d:%02d".format(hour, minute)
}

/** Scheduled prompts Blaze runs by itself ("every weekday at 7, brief me"). */
object Routines {
    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private val _all = MutableStateFlow<List<Routine>>(emptyList())
    val all: StateFlow<List<Routine>> = _all

    fun init(ctx: Context) {
        app = ctx.applicationContext
        prefs = app.getSharedPreferences("blaze", Context.MODE_PRIVATE)
        _all.value = try {
            val a = JSONArray(prefs.getString("routines", "[]"))
            (0 until a.length()).map { val o = a.getJSONObject(it)
                Routine(o.getInt("id"), o.getString("name"), o.getString("prompt"), o.getInt("hour"), o.getInt("minute"),
                    o.optInt("days", 127), o.optBoolean("enabled", true), o.optString("command"), o.optBoolean("linux"),
                    o.optString("notify", "always"), o.optInt("every_minutes", 0)) }
        } catch (_: Exception) { emptyList() }
        scheduleAll()
    }

    private fun save(list: List<Routine>) {
        _all.value = list
        prefs.edit().putString("routines", JSONArray(list.map { it.toJson() }).toString()).apply()
        scheduleAll()
    }

    fun get(id: Int) = _all.value.firstOrNull { it.id == id }

    fun add(name: String, prompt: String, hour: Int, minute: Int, days: Int): Routine {
        val r = Routine((_all.value.maxOfOrNull { it.id } ?: 0) + 1, name.trim().ifEmpty { "Routine" }, prompt.trim(),
            hour.coerceIn(0, 23), minute.coerceIn(0, 59), days)
        save(_all.value + r); return r
    }

    fun addCommand(name: String, command: String, hour: Int, minute: Int, days: Int, linux: Boolean, notify: String, everyMinutes: Int): Routine {
        val r = Routine((_all.value.maxOfOrNull { it.id } ?: 0) + 1, name.trim().ifEmpty { "Shell job" }, "", hour.coerceIn(0, 23), minute.coerceIn(0, 59), days,
            command = command.trim(), linux = linux, notify = notify, everyMinutes = if (everyMinutes > 0) everyMinutes.coerceAtLeast(15) else 0)
        save(_all.value + r); return r
    }

    fun rename(id: Int, name: String) = save(_all.value.map { if (it.id == id) it.copy(name = name.trim()) else it })
    fun retime(id: Int, h: Int, m: Int) = save(_all.value.map { if (it.id == id) it.copy(hour = h, minute = m) else it })
    fun setEnabled(id: Int, on: Boolean) = save(_all.value.map { if (it.id == id) it.copy(enabled = on) else it })
    fun delete(id: Int) { cancel(id); save(_all.value.filterNot { it.id == id }) }

    /** After a one-time routine runs it switches off. */
    fun ran(id: Int) { get(id)?.let { if (it.days == 0 && it.everyMinutes == 0) setEnabled(id, false) else scheduleAll() } }

    /** Parses "daily", "weekdays", "weekends", "once", or names like "mon,wed,fri". */
    fun parseDays(s: String): Int {
        val t = s.lowercase()
        return when {
            t.isBlank() || "daily" in t || "every day" in t -> 127
            "weekday" in t -> 31
            "weekend" in t -> 96
            "once" in t || "today" in t || "tomorrow" in t -> 0
            else -> listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun").foldIndexed(0) { i, acc, d -> if (d in t) acc or (1 shl i) else acc }
                .let { if (it == 0) 127 else it }
        }
    }

    fun next(r: Routine, from: Long = System.currentTimeMillis()): Long {
        if (r.everyMinutes > 0) return from + r.everyMinutes * 60_000L
        val c = Calendar.getInstance().apply { timeInMillis = from; set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, r.hour); set(Calendar.MINUTE, r.minute) }
        if (c.timeInMillis <= from) c.add(Calendar.DAY_OF_YEAR, 1)
        if (r.days == 0) return c.timeInMillis
        repeat(8) {
            val idx = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7   // Mon=0 … Sun=6
            if (r.days and (1 shl idx) != 0) return c.timeInMillis
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return c.timeInMillis
    }

    private fun pi(id: Int, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT) = PendingIntent.getBroadcast(
        app, 9000 + id, Intent(app, RoutineReceiver::class.java).setAction(RoutineReceiver.FIRE).putExtra("id", id),
        flags or PendingIntent.FLAG_IMMUTABLE)

    private fun cancel(id: Int) { app.getSystemService(AlarmManager::class.java).cancel(pi(id)) }

    fun scheduleAll() {
        val am = app.getSystemService(AlarmManager::class.java)
        for (r in _all.value) {
            if (!r.enabled) { cancel(r.id); continue }
            val t = next(r)
            val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            try {
                if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi(r.id))
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi(r.id))
            } catch (_: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi(r.id)) }
        }
    }

    fun tools(): List<JSONObject> {
        fun fn(n: String, d: String, props: JSONObject = JSONObject(), req: List<String> = emptyList()) =
            JSONObject().put("type", "function").put("function", JSONObject().put("name", n).put("description", d)
                .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))
        fun p(d: String) = JSONObject().put("type", "string").put("description", d)
        return listOf(
            fn("create_routine", "Schedule something Blaze does by itself at a time, once or repeating, e.g. a morning brief, a reminder, checking messages. The result arrives as a notification.",
                JSONObject().put("name", p("Short name, e.g. 'Morning brief'"))
                    .put("prompt", p("What Blaze should do when it runs, written as an instruction to Blaze"))
                    .put("time", p("24h time HH:mm"))
                    .put("days", p("daily, weekdays, weekends, once, or days like 'mon,wed,fri'")),
                listOf("name", "prompt", "time")),
            fn("schedule_command", "Run a shell command or script on a schedule (cron), without the AI: backups, scrapers, checks, syncs. Works offline, survives reboots, notifies the result. For things that need thinking, use create_routine instead.",
                JSONObject().put("name", p("Short name")).put("command", p("Shell command, e.g. 'sh ~/scripts/backup.sh'"))
                    .put("time", p("24h HH:mm for daily/weekly runs"))
                    .put("days", p("daily, weekdays, weekends, once, or 'mon,wed,fri'"))
                    .put("every_minutes", JSONObject().put("type", "integer").put("description", "Repeat every N minutes instead of at a time (min 15)"))
                    .put("linux", JSONObject().put("type", "boolean").put("description", "Run inside Alpine Linux"))
                    .put("notify", JSONObject().put("type", "string").put("enum", JSONArray(listOf("always", "on_failure", "never")))),
                listOf("name", "command")),
            fn("list_routines", "List the user's scheduled routines and scheduled shell jobs."),
            fn("delete_routine", "Delete a routine by name.", JSONObject().put("name", p("Routine name")), listOf("name"))
        )
    }

    fun run(name: String, a: JSONObject): String = when (name) {
        "create_routine" -> {
            val (h, m) = a.getString("time").split(":").let { it[0].trim().toInt() to (it.getOrNull(1)?.trim()?.take(2)?.toInt() ?: 0) }
            val r = add(a.getString("name"), a.getString("prompt"), h, m, parseDays(a.optString("days", "daily")))
            "Created routine \"${r.name}\" at ${r.timeLabel()}, ${r.daysLabel()}. [ID ${com.blaze.assistant.agent.Processes.idFor("routine", r.id.toString())}]"
        }
        "schedule_command" -> {
            val every = a.optInt("every_minutes", 0)
            val t = a.optString("time").ifBlank { if (every > 0) "00:00" else "07:00" }
            val (h, m) = t.split(":").let { it[0].trim().toInt() to (it.getOrNull(1)?.trim()?.take(2)?.toInt() ?: 0) }
            val r = addCommand(a.getString("name"), a.getString("command"), h, m, if (every > 0) 127 else parseDays(a.optString("days", "daily")),
                a.optBoolean("linux"), a.optString("notify", "always"), every)
            "Scheduled \"${r.name}\": ${if (every > 0) r.daysLabel() else "${r.timeLabel()} ${r.daysLabel()}"}, runs `${r.command.take(80)}`. [ID ${com.blaze.assistant.agent.Processes.idFor("routine", r.id.toString())}]"
        }
        "list_routines" -> _all.value.takeIf { it.isNotEmpty() }?.joinToString("\n") {
            "${it.name}: ${if (it.everyMinutes > 0) "" else it.timeLabel() + " "}${it.daysLabel()}${if (!it.enabled) " (off)" else ""} — ${if (it.isCommand) "$ " + it.command else it.prompt}" } ?: "No routines yet."
        "delete_routine" -> _all.value.firstOrNull { it.name.equals(a.getString("name"), true) || it.name.contains(a.getString("name"), true) }
            ?.let { delete(it.id); "Deleted \"${it.name}\"." } ?: "No routine called \"${a.getString("name")}\"."
        else -> "Unknown"
    }
}
