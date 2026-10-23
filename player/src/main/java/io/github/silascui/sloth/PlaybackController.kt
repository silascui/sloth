package io.github.silascui.sloth

/** Main-thread playback commands shared by feeds, detail pages and control panels. */
interface PlaybackController : AutoCloseable {
    val state: PlaybackState
    /** Immediately delivers the current state; close the returned subscription when no longer needed. */
    fun observeState(observer: (PlaybackState) -> Unit): AutoCloseable
    fun play()
    fun pause()
    fun retry()
    fun replay()
    fun togglePlayback() { if (state.playWhenReady) pause() else play() }
    fun seekTo(positionMs: Long)
    fun setSpeed(value: Float)
    fun setMuted(value: Boolean)
    override fun close()
}

enum class PlaybackStatus { IDLE, WAITING, READY, BUFFERING, PLAYING, PAUSED, ENDED, ERROR, CLOSED }
enum class PlaybackBlockReason { NONE, HIDDEN, HOST_UNAVAILABLE, MEDIA_UNAVAILABLE, POLICY, INTERRUPTED, SCROLLING }

/** Error codes are safe to display; raw URLs, headers and exception messages are excluded. */
data class PlaybackFailure(val code: Int)

/** Immutable, view-independent state for the currently selected media. */
data class PlaybackState(
    val mediaId: String? = null,
    val selectedIndex: Int = 0,
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val blockReason: PlaybackBlockReason = PlaybackBlockReason.NONE,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val speed: Float = 1f,
    val muted: Boolean = true,
    val userPaused: Boolean = false,
    val visible: Boolean = false,
    val playWhenReady: Boolean = false,
    val canPlay: Boolean = false,
    val canSeek: Boolean = false,
    val error: PlaybackFailure? = null,
) {
    val isPlaying: Boolean get() = status == PlaybackStatus.PLAYING
    val canPause: Boolean get() = playWhenReady
    val canRetry: Boolean get() = canPlay && status == PlaybackStatus.ERROR
}
