package com.blaze.assistant.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.TelephonyManager
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.notify.NotificationBrain
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Text Mary on WhatsApp: on my way." Background first: reply through the chat's live notification
 * if there is one, SMS straight from the phone, or WhatsApp opened on the chat with the message typed,
 * and Send pressed for you when phone control is on.
 */
object Messaging {
    const val NAME = "message_contact"

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", NAME)
        .put("description", "Send a message to a person on WhatsApp, SMS, Telegram or another chat app. Replies through the chat's notification in the background when it can. Prefer this over driving the app on screen. The user approves first.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("to", JSONObject().put("type", "string").put("description", "Contact name or phone number"))
            .put("text", JSONObject().put("type", "string").put("description", "Message text"))
            .put("app", JSONObject().put("type", "string").put("enum", JSONArray(listOf("whatsapp", "sms", "telegram", "any")))
                .put("description", "Which app; 'any' = whichever chat they last messaged from, else WhatsApp")))
            .put("required", JSONArray(listOf("to", "text")))))

    private fun appLabel(a: String) = when (a) { "sms" -> "Messages"; "telegram" -> "Telegram"; "whatsapp" -> "WhatsApp"; else -> "" }

    /**
     * [always]: kept for callers; since v0.28.2 every send in the user's name shows the confirm card whatever the
     * standing permissions or Autopilot say. [from]: who drafted it, shown on the card.
     */
    suspend fun run(ctx: Context, a: JSONObject, always: Boolean = false, from: String = ""): String {
        val by = if (from.isBlank()) "" else "Drafted on $from. "
        val to = a.optString("to").trim(); val text = a.optString("text").trim()
        val app = a.optString("app", "any").lowercase()
        if (to.isEmpty() || text.isEmpty()) return "Need who to message and what to say."

        // 1) Live notification from them: reply in the background.
        val live = NotificationBrain.replyable(to, appLabel(app).ifBlank { null })
        if (live != null) {
            if (!Agent.confirm("Reply to ${live.title}?", "$by\u201c$text\u201d (in ${live.app})", "Send", always = true)) return "The user declined. Nothing was sent."
            if (NotificationBrain.sendReply(ctx, live, text)) return "Sent to ${live.title} in ${live.app}, in the background."
        }

        // 2) SMS: straight from the phone.
        if (app == "sms") return Skills.run(ctx, "send_sms", JSONObject().put("to", to).put("message", text))

        // 3) WhatsApp / Telegram: open the chat with the text, then press Send.
        val contact = Skills.resolve(ctx, to) ?: return "I couldn't find one contact called \u201c$to\u201d. Give the number, or the full name as saved."
        val number = international(ctx, contact.second)
        val label = if (app == "telegram") "Telegram" else "WhatsApp"
        if (!Agent.confirm("Message ${contact.first} on $label?", "$by\u201c$text\u201d", "Send", always = true)) return "The user declined. Nothing was sent."
        val intent = if (app == "telegram") Intent(Intent.ACTION_VIEW, Uri.parse("tg://msg?to=+$number&text=" + Uri.encode(text)))
            else Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$number&text=" + Uri.encode(text)))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pkgs = if (app == "telegram") listOf("org.telegram.messenger", "org.telegram.messenger.web") else listOf("com.whatsapp", "com.whatsapp.w4b")
        val pkg = pkgs.firstOrNull { p -> try { ctx.packageManager.getPackageInfo(p, 0); true } catch (_: Exception) { false } }
            ?: return "$label isn't installed. Want me to send it as an SMS instead?"
        try { ctx.startActivity(intent.setPackage(pkg)) } catch (e: Exception) { return "Couldn't open $label: ${e.message}" }
        val svc = BlazeAccessibilityService.instance ?: return "$label is open on ${contact.first}'s chat with the message typed. Phone control is off, so tap Send."
        val send = svc.waitFor("Send", 8000) ?: return "$label opened with the message typed, but I couldn't find Send. Tap it to finish."
        svc.tap(send)
        kotlinx.coroutines.delay(600)
        try { svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) } catch (_: Exception) { }
        return "Sent to ${contact.first} on $label."
    }

    /** WhatsApp needs the country code: 0760… in Zambia → 260760… */
    private fun international(ctx: Context, raw: String): String {
        val d = raw.filter { it.isDigit() || it == '+' }
        if (d.startsWith("+")) return d.drop(1)
        if (d.startsWith("00")) return d.drop(2)
        val iso = try { (ctx.getSystemService(TelephonyManager::class.java)?.simCountryIso ?: "").lowercase() } catch (_: Exception) { "" }
        val cc = mapOf("zm" to "260", "za" to "27", "zw" to "263", "mw" to "265", "tz" to "255", "ke" to "254", "ug" to "256", "ng" to "234",
            "gh" to "233", "bw" to "267", "mz" to "258", "cd" to "243", "ao" to "244", "na" to "264", "rw" to "250", "et" to "251",
            "gb" to "44", "us" to "1", "ca" to "1", "in" to "91", "ae" to "971", "cn" to "86", "de" to "49", "fr" to "33")[iso] ?: return d
        return if (d.startsWith("0")) cc + d.drop(1) else if (d.startsWith(cc)) d else cc + d
    }
}
