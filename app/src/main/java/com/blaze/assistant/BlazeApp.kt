package com.blaze.assistant

import android.app.Application
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.Settings

class BlazeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.blaze.assistant.device.CrashLog.init(this)
        com.blaze.assistant.device.CrashLog.shieldMainThread()
        Settings.init(this)
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
        com.blaze.assistant.voice.Voice.init(this)
        // Wake word starts from the screen (MainActivity/assistant) and at boot: starting a
        // microphone service while Blaze is in the background makes Android 12+ kill the app.
        com.blaze.assistant.terminal.Terminal.init(this)
        com.blaze.assistant.agent.SkillLibrary.init(this)
        Thread { com.blaze.assistant.agent.SkillPacks.ensureStarters(); com.blaze.assistant.agent.SkillLibrary.seed(this) }.start()
        com.blaze.assistant.proactive.Triggers.init(this)
        com.blaze.assistant.proactive.Proactive.start(this)
        com.blaze.assistant.ui.Overlay.watchTasks(this)
        com.blaze.assistant.link.DesktopLink.start()
        if (Settings.bool("dash_auto", false)) com.blaze.assistant.dashboard.Dashboard.start(this)
        if (Settings.orbPinned && android.provider.Settings.canDrawOverlays(this)) com.blaze.assistant.ui.Overlay.showOrb(this)
    }
}
