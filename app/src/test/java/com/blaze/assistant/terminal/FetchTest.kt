package com.blaze.assistant.terminal

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlin.random.Random

/** v0.25.5: the Linux download resumes after a dropped connection, checks its SHA-256 and moves on to other mirrors. */
class FetchTest {
    private val body = Random(7).nextBytes(300_000)
    private val sha = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
    private lateinit var server: ServerSocket
    private lateinit var dir: File
    private val ranges = java.util.Collections.synchronizedList(mutableListOf<String?>())
    @Volatile private var cutFirst = 0          // how many requests to cut off half-way
    @Volatile private var honourRange = true
    private var port = 0

    /** A tiny HTTP/1.1 server on a plain socket (the Android test classpath has no com.sun.net.httpserver). */
    @Before fun up() {
        dir = Files.createTempDirectory("fetch").toFile()
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        port = server.localPort
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { try { sock.use { serve(it) } } catch (_: Exception) { } }
            }
        }
    }

    private fun serve(sock: Socket) {
        val inp = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val path = inp.readLine()?.split(' ')?.getOrNull(1) ?: return
        var range: String? = null
        while (true) { val l = inp.readLine() ?: break; if (l.isEmpty()) break; if (l.startsWith("Range:", true)) range = l.substringAfter(':').trim() }
        val out = sock.getOutputStream()
        fun head(code: Int, len: Int, extra: String = "") =
            out.write("HTTP/1.1 $code X\r\nContent-Length: $len\r\nConnection: close\r\n$extra\r\n".toByteArray(Charsets.ISO_8859_1))
        when (path) {
            "/good" -> {
                ranges += range
                val from = if (honourRange) range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0 else 0
                val rest = body.copyOfRange(from, body.size)
                head(if (from > 0) 206 else 200, rest.size, if (from > 0) "Content-Range: bytes $from-${body.size - 1}/${body.size}\r\n" else "")
                if (cutFirst > 0) { cutFirst--; out.write(rest, 0, rest.size / 2); out.flush(); return }   // drop the connection half-way
                out.write(rest)
            }
            "/bad" -> { head(200, 1000); out.write(ByteArray(1000)) }
            else -> head(404, 0)
        }
        out.flush()
    }

    @After fun down() { server.close(); dir.deleteRecursively() }

    private fun url(p: String) = "http://127.0.0.1:$port$p"

    @Test fun pureBits() {
        assertEquals(listOf(0, 2, 4, 8, 16, 30, 30), (1..7).map { Fetch.backoffSec(it) })
        assertEquals(listOf(0, 0, 1, 1, 2, 2, 0), (1..7).map { Fetch.mirrorIndex(it, 3) })
        assertNull(Fetch.rangeHeader(0)); assertEquals("bytes=500-", Fetch.rangeHeader(500))
        assertEquals(3947906L, Fetch.totalFrom("bytes 100-199/3947906")); assertNull(Fetch.totalFrom("bytes 0-1/*"))
        assertTrue(Fetch.resumes(206, "bytes 500-999/1000", 500))
        assertFalse(Fetch.resumes(200, null, 500)); assertFalse(Fetch.resumes(206, "bytes 0-999/1000", 500))
        assertEquals("Can't reach the download server", Fetch.reason(java.net.UnknownHostException("x")))
        assertEquals("The connection went quiet", Fetch.reason(java.net.SocketTimeoutException("Read timed out")))
        assertEquals("Connection dropped", Fetch.reason(java.io.IOException("unexpected end of stream")))
    }

    @Test fun resumesAfterADroppedConnection() {
        cutFirst = 1
        val retries = mutableListOf<String>(); val waits = mutableListOf<Long>()
        val f = Fetch.download(listOf(url("/good")), File(dir, "a.tgz"), sha, progress = { _, _, r -> if (r != null) retries += r }, sleep = { waits += it })
        assertArrayEquals(body, f.readBytes())
        assertEquals(2, ranges.size)
        assertNull(ranges[0]); assertTrue(ranges[1]!!.startsWith("bytes=")); assertTrue(ranges[1]!!.removePrefix("bytes=").removeSuffix("-").toInt() > 0)
        assertEquals(listOf("Connection dropped. Retrying (2 of 6)\u2026"), retries)
        assertEquals(listOf(2_000L), waits)
        assertFalse(File(dir, "a.tgz.part").exists())
    }

    @Test fun startsOverCleanlyWhenTheServerIgnoresRange() {
        cutFirst = 1; honourRange = false
        val f = Fetch.download(listOf(url("/good")), File(dir, "b.tgz"), sha, sleep = { })
        assertArrayEquals(body, f.readBytes())
    }

    @Test fun badChecksumOrMissingFileMovesToTheNextMirror() {
        val f = Fetch.download(listOf(url("/bad"), url("/gone"), url("/good")), File(dir, "c.tgz"), sha, sleep = { })
        assertArrayEquals(body, f.readBytes())
    }

    @Test fun keepsThePartWhenEveryTryFails() {
        cutFirst = 100
        try { Fetch.download(listOf(url("/good")), File(dir, "d.tgz"), sha, tries = 2, sleep = { }); fail() } catch (_: Exception) { }
        assertTrue(File(dir, "d.tgz.part").length() > 0)
        cutFirst = 0
        assertArrayEquals(body, Fetch.download(listOf(url("/good")), File(dir, "d.tgz"), sha, sleep = { }).readBytes())
        assertTrue(ranges.last()!!.startsWith("bytes="))
    }
}
