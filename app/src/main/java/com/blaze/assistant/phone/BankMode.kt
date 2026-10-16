package com.blaze.assistant.phone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.blaze.assistant.R
import com.blaze.assistant.agent.Settings
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Bank mode (v0.30.9). Banking apps refuse to run while Blaze's accessibility service is on (and some while Blaze draws
 * over other apps). Turning bank mode on removes every Blaze overlay (bubble, pointer, Magic Hand, task card, panel),
 * stops the wake word, and switches phone control off for real (`disableSelf()`), so it is off in system settings too.
 * A quiet ongoing notification says so and opens Blaze's own switch in Accessibility settings. Bank mode ends when the
 * user switches phone control back on ([onServiceConnected]). Chat keeps working; phone tools answer with
 * [BankModeLogic.TOOL_REPLY].
 *
 * Auto mode (secondary, opt-in): with WRITE_SECURE_SETTINGS granted once over ADB / Shizuku
 * (`pm grant com.blaze.assistant android.permission.WRITE_SECURE_SETTINGS`) and a list of bank apps, Blaze takes itself
 * out of ENABLED_ACCESSIBILITY_SERVICES when one of them comes to the front and puts itself back when it leaves
 * (leaving is seen with Usage access, polled while the service is off).
 */
object BankMode {
    private const val K_ON = "bank_mode"
    private const val K_APPS = "bank_apps"
    private const val K_SUGGESTED = "bank_suggested"
    private const val K_AUTO = "bank_auto"
    private const val K_PAUSED = "bank_paused_for"
    private const val CH = "blaze_bank"
    private const val CH_TIP = "blaze_bank_tips"
    private const val NOTE_ID = 7301
    private const val TIP_ID = 7302
    const val WRITE_SECURE = "android.permission.WRITE_SECURE_SETTINGS"
    const val GRANT_CMD = "adb shell pm grant com.blaze.assistant android.permission.WRITE_SECURE_SETTINGS"

    private val main = Handler(Looper.getMainLooper())

    /** For the settings page, the tile and the chat banner. */
    val state = MutableStateFlow(false)

    /** Set when phone control reconnects in front of a bank app that auto mode had paused for: don't pause again for it. */
    @Volatile private var skipPkg = ""
    @Volatile private var polling = false

    val isOn: Boolean get() = try { Settings.bool(K_ON, false) } catch (_: Throwable) { false }
    private val pausedFor: String get() = Settings.str(K_PAUSED, "")

    var autoEnabled: Boolean
        get() = Settings.bool(K_AUTO, false)
        set(v) = Settings.setBool(K_AUTO, v)

    fun apps(): Set<String> = BankModeLogic.parseApps(Settings.str(K_APPS, ""))
    private fun saveApps(s: Collection<String>) = Settings.setStr(K_APPS, BankModeLogic.storeApps(s))

    fun component(ctx: Context): ComponentName = ComponentName(ctx.packageName, BlazeAccessibilityService::class.java.name)

    fun canWriteSecure(ctx: Context): Boolean = try { ctx.checkSelfPermission(WRITE_SECURE) == PackageManager.PERMISSION_GRANTED } catch (_: Throwable) { false }

