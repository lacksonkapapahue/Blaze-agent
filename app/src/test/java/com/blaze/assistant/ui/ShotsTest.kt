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
        fun r(n: String, d: String, bundled: Boolean = true, on: Boolean = true, edited: Boolean = false, installed: Boolean = true, update: Boolean = false) =
            com.blaze.assistant.agent.SkillLibrary.Row(n, d, bundled, on, edited, installed, update)
        SettingsFrame("Skill library") {
            SkillLibraryPage(listOf(
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
}
