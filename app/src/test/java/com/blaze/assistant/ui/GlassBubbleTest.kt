package com.blaze.assistant.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class GlassBubbleTest {
    @Test fun particlesAreBlue() {
        for (d in listOf(false, true)) {
            val c = ParticleBlue.of(d)
            assertTrue("blue dominates", c.blue > c.red + 0.2f && c.blue > c.green)
        }
    }

    @Test fun bubblesAreThemeGlassWithAHairline() {
        val src = java.io.File("src/main/java/com/blaze/assistant/ui/Bubbles.kt").readText()
        assertTrue(src.contains("chatGlass(c, mine = true, shape = shape)") && src.contains("chatGlass(c, mine = false, shape = shape)"))
        assertTrue("no solid ink bubble", !src.contains("max = 300.dp).clip(Bubbles.shape(Side.ME, pos)).background(c.ink)"))
        assertTrue("work card shares the glass", java.io.File("src/main/java/com/blaze/assistant/ui/Shell.kt").readText().contains("chatGlass(c, mine = false"))
    }

    // v0.30.5: dark = faint white-tinted glass with a light hairline; light = white frost with a soft darker hairline.
    @Test fun glassFitsTheTheme() {
        for (c in listOf(LightColors, DarkColors)) {
            for (f in listOf(GlassBubble.mineFill(c), GlassBubble.blazeFill(c))) {
                assertTrue("fill ${f.alpha}", f.alpha <= GlassBubble.maxAlpha(c.dark))
                assertTrue("see-through", f.alpha < 1f && f.alpha > 0f)
                assertTrue("whitish glass", f.red > 0.6f && f.green > 0.6f)
            }
            assertTrue("cards inside see-through", GlassBubble.cardFill(c).alpha < 0.5f)
            val h = GlassBubble.hairline(c)
            assertTrue("hairline visible but soft ${h.alpha}", h.alpha in 0.08f..0.3f)
            if (c.dark) assertTrue("light hairline on dark", h.red > 0.9f) else assertTrue("dark hairline on light", h.red < 0.1f)
            val halo = GlassBubble.halo(c)
            assertTrue("halo glows", halo.blurRadius > 0f && halo.color.alpha > 0.7f)
        }
    }

    @Test fun picturesStandOutsideTheBubble() {
        val src = java.io.File("src/main/java/com/blaze/assistant/ui/Attach.kt").readText()
        val sent = src.substring(src.indexOf("fun SentBubble("), src.indexOf("fun ViewOfferChip("))
        assertTrue("no bubble glass around pictures", !sent.contains("GlassBubble.") && !sent.contains("chatGlass("))
        assertTrue("words get their own bubble", sent.contains("UserBubble(words, quoted, pos)"))
    }

    @Test fun noOpaqueCardsInsideBubbles() {
        val cards = java.io.File("src/main/java/com/blaze/assistant/ui/AnswerCards.kt").readText()
        val rich = java.io.File("src/main/java/com/blaze/assistant/ui/RichInteractive.kt").readText()
        assertTrue(!cards.contains("LocalBubble.current != null) c.inset") && !rich.contains("LocalBubble.current != null) c.inset"))
        assertTrue("reply text keeps its halo", cards.contains("GlassBubble.halo(c)"))
        assertTrue(java.io.File("src/main/java/com/blaze/assistant/ui/Bubbles.kt").readText().contains("shadow = GlassBubble.halo(c)"))
    }
}