    fun usageAllowed(ctx: Context): Boolean = try {
        val ops = ctx.getSystemService(android.app.AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName)
            else ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName)
        mode == android.app.AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) { false }

    /** Auto mode can run: switched on by the user, the secure-settings grant, and at least one bank app chosen. */
    fun autoAllowed(ctx: Context): Boolean = autoEnabled && canWriteSecure(ctx) && apps().isNotEmpty()

    /** Called once from BlazeApp: keeps the state flow and the auto-mode poller right after a process restart. */
    fun boot(ctx: Context) {
        state.value = isOn
        if (isOn && pausedFor.isNotEmpty()) startPolling(ctx.applicationContext)
    }

    /** Runs a parsed /bank-mode command or phrase; returns the chat reply. */
    fun run(ctx: Context, c: BankModeLogic.Cmd): String = when (c) {
        is BankModeLogic.Cmd.On -> turnOn(ctx, c.app)
        is BankModeLogic.Cmd.Off -> if (!isOn && BlazeAccessibilityService.instance != null) "Bank mode is already off." else turnOff(ctx)
        is BankModeLogic.Cmd.Toggle -> if (isOn) turnOff(ctx) else turnOn(ctx)
        is BankModeLogic.Cmd.Status -> status(ctx)
        is BankModeLogic.Cmd.Add -> addApp(ctx, c.app)
        is BankModeLogic.Cmd.Remove -> removeApp(ctx, c.app)
        is BankModeLogic.Cmd.Apps -> appsLine(ctx)
    }

    // ---------- On ----------

    /**
     * Bank mode on: overlays gone, wake word stopped, phone control switched off, the notification posted, then [openApp]
     * (a name or package) opened. [autoFor] is the bank app auto mode paused for ("" = the user asked). Returns the chat reply.
     */
    fun turnOn(ctx: Context, openApp: String? = null, autoFor: String = "", autoLabel: String = ""): String {
        val app = ctx.applicationContext
        val connected = BlazeAccessibilityService.instance != null
        val enabled = connected || PhoneControl.enabledInSettings(app)
        val canWrite = canWriteSecure(app)
        val was = isOn
        Settings.setBool(K_ON, true)
        Settings.setStr(K_PAUSED, autoFor)
        state.value = true
        try { com.blaze.assistant.device.CrashLog.write("Bank mode on" + if (autoFor.isNotEmpty()) " (auto, $autoFor)" else "") } catch (_: Throwable) { }
        val open = openApp?.takeIf { it.isNotBlank() }?.let { resolveApp(app, it) }
        main.post {
            try { com.blaze.assistant.ui.Overlay.hideForBankMode(app) } catch (_: Throwable) { }
            try { Pointer.done() } catch (_: Throwable) { }
            try { MagicHand.stop() } catch (_: Throwable) { }
            try { com.blaze.assistant.voice.WakeWordService.stop(app) } catch (_: Throwable) { }
            postNote(app, autoLabel)
            // Open the bank app while phone control is still bound (Android lets a bound accessibility service start it).
            open?.let { (pkg, _) -> try { app.packageManager.getLaunchIntentForPackage(pkg)?.let { app.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } catch (_: Throwable) { } }
        }
        main.postDelayed({
            var off = false
            BlazeAccessibilityService.instance?.let { s -> off = try { s.disableSelf(); true } catch (_: Throwable) { false } }
            if (canWrite && PhoneControl.enabledInSettings(app)) off = writeEnabled(app, false) || off
            if (autoFor.isNotEmpty()) startPolling(app)
        }, 300)
        val opening = when {
            openApp.isNullOrBlank() -> ""
            open == null -> " I couldn't find an app called \u201c$openApp\u201d, so open it yourself."
            else -> " Opening ${open.second}."
        }
        val back = "To give me my hands back, tap \u201c${BankModeLogic.NOTE_ACTION}\u201d in the notification (or type /bank-mode off) and switch on Blaze phone control."
        return when {
            was && !enabled -> "Bank mode is already on.$opening"
            !enabled -> "Bank mode on. Phone control was already off, and my bubble and pointer are gone, so banking apps can open. Chat still works.$opening"
            connected || canWrite -> "Bank mode on: my bubble and pointer are gone and Blaze phone control is switched off, so banking apps can open. Chat still works.$opening $back"
            else -> "Bank mode on, but Android shows Blaze phone control as on and not connected, so I can't switch it off myself. " +
                "Tap \u201c${BankModeLogic.NOTE_ACTION}\u201d in the notification and switch Blaze phone control off there, then open your bank app.$opening"
        }
    }

    // ---------- Off ----------

    /**
     * Bank mode off. Phone control has to come back on for it to end: with the secure-settings grant Blaze switches itself
     * back on; otherwise this opens Blaze's switch in Accessibility settings ([launch] starts it; a tile collapses the shade).
     */
    fun turnOff(ctx: Context, launch: (Intent) -> Unit = { i -> ctx.startActivity(i) }): String {
        val app = ctx.applicationContext
        if (BlazeAccessibilityService.instance != null) { if (isOn) clear(app); return "Bank mode is off and phone control is on." }
        if (canWriteSecure(app) && writeEnabled(app, true)) return "Switching Blaze phone control back on. Bank mode ends as soon as it connects."
        return try {
            launch(settingsIntent(app))
            "Opened Blaze phone control in Accessibility settings. Switch it on and bank mode ends by itself."
        } catch (_: Throwable) {
            "Open Settings \u2192 Accessibility \u2192 Blaze phone control and switch it on. Bank mode ends by itself."
        }
    }

    /** True while auto mode itself is switching Blaze back on (the bank app has left the front). */
    @Volatile private var resuming = false

    /** From BlazeAccessibilityService.onServiceConnected: the user switched phone control back on, so bank mode ends. */
    fun onServiceConnected(ctx: Context) {
        if (!isOn) return
        val paused = pausedFor
        // Switched back on by hand while still in the bank app auto mode paused for: don't pause again right away.
        if (paused.isNotEmpty() && !resuming) skipPkg = paused
        resuming = false
        clear(ctx.applicationContext)
    }

    private fun clear(app: Context) {
        Settings.setBool(K_ON, false)
        Settings.setStr(K_PAUSED, "")
        state.value = false
        polling = false
        try { NotificationManagerCompat.from(app).cancel(NOTE_ID) } catch (_: Throwable) { }
        try { com.blaze.assistant.device.CrashLog.write("Bank mode off (phone control back)") } catch (_: Throwable) { }
        // Overlays come back through onServiceConnected itself (pinned orb, wake word).
    }

    // ---------- Secure settings (WRITE_SECURE_SETTINGS only) ----------

    /** Adds or removes Blaze in ENABLED_ACCESSIBILITY_SERVICES. False without the grant or when Android refuses. */
    fun writeEnabled(ctx: Context, on: Boolean): Boolean = try {
        val cr = ctx.contentResolver
        val key = android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        val cur = android.provider.Settings.Secure.getString(cr, key)
        val comp = component(ctx).flattenToString()
        val next = if (on) BankModeLogic.add(cur, comp) else BankModeLogic.remove(cur, comp)
        val ok = android.provider.Settings.Secure.putString(cr, key, next)
        if (on && ok) android.provider.Settings.Secure.putInt(cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        ok
    } catch (_: Throwable) { false }

    // ---------- Foreground apps ----------

    /** From the accessibility service, for each new app in front (before anything else is done with the event). */
    fun onForeground(ctx: Context, pkg: String, label: String) {
        if (pkg.isEmpty()) return
        if (pkg == skipPkg) return
        if (!BankModeLogic.transient(pkg)) skipPkg = ""
        val app = ctx.applicationContext
        val chosen = apps()
        val step = BankModeLogic.autoStep(pkg, app.packageName, chosen, pausedFor, isOn && pausedFor.isEmpty(), autoAllowed(app))
        if (step == BankModeLogic.Auto.PAUSE) { turnOn(app, autoFor = pkg, autoLabel = label); return }
        val suggested = BankModeLogic.parseApps(Settings.str(K_SUGGESTED, ""))
        if (BankModeLogic.shouldSuggest(pkg, label, app.packageName, isOn, if (autoAllowed(app)) emptySet() else chosen, suggested)) {
            Settings.setStr(K_SUGGESTED, BankModeLogic.storeApps(suggested + pkg))
            suggest(app, pkg, label)
        }
    }

    /** While auto mode has Blaze paused: Usage access tells when the bank app left the front, then Blaze switches back on. */
    private fun startPolling(app: Context) {
        if (polling || !usageAllowed(app)) return
        polling = true
        var since = System.currentTimeMillis() - 5_000
        var fg = pausedFor
        val tick = object : Runnable {
            override fun run() {
                if (!polling || !isOn || pausedFor.isEmpty()) { polling = false; return }
                try {
                    val now = System.currentTimeMillis()
                    val um = app.getSystemService(android.app.usage.UsageStatsManager::class.java)
                    val evs = um.queryEvents(since - 1_000, now)
                    val list = ArrayList<Triple<Long, String, Boolean>>()
                    val e = android.app.usage.UsageEvents.Event()
                    while (evs.hasNextEvent()) {
                        evs.getNextEvent(e)
                        // 1 = MOVE_TO_FOREGROUND / ACTIVITY_RESUMED (the same value on every API level).
                        list += Triple(e.timeStamp, e.packageName.orEmpty(), e.eventType == 1)
                    }
                    since = now
                    BankModeLogic.lastForeground(list).takeIf { it.isNotEmpty() }?.let { fg = it }
                    if (BankModeLogic.autoStep(fg, app.packageName, apps(), pausedFor, false, canWriteSecure(app)) == BankModeLogic.Auto.RESUME) {
                        polling = false
                        resuming = true
                        if (!writeEnabled(app, true)) { resuming = false; postNote(app, "") }
                        return
                    }
                } catch (_: Throwable) { }
                main.postDelayed(this, 1_500)
            }
        }
        main.postDelayed(tick, 1_500)
    }

    // ---------- Bank apps list ----------

    /** Adds an app (name or package) to the bank apps. Returns the reply. */
    fun addApp(ctx: Context, name: String): String {
        val r = resolveApp(ctx.applicationContext, name) ?: return "I couldn't find an installed app called \u201c$name\u201d."
        saveApps(apps() + r.first)
        return "Added ${r.second} to your bank apps." + autoHint(ctx)
    }

    fun removeApp(ctx: Context, name: String): String {
        val cur = apps()
        val r = resolveApp(ctx.applicationContext, name)
        val pkg = r?.first ?: name.trim()
        if (pkg !in cur) return "\u201c$name\u201d isn't in your bank apps."
        saveApps(cur - pkg)
        return "Removed ${r?.second ?: name} from your bank apps."
    }

    fun appsLine(ctx: Context): String {
        val a = apps()
        if (a.isEmpty()) return "No bank apps chosen yet. Add one with /bank-mode add <app>." + autoHint(ctx)
        return "Bank apps: " + a.joinToString(", ") { labelOf(ctx, it) } + "." + autoHint(ctx)
    }

    private fun autoHint(ctx: Context): String = when {
        !canWriteSecure(ctx) -> ""
        !autoEnabled -> " Auto mode is off (Settings \u2192 Phone control \u2192 Bank mode)."
        else -> " Auto mode switches me off whenever one of them opens."
    }

    fun status(ctx: Context): String {
        val app = ctx.applicationContext
        return when {
            isOn && pausedFor.isNotEmpty() -> "Bank mode is on (auto, for ${labelOf(app, pausedFor)}). Phone control comes back when you leave it."
            isOn -> "Bank mode is on: phone control is off and my overlays are gone. Type /bank-mode off to switch me back on."
            else -> "Bank mode is off." + if (canWriteSecure(app)) " Auto mode is ${if (autoEnabled) "on" else "off"}." else ""
        }
    }

    fun labelOf(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager; pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) { pkg }

    /** (package, label) for an installed launcher app named [name] (or given by package), else null. */
    fun resolveApp(ctx: Context, name: String): Pair<String, String>? {
        val pm = ctx.packageManager
        val n = name.trim()
        if (n.contains('.') && !n.contains(' ') && pm.getLaunchIntentForPackage(n) != null) return n to labelOf(ctx, n)
        val q = AppMatch.clean(n)
        if (q.isEmpty()) return null
        val apps = try { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0) } catch (_: Throwable) { emptyList() }
        val best = apps.map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .mapNotNull { (p, l) -> AppMatch.score(q, l, p)?.let { s -> Triple(p, l, s) } }
            .filter { it.first != ctx.packageName }
            .sortedWith(compareBy({ it.third }, { -it.second.length }))
            .firstOrNull() ?: return null
        return best.first to best.second
    }

    // ---------- Notifications ----------

    private fun channels(app: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH, "Bank mode", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while Blaze's phone control is paused for banking apps"; setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(CH_TIP, "Bank mode tips", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Once per banking app: offer to turn on Bank mode" })
    }

    /**
     * Blaze's own switch in Accessibility settings, as directly as Android allows: the details page on Android 11+,
     * else the list with Blaze's row highlighted (the ":settings:fragment_args_key" extras Settings understands).
     */
    fun settingsIntent(ctx: Context): Intent {
        val cn = component(ctx).flattenToString()
        if (Build.VERSION.SDK_INT >= 30) {
            val d = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { if (d.resolveActivity(ctx.packageManager) != null) return d } catch (_: Throwable) { }
        }
        val args = Bundle().apply { putString(":settings:fragment_args_key", cn) }
        return Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", cn)
            .putExtra(":settings:show_fragment_args", args)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun flags() = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun postNote(app: Context, autoLabel: String) {
        try {
            channels(app)
            // With the grant, one tap switches Blaze back on; otherwise it opens the switch in Accessibility settings.
            val back = if (canWriteSecure(app))
                PendingIntent.getBroadcast(app, 7311, Intent(app, BankModeReceiver::class.java).setAction(BankModeReceiver.OFF), flags())
            else PendingIntent.getActivity(app, 7312, settingsIntent(app), flags())
            val open = app.packageManager.getLaunchIntentForPackage(app.packageName)?.let { PendingIntent.getActivity(app, 7313, it, flags()) }
            val title = if (autoLabel.isNotEmpty()) BankModeLogic.autoNoteTitle(autoLabel) else BankModeLogic.NOTE_TITLE
            val text = if (autoLabel.isNotEmpty() && usageAllowed(app)) BankModeLogic.autoNoteText() else BankModeLogic.NOTE_TEXT
            val b = NotificationCompat.Builder(app, CH).setSmallIcon(R.drawable.ic_stat_blaze)
                .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW).setCategory(NotificationCompat.CATEGORY_STATUS)
                .addAction(0, BankModeLogic.NOTE_ACTION, back)
            open?.let { b.setContentIntent(it) }
            NotificationManagerCompat.from(app).notify(NOTE_ID, b.build())
        } catch (_: Throwable) { }
    }

    /** The one-time "Bank app opened? Turn on Bank mode" offer for [pkg]. */
    private fun suggest(app: Context, pkg: String, label: String) {
        try {
            channels(app)
            val on = PendingIntent.getBroadcast(app, 7321 + (pkg.hashCode() and 0xfff),
                Intent(app, BankModeReceiver::class.java).setAction(BankModeReceiver.ON).putExtra(BankModeReceiver.EXTRA_PKG, pkg), flags())
            val always = PendingIntent.getBroadcast(app, 9321 + (pkg.hashCode() and 0xfff),
                Intent(app, BankModeReceiver::class.java).setAction(BankModeReceiver.ADD).putExtra(BankModeReceiver.EXTRA_PKG, pkg), flags())
            val name = label.ifBlank { pkg }
            val text = "If $name says to turn off accessibility, Bank mode switches Blaze's phone control off until you turn it back on."
            val b = NotificationCompat.Builder(app, CH_TIP).setSmallIcon(R.drawable.ic_stat_blaze)
                .setContentTitle("$name opened? Turn on Bank mode").setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true)
                .addAction(0, "Turn on Bank mode", on)
            if (canWriteSecure(app)) b.addAction(0, "Always for $name", always)
            NotificationManagerCompat.from(app).notify(TIP_ID, b.build())
        } catch (_: Throwable) { }
    }

    internal fun cancelTip(app: Context) { try { NotificationManagerCompat.from(app).cancel(TIP_ID) } catch (_: Throwable) { } }
}

