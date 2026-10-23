package io.github.silascui.sloth

/** 可恢复的轻量值，不持有 Player、View、Context 或网络请求。 */
data class PlaybackSessionSnapshot(
    val mediaId: String,
    val positionMs: Long,
    val userPaused: Boolean,
    val speed: Float,
    val muted: Boolean,
)

data class FeedPlaybackState(
    val selectedIndex: Int,
    val muted: Boolean,
    val speed: Float,
    val sessions: List<PlaybackSessionSnapshot>,
)
