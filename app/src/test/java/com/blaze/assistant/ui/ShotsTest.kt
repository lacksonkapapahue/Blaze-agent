package com.blaze.assistant.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
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

    /** v0.24.0 messaging thread: grouped bubbles, quiet times, light inline work cards. Same components as ChatScreen. */
    @Composable
    private fun ThreadSample(busy: Boolean = true) {
        val gap: @Composable (GroupPos, Boolean) -> Unit = { p, first -> Spacer(Modifier.height(Bubbles.gapAbove(p, first))) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 14.dp)) {
            UserBubble("Hey Blaze", pos = GroupPos.FIRST)
            gap(GroupPos.LAST, false)
            UserBubble("Make me a one-page portfolio site and show it to me", pos = GroupPos.LAST)
            MetaLine(Side.ME, "09:41", listOf("Copy" to {}, "Retry" to {}))
            gap(GroupPos.FIRST, false)
            WorkCard(doneSteps, live = false, startAt = t0 + 9_000, endAt = t0 + 9_000 + 191_000)
            gap(GroupPos.MIDDLE, false)
            BlazeReply(1, "Done. Your portfolio is in **~/site/index.html** with a hero, three project cards and a contact form.\n\n" +
                "| Section | What's in it |\n|---|---|\n| Hero | Name, one line, photo |\n| Projects | 3 cards |\n| Contact | Email form |", GroupPos.MIDDLE)
            gap(GroupPos.LAST, false)
            BlazeReply(2, "Want a dark version too?", GroupPos.LAST)
            MetaLine(Side.BLAZE, "09:44", listOf("Copy" to {}, "Quote" to {}, "Retry" to {}), chip = "Memory updated")
            gap(GroupPos.SINGLE, false)
            UserBubble("What's on Friday?", quoted = "Want a dark version too?", pos = GroupPos.SINGLE)
            MetaLine(Side.ME, "09:46", listOf("Copy" to {}, "Retry" to {}))
            gap(GroupPos.SINGLE, false)
            if (busy) WorkCard(liveSteps, live = true, startAt = t0 + 90_000, endAt = 0, status = "Reading 3 new emails", now = t0 + 90_000 + 72_000,
                meter = "in 4.2k · out 0.6k")
            else {
                BlazeReply(3, "Friday: **CIT102 lab at 10:00**, then the NET103 quiz at 14:00. Nothing in the evening.", GroupPos.SINGLE)
                MetaLine(Side.BLAZE, "09:46", listOf("Copy" to {}, "Quote" to {}, "Retry" to {}))
                SoftPill("Remind me Thursday night", lead = "↳") {}
            }
        }
    }

    @Composable
    private fun Chat(plus: Boolean = false, views: Boolean = false, chip: String = "", input: String = "", busy: Boolean = true) {
        Column(Modifier.fillMaxSize()) {
            ShellTopBar("chat", {}, {}, {}, routerOk = true, onRouter = {}, avatar = "Luke", onAvatar = {}, mood = if (busy) Mood.ACTING else Mood.IDLE)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ThreadSample(busy)
                if (plus || views) SheetScrim {}
            }
            if (views) ViewsSheet(mapOf("agents" to "1 LIVE"), onPick = {}, onDismiss = {}, autoFocus = false)
            if (plus) PlusMenu(plusItems("plan", "autopilot", "Auto (free)", false)) {}
            Composer(TextFieldValue(input), {}, busy = busy && !plus && !views, canSend = input.isNotEmpty(), plusOpen = plus, chip = chip, agents = 0,
                listening = false, speaking = false, onPlus = {}, onMic = {}, onSend = {}, onStop = {}, hints = sampleHints)
        }
    }

    private val sampleHints = listOf("Wish Gloria happy birthday", "Check my Gmail…", "Open WhatsApp…", "Get me ready for NET103 quiz")

    // v0.24.1: the reference pill composer (+, rotating hint, mic → send, stop) in both themes.
    @Composable
    private fun ComposerStates() {
        val c = LocalBlaze.current
        Column(Modifier.fillMaxWidth().background(c.paper).padding(vertical = 10.dp)) {
            Label("Idle · rotating hint", size = 9.sp, modifier = Modifier.padding(start = 20.dp, top = 6.dp))
            Composer(TextFieldValue(""), {}, busy = false, canSend = false, plusOpen = false, chip = "", agents = 0, listening = false, speaking = false,
                onPlus = {}, onMic = {}, onSend = {}, onStop = {}, hints = sampleHints)
            Composer(TextFieldValue(""), {}, busy = false, canSend = false, plusOpen = false, chip = "", agents = 0, listening = false, speaking = false,
                onPlus = {}, onMic = {}, onSend = {}, onStop = {}, hints = sampleHints, hintIndex0 = 2)
            Label("Typing · send", size = 9.sp, modifier = Modifier.padding(start = 20.dp, top = 6.dp))
            Composer(TextFieldValue("Wish Gloria happy birthday on WhatsApp"), {}, busy = false, canSend = true, plusOpen = false, chip = "", agents = 0,
                listening = false, speaking = false, onPlus = {}, onMic = {}, onSend = {}, onStop = {}, hints = sampleHints)
            Label("Working · stop", size = 9.sp, modifier = Modifier.padding(start = 20.dp, top = 6.dp))
            Composer(TextFieldValue(""), {}, busy = true, canSend = false, plusOpen = false, chip = "PLAN", agents = 1, listening = false, speaking = false,
                onPlus = {}, onMic = {}, onSend = {}, onStop = {}, hints = sampleHints, hintIndex0 = 1)
        }
    }

    @Test fun composerPill() = shot("43-composer") {
        Column(Modifier.fillMaxSize()) {
            ComposerStates()
            CompositionLocalProvider(LocalBlaze provides DarkColors) { ComposerStates() }
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
    @Test fun search() = shot("08-search") { SearchScreen(onView = {}, onAsk = {}, onDismiss = {}, initial = "lamp", autoFocus = false) }

    // v0.24.0: messaging-style chat (idle, typing), /clear with its undo bar, and the ask_user card inline.
    @Test fun messaging() = shot("39-messaging") { Chat(busy = false, input = "Thanks!") }
    @Test fun messagingDark() = shot("40-messaging-dark", dark = true) { Chat(busy = false) }
    @Test fun clearUndo() = shot("41-clear-undo") {
        Column(Modifier.fillMaxSize()) {
            ShellTopBar("chat", {}, {}, {}, true, {}, "Luke", {})
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Welcome(needsSetup = false, onSetup = {}) {}
                UndoBar("Chat cleared \u00b7 nothing is forgotten", onUndo = {})
            }
            Composer(TextFieldValue(""), {}, busy = false, canSend = false, plusOpen = false, chip = "", agents = 0,
                listening = false, speaking = false, onPlus = {}, onMic = {}, onSend = {}, onStop = {})
        }
    }
    @Test fun questionInline() = shot("42-question-inline", dark = true) {
        Column(Modifier.fillMaxSize()) {
            ShellTopBar("chat", {}, {}, {}, true, {}, "Luke", {}, mood = Mood.ACTING)
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp)) {
                UserBubble("Book me a bus to Lusaka on Friday")
                Spacer(Modifier.height(16.dp))
                WorkCard(listOf(step(61, "Searching buses Ndola → Lusaka"), step(62, "Comparing 3 times")), live = false, startAt = t0, endAt = t0 + 18_000)
            }
            QuestionView("Which bus should I book for Friday?", listOf("06:00 · Mazhandu · K250", "09:30 · Power Tools · K230", "14:00 · Juldan · K240"))
            Composer(TextFieldValue(""), {}, busy = true, canSend = false, plusOpen = false, chip = "", agents = 0,
                listening = false, speaking = false, onPlus = {}, onMic = {}, onSend = {}, onStop = {})
        }
    }

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
                BlazeReply(23, "On turn 41 we set **$24.99** for the moon lamp and **$19.99** for the mini, with free shipping over $35.")
            }
        }
    }

    private fun seedCards() {
        val f = File(System.getProperty("java.io.tmpdir"), "blaze-shots-cards-" + System.nanoTime() + ".json")
        com.blaze.assistant.agent.Flashcards.fileOverride = f
        com.blaze.assistant.agent.Flashcards.reset()
        com.blaze.assistant.agent.Flashcards.add("CIT102 loops", listOf(
            "What does range(2, 10, 3) produce?" to "2, 5, 8 (start 2, stop before 10, step 3)",
            "When does a while loop's else block run?" to "When the loop ends without break",
            "What does break do inside nested loops?" to "Leaves only the innermost loop"))
        com.blaze.assistant.agent.Flashcards.add("NET103 OSI layers", listOf("Layer 3?" to "Network (IP, routing)", "Layer 4?" to "Transport (TCP, UDP)"))
        com.blaze.assistant.agent.Flashcards.add("COM102 citations", listOf("APA in-text, one author?" to "(Author, Year)"))
        com.blaze.assistant.agent.Flashcards.load().filter { it.deck == "COM102 citations" }.forEach { com.blaze.assistant.agent.Flashcards.answer(it.id, com.blaze.assistant.agent.Flashcards.GOOD) }
    }
    @Test fun learn() { seedCards(); shot("12-learn") {
        Column(Modifier.fillMaxSize()) {
            ShellTopBar("chat", {}, {}, {}, true, {}, "Luke", {}, viewChip = "Learn")
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) { LearnPage() }
        }
    } }
    @Test fun flashcard() { seedCards(); shot("13-flashcard", dark = true) {
        Column(Modifier.fillMaxSize()) {
            ShellTopBar("chat", {}, {}, {}, true, {}, "Luke", {}, viewChip = "Learn")
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)) { LearnPage(startDeck = "CIT102 loops", reveal = true, now = t0) }
        }
    } }
    @Test fun seller() {
        com.blaze.assistant.agent.Seller.brandFileOverride = File(System.getProperty("java.io.tmpdir"), "blaze-shots-brand-" + System.nanoTime() + ".json")
        com.blaze.assistant.agent.Seller.saveBrand(com.blaze.assistant.agent.Brand(name = "HomeGlow", paper = "#fffaf2", ink = "#1d1a16",
            accents = listOf("#e8743b", "#2f5d50"), fonts = listOf("Playfair Display", "Inter"), notes = "Warm, cosy home decor for young renters"))
        shot("14-seller") {
            Column(Modifier.fillMaxSize()) {
                ShellTopBar("chat", {}, {}, {}, true, {}, "Luke", {}, viewChip = "Seller")
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) { SellerPage(digest0 = true) }
            }
        }
    }

    // v0.20.0: background browser secure form, password-free sign-in and phone control banner.
    private val signIn = listOf(
        com.blaze.assistant.browser.SecureInput.Field("email", "GitHub email or username", com.blaze.assistant.browser.SecureInput.Kind.EMAIL),
        com.blaze.assistant.browser.SecureInput.Field("password", "GitHub password", com.blaze.assistant.browser.SecureInput.Kind.PASSWORD))
    @Composable private fun BrowserChat(content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize()) {
            ShellTopBar("chat", {}, {}, {}, true, {}, "Luke", {}, mood = Mood.ACTING)
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                UserBubble("Star the Blaze-agent repo on GitHub for me")
                WorkCard(listOf(step(31, "Browsing github.com/login"), step(32, "Reading the page"), step(33, "Waiting for you: Sign in to GitHub")),
                    live = true, startAt = t0, endAt = 0, status = "Waiting for you", now = t0 + 21_000)
            }
            content()
        }
    }
    @Test fun secureForm() = shot("30-secure-form") {
        BrowserChat { SecureForm("github.com", "Sign in to GitHub", "GitHub needs you signed in to star the repo.", signIn,
            onSubmit = { _, _ -> }, onCancel = {}, onWatch = {}, preset = mapOf("email" to "lacksonkapapahue", "password" to "not-a-real-one"), notifPrompt = false) }
    }
    @Test fun secureFormDark() = shot("31-secure-form-dark", dark = true) {
        BrowserChat { SecureForm("api.groq.com", "Your Groq API key", "Paste it once; Blaze saves it encrypted on this phone.",
            listOf(com.blaze.assistant.browser.SecureInput.Field("groq_key", "Groq API key", com.blaze.assistant.browser.SecureInput.Kind.API_KEY)),
            onSubmit = { _, _ -> }, onCancel = {}, onWatch = {}, notifPrompt = true) }
    }
    @Test fun noPasswordChoice() = shot("32-no-password-choice") {
        Column(Modifier.fillMaxSize()) {
            ShellTopBar("chat", {}, {}, {}, true, {}, "Luke", {})
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                UserBubble("Sign me in to GitHub")
                WorkCard(listOf(step(41, "Browsing github.com/login"), step(42, "GitHub says this account can't use a password")), live = false, startAt = t0, endAt = t0 + 9_000)
            }
            PhoneControlBanner(com.blaze.assistant.phone.PhoneControl.State.OFF) {}
            QuestionView("GitHub won't take a password for this account. How do you want to sign in?",
                listOf("Continue with Google", "Use a passkey", "I'll do it in the browser"))
        }
    }
    @Test fun savedLogins() {
        com.blaze.assistant.browser.Vault.fileOverride = File(System.getProperty("java.io.tmpdir"), "blaze-shots-vault-" + System.nanoTime() + ".json")
        com.blaze.assistant.browser.Vault.seal = { it }; com.blaze.assistant.browser.Vault.open = { it }
        com.blaze.assistant.browser.Vault.save("github.com", "email", "lacksonkapapahue@gmail.com")
        com.blaze.assistant.browser.Vault.save("github.com", "password", "not-a-real-one")
        com.blaze.assistant.browser.Vault.save("console.groq.com", "groq_key", "gsk_not_real_0000")
        shot("33-saved-logins") {
            Column(Modifier.fillMaxSize()) {
                ShellTopBar("chat", {}, {}, {}, true, {}, "Luke", {}, viewChip = "Saved logins")
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) { SavedLoginsPage() }
            }
        }
    }

    // ---------------- v0.19.0 visual parity ----------------

    /** The settings screen's frame (back arrow + title, then a padded scroll column), without its BackHandler. */
    @Composable
    private fun SettingsFrame(title: String, scroll: Boolean = true, content: @Composable () -> Unit) {
        val c = LocalBlaze.current
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Text("\u2190", style = TextStyle(fontFamily = Type.Display, fontSize = 22.sp, color = c.ink))
                }
                Text(title, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 20.sp, color = c.ink))
            }
            Column(Modifier.weight(1f).fillMaxWidth().let { if (scroll) it.verticalScroll(rememberScrollState()) else it }.padding(horizontal = 20.dp)) { content() }
        }
    }

    private val day = 86_400_000L
    private fun mem(id: Long, kind: String, subject: String, text: String, related: String = "", turn: Long = 0, ago: Int = 40 - id.toInt()) =
        com.blaze.assistant.agent.Mind.Mem(id, kind, subject, text, related, turn, t0 - ago * day, t0 - ago * day)
    private val mems = listOf(
        mem(1, "profile", "You", "Luke: Computer Science student at Northrise University, Ndola"),
        mem(2, "profile", "You", "From Chingola, Zambia; finished school at Amano Christian School in 2025"),
        mem(3, "profile", "You", "Phone is a Samsung A12; data is expensive, prefers low-data answers (inferred)"),
        mem(4, "preference", "Replies", "Short, friendly replies; no long lists unless asked"),
        mem(5, "preference", "Money", "Picks the free or cheapest option first (inferred)"),
        mem(6, "preference", "Study", "Studies at night, 21:00 to 23:00, on weekdays", related = "Northrise University"),
        mem(7, "preference", "Music", "Lofi or afro beats while studying", related = "Study"),
        mem(8, "person", "Gloria", "Gloria Gozza is Luke's cousin; he sends her his school documents", related = "Northrise University"),
        mem(9, "person", "Gloria", "Gloria's birthday is on 3 March"),
        mem(10, "person", "Mutale", "Mutale is Luke's CIT102 lecturer", related = "CIT102"),
        mem(11, "person", "Chanda", "Chanda is in Luke's NET103 group project", related = "NET103 group project"),
        mem(12, "project", "HomeGlow store", "Shopify dropshipping store selling lamps and home decor", related = "Gloria"),
        mem(13, "project", "HomeGlow store", "Goal: 20 orders a week by December"),
        mem(14, "project", "Portfolio", "One-page portfolio site in ~/site, hosted free on GitHub Pages"),
        mem(15, "project", "NET103 group project", "Network design report due 30 October; Luke owns the diagrams", related = "Chanda"),
        mem(16, "project", "Blaze", "Building Blaze, an AI assistant for Android, Desktop and CLI"),
        mem(17, "decision", "Lamp prices", "Moon lamp at $24.99 and the mini at $19.99 for the HomeGlow store, free shipping over $35", turn = 41),
        mem(18, "decision", "Ads", "TikTok first for the HomeGlow store; no paid ads until 10 organic orders", turn = 41),
        mem(19, "decision", "Hosting", "GitHub Pages for the portfolio: free and no card needed", related = "Portfolio"),
        mem(20, "fact", "CIT102", "CIT102 Programming exam on 14 November", related = "Mutale"),
        mem(21, "fact", "CIT102", "Weak spot: nested loops and while-else"),
        mem(22, "fact", "Northrise University", "On-campus housing for semester 1, 2026"),
        mem(23, "fact", "Data", "Buys a 2 GB bundle every Monday"),
        mem(24, "fact", "Exam week", "Exam week starts 10 November", related = "CIT102", ago = 0))

    @Test fun memoryGraph() = shot("15-memory-graph") { SettingsFrame("Memory & learning") { MemoryPage(mems, mode0 = "graph") } }
    @Test fun memoryDetail() = shot("16-memory-detail", dark = true) {
        SettingsFrame("Memory & learning") { MemoryPage(mems, mode0 = "graph", select0 = 8) }
    }
    @Test fun memoryList() = shot("17-memory-list") { SettingsFrame("Memory & learning") { MemoryPage(mems, mode0 = "list", select0 = 12, query0 = "") } }

    @Test fun skills() = shot("18-skills") {
        fun r(n: String, d: String, bundled: Boolean = true, on: Boolean = true, edited: Boolean = false, installed: Boolean = true, update: Boolean = false, core: Boolean = false) =
            com.blaze.assistant.agent.SkillLibrary.Row(n, d, bundled, on, edited, installed, update, core)
        SettingsFrame("Skill library") {
            SkillLibraryPage(listOf(
                r("blaze-voice", "How Blaze sounds in every reply: answer first, short, one clear pick.", core = true),
                r("blaze-specialists", "Pick the matching specialist, split big work into parallel briefs.", core = true),
                r("homeglow-weekly", "Monday numbers for the HomeGlow store: orders, revenue, top post.", bundled = false),
                r("deep-research", "Search widely, read the sources, cite every claim with a link."),
                r("study-planner", "Exam date and topics in, a day-by-day plan in Schedule out.", update = true),
                r("essay-coach", "Outline, scored feedback and APA 7 or Harvard citations; never writes graded work.", edited = true),
                r("content-calendar", "A week of posts in your brand kit, each saved as a reminder to publish."),
                r("ad-lab", "Five ad angles scored on hook, clarity and call to action.", on = false),
                r("lecture-notes", "Recording to notes, summary, key terms and tasks with free Whisper.", installed = false)), open0 = "deep-research")
        }
    }

    @Test fun schedule() = shot("19-schedule") {
        SettingsFrame("Routines") {
            SchedulePage(listOf(
                com.blaze.assistant.routine.Routine(1, "Morning brief", "Summarise today's calendar and unread texts", 7, 0, 31),
                com.blaze.assistant.routine.Routine(2, "Weekly store digest", "Run the weekly-digest skill", 8, 0, 1),
                com.blaze.assistant.routine.Routine(3, "CIT102 lab 3 due", "Remind me: CIT102 lab 3 is due tomorrow", 9, 0, 0, at = t0 + 3 * day),
                com.blaze.assistant.routine.Routine(4, "Back up notes", "", 22, 30, 127, enabled = false, command = "tar czf ~/backup/notes.tgz ~/notes", linux = true)),
                now = t0)
        }
    }

    @Test fun settingsList() = shot("20-settings") { SettingsFrame("Settings") { SettingsList {} } }

    @Test fun modelPicker() = shot("21-model-picker") {
        SettingsFrame("Brain (classic editor)") {
            Spacer(Modifier.height(18.dp))
            Label("Model", modifier = Modifier.padding(bottom = 8.dp))
            ModelField("meta-llama/llama-3.3-70b-instruct:free", open = true) {}
            ModelList(listOf(
                com.blaze.assistant.agent.ModelCatalog.Model("meta-llama/llama-3.3-70b-instruct:free", "Llama 3.3 70B Instruct", "free"),
                com.blaze.assistant.agent.ModelCatalog.Model("google/gemini-2.0-flash-exp:free", "Gemini 2.0 Flash", "free"),
                com.blaze.assistant.agent.ModelCatalog.Model("deepseek/deepseek-chat-v3-0324:free", "DeepSeek V3", "free"),
                com.blaze.assistant.agent.ModelCatalog.Model("qwen/qwen-2.5-coder-32b-instruct:free", "Qwen 2.5 Coder 32B", "free"),
                com.blaze.assistant.agent.ModelCatalog.Model("mistralai/mistral-small-3.1-24b-instruct", "Mistral Small 3.1", "128k context")),
                selected = "meta-llama/llama-3.3-70b-instruct:free", query = "", onQuery = {}, onPick = {})
        }
    }

    @Test fun onboarding() = shot("22-onboarding") { SetupFlow(startPage = 1) {} }

    @Test fun accounts() = shot("23-accounts") {
        SettingsFrame("Connected accounts") { ConnectionsPage(connected0 = setOf("google", "github"), online0 = true, open0 = "spotify") }
    }

    @Test fun emptyStates() = shot("24-empty-states") {
        SettingsFrame("Empty states") {
            MemoryPage(emptyList())
            SchedulePage(emptyList(), now = t0)
        }
    }

    @Test fun workCard() = shot("25-work-card", dark = true) {
        Column(Modifier.fillMaxSize()) {
            ShellTopBar("chat", {}, {}, {}, true, {}, "Luke", {}, mood = Mood.ACTING)
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                UserBubble("Make me a one-page portfolio site and show it to me")
                WorkCard(doneSteps, live = false, startAt = t0 + 9_000, endAt = t0 + 9_000 + 191_000, open0 = true)
                UserBubble("What's on Friday?")
                WorkCard(liveSteps + step(13, "Checking WhatsApp for plans with Chanda"), live = true, startAt = t0 + 90_000, endAt = 0,
                    status = "Checking WhatsApp for plans with Chanda", now = t0 + 140_000, meter = "in 4.2k \u00b7 out 0.6k", open0 = true)
            }
        }
    }

    @Test fun lifeLogs() = shot("26-life-logs") {
        SettingsFrame("Life logs") {
            LifeLogsPage(preview = com.blaze.assistant.life.LifeLogs.State(on = true, engine = "offline Vosk model", since = t0,
                last = "so the lamp photos are due on friday"), previewToday = 83 * 60_000L, previewChunks = 14, previewModel = true)
        }
    }
    @Test fun lifeLogsPaused() = shot("27-life-logs-paused", dark = true) {
        SettingsFrame("Life logs") {
            LifeLogsPage(preview = com.blaze.assistant.life.LifeLogs.State(on = true, paused = "phone call", brainstorm = true, engine = "offline Vosk model", since = t0),
                previewToday = 12 * 60_000L, previewChunks = 3, previewModel = false)
        }
    }
    @Test fun replies() {
        val n = com.blaze.assistant.notify.Conversations
        n.reset()
        n.add("com.whatsapp", "WhatsApp", "Gloria", false, listOf(com.blaze.assistant.notify.Conversations.Msg(t0, "Gloria", "Can you send the CIT102 notes?")), null)
        n.add("com.whatsapp", "WhatsApp", "CS Year 2", true, listOf(com.blaze.assistant.notify.Conversations.Msg(t0 - 60_000, "Mutale", "Lab moved to 14:00")), null)
        n.add("com.google.android.apps.messaging", "Messages", "Mum", false, listOf(com.blaze.assistant.notify.Conversations.Msg(t0 - 120_000, "Mum", "Call me tonight")), null)
        try { n.setAuto("wa:gloria", true) } catch (_: Throwable) { }
        shot("28-replies") { SettingsFrame("Notification replies") { RepliesPage(previewAccess = true) } }
    }

    // v0.23.0: views + skills. "Make flashcards" → skills on the work card, a Learn deck, a short reply.
    @Test fun viewsSkills() = shot("35-views-skills") {
        Column(Modifier.fillMaxSize()) {
            ShellTopBar("chat", {}, {}, {}, true, {}, "Luke", {})
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                UserBubble("Make me flashcards on CIT102 loops")
                WorkCard(listOf(step(51, "Skills: study-flashcards, tutor"), step(52, "Making flashcards", "flashcards {\"action\":\"add\",\"deck\":\"CIT102 loops\"}"),
                    step(53, "Opening Learn")), live = false, startAt = t0, endAt = t0 + 21_000, open0 = true)
                BlazeReply(54, "Made the **CIT102 loops** deck in Learn: 12 cards. It's open now; cards come back right before you'd forget them.")
            }
        }
    }

    // v0.23.2: Design is for anything visual. A 3D rocket made by the design tool (parts JSON), drawn natively
    // here because Robolectric has no WebGL; on the phone the same parts render in three.js with orbit controls.
    @Test fun design3d() {
        val dir = java.nio.file.Files.createTempDirectory("shots-designs").toFile()
        com.blaze.assistant.agent.Designs.rootOverride = dir
        com.blaze.assistant.agent.Designs.templateLoader = { File("src/main/assets/skills/three-d-design/assets/mockup.html").readText() }
        fun part(id: String, shape: String, vararg kv: Pair<String, Any>) = org.json.JSONObject().put("id", id).put("shape", shape).apply { kv.forEach { (k, v) -> put(k, v) } }
        fun a(vararg n: Number) = org.json.JSONArray(n.toList())
        val parts = org.json.JSONArray()
            .put(part("body", "cylinder", "radius" to 0.6, "height" to 7, "position" to a(0, 4.5, 0), "color" to "#e9e9ec", "material" to "metal"))
            .put(part("band", "cylinder", "radius" to 0.62, "height" to 0.4, "position" to a(0, 6.4, 0), "color" to "#1f4fd8"))
            .put(part("window", "cylinder", "radius" to 0.22, "height" to 0.1, "position" to a(0, 5.6, 0.6), "rotation" to a(90, 0, 0), "color" to "#7fc4ff", "material" to "glass"))
            .put(part("nose", "cone", "radius" to 0.6, "height" to 2.2, "position" to a(0, 9.1, 0), "color" to "#d8342c"))
            .put(part("engine", "cylinder", "radius" to 0.45, "radiusTop" to 0.3, "height" to 0.8, "position" to a(0, 0.6, 0), "color" to "#3a3a40", "material" to "metal"))
            .put(part("flame", "cone", "radius" to 0.35, "height" to 1.0, "position" to a(0, -0.3, 0), "rotation" to a(180, 0, 0), "color" to "#ff9a1f"))
            .put(part("fin-1", "box", "size" to a(0.08, 1.8, 1.1), "position" to a(0.0, 1.6, 0.85), "rotation" to a(0, 90, 0), "color" to "#d8342c"))
            .put(part("fin-2", "box", "size" to a(0.08, 1.8, 1.1), "position" to a(0.74, 1.6, -0.43), "rotation" to a(0, -30, 0), "color" to "#d8342c"))
            .put(part("fin-3", "box", "size" to a(0.08, 1.8, 1.1), "position" to a(-0.74, 1.6, -0.43), "rotation" to a(0, 30, 0), "color" to "#d8342c"))
        val made = com.blaze.assistant.agent.Designs.run(org.json.JSONObject().put("action", "create").put("kind", "3d").put("title", "Tall modern rocket")
            .put("units", "m").put("parts", parts))
        shot("36-design-3d") {
            Column(Modifier.fillMaxSize()) {
                ShellTopBar("chat", {}, {}, {}, true, {}, "Luke", {}, viewChip = "Design")
                Box(Modifier.weight(1f)) { DesignView(made.open ?: "", busy = false, status = "", onAsk = {}, onOpenInBrowser = {}, home = null, preview = false) }
            }
        }
        com.blaze.assistant.agent.Designs.rootOverride = null
    }

    // v0.23.3: three.js couldn't start (the A12 with WebGL turned off): the same parts drawn natively, the real error, Retry.
    @Test fun design3dFallback() {
        val dir = java.nio.file.Files.createTempDirectory("shots-designs").toFile()
        com.blaze.assistant.agent.Designs.rootOverride = dir
        com.blaze.assistant.agent.Designs.templateLoader = { File("src/main/assets/skills/three-d-design/assets/mockup.html").readText() }
        fun part(id: String, shape: String, vararg kv: Pair<String, Any>) = org.json.JSONObject().put("id", id).put("shape", shape).apply { kv.forEach { (k, v) -> put(k, v) } }
        fun a(vararg n: Number) = org.json.JSONArray(n.toList())
        val parts = org.json.JSONArray()
            .put(part("body", "cylinder", "radius" to 0.6, "height" to 6, "position" to a(0, 4, 0), "color" to "#e9e9ec", "material" to "metal"))
            .put(part("nose", "cone", "radius" to 0.6, "height" to 2, "position" to a(0, 8, 0), "color" to "#d8342c"))
            .put(part("engine", "cylinder", "radius" to 0.45, "radiusTop" to 0.3, "height" to 0.8, "position" to a(0, 0.6, 0), "color" to "#3a3a40"))
            .put(part("fin-1", "box", "size" to a(0.08, 1.6, 1.0), "position" to a(0.0, 1.5, 0.8), "rotation" to a(0, 90, 0), "color" to "#d8342c"))
            .put(part("fin-2", "box", "size" to a(0.08, 1.6, 1.0), "position" to a(0.7, 1.5, -0.4), "rotation" to a(0, -30, 0), "color" to "#d8342c"))
            .put(part("fin-3", "box", "size" to a(0.08, 1.6, 1.0), "position" to a(-0.7, 1.5, -0.4), "rotation" to a(0, 30, 0), "color" to "#d8342c"))
        val made = com.blaze.assistant.agent.Designs.run(org.json.JSONObject().put("action", "create").put("kind", "3d").put("title", "Simple Rocket")
            .put("units", "m").put("parts", parts))
        shot("37-design-3d-fallback") {
            Column(Modifier.fillMaxSize()) {
                ShellTopBar("chat", {}, {}, {}, true, {}, "Luke", {}, viewChip = "Design")
                Box(Modifier.weight(1f)) { DesignView(made.open ?: "", busy = false, status = "", onAsk = {}, onOpenInBrowser = {}, home = null, preview = false,
                    initialFailure = "WebGL is turned off in this phone's WebView (no WebGL 1 or 2), so three.js can't draw.") }
            }
        }
        com.blaze.assistant.agent.Designs.rootOverride = null
    }

    // v0.21.0: the visible pointer over another app, paused by the user (bar), with a swipe path and a ripple.
    @Test fun pointer() = shot("34-pointer") {
        val c = LocalBlaze.current
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFFF4F4F4))) {
            Column(Modifier.fillMaxSize().padding(top = 90.dp, start = 18.dp, end = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("Wi-Fi", "Bluetooth", "Mobile data", "Airplane mode", "Hotspot").forEach {
                    Box(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color.White).padding(18.dp)) {
                        androidx.compose.material3.Text(it, style = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, color = androidx.compose.ui.graphics.Color(0xFF1D1A16)))
                    }
                }
            }
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                drawLine(androidx.compose.ui.graphics.Color(0xFF1F4BD8).copy(alpha = 0.66f), androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.78f),
                    androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.55f), strokeWidth = 5 * density, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawCircle(androidx.compose.ui.graphics.Color(0xFF1F4BD8).copy(alpha = 0.8f), 6 * density, androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.78f))
            }
            PointerGlyph(paused = true, ripple = 0.35f, modifier = Modifier.padding(start = 230.dp, top = 166.dp))
            Box(Modifier.fillMaxWidth().padding(top = 36.dp), contentAlignment = androidx.compose.ui.Alignment.TopCenter) { PointerBar() }
        }
    }

    // v0.24.5: the mascot pointer on light, dark and busy backgrounds, gliding and clicking, plus the "Click '…'?" check.
    @Test fun pointerMascot() = shot("44-pointer-mascot") {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f).background(androidx.compose.ui.graphics.Color(0xFFF4F4F4))) {
                Text("Light app \u00b7 Blaze gliding", Modifier.padding(top = 96.dp, start = 18.dp), style = TextStyle(fontSize = 15.sp, color = androidx.compose.ui.graphics.Color(0xFF1D1A16)))
                PointerGlyph(pulse = 1.06f, modifier = Modifier.padding(start = 150.dp, top = 140.dp))
                Box(Modifier.fillMaxWidth().padding(top = 36.dp), contentAlignment = Alignment.TopCenter) { PointerCheck("Click \u2018Add to cart\u2019?") }
            }
            Box(Modifier.fillMaxWidth().weight(1f).background(androidx.compose.ui.graphics.Color(0xFF111110))) {
                Text("Dark app \u00b7 clicking (squish)", Modifier.padding(top = 20.dp, start = 18.dp), style = TextStyle(fontSize = 15.sp, color = androidx.compose.ui.graphics.Color(0xFFEDEDED)))
                PointerGlyph(ripple = 0.5f, modifier = Modifier.padding(start = 210.dp, top = 70.dp))
            }
            Box(Modifier.fillMaxWidth().weight(1f).background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(
                androidx.compose.ui.graphics.Color(0xFF1F4BD8), androidx.compose.ui.graphics.Color(0xFFE8743B), androidx.compose.ui.graphics.Color(0xFF2E7D32))))) {
                Text("Busy photo \u00b7 paused (you have it)", Modifier.padding(top = 20.dp, start = 18.dp), style = TextStyle(fontSize = 15.sp, color = androidx.compose.ui.graphics.Color.White))
                PointerGlyph(paused = true, modifier = Modifier.padding(start = 120.dp, top = 70.dp))
            }
        }
    }

    // v0.24.5: the real launcher icon (adaptive background + foreground), the themed-icon silhouette and the
    // notification icon, rendered from res/drawable, next to the mascot pointer drawn big.
    @Test fun mascotIcons() = shot("45-mascot-icons") {
        Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF212226)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Launcher icon", style = TextStyle(fontSize = 13.sp, color = androidx.compose.ui.graphics.Color(0xFFBBBBBB)))
            Box(Modifier.size(216.dp)) {
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.blaze.assistant.R.drawable.ic_launcher_background), null, Modifier.size(216.dp))
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.blaze.assistant.R.drawable.ic_launcher_foreground), null, Modifier.size(216.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Box(Modifier.size(108.dp).background(androidx.compose.ui.graphics.Color(0xFFDDE3F0))) {
                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.blaze.assistant.R.drawable.ic_launcher_monochrome), null, Modifier.size(108.dp),
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(androidx.compose.ui.graphics.Color(0xFF2A3550)))
                }
                Box(Modifier.size(108.dp).background(androidx.compose.ui.graphics.Color(0xFF333333)), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.blaze.assistant.R.drawable.ic_stat_blaze), null, Modifier.size(72.dp))
                }
                PointerGlyph(scale = 1.7f)
            }
            Text("Themed icon \u00b7 notification icon \u00b7 pointer", style = TextStyle(fontSize = 13.sp, color = androidx.compose.ui.graphics.Color(0xFFBBBBBB)))
        }
    }

    // v0.24.5: the "Leave private chat?" dialog in the Blaze dialog look (dark card, pill buttons), over a dimmed chat.
    @Test fun privateLeaveDialog() = shot("46-private-leave-dialog", dark = true) {
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF26262A))) {
            Text("Private chat", Modifier.padding(24.dp), style = TextStyle(fontSize = 15.sp, color = androidx.compose.ui.graphics.Color(0xFFF0F0EE)))
            Box(Modifier.fillMaxSize().background(DialogColors.scrim).padding(24.dp), contentAlignment = Alignment.Center) {
                BlazeDialogCard(com.blaze.assistant.agent.PrivateChat.LEAVE_TITLE, com.blaze.assistant.agent.PrivateChat.LEAVE_TEXT,
                    confirm = "Leave", onConfirm = {}, dismiss = "Stay", warm = true)
            }
        }
    }

    // v0.24.5: the floating work banner (rounded dark card, mascot in a spinning ring, Stop pill) over a light and a dark app, and the Blaze menu.
    @Test fun workBanner() = shot("47-work-banner") {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f).background(androidx.compose.ui.graphics.Color(0xFFF4F4F4)).padding(top = 36.dp, start = 12.dp, end = 12.dp)) {
                TaskCard(onOpen = {}, onStop = {}, step0 = "Opening YouTube and searching \u201clofi\u201d", tasks0 = 1)
            }
            Box(Modifier.fillMaxWidth().weight(1f).background(androidx.compose.ui.graphics.Color(0xFF111110)).padding(top = 36.dp, start = 12.dp, end = 12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    TaskCard(onOpen = {}, onStop = {}, step0 = "Comparing prices at 3 shops", tasks0 = 2)
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) { BlazeMenuCard(listOf("Close all tabs" to {}, "New tab" to {})) }
                }
            }
        }
    }

    // v0.23.4: Settings › Brains / Auto, models per provider with cooling counts, one provider opened.
    @Test fun autoPool() = shot("38-auto-pool") {
        fun S(pid: String, name: String, n: Int, cool: Int, tools: Int, at: Long, err: String, next: Long) =
            com.blaze.assistant.agent.FreeRouter.PoolSummary(pid, name, n, cool, tools, at, err, next)
        val rows = listOf(
            S("gemini", "Google Gemini", 9, 1, 7, 1L, "", 0),
            S("groq", "Groq", 12, 12, 9, 1L, "", 40_000),
            S("nvidia", "NVIDIA NIM", 31, 2, 24, 1L, "", 0),
            S("openrouter", "OpenRouter", 14, 0, 10, 1L, "", 0),
            S("pollinations", "Keyless", 1, 0, 0, 0L, "", 0),
        )
        val nim = listOf(
            PoolModel("moonshotai/kimi-k2.6", true, "worked", false),
            PoolModel("z-ai/glm-5.3", true, "limited · back in 52s · rate limit", true),
            PoolModel("nvidia/nemotron-3-super-120b-a12b", true, "untried", false),
            PoolModel("meta/llama-3.3-70b-instruct", true, "failed · back in 23h 59m · not available", true),
            PoolModel("deepseek-ai/deepseek-v4.1-flash", true, "untried", false),
            PoolModel("google/gemma-4-31b-it", false, "untried", false),
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            PageHead("Brains", "Auto (free routing)", "Every free model your providers offer, taking turns.")
            AutoPoolPanel(rows, refreshing = false, onRefresh = {}, models = { if (it == "nvidia") nim else emptyList() }, initiallyOpen = "nvidia")
        }
    }
    // v0.24.7 Magic Hand: the dock over an app, the menu (shared pointer tiles), grab, teach review and the watches page.
    private fun handState() = com.blaze.assistant.phone.MagicHandState.point(com.blaze.assistant.phone.MagicHandState.start(false),
        "K1,299", com.blaze.assistant.phone.MagicHandLogic.Kind.VALUE, "Jumia")

    @Composable private fun FakeApp(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFFF4F4F4))) {
            Column(Modifier.padding(start = 20.dp, top = 48.dp, end = 20.dp)) {
                Text("Galaxy A15 128GB", style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = androidx.compose.ui.graphics.Color(0xFF222222)))
                Spacer(Modifier.height(8.dp))
                Text("K1,299", Modifier.background(androidx.compose.ui.graphics.Color(0x22FF9A3C)).padding(4.dp),
                    style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color(0xFFE05A00)))
                Spacer(Modifier.height(6.dp))
                Text("In stock · free delivery in Lusaka", style = TextStyle(fontSize = 14.sp, color = androidx.compose.ui.graphics.Color(0xFF555555)))
            }
            Box(Modifier.padding(start = 96.dp, top = 112.dp)) { com.blaze.assistant.phone.MagicHandGlyph(64, lit = true, carrying = false) }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                CompositionLocalProvider(LocalBlaze provides DarkColors) { content() }
            }
        }
    }

    @Test fun magicHandDock() = shot("48-magic-hand-dock") {
        FakeApp {
            Column {
                com.blaze.assistant.phone.MagicHandDock(com.blaze.assistant.phone.MagicHandState.answered(
                    com.blaze.assistant.phone.MagicHandState.asking(handState(), "K1,299"),
                    "That's the **price** of the Galaxy A15: K1,299, about average for Lusaka right now."), com.blaze.assistant.phone.DockCalls())
                com.blaze.assistant.phone.MagicHandDock(com.blaze.assistant.phone.MagicHandState.confirm(
                    com.blaze.assistant.phone.MagicHandState.carry(handState(), "Meet at 6 by the gate"),
                    com.blaze.assistant.phone.MagicHandLogic.Intent.DROP, "Message"), com.blaze.assistant.phone.DockCalls())
            }
        }
    }

    @Test fun magicHandMenu() = shot("49-magic-hand-menu") {
        FakeApp {
            com.blaze.assistant.phone.MagicHandDock(com.blaze.assistant.phone.MagicHandState.menu(handState(), "K1,299",
                com.blaze.assistant.phone.MagicHandLogic.Kind.VALUE, "Jumia"), com.blaze.assistant.phone.DockCalls())
        }
    }

    @Test fun magicHandGrab() = shot("50-magic-hand-grab") {
        FakeApp {
            com.blaze.assistant.phone.MagicHandDock(com.blaze.assistant.phone.MagicHandState.grabbed(handState(),
                "Galaxy A15 128GB\nK1,299\nIn stock · free delivery in Lusaka", "Jumia"), com.blaze.assistant.phone.DockCalls())
        }
    }

    @Test fun magicHandTeachReview() = shot("51-magic-hand-teach-review") {
        FakeApp {
            val steps = listOf(
                com.blaze.assistant.phone.MagicHandTeach.Step(com.blaze.assistant.phone.MagicHandTeach.Kind.OPEN_APP, "com.whatsapp", app = "WhatsApp"),
                com.blaze.assistant.phone.MagicHandTeach.Step(com.blaze.assistant.phone.MagicHandTeach.Kind.TAP, "com.whatsapp", app = "WhatsApp", label = "Mum", viewId = "com.whatsapp:id/conversations_row_contact_name"),
                com.blaze.assistant.phone.MagicHandTeach.Step(com.blaze.assistant.phone.MagicHandTeach.Kind.TYPE, "com.whatsapp", app = "WhatsApp", label = "Message", text = "On my way"),
                com.blaze.assistant.phone.MagicHandTeach.Step(com.blaze.assistant.phone.MagicHandTeach.Kind.TAP, "com.whatsapp", app = "WhatsApp", label = "Send"),
            ).map(com.blaze.assistant.phone.MagicHandTeach::line)
            com.blaze.assistant.phone.MagicHandDock(com.blaze.assistant.phone.MagicHandState.review(
                com.blaze.assistant.phone.MagicHandState.teach(handState()), steps, "Tell Mum I'm on my way"), com.blaze.assistant.phone.DockCalls())
        }
    }

    // v0.25.0: the teach explainer with Start, and the teach dock with Undo / Cancel / Done.
    @Test fun magicHandTeachIntro() = shot("55-magic-hand-teach-intro") {
        FakeApp {
            Column {
                com.blaze.assistant.phone.MagicHandDock(com.blaze.assistant.phone.MagicHandState.intro(handState(), firstTime = true), com.blaze.assistant.phone.DockCalls())
                com.blaze.assistant.phone.MagicHandDock(com.blaze.assistant.phone.MagicHandState.teachSteps(
                    com.blaze.assistant.phone.MagicHandState.teach(handState()), listOf("Open WhatsApp (package com.whatsapp)", "Tap \u201cMum\u201d")),
                    com.blaze.assistant.phone.DockCalls())
            }
        }
    }

    // v0.25.1: the Desktop view while the Linux desktop installs.
    @Test fun linuxDesktop() = shot("56-linux-desktop", dark = true) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            LinuxDesktopPage(preview = com.blaze.assistant.desktop.LinuxDesktop.State(
                com.blaze.assistant.desktop.DesktopPlan.Phase.INSTALLING, "Installing xfce4-panel (41 of 96)", 42))
        }
    }

    // v0.25.5: the live desktop card while Blaze clicks in the Linux desktop (no picture yet: "Connecting…").
    @Test fun desktopLiveCard() = shot("57-desktop-live-card", dark = true) {
        Column(Modifier.fillMaxSize().padding(top = 40.dp)) {
            DesktopLiveCard(onOpen = {}, preview = com.blaze.assistant.desktop.DesktopNarration.Live(active = true, acting = true,
                status = "Clicking Save", lastAt = 0L, x = 512, y = 300))
            Spacer(Modifier.height(16.dp))
            DesktopLiveCard(onOpen = {}, preview = com.blaze.assistant.desktop.DesktopNarration.Live(active = true, acting = true,
                status = "Typing into Terminal", lastAt = 0L))
        }
    }

    @Test fun magicHandWatches() = shot("52-magic-hand-watches", dark = true) {
        val now = 1_760_000_000_000L
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            com.blaze.assistant.phone.MagicHandWatchesPage(listOf(
                com.blaze.assistant.phone.MagicHandWatch.Watch("1", "com.jumia.android", "Jumia", "Price", listOf(0, 3, 1), "", "", "K1,099", now - 86_400_000, now - 20 * 60_000, now - 20 * 60_000, "K1,299"),
                com.blaze.assistant.phone.MagicHandWatch.Watch("2", "com.fotmob", "FotMob", "Score", listOf(0, 1), "", "", "2 - 1", now - 3_600_000, now - 3 * 3_600_000),
                com.blaze.assistant.phone.MagicHandWatch.Watch("3", "com.dhl", "DHL", "Status", listOf(2), "", "", "Out for delivery", now - 60_000),
            ), now = now)
            Spacer(Modifier.height(24.dp))
            com.blaze.assistant.phone.MagicHandWatchesPage(emptyList(), now = now)
        }
    }
    // v0.24.9 rich replies: step flow, a section picture beside its text, a 3-picture row, the tilted deck and a diagram.
    private fun fakePicture(seed: Int): androidx.compose.ui.graphics.ImageBitmap {
        val b = Bitmap.createBitmap(480, 360, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(b)
        val cols = listOf(0xFFFF9A3C.toInt(), 0xFF4C7DFF.toInt(), 0xFF66D8FF.toInt(), 0xFF2E7D5B.toInt(), 0xFFB04AD8.toInt(), 0xFFE0C04A.toInt())
        val p = android.graphics.Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, 480f, 360f, cols[seed % cols.size], cols[(seed + 2) % cols.size], android.graphics.Shader.TileMode.CLAMP) }
        cv.drawRect(0f, 0f, 480f, 360f, p)
        val w = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF; textSize = 120f }
        cv.drawCircle(360f, 90f, 50f, w)
        cv.drawText("${seed + 1}", 40f, 320f, w)
        return b.asImageBitmap()
    }

    @Composable private fun RichFrame(dark: Boolean, md: String) {
        CompositionLocalProvider(RichImages.Local provides { url: String -> fakePicture(url.filter { it.isDigit() }.takeLast(1).toIntOrNull() ?: 0) }) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp)) {
                AnswerBody(1L, md)
            }
        }
    }

    @Test fun richSteps() = shot("53-rich-steps") {
        RichFrame(false, "Here's how to turn on battery saver:\n\n1. **Open Settings** and scroll to the bottom.\n2. **Tap Battery and device care**: it's the green icon.\n3. **Turn on Power saving** and pick \u201cLimit apps\u201d if you want more.\n\n## Victoria Falls\n![Victoria Falls from the Zambian side](https://example.org/p1.jpg)\nThe falls are about **1.7 km** wide and 108 m tall, on the Zambezi between Zambia and Zimbabwe. Go in April or May for the biggest spray.\n\n![Rainbow](https://example.org/p2.jpg)\n![Bridge](https://example.org/p3.jpg)\n![Gorge](https://example.org/p4.jpg)")
    }

    @Test fun richDeck() = shot("54-rich-deck", dark = true) {
        RichFrame(true, "Five spots worth a stop:\n\n" + (1..5).joinToString("\n") { "![Spot $it](https://example.org/d$it.jpg)" } +
            "\n\nAnd the plan as a diagram:\n\n```mermaid\ngraph LR\nA[Lusaka] --> B[Livingstone] --> C[Falls]\n```")
    }

    // v0.26.1 visual answers: a flow diagram's text, a tappable chart, a compare card, a live bill splitter, choices and a form.
    @Test fun visualAnswers() = shot("58-visual-answers") {
        RichFrame(false, "Solar panels turn sunlight into electricity your home can use.\n\n" +
            "```chart\n{\"type\":\"bar\",\"title\":\"Average sun hours per day\",\"labels\":[\"Lusaka\",\"Ndola\",\"Livingstone\",\"Kasama\"],\"series\":[{\"name\":\"Hours\",\"values\":[8.2,7.6,8.5,7.1]}],\"unit\":\"h\"}\n```\n\n" +
            "```compare\n{\"title\":\"iPhone 16 vs Galaxy S25\",\"columns\":[\"iPhone 16\",\"Galaxy S25\"],\"rows\":[{\"label\":\"Screen\",\"values\":[\"6.1 in OLED\",\"6.2 in AMOLED\"]},{\"label\":\"Battery\",\"values\":[\"3,561 mAh\",\"4,000 mAh\"]},{\"label\":\"Storage\",\"values\":[\"128 GB\",\"128 GB\"]}],\"verdict\":\"Galaxy S25 for battery; iPhone 16 if you live in Apple's world.\",\"pick\":1}\n```")
    }

    @Test fun visualTools() = shot("59-visual-tools", dark = true) {
        CompositionLocalProvider(RichSend.Local provides { _: String -> }) {
            RichFrame(true, "Each of you pays **K165**.\n\n" +
                "```tool\n{\"title\":\"Bill splitter\",\"inputs\":[{\"id\":\"bill\",\"label\":\"Bill\",\"type\":\"number\",\"default\":450,\"prefix\":\"K\"},{\"id\":\"people\",\"label\":\"People\",\"type\":\"slider\",\"min\":1,\"max\":12,\"default\":3},{\"id\":\"tip\",\"label\":\"Tip\",\"type\":\"slider\",\"min\":0,\"max\":25,\"default\":10,\"suffix\":\"%\"}],\"outputs\":[{\"label\":\"Tip\",\"formula\":\"bill*tip/100\",\"prefix\":\"K\"},{\"label\":\"Each pays\",\"formula\":\"bill*(1+tip/100)/people\",\"prefix\":\"K\"}]}\n```\n\n" +
                "```choices\n{\"prompt\":\"Want me to save this?\",\"options\":[\"Save as a note\",\"Remind us on Friday\",\"No thanks\"]}\n```\n\n" +
                "```chart\n{\"type\":\"pie\",\"title\":\"Where the K495 goes\",\"labels\":[\"Food\",\"Drinks\",\"Tip\"],\"series\":[{\"values\":[300,150,45]}],\"unit\":\"K\"}\n```")
        }
    }

    // v0.26.2: specialists handed out at once (cards with Steer / Stop, one already done) and the follow-up chips as one swipe row.
    @Test fun specialistsAndChipRow() = shot("60-specialists-chip-row") {
        val now = System.currentTimeMillis()
        com.blaze.assistant.agent.BgAgents.preview(listOf(
            com.blaze.assistant.agent.BgAgents.Bg("B3", "Galaxy A15 at Game", "brief", "main", "bg-1", status = com.blaze.assistant.agent.BgAgents.DONE,
                created = now - 50_000, started = now - 48_000, ended = now - 9_000, steps = 5,
                result = "K3,499 at Game Manda Hill, in stock, free delivery to Ndola in 3 days.", specialist = "shopper"),
            com.blaze.assistant.agent.BgAgents.Bg("B4", "Galaxy A15 at Jumia", "brief", "main", "bg-2", status = com.blaze.assistant.agent.BgAgents.RUNNING,
                created = now - 50_000, started = now - 47_000, steps = 3, last = "Reading the Jumia listing", specialist = "shopper"),
            com.blaze.assistant.agent.BgAgents.Bg("B5", "Phone prices this year", "brief", "main", "bg-3", status = com.blaze.assistant.agent.BgAgents.QUEUED,
                created = now - 49_000, specialist = "researcher"),
        ))
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Sending Shopper ×2 and Researcher to work on this at the same time. Results land here as each one finishes.",
                style = TextStyle(fontSize = 15.sp, color = LocalBlaze.current.ink))
            AgentCard("B4")
            AgentCard("B5")
            AgentCard("B3")
            Text("Game has it for K3,499 with free delivery to Ndola. Waiting on Jumia and the price check.", style = TextStyle(fontSize = 15.sp, color = LocalBlaze.current.ink))
            FollowupRow(listOf("Which one is cheaper?", "Add delivery to the total", "Remind me on payday", "Compare the A25 too")) { }
        }
    }
}
