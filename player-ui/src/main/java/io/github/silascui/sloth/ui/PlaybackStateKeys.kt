package io.github.silascui.sloth.ui

/** Persisted keys are stable to restore state saved by earlier versions. */
internal object PlaybackStateKeys {
    const val PLAYBACK = "playback"
    const val SCROLL = "scroll"
    const val SELECTED_INDEX = "index"
    const val MUTED = "muted"
    const val SPEED = "speed"
    const val SESSIONS = "sessions"
    const val MEDIA_ID = "id"
    const val POSITION_MS = "position"
    const val USER_PAUSED = "paused"
}
