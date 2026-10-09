package com.blaze.assistant.browser

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/** Tools that let Blaze browse in its own browser, in the background, without touching the screen. */
object BrowserTools {
    val names = setOf("browser_open", "browser_read", "browser_click", "browser_type", "browser_scroll", "browser_back", "browser_tabs", "browser_show")

    private fun fn(name: String, desc: String, props: JSONObject = JSONObject(), req: List<String> = emptyList()) =
        JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))
    private fun p(t: String, d: String) = JSONObject().put("type", t).put("description", d)

    fun definitions(): List<JSONObject> = listOf(
        fn("browser_open", "Open a web page (or search words) in Blaze's own built-in browser, in the background. No screen control needed. " +
            "Use it for sites you must interact with: forms, logins, shopping, booking, reading a full page. Returns the page text and numbered elements.",
            JSONObject().put("url", p("string", "URL or search words")).put("new_tab", p("boolean", "Open in a new tab"))
                .put("show", p("boolean", "Also show the browser to the user")), listOf("url")),
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
    )

    fun describe(name: String, a: JSONObject): String = when (name) {
        "browser_open" -> "Browsing ${a.optString("url").take(50)}"
        "browser_read" -> "Reading the page"
        "browser_click" -> "Clicking ${a.optString("text").ifBlank { "#" + a.optInt("number") }}"
        "browser_type" -> "Typing in the page"
        "browser_scroll" -> "Scrolling the page"
        "browser_back" -> "Going back"
        "browser_tabs" -> "Checking tabs"
        else -> "Showing the browser"
    }

    fun show(ctx: Context) = ctx.startActivity(Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))

    suspend fun run(ctx: Context, name: String, a: JSONObject): String = try {
        when (name) {
            "browser_open" -> BrowserEngine.open(ctx, a.optString("url"), a.optBoolean("new_tab", false)).also { if (a.optBoolean("show")) show(ctx) }
            "browser_read" -> BrowserEngine.read(ctx)
            "browser_click" -> BrowserEngine.click(ctx, if (a.has("number")) a.optInt("number") else null, a.optString("text").ifBlank { null })
            "browser_type" -> BrowserEngine.type(ctx, if (a.has("number")) a.optInt("number") else null, a.optString("label").ifBlank { null },
                a.optString("text"), a.optBoolean("submit", false))
            "browser_scroll" -> BrowserEngine.scroll(ctx, a.optString("direction", "down") != "up")
            "browser_back" -> BrowserEngine.back(ctx)
            "browser_tabs" -> if (a.has("switch_to")) BrowserEngine.switchTab(a.optInt("switch_to")) else BrowserEngine.tabList()
            "browser_show" -> { show(ctx); "The browser is on screen." }
            else -> "Unknown browser tool."
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "Browser error: ${e.message}" }
}
