package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/** v0.24.4: technical lines under replies are hidden by default. */
class ReplyChipsTest {
    @Test fun hidesTechnicalParts() {
        assertEquals("", ReplyChips.visible("\u26a1 instant \u00b7 12 ms", false))
        assertEquals("", ReplyChips.visible("\uD83C\uDFA8 free image \u00b7 3.1 s", false))
        assertEquals("Memory updated", ReplyChips.visible("Memory updated \u00b7 1.2 MB", false))
        assertEquals("", ReplyChips.visible("New context", false))
        assertEquals("Skill saved", ReplyChips.visible("google/gemini-2.5-flash \u00b7 Skill saved", false))
        assertEquals("Heads-up \u00b7 14:02", ReplyChips.visible("Heads-up \u00b7 14:02", false))
    }

    @Test fun showsEverythingWhenAsked() {
        assertEquals("\u26a1 instant \u00b7 12 ms", ReplyChips.visible("\u26a1 instant \u00b7 12 ms", true))
    }
}
