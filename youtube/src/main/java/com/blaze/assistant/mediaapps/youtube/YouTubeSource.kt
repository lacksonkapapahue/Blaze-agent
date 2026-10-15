package com.blaze.assistant.mediaapps.youtube

import com.blaze.assistant.mediaapps.store.Choice
import com.blaze.assistant.mediaapps.store.StoreDetail
import com.blaze.assistant.mediaapps.store.StoreFormat
import com.blaze.assistant.mediaapps.store.StoreItem
import com.blaze.assistant.mediaapps.store.StorePage
import com.blaze.assistant.mediaapps.store.StoreSource

/**
 * v0.30.1: YouTube as a Store source (Music and Video). Everything YouTube-specific is in this package
 * (YtClient, YtParse, YtQuality, this file) plus its tests, and it is
 * found by the app at runtime by class name (StoreSources.YOUTUBE_CLASS). This whole folder is the Gradle module
 * :youtube; delete (or move) the youtube/ folder and the app builds and runs without a YouTube store.
 */
object YouTubeSource : StoreSource {
    const val ID = "youtube"
    override val id = ID
    override val name = "YouTube"
    override val kinds = setOf("audio", "video")
    override val userAgent: String get() = YtClient.lastUa

    override suspend fun search(kind: String, query: String, next: String): StorePage = YtClient.search(query, next)

    override suspend fun detail(item: StoreItem, kind: String): StoreDetail = YtClient.streams(item.id).detail

    override suspend fun refresh(itemId: String, kind: String): StoreDetail = YtClient.streams(itemId).detail

    override fun choices(kind: String, d: StoreDetail): List<Choice> =
        if (kind == "video") YtQuality.videoChoices(d.formats, d.lengthSec) else YtQuality.audioChoices(d.formats, d.lengthSec)

    override fun defaultChoice(kind: String, choices: List<Choice>): Choice? =
        if (kind == "video") YtQuality.defaultVideo(choices) else YtQuality.defaultAudio(choices)

    override fun streamPick(kind: String, d: StoreDetail): List<StoreFormat> =
        if (kind == "video") YtQuality.streamPick(d.formats) else listOfNotNull(YtQuality.defaultAudio(choices(kind, d))?.keys?.firstOrNull()?.let { k -> d.formats.firstOrNull { it.key == k } })
}
