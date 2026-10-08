package com.blaze.assistant.phone

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** The actions Blaze can take on the phone, exposed to the AI as tools. */
object PhoneTools {

    private fun fn(name: String, desc: String, props: JSONObject = JSONObject(), required: List<String> = emptyList()) =
        JSONObject().put("type", "function").put(
            "function", JSONObject()
                .put("name", name)
                .put("description", desc)
                .put("parameters", JSONObject().put("type", "object").put("properties", props)
                    .put("required", JSONArray(required)))
        )

    private fun p(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)

    fun definitions(): JSONArray = JSONArray().apply {
        put(fn("open_app", "Open an installed app by its name, e.g. 'WhatsApp', 'Camera', 'Settings'.",
            JSONObject().put("app_name", p("string", "App name as shown on the home screen")), listOf("app_name")))
        put(fn("read_screen", "Read what is currently on the screen: the foreground app and a numbered list of elements. Call this before tapping or typing, and again after acting to check the result."))
        if (android.os.Build.VERSION.SDK_INT >= 30)
            put(fn("look_at_screen", "Take a screenshot and look at it. Use when read_screen isn't enough: images, maps, games, video, or to check how something looks."))
        put(fn("tap", "Tap an element from the latest screen by its number, or by its visible text with 'text' (safer when numbers may be stale).",
            JSONObject().put("element", p("integer", "Element number"))
                .put("text", p("string", "Visible text or description of the element, instead of a number"))
                .put("long_press", p("boolean", "Long-press instead of tap"))))
        put(fn("type_text", "Type into a text field (replaces its content). A search icon/bar works too: it's tapped first. Set submit=true to press Enter/Search after typing.",
            JSONObject().put("element", p("integer", "Element number of the field"))
                .put("text", p("string", "Text to type"))
                .put("submit", p("boolean", "Press the keyboard's Enter/Search key after typing")), listOf("element", "text")))
        put(fn("search_in_app", "Open an app straight to search results for a query, with no tapping: YouTube, YouTube Music, Spotify, Play Store, Google Maps, Google, Chrome, TikTok, X, Netflix, Amazon. Use play=true to start playing the top music/video match where the app supports it. Best first step for 'play X on Y' or 'search Y for X'.",
            JSONObject().put("app", p("string", "App name, e.g. YouTube"))
                .put("query", p("string", "What to search for"))
                .put("play", p("boolean", "Try to start playing the first result (music/video apps)")), listOf("app", "query")))
        put(fn("scroll", "Scroll the screen or a scrollable element.",
            JSONObject().put("direction", JSONObject().put("type", "string")
                .put("enum", JSONArray(listOf("up", "down", "left", "right"))))
                .put("element", p("integer", "Optional scrollable element number")), listOf("direction")))
        put(fn("press_button", "Press a system button.",
            JSONObject().put("button", JSONObject().put("type", "string")
                .put("enum", JSONArray(listOf("back", "home", "recents", "notifications", "quick_settings", "lock")))),
            listOf("button")))
        put(fn("open_url", "Open a web address in the browser, or a Google search for a query.",
            JSONObject().put("url", p("string", "Full URL, or plain words to search Google")), listOf("url")))
        put(fn("dial", "Open the phone dialer with a number filled in (the user presses call).",
            JSONObject().put("number", p("string", "Phone number")), listOf("number")))
        put(fn("compose_sms", "Open the messaging app with a text drafted to a number (the user presses send).",
            JSONObject().put("number", p("string", "Phone number"))
                .put("message", p("string", "Message text")), listOf("number", "message")))
        put(fn("set_alarm", "Set an alarm in the clock app.",
            JSONObject().put("hour", p("integer", "0-23")).put("minute", p("integer", "0-59"))
                .put("label", p("string", "Optional label")), listOf("hour", "minute")))
        put(fn("set_timer", "Start a countdown timer.",
            JSONObject().put("seconds", p("integer", "Length in seconds"))
                .put("label", p("string", "Optional label")), listOf("seconds")))
        put(fn("wait", "Wait for the screen to load.",
            JSONObject().put("seconds", p("number", "1 to 5")), listOf("seconds")))
    }

    /** Human-friendly line shown in the chat while a tool runs. */
    fun describe(name: String, args: JSONObject): String = when (name) {
        "open_app" -> "Opening ${args.optString("app_name")}"
        "read_screen" -> "Looking at the screen"
        "tap" -> if (args.optBoolean("long_press")) "Long-pressing" else "Tapping"
        "type_text" -> "Typing \"${args.optString("text").take(40)}\""
        "scroll" -> "Scrolling ${args.optString("direction")}"
        "press_button" -> "Pressing ${args.optString("button")}"
        "open_url" -> "Opening ${args.optString("url").take(40)}"
        "dial" -> "Opening the dialer"
        "compose_sms" -> "Drafting a text"
        "set_alarm" -> "Setting an alarm for %02d:%02d".format(args.optInt("hour"), args.optInt("minute"))
        "set_timer" -> "Starting a timer"
        "search_in_app" -> "Searching ${args.optString("app")} for \u201c${args.optString("query").take(40)}\u201d"
        "wait" -> "Waiting a moment"
        else -> name
    }

