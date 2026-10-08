package com.blaze.assistant.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.blaze.assistant.agent.FastLane
import com.blaze.assistant.agent.LlmClient
import com.blaze.assistant.agent.Settings
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** One button that tests every part of Blaze and says what's broken and why. */
object SelfCheck {
    data class Item(val name: String, val ok: Boolean, val detail: String)

    suspend fun run(ctx: Context): List<Item> {
        val out = mutableListOf<Item>()
        // AI
        val t0 = System.currentTimeMillis()
        val ai = try {
            withTimeoutOrNull(25_000) {
                LlmClient.complete(JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with the single word OK.")), null, null, null, LlmClient.Lane.BACKGROUND)
            }?.let { Item("AI model", true, "${LlmClient.lastRoute.ifBlank { Settings.model }} answered in ${System.currentTimeMillis() - t0} ms") }
                ?: Item("AI model", false, "No answer in 25 s. Check your connection or try another model.")
        } catch (e: Exception) { Item("AI model", false, e.message?.take(160) ?: "Failed") }
        out += ai
        out += Item("Fast lane", FastLane.ready(ctx), when {
            !FastLane.supported(ctx) -> "This phone's chip isn't supported."
            !FastLane.ready(ctx) -> "Model not downloaded (Settings → AI & model)."
            !Settings.fastLane -> "Downloaded but turned off."
            else -> "Ready · ${com.blaze.assistant.agent.IntentCache.size()} cached commands" })
        out += Item("Phone control", com.blaze.assistant.phone.BlazeAccessibilityService.instance != null,
            if (com.blaze.assistant.phone.BlazeAccessibilityService.instance != null) "Accessibility service connected" else "Turn on Blaze in Accessibility settings.")
        out += Item("Notifications access", com.blaze.assistant.notify.NotificationBrain.enabled(ctx),
            if (com.blaze.assistant.notify.NotificationBrain.enabled(ctx)) "Can read and reply to notifications" else "Off: Blaze can't see or react to notifications.")
        out += Item("Overlay", android.provider.Settings.canDrawOverlays(ctx), if (android.provider.Settings.canDrawOverlays(ctx)) "Orb and live task card allowed" else "Not allowed: no floating orb or task card.")
        val mic = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        out += Item("Microphone", mic, if (mic) "Allowed" else "Not allowed: voice and wake word won't work.")
        val notif = android.os.Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        out += Item("Post notifications", notif, if (notif) "Allowed" else "Not allowed: Blaze can't speak up on its own.")
        val wake = java.io.File(ctx.filesDir, "wake-model").exists()
        out += Item("Wake word model", wake, if (wake) "Downloaded" else "Not downloaded yet (Settings → Voice).")
        val pm = ctx.getSystemService(android.os.PowerManager::class.java)
        val unrestricted = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
        out += Item("Background running", unrestricted, if (unrestricted) "Battery optimisation is off for Blaze" else "Battery optimisation may stop Blaze in the background.")
        val conns = com.blaze.assistant.connect.Connectors.connectedCount()
        out += Item("Connections", true, "$conns connected")
        try {
            val m = withTimeoutOrNull(15_000) { com.blaze.assistant.connect.Mcp.tools().size }
            out += Item("MCP servers", m != null, if (m == null) "A server didn't answer in 15 s" else "$m tools available")
        } catch (e: Exception) { out += Item("MCP servers", false, e.message?.take(120) ?: "Failed") }
        val crashes = CrashLog.crashes()
        out += Item("Crash log", crashes == 0, if (crashes == 0) "No crashes recorded" else "$crashes crash(es) recorded. Send the report from this page.")
        val s = DeviceStatus.snap(ctx)
        val w = DeviceStatus.warnings(s)
        out += Item("Phone health", w.isEmpty(), if (w.isEmpty()) "Battery ${s.battery}%, ${"%.1f".format(s.freeStorageGb)} GB free" else w.joinToString("; "))
        return out
    }
}
