package com.blaze.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.update.Updater
import com.blaze.assistant.update.Updater.State

/** Checking → Downloading N% → Installing, with a plain error and Retry. */
@Composable
fun UpdateCard() {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val st by Updater.state.collectAsState()
    val (title, line, pct) = when (val s = st) {
        is State.Idle -> Triple("Blaze update", "You're on v${Updater.installed(ctx)}.", -1)
        is State.Checking -> Triple("Checking", "Looking for a newer Blaze…", -1)
        is State.Downloading -> Triple("Downloading ${s.pct}%", "Blaze v${s.version}", s.pct)
        is State.Installing -> Triple("Installing", "Confirm on the Android screen. Blaze restarts by itself, and your keys, settings and models stay.", 100)
        is State.UpToDate -> Triple("Up to date", "You have the newest Blaze, v${s.version}.", -1)
        is State.Ready -> Triple("Update available", "Blaze v${s.version} is ready to install.", -1)
        is State.NeedsPermission -> Triple("One permission", "Turn on \"Allow from this source\" for Blaze, come back, then tap Retry.", -1)
        is State.Done -> Triple("Updated", "Blaze v${s.version} is installed.", 100)
        is State.Failed -> Triple("Update didn't work", s.message, -1)
    }
    val busy = st is State.Checking || st is State.Downloading || st is State.Installing
    val bad = st is State.Failed
    Column(Modifier.widthIn(max = 330.dp).fillMaxWidth().background(c.paper, BlazeShapes.medium).hairline(if (bad) c.accent else c.hair).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (busy) Shimmer(title, Modifier.weight(1f)) else Label(title, color = if (bad) c.accent else c.ink, size = 10.sp, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Text(line, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 20.sp, color = c.body))
        if (pct >= 0) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).background(c.faint)) {
                Box(Modifier.fillMaxWidth(pct / 100f).height(4.dp).background(c.accent))
            }
        }
        val action = when (st) {
            is State.Failed, is State.NeedsPermission -> "Retry"
            is State.Ready -> "Install"
            is State.Idle, is State.UpToDate -> "Check again"
            else -> null
        }
        if (action != null) {
            Spacer(Modifier.height(10.dp))
            ActionText(action, color = c.accent, size = 10.sp, modifier = Modifier.hairline(c.accent).clickable { Updater.retry(ctx) }.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}
