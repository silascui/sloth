package io.github.silascui.sloth.ui

import android.os.Bundle
import io.github.silascui.sloth.FeedPlaybackState
import io.github.silascui.sloth.PlaybackSessionSnapshot

internal fun FeedPlaybackState.toBundle() = Bundle().apply {
    putInt(PlaybackStateKeys.SELECTED_INDEX, selectedIndex)
    putBoolean(PlaybackStateKeys.MUTED, muted)
    putFloat(PlaybackStateKeys.SPEED, speed)
    putParcelableArrayList(PlaybackStateKeys.SESSIONS, ArrayList(sessions.map { snapshot -> Bundle().apply {
        putString(PlaybackStateKeys.MEDIA_ID, snapshot.mediaId)
        putLong(PlaybackStateKeys.POSITION_MS, snapshot.positionMs)
        putBoolean(PlaybackStateKeys.USER_PAUSED, snapshot.userPaused)
        putFloat(PlaybackStateKeys.SPEED, snapshot.speed)
        putBoolean(PlaybackStateKeys.MUTED, snapshot.muted)
    } }))
}

@Suppress("DEPRECATION")
internal fun Bundle.readPlaybackState() = FeedPlaybackState(
    getInt(PlaybackStateKeys.SELECTED_INDEX), getBoolean(PlaybackStateKeys.MUTED), getFloat(PlaybackStateKeys.SPEED, 1f),
    getParcelableArrayList<Bundle>(PlaybackStateKeys.SESSIONS).orEmpty().map { snapshot ->
        PlaybackSessionSnapshot(requireNotNull(snapshot.getString(PlaybackStateKeys.MEDIA_ID)), snapshot.getLong(PlaybackStateKeys.POSITION_MS),
            snapshot.getBoolean(PlaybackStateKeys.USER_PAUSED), snapshot.getFloat(PlaybackStateKeys.SPEED, 1f), snapshot.getBoolean(PlaybackStateKeys.MUTED))
    }
)
