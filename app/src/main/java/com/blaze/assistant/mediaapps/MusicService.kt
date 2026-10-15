package com.blaze.assistant.mediaapps

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * v0.30.1 Blaze Music's player: media3 ExoPlayer in a MediaSessionService, so music keeps playing with Blaze closed,
 * with the media notification and lock-screen / Bluetooth / headset controls. Audio focus is handled (a call or another
 * app's sound pauses or ducks it) and unplugging headphones pauses it. Sleep timer and the shuffle order live here.
 */
class MusicService : MediaSessionService() {
    companion object {
        /** The running service (same process), for the sleep timer. */
        @Volatile var current: MusicService? = null
            private set
        /** When the sleep timer stops playback (epoch ms, 0 = off). -1 = at the end of the current song. */
        val sleepAt = MutableStateFlow(0L)

        fun item(t: Track): MediaItem = MediaItem.Builder().setMediaId(t.key).setUri(t.uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist.ifBlank { null })
                .setAlbumTitle(t.album.ifBlank { null })
                .setArtworkUri(t.art.takeIf { it.isNotBlank() }?.let { if (it.startsWith("http")) Uri.parse(it) else Uri.fromFile(java.io.File(it)) })
                .setIsPlayable(true).build()).build()

        /** A Store result played without downloading (url from the source; expires, so never saved). */
        fun streamItem(t: Track, url: String): MediaItem = item(t.copy(uri = url))
    }

    private var session: MediaSession? = null
    /** Network streams (Store "Play"): the user-agent follows the source (see [streamUa]). */
    private val http = androidx.media3.datasource.DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)

    fun streamUa(ua: String) { http.setUserAgent(ua) }
    private lateinit var player: ExoPlayer
    private val main = Handler(Looper.getMainLooper())
    private val sleepTick = object : Runnable {
        override fun run() {
            val at = sleepAt.value
            if (at > 0 && System.currentTimeMillis() >= at) { player.pause(); sleepAt.value = 0 }
            main.postDelayed(this, 1000)
        }
    }

    private val saver = object : Runnable {
        override fun run() { if (player.isPlaying) saveQueue(); main.postDelayed(this, 15_000) }
    }

    /** The queue as library tracks (streams are left out: their urls expire). */
    fun saveQueue() {
        val n = player.mediaItemCount
        if (n == 0) return
        val tracks = (0 until n).mapNotNull { MediaLib.find(player.getMediaItemAt(it).mediaId) }
        val cur = player.currentMediaItem?.mediaId
        val idx = tracks.indexOfFirst { it.key == cur }.coerceAtLeast(0)
        if (tracks.isNotEmpty()) MediaLib.saveQueue(tracks, idx, if (tracks.getOrNull(idx)?.key == cur) player.currentPosition else 0)
    }

    override fun onCreate() {
        super.onCreate()
        MediaLib.ensure(this)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                androidx.media3.datasource.DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(object : Player.Listener {
            override fun onShuffleModeEnabledChanged(on: Boolean) { if (on) reshuffle() }
            override fun onIsPlayingChanged(playing: Boolean) { if (!playing) saveQueue() }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                item?.mediaId?.let { id -> MediaLib.find(id)?.let { MediaLib.played(it) } }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && sleepAt.value == -1L) sleepAt.value = 0
            }
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                // "End of song" sleep timer: stop when the song changes on its own.
                if (sleepAt.value == -1L && reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) { player.pause(); sleepAt.value = 0 }
            }
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MusicActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(open).build()
        current = this
        main.post(sleepTick)
        // Resume where the user left off: the last queue, song and position (paused, ready to play).
        MediaLib.loadQueue()?.let { (items, index, pos) ->
            player.setMediaItems(items.map { item(it) }, index, pos)
            player.prepare()
        }
        main.postDelayed(saver, 15_000)
    }

    /** Shuffle with the song that's playing first (QueueLogic, unit tested), the rest random. */
    fun reshuffle() {
        val n = player.mediaItemCount
        if (n < 2) return
        val order = QueueLogic.shuffleOrder(n, player.currentMediaItemIndex.coerceAtLeast(0), System.nanoTime())
        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), System.nanoTime()))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0 || p.playbackState == Player.STATE_ENDED) stopSelf()
    }

    override fun onDestroy() {
        saveQueue()
        main.removeCallbacks(sleepTick); main.removeCallbacks(saver)
        current = null
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
