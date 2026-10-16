package com.blaze.assistant.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v0.30.8: silent, async pictures: the placeholder slot in a reply's media, its reveal curve, and background jobs. */
class ImageSlotTest {

    @Test fun tokenRoundTrips() {
        val t = ImageSlot.pending("abc123", 1080, 1920, "a fox: at dawn, 50% fog & rain")
        assertTrue(ImageSlot.isSlot(t))
        val s = ImageSlot.parse(t)!!
        assertEquals(ImageSlot.State.PENDING, s.state)
        assertEquals("abc123", s.job)
        assertEquals(1080, s.width); assertEquals(1920, s.height)
        assertEquals("a fox: at dawn, 50% fog & rain", s.prompt)
        val f = ImageSlot.parse(s.failed().token())!!
        assertEquals(ImageSlot.State.FAILED, f.state)
        assertEquals(s.prompt, f.prompt)
        assertNull(ImageSlot.parse("/data/user/0/com.blaze/files/gallery/fox.png"))
        assertNull(ImageSlot.parse("blaze-image:weird:x:1x1:p"))
        // The job id can't break the token.
        assertEquals("ab-12", ImageSlot.parse(ImageSlot.pending("ab:-12", 1, 1, "p"))!!.job)
    }

    @Test fun aspectIsClamped() {
        assertEquals(1f, ImageSlot.aspectOf(1024, 1024), 0.001f)
        assertEquals(9f / 16f, ImageSlot.aspectOf(1080, 1920), 0.001f)
        assertEquals(9f / 16f, ImageSlot.aspectOf(100, 1000), 0.001f)
        assertEquals(16f / 9f, ImageSlot.aspectOf(4000, 500), 0.001f)
        assertEquals(1f, ImageSlot.aspectOf(0, 0), 0.001f)
    }

    @Test fun replacesInPlace() {
        val a = ImageSlot.pending("j1", 1024, 1024, "cat")
        val b = ImageSlot.pending("j2", 1024, 1024, "dog")
        val media = listOf("/x/beat.wav", a, b)
        assertEquals(listOf("/x/beat.wav", "/x/cat.png", b), ImageSlot.replace(media, "j1", "/x/cat.png"))
        assertEquals(media, ImageSlot.replace(media, "nope", "/x/y.png"))
        assertTrue(ImageSlot.holds(media, "j2")); assertFalse(ImageSlot.holds(media, "j3"))
    }

    @Test fun loadTurnsOrphanedPendingIntoFailed() {
        val live = ImageSlot.pending("live1", 1024, 1024, "running")
        val orphan = ImageSlot.pending("dead1", 1024, 768, "lost")
        val failed = ImageSlot.parse(ImageSlot.pending("old", 1, 1, "f"))!!.failed().token()
        val out = ImageSlot.onLoad(listOf("/gone.png", "/here.png", live, orphan, failed), live = { it == "live1" }, exists = { it == "/here.png" })
        assertEquals(4, out.size)
        assertEquals("/here.png", out[0])
        assertEquals(live, out[1])
        assertEquals(ImageSlot.State.FAILED, ImageSlot.parse(out[2])!!.state)
        assertEquals("lost", ImageSlot.parse(out[2])!!.prompt)
        assertEquals(failed, out[3])
    }

    @Test fun revealDevelopsButNeverFinishesAlone() {
        assertEquals(0f, ImageSlot.reveal(0), 0f)
        val early = ImageSlot.reveal(2_000); val mid = ImageSlot.reveal(15_000); val late = ImageSlot.reveal(120_000)
        assertTrue(early > 0f && early < mid && mid < late)
        assertTrue(late <= 0.85f)
        assertEquals(36f, ImageSlot.blurDp(0f), 0.001f)
        assertEquals(6f, ImageSlot.blurDp(1f), 0.001f)
        assertTrue(ImageSlot.blurDp(mid) < ImageSlot.blurDp(early))
    }

    @Test fun notifiesOnlyWhenElsewhere() {
        assertFalse(ImageSlot.shouldNotify(isCurrentChat = true, inForeground = true, overlayOpen = false))
        assertFalse(ImageSlot.shouldNotify(isCurrentChat = true, inForeground = false, overlayOpen = true))
        assertTrue(ImageSlot.shouldNotify(isCurrentChat = true, inForeground = false, overlayOpen = false))
        assertTrue(ImageSlot.shouldNotify(isCurrentChat = false, inForeground = true, overlayOpen = false))
    }

    @Test fun userFacingLinesNameNoService() {
        for (s in listOf(ImageSlot.ACK, ImageSlot.READY, ImageSlot.FAILED_TEXT, ImageSlot.FAILED_NOTE))
            assertFalse(s, Regex("(?i)qwen|pollinations|fallback|service|step").containsMatchIn(s))
    }

    @Test fun jobsRunInTheBackgroundAndCanBeAwaited() = runBlocking {
        ImageJobs.clearForTests()
        val gate = CompletableDeferred<Unit>()
        val done = CompletableDeferred<File?>()
        val f = File("/tmp/fake.png")
        val id = ImageJobs.start(CoroutineScope(Dispatchers.Default), ImageJobs.newId(), make = { gate.await(); f }, done = { done.complete(it) })
        // start() returned before the picture exists: the turn isn't blocked.
        assertTrue(ImageJobs.isLive(id)); assertTrue(ImageJobs.newestRunning())
        assertNotNull(ImageJobs.startedAt(id))
        gate.complete(Unit)
        assertEquals(f, ImageJobs.awaitNewest(5_000))
        assertEquals(f, done.await())
        Thread.sleep(50)
        assertFalse(ImageJobs.isLive(id)); assertFalse(ImageJobs.newestRunning())
        assertEquals(f, ImageJobs.awaitNewest(10))   // already finished: returns at once
        ImageJobs.clearForTests()
        assertNull(ImageJobs.awaitNewest(10))       // none started
    }

    @Test fun aFailingJobReportsNull() = runBlocking {
        ImageJobs.clearForTests()
        val done = CompletableDeferred<File?>()
        ImageJobs.start(CoroutineScope(Dispatchers.Default), "boom", make = { throw IllegalStateException("x") }, done = { done.complete(it) })
        assertNull(done.await())
    }
}
