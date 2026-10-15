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
        assertTrue("no solid ink bubble", !src.contains(".background(c.ink)"))
    }
}
