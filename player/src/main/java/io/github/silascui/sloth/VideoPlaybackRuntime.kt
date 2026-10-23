package io.github.silascui.sloth

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener

/** 唯一资源所有者：至多一个当前引擎和一个静音预热引擎，页面不构建或释放播放器。 */
@SuppressLint("UnsafeOptInUsageError")
object VideoPlaybackRuntime {
    private var application: android.app.Application? = null
    private var installedConfiguration = PlayerConfiguration()
    val isInitialized: Boolean get() = application != null
    internal val applicationContext: android.content.Context get() = checkNotNull(application) { "Initialize VideoPlaybackRuntime after user consent before playing" }
    internal val configuration: PlayerConfiguration get() = installedConfiguration

    fun initialize(context: android.content.Context, configuration: PlayerConfiguration = PlayerConfiguration()) {
        checkThread()
        check(!isInitialized) { "Runtime is already initialized; close controllers and shutdown before reconfiguration" }
        this.application = context.applicationContext as? android.app.Application
            ?: throw IllegalArgumentException("An Android Application context is required")
        installedConfiguration = configuration
    }

    fun shutdown() {
        checkThread()
        check(current == null && waiting.isEmpty()) { "Close controllers and floating handles before shutdown" }
        resources?.release()
        resources = null
        abandonFocus()
        VideoCacheStore.release()
        application = null
        installedConfiguration = PlayerConfiguration()
    }

    private const val FOCUS_NAME = "video_runtime"
    private val ownership = PlaybackOwnership()
    private val handler = Handler(Looper.getMainLooper())
    private val startupRunId = SystemClock.elapsedRealtime()
    private var current: PlaybackHandle? = null
    private var resources: VideoPreloadCoordinator? = null
    private var mediaId: String? = null
    private var reporter: PlaybackReporter? = null
    private var events: AnalyticsListener? = null
    private var startupTrace: PlaybackStartupTrace? = null
    private var currentFrameRendered = false
    private var warmupPlan: Pair<PlaybackHandle, VideoEnginePool.Request>? = null
    internal var lastStartupSample: PlaybackStartupTrace.Sample? = null
        private set
    private var focusInterrupted = false
    private var focusBlocked = false
    private val waiting = LinkedHashMap<Any, () -> Unit>()

    internal fun startAttempt(index: Int, selectedAtMs: Long): PlaybackAttempt = PlaybackAttempt(index, selectedAtMs).also {
        PlayerLog.d("VideoSwitch runId=$startupRunId processId=${android.os.Process.myPid()} attemptId=${it.id} index=$index phase=selected")
    }

    internal fun finishAttempt(attempt: PlaybackAttempt?, outcome: String) {
        attempt?.finish(outcome, SystemClock.elapsedRealtime())?.let {
            PlayerLog.d("VideoSwitch runId=$startupRunId processId=${android.os.Process.myPid()} attemptId=${it.id} index=${it.index} phase=finished outcome=${it.outcome} elapsedMs=${it.elapsedMs} wasPrepared=${it.wasPrepared} waitReason=${it.waitReason}")
        }
    }

    internal fun acquire(
        session: PlaybackSession,
        automatic: Boolean,
        onPlayRequest: () -> Unit,
        onEnded: () -> Unit,
        onRevoked: () -> Unit,
    ): PlaybackHandle? {
        checkThread()
        applicationContext // Fail before acquiring ownership if not initialized.
        val acquireAtMs = SystemClock.elapsedRealtime()
        val handle = PlaybackHandle(session, onPlayRequest, onEnded, onRevoked)
        val previous = current
        if (!ownership.acquire(handle.owner, automatic)) return null
        handle.startupStages.targetUnbindMs = previous?.startupStages?.targetUnbindMs ?: 0L
        current = handle
        focusBlocked = false
        configuration.beforeAcquire()
        handle.startupStages.acquireMs = SystemClock.elapsedRealtime() - acquireAtMs
        return handle
    }

    internal fun awaitFloatingClose(key: Any, callback: () -> Unit) {
        if (current?.floating == true) waiting[key] = callback
    }

