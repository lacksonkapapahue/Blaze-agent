package com.blaze.assistant.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TermLogicTest {
    @Test fun termuxKeyRows() {
        assertEquals(listOf("ESC", "/", "―", "HOME", "↑", "END", "PGUP"), TermLogic.ROWS[0].map { it.label })
        assertEquals(listOf("⇥", "CTRL", "ALT", "←", "↓", "→", "PGDN"), TermLogic.ROWS[1].map { it.label })
        assertTrue(TermLogic.ROWS.flatten().all { it.code != 0 || it.text.isNotEmpty() || it.toggle.isNotEmpty() })
    }

    @Test fun envsPreferLinux() {
        assertEquals(listOf("alpine", "phone"), TermLogic.envs(true).map { it.id })
        assertEquals(listOf("phone"), TermLogic.envs(false).map { it.id })
        assertEquals("alpine", TermLogic.pick(null, true).id)
        assertEquals("phone", TermLogic.pick("phone", true).id)
        assertEquals("phone", TermLogic.pick("alpine", false).id)
    }

    @Test fun phoneShell() {
        assertEquals("/lib/libbusybox.so" to listOf("ash", "-l"), TermLogic.phoneArgs("/lib/libbusybox.so"))
        assertEquals("/system/bin/sh" to listOf("sh", "-l"), TermLogic.phoneArgs(null))
    }

    @Test fun envForcesColourTerm() {
        val e = TermLogic.envArray(mapOf("HOME" to "/h", "TERM" to "dumb"), mapOf("PATH" to "/bin"))
        assertTrue("TERM=xterm-256color" in e); assertTrue("HOME=/h" in e); assertTrue("PATH=/bin" in e)
        assertTrue(e.none { it == "TERM=dumb" })
    }

    @Test fun tabsAndZoom() {
        assertEquals("1 alpine", TermLogic.tabLabel(1, TermLogic.ALPINE, null, false))
        assertEquals("2 vim", TermLogic.tabLabel(2, TermLogic.ALPINE, "vim", false))
        assertEquals("3 phone ✕", TermLogic.tabLabel(3, TermLogic.PHONE, "", true))
        assertEquals(TermLogic.MAX_FONT, TermLogic.zoom(60, 2f)); assertEquals(TermLogic.MIN_FONT, TermLogic.zoom(15, 0.2f))
        assertTrue(TermLogic.welcome(TermLogic.ALPINE).contains("apk add <package>"))
    }
}
