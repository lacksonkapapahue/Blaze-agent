package com.blaze.assistant.phone

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Where the phone is: last known fix from any provider, plus a street address when the geocoder has one. */
object Location {
    const val NAME = "get_location"

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", NAME)
        .put("description", "The phone's current location (latitude, longitude, accuracy, nearest address). Use instead of asking the user where they are.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()).put("required", JSONArray())))

    @Suppress("MissingPermission", "DEPRECATION")
    fun run(ctx: Context): String {
        val fine = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            try { ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
            return "Blaze doesn't have location permission. I opened Blaze's App info: Permissions → Location → Allow while using the app. Tell the user that, briefly."
        }
        val lm = ctx.getSystemService(LocationManager::class.java)
        if (lm == null || !(try { lm.isLocationEnabled } catch (_: Exception) { true })) return "Location is switched off on the phone (Quick Settings → Location)."
        val best = lm.getProviders(true).mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
            .maxByOrNull { it.time } ?: return "No location fix yet. Open Maps once so the phone gets a fix, then try again."
        val ageMin = (System.currentTimeMillis() - best.time) / 60_000
        val addr = try {
            Geocoder(ctx, Locale.getDefault()).getFromLocation(best.latitude, best.longitude, 1)?.firstOrNull()?.let { a ->
                (0..a.maxAddressLineIndex).joinToString(", ") { a.getAddressLine(it) }
            }
        } catch (_: Exception) { null }
        return "Location: ${"%.5f".format(best.latitude)}, ${"%.5f".format(best.longitude)} (±${best.accuracy.toInt()} m, ${if (ageMin < 1) "just now" else "$ageMin min ago"})" +
            (addr?.let { "\nNear: $it" } ?: "")
    }
}