    internal fun cancelWaiting(key: Any) { waiting.remove(key) }

    internal fun prepare(handle: PlaybackHandle, media: VideoMedia, loop: Boolean, report: PlaybackReporter, selectedIndex: Int = 0,
        selectedAtMs: Long = SystemClock.elapsedRealtime(), attempt: PlaybackAttempt? = null, retry: Boolean = false) {
        checkThread()
        if (!isActive(handle)) return
        if (mediaId == media.sourceKey) {
            if (retry) resources?.player?.prepare()
            if (attempt != null && attempt !== handle.startupAttempt) {
                finishAttempt(attempt, if (currentFrameRendered) PlaybackOutcome.ALREADY_RENDERED else PlaybackOutcome.SAME_MEDIA_INFLIGHT)
            }
            return
        }
        val measurement = attempt ?: startAttempt(selectedIndex, selectedAtMs)
        handle.startupAttempt = measurement
        if (measurement.prepared(SystemClock.elapsedRealtime())) {
            PlayerLog.d("VideoSwitch runId=$startupRunId processId=${android.os.Process.myPid()} attemptId=${measurement.id} index=$selectedIndex phase=prepared")
        }
        val group = resources ?: VideoPreloadCoordinator(applicationContext).also {
            resources = it
            it.onPreloadReady = { tryWarmup() }
        }
        val previousPlayer = group.player
        if (mediaId != null) {
            handle.session.savePosition(positionOfCurrent(), previousPlayer.playbackState == Player.STATE_ENDED)
            reporter?.detach(previousPlayer)
            events?.let(previousPlayer::removeAnalyticsListener)
            group.engines.clearPromotedListener()
            previousPlayer.pause()
            previousPlayer.clearMediaItems()
        }
        val promotion = group.engines.promote(media, handle.target, handle.session.positionMs) { renderTimeMs ->
            if (isActive(handle) && mediaId == media.sourceKey) deliverFirstFrame(handle, renderTimeMs)
        }
        val player = group.player
        mediaId = media.sourceKey
        currentFrameRendered = false
        handle.target?.onPlaybackPreparing()
        player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.volume = if (handle.session.muted) 0F else 1F
        player.setPlaybackSpeed(handle.session.speed)
        installEvents(handle)
        reporter = report
        report.attach(player, selectedAtMs)
        val bindAtMs = SystemClock.elapsedRealtime()
        handle.target?.bindPlayback(handle, group.engines.presentation(player))
        handle.startupStages.targetBindMs += SystemClock.elapsedRealtime() - bindAtMs
        val prepareAtMs = SystemClock.elapsedRealtime()
        val source = if (promotion == null) group.sourceFor(media, selectedIndex, handle.session.positionMs) else null
        handle.startupStages.sourceLookupMs = SystemClock.elapsedRealtime() - prepareAtMs
        val readMeter = group.readMeterFor(media)
        PlayerLog.d("VideoReadSelection runId=$startupRunId processId=${android.os.Process.myPid()} attemptId=${measurement.id} slotId=${readMeter?.slotId} atMs=${SystemClock.elapsedRealtime()}")
        startupTrace = PlaybackStartupTrace(selectedIndex, selectedAtMs, prepareAtMs,
            promotion != null || group.lastPlaybackPreloadHit, handle.startupStages,
            promotion?.firstFrameRendered == true, promotion != null,
            if (promotion != null) group.diagnosticsFor(media) else group.lastPlaybackDiagnostics,
            group.engines.lastPromotionReason, measurement, handle.session.positionMs, promotion?.timing,
            readMeter?.let { meter -> { meter.snapshot(SystemClock.elapsedRealtime()) } })
        if (promotion != null) {
            if (promotion.firstFrameRendered) {
                // 此帧此前已由备用引擎渲染到同一 Surface；单独标记，不能冒充选中后的解码回调。
                deliverFirstFrame(handle, SystemClock.elapsedRealtime())
            }
            return
        }
        val sourceSetAtMs = SystemClock.elapsedRealtime()
        // 一次设置媒体源与起播位置，避免初始准备期间再排入一个 seek/reset。
        player.setMediaSource(checkNotNull(source), if (media.live) androidx.media3.common.C.TIME_UNSET else handle.session.positionMs)
        handle.startupStages.sourceSetMs = SystemClock.elapsedRealtime() - sourceSetAtMs
        val prepareDispatchAtMs = SystemClock.elapsedRealtime()
        player.prepare()
        handle.startupStages.prepareDispatchMs = SystemClock.elapsedRealtime() - prepareDispatchAtMs
    }

