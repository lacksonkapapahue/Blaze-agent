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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.SkillLibrary

/** Settings › Skill library: bundled skills (from Blaze Desktop) and your own, on/off, delete, restore. */
@Composable
fun SkillLibraryPage() {
    val c = LocalBlaze.current
    var tick by remember { mutableStateOf(0) }
    var open by remember { mutableStateOf("") }
    val rows = remember(tick) { SkillLibrary.rows() }
    Spacer(Modifier.height(10.dp))
    Text("Methods Blaze reads before a matching task: research, emails, plans, documents, data, code and more. " +
        "Bundled skills update themselves unless you edit them; a skill you delete stays gone until you restore it.",
        modifier = Modifier.padding(bottom = 12.dp),
        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 18.sp, color = c.sub))
    if (rows.isEmpty()) Label("No skills yet.", color = c.weak, size = 10.sp)
    rows.forEach { r ->
        Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).clickable { open = if (open == r.name) "" else r.name }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(if (r.installed && r.on) c.accent else c.weak)); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.name, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 15.sp,
                    color = if (r.installed && r.on) c.ink else c.weak))
                val tag = when {
                    !r.installed -> "Deleted · bundled"
                    r.edited -> "Bundled · edited by you"
                    r.bundled -> "Bundled"
                    else -> "Yours"
                }
                Label(tag + if (r.update) " · update available" else "", color = c.weak, size = 9.sp)
            }
            if (r.installed) Label(if (r.on) "On" else "Off", color = if (r.on) c.accent else c.sub, size = 10.sp,
                modifier = Modifier.clickable { SkillLibrary.setOn(r.name, !r.on); tick++ }.padding(8.dp))
        }
        if (open == r.name) Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            if (r.description.isNotBlank()) Text(r.description, modifier = Modifier.padding(bottom = 10.dp),
                style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 18.sp, color = c.sub))
            Row {
                if (r.installed) Label("Delete", color = c.accent, size = 10.sp,
                    modifier = Modifier.clickable { SkillLibrary.delete(r.name); open = ""; tick++ }.padding(end = 18.dp, top = 6.dp, bottom = 6.dp))
                if (r.bundled && (!r.installed || r.edited || r.update)) Label(if (r.installed) "Restore original" else "Restore", color = c.accent, size = 10.sp,
                    modifier = Modifier.clickable { SkillLibrary.restore(r.name); tick++ }.padding(vertical = 6.dp))
            }
        }
    }
}
