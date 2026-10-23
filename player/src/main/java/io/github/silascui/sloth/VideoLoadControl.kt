package io.github.silascui.sloth

import android.annotation.SuppressLint
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection

/** 只限制可选预热引擎；正式播放与 DefaultPreloadManager 继续使用原 LoadControl。 */
@SuppressLint("UnsafeOptInUsageError")
internal class VideoLoadControl(private val delegate: LoadControl) : LoadControl {
    val warmupBudget = WarmupBufferBudget()

    // Java 默认接口方法不会自动经 Kotlin by 转发；显式保留 Media3 1.9 的全部运行时入口。
    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)
    override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)
    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)
    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)
    override fun getAllocator(playerId: PlayerId) = delegate.getAllocator(playerId)
    override fun getBackBufferDurationUs(playerId: PlayerId) = delegate.getBackBufferDurationUs(playerId)
    override fun retainBackBufferFromKeyframe(playerId: PlayerId) = delegate.retainBackBufferFromKeyframe(playerId)
    override fun shouldStartPlayback(parameters: LoadControl.Parameters) = delegate.shouldStartPlayback(parameters)
    override fun shouldContinuePreloading(playerId: PlayerId, timeline: Timeline, mediaPeriodId: MediaPeriodId,
        bufferedDurationUs: Long) = delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val allowed = delegate.shouldContinueLoading(parameters)
        if (parameters.playerId.name != warmupBudget.playerName) return allowed
        return allowed && warmupBudget.allows(parameters.playerId.name, parameters.bufferedDurationUs,
            delegate.getAllocator(parameters.playerId).totalBytesAllocated.toLong())
    }
}

/** 主线程切换角色，播放线程读取；提升前清除限制，避免把预热预算带入正式播放。 */
internal class WarmupBufferBudget {
    @Volatile var playerName: String? = null

    fun allows(name: String, bufferedUs: Long, allocatedBytes: Long): Boolean =
        name != playerName || (bufferedUs < MAX_BUFFER_US && allocatedBytes < MAX_BUFFER_BYTES)

    companion object {
        const val MAX_BUFFER_US = 1_000_000L
        const val MAX_BUFFER_BYTES = 2L * 1024 * 1024
    }
}
