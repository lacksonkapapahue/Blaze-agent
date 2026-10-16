package com.blaze.assistant.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.phone.BankMode

/**
 * v0.30.9 Settings → Phone control → Bank mode: the switch, the bank apps list (for the one-time offer and auto mode),
 * and auto mode when Blaze holds WRITE_SECURE_SETTINGS.
 */
@Composable
internal fun BankModeSection() {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val on by BankMode.state.collectAsState()
    var apps by remember { mutableStateOf(BankMode.apps().toList()) }
    var adding by remember { mutableStateOf("") }
    var auto by remember { mutableStateOf(BankMode.autoEnabled) }
    val canAuto = remember { BankMode.canWriteSecure(ctx) }
    val usage = remember { BankMode.usageAllowed(ctx) }
    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

    Spacer(Modifier.height(18.dp))
    Label("Bank mode", modifier = Modifier.padding(bottom = 4.dp))
    ToggleRow("Bank mode", if (on) "On: phone control and Blaze's overlays are paused" else "Banking apps refuse to open while phone control is on", on) {
        toast(if (on) BankMode.turnOff(ctx) else BankMode.turnOn(ctx))
    }
    Text("Turning it on removes Blaze's bubble and pointer and switches phone control off, so banking apps run. Chat keeps working. " +
        "It ends when you switch Blaze phone control back on (the notification's \u201cTurn Blaze back on\u201d). Also: /bank-mode, \u201cbank mode on\u201d, " +
        "/bank-mode open <app>, or the Bank mode tile in Quick Settings.",
        modifier = Modifier.padding(vertical = 8.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 18.sp, color = c.sub))

    Label("Bank apps", modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
    if (apps.isEmpty()) Text("None yet. Blaze also offers Bank mode once when an app that looks like a bank opens.",
        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.weak))
    Column(Modifier.fillMaxWidth()) {
        apps.forEach { pkg ->
            Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(BankMode.labelOf(ctx, pkg), modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.body))
                Text("\u00d7", modifier = Modifier.clickable { BankMode.removeApp(ctx, pkg); apps = BankMode.apps().toList() }.padding(10.dp),
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 16.sp, color = c.sub))
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Field("Add an app", adding, { adding = it }, "Zanaco")
    Text("Add", modifier = Modifier.clickable {
        if (adding.isNotBlank()) { toast(BankMode.addApp(ctx, adding.trim())); apps = BankMode.apps().toList(); adding = "" }
    }.padding(vertical = 10.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.accent))

    if (canAuto) {
        ToggleRow("Auto bank mode", if (usage) "Pause Blaze when a bank app opens, back when you leave it" else "Pause Blaze when a bank app opens (Usage access brings it back by itself)", auto) {
            auto = !auto; BankMode.autoEnabled = auto
        }
    } else {
        Text("Auto bank mode (optional): grant once from a computer or Shizuku, then it shows here:\n${BankMode.GRANT_CMD}",
            modifier = Modifier.padding(top = 8.dp), style = TextStyle(fontFamily = Type.Mono, fontSize = 11.sp, lineHeight = 16.sp, color = c.weak))
    }
}
