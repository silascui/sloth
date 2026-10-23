package io.github.silascui.sloth

import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ScrubbingModeParameters

/** 非零进度预热的分阶段预算；不改变轨道选择、seek 容差或关键帧重置语义。 */
@android.annotation.SuppressLint("UnsafeOptInUsageError")
internal class WarmupSeekBoost {
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private val reduceCodecRate = Runnable { player?.setScrubbingModeParameters(SCHEDULING_PARAMETERS) }
    private val expire = Runnable { player?.let(::finish) }

    fun begin(value: ExoPlayer, positionMs: Long): Boolean {
        player?.let(::finish)
        // 沿用 Media3 在 Android 12 起默认启用的异步 codec，不强制旧设备切换 codec 模式。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || positionMs <= 0L) return false
        player = value
        value.setScrubbingModeParameters(PARAMETERS)
        value.setScrubbingModeEnabled(true)
        // 高 codec operating rate 仍限制为500ms；长距离精确恢复继续使用动态调度，
        // 避免半途退回普通轮询节奏。首帧、取消及退出都会提前finish，另有硬上限。
        handler.postDelayed(reduceCodecRate, CODEC_RATE_DURATION_MS)
        handler.postDelayed(expire, MAX_SCHEDULING_DURATION_MS)
        return true
    }

    fun finish(value: ExoPlayer) {
        if (player !== value) return
        handler.removeCallbacks(reduceCodecRate)
        handler.removeCallbacks(expire)
        player = null
        value.setScrubbingModeEnabled(false)
    }

    companion object {
        private const val CODEC_RATE_DURATION_MS = 500L
        private const val MAX_SCHEDULING_DURATION_MS = 1_500L
        private val PARAMETERS = parameters(increaseCodecRate = true)
        private val SCHEDULING_PARAMETERS = parameters(increaseCodecRate = false)

        private fun parameters(increaseCodecRate: Boolean) = ScrubbingModeParameters.Builder()
            .setDisabledTrackTypes(emptySet())
            .setFractionalSeekTolerance(null, null)
            .setAllowSkippingMediaCodecFlush(false)
            .setAllowSkippingKeyFrameReset(false)
            .setUseDecodeOnlyFlag(false)
            .setShouldIncreaseCodecOperatingRate(increaseCodecRate)
            .setShouldEnableDynamicScheduling(true)
            .build()
    }
}
