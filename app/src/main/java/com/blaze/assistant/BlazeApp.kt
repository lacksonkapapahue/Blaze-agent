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
        com.blaze.assistant.agent.IntentCache.init(this)
        com.blaze.assistant.agent.Memory.init(this)
        com.blaze.assistant.agent.Usage.init(this)
        com.blaze.assistant.agent.Learning.init(this)
        com.blaze.assistant.connect.Mcp.load()
        Agent.init(this)
        com.blaze.assistant.routine.Routines.init(this)
        com.blaze.assistant.notify.Notifier.init(this)
        com.blaze.assistant.voice.Voice.init(this)
        // Wake word starts from the screen (MainActivity/assistant) and at boot: starting a
        // microphone service while Blaze is in the background makes Android 12+ kill the app.
        com.blaze.assistant.terminal.Terminal.init(this)
        com.blaze.assistant.proactive.Triggers.init(this)
        com.blaze.assistant.proactive.Proactive.start(this)
        com.blaze.assistant.ui.Overlay.watchTasks(this)
        if (Settings.bool("dash_auto", false)) com.blaze.assistant.dashboard.Dashboard.start(this)
        if (Settings.orbPinned && android.provider.Settings.canDrawOverlays(this)) com.blaze.assistant.ui.Overlay.showOrb(this)
    }
}
