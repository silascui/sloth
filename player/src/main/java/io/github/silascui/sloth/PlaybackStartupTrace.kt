package io.github.silascui.sloth

/** 单次选中到首帧的测量；时间均来自 elapsedRealtime，不记录媒体地址或业务 ID。 */
internal class PlaybackStartupTrace(
    val selectedIndex: Int,
    private val selectedAtMs: Long,
    private val prepareAtMs: Long,
    val preloadHit: Boolean,
    private val stages: Stages = Stages(),
    private val warmFrameReused: Boolean = false,
    private val warmEngineReused: Boolean = false,
    private val preload: PreloadDiagnostics = PreloadDiagnostics(),
    private val warmupReason: String = WarmupReason.NOT_WARMED,
    private val attempt: PlaybackAttempt? = null,
    private val restorePositionMs: Long = 0L,
    private val warmup: WarmupTiming? = null,
    private val readSnapshot: (() -> VideoReadStatistics.Snapshot)? = null,
) {
    private val readBeforePrepare = readSnapshot?.invoke()
    class Stages {
        var acquireMs = 0L
        var targetBindMs = 0L
        var targetUnbindMs = 0L
        var sourceLookupMs = 0L
        var sourceSetMs = 0L
        var prepareDispatchMs = 0L
    }
    data class Sample(
        val index: Int,
        val preloadHit: Boolean,
        val selectionToPrepareMs: Long,
        val prepareToReadyMs: Long?,
        val selectionToFirstFrameMs: Long,
        val decoderInitializations: Int,
        val decoderInitializationMs: Long,
        val acquireMs: Long,
        val targetBindMs: Long,
        val targetUnbindMs: Long,
        val sourceLookupMs: Long,
        val sourceSetMs: Long,
        val prepareDispatchMs: Long,
        val warmFrameReused: Boolean,
        val warmEngineReused: Boolean,
        val preloadReason: String,
        val preloadRequestedMs: Long,
        val preloadCompletedMs: Long,
        val preloadQueueMs: Long,
        val preloadLoadMs: Long,
        val preloadStartupMs: Long,
        val warmupReason: String,
        val attemptId: Long,
        val restorePositionMs: Long,
        val prepareToFirstFrameMs: Long,
        val readyToFirstFrameMs: Long?,
        val frameCallbackDelayMs: Long?,
        val warmupLeadMs: Long?,
        val warmupReplaceMs: Long?,
        val warmupBindMs: Long?,
        val warmupSourceLookupMs: Long?,
        val warmupSourceSetMs: Long?,
        val warmupPrepareDispatchMs: Long?,
        val warmupReadyMs: Long?,
        val warmupDecoderInitializations: Int,
        val warmupDecoderInitializationMs: Long,
        val warmupDecoderReadyMs: Long?,
        val warmupFirstFrameMs: Long?,
        val preloadStartPositionMs: Long = 0L,
        val warmupSeekBoosted: Boolean = false,
        val warmupInputFormatMs: Long? = null,
        val warmupFormatToFirstFrameMs: Long? = null,
        val warmupDecoderReuseResult: Int? = null,
        val warmupQueuedInputBuffers: Int? = null,
        val warmupSkippedOutputBuffers: Int? = null,
        val warmupRenderedOutputBuffers: Int? = null,
        val warmupVideoWidth: Int? = null,
        val warmupVideoHeight: Int? = null,
        val read: VideoReadStatistics.Frame? = null,
    )
    private var readyAtMs: Long? = null
    private var completed = false
    private var decoderInitializations = 0
    private var decoderInitializationMs = 0L

    fun onReady(atMs: Long): Long? {
        if (readyAtMs != null) return null
        readyAtMs = atMs
        return (atMs - prepareAtMs).coerceAtLeast(0L)
    }
    fun onDecoderInitialized(durationMs: Long) {
        decoderInitializations++
        decoderInitializationMs += durationMs.coerceAtLeast(0L)
    }
    fun onFirstFrame(atMs: Long, availableAtMs: Long = atMs): Sample? {
        if (completed || attempt?.finished == true || (atMs < prepareAtMs && !warmEngineReused)) return null
        completed = true
        // 预热帧可能已经渲染，但回调尚在主线程排队；此时度量可展示时刻，不丢样本或倒算负耗时。
        val renderedBeforeHandoff = warmEngineReused && atMs < prepareAtMs
        val firstFrameAtMs = if (renderedBeforeHandoff) availableAtMs.coerceAtLeast(prepareAtMs) else atMs
        return Sample(selectedIndex, preloadHit, (prepareAtMs - selectedAtMs).coerceAtLeast(0L),
            readyAtMs?.let { (it - prepareAtMs).coerceAtLeast(0L) },
            (firstFrameAtMs - selectedAtMs).coerceAtLeast(0L), decoderInitializations, decoderInitializationMs,
            stages.acquireMs, stages.targetBindMs, stages.targetUnbindMs, stages.sourceLookupMs,
            stages.sourceSetMs, stages.prepareDispatchMs, warmFrameReused || renderedBeforeHandoff, warmEngineReused,
            preload.reason, preload.requestedTargetMs, preload.completedTargetMs, preload.queueMs, preload.loadMs,
            preload.startupLoadMs, warmupReason, attempt?.id ?: 0L, restorePositionMs,
            (firstFrameAtMs - prepareAtMs).coerceAtLeast(0L), readyAtMs?.let { firstFrameAtMs - it },
            if (warmFrameReused || renderedBeforeHandoff) null else (availableAtMs - atMs).coerceAtLeast(0L),
            warmup?.sinceStart(warmup.promotedAtMs), warmup?.replaceMs, warmup?.bindMs, warmup?.sourceLookupMs,
            warmup?.sourceSetMs, warmup?.prepareDispatchMs, warmup?.sinceStart(warmup.readyAtMs),
            warmup?.decoderInitializations ?: 0, warmup?.decoderInitializationMs ?: 0L,
            warmup?.sinceStart(warmup.decoderReadyAtMs), warmup?.sinceStart(warmup.firstFrameAtMs), preload.startPositionMs, warmup?.seekBoosted == true,
            warmup?.sinceStart(warmup.inputFormatAtMs),
            warmup?.firstFrameAtMs?.let { frame -> warmup.inputFormatAtMs?.let { frame - it } },
            warmup?.decoderReuseResult, warmup?.queuedInputBuffers, warmup?.skippedOutputBuffers,
            warmup?.renderedOutputBuffers, warmup?.videoWidth, warmup?.videoHeight,
            readSnapshot?.invoke()?.let { snapshot -> readBeforePrepare?.let { baseline ->
                VideoReadStatistics.Frame(snapshot, baseline.atMs,
                    (snapshot.cacheBytes - baseline.cacheBytes).coerceAtLeast(0L),
                    (snapshot.networkBytes - baseline.networkBytes).coerceAtLeast(0L))
            } })
    }
}
