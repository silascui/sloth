package io.github.silascui.sloth

import android.os.SystemClock
import androidx.media3.exoplayer.ExoPlayer

/** Exactly one host analytics session per ownership handoff, never on optional warmup. */
@android.annotation.SuppressLint("UnsafeOptInUsageError")
internal class PlaybackReporter(media: VideoMedia, index: Int) {
    private val analytics = VideoPlaybackRuntime.configuration.analytics(media, index)
    private val listener = analytics.listener
    fun attach(player: ExoPlayer, selectedAtMs: Long = SystemClock.elapsedRealtime()) {
        analytics.onAttached(player.playbackState, player.playWhenReady,
            (SystemClock.elapsedRealtime() - selectedAtMs).coerceAtLeast(0L))
        listener?.let(player::addAnalyticsListener)
    }
    fun detach(player: ExoPlayer) {
        listener?.let(player::removeAnalyticsListener)
        analytics.onDetached(player.currentPosition, player.duration)
    }
}
