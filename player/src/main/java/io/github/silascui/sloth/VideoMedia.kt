package io.github.silascui.sloth

import java.util.UUID

/** Host-owned media description; signed URLs never appear in framework diagnostics. */
data class VideoMedia(val id: String, val url: String, val live: Boolean = false,
    val initialPositionMs: Long = 0L, val playable: Boolean = true) {
    init { require(id.isNotBlank()); require(url.isNotBlank()); require(initialPositionMs >= 0L) }
    val sourceKey: String = "$id:${UUID.nameUUIDFromBytes(url.toByteArray(Charsets.UTF_8))}"
    companion object {
        internal fun from(media: VideoMedia?): VideoMedia? = media
        internal fun playUrl(media: VideoMedia?): String? = media?.url
    }
}
