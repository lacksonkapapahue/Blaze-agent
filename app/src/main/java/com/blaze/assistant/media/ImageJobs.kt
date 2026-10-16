package com.blaze.assistant.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * v0.30.8: pictures being made in the background. A chat turn starts one and moves on; the job runs in the
 * caller's long-lived scope, and [start]'s `done` swaps the chat's placeholder ([ImageSlot]) for the file (or a
 * failed slot). No Android here (ImageJobsTest).
 */
object ImageJobs {
    private val live = ConcurrentHashMap<String, CompletableDeferred<File?>>()
    private val started = ConcurrentHashMap<String, Long>()
    @Volatile private var newest: String? = null
    @Volatile private var newestResult: CompletableDeferred<File?>? = null

    fun newId(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(16)

    fun isLive(job: String) = live.containsKey(job)

    /** When [job] started (ms), so a placeholder scrolled away and back keeps developing from where it was. */
    fun startedAt(job: String): Long? = started[job]

    /**
     * Runs [make] for [job] in [scope]; then [done] with the file, or null when it failed (an exception counts
     * as a failure). Returns the job id.
     */
    fun start(scope: CoroutineScope, job: String, make: suspend () -> File?, done: suspend (File?) -> Unit): String {
        val d = CompletableDeferred<File?>()
        live[job] = d; started[job] = System.currentTimeMillis(); newest = job; newestResult = d
        scope.launch {
            val f = try { make() } catch (e: kotlinx.coroutines.CancellationException) { null } catch (_: Throwable) { null }
            try { done(f) } catch (_: Throwable) { }
            live.remove(job); d.complete(f)
        }
        return job
    }

    /**
     * Waits (up to [ms]) for the newest picture started, so "make a picture and send it to X" sends the new picture
     * rather than the one before. Its file, or null if it failed, timed out or none was started. Returns at once
     * when it has already finished.
     */
    suspend fun awaitNewest(ms: Long): File? {
        val d = newestResult ?: return null
        return withTimeoutOrNull(ms) { d.await() }
    }

    /** True while the newest picture is still being made. */
    fun newestRunning(): Boolean = newest?.let { live.containsKey(it) } == true

    /** For tests. */
    fun clearForTests() { live.clear(); started.clear(); newest = null; newestResult = null }
}
