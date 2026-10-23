package io.github.silascui.sloth

internal data class PlaybackEngineState(
    val status: PlaybackStatus,
    val positionMs: Long,
    val durationMs: Long,
    val bufferedPositionMs: Long,
    val playWhenReady: Boolean,
    val canSeek: Boolean,
    val error: PlaybackFailure?,
)
