package com.blaze.assistant.phone

import com.blaze.assistant.phone.BankModeLogic.Auto
import com.blaze.assistant.phone.BankModeLogic.Cmd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BankModeLogicTest {
    private val comp = "com.blaze.assistant/com.blaze.assistant.phone.BlazeAccessibilityService"
    private val short = "com.blaze.assistant/.phone.BlazeAccessibilityService"
    private val talkback = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"

    // ---------- Commands ----------

    @Test fun slashCommand() {
        assertEquals(Cmd.Toggle, BankModeLogic.parse("/bank-mode"))
        assertEquals(Cmd.On(), BankModeLogic.parse("/bank-mode on"))
        assertEquals(Cmd.Off, BankModeLogic.parse("/bank-mode off"))
        assertEquals(Cmd.Off, BankModeLogic.parse("/BANK-MODE OFF"))
        assertEquals(Cmd.Status, BankModeLogic.parse("/bank-mode status"))
        assertEquals(Cmd.Apps, BankModeLogic.parse("/bank-mode apps"))
        assertEquals(Cmd.On(), BankModeLogic.parse("/bankmode on"))
        assertEquals(Cmd.On(), BankModeLogic.parse("/bank on"))
        assertEquals(Cmd.On(), BankModeLogic.parse("  /bank_mode   on "))
    }

    @Test fun slashCommandOpensAnApp() {
        assertEquals(Cmd.On("Zanaco"), BankModeLogic.parse("/bank-mode open Zanaco"))
        assertEquals(Cmd.On("Zanaco"), BankModeLogic.parse("/bank-mode Zanaco"))
        assertEquals(Cmd.On("Stanbic"), BankModeLogic.parse("/bank-mode on and open Stanbic"))
        assertEquals(Cmd.On("FNB"), BankModeLogic.parse("/bank-mode launch FNB app"))
        assertEquals(Cmd.On("com.zanaco.mobile"), BankModeLogic.parse("/bank-mode open com.zanaco.mobile"))
    }

    @Test fun slashCommandEditsTheList() {
        assertEquals(Cmd.Add("Zanaco"), BankModeLogic.parse("/bank-mode add Zanaco"))
        assertEquals(Cmd.Remove("Absa Banking"), BankModeLogic.parse("/bank-mode remove Absa Banking"))
        assertEquals(Cmd.Apps, BankModeLogic.parse("/bank-mode add "))
    }

    @Test fun otherSlashCommandsAreNotBankMode() {
        for (t in listOf("/bankrupt", "/banking", "//bank-mode", "/clear", "/ bank-mode", "/bank-modes on", "bank-mode on"))
            assertNull(t, BankModeLogic.parse(t))
    }

    @Test fun naturalPhrases() {
        for (t in listOf("bank mode on", "Bank mode on.", "turn on bank mode", "Turn on bank mode please", "enable bank mode",
                "switch on the bank mode", "hey blaze, bank mode on", "turn bank mode on", "bankmode on", "bank mode", "go into bank mode"))
            assertEquals(t, Cmd.On(), BankModeLogic.parse(t))
        for (t in listOf("bank mode off", "turn off bank mode", "disable bank mode", "Turn bank mode off", "exit bank mode", "stop bank mode"))
            assertEquals(t, Cmd.Off, BankModeLogic.parse(t))
        assertEquals(Cmd.Status, BankModeLogic.parse("is bank mode on?"))
        assertEquals(Cmd.Status, BankModeLogic.parse("bank mode status"))
    }

    @Test fun naturalPhrasesWithAnApp() {
        assertEquals(Cmd.On("Zanaco"), BankModeLogic.parse("bank mode open Zanaco"))
        assertEquals(Cmd.On("Zanaco"), BankModeLogic.parse("bank mode on and open Zanaco"))
        assertEquals(Cmd.On("Zanaco"), BankModeLogic.parse("open Zanaco in bank mode"))
        assertEquals(Cmd.On("Stanbic"), BankModeLogic.parse("open Stanbic app in bank mode"))
    }

    @Test fun ordinaryMessagesAreNotBankMode() {
        for (t in listOf("what is bank mode?", "open Zanaco", "my bank app won't open", "send money to the bank", "how do banks work",
                "bank mode on\nand something else", "tell me about the bank mode feature in other assistants", ""))
            assertNull(t, BankModeLogic.parse(t))
    }

    // ---------- Secure settings string ----------

    @Test fun addsOnceKeepingOthers() {
        assertEquals(comp, BankModeLogic.add(null, comp))
        assertEquals(comp, BankModeLogic.add("", comp))
        assertEquals("$talkback:$comp", BankModeLogic.add(talkback, comp))
        assertEquals("$talkback:$comp", BankModeLogic.add("$talkback:$comp", comp))
        // Already there in the short form: unchanged, no duplicate.
        assertEquals("$short:$talkback", BankModeLogic.add("$short:$talkback", comp))
        // Stray separators are cleaned.
        assertEquals("$talkback:$comp", BankModeLogic.add(":$talkback::", comp))
    }

    @Test fun removesEverySpellingKeepingOthers() {
        assertEquals("", BankModeLogic.remove(comp, comp))
        assertEquals("", BankModeLogic.remove(null, comp))
        assertEquals(talkback, BankModeLogic.remove("$comp:$talkback", comp))
        assertEquals(talkback, BankModeLogic.remove("$short:$talkback:$comp", comp))
        assertEquals("$talkback:com.other/.Svc", BankModeLogic.remove("$talkback:$comp:com.other/.Svc", comp))
        // A different service of another app with a similar name stays.
        assertEquals("com.blaze.assistantx/.phone.BlazeAccessibilityService", BankModeLogic.remove("com.blaze.assistantx/.phone.BlazeAccessibilityService", comp))
    }

    @Test fun addThenRemoveRoundTrips() {
        val before = "$talkback:com.other/.Svc"
        assertEquals(before, BankModeLogic.remove(BankModeLogic.add(before, comp), comp))
        assertTrue(BankModeLogic.contains(BankModeLogic.add(before, comp), short))
        assertFalse(BankModeLogic.contains(before, comp))
    }

    @Test fun componentSpellings() {
        assertEquals(comp, BankModeLogic.expand(short))
        assertEquals(comp, BankModeLogic.expand(comp))
        assertNull(BankModeLogic.expand("nonsense"))
        assertNull(BankModeLogic.expand("/x"))
        assertNull(BankModeLogic.expand("pkg/"))
        assertTrue(BankModeLogic.sameComponent(short, comp))
        assertFalse(BankModeLogic.sameComponent(comp, talkback))
    }

    // ---------- Bank apps ----------

    @Test fun looksLikeBank() {
        assertTrue(BankModeLogic.looksLikeBank("com.zanaco.mobile", "Zanaco Xapit"))
        assertTrue(BankModeLogic.looksLikeBank("com.x.y", "Stanbic Bank Zambia"))
        assertTrue(BankModeLogic.looksLikeBank("za.co.absa.banking", "Absa"))
        assertTrue(BankModeLogic.looksLikeBank("com.mtn.momo", "MoMo"))
        assertTrue(BankModeLogic.looksLikeBank("com.acme.wallet", "Acme", extraWords = listOf("acme")))
        assertFalse(BankModeLogic.looksLikeBank("com.whatsapp", "WhatsApp"))
        assertFalse(BankModeLogic.looksLikeBank("com.anker.powerbank", "Power Bank Monitor"))
        assertFalse(BankModeLogic.looksLikeBank("com.bankside.game", "Bankside Tycoon"))
    }

    @Test fun appsListStorage() {
        assertEquals(setOf("a.b", "c.d"), BankModeLogic.parseApps(" a.b , c.d,,a.b "))
        assertEquals("a.b,c.d", BankModeLogic.storeApps(listOf("a.b", " c.d ", "a.b", "")))
        assertTrue(BankModeLogic.parseApps("").isEmpty())
    }

    @Test fun suggestsOncePerBankApp() {
        val own = "com.blaze.assistant"
        assertTrue(BankModeLogic.shouldSuggest("com.zanaco.mobile", "Zanaco", own, false, emptySet(), emptySet()))
        assertFalse(BankModeLogic.shouldSuggest("com.zanaco.mobile", "Zanaco", own, false, emptySet(), setOf("com.zanaco.mobile")))
        assertFalse(BankModeLogic.shouldSuggest("com.zanaco.mobile", "Zanaco", own, true, emptySet(), emptySet()))
        assertFalse(BankModeLogic.shouldSuggest(own, "Blaze bank", own, false, emptySet(), emptySet()))
        assertFalse(BankModeLogic.shouldSuggest("com.whatsapp", "WhatsApp", own, false, emptySet(), emptySet()))
        // The user's own list counts even when the name doesn't look like a bank.
        assertTrue(BankModeLogic.shouldSuggest("com.acme.pay", "Acme", own, false, setOf("com.acme.pay"), emptySet()))
    }

    // ---------- Auto mode ----------

    @Test fun autoPausesForAChosenBankAppAndResumesWhenItLeaves() {
        val own = "com.blaze.assistant"; val bank = setOf("com.zanaco.mobile")
        assertEquals(Auto.PAUSE, BankModeLogic.autoStep("com.zanaco.mobile", own, bank, "", false, true))
        assertEquals(Auto.NOTHING, BankModeLogic.autoStep("com.zanaco.mobile", own, bank, "", false, false))   // no grant
        assertEquals(Auto.NOTHING, BankModeLogic.autoStep("com.zanaco.mobile", own, bank, "", true, true))     // already on by hand
        assertEquals(Auto.NOTHING, BankModeLogic.autoStep("com.whatsapp", own, bank, "", false, true))
        assertEquals(Auto.NOTHING, BankModeLogic.autoStep("com.zanaco.mobile", own, bank, "com.zanaco.mobile", false, true))
        assertEquals(Auto.NOTHING, BankModeLogic.autoStep("com.android.systemui", own, bank, "com.zanaco.mobile", false, true))
        assertEquals(Auto.NOTHING, BankModeLogic.autoStep("com.google.android.inputmethod.latin", own, bank, "com.zanaco.mobile", false, true))
        assertEquals(Auto.RESUME, BankModeLogic.autoStep("com.whatsapp", own, bank, "com.zanaco.mobile", false, true))
        assertEquals(Auto.RESUME, BankModeLogic.autoStep(own, own, bank, "com.zanaco.mobile", false, true))
    }

    @Test fun lastForegroundFromUsageEvents() {
        val evs = listOf(Triple(10L, "com.zanaco.mobile", true), Triple(20L, "com.android.systemui", true),
            Triple(30L, "com.whatsapp", false), Triple(25L, "com.whatsapp", true))
        assertEquals("com.whatsapp", BankModeLogic.lastForeground(evs))
        assertEquals("", BankModeLogic.lastForeground(emptyList()))
        assertEquals("", BankModeLogic.lastForeground(listOf(Triple(1L, "x.y", false))))
    }

    @Test fun wordsSayHowToGetBack() {
        assertEquals("Bank mode on, Blaze's phone control is paused", BankModeLogic.NOTE_TITLE)
        assertEquals("Turn Blaze back on", BankModeLogic.NOTE_ACTION)
        assertTrue(BankModeLogic.TOOL_REPLY.startsWith("Bank mode is on"))
        assertTrue(BankModeLogic.TOOL_REPLY.contains("/bank-mode off"))
    }
}
