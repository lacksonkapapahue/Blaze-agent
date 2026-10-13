package com.blaze.assistant.ui

import android.content.Context
import android.widget.Toast
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.Command
import com.blaze.assistant.agent.Commands
import com.blaze.assistant.agent.Memory
import com.blaze.assistant.agent.ModelCatalog
import com.blaze.assistant.agent.Personas
import com.blaze.assistant.agent.Providers
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.agent.Usage

/** A suggestion shown above the input while typing a /command. [fill] replaces the input text. */
data class Suggestion(val title: String, val sub: String, val fill: String, val runNow: Boolean)

object CommandRunner {
    /** Suggestions for what's typed, or empty when it isn't a command. */
    fun suggest(text: String): List<Suggestion> {
        if (!text.startsWith("/")) return emptyList()
        val name = text.removePrefix("/").substringBefore(' ').lowercase()
        val hasSpace = text.contains(' ')
        val arg = text.substringAfter(' ', "").trim().lowercase()
        if (hasSpace) {
            return when (name) {
                "model" -> {
                    val models = (ModelCatalog.cached()?.models?.map { it.id to it.note } ?: emptyList())
                        .ifEmpty { listOf(Settings.model to "current") }
                    models.filter { arg.isEmpty() || it.first.lowercase().contains(arg) }.take(12)
                        .map { (id, note) -> Suggestion(id, if (id == Settings.model) "current" else note, "/model $id", true) }
                }
                "provider" -> Providers.all.filter { arg.isEmpty() || it.name.lowercase().contains(arg) || it.id.contains(arg) }
                    .map { Suggestion(it.name, if (it.id == Settings.providerId) "current" else "", "/provider ${it.id}", true) }
                "persona" -> Personas.all.filter { it.id != Personas.CUSTOM && (arg.isEmpty() || it.name.lowercase().contains(arg) || it.id.contains(arg)) }
                    .map { Suggestion(it.name, it.blurb, "/persona ${it.id}", true) }
                else -> emptyList()
            }
        }
        return Commands.matching(text).take(30).map { c ->
            val needsArg = c.args.startsWith("<")
            Suggestion("/" + c.name + if (c.args.isNotEmpty()) " " + c.args else "", c.desc,
                "/" + c.name + if (c.args.isNotEmpty()) " " else "", runNow = c.args.isEmpty())
        }
    }

    /**
     * Run a typed command. Returns the text to send to Blaze instead (for prompt commands),
     * "" when the command was handled here, or null when it isn't a command.
     * [openSettings] opens a settings page by id ("" = list). The chat screen handles /clear itself (with undo);
     * here it clears without one (the floating orb).
     */
    fun run(ctx: Context, text: String, openSettings: (String) -> Unit, listen: () -> Unit): String? {
        if (!text.startsWith("/") || text.startsWith("//")) return null
        val name = text.removePrefix("/").substringBefore(' ').lowercase()
        val arg = text.substringAfter(' ', "").trim()
        fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
        when (name) {
            "new" -> Agent.clear()
            "clear" -> if (Agent.clearScreen() == com.blaze.assistant.agent.ClearScreen.Result.BUSY) toast("Blaze is working. Stop it first, then /clear.")
            "model" -> if (arg.isEmpty()) openSettings("ai") else { Settings.model = arg; Agent.note("Model: $arg") }
            "provider" -> {
                val p = Providers.all.firstOrNull { it.id.equals(arg, true) || it.name.equals(arg, true) || it.name.lowercase().startsWith(arg.lowercase()) }
                if (p == null) openSettings("ai") else { Settings.providerId = p.id; if (p.id != com.blaze.assistant.agent.Providers.AUTO && Settings.stepsProvider == com.blaze.assistant.agent.Providers.AUTO) Settings.stepsProvider = ""; Agent.note("Provider: ${p.name} · ${Settings.model}") }
            }
            "persona" -> {
                val p = Personas.all.firstOrNull { it.id.equals(arg, true) || it.name.equals(arg, true) }
                if (p == null) openSettings("persona") else { Settings.personaId = p.id; Agent.note("Personality: ${p.name}") }
            }
            // v0.24.0: no chat list any more; one conversation, and Blaze looks older words up.
            "chats", "history", "archive" -> Agent.note("There's one conversation now, so there's no chat list. Ask me about anything you said before and I'll look it up.", asReply = true)
            "settings" -> openSettings("")
            "retry" -> Agent.retryLast()
            "update", "upgrade" -> com.blaze.assistant.update.Updater.start(ctx)
            "stop" -> Agent.stop()
            "plan" -> { com.blaze.assistant.agent.Modes.setWork("plan"); Agent.note("Plan mode: I'll only read and look, then give you a plan. /build to let me act.")
                if (arg.isNotEmpty()) return arg }
            "build" -> { com.blaze.assistant.agent.Modes.setWork("build"); Agent.note("Build mode: full access.") }
            "autopilot" -> { com.blaze.assistant.agent.Modes.setApproval("autopilot")
                Agent.note("Autopilot on: I keep going without asking, up to ${com.blaze.assistant.agent.Modes.apSteps} steps or ${com.blaze.assistant.agent.Modes.apMinutes} min. Deleting, paying and messaging people still need your tap.") }
            "auto" -> { com.blaze.assistant.agent.Modes.setApproval("auto"); Agent.note("Auto: I'll ask before risky actions.") }
            "agent" -> if (arg.isEmpty()) toast("Type the task after /agent") else Agent.startAgentFromUser(arg)
            "agents" -> Agent.note(com.blaze.assistant.agent.BgAgents.run("list_agents", org.json.JSONObject(), ""), asReply = true)
            "remember" -> if (arg.isNotEmpty()) { Memory.add(arg); Agent.note("Remembered: $arg") } else openSettings("memory")
            "forget" -> if (arg.isNotEmpty()) Agent.note(Memory.forget(arg)) else openSettings("memory")
            "memory" -> openSettings("memory")
            "routines" -> openSettings("routines")
            "usage", "cost" -> {
                val t = Usage.today(); val m = Usage.month()
                fun f(x: Usage.Totals) = "${x.input + x.output} tokens, ${x.calls} calls" + if (x.priced) ", about $%.3f".format(x.cost) else ""
                Agent.note("Today: ${f(t)}\nThis month: ${f(m)}", asReply = true)
            }
            "think", "reasoning" -> { Settings.showReasoning = !Settings.showReasoning; toast("Reasoning " + if (Settings.showReasoning) "shown" else "hidden") }
            "voice", "listen" -> listen()
            "orb" -> if (!Overlay.showOrb(ctx)) Overlay.requestOverlayPermission(ctx)
            "logs", "livelogs", "lifelogs" -> com.blaze.assistant.life.LifeLogs.toggleFromCommand(ctx, arg)
            "brainstorm" -> com.blaze.assistant.life.LifeLogs.toggleFromCommand(ctx, if (com.blaze.assistant.life.LifeLogs.active && arg.isBlank()) "stop" else "brainstorm")
            "copy" -> {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Blaze chat", Agent.transcript())); toast("Chat copied")
            }
            "help", "commands" -> Agent.note(Commands.all().joinToString("\n") { c ->
                "/" + c.name + (if (c.args.isNotEmpty()) " " + c.args else "") + " — " + c.desc }, asReply = true)
            else -> {
                val c: Command = Commands.all().firstOrNull { it.name == name && it.prompt != null } ?: return null
                return Commands.expand(c, arg)
            }
        }
        return ""
    }
}
