package io.github.silascui.sloth

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** 列表播放的唯一协调入口；Holder 仅注册渲染宿主，不持有播放器。 */
class FeedPlaybackController(
    private val initiallyMuted: Boolean = true,
    private val loop: Boolean = true,
    private val warmupEnabled: Boolean = true,
) : PlaybackController {
    private data class Host(val position: Int, val item: VideoMedia, val view: FeedRenderTarget)
    private val hosts = LinkedHashMap<Any, Host>()
    private val sessions = LinkedHashMap<String, PlaybackSession>()
    private var items: List<VideoMedia> = emptyList()
    private val policy get() = VideoPlaybackRuntime.configuration.playbackPolicy
    private val handler = Handler(Looper.getMainLooper())
    private val prepareSelected = Runnable { reconcilePlayback(prepareWhileScrolling = true) }
    private var handle: PlaybackHandle? = null
    private var visible = false
    private var scrolling = false
    private var scrollSettling = false
    private var interrupted = false
    private var closed = false
    private var policyBlocked = false
    private var pendingManualPlay = false
    private var manualPlaybackGranted = false
    private var pendingRetry = false
    private val states = PlaybackStateStore(PlaybackState(muted = initiallyMuted))
    private val publishProgress = Runnable { publishState() }
    override val state: PlaybackState get() = createState()

    override fun observeState(observer: (PlaybackState) -> Unit): AutoCloseable {
        publishState()
        val subscription = states.observe(observer)
        scheduleProgress()
        return AutoCloseable {
            subscription.close()
            scheduleProgress()
        }
    }

    private var selectedAtMs = SystemClock.elapsedRealtime()
    private var startupAttempt: PlaybackAttempt? = null
    private var requestVersion = 0L
    private var speed = 1F
    private var muted = initiallyMuted
    private var direction = 1
    private var dragStartIndex: Int? = null
    private var dragWarmupIndex: Int? = null
    var selectedIndex = 0
        private set
    var onEnded: ((Int) -> Unit)? = null

    val currentHandle: PlaybackHandle? get() = handle?.takeIf { it.isActive && it.session.mediaId == sessionAt(selectedIndex)?.mediaId }
    val currentPosition: Long get() = currentHandle?.positionMs ?: sessionAt(selectedIndex)?.positionMs ?: 0L
    val duration: Long get() = currentHandle?.durationMs ?: 0L

    fun submitFeed(data: List<VideoMedia>) {
        if (closed) return
        val previousSelection = items.getOrNull(selectedIndex)?.let(VideoMedia::from)?.sourceKey
        requestVersion++
        dragStartIndex = null
        dragWarmupIndex = null
        items = data.toList()
        pendingManualPlay = false
        pendingRetry = false
        policyBlocked = false
        if (items.getOrNull(selectedIndex)?.sourceKey != previousSelection) manualPlaybackGranted = false
        selectedIndex = selectedIndex.coerceIn(0, items.lastIndex.coerceAtLeast(0))
        if (items.getOrNull(selectedIndex)?.let(VideoMedia::from)?.sourceKey != previousSelection) {
            selectedAtMs = SystemClock.elapsedRealtime()
            if (visible) beginAttempt()
        }
        val valid = items.mapNotNull { VideoMedia.from(it)?.id }.toSet()
        sessions.keys.retainAll(valid)
        if (items.isEmpty()) {
            VideoPlaybackRuntime.finishAttempt(startupAttempt, PlaybackOutcome.FEED_EMPTY)
            startupAttempt = null
            closeHandle()
        }
        else if (handle?.session?.mediaId != VideoMedia.from(items[selectedIndex])?.id) {
            handle?.let { VideoPlaybackRuntime.pause(it, user = false, preservePreload = true, showControls = false) }
        }
        reconcilePlayback()
    }

    fun select(position: Int) {
        if (closed || position !in items.indices) return
        if (selectedIndex != position) {
            pendingManualPlay = false
            pendingRetry = false
            manualPlaybackGranted = false
            policyBlocked = false
            direction = if (position > selectedIndex) 1 else -1
            selectedIndex = position
            selectedAtMs = SystemClock.elapsedRealtime()
            if (visible) beginAttempt()
            requestVersion++
            interrupted = false
            handle?.let { VideoPlaybackRuntime.pause(it, user = false, preservePreload = true, showControls = false) }
        }
        requestSelectedPlayback()
    }

    fun setVisible(value: Boolean, position: Int = selectedIndex) {
        if (closed) return
        val wasVisible = visible
        val previousIndex = selectedIndex
        if (visible != value) {
            if (value) selectedAtMs = SystemClock.elapsedRealtime()
            requestVersion++
            interrupted = false
        }
        visible = value
        if (position in items.indices) {
            if (selectedIndex != position) selectedAtMs = SystemClock.elapsedRealtime()
            selectedIndex = position
        }
        if (!value) {
            manualPlaybackGranted = false
            pendingManualPlay = false
            pendingRetry = false
            VideoPlaybackRuntime.finishAttempt(startupAttempt, PlaybackOutcome.HIDDEN)
            startupAttempt = null
            dragStartIndex = null
            dragWarmupIndex = null
            VideoPlaybackRuntime.cancelWaiting(this)
            closeHandle()
            publishState()
        } else {
            if (!wasVisible || previousIndex != selectedIndex || startupAttempt == null) beginAttempt()
            reconcilePlayback()
        }
    }

    private fun beginAttempt() {
        VideoPlaybackRuntime.finishAttempt(startupAttempt, PlaybackOutcome.SUPERSEDED)
        startupAttempt = if (selectedIndex in items.indices) VideoPlaybackRuntime.startAttempt(selectedIndex, selectedAtMs) else null
    }

    fun setScrolling(value: Boolean, settling: Boolean = false) {
        if (value && !settling && (!scrolling || scrollSettling)) {
            dragStartIndex = selectedIndex
            dragWarmupIndex = null
        } else if (!value) {
            dragStartIndex = null
            dragWarmupIndex = null
        }
        scrolling = value
        scrollSettling = value && settling
        hosts.values.forEach { it.view.setFeedScrolling(value) }
        // 开始拖动不打断当前播放；select 确认新目标时再暂停旧内容并交接。
        publishState()
        if (!value) {
            handler.removeCallbacks(prepareSelected)
            reconcilePlayback()
        }
    }

    /** 拖动只提示备用引擎目标，不改变选中项、正式播放或对称数据预加载窗口。 */
    fun previewPageScroll(position: Int, positionOffset: Float) {
        val origin = dragStartIndex ?: return
        if (closed || !visible || !scrolling || scrollSettling || selectedIndex != origin ||
            !positionOffset.isFinite() || positionOffset !in 0F..1F) return
        // 越过页面 2% 才接受方向提示，避免手指在页边轻微抖动就重建备用内容。
        val candidate = when {
            position == origin && positionOffset >= DRAG_DIRECTION_THRESHOLD -> origin + 1
            position == origin - 1 && positionOffset in 0F..REVERSE_DRAG_DIRECTION_THRESHOLD -> origin - 1
            else -> return
        }
        if (candidate !in items.indices || candidate == dragWarmupIndex) return
        dragWarmupIndex = candidate
        currentHandle?.let(::updateAdjacentWarmup)
    }

    fun registerHost(token: Any, position: Int, item: VideoMedia, view: FeedRenderTarget) {
        if (closed || position < 0) return
        hosts[token] = Host(position, item, view)
        view.setFeedScrolling(scrolling)
        view.onPlaybackToggle = { if (hosts[token]?.view === view) toggle(position) }
        view.onPlaybackSeek = { value -> if (hosts[token]?.view === view && selectedIndex == position) seekTo(value) }
        view.onPlaybackHostAttached = { if (hosts[token]?.view === view) requestSelectedPlayback() }
        requestSelectedPlayback()
    }

    private fun requestSelectedPlayback() {
        handler.removeCallbacks(prepareSelected)
        // 拖动时合并目标；Pager 已确认吸附目标且输出就绪时立即准备，避免再等一次 UI 消息。
        // 新目标在两种路径下均保持暂停，IDLE 才播放；尚未切换的当前内容继续播放。
        if (!scrolling) reconcilePlayback()
        else if (scrollSettling && selectedHost()?.view?.outputReady() == true) {
            reconcilePlayback(prepareWhileScrolling = true)
        } else handler.post(prepareSelected)
    }

    fun unregisterHost(token: Any) {
        val host = hosts.remove(token) ?: return
        VideoPlaybackRuntime.cancelWarmup(host.view)
        host.view.onPlaybackToggle = null
        host.view.onPlaybackSeek = null
        host.view.onPlaybackHostAttached = null
        val old = handle
        if (old?.target === host.view) {
            old.unbind(host.view)
            VideoPlaybackRuntime.pause(old, user = false, preservePreload = true, showControls = false)
            handler.post {
                if (handle === old && selectedHost() == null) { closeHandle(); publishState() }
            }
        }
    }

    private fun selectedHost() = hosts.values.lastOrNull {
        it.position == selectedIndex && items.getOrNull(selectedIndex) === it.item
    }

    private fun sessionAt(position: Int): PlaybackSession? {
        val item = items.getOrNull(position) ?: return null
        val media = VideoMedia.from(item) ?: return null
        return sessions.getOrPut(media.id) {
            PlaybackSession(media.id, item.initialPositionMs).apply { muted = this@FeedPlaybackController.muted; speed = this@FeedPlaybackController.speed }
        }
    }

    private fun reconcilePlayback(manual: Boolean = false, prepareWhileScrolling: Boolean = false, retry: Boolean = false) {
        val explicit = manual || pendingManualPlay
        try {
            if (closed || !visible || (scrolling && !prepareWhileScrolling) || (interrupted && !explicit)) return
            val host = selectedHost() ?: return
            val item = host.item
            if (!item.playable) {
                startupAttempt?.waiting(PlaybackWaitReason.BLOCKED_MEDIA)
                closeHandle()
                return
            }
            val media = VideoMedia.from(item) ?: run { startupAttempt?.waiting(PlaybackWaitReason.INVALID_MEDIA); return }
            val session = sessionAt(selectedIndex) ?: return
            if (explicit) { session.userPaused = false; interrupted = false }
            val version = requestVersion
            val manualPolicy = explicit || manualPlaybackGranted
            val allowed = (manualPolicy || VideoPlaybackRuntime.configuration.autoplayAllowed()) && policy.permits(host.view.renderingContext(), item, manualPolicy,
                isCurrent = { !closed && visible && requestVersion == version && selectedHost() === host },
                onAllowed = {
                    handler.post {
                        if (!closed && visible && requestVersion == version && selectedHost() === host) {
                            reconcilePlayback(manual = explicit, retry = retry || pendingRetry)
                        }
                    }
                })
            policyBlocked = !allowed
            if (allowed && explicit) manualPlaybackGranted = true
            else if (!allowed) manualPlaybackGranted = false
            var active = handle?.takeIf { it.isActive && it.session === session }
            if (active == null) {
                val previous = handle
                handle = null
                // acquire 先撤销旧句柄，再复用同一资源；旧 close 不会释放新归属。
                val next = VideoPlaybackRuntime.acquire(session, automatic = !explicit,
                    onPlayRequest = { play() },
                    onEnded = { publishState(); if (selectedIndex == host.position && selectedHost()?.item === item) onEnded?.invoke(host.position) },
                    onRevoked = { if (handle != null) { interrupted = true; handle = null; publishState() } })
                previous?.close()
                if (next == null) {
                    VideoPlaybackRuntime.awaitFloatingClose(this) { reconcilePlayback() }
                    host.view.setShowPlayerButton(true)
                    return
                }
                handle = next
                next.onStateChanged = { if (handle === next) publishState() }
                active = next
                interrupted = false
            }
            active.bind(host.view)
            if (explicit) VideoPlaybackRuntime.allowExplicitPlayback(active)
            if (!allowed || session.userPaused) {
                startupAttempt?.waiting(if (session.userPaused) PlaybackWaitReason.USER_PAUSED else PlaybackWaitReason.BLOCKED_POLICY)
                VideoPlaybackRuntime.pause(active, user = false)
                return
            }
            VideoPlaybackRuntime.prepare(active, media, loop = loop, PlaybackReporter(item, selectedIndex), selectedIndex,
                if (explicit) SystemClock.elapsedRealtime() else selectedAtMs, startupAttempt, retry = retry || pendingRetry)
            val window = (selectedIndex - PRELOAD_RADIUS..selectedIndex + PRELOAD_RADIUS).mapNotNull { index ->
                val candidate = items.getOrNull(index) ?: return@mapNotNull null
                if (!candidate.playable) return@mapNotNull null
                VideoMedia.from(candidate)?.let { index to it }
            }
            val preloadPositions = window.associate { (index, value) ->
                value.sourceKey to (sessionAt(index)?.positionMs ?: 0L)
            }
            VideoPlaybackRuntime.updatePreload(active, window, selectedIndex, VideoPlaybackRuntime.configuration.autoplayAllowed(), preloadPositions)
            updateAdjacentWarmup(active)
            if (!scrolling) {
                VideoPlaybackRuntime.play(active)
                if (pendingManualPlay) requestVersion++
                pendingManualPlay = false
                pendingRetry = false
            }
        } finally { publishState() }
    }


    private fun updateAdjacentWarmup(active: PlaybackHandle) {
        // 拖动提示只调整备用角色；取消拖动后恢复原方向，数据预加载始终前后对称。
        val nextIndex = dragWarmupIndex?.takeIf { scrolling && selectedIndex == dragStartIndex }
            ?: (selectedIndex + direction).takeIf { it in items.indices } ?: (selectedIndex - direction)
        val next = hosts.values.lastOrNull { it.position == nextIndex && items.getOrNull(nextIndex) === it.item }
        val request = next?.takeIf {
            warmupEnabled && VideoPlaybackRuntime.configuration.autoplayAllowed() && !active.session.userPaused &&
                it.item.playable && sessionAt(nextIndex)?.userPaused != true
        }?.let { host ->
            VideoMedia.from(host.item)?.takeUnless { it.live }?.let {
                VideoEnginePool.Request(it, host.view, sessionAt(nextIndex)?.positionMs ?: 0L)
            }
        }
        VideoPlaybackRuntime.updateWarmup(active, request)
    }

    override fun play() {
        if (closed) return
        pendingManualPlay = true
        requestVersion++
        sessionAt(selectedIndex)?.userPaused = false
        interrupted = false
        reconcilePlayback(manual = true, retry = currentHandle?.let(VideoPlaybackRuntime::hasError) == true)
    }

    override fun pause() {
        if (closed) return
        pendingManualPlay = false
        pendingRetry = false
        manualPlaybackGranted = false
        requestVersion++ // Invalidate deferred consent callbacks, including manual play requests.
        handler.removeCallbacks(prepareSelected)
        sessionAt(selectedIndex)?.userPaused = true
        currentHandle?.pause()
        publishState()
    }

    override fun retry() {
        if (closed) return
        pendingManualPlay = true
        pendingRetry = true
        requestVersion++
        sessionAt(selectedIndex)?.userPaused = false
        interrupted = false
        reconcilePlayback(manual = true, retry = true)
    }

    override fun togglePlayback() { if (state.playWhenReady) pause() else play() }

    fun toggle(position: Int) {
        if (position == selectedIndex) togglePlayback()
    }

    override fun setSpeed(value: Float) {
        if (closed) return
        if (!value.isFinite() || value <= 0F) return
        speed = value
        sessionAt(selectedIndex)?.speed = value
        currentHandle?.setSpeed(value)
        publishState()
    }

    override fun setMuted(value: Boolean) {
        if (closed) return
        muted = value
        sessions.values.forEach { it.muted = value }
        currentHandle?.setMuted(value)
        publishState()
    }

    override fun seekTo(positionMs: Long) = seekTo(positionMs, null)

    fun seekTo(positionMs: Long, url: String? = null) {
        if (closed) return
        if (url != null && VideoMedia.playUrl(items.getOrNull(selectedIndex)) != url) return
        sessionAt(selectedIndex)?.savePosition(positionMs)
        currentHandle?.seekTo(positionMs)
        publishState()
    }

    override fun replay() {
        seekTo(0L)
        play()
    }

    fun reset() {
        seekTo(0L)
        pause()
    }

    fun onNetworkChanged() {
        requestVersion++
        if (!VideoPlaybackRuntime.configuration.autoplayAllowed()) closeHandle()
        else currentHandle?.let { VideoPlaybackRuntime.pause(it, user = false, preservePreload = true, showControls = false) }
        reconcilePlayback()
    }

    private fun closeHandle() {
        val previous = handle
        handle = null
        previous?.onStateChanged = null
        previous?.onRevoked = null
        previous?.close()
    }

    fun saveState(): FeedPlaybackState {
        currentHandle?.let { it.session.savePosition(it.positionMs) }
        return FeedPlaybackState(selectedIndex, muted, speed, sessions.values.map {
            PlaybackSessionSnapshot(it.mediaId, it.positionMs, it.userPaused, it.speed, it.muted)
        })
    }

    fun restoreState(state: FeedPlaybackState) {
        check(!visible && handle == null) { "Restore before attaching playback" }
        selectedIndex = state.selectedIndex.coerceAtLeast(0)
        muted = state.muted
        speed = state.speed
        sessions.clear()
        state.sessions.forEach { snapshot ->
            sessions[snapshot.mediaId] = PlaybackSession(snapshot.mediaId, snapshot.positionMs).apply {
                userPaused = snapshot.userPaused
                speed = snapshot.speed
                muted = snapshot.muted
            }
        }
        publishState()
    }

    override fun close() {
        if (closed) return
        VideoPlaybackRuntime.finishAttempt(startupAttempt, PlaybackOutcome.CLOSED)
        startupAttempt = null
        closed = true
        requestVersion++
        VideoPlaybackRuntime.cancelWaiting(this)
        closeHandle()
        hosts.values.forEach {
            it.view.onPlaybackToggle = null
            it.view.onPlaybackSeek = null
            it.view.onPlaybackHostAttached = null
        }
        hosts.clear()
        sessions.clear()
        items = emptyList()
        onEnded = null
        handler.removeCallbacksAndMessages(null)
        states.close(createState())
    }
    private fun createState(): PlaybackState {
        val item = items.getOrNull(selectedIndex)
        val session = item?.let { sessionAt(selectedIndex) }
        val engine = item?.let { value -> currentHandle?.let { VideoPlaybackRuntime.state(it, value.sourceKey) } }
        val reason = when {
            closed || item == null -> PlaybackBlockReason.NONE
            !visible -> PlaybackBlockReason.HIDDEN
            !item.playable -> PlaybackBlockReason.MEDIA_UNAVAILABLE
            interrupted -> PlaybackBlockReason.INTERRUPTED
            session?.userPaused == true -> PlaybackBlockReason.NONE
            selectedHost() == null -> PlaybackBlockReason.HOST_UNAVAILABLE
            policyBlocked -> PlaybackBlockReason.POLICY
            scrolling && engine?.playWhenReady != true -> PlaybackBlockReason.SCROLLING
            else -> PlaybackBlockReason.NONE
        }
        val status = when {
            closed -> PlaybackStatus.CLOSED
            item == null -> PlaybackStatus.IDLE
            !visible || interrupted -> PlaybackStatus.PAUSED
            engine?.error != null -> PlaybackStatus.ERROR
            session?.userPaused == true -> PlaybackStatus.PAUSED
            reason != PlaybackBlockReason.NONE -> PlaybackStatus.WAITING
            else -> engine?.status ?: PlaybackStatus.WAITING
        }
        return PlaybackState(item?.id, selectedIndex, status, reason,
            engine?.positionMs ?: session?.positionMs ?: 0L, engine?.durationMs ?: 0L,
            engine?.bufferedPositionMs ?: 0L, session?.speed ?: speed, session?.muted ?: muted,
            session?.userPaused ?: false, visible && !closed,
            (engine?.playWhenReady == true || pendingManualPlay) && visible && !closed && !interrupted && session?.userPaused != true,
            item?.playable == true && visible && !closed && selectedHost() != null,
            engine?.canSeek == true && item?.live == false && visible && !closed, engine?.error)
    }

    private fun publishState() {
        if (closed) return
        states.emit(createState())
        scheduleProgress()
    }

    private fun scheduleProgress() {
        handler.removeCallbacks(publishProgress)
        val snapshot = states.value
        if (!closed && visible && states.hasObservers && (snapshot.isPlaying || snapshot.status == PlaybackStatus.BUFFERING)) {
            handler.postDelayed(publishProgress, PROGRESS_UPDATE_INTERVAL_MS)
        }
    }

    private companion object {
        private const val DRAG_DIRECTION_THRESHOLD = 0.02F
        private const val REVERSE_DRAG_DIRECTION_THRESHOLD = 0.98F
        private const val PRELOAD_RADIUS = 2
        private const val PROGRESS_UPDATE_INTERVAL_MS = 500L
    }

}
