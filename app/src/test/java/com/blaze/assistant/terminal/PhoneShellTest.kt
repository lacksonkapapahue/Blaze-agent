package com.blaze.assistant.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * v0.29.8: the phone tab used to open as "[Process completed (signal 31) - press Enter]". Android's app seccomp filter
 * kills setgid()/setuid() with SIGSYS, and the bundled BusyBox called both at the start of every applet (check_suid's
 * privilege drop). These pin the launch decision, the probe, the exit note and the binary patch that skips the drop.
 */
class PhoneShellTest {
    @Test fun launchUsesBusyboxOnlyWhenTheProbePassed() {
        val bb = "/data/app/x/lib/arm64/libbusybox.so"
        assertEquals(TermLogic.Launch(bb, listOf("ash", "-l"), true), TermLogic.phoneLaunch(bb, true))
        assertEquals(TermLogic.Launch("/system/bin/sh", listOf("sh", "-l"), false), TermLogic.phoneLaunch(bb, false))
        assertEquals(TermLogic.Launch("/system/bin/sh", listOf("sh", "-l"), false), TermLogic.phoneLaunch(null, true))
        assertEquals(TermLogic.Launch("/system/bin/sh", listOf("sh", "-l"), false), TermLogic.phoneLaunch(null, false))
    }

    @Test fun probe() {
        assertTrue(TermLogic.probeOk(0, "ok\n"))
        assertTrue(TermLogic.probeOk(0, "warning: something\nok"))
        assertFalse(TermLogic.probeOk(159, ""))                       // killed by SIGSYS (128 + 31)
        assertFalse(TermLogic.probeOk(127, "libbusybox.so: applet not found"))
        assertFalse(TermLogic.probeOk(0, "nope"))
    }

    @Test fun exitNotes() {
        assertEquals(31, TermLogic.signalOf(-31)); assertNull(TermLogic.signalOf(0)); assertNull(TermLogic.signalOf(2))
        val n = TermLogic.finishNote("phone", true, -31)
        assertNotNull(n); assertTrue(n!!.contains("signal 31")); assertTrue(n.contains("Android's own shell"))
        assertTrue(TermLogic.finishNote("phone", false, -31)!!.contains("signal 31"))
        assertNull(TermLogic.finishNote("phone", true, 0))            // a normal `exit`
        assertNotNull(TermLogic.finishNote("phone", true, -9))
        assertNull(TermLogic.finishNote("ubuntu", true, -31))
    }

    @Test fun pingAndRootToolsStayAndroids() {
        for (a in listOf("sh", "su", "ping", "ping6", "mount")) assertTrue(a, a in TermLogic.KEEP_ANDROIDS)
        for (a in listOf("ls", "top", "vi", "wget")) assertFalse(a, a in TermLogic.KEEP_ANDROIDS)
    }

    @Test fun phoneShellExplainsItself() {
        val w = TermLogic.welcome(TermLogic.PHONE, true)
        assertTrue(w.contains("PHONE SHELL")); assertTrue(w.contains("Not a full Linux")); assertTrue(w.contains("Install a Linux"))
        for (c in listOf("ls", "cd /sdcard", "top", "help")) assertTrue(c, w.contains("   $c "))
        // Lines fit a small phone (Galaxy A12 portrait at the default size is ~45 columns).
        w.split("\r\n").forEach { assertTrue("too wide: $it", it.length <= 44) }
        assertTrue(TermLogic.welcome(TermLogic.PHONE, false).contains("toybox"))
        val labels = TermLogic.PHONE_EXAMPLES.map { it.first }
        assertTrue("help" in labels && "ls" in labels && "top" in labels && "ping" in labels)
        assertTrue(TermLogic.PHONE_EXAMPLES.none { it.second.isBlank() || it.second.contains('\n') })
        assertEquals("Phone shell · built-in tools, no install", TermLogic.PHONE.label)
    }

    @Test fun rcDefinesHelp() {
        for (bb in listOf(true, false)) {
            val rc = TermLogic.phoneRc(bb)
            assertTrue(rc.contains("help() {")); assertTrue(rc.contains("cat <<'BLAZE_HELP'")); assertTrue(rc.trimEnd().endsWith("}"))
            assertEquals(2, Regex("(?m)^BLAZE_HELP$|<<'BLAZE_HELP'").findAll(rc).count())
            assertTrue(rc.contains("ping -c 3 google.com")); assertTrue(rc.contains("Install a Linux"))
            assertEquals(bb, rc.contains("busybox --list"))
        }
        assertTrue(TermLogic.phoneRc(true).contains("PS1='phone:\\w \\$ '"))
        assertTrue(TermLogic.phoneRc(false).contains("PS1='phone:\${PWD##*/} \$ '"))
    }

    // ---- the BusyBox binaries: check_suid's privilege drop is skipped (tools/busybox/README.md) ----

    private fun word(f: File, off: Int): Int {
        val b = f.readBytes()
        return ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun lib(abi: String): File? = listOf("src/main/jniLibs", "app/src/main/jniLibs").map { File(it, "$abi/libbusybox.so") }.firstOrNull { it.exists() }

    @Test fun arm64BusyboxSkipsSetidDrop() {
        val f = lib("arm64-v8a") ?: return
        // 0x404758: was `cbnz w3, 0x404768` (0x35000083: drop privileges when the applet is BB_SUID_DROP), now `b 0x404768`.
        assertEquals(0x14000004, word(f, 0x4758))
        // The two calls it now jumps over: bl setgid; ldr w0, [x21, #0xfd4]; bl setuid.
        assertEquals(0x9403410B.toInt(), word(f, 0x475c))
    }

    @Test fun armv7BusyboxSkipsSetidDrop() {
        val f = lib("armeabi-v7a") ?: return
        // 0x1f22c: was `bne 0x1f240` (0x1A000003), now `b 0x1f240`, over bl setgid / bl setuid.
        assertEquals(0xEA000003.toInt(), word(f, 0xf22c))
    }
}
