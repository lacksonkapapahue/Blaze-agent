package com.blaze.assistant

import android.app.Application
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.Settings
import kotlinx.coroutines.launch

class BlazeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.blaze.assistant.device.CrashLog.init(this)
        com.blaze.assistant.device.CrashLog.shieldMainThread()
        Settings.init(this)
        // v0.23.4: Auto's per-provider model lists and per-model cooldowns (new files, no prefs changes).
        try { com.blaze.assistant.agent.ProviderCatalog.init(filesDir); com.blaze.assistant.agent.ModelMemory.init(filesDir) } catch (_: Exception) { }
        try { com.blaze.assistant.agent.Projects.load() } catch (_: Exception) { }
        // v1.9.10 fix: a leftover "Auto" steps model silently sent phone steps through the free chain
        // even after the user picked a specific model. Clear it once; they can set it again.
        if (Settings.str("mig_steps_auto", "").isEmpty()) {
            if (Settings.providerId != com.blaze.assistant.agent.Providers.AUTO && Settings.stepsProvider == com.blaze.assistant.agent.Providers.AUTO) Settings.stepsProvider = ""
            Settings.setStr("mig_steps_auto", "1")
        }
        com.blaze.assistant.agent.IntentCache.init(this)
        com.blaze.assistant.phone.Watchers.init(this)
        com.blaze.assistant.agent.Autonomy.init(this)
        com.blaze.assistant.agent.Processes.init(this)
        // One mind (v0.18.0): mind.db first; Memory is now a view of its memories table.
        com.blaze.assistant.agent.Mind.init(this)
        com.blaze.assistant.agent.Memory.init(this)
        com.blaze.assistant.agent.Usage.init(this)
        try { com.blaze.assistant.agent.ToolStats.init(this) } catch (_: Exception) { }
        com.blaze.assistant.agent.Learning.init(this)
        com.blaze.assistant.connect.Mcp.load()
        com.blaze.assistant.connect.BlazeAccount.start()
        com.blaze.assistant.agent.Traces.init(this)
        Agent.init(this)
        com.blaze.assistant.agent.BgAgents.init(this)
        com.blaze.assistant.routine.Routines.init(this)
        com.blaze.assistant.notify.Notifier.init(this)
        try { com.blaze.assistant.update.Updater.checkInBackground(this) } catch (_: Throwable) { }
        Settings.tellIfSecretsDropped()
        com.blaze.assistant.voice.Voice.init(this)
        // Wake word starts from the screen (MainActivity/assistant) and at boot: starting a
        // microphone service while Blaze is in the background makes Android 12+ kill the app.
        com.blaze.assistant.terminal.Terminal.init(this)
        com.blaze.assistant.agent.SkillLibrary.init(this)
        // BlazeRouter: start when it's the brain or set to start with Blaze (after Terminal/Linux are ready).
        if (com.blaze.assistant.agent.BlazeRouter.autostart || Settings.providerId == com.blaze.assistant.agent.BlazeRouter.ID) {
            val app = this
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
                try { com.blaze.assistant.agent.BlazeRouter.start(app) } catch (_: Throwable) { }
            }
        }
        Thread { com.blaze.assistant.agent.SkillPacks.ensureStarters(); com.blaze.assistant.agent.SkillLibrary.seed(this) }.start()
        try { com.blaze.assistant.agent.MindReview.schedule(this) } catch (_: Throwable) { }
        com.blaze.assistant.proactive.Triggers.init(this)
        com.blaze.assistant.proactive.Proactive.start(this)
        com.blaze.assistant.ui.Overlay.watchTasks(this)
        com.blaze.assistant.link.DesktopLink.start()
        if (Settings.bool("dash_auto", false)) com.blaze.assistant.dashboard.Dashboard.start(this)
        if (Settings.orbPinned && android.provider.Settings.canDrawOverlays(this)) com.blaze.assistant.ui.Overlay.showOrb(this)
    }
}
