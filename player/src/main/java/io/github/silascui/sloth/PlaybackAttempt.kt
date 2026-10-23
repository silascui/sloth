package io.github.silascui.sloth

import java.util.concurrent.atomic.AtomicLong

/** 一次可见选中；即使从未获得宿主或没有出帧，也必须保留唯一的结束记录。 */
internal class PlaybackAttempt(val index: Int, val selectedAtMs: Long) {
    val id = nextId.incrementAndGet()
    var preparedAtMs: Long? = null
        private set
    var waitReason = PlaybackWaitReason.WAITING_HOST
        private set
    var end: End? = null
        private set
    val finished: Boolean get() = end != null

    data class End(val id: Long, val index: Int, val outcome: String, val elapsedMs: Long,
        val wasPrepared: Boolean, val waitReason: String)

    fun waiting(reason: String) { if (!finished && preparedAtMs == null) waitReason = reason }
    fun prepared(atMs: Long): Boolean {
        if (finished || preparedAtMs != null) return false
        preparedAtMs = atMs
        waitReason = PlaybackWaitReason.PREPARING
        return true
    }
    fun finish(outcome: String, atMs: Long): End? {
        if (finished) return null
        return End(id, index, outcome, (atMs - selectedAtMs).coerceAtLeast(0L), preparedAtMs != null, waitReason).also { end = it }
    }

    companion object { private val nextId = AtomicLong() }
}

/** 从备用开始准备到首帧的独立计时；提升不重置它，不能把提前完成的工作算作选中后耗时。 */
internal class WarmupTiming(val startedAtMs: Long, val positionMs: Long) {
    var seekBoosted = false
    var promotedAtMs: Long? = null
    var replaceMs = 0L
    var bindMs = 0L
    var sourceLookupMs = 0L
    var sourceSetMs = 0L
    var prepareDispatchMs = 0L
    var readyAtMs: Long? = null
    var decoderReadyAtMs: Long? = null
    var decoderInitializations = 0
    var decoderInitializationMs = 0L
    var firstFrameAtMs: Long? = null
    var inputFormatAtMs: Long? = null
        private set
    var videoWidth: Int? = null
        private set
    var videoHeight: Int? = null
        private set
    var decoderReuseResult: Int? = null
        private set
    var queuedInputBuffers: Int? = null
        private set
    var skippedOutputBuffers: Int? = null
        private set
    var renderedOutputBuffers: Int? = null
        private set

    fun inputFormat(atMs: Long, width: Int, height: Int, reuseResult: Int?) {
        if (firstFrameAtMs != null || inputFormatAtMs != null) return
        inputFormatAtMs = atMs
        videoWidth = width.takeIf { it > 0 }
        videoHeight = height.takeIf { it > 0 }
        decoderReuseResult = reuseResult
    }
    // 调用方以同一 counters 对象的基线求差；这里只冻结非负观测值，不推断 GOP 或网络耗时。
    fun decodeCounters(queued: Int, skipped: Int, rendered: Int) {
        if (firstFrameAtMs != null) return
        queuedInputBuffers = queued.coerceAtLeast(0)
        skippedOutputBuffers = skipped.coerceAtLeast(0)
        renderedOutputBuffers = rendered.coerceAtLeast(0)
    }

    fun ready(atMs: Long) { if (readyAtMs == null) readyAtMs = atMs }
    fun decoderReady(atMs: Long, durationMs: Long) {
        decoderReadyAtMs = atMs
        decoderInitializations++
        decoderInitializationMs += durationMs.coerceAtLeast(0L)
    }
    fun frame(atMs: Long) { if (firstFrameAtMs == null) firstFrameAtMs = atMs }
    fun sinceStart(atMs: Long?): Long? = atMs?.let { (it - startedAtMs).coerceAtLeast(0L) }
}
