package com.blaze.assistant.agent

import android.content.Context
import android.content.SharedPreferences

object Settings {
    const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai/"
    const val DEFAULT_MODEL = "gemini-3.8-flash"

    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("blaze", Context.MODE_PRIVATE)
        // v1.1 kept one key/model/address. Move them under Gemini (or Custom if the address was changed).
        if (!prefs.contains("provider")) {
            val oldBase = prefs.getString("base_url", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
            val pid = if (oldBase.contains("generativelanguage")) "gemini" else Providers.CUSTOM
            val e = prefs.edit().putString("provider", pid)
            prefs.getString("api_key", null)?.let { e.putString("key_$pid", it) }
            prefs.getString("model", null)?.let { e.putString("model_$pid", it) }
            if (pid == Providers.CUSTOM) e.putString("custom_base", oldBase)
            e.apply()
        }
        // v1.9: the fast lane misread things like "shut down" at 0.3; raise an untouched low threshold.
        if (!prefs.getBoolean("v19_fl_min", false)) {
            val e = prefs.edit().putBoolean("v19_fl_min", true)
            if (prefs.getFloat("fast_lane_min", 0.55f) < 0.5f) e.putFloat("fast_lane_min", 0.55f)
            e.apply()
        }
        // v1.7: API keys and tokens move into Keystore-encrypted form (plain values still read).
        try {
            val e = prefs.edit(); var n = 0
            for ((k, v) in prefs.all) if (Secrets.isSecretPref(k) && v is String && v.isNotEmpty() && !Secrets.isEncrypted(v)) {
                val enc = Secrets.encrypt(v); if (enc != v) { e.putString(k, enc); n++ }
            }
            if (n > 0) e.apply()
        } catch (_: Exception) { }
        // 2.x Gemini models are closed to new users; move old installs to the current default.
        if (prefs.getString("model_gemini", null)?.startsWith("gemini-2.") == true) {
            prefs.edit().putString("model_gemini", DEFAULT_MODEL).apply()
        }
    }

    var providerId: String
        get() = prefs.getString("provider", "gemini") ?: "gemini"
        set(v) = prefs.edit().putString("provider", v).apply()

    val provider: Provider get() = Providers.byId(providerId)

    fun keyFor(pid: String) = Secrets.decrypt(prefs.getString("key_$pid", "") ?: "")
    fun setKeyFor(pid: String, v: String) {
        val t = v.trim()
        if (t == keyFor(pid)) return
        prefs.edit().putString("key_$pid", Secrets.encrypt(t)).apply()
        com.blaze.assistant.connect.KeySync.localChange(pid)   // follows the Blaze account to the PC
    }
    /** Writes a key that came from the Blaze account (no new change stamp, no sync back). */
    fun setKeyForSynced(pid: String, v: String) {
        val t = v.trim()
        if (t.isEmpty()) prefs.edit().remove("key_$pid").apply() else prefs.edit().putString("key_$pid", Secrets.encrypt(t)).apply()
    }

    /** Any secret pref (connector tokens etc.), stored encrypted. */
    fun secret(name: String) = Secrets.decrypt(prefs.getString(name, "") ?: "")
    fun setSecret(name: String, v: String) {
        if (v.isEmpty()) prefs.edit().remove(name).apply() else prefs.edit().putString(name, Secrets.encrypt(v)).apply()
    }
    fun str(name: String, def: String = "") = prefs.getString(name, def) ?: def
    fun setStr(name: String, v: String) = prefs.edit().putString(name, v).apply()
    fun bool(name: String, def: Boolean) = prefs.getBoolean(name, def)
    fun setBool(name: String, v: Boolean) = prefs.edit().putBoolean(name, v).apply()

    /** Fast lane (on-device Needle model) for direct commands. */
    var fastLane: Boolean
        get() = prefs.getBoolean("fast_lane", true)
        set(v) = prefs.edit().putBoolean("fast_lane", v).apply()
    /** Minimum Needle confidence (0-1) to act without the cloud. */
    var fastLaneMin: Float
        get() = prefs.getFloat("fast_lane_min", 0.55f)
        set(v) = prefs.edit().putFloat("fast_lane_min", v).apply()

