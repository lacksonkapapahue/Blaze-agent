package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/** Blaze Gateway 0.3.0 waiting list: plain wording instead of "sign in again". */
class PlainErrorTest {
    @Test fun waitlistedReadsPlainly() {
        val raw = "Blaze said (403): {\"error\":{\"message\":\"You're on the Blaze waiting list. You'll get access as soon as you're approved.\",\"type\":\"waitlisted\",\"code\":\"waitlisted\"}}"
        assertEquals(PlainError.WAITLISTED, PlainError.of(raw))
        assertEquals(PlainError.WAITLISTED, PlainError.of("waitlisted"))
    }

    @Test fun rejectedReadsPlainly() {
        val raw = "Blaze said (403): {\"error\":{\"message\":\"Your Blaze account hasn't been approved for Blaze AI.\",\"type\":\"access_rejected\",\"code\":\"access_rejected\"}}"
        assertEquals(PlainError.REJECTED, PlainError.of(raw))
    }

    @Test fun cloudKeepsTheWaitingListWordingOn403() {
        // LlmClient wraps the gateway's 403 like a rejected key; Cloud must not turn it into "sign in again".
        val wrapped = "Blaze Cloud rejected its key (403). Check the Blaze Cloud key in Settings \u2192 AI: You're on the Blaze waiting list. You'll get access as soon as you're approved."
        assertEquals(PlainError.WAITLISTED, Cloud.friendly(403, wrapped))
        assertEquals(PlainError.REJECTED, Cloud.friendly(403, "rejected its key (403): Your Blaze account hasn't been approved for Blaze AI."))
        assertEquals(Cloud.EXPIRED, Cloud.friendly(401, "Your Blaze session has expired. Sign in again."))
    }
}
