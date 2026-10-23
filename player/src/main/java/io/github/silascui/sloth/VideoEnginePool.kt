package io.github.silascui.sloth

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.MediaSource

/** Runtime 内唯一的引擎组：一个当前引擎，加至多一个暂停、静音、无统计的预热引擎。 */
@SuppressLint("UnsafeOptInUsageError")
internal class VideoEnginePool(
    private val context: Context,
    private val onWarmPlayerChanged: (String?) -> Unit,
    private val warmupSeekBoostEnabled: Boolean = true,
    private val retainWarmDecoder: Boolean = true,
    private val onWarmupCancelled: (Request) -> Unit = {},
    private val createPlayer: (String) -> ExoPlayer,
) : ComponentCallbacks2 {
    data class Request(val media: VideoMedia, val target: FeedRenderTarget, val positionMs: Long)
    data class Promotion(val firstFrameRendered: Boolean, val timing: WarmupTiming)

    private val seekBoost = WarmupSeekBoost()
    private val renders = java.util.IdentityHashMap<ExoPlayer, VideoRenderBinding>()
    private var activeName = PRIMARY_ENGINE_NAME
    private var spareName = SECONDARY_ENGINE_NAME
    var active: ExoPlayer = createPlayer(activeName)
        private set
    private var spare: ExoPlayer? = null
    private var request: Request? = null
    private var timing: WarmupTiming? = null
    private var listener: AnalyticsListener? = null
    private var promotedListener: AnalyticsListener? = null
    private var onPromotedFrame: ((Long) -> Unit)? = null
    private var firstFrameRendered = false
    private var renderedSurface: android.graphics.SurfaceTexture? = null
    private var disabled = context.getSystemService(ActivityManager::class.java)?.let {
        it.isLowRamDevice || it.memoryClass < MIN_DUAL_ENGINE_MEMORY_MB
    } ?: true
    val engineCount: Int get() = if (spare == null) 1 else 2
    val warmedTarget: FeedRenderTarget? get() = request?.target
    val warmedMediaKey: String? get() = request?.media?.sourceKey
    val hasWarmFrame: Boolean get() = firstFrameRendered
    var lastPromotionReason = WarmupReason.NOT_WARMED
        private set

    init { context.registerComponentCallbacks(this) }

    fun presentation(player: ExoPlayer = active): Player =
        renders.getOrPut(player) { VideoRenderBinding(context, player) }.player

    fun detachRender(player: ExoPlayer) { renders[player]?.detach() }

    fun warm(value: Request, source: () -> MediaSource) {
        if (disabled || value.media.live || !value.target.isHostAttached()) { cancelWarmup(); return }
        val previous = request
        if (previous?.media?.sourceKey == value.media.sourceKey && previous.target === value.target &&
            previous.positionMs == value.positionMs) return
        val measurement = WarmupTiming(SystemClock.elapsedRealtime(), value.positionMs)
        cancelWarmup()
        measurement.replaceMs = SystemClock.elapsedRealtime() - measurement.startedAtMs
        val player = spare ?: try {
            createPlayer(spareName).also { spare = it }
        } catch (failure: IllegalStateException) {
            // 资源构建失败仅关闭可选预热，不改变当前播放的错误处理。
            PlayerLog.d("VideoWarmup disabled reason=engine_creation type=${failure.javaClass.simpleName}")
            disabled = true
            return
        }
        // Runtime 随可见页面持有资源组；在途取消仍 stop，但不因此重置兼容 codec。
        // release 始终完整释放。不要在热交接中 setForegroundMode(false)，该 API 会同步等待播放线程。
        if (retainWarmDecoder) player.setForegroundMode(true)
        onWarmPlayerChanged(spareName)
        if (warmupSeekBoostEnabled) measurement.seekBoosted = seekBoost.begin(player, value.positionMs)
        request = value
        timing = measurement
        firstFrameRendered = false
        player.volume = 0F
        player.playWhenReady = false
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.setPlaybackSpeed(1F)
        val initialCounters = player.videoDecoderCounters
        initialCounters?.ensureUpdated()
        val initialQueued = initialCounters?.queuedInputBufferCount ?: 0
        val initialSkipped = initialCounters?.skippedOutputBufferCount ?: 0
        val initialRendered = initialCounters?.renderedOutputBufferCount ?: 0
        listener = object : AnalyticsListener {
            private fun matchesMedia(eventTime: AnalyticsListener.EventTime): Boolean =
                eventTime.windowIndex in 0 until eventTime.timeline.windowCount &&
                    eventTime.timeline.getWindow(eventTime.windowIndex, androidx.media3.common.Timeline.Window()).mediaItem.mediaId == value.media.sourceKey

            override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime,
                format: androidx.media3.common.Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
                if (matchesMedia(eventTime)) measurement.inputFormat(eventTime.realtimeMs,
                    format.width, format.height, decoderReuseEvaluation?.result)
            }
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
                initializedTimestampMs: Long, initializationDurationMs: Long) {
                if (matchesMedia(eventTime)) measurement.decoderReady(initializedTimestampMs, initializationDurationMs)
            }
            override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
                if (matchesMedia(eventTime) && state == Player.STATE_READY) measurement.ready(SystemClock.elapsedRealtime())
            }
            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                if (!matchesMedia(eventTime) || (request !== value && promotedListener !== this)) return
                player.videoDecoderCounters?.let { counters ->
                    counters.ensureUpdated()
                    val sameEpoch = counters === initialCounters
                    measurement.decodeCounters(
                        counters.queuedInputBufferCount - if (sameEpoch) initialQueued else 0,
                        counters.skippedOutputBufferCount - if (sameEpoch) initialSkipped else 0,
                        counters.renderedOutputBufferCount - if (sameEpoch) initialRendered else 0)
                }
                measurement.frame(renderTimeMs)
                if (request === value && spare === player) {
                    seekBoost.finish(player)
                    firstFrameRendered = true
                    renderedSurface = value.target.outputTexture()
                    PlayerLog.d("VideoWarmup firstFrame")
                } else if (active === player && promotedListener === this) {
                    seekBoost.finish(player)
                    val callback = onPromotedFrame
                    clearPromotedListener()
                    callback?.invoke(renderTimeMs)
                }
            }
            override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
                if (request === value && spare === player) {
                    PlayerLog.d("VideoWarmup disabled reason=prepare_error code=${error.errorCode}")
                    disable()
                }
            }
        }.also(player::addAnalyticsListener)
        val bindAtMs = SystemClock.elapsedRealtime()
        value.target.bindWarmup(value, presentation(player))
        measurement.bindMs = SystemClock.elapsedRealtime() - bindAtMs
        val lookupAtMs = SystemClock.elapsedRealtime()
        val mediaSource = source()
        measurement.sourceLookupMs = SystemClock.elapsedRealtime() - lookupAtMs
        val setAtMs = SystemClock.elapsedRealtime()
        player.setMediaSource(mediaSource, value.positionMs)
        measurement.sourceSetMs = SystemClock.elapsedRealtime() - setAtMs
        val prepareAtMs = SystemClock.elapsedRealtime()
        player.prepare()
        measurement.prepareDispatchMs = SystemClock.elapsedRealtime() - prepareAtMs
    }

    fun promote(media: VideoMedia, target: PlaybackRenderTarget?, positionMs: Long, onFirstFrame: (Long) -> Unit): Promotion? {
        val value = request ?: run {
            lastPromotionReason = if (disabled) WarmupReason.RESOURCE_DISABLED else WarmupReason.NOT_WARMED
            return null
        }
        lastPromotionReason = when {
            value.media.sourceKey != media.sourceKey -> WarmupReason.MEDIA_CHANGED
            value.target !== target -> WarmupReason.HOST_CHANGED
            value.positionMs != positionMs -> WarmupReason.POSITION_CHANGED
            !value.target.outputReady() -> WarmupReason.SURFACE_NOT_READY
            firstFrameRendered && renderedSurface !== value.target.outputTexture() -> WarmupReason.SURFACE_RECREATED
            spare?.playerError != null -> WarmupReason.WARMUP_ERROR
            else -> if (firstFrameRendered) WarmupReason.WARM_FRAME_READY else WarmupReason.ENGINE_PREPARING
        }
        if (lastPromotionReason != WarmupReason.WARM_FRAME_READY && lastPromotionReason != WarmupReason.ENGINE_PREPARING) {
            cancelWarmup()
            return null
        }
        val next = spare ?: return null
        val measurement = checkNotNull(timing)
        measurement.promotedAtMs = SystemClock.elapsedRealtime()
        val promotion = Promotion(firstFrameRendered, measurement)
        if (firstFrameRendered) listener?.let(next::removeAnalyticsListener)
        else {
            // 首帧在途时也复用已经准备的引擎；保留原监听桥接已排队的首帧事件，避免丢回调。
            promotedListener = listener
            onPromotedFrame = onFirstFrame
        }
        listener = null
        value.target.promoteWarmup(value)
        request = null
        timing = null
        firstFrameRendered = false
        renderedSurface = null
        onWarmPlayerChanged(null)
        val previousName = activeName
        activeName = spareName
        spareName = previousName
        spare = active
        active = next
        return promotion
    }

    fun clearPromotedListener() {
        seekBoost.finish(active)
        promotedListener?.let(active::removeAnalyticsListener)
        promotedListener = null
        onPromotedFrame = null
    }

    fun cancelWarmup(target: PlaybackRenderTarget? = null, releaseEngine: Boolean = false) {
        val value = request
        if (target != null && value?.target !== target) return
        val cancelledBeforeFrame = value != null && !firstFrameRendered
        request = null
        timing = null
        firstFrameRendered = false
        renderedSurface = null
        spare?.let { player ->
            seekBoost.finish(player)
            listener?.let(player::removeAnalyticsListener)
            value?.target?.unbindWarmup(value)
            player.pause()
            // 在途取消仍 stop 回到 IDLE，保留既有取消保护；前台模式仅避免因此 reset 兼容 codec。
            if (releaseEngine || cancelledBeforeFrame) player.stop()
            player.clearMediaItems()
            detachRender(player)
            if (releaseEngine) {
                spare = null
                player.release()
                renders.remove(player)?.releaseAfterEngine()
            }
        }
        listener = null
        onWarmPlayerChanged(null)
        // 取消会释放已经借入的 sample period；协调器必须撤销该代次的就绪状态。
        value?.let(onWarmupCancelled)
    }

    fun disable() {
        disabled = true
        cancelWarmup(releaseEngine = true)
    }

    fun release() {
        context.unregisterComponentCallbacks(this)
        clearPromotedListener()
        cancelWarmup(releaseEngine = true)
        active.release()
        renders.remove(active)?.releaseAfterEngine()
    }

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) disable()
    }
    override fun onLowMemory() = disable()
    override fun onConfigurationChanged(newConfig: Configuration) = Unit
    private companion object {
        private const val PRIMARY_ENGINE_NAME = "video_runtime_a"
        private const val SECONDARY_ENGINE_NAME = "video_runtime_b"
        private const val MIN_DUAL_ENGINE_MEMORY_MB = 192
    }

}
