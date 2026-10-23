package io.github.silascui.sloth

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.io.File

@android.annotation.SuppressLint("UnsafeOptInUsageError")
class PlayerConfiguration(
    val cacheDirectory: File? = null,
    val cacheBytes: Long = DEFAULT_CACHE_BYTES,
    val upstream: (Context) -> DataSource.Factory = { DefaultDataSource.Factory(it) },
    val codecSelector: MediaCodecSelector = MediaCodecSelector.DEFAULT,
    val autoplayAllowed: () -> Boolean = { true },
    val playbackPolicy: PlaybackPolicy = PlaybackPolicy.Allow,
    val analytics: (VideoMedia, Int) -> PlaybackAnalytics = { _, _ -> PlaybackAnalytics.None },
    val events: PlaybackEvents = PlaybackEvents.None,
    val beforeAcquire: () -> Unit = {},
    val logger: ((String) -> Unit)? = null,
) {
    init { require(cacheBytes > 0L) }
    companion object {
        /** Default process-wide disk cache capacity (512 MiB). */
        const val DEFAULT_CACHE_BYTES = 512L * 1024 * 1024
    }
}

/** Host implements metered-network consent, entitlement, and other business decisions here.
 * Return false while awaiting consent; invoke onAllowed on the main thread only if isCurrent(). */
fun interface PlaybackPolicy {
    fun permits(context: Context, media: VideoMedia, manual: Boolean,
        isCurrent: () -> Boolean, onAllowed: () -> Unit): Boolean
    companion object { val Allow = PlaybackPolicy { _, _, _, _, _ -> true } }
}

interface PlaybackAnalytics {
    val listener: AnalyticsListener? get() = null
    fun onAttached(state: Int, playWhenReady: Boolean, selectionElapsedMs: Long) {}
    fun onDetached(positionMs: Long, durationMs: Long) {}
    object None : PlaybackAnalytics
}

data class PlaybackMetrics(val index: Int, val selectionToFrameMs: Long, val restorePositionMs: Long,
    val reusedWarmFrame: Boolean, val reusedWarmEngine: Boolean, val preloadReason: String)

interface PlaybackEvents {
    fun onFirstFrame(metrics: PlaybackMetrics) {}
    fun onError(mediaId: String, errorCode: Int) {}
    fun onAudiblePlaybackStarted() {}
    fun onAudiblePlaybackStopped() {}
    object None : PlaybackEvents
}

internal object PlayerLog {
    fun d(message: String) { VideoPlaybackRuntime.configuration.logger?.invoke(message) }
    fun d(tag: String, message: String) = d("$tag $message")
}
