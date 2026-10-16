package com.blaze.assistant.mediaapps

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v0.30.1: the Music and Video libraries: what Blaze downloaded (Music/Blaze/Music, Movies/Blaze/Videos) plus the
 * audio and video files already in Blaze's Gallery, and the user's playlists, recently played and video resume
 * positions. Kept as small JSON files in filesDir/mediaapps (survive updates; included in Blaze's own backup).
 */
object MediaLib {
    private var app: Context? = null
    private val lock = Any()

    private val _songs = MutableStateFlow<List<Track>>(emptyList())
    private val _videos = MutableStateFlow<List<Track>>(emptyList())
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    private val _recent = MutableStateFlow<List<String>>(emptyList())
    private val _recentVideos = MutableStateFlow<List<String>>(emptyList())
    val songs: StateFlow<List<Track>> = _songs
    val videos: StateFlow<List<Track>> = _videos
    val playlists: StateFlow<List<Playlist>> = _playlists
    val recent: StateFlow<List<String>> = _recent
    val recentVideos: StateFlow<List<String>> = _recentVideos
    private val _liked = MutableStateFlow<List<String>>(emptyList())
    /** Liked songs, newest first. */
    val liked: StateFlow<List<String>> = _liked

    private var downloads = listOf<Track>()   // what Blaze downloaded (both kinds)
    private val positions = HashMap<String, Long>()

    private fun dir(ctx: Context) = File(ctx.filesDir, "mediaapps").apply { mkdirs() }
    private fun f(ctx: Context, n: String) = File(dir(ctx), n)

    fun ensure(ctx: Context) {
        synchronized(lock) {
            if (app != null) return
            app = ctx.applicationContext
            val c = app!!
            Taste.init(c)
            downloads = readTracks(f(c, "library.json"))
            _playlists.value = runCatching { val a = JSONArray(f(c, "playlists.json").readText()); (0 until a.length()).map { pl(a.getJSONObject(it)) } }.getOrDefault(emptyList())
            _recent.value = readStrings(f(c, "recent.json"))
            _recentVideos.value = readStrings(f(c, "recent-videos.json"))
            _liked.value = readStrings(f(c, "liked.json"))
            runCatching { val o = JSONObject(f(c, "positions.json").readText()); o.keys().forEach { positions[it] = o.optLong(it) } }
        }
        refresh()
    }

    /** Re-reads the Gallery and drops downloads the user deleted elsewhere. Call off the main thread when possible. */
    fun refresh() {
        val c = app ?: return
        val kept = downloads.filter { MediaFiles.exists(c, it.uri) }
        if (kept.size != downloads.size) { downloads = kept; saveTracks() }
        val gallery = try {
            com.blaze.assistant.media.Media.init(c)
            com.blaze.assistant.media.Media.items.value.filter { it.kind == "audio" || it.kind == "video" }.map { m ->
                Track(Uri.fromFile(m.file).toString(), Uri.fromFile(m.file).toString(), m.title, "Blaze", "Gallery", 0, "", "gallery", m.time, "", m.kind)
            }
        } catch (_: Exception) { emptyList() }
        _songs.value = (kept.filter { it.kind == "audio" } + gallery.filter { it.kind == "audio" }).sortedByDescending { it.addedAt }
        _videos.value = (kept.filter { it.kind == "video" } + gallery.filter { it.kind == "video" }).sortedByDescending { it.addedAt }
    }

    fun find(key: String): Track? = _songs.value.firstOrNull { it.key == key } ?: _videos.value.firstOrNull { it.key == key }

    fun addDownload(t: Track) {
        synchronized(lock) { downloads = listOf(t) + downloads.filter { it.key != t.key } }
        saveTracks(); refresh()
    }

    /** Removes a downloaded file from the phone (gallery files are the Gallery's to delete). */
    fun deleteDownload(ctx: Context, key: String): Boolean {
        val t = downloads.firstOrNull { it.key == key } ?: return false
        MediaFiles.delete(ctx, t.uri)
        synchronized(lock) { downloads = downloads.filter { it.key != key } }
        setPlaylists(PlaylistOps.forget(_playlists.value, key))
        if (key in _liked.value) toggleLike(key)
        saveTracks(); refresh()
        return true
    }

    // ---- playlists ----
    fun setPlaylists(l: List<Playlist>) {
        _playlists.value = l
        val c = app ?: return
        val a = JSONArray(); l.forEach { p -> a.put(JSONObject().put("id", p.id).put("name", p.name).put("keys", JSONArray(p.keys)).put("u", p.updated)) }
        write(f(c, "playlists.json"), a.toString())
    }
    fun newPlaylistId() = "pl" + System.currentTimeMillis().toString(36) + (100..999).random()

    // ---- liked ----
    fun isLiked(key: String) = key in _liked.value
    fun toggleLike(key: String) {
        val c = app ?: return
        _liked.value = if (key in _liked.value) _liked.value - key else listOf(key) + _liked.value
        write(f(c, "liked.json"), JSONArray(_liked.value).toString())
    }

    // ---- the last music queue (restored when the player starts again) ----
    fun saveQueue(tracks: List<Track>, index: Int, posMs: Long) {
        val c = app ?: return
        val a = JSONArray(); tracks.forEach { a.put(trackJson(it)) }
        write(f(c, "queue.json"), JSONObject().put("items", a).put("index", index).put("pos", posMs).toString())
    }
    fun loadQueue(): Triple<List<Track>, Int, Long>? {
        val c = app ?: return null
        return runCatching {
            val o = JSONObject(f(c, "queue.json").readText())
            val a = o.getJSONArray("items")
            val items = (0 until a.length()).map { trackFrom(a.getJSONObject(it)) }.filter { it.source != "stream" && MediaFiles.exists(c, it.uri) }
            if (items.isEmpty()) null else Triple(items, o.optInt("index").coerceIn(0, items.size - 1), o.optLong("pos"))
        }.getOrNull()
    }

    // ---- recents / positions ----
    fun played(t: Track) {
        val c = app ?: return
        Taste.note(c, t.kind, t.title, t.artist, Taste.PLAY)
        if (t.kind == "video") { _recentVideos.value = Recents.push(_recentVideos.value, t.key); write(f(c, "recent-videos.json"), JSONArray(_recentVideos.value).toString()) }
        else { _recent.value = Recents.push(_recent.value, t.key); write(f(c, "recent.json"), JSONArray(_recent.value).toString()) }
    }

    fun position(key: String): Long = synchronized(lock) { positions[key] ?: 0 }
    fun savePosition(key: String, posMs: Long, durMs: Long) {
        val c = app ?: return
        synchronized(lock) {
            if (ResumeLogic.keep(posMs, durMs)) positions[key] = posMs else positions.remove(key)
            if (positions.size > 300) positions.keys.take(positions.size - 300).forEach { positions.remove(it) }
            write(f(c, "positions.json"), JSONObject(positions as Map<*, *>).toString())
        }
    }

    // ---- JSON ----
    private fun write(file: File, text: String) = try { val tmp = File(file.path + ".tmp"); tmp.writeText(text); tmp.renameTo(file) } catch (_: Exception) { false }
    private fun readStrings(file: File): List<String> = runCatching { val a = JSONArray(file.readText()); (0 until a.length()).map { a.getString(it) } }.getOrDefault(emptyList())
    private fun pl(o: JSONObject) = Playlist(o.optString("id"), o.optString("name"),
        o.optJSONArray("keys")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(), o.optLong("u"))

    fun trackJson(t: Track): JSONObject = JSONObject().put("k", t.key).put("u", t.uri).put("t", t.title).put("a", t.artist).put("al", t.album)
        .put("d", t.durationMs).put("art", t.art).put("src", t.source).put("at", t.addedAt).put("vid", t.videoId).put("kind", t.kind).put("lic", t.licence).put("ss", t.storeSource)
    fun trackFrom(o: JSONObject) = Track(o.optString("k"), o.optString("u"), o.optString("t"), o.optString("a"), o.optString("al"), o.optLong("d"),
        o.optString("art"), o.optString("src"), o.optLong("at"), o.optString("vid"), o.optString("kind", "audio"), o.optString("lic"), o.optString("ss"))

    private fun readTracks(file: File): List<Track> = runCatching { val a = JSONArray(file.readText()); (0 until a.length()).map { trackFrom(a.getJSONObject(it)) } }.getOrDefault(emptyList())
    private fun saveTracks() {
        val c = app ?: return
        val a = JSONArray(); synchronized(lock) { downloads.forEach { a.put(trackJson(it)) } }
        write(f(c, "library.json"), a.toString())
    }
}