    suspend fun run(ctx: Context, name: String, args: JSONObject): String {
        val svc = BlazeAccessibilityService.instance
        fun needSvc() = "Phone control is off. Ask the user to turn on 'Blaze phone control' in Accessibility settings (there's a button in Blaze's settings)."
        return try {
            when (name) {
                "open_app" -> openApp(ctx, args.getString("app_name"))
                "read_screen" -> svc?.readScreen() ?: needSvc()
                "tap" -> when {
                    svc == null -> needSvc()
                    args.optString("text").isNotBlank() && !args.has("element") -> svc.tapText(args.optString("text"), args.optBoolean("long_press"))
                    args.has("element") -> svc.tap(args.getInt("element"), args.optBoolean("long_press"))
                    else -> "Give an element number or its text."
                }
                "type_text" -> svc?.type(args.getInt("element"), args.getString("text"), args.optBoolean("submit")) ?: needSvc()
                "search_in_app" -> AppSearch.run(ctx, args.optString("app"), args.optString("query"), args.optBoolean("play"))
                "scroll" -> svc?.scroll(args.getString("direction"),
                    if (args.has("element")) args.getInt("element") else null) ?: needSvc()
                "press_button" -> svc?.press(args.getString("button")) ?: needSvc()
                "open_url" -> {
                    val raw = args.getString("url").trim()
                    val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw
                    else if (raw.contains('.') && !raw.contains(' ')) "https://$raw"
                    else "https://www.google.com/search?q=" + Uri.encode(raw)
                    start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    "Opened $url"
                }
                "dial" -> { start(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + args.getString("number")))); "Dialer opened." }
                "compose_sms" -> {
                    val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + args.getString("number")))
                        .putExtra("sms_body", args.getString("message"))
                    start(ctx, i); "Message drafted; the user needs to press send."
                }
                "set_alarm" -> {
                    val i = Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, args.getInt("hour"))
                        .putExtra(AlarmClock.EXTRA_MINUTES, args.getInt("minute"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    if (args.has("label")) i.putExtra(AlarmClock.EXTRA_MESSAGE, args.getString("label"))
                    start(ctx, i); "Alarm set."
                }
                "set_timer" -> {
                    val i = Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, args.getInt("seconds"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    if (args.has("label")) i.putExtra(AlarmClock.EXTRA_MESSAGE, args.getString("label"))
                    start(ctx, i); "Timer started."
                }
                "wait" -> {
                    delay((args.optDouble("seconds", 1.0).coerceIn(0.5, 5.0) * 1000).toLong()); "Waited."
                }
                else -> "Unknown tool $name"
            }
        } catch (e: ActivityNotFoundException) {
            "No app on this phone can do that."
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    /** A screenshot as a JPEG data URL, or an explanation when it can't be taken. */
    suspend fun screenshot(): String {
        if (android.os.Build.VERSION.SDK_INT < 30) return "Screenshots need Android 11 or newer."
        val svc = BlazeAccessibilityService.instance ?: return "Phone control is off, so I can't take a screenshot."
        return svc.screenshot() ?: "Couldn't take a screenshot (some apps block it)."
    }

    private fun start(ctx: Context, i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    private suspend fun openApp(ctx: Context, name: String): String {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        val q = name.lowercase().trim()
        val scored = apps.map { it to it.loadLabel(pm).toString() }
            .map { (ri, label) ->
                val l = label.lowercase()
                val score = when {
                    l == q -> 0
                    l.startsWith(q) -> 1
                    l.contains(q) -> 2
                    q.contains(l) -> 3
                    ri.activityInfo.packageName.lowercase().contains(q.replace(" ", "")) -> 4
                    else -> 99
                }
                Triple(ri, label, score)
            }
            .filter { it.third < 99 }
            .sortedBy { it.third }
        val best = scored.firstOrNull()
            ?: return "No app called '$name' is installed. Installed apps include: " +
                apps.map { it.loadLabel(pm).toString() }.sorted().take(60).joinToString(", ")
        val intent = pm.getLaunchIntentForPackage(best.first.activityInfo.packageName)
            ?: return "Couldn't open ${best.second}."
        start(ctx, intent)
        BlazeAccessibilityService.instance?.settle(maxMs = 2000, quietMs = 300) ?: delay(900)
        return "Opened ${best.second}."
    }
}