/** Notification actions: turn bank mode on (from the one-time offer), add an app to the list, switch Blaze back on. */
class BankModeReceiver : BroadcastReceiver() {
    companion object {
        const val ON = "com.blaze.assistant.BANK_MODE_ON"
        const val OFF = "com.blaze.assistant.BANK_MODE_OFF"
        const val ADD = "com.blaze.assistant.BANK_MODE_ADD"
        const val EXTRA_PKG = "pkg"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        val pkg = intent?.getStringExtra(EXTRA_PKG).orEmpty()
        try {
            when (intent?.action) {
                ON -> { BankMode.cancelTip(app); BankMode.turnOn(app, openApp = pkg.ifEmpty { null }) }
                ADD -> {
                    BankMode.cancelTip(app)
                    if (pkg.isNotEmpty()) BankMode.addApp(app, pkg)
                    BankMode.autoEnabled = true
                    BankMode.turnOn(app, autoFor = pkg, autoLabel = BankMode.labelOf(app, pkg), openApp = pkg.ifEmpty { null })
                }
                OFF -> BankMode.turnOff(app) { i -> try { app.startActivity(i) } catch (_: Throwable) { } }
            }
        } catch (_: Throwable) { }
    }
}

/** Quick Settings tile: one tap turns bank mode on; when it is on, a tap switches Blaze back on. */
class BankModeTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    private fun refresh() {
        val t = qsTile ?: return
        val on = BankMode.isOn
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Bank mode"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (on) "Blaze paused" else "Off"
        t.updateTile()
    }

    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        if (!BankMode.isOn) BankMode.turnOn(this)
        else BankMode.turnOff(this) { i ->
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 7331, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            else { @Suppress("DEPRECATION") startActivityAndCollapse(i) }
        }
        refresh()
    }
}