    private fun installEvents(handle: PlaybackHandle) {
        val player = resources?.player ?: return
        events?.let(player::removeAnalyticsListener)
        val expectedMedia = mediaId ?: return
        events = object : AnalyticsListener {
            override fun onEvents(player: Player, events: AnalyticsListener.Events) {
                if (isActive(handle) && mediaId == expectedMedia) handle.onStateChanged?.invoke()
            }

            private fun matchesMedia(eventTime: AnalyticsListener.EventTime): Boolean =
                isActive(handle) && eventTime.windowIndex in 0 until eventTime.timeline.windowCount &&
                    eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem.mediaId == expectedMedia

            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                if (matchesMedia(eventTime)) {
                    deliverFirstFrame(handle, renderTimeMs)
                }
            }

            override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: androidx.media3.common.PlaybackException) {
                if (!isActive(handle) || mediaId != expectedMedia || resources?.player !== player) return
                configuration.events.onError(handle.session.mediaId, error.errorCode)
                if (resources?.engines?.engineCount == 2 &&
                    (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED)) {
                    PlayerLog.d("VideoWarmup fallback reason=active_decoder_error code=${error.errorCode}")
                    resources?.engines?.disable()
                    warmupPlan = null
                    handler.post { if (isActive(handle) && mediaId == expectedMedia) player.prepare() }
                } else finishAttempt(handle.startupAttempt, PlaybackOutcome.ERROR)
            }

            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
                initializedTimestampMs: Long, initializationDurationMs: Long) {
                if (matchesMedia(eventTime)) startupTrace?.onDecoderInitialized(initializationDurationMs)
            }

            override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
                if (!matchesMedia(eventTime)) return
                if (state == Player.STATE_READY) startupTrace?.onReady(SystemClock.elapsedRealtime())?.let {
                    PlayerLog.d("VideoStartupReady index=${startupTrace?.selectedIndex} prepareToReadyMs=$it")
                }
                if (state == Player.STATE_ENDED) {
                    handle.session.savePosition(0L, ended = true)
                    handler.post { if (isActive(handle)) handle.onEnded?.invoke() }
                }
            }
        }.also(player::addAnalyticsListener)
    }

    private fun deliverFirstFrame(handle: PlaybackHandle, atMs: Long) {
        currentFrameRendered = true
        startupTrace?.onFirstFrame(atMs, SystemClock.elapsedRealtime())?.let {
            lastStartupSample = it
            it.read?.snapshot?.let { snapshot ->
                snapshot.requests.forEach { request ->
                    PlayerLog.d("VideoReadRequest runId=$startupRunId processId=${android.os.Process.myPid()} attemptId=${it.attemptId} slotId=${snapshot.slotId} sourceId=${request.sourceId} requestId=${request.requestId} kind=${request.kind} startAtMs=${request.startedAtMs} openedAtMs=${request.openedAtMs} firstByteAtMs=${request.firstByteAtMs} endAtMs=${request.endedAtMs} bytes=${request.bytes} readMs=${request.readMs} failed=${request.failed}")
                }
            }
            configuration.events.onFirstFrame(PlaybackMetrics(it.index, it.selectionToFirstFrameMs,
                it.restorePositionMs, it.warmFrameReused, it.warmEngineReused, it.preloadReason))
            PlayerLog.d("VideoStartup runId=$startupRunId processId=${android.os.Process.myPid()} attemptId=${it.attemptId} index=${it.index} hit=${it.preloadHit} warmFrame=${it.warmFrameReused} warmEngine=${it.warmEngineReused} preloadReason=${it.preloadReason} preloadRequestedMs=${it.preloadRequestedMs} preloadCompletedMs=${it.preloadCompletedMs} preloadQueueMs=${it.preloadQueueMs} preloadLoadMs=${it.preloadLoadMs} preloadStartupMs=${it.preloadStartupMs} preloadStartPositionMs=${it.preloadStartPositionMs} warmupReason=${it.warmupReason} selectionToPrepareMs=${it.selectionToPrepareMs} prepareToReadyMs=${it.prepareToReadyMs} firstFrameMs=${it.selectionToFirstFrameMs} decoderInits=${it.decoderInitializations} decoderInitMs=${it.decoderInitializationMs} acquireMs=${it.acquireMs} bindMs=${it.targetBindMs} unbindMs=${it.targetUnbindMs} sourceLookupMs=${it.sourceLookupMs} sourceSetMs=${it.sourceSetMs} prepareDispatchMs=${it.prepareDispatchMs} restorePositionMs=${it.restorePositionMs} prepareToFirstFrameMs=${it.prepareToFirstFrameMs} readyToFirstFrameMs=${it.readyToFirstFrameMs} frameCallbackDelayMs=${it.frameCallbackDelayMs} warmupSeekBoosted=${it.warmupSeekBoosted} warmupLeadMs=${it.warmupLeadMs} warmupReplaceMs=${it.warmupReplaceMs} warmupBindMs=${it.warmupBindMs} warmupSourceLookupMs=${it.warmupSourceLookupMs} warmupSourceSetMs=${it.warmupSourceSetMs} warmupPrepareDispatchMs=${it.warmupPrepareDispatchMs} warmupReadyMs=${it.warmupReadyMs} warmupDecoderInits=${it.warmupDecoderInitializations} warmupDecoderInitMs=${it.warmupDecoderInitializationMs} warmupDecoderReadyMs=${it.warmupDecoderReadyMs} warmupFirstFrameMs=${it.warmupFirstFrameMs} warmupInputFormatMs=${it.warmupInputFormatMs} warmupFormatToFirstFrameMs=${it.warmupFormatToFirstFrameMs} warmupDecoderReuseResult=${it.warmupDecoderReuseResult} warmupQueuedInputBuffers=${it.warmupQueuedInputBuffers} warmupSkippedOutputBuffers=${it.warmupSkippedOutputBuffers} warmupRenderedOutputBuffers=${it.warmupRenderedOutputBuffers} warmupVideoWidth=${it.warmupVideoWidth} warmupVideoHeight=${it.warmupVideoHeight}${readFields(it.read)}")
            finishAttempt(handle.startupAttempt, PlaybackOutcome.RENDERED)
        }
        handle.target?.onPlaybackFirstFrame()
        handler.post { if (isActive(handle)) tryWarmup() }
    }

    private fun readFields(frame: VideoReadStatistics.Frame?): String {
        if (frame == null) return " ioSlotId=null"
        val s = frame.snapshot
        return " ioSlotId=${s.slotId} ioCacheBytes=${s.cacheBytes} ioNetworkBytes=${s.networkBytes}" +
            " ioLocalBytes=${s.localBytes} ioUnknownBytes=${s.unknownBytes} ioSources=${s.sourceCount}" +
            " ioOpens=${s.openCount} ioCloses=${s.closeCount} ioActive=${s.activeCount} ioMaxActive=${s.maxActiveCount}" +
            " ioNetworkOpenMs=${s.networkOpenMs} ioNetworkReadMs=${s.networkReadMs}" +
            " ioCanceledLoads=${s.loadCanceledCount} ioLoadErrors=${s.loadErrorCount} ioDroppedRequests=${s.droppedRequests}" +
            " ioCacheBytesAfterPrepare=${frame.cacheBytesAfterPrepare} ioNetworkBytesAfterPrepare=${frame.networkBytesAfterPrepare}" +
            " ioBaselineAtMs=${frame.baselineAtMs} ioSnapshotAtMs=${s.atMs}"
    }

    internal fun updateWarmup(handle: PlaybackHandle, request: VideoEnginePool.Request?) {
        if (!isActive(handle)) return
        warmupPlan = request?.let { handle to it }
        if (request == null) resources?.engines?.cancelWarmup()
        else tryWarmup()
    }

    private fun tryWarmup() {
        val (handle, request) = warmupPlan ?: return
        val group = resources ?: return
        if (!isActive(handle) || handle.floating || handle.session.userPaused || focusBlocked ||
            !currentFrameRendered || request.media.sourceKey == mediaId || !group.isPreloaded(request.media, request.positionMs)) return
        group.engines.warm(request) { group.sourceFor(request.media, positionMs = request.positionMs) }
    }

    internal fun cancelWarmup(target: PlaybackRenderTarget) {
        if (warmupPlan?.second?.target === target) warmupPlan = null
        resources?.engines?.cancelWarmup(target)
    }

    internal fun updatePreload(handle: PlaybackHandle, media: List<Pair<Int, VideoMedia>>, selectedIndex: Int, enabled: Boolean,
        positions: Map<String, Long>) {
        if (isActive(handle)) resources?.updateWindow(media, selectedIndex, enabled && !focusBlocked, positions)
    }

    internal fun play(handle: PlaybackHandle) {
        checkThread()
        if (!isActive(handle)) return
        val player = resources?.player ?: return
        if (handle.session.userPaused || focusBlocked) {
            handle.target?.showPlaybackBlocked(true)
            return
        }
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
        ensureFocus(handle)
        val command = if (handle.session.muted) MediaAudioFocusManager.PLAYER_COMMAND_PLAY_WHEN_READY
        else VideoAudioFocusRegistry.updateAudioFocus(FOCUS_NAME, true, player.playbackState)
        val permitted = command == MediaAudioFocusManager.PLAYER_COMMAND_PLAY_WHEN_READY
        if (permitted) {
            focusInterrupted = false
            if (!handle.session.muted) configuration.events.onAudiblePlaybackStarted()
            player.play()
        } else player.pause()
        handle.target?.showPlaybackBlocked(!permitted)
    }

    private fun ensureFocus(handle: PlaybackHandle) {
        if (VideoAudioFocusRegistry.getAudioFocusManager(FOCUS_NAME) != null) return
        VideoAudioFocusRegistry.createAudioFocusManager(FOCUS_NAME, MediaAudioFocusManager(applicationContext, object : MediaAudioFocusManager.PlayerControl {
            override fun setVolumeMultiplier(volumeMultiplier: Float) {
                if (isActive(handle)) resources?.player?.volume = if (handle.session.muted) 0F else volumeMultiplier
            }

            override fun executePlayerCommand(playerCommand: Int) {
                handler.post {
                    if (!isActive(handle) || handle.session.muted) return@post
                    if (playerCommand == MediaAudioFocusManager.PLAYER_COMMAND_PLAY_WHEN_READY) {
                        if (focusInterrupted && !handle.session.userPaused) {
                            focusBlocked = false
                            play(handle)
                        }
                    } else {
                        if (handle.floating) {
                            waiting.clear()
                            release(handle)
                            return@post
                        }
                        focusBlocked = true
                        focusInterrupted = playerCommand == MediaAudioFocusManager.PLAYER_COMMAND_WAIT_FOR_CALLBACK
                        resources?.player?.pause()
                        resources?.stopPreloading()
                        handle.target?.showPlaybackBlocked(true)
                    }
                }
            }
        }))
    }

    internal fun allowExplicitPlayback(handle: PlaybackHandle) {
        if (isActive(handle)) focusBlocked = false
    }

    internal fun pause(handle: PlaybackHandle, user: Boolean, preservePreload: Boolean = false, showControls: Boolean = true) {
        checkThread()
        if (!isActive(handle)) return
        if (user) handle.session.userPaused = true
        if (user) finishAttempt(handle.startupAttempt, PlaybackOutcome.USER_PAUSED)
        focusInterrupted = false
        // 内容交接的临时暂停先隐藏按钮，再发 pause，避免状态回调把暂停控件闪到屏幕上。
        handle.target?.showPlaybackBlocked(showControls)
        resources?.player?.pause()
        if (!preservePreload) {
            warmupPlan = null
            resources?.stopPreloading()
        }
        abandonFocus()
        handle.onStateChanged?.invoke()
    }

    internal fun bind(handle: PlaybackHandle, target: PlaybackRenderTarget) {
        checkThread()
        if (!isActive(handle)) return
        if (handle.target === target) return
        handle.target?.unbindPlayback(handle)
        handle.target = target
        val bindAtMs = SystemClock.elapsedRealtime()
        if (resources?.engines?.warmedTarget !== target) {
            target.bindPlayback(handle, resources?.engines?.takeIf { mediaId != null }?.presentation())
        }
        handle.startupStages.targetBindMs += SystemClock.elapsedRealtime() - bindAtMs
        installEvents(handle)
        // 同一输出只搬移宿主，不会再产生首次渲染事件；复用已渲染帧揭开新宿主封面。
        if (currentFrameRendered) target.onPlaybackFirstFrame()
    }

    internal fun unbind(handle: PlaybackHandle, target: PlaybackRenderTarget) {
        checkThread()
        if (!isActive(handle) || handle.target !== target) return
        handle.target = null
        target.unbindPlayback(handle)
    }

    internal fun seek(handle: PlaybackHandle, positionMs: Long) {
        checkThread()
        if (!isActive(handle)) return
        handle.session.savePosition(positionMs)
        resources?.player?.seekTo(handle.session.positionMs)
        handle.onStateChanged?.invoke()
    }

    internal fun setSpeed(handle: PlaybackHandle, speed: Float) {
        checkThread()
        if (!isActive(handle) || !speed.isFinite() || speed <= 0F) return
        handle.session.speed = speed
        resources?.player?.setPlaybackSpeed(speed)
        handle.onStateChanged?.invoke()
    }

    internal fun setMuted(handle: PlaybackHandle, muted: Boolean) {
        checkThread()
        if (!isActive(handle)) return
        handle.session.muted = muted
        resources?.player?.volume = if (muted) 0F else 1F
        if (muted) abandonFocus() else if (wantsToPlay(handle)) play(handle)
        handle.onStateChanged?.invoke()
    }

    internal fun position(handle: PlaybackHandle): Long {
        if (!isActive(handle) || mediaId == null) return handle.session.positionMs
        val player = resources?.player ?: return handle.session.positionMs
        return if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition.coerceAtLeast(0L)
    }

    internal fun duration(handle: PlaybackHandle) = if (isActive(handle)) resources?.player?.duration?.coerceAtLeast(0L) ?: 0L else 0L
    internal fun videoSize(handle: PlaybackHandle) =
        if (isActive(handle)) resources?.player?.videoSize ?: androidx.media3.common.VideoSize.UNKNOWN
        else androidx.media3.common.VideoSize.UNKNOWN

    internal fun isCurrentPlayer(player: Player?) = player != null && resources?.player === VideoRenderBinding.engine(player)

    internal fun isPlaying(handle: PlaybackHandle) = isActive(handle) && resources?.player?.isPlaying == true
    internal fun hasError(handle: PlaybackHandle) = isActive(handle) && resources?.player?.playerError != null
    internal fun wantsToPlay(handle: PlaybackHandle) = isActive(handle) && resources?.player?.playWhenReady == true
    internal fun state(handle: PlaybackHandle, sourceKey: String): PlaybackEngineState? {
        if (!isActive(handle) || mediaId != sourceKey) return null
        val player = resources?.player ?: return null
        val error = player.playerError?.let { PlaybackFailure(it.errorCode) }
        val status = when {
            error != null -> PlaybackStatus.ERROR
            player.playbackState == Player.STATE_ENDED -> PlaybackStatus.ENDED
            player.isPlaying -> PlaybackStatus.PLAYING
            player.playbackState == Player.STATE_BUFFERING -> PlaybackStatus.BUFFERING
            player.playbackState == Player.STATE_READY -> if (player.playWhenReady) PlaybackStatus.READY else PlaybackStatus.PAUSED
            else -> PlaybackStatus.WAITING
        }
        return PlaybackEngineState(status, player.currentPosition.coerceAtLeast(0L),
            player.duration.coerceAtLeast(0L), player.bufferedPosition.coerceAtLeast(0L), player.playWhenReady,
            player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM), error)
    }

    internal fun isActive(handle: PlaybackHandle) = !handle.closed && ownership.owns(handle.owner) && current === handle

    /** 浮窗窗口添加成功后交接，不把页面 Controller 或列表一并转移。 */
    fun transferToFloating(handle: PlaybackHandle, target: PlaybackRenderTarget, onRevoked: () -> Unit): PlaybackHandle? {
        checkThread()
        if (!isActive(handle) || resources == null) return null
        val next = PlaybackHandle(handle.session, null, null, onRevoked, floating = true)
        if (!ownership.transfer(handle.owner, next.owner)) return null
        finishAttempt(handle.startupAttempt, PlaybackOutcome.FLOATING)
        handle.session.savePosition(positionOfCurrent())
        handle.target?.unbindPlayback(handle)
        handle.target = null
        val previousCallback = handle.onRevoked
        handle.closed = true
        handle.clearCallbacks()
        abandonFocus()
        current = next
        focusBlocked = false
        next.onPlayRequest = { next.session.userPaused = false; play(next) }
        next.bind(target)
        warmupPlan = null
        resources?.stopPreloading()
        resources?.player?.repeatMode = Player.REPEAT_MODE_OFF
        setSpeed(next, 1F)
        setMuted(next, false)
        previousCallback?.invoke()
        play(next)
        return next
    }

    private fun positionOfCurrent() = resources?.player?.currentPosition?.coerceAtLeast(0L) ?: 0L

    internal fun revoke(handle: PlaybackHandle) {
        stopContent(handle, replacingContent = true)?.invoke()
    }

    private fun stopContent(handle: PlaybackHandle, replacingContent: Boolean = false): (() -> Unit)? {
        if (handle.closed) return null
        finishAttempt(handle.startupAttempt, if (replacingContent) PlaybackOutcome.REVOKED else PlaybackOutcome.RELEASED)
        if (mediaId != null) handle.session.savePosition(positionOfCurrent(), resources?.player?.playbackState == Player.STATE_ENDED)
        val unbindAtMs = SystemClock.elapsedRealtime()
        handle.target?.unbindPlayback(handle)
        handle.startupStages.targetUnbindMs = SystemClock.elapsedRealtime() - unbindAtMs
        handle.target = null
        handle.closed = true
        reporter?.let { report -> resources?.player?.let(report::detach) }
        reporter = null
        resources?.player?.let { player ->
            resources?.engines?.clearPromotedListener()
            events?.let(player::removeAnalyticsListener)
            // 内容接替保留兼容解码器，真正退出仍完整释放资源。
            if (replacingContent) player.pause() else player.stop()
            player.clearMediaItems()
            resources?.engines?.detachRender(player)
            player.volume = 0F
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.setPlaybackSpeed(1F)
        }
        events = null
        mediaId = null
        startupTrace = null
        currentFrameRendered = false
        warmupPlan = null
        abandonFocus()
        if (current === handle) current = null
        val callback = handle.onRevoked
        handle.clearCallbacks()
        return callback
    }

    internal fun release(handle: PlaybackHandle) {
        checkThread()
        if (!isActive(handle)) return
        ownership.release(handle.owner)
        val callback = stopContent(handle)
        val previousResources = resources
        resources = null
        previousResources?.release()
        callback?.invoke()
        if (current == null) {
            val callbacks = waiting.values.toList()
            waiting.clear()
            callbacks.forEach { it() }
        }
    }

    fun interruptForAudio() {
        checkThread()
        waiting.clear()
        current?.takeUnless { it.session.muted }?.close()
    }

    fun interruptForOtherVideo() {
        checkThread()
        waiting.clear()
        current?.close()
    }

    private fun abandonFocus() {
        VideoAudioFocusRegistry.removeAudioFocus(FOCUS_NAME)
        configuration.events.onAudiblePlaybackStopped()
    }

    private fun checkThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Video playback must run on the main thread" }
    }
}
