package com.blaze.assistant.proactive

import com.blaze.assistant.agent.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Notices routines: when you open the same app around the same hour on 4 of the last 7 days,
 * Blaze offers once to do it for you (a tap turns it into a routine). Stored as small date
 * lists in prefs, never uploaded.
 */
object Habits {
    private val skip = Regex("launcher|systemui|settings|blaze|inputmethod|keyboard|packageinstaller|permissioncontroller", RegexOption.IGNORE_CASE)

    @Synchronized fun onApp(pkg: String, label: String) {
        if (skip.containsMatchIn(pkg) || !Settings.bool("habit_offers", true)) return
        try {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            val all = JSONObject(Settings.str("habits", "{}"))
            val key = "$pkg|$hour"
            val days = all.optJSONArray(key) ?: JSONArray()
            val list = (0 until days.length()).map { days.optString(it) }.toMutableList()
            if (day !in list) list += day
            val week = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(System.currentTimeMillis() - 7 * 86_400_000L))
            val recent = list.filter { it > week }
            all.put(key, JSONArray(recent))
            // Keep it small: drop keys with nothing recent.
            all.keys().asSequence().toList().forEach { k -> if ((all.optJSONArray(k)?.length() ?: 0) == 0) all.remove(k) }
            Settings.setStr("habits", all.toString().take(20_000))
            val offered = Settings.str("habits_offered", "")
            if (recent.size >= 4 && !offered.split(',').contains(key)) {
                Settings.setStr("habits_offered", (offered.split(',').filter { it.isNotBlank() }.takeLast(40) + key).joinToString(","))
                val time = "%02d:%02d".format(hour, 0)
                Proactive.say("You open $label around ${hour}:00 most days. Want me to do it for you?",
                    "habit_$key", suggest = "Every day at $time open $label for me")
            }
        } catch (_: Exception) { }
    }
}
