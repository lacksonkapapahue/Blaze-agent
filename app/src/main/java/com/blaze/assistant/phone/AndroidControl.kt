package com.blaze.assistant.phone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.blaze.assistant.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wider Android control with plain public APIs (no root, no Shizuku): clipboard, Blaze's own
 * notifications (with progress and a tap action), the share sheet, ringer/DND/brightness/rotation,
 * jumping straight to any settings page, system actions (power menu, split screen, screenshot,
 * lock), app usage, uninstall prompts and the camera. Anything Android doesn't let an app change
 * directly opens the right settings page for the user instead.
 */
object AndroidControl {
    val names = setOf("clipboard", "post_notification", "share_text", "phone_mode", "open_settings_page", "system_action", "app_usage", "uninstall_app", "open_camera", "list_apps")

    private fun fn(name: String, desc: String, props: JSONObject, req: List<String>) = JSONObject().put("type", "function").put("function",
        JSONObject().put("name", name).put("description", desc).put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))
    private fun en(vararg v: String) = JSONObject().put("type", "string").put("enum", JSONArray(v.toList()))
    private fun str(d: String = "") = JSONObject().put("type", "string").apply { if (d.isNotBlank()) put("description", d) }

    private val pages = mapOf(
        "wifi" to Settings.ACTION_WIFI_SETTINGS, "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS, "hotspot" to "android.settings.TETHER_SETTINGS",
        "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS, "nfc" to Settings.ACTION_NFC_SETTINGS, "dnd" to "android.settings.ZEN_MODE_SETTINGS",
        "battery" to Intent.ACTION_POWER_USAGE_SUMMARY, "display" to Settings.ACTION_DISPLAY_SETTINGS, "sound" to Settings.ACTION_SOUND_SETTINGS,
        "apps" to Settings.ACTION_APPLICATION_SETTINGS, "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
        "notifications" to "android.settings.ALL_APPS_NOTIFICATION_SETTINGS", "data_usage" to Settings.ACTION_DATA_USAGE_SETTINGS,
        "mobile_network" to Settings.ACTION_NETWORK_OPERATOR_SETTINGS, "airplane" to Settings.ACTION_AIRPLANE_MODE_SETTINGS, "date_time" to Settings.ACTION_DATE_SETTINGS,
        "language" to Settings.ACTION_LOCALE_SETTINGS, "security" to Settings.ACTION_SECURITY_SETTINGS, "developer" to Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        "usage_access" to Settings.ACTION_USAGE_ACCESS_SETTINGS, "write_settings" to Settings.ACTION_MANAGE_WRITE_SETTINGS, "cast" to Settings.ACTION_CAST_SETTINGS,
        "vpn" to Settings.ACTION_VPN_SETTINGS, "print" to Settings.ACTION_PRINT_SETTINGS, "default_apps" to Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
    )

    fun tools(): List<JSONObject> = listOf(
        fn("clipboard", "Read or set the clipboard.", JSONObject().put("action", en("get", "set")).put("text", str()), listOf("action")),
        fn("post_notification", "Show a notification from Blaze (a reminder, a test, a progress bar). Tapping it can ask Blaze to do something.",
            JSONObject().put("title", str()).put("text", str()).put("progress", JSONObject().put("type", "integer").put("description", "0-100, optional"))
                .put("on_tap", str("Request Blaze runs when tapped, optional")), listOf("title")),
        fn("share_text", "Open the share sheet with some text or a link.", JSONObject().put("text", str()), listOf("text")),
        fn("phone_mode", "Ringer and screen: ringer normal/vibrate/silent, Do Not Disturb on/off, brightness 0-100, auto-rotate on/off.",
            JSONObject().put("setting", en("ringer", "dnd", "brightness", "rotation")).put("value", str("normal|vibrate|silent, on|off, or 0-100")), listOf("setting", "value")),
        fn("open_settings_page", "Open a specific Android settings page for the user.", JSONObject().put("page", en(*pages.keys.toTypedArray())), listOf("page")),
        fn("system_action", "Power menu, split screen, screenshot, lock screen, recents, notifications, quick settings.",
            JSONObject().put("action", en("power_dialog", "split_screen", "screenshot", "lock", "recents", "notifications", "quick_settings")), listOf("action")),
        fn("app_usage", "Which apps were used most today/this week (needs Usage access).", JSONObject().put("days", JSONObject().put("type", "integer")), emptyList()),
        fn("uninstall_app", "Ask Android to uninstall an app (the user confirms).", JSONObject().put("package", str("Package name")), listOf("package")),
        fn("list_apps", "List the apps installed on this phone (name and package), optionally filtered by a word. Use it instead of asking the user what they have.",
            JSONObject().put("filter", str("Optional word, e.g. 'bank', 'secure', 'whatsapp'")), emptyList()),
        fn("open_camera", "Open the camera to take a photo, record video, or scan a QR code.", JSONObject().put("mode", en("photo", "video", "qr")), emptyList()),
    )

    private fun start(ctx: Context, i: Intent): Boolean = try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }

    fun run(ctx: Context, name: String, a: JSONObject): String { return when (name) {
        "clipboard" -> {
            val cm = ctx.getSystemService(ClipboardManager::class.java)
            if (a.optString("action") == "set") { cm.setPrimaryClip(ClipData.newPlainText("Blaze", a.optString("text"))); "Copied to the clipboard." }
            else cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.take(4000)?.ifBlank { null }
                ?: "The clipboard is empty (Android only lets Blaze read it while Blaze is on screen)."
        }
        "post_notification" -> notify(ctx, a.optString("title"), a.optString("text"), if (a.has("progress")) a.optInt("progress") else -1, a.optString("on_tap"))
        "share_text" -> if (start(ctx, Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, a.optString("text")), "Share"))) "Opened the share sheet." else "Couldn't open sharing."
        "phone_mode" -> mode(ctx, a.optString("setting"), a.optString("value").trim().lowercase())
        "open_settings_page" -> {
            val p = a.optString("page"); val act = pages[p] ?: return "Unknown page."
            if (start(ctx, Intent(act))) "Opened $p settings." else if (start(ctx, Intent(Settings.ACTION_SETTINGS))) "That page isn't on this phone, so I opened Settings." else "Couldn't open settings."
        }
        "system_action" -> system(a.optString("action"))
        "app_usage" -> usage(ctx, a.optInt("days", 1).coerceIn(1, 30))
        "list_apps" -> {
            val pm = ctx.packageManager
            val f = a.optString("filter").trim()
            val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }.distinctBy { it.second }
                .filter { f.isBlank() || it.first.contains(f, true) || it.second.contains(f, true) }.sortedBy { it.first.lowercase() }
            if (apps.isEmpty()) "No installed app matches \u201c$f\u201d." else "${apps.size} apps:\n" + apps.take(150).joinToString("\n") { "${it.first} (${it.second})" }
        }
        "uninstall_app" -> if (start(ctx, Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.optString("package"))))) "Android is asking the user to confirm the uninstall." else "Couldn't start the uninstall."
        "open_camera" -> when (a.optString("mode", "photo")) {
            "video" -> if (start(ctx, Intent(android.provider.MediaStore.INTENT_ACTION_VIDEO_CAMERA))) "Opened the video camera." else "No camera app found."
            "qr" -> if (start(ctx, Intent("com.google.zxing.client.android.SCAN")) || start(ctx, Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)))
                "Opened the scanner/camera: point it at the QR code (most cameras read QR codes automatically)." else "No camera app found."
            else -> if (start(ctx, Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))) "Opened the camera." else "No camera app found."
        }
        else -> "Unknown tool"
    } }

    private var nextNote = 500

    private fun notify(ctx: Context, title: String, text: String, progress: Int, onTap: String): String {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("blaze_custom", "Blaze notes", NotificationManager.IMPORTANCE_DEFAULT))
        val b = NotificationCompat.Builder(ctx, "blaze_custom").setSmallIcon(R.drawable.ic_stat_blaze)
            .setContentTitle(title.ifBlank { "Blaze" }).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true)
        if (progress in 0..100) b.setProgress(100, progress, false).setOnlyAlertOnce(true)
        if (onTap.isNotBlank()) {
            val pi = android.app.PendingIntent.getBroadcast(ctx, onTap.hashCode(), Intent(ctx, com.blaze.assistant.notify.ActionReceiver::class.java)
                .setAction(com.blaze.assistant.notify.ActionReceiver.DO).putExtra("prompt", onTap),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            b.setContentIntent(pi)
        } else {
            ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
                b.setContentIntent(android.app.PendingIntent.getActivity(ctx, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE))
            }
        }
        return try { NotificationManagerCompat.from(ctx).notify(nextNote++, b.build()); "Notification shown." }
        catch (_: SecurityException) { "Blaze isn't allowed to post notifications. Turn them on in Settings → Apps → Blaze → Notifications." }
    }

    private fun mode(ctx: Context, setting: String, v: String): String {
        val audio = ctx.getSystemService(AudioManager::class.java)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        return when (setting) {
            "ringer" -> try {
                audio.ringerMode = when (v) { "silent" -> AudioManager.RINGER_MODE_SILENT; "vibrate" -> AudioManager.RINGER_MODE_VIBRATE; else -> AudioManager.RINGER_MODE_NORMAL }
                "Ringer set to $v."
            } catch (_: SecurityException) { start(ctx, Intent("android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS")); "Android needs Do Not Disturb access for Blaze first: I opened that page, switch Blaze on." }
            "dnd" -> if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(if (v == "on") NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
                "Do Not Disturb ${if (v == "on") "on" else "off"}."
            } else { start(ctx, Intent("android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS")); "Allow Blaze Do Not Disturb access on the page I opened, then ask again." }
            "brightness", "rotation" -> if (!Settings.System.canWrite(ctx)) {
                start(ctx, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName)))
                "Android needs 'Modify system settings' for Blaze: I opened that page, switch it on and ask again."
            } else if (setting == "brightness") {
                val pct = v.filter { it.isDigit() }.toIntOrNull()?.coerceIn(0, 100) ?: return "Give brightness as 0-100."
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (pct * 255 / 100).coerceAtLeast(1))
                "Brightness $pct%."
            } else {
                Settings.System.putInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (v == "on") 1 else 0)
                "Auto-rotate ${if (v == "on") "on" else "off"}."
            }
            else -> "Unknown setting."
        }
    }

    private fun system(action: String): String {
        val svc = BlazeAccessibilityService.instance ?: return "Turn on Blaze phone control first."
        val g = when (action) {
            "power_dialog" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "split_screen" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
            "screenshot" -> if (Build.VERSION.SDK_INT >= 28) android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT else return "Screenshots need Android 9+."
            "lock" -> if (Build.VERSION.SDK_INT >= 28) android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else return "Locking needs Android 9+."
            "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> return "Unknown action."
        }
        return if (svc.performGlobalAction(g)) "Done: ${action.replace('_', ' ')}." else "The phone didn't allow ${action.replace('_', ' ')}."
    }

    private fun usage(ctx: Context, days: Int): String {
        val usm = ctx.getSystemService(android.app.usage.UsageStatsManager::class.java) ?: return "Usage stats aren't available."
        val end = System.currentTimeMillis()
        val stats = try { usm.queryAndAggregateUsageStats(end - days * 86_400_000L, end) } catch (_: Exception) { emptyMap() }
        if (stats.isEmpty()) { start(ctx, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); return "Blaze needs Usage access: I opened that page, switch Blaze on and ask again." }
        val pm = ctx.packageManager
        return stats.values.filter { it.totalTimeInForeground > 60_000 }.sortedByDescending { it.totalTimeInForeground }.take(10).joinToString("\n") {
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(it.packageName, 0)).toString() } catch (_: Exception) { it.packageName }
            val m = it.totalTimeInForeground / 60_000
            "$label: ${if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"}"
        }.ifBlank { "No app was used for more than a minute." }
    }
}
