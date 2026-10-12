package com.blaze.assistant.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blaze.assistant.agent.ChatMessage
import com.blaze.assistant.agent.ChatMeta
import com.blaze.assistant.agent.Mood
import com.blaze.assistant.agent.Project
import com.blaze.assistant.agent.Role
import com.blaze.assistant.agent.Settings
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Preview screenshots of the 0.12 shell (Samsung A12-sized: 360x780 dp). Only runs with BLAZE_SHOTS=1
 * (CI's screenshot step); PNGs land in app/build/shots and CI pushes them to the `shots` branch.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h780dp-xhdpi", application = android.app.Application::class)
class ShotsTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun setUp() {
        assumeTrue(System.getProperty("blaze.shots") == "1")
        try { Settings.init(ApplicationProvider.getApplicationContext()) } catch (_: Throwable) { }
    }

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalBlaze provides if (dark) DarkColors else LightColors) {
                Box(Modifier.fillMaxSize().background(LocalBlaze.current.paper)) { content() }
            }
        }
        rule.mainClock.advanceTimeBy(1200)
        // Draw the window ourselves: captureToImage waits for idle, which never comes with live animations.
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        val dir = File(System.getProperty("blaze.shotsDir") ?: "build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val t0 = 1_760_000_000_000L
    private fun step(id: Long, text: String, meta: String = "") = ChatMessage(id, Role.STEP, text, meta = meta, at = t0 + id * 9_000)
    private val doneSteps = listOf(
        step(2, "Searching the web for \u201cfree hosting for a portfolio\u201d", "web_search {\"query\":\"free hosting\"}"),
        step(3, "Reading pages.github.com"), step(4, "Writing ~/site/index.html"), step(5, "Opening Design"))
    private val liveSteps = listOf(step(11, "Checking your calendar for Friday"), step(12, "Reading 3 new emails"))
    private val chats = listOf(
        ChatMeta("c1", "Portfolio site", t0, true, "Make me a one-page portfolio"),
        ChatMeta("c2", "Exam plan: Data Structures", t0, false, "Plan my week before the exam"),
        ChatMeta("c3", "Afro beat for the intro", t0, false, "Make me an afro beat"),
        ChatMeta("c4", "Fix the WiFi", t0, false, "My phone keeps dropping WiFi"))

    @Composable
    private fun Chat(plus: Boolean = false, views: Boolean = false, chip: String = "", input: String = "") {
        Column(Modifier.fillMaxSize()) {
            ShellTopBar("chat", {}, {}, {}, routerOk = true, onRouter = {}, avatar = "Luke", onAvatar = {}, mood = Mood.ACTING)
            Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                UserBubble("Make me a one-page portfolio site and show it to me")
                WorkCard(doneSteps, live = false, startAt = t0 + 9_000, endAt = t0 + 9_000 + 191_000)
                AnswerBody(1, "Done. Your portfolio is in **~/site/index.html** with a hero, three project cards and a contact form.\n\n" +
                    "| Section | What's in it |\n|---|---|\n| Hero | Name, one line, photo |\n| Projects | 3 cards |\n| Contact | Email form |")
                UserBubble("What's on Friday?")
                WorkCard(liveSteps, live = true, startAt = t0 + 90_000, endAt = 0, status = "Reading 3 new emails", now = t0 + 90_000 + 72_000,
                    meter = "in 4.2k · out 0.6k")
            }
            if (plus || views) SheetScrim {}
            }
            if (views) ViewsSheet(mapOf("agents" to "1 LIVE"), onPick = {}, onDismiss = {}, autoFocus = false)
            if (plus) PlusMenu(plusItems("plan", "autopilot", "Auto (free)", false)) {}
            Composer(TextFieldValue(input), {}, busy = !plus && !views, canSend = input.isNotEmpty(), plusOpen = plus, chip = chip, agents = 0,
                listening = false, speaking = false, onPlus = {}, onMic = {}, onSend = {}, onStop = {})
        }
    }

    @Test fun chatLight() = shot("01-chat-light") { Chat() }
    @Test fun chatDark() = shot("02-chat-dark", dark = true) { Chat() }
    @Test fun plusMenu() = shot("03-plus-menu") { Chat(plus = true, chip = "PLAN · AUTOPILOT") }
    @Test fun views() = shot("04-views") { Chat(views = true, input = "/views") }
    @Test fun avatar() = shot("05-avatar-menu") {
        Chat()
        AvatarMenu("Luke", "lacksonkapapahue@gmail.com", listOf(
            MenuItem("account", "Account", "Signed in"), MenuItem("settings", "Settings"), MenuItem("router", "BlazeRouter", "AUTO"),
            MenuItem("views", "All views", "/VIEWS"), MenuItem("control", "Phone control", "ON"), MenuItem("theme", "Dark theme", "System"),
            MenuItem("update", "Check for updates", "v0.15.0"), MenuItem("signout", "Sign out")), onPick = {}, onDismiss = {})
    }
    @Test fun projects() = shot("06-projects") {
        Column(Modifier.fillMaxSize()) {
            ShellTopBar("projects", {}, {}, {}, true, {}, "Luke", {})
            ProjectsPane(listOf(Project("p1", "Northrise CS year 2", chats = listOf("c2")), Project("p2", "HomeGlow store", chats = listOf("c3", "c4")),
                Project("p3", "Portfolio", chats = listOf("c1"))), chats, busy = setOf("c3"), needsYou = setOf("c1"), openId = "",
                onOpen = {}, onBack = {}, onCreate = {}, onChat = {}, onNewChat = {}, onInstructions = { _, _ -> }, onDelete = {})
        }
    }
    @Test fun design() = shot("07-design-exit", dark = true) {
        Column(Modifier.fillMaxSize()) {
            ShellTopBar("chat", {}, {}, {}, true, {}, "Luke", {}, viewChip = "Design")
            Box(Modifier.weight(1f)) { DesignView("~/site/index.html", busy = false, status = "", onAsk = {}, onOpenInBrowser = {}, home = File("/home"), preview = false) }
        }
    }
    @Test fun search() = shot("08-search") { SearchScreen(chats, onChat = {}, onView = {}, onDismiss = {}, initial = "me", autoFocus = false) }

    @Test fun router() = shot("09-router") {
        Column(Modifier.fillMaxSize()) {
            ShellTopBar("chat", {}, {}, {}, true, {}, "Luke", {})
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                Label("Settings \u203a BlazeRouter", size = 10.sp, modifier = Modifier.padding(top = 18.dp))
                RouterPage(onClassic = {})
            }
        }
    }
    @Test fun projectBrief() = shot("10-project-brief") {
        Column(Modifier.fillMaxSize()) {
            ShellTopBar("projects", {}, {}, {}, true, {}, "Luke", {})
            ProjectsPane(listOf(Project("p2", "HomeGlow store", "Friendly, short replies. Prices in USD.", chats = listOf("c3", "c4"),
                brief = "Purpose: grow the HomeGlow Shopify store to 20 orders a week.\nRules: free tools only; every customer message needs Luke's OK.\n" +
                    "Key files: ~/homeglow/products.csv, ~/homeglow/brand.md\nIn progress: 5 new lamp listings, TikTok week 3.")),
                chats, busy = setOf("c3"), needsYou = emptySet(), openId = "p2",
                onOpen = {}, onBack = {}, onCreate = {}, onChat = {}, onNewChat = {}, onInstructions = { _, _ -> }, onDelete = {})
        }
    }
    @Test fun longChat() = shot("11-long-chat", dark = true) {
        Column(Modifier.fillMaxSize()) {
            ShellTopBar("chat", {}, {}, {}, true, {}, "Luke", {})
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                UserBubble("Where did we land on the lamp prices?")
                WorkCard(listOf(step(21, "Looking back through this chat for \u201clamp prices\u201d"), step(22, "Reading an earlier message")), live = false,
                    startAt = t0, endAt = t0 + 14_000, meter = "in 26.9k · out 0.4k", meterHigh = true)
                AnswerBody(23, "On turn 41 we set **$24.99** for the moon lamp and **$19.99** for the mini, with free shipping over $35.")
                FreshChatOffer {}
            }
        }
    }
}
