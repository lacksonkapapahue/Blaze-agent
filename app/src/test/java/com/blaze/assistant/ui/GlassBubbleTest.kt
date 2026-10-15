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

    @Test fun bubblesAreSeeThroughWithAVisibleEdge() {
        val src = java.io.File("src/main/java/com/blaze/assistant/ui/Bubbles.kt").readText()
        assertTrue(src.contains("GlassBubble.mineFill(c)") && src.contains("GlassBubble.mineEdge(c), shape"))
        assertTrue(src.contains("GlassBubble.blazeEdge(c)"))
        assertTrue("no solid ink bubble", !src.contains("max = 300.dp).clip(Bubbles.shape(Side.ME, pos)).background(c.ink)"))
    }

    // v0.29.9: much clearer glass so particles float through; edges stay visible; text keeps a halo.
    @Test fun bubblesAreClearGlass() {
        for (c in listOf(LightColors, DarkColors)) {
            for (f in listOf(GlassBubble.mineFill(c), GlassBubble.blazeFill(c))) assertTrue("fill ${f.alpha}", f.alpha <= GlassBubble.MAX_FILL_ALPHA)
            assertTrue("cards inside see-through", GlassBubble.cardFill(c).alpha < 0.5f)
            for (e in listOf(GlassBubble.mineEdge(c), GlassBubble.blazeEdge(c))) assertTrue("edge ${e.alpha}", e.alpha >= 0.2f)
            val h = GlassBubble.halo(c)
            assertTrue("halo glows", h.blurRadius > 0f && h.color.alpha > 0.7f)
        }
    }

    @Test fun noOpaqueCardsInsideBubbles() {
        val cards = java.io.File("src/main/java/com/blaze/assistant/ui/AnswerCards.kt").readText()
        val rich = java.io.File("src/main/java/com/blaze/assistant/ui/RichInteractive.kt").readText()
        assertTrue(!cards.contains("LocalBubble.current != null) c.inset") && !rich.contains("LocalBubble.current != null) c.inset"))
        assertTrue("reply text keeps its halo", cards.contains("GlassBubble.halo(c)"))
        assertTrue(java.io.File("src/main/java/com/blaze/assistant/ui/Bubbles.kt").readText().contains("shadow = GlassBubble.halo(c)"))
    }
}
