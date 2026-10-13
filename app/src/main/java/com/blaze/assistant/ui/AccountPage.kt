package com.blaze.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.connect.BlazeAccount
import com.blaze.assistant.connect.KeySync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.draw.clip

/** Settings › Account: the Blaze account shared with Blaze Desktop. */
@Composable
fun AccountPage() {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val v by BlazeAccount.view.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { BlazeAccount.refreshProviders(); BlazeAccount.checkSession() } }

    Spacer(Modifier.height(10.dp))
    val u = v.user
    if (u != null) {
        // ---------- signed in ----------
        Row(Modifier.fillMaxWidth().hairline(c.hair).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(BlazeShapes.pill).background(c.ink, BlazeShapes.pill), contentAlignment = Alignment.Center) {
                Text((u.name.ifBlank { u.email }).take(1).uppercase(),
                    style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 18.sp, color = c.paper))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(u.name.ifBlank { u.email }, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = c.ink))
                if (u.name.isNotBlank()) Text(u.email, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
                val how = (u.providers.map { BlazeAccount.title(it) } + if (u.hasPassword) listOf("Email") else emptyList()).distinct()
                if (how.isNotEmpty()) Label("Signs in with " + how.joinToString(", "), color = c.weak, size = 9.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Note("The same account works in Blaze Desktop and Blaze CLI. Your model API keys and your own skills follow it, encrypted, so a key or skill added on your PC works here and the other way round. Connected accounts stay on this phone.", top = 12)
        if (v.message.isNotEmpty()) Note(v.message)
        val keyStatus by KeySync.status.collectAsState()
        var syncing by remember { mutableStateOf(false) }
        AccountButton(if (syncing) "Syncing keys…" else "Sync keys now", primary = false) {
            if (syncing) return@AccountButton
            syncing = true
            scope.launch { KeySync.sync(); syncing = false }
        }
        if (keyStatus.isNotEmpty()) Note(keyStatus, top = 8)
        val skillStatus by com.blaze.assistant.connect.SkillSync.status.collectAsState()
        var syncingSkills by remember { mutableStateOf(false) }
        Spacer(Modifier.height(8.dp))
        AccountButton(if (syncingSkills) "Syncing skills…" else "Sync skills now", primary = false) {
            if (syncingSkills) return@AccountButton
            syncingSkills = true
            scope.launch { com.blaze.assistant.connect.SkillSync.sync(); syncingSkills = false }
        }
        if (skillStatus.isNotEmpty()) Note(skillStatus, top = 8)
        Spacer(Modifier.height(8.dp))
        AccountButton("Sign out", primary = false) { scope.launch { BlazeAccount.signOut() } }
        Spacer(Modifier.height(24.dp))
        var confirm by remember { mutableStateOf(false) }
        if (!confirm) ActionText("Delete account", color = c.accent, size = 10.sp, modifier = Modifier.clickable { confirm = true }.padding(vertical = 8.dp))
        else {
            Note("This deletes your Blaze account, its sign-ins and sessions everywhere. Chats, keys and connected accounts on this phone stay.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f)) { AccountButton("Keep it", primary = false) { confirm = false } }
                Box(Modifier.weight(1f)) {
                    AccountButton(if (busy) "Deleting…" else "Delete for good") {
                        if (busy) return@AccountButton
                        busy = true
                        scope.launch { err = BlazeAccount.delete(); busy = false; confirm = false }
                    }
                }
            }
        }
        if (err.isNotEmpty()) Note(err, top = 8)
        return
    }

    // ---------- signed out ----------
    var signUp by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    Note("One Blaze account for your phone and Blaze Desktop. Optional: everything works without it.")
    if (!v.online) Note("Can't reach Blaze's sign-in service right now. Check your connection.")

    if (v.pending.isNotEmpty()) {
        Note("Finish signing in with ${BlazeAccount.title(v.pending)} in your browser, then come back.")
        AccountButton("Cancel", primary = false) { BlazeAccount.cancel() }
    } else {
        for (p in listOf("google", "github")) if (v.login[p] == true) {
            AccountButton("Continue with ${BlazeAccount.title(p)}", primary = false) {
                err = BlazeAccount.signInWith(ctx, p)
            }
            Spacer(Modifier.height(8.dp))
        }
        if (v.login["google"] == true || v.login["github"] == true) Label("or with email", color = c.weak, size = 9.sp, modifier = Modifier.padding(vertical = 10.dp))
    }

    Spacer(Modifier.height(6.dp))
    if (signUp) Field("Name", name, { name = it }, "Optional")
    Field("Email", email, { email = it }, "you@example.com")
    Field("Password", pass, { pass = it }, if (signUp) "At least 8 characters" else "Your password", secret = true)
    AccountButton(if (busy) "One moment…" else if (signUp) "Create account" else "Sign in") {
        if (busy) return@AccountButton
        busy = true; err = ""; BlazeAccount.clearMessage()
        scope.launch {
            err = BlazeAccount.email(signUp, name, email, pass)
            if (err.isEmpty()) pass = ""
            busy = false
        }
    }
    Label(if (signUp) "Have an account? Sign in" else "New to Blaze? Create an account", color = c.accent, size = 10.sp,
        modifier = Modifier.clickable { signUp = !signUp; err = "" }.padding(vertical = 14.dp))
    val shown = err.ifEmpty { v.message }
    if (shown.isNotEmpty()) Note(shown)
}

@Composable
private fun Note(text: String, top: Int = 0) {
    val c = LocalBlaze.current
    Text(text, modifier = Modifier.padding(top = top.dp, bottom = 10.dp),
        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 18.sp, color = c.sub))
}

@Composable
private fun AccountButton(text: String, primary: Boolean = true, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Box(Modifier.fillMaxWidth().height(44.dp).background(if (primary) c.ink else c.paper, BlazeShapes.medium).hairline(if (primary) c.ink else c.hair)
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) { ButtonText(text, color = if (primary) c.paper else c.ink, size = 13.sp) }
}
