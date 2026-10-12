package com.blaze.assistant.browser

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/** Tools that let Blaze browse in its own browser, in the background, without touching the screen. */
object BrowserTools {
    val names = setOf("browser_open", "browser_read", "browser_click", "browser_type", "browser_scroll", "browser_back", "browser_tabs", "browser_show", "browser_vpn")

    private fun fn(name: String, desc: String, props: JSONObject = JSONObject(), req: List<String> = emptyList()) =
        JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))
    private fun p(t: String, d: String) = JSONObject().put("type", t).put("description", d)

    fun definitions(): List<JSONObject> = listOf(
        fn("browser_open", "Open a web page (or search words) in Blaze's own built-in browser, in the background. No screen control needed. " +
            "Use it for sites you must interact with: forms, logins, shopping, booking, reading a full page. Returns the page text and numbered elements.",
            JSONObject().put("url", p("string", "URL or search words")).put("new_tab", p("boolean", "Open in a new tab"))
                .put("show", p("boolean", "Also show the browser to the user"))
                .put("private", p("boolean", "Open in a private tab: no history, cookies wiped when closed, location masked")), listOf("url")),
        fn("browser_read", "Read the current browser page again: text plus numbered links, buttons and fields."),
        fn("browser_click", "Click an element in the browser page by its [number] from browser_read, or by its visible text.",
            JSONObject().put("number", p("integer", "Element number")).put("text", p("string", "Visible text, if no number"))),
        fn("browser_type", "Type into a field in the browser page (by [number] or label), optionally pressing Enter/submitting.",
            JSONObject().put("number", p("integer", "Field number")).put("label", p("string", "Field label/placeholder, if no number"))
                .put("text", p("string", "Text to type")).put("submit", p("boolean", "Press Enter / submit the form")), listOf("text")),
        fn("browser_scroll", "Scroll the browser page.", JSONObject().put("direction", JSONObject().put("type", "string").put("enum", JSONArray(listOf("down", "up"))))),
        fn("browser_back", "Go back one page in the browser."),
        fn("browser_tabs", "List browser tabs, or switch with switch_to.", JSONObject().put("switch_to", p("integer", "Tab number to switch to"))),
        fn("browser_show", "Show the built-in browser on screen with the current page, so the user can see or take over (e.g. to log in or pay)."),
        fn("browser_vpn", "The built-in browser's VPN (only the browser goes through it, from the user's own WireGuard configs, one per country). " +
            "status: is it on, which country, which IP, which countries are available. connect: browse from a country (name or code), e.g. to see a site as it looks there. " +
            "disconnect: back to the real connection. Only countries the user has imported are available; if none, tell them to import a WireGuard config in the browser (⋯ › VPN & privacy).",
            JSONObject().put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("status", "connect", "disconnect"))))
                .put("country", p("string", "Country name or code for connect, e.g. Germany or DE")), listOf("action")),
    )

    fun describe(name: String, a: JSONObject): String = when (name) {
        "browser_open" -> "Browsing ${a.optString("url").take(50)}"
        "browser_read" -> "Reading the page"
        "browser_click" -> "Clicking ${a.optString("text").ifBlank { "#" + a.optInt("number") }}"
        "browser_type" -> "Typing in the page"
        "browser_scroll" -> "Scrolling the page"
        "browser_back" -> "Going back"
        "browser_tabs" -> "Checking tabs"
        "browser_vpn" -> when (a.optString("action")) { "connect" -> "Switching the browser VPN to ${a.optString("country")}"; "disconnect" -> "Turning the browser VPN off"; else -> "Checking the browser VPN" }
        else -> "Showing the browser"
    }

    fun show(ctx: Context) = ctx.startActivity(Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))

    suspend fun run(ctx: Context, name: String, a: JSONObject): String = try {
        when (name) {
            "browser_open" -> BrowserEngine.open(ctx, a.optString("url"), a.optBoolean("new_tab", false), a.optBoolean("private", false)).also { if (a.optBoolean("show")) show(ctx) }
            "browser_read" -> BrowserEngine.read(ctx)
            "browser_click" -> BrowserEngine.click(ctx, if (a.has("number")) a.optInt("number") else null, a.optString("text").ifBlank { null })
            "browser_type" -> BrowserEngine.type(ctx, if (a.has("number")) a.optInt("number") else null, a.optString("label").ifBlank { null },
                a.optString("text"), a.optBoolean("submit", false))
            "browser_scroll" -> BrowserEngine.scroll(ctx, a.optString("direction", "down") != "up")
            "browser_back" -> BrowserEngine.back(ctx)
            "browser_tabs" -> if (a.has("switch_to")) BrowserEngine.switchTab(a.optInt("switch_to")) else BrowserEngine.tabList()
            "browser_show" -> { show(ctx); "The browser is on screen." }
            "browser_vpn" -> vpn(ctx, a)
            else -> "Unknown browser tool."
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "Browser error: ${e.message}" }

    private suspend fun vpn(ctx: Context, a: JSONObject): String {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { BrowserEngine.init(ctx) }
        return when (a.optString("action")) {
            "connect" -> {
                val q = a.optString("country")
                val loc = BrowserVpn.find(q) ?: return "No VPN location for \u201c$q\u201d. Available: " +
                    BrowserVpn.locations.joinToString { it.label }.ifBlank { "none yet (the user imports WireGuard configs in the browser: ⋯ › VPN & privacy)" }
                BrowserVpn.connectNow(loc.id)
            }
            "disconnect" -> { BrowserVpn.disconnect(); "VPN off. The browser uses the real connection again." }
            else -> BrowserVpn.statusText()
        }
    }
}