    /** Optional quicker model for in-between phone steps; blank = use the main model. */
    var stepsProvider: String
        get() = prefs.getString("steps_provider", "") ?: ""
        set(v) = prefs.edit().putString("steps_provider", v).apply()
    var stepsModel: String
        get() = prefs.getString("steps_model", "") ?: ""
        set(v) = prefs.edit().putString("steps_model", v.trim()).apply()

    /** Auto (free) chain: JSON array of "providerId|model". */
    var freeChain: String
        get() = prefs.getString("free_chain", "") ?: ""
        set(v) = prefs.edit().putString("free_chain", v).apply()
    /** Start the next model in the chain if the first hasn't answered within this many ms (0 = off). */
    var hedgeMs: Int
        get() = prefs.getInt("hedge_ms", 0)
        set(v) = prefs.edit().putInt("hedge_ms", v).apply()

    /** Self-improvement: learn memories and skills after chats. */
    var autoLearn: Boolean
        get() = prefs.getBoolean("auto_learn", true)
        set(v) = prefs.edit().putBoolean("auto_learn", v).apply()
    /** v0.8.0: AI chat titles from the conversation's main theme (Desktop titles.go). */
    var autoTitles: Boolean
        get() = prefs.getBoolean("auto_titles", true)
        set(v) = prefs.edit().putBoolean("auto_titles", v).apply()
    var learnApproval: Boolean
        get() = prefs.getBoolean("learn_approval", false)
        set(v) = prefs.edit().putBoolean("learn_approval", v).apply()
    /** Opt-in: keep recent on-screen text on the phone so Blaze can recall it. */
    var screenMemory: Boolean
        get() = prefs.getBoolean("screen_memory", false)
        set(v) = prefs.edit().putBoolean("screen_memory", v).apply()

    var apiKey: String
        get() = keyFor(providerId)
        set(v) = setKeyFor(providerId, v)

    var customBase: String
        get() = prefs.getString("custom_base", "") ?: ""
        set(v) = prefs.edit().putString("custom_base", v.trim()).apply()

    /** True when Blaze can answer: a key is set, or Auto (free) is picked. */
    val ready: Boolean get() = providerId == Providers.AUTO || apiKey.isNotBlank()

    val baseUrl: String
        get() = if (providerId == Providers.CUSTOM) customBase else provider.baseUrl

    fun modelFor(pid: String) = prefs.getString("model_$pid", null) ?: Providers.byId(pid).defaultModel
    var model: String
        get() = modelFor(providerId)
        set(v) = prefs.edit().putString("model_$providerId", v.trim()).apply()

    var phoneControl: Boolean
        get() = prefs.getBoolean("phone_control", true)
        set(v) = prefs.edit().putBoolean("phone_control", v).apply()

    /** Read replies aloud when you asked by voice. */
    var speakReplies: Boolean
        get() = prefs.getBoolean("speak_replies", true)
        set(v) = prefs.edit().putBoolean("speak_replies", v).apply()

    /** Listen for "Hey Blaze" / "Wake up Blaze" in the background. */
    var wakeWord: Boolean
        get() = prefs.getBoolean("wake_word", false)
        set(v) = prefs.edit().putBoolean("wake_word", v).apply()

    var personaId: String
        get() = prefs.getString("persona", "blaze") ?: "blaze"
        set(v) = prefs.edit().putString("persona", v).apply()

    var customPersona: String
        get() = prefs.getString("persona_custom", "") ?: ""
        set(v) = prefs.edit().putString("persona_custom", v).apply()

    var streamReplies: Boolean
        get() = prefs.getBoolean("stream", true)
        set(v) = prefs.edit().putBoolean("stream", v).apply()

    var lastChatId: String
        get() = prefs.getString("last_chat", "") ?: ""
        set(v) = prefs.edit().putString("last_chat", v).apply()

    /** Everything in settings (keys included) as JSON, for Backup → Export. */
    fun exportJson(): String {
        val o = org.json.JSONObject().put("blaze_backup", 1)
        val p = org.json.JSONObject()
        for ((k, v) in prefs.all) {
            if (k.startsWith("model_cache")) continue
            when (v) {
                is String -> p.put(k, if (Secrets.isSecretPref(k)) Secrets.decrypt(v) else v)
                is Boolean, is Int, is Long, is Float -> p.put(k, v)
                is Set<*> -> p.put(k, org.json.JSONArray(v.toList()))
            }
        }
        return o.put("prefs", p).toString(2)
    }

