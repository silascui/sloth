package io.github.silascui.sloth

/** 记录目标阶段而非推测实际缓存命中；轨道完成不能当作 sample 可用。 */
internal class PreloadProgress(private val registeredAtMs: Long,
    private val startupTargetMs: Long = STARTUP_TARGET_MS) {
    init { require(startupTargetMs in 1..FULL_TARGET_MS) }
    var requestedTargetMs: Long? = null
        private set
    var completedTargetMs = 0L
        private set
    private var startedAtMs: Long? = null
    private var stageStartedAtMs: Long? = null
    private var completedAtMs: Long? = null
    private var tracksCompleted = false
    private var failed = false
    private var startupLoadMs = 0L

    fun request(targetMs: Long, atMs: Long) {
        requestedTargetMs = targetMs
        if (startedAtMs == null) startedAtMs = atMs
        stageStartedAtMs = atMs
        completedAtMs = null
        failed = false
    }

    fun complete(atMs: Long): Boolean {
        val target = requestedTargetMs ?: return false
        val wasReady = completedTargetMs >= startupTargetMs
        if (target == 0L) tracksCompleted = true
        else completedTargetMs = maxOf(completedTargetMs, target)
        completedAtMs = atMs
        failed = false
        if (!wasReady && completedTargetMs >= startupTargetMs) {
            startupLoadMs = (atMs - (stageStartedAtMs ?: atMs)).coerceAtLeast(0L)
        }
        return !wasReady && completedTargetMs >= startupTargetMs
    }

    fun finishRequest(atMs: Long) { if (completedAtMs == null) completedAtMs = atMs }

    fun fail(atMs: Long? = null) {
        failed = true
        atMs?.let(::finishRequest)
    }

    fun snapshot(atMs: Long, overrideReason: String? = null): PreloadDiagnostics {
        val reason = overrideReason ?: when {
            completedTargetMs >= FULL_TARGET_MS -> PreloadReason.BUFFER_READY
            completedTargetMs >= startupTargetMs -> PreloadReason.STARTUP_READY
            failed -> PreloadReason.PRELOAD_ERROR
            requestedTargetMs == null -> PreloadReason.NOT_STARTED
            tracksCompleted && requestedTargetMs == 0L -> PreloadReason.TRACKS_ONLY
            else -> PreloadReason.IN_PROGRESS
        }
        return PreloadDiagnostics(reason, requestedTargetMs ?: 0L, completedTargetMs,
            ((startedAtMs ?: atMs) - registeredAtMs).coerceAtLeast(0L),
            stageStartedAtMs?.let { ((completedAtMs ?: atMs) - it).coerceAtLeast(0L) } ?: 0L, startupLoadMs)
    }

    companion object {
        const val STARTUP_TARGET_MS = 500L
        const val FULL_TARGET_MS = 1_000L
    }
}

internal data class PreloadDiagnostics(
    val reason: String = PreloadReason.NOT_REGISTERED,
    val requestedTargetMs: Long = 0L,
    val completedTargetMs: Long = 0L,
    val queueMs: Long = 0L,
    val loadMs: Long = 0L,
    val startupLoadMs: Long = 0L,
    val startPositionMs: Long = 0L,
)