    /** Restore an export. Returns how many settings were restored. */
    fun importJson(text: String): Int {
        val p = org.json.JSONObject(text).getJSONObject("prefs")
        val e = prefs.edit()
        var n = 0
        for (k in p.keys()) {
            when (val v = p.get(k)) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Double -> e.putFloat(k, v.toFloat())
                is String -> e.putString(k, if (Secrets.isSecretPref(k)) Secrets.encrypt(v) else v)
                else -> continue
            }
            n++
        }
        e.commit()
        return n
    }

    /** Slim live task card over other apps while Blaze works. */
    var taskCard: Boolean
        get() = prefs.getBoolean("task_card", true)
        set(v) = prefs.edit().putBoolean("task_card", v).apply()

    /** Haptic ticks when actions land. */
    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(v) = prefs.edit().putBoolean("haptics", v).apply()

    /** Chat text size multiplier (accessibility). */
    var textScale: Float
        get() = prefs.getFloat("text_scale", 1f)
        set(v) = prefs.edit().putFloat("text_scale", v).apply()

    /** Small "⚡ on-device" / "☁ model · 2.1 s" badge under replies. */
    var showRoute: Boolean
        get() = prefs.getBoolean("show_route", true)
        set(v) = prefs.edit().putBoolean("show_route", v).apply()

    /** Drifting background dots. Off = lightest on slower phones. */
    var particles: Boolean
        get() = prefs.getBoolean("particles", true)
        set(v) = prefs.edit().putBoolean("particles", v).apply()

    /** Show the model's reasoning (brain button) when the provider sends it. */
    var showReasoning: Boolean
        get() = prefs.getBoolean("show_reasoning", true)
        set(v) = prefs.edit().putBoolean("show_reasoning", v).apply()

    /** Show every step as it happens (the old list) instead of one calm plain-English line. */
    var showSteps: Boolean
        get() = prefs.getBoolean("show_steps", false)
        set(v) = prefs.edit().putBoolean("show_steps", v).apply()

    /** Fold long tables, lists, code and reports into cards you tap to open. */
    var foldAnswers: Boolean
        get() = prefs.getBoolean("fold_answers", true)
        set(v) = prefs.edit().putBoolean("fold_answers", v).apply()

    /** system, light or dark (the sun/moon button in the top bar). */
    var themeMode: String
        get() = prefs.getString("theme_mode", "system") ?: "system"
        set(v) = prefs.edit().putString("theme_mode", v).apply()

    /** Three tap-to-send suggestions under replies. */
    var followups: Boolean
        get() = prefs.getBoolean("followups", true)
        set(v) = prefs.edit().putBoolean("followups", v).apply()

    /** User-made slash commands: JSON array of {name, desc, prompt}. */
    var userCommands: String
        get() = prefs.getString("user_commands", "[]") ?: "[]"
        set(v) = prefs.edit().putString("user_commands", v).apply()

    var confirmRisky: Boolean
        get() = prefs.getBoolean("confirm_risky", true)
        set(v) = prefs.edit().putBoolean("confirm_risky", v).apply()

    var appLock: Boolean
        get() = prefs.getBoolean("app_lock", false)
        set(v) = prefs.edit().putBoolean("app_lock", v).apply()

    var orbPinned: Boolean
        get() = prefs.getBoolean("orb_pinned", false)
        set(v) = prefs.edit().putBoolean("orb_pinned", v).apply()
    var orbX: Int
        get() = prefs.getInt("orb_x", -1)
        set(v) = prefs.edit().putInt("orb_x", v).apply()
    var orbY: Int
        get() = prefs.getInt("orb_y", -1)
        set(v) = prefs.edit().putInt("orb_y", v).apply()

    var userName: String
        get() = prefs.getString("user_name", "") ?: ""
        set(v) = prefs.edit().putString("user_name", v.trim()).apply()

    /** Last plan-detection result, per provider. */
    var modelCache: String
        get() = prefs.getString("model_cache_$providerId", "") ?: ""
        set(v) = prefs.edit().putString("model_cache_$providerId", v).apply()
}
