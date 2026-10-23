package io.github.silascui.sloth

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.PreloadManagerListener
import androidx.media3.exoplayer.source.preload.PreloadException
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import kotlin.math.abs

/** 一个运行时资源组，共享 Builder、缓存和预加载窗口；引擎数量由池统一限制。 */
@SuppressLint("UnsafeOptInUsageError")
internal class VideoPreloadCoordinator(context: Context,
    private val startupTargetMs: Long = PreloadProgress.STARTUP_TARGET_MS,
    mediaSourceFactory: MediaSource.Factory? = null) {
    init { require(startupTargetMs in 1..PreloadProgress.FULL_TARGET_MS) }
    private var selectedIndex = -1
    private var enabled = false
    var lastPlaybackPreloadHit = false
        private set
    private val items = LinkedHashMap<String, MediaItem>()
    private val rankings = HashMap<String, Int>()
    private val startPositions = HashMap<String, Long>()
    private val ready = HashSet<String>()
    private val progress = HashMap<String, PreloadProgress>()
    private val handler = Handler(Looper.getMainLooper())
    private val reads = VideoReadRegistry()
    private var windowMedia = emptyList<Pair<Int, VideoMedia>>()
    private var windowPositions = emptyMap<String, Long>()
    private val rebuildCancelledPreloads = Runnable {
        if (enabled) updateWindow(windowMedia, selectedIndex, true, windowPositions)
    }
    private val extendPreload = Runnable { if (enabled && adjacentStartupReady()) manager.invalidate() }
    var lastPlaybackDiagnostics = PreloadDiagnostics()
        private set
    private val liveFactory = DefaultMediaSourceFactory(VideoPlaybackRuntime.configuration.upstream(context))
    private val cache = VideoCacheStore.obtain(context, VideoPlaybackRuntime.configuration)
    private val playbackFactory: MediaSource.Factory
    private val policy = TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> { index ->
        val id = rankings.entries.firstOrNull { it.value == index }?.key
        // 保留 1.9.0 暂停时的 sample，不使用 NOT_PRELOADED 清空正在准备的 period。
        val inUse = id?.let(::isSourceInUse) == true
        val targetMs = when {
            inUse -> 0L
            !enabled -> 0L
            abs(index - selectedIndex) == 1 && adjacentStartupReady() -> PreloadProgress.FULL_TARGET_MS
            abs(index - selectedIndex) in 1..2 -> startupTargetMs
            else -> 0L
        }
        if (!inUse) id?.let { progress[it]?.request(targetMs, SystemClock.elapsedRealtime()) }
        if (targetMs == 0L) DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
        else DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(startPositions[id] ?: 0L, targetMs)
    }
    private val builder: DefaultPreloadManager.Builder
    private val manager: DefaultPreloadManager
    val engines: VideoEnginePool
    val loadControl: VideoLoadControl
    val player: ExoPlayer get() = engines.active
    var onPreloadReady: (() -> Unit)? = null

    init {
        loadControl = VideoLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    LOAD_CONTROL_MIN_BUFFER_MS,
                    LOAD_CONTROL_MAX_BUFFER_MS,
                    LOAD_CONTROL_BUFFER_FOR_PLAYBACK_MS,
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build())
        val renderersFactory = DefaultRenderersFactory(context).apply {
            setEnableDecoderFallback(true)
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            setMediaCodecSelector(VideoPlaybackRuntime.configuration.codecSelector)
        }

        playbackFactory = mediaSourceFactory ?: VideoReadMediaSourceFactory(context.applicationContext, cache, reads, VideoPlaybackRuntime.configuration.upstream(context))
        builder = DefaultPreloadManager.Builder(context, policy)
            .setLoadControl(loadControl)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(playbackFactory)
            .setCache(cache)
        manager = builder.build()
        manager.addListener(object : PreloadManagerListener {
            override fun onCompleted(mediaItem: MediaItem) {
                // 保持登记对象身份，移出后重新登记的旧回调不能完成新一代目标。
                if (!isCurrentRegistration(mediaItem)) return
                if (isSourceInUse(mediaItem.mediaId)) return
                val index = rankings[mediaItem.mediaId] ?: return
                val state = progress[mediaItem.mediaId] ?: return
                val startupJustCompleted = state.complete(SystemClock.elapsedRealtime())
                if (state.completedTargetMs >= startupTargetMs) ready.add(mediaItem.mediaId)
                if (enabled && abs(index - selectedIndex) in 1..2 && mediaItem.mediaId in ready) {
                    PlayerLog.d("VideoPreload completed index=$index selected=$selectedIndex targetMs=${state.completedTargetMs}")
                    onPreloadReady?.invoke()
                }
                // 两侧都达到起播目标后再补缓冲，不改变前后对称的距离排名。
                // 延后 invalidate，避免重入 BasePreloadManager 的队列推进。
                if (startupJustCompleted && abs(index - selectedIndex) == 1 && adjacentStartupReady()) {
                    handler.removeCallbacks(extendPreload)
                    handler.post(extendPreload)
                }
            }

            override fun onError(error: PreloadException) {
                if (!isCurrentRegistration(error.mediaItem)) return
                progress[error.mediaItem.mediaId]?.fail(SystemClock.elapsedRealtime())
                PlayerLog.d("VideoPreload error index=${rankings[error.mediaItem.mediaId] ?: -1}")
            }
        })
        engines = VideoEnginePool(context.applicationContext, { loadControl.warmupBudget.playerName = it },
            onWarmupCancelled = { request ->
                val id = request.media.sourceKey
                if (engines.active.currentMediaItem?.mediaId != id) {
                    removeRegistration(id)
                    // 不在取消/窗口更新调用栈内重新登记，后续使用最新窗口及恢复位置。
                    handler.removeCallbacks(rebuildCancelledPreloads)
                    if (enabled) handler.post(rebuildCancelledPreloads)
                }
            }) { name ->
            builder.buildExoPlayer(ExoPlayer.Builder(context).setName(name))
        }
    }

    fun updateWindow(media: List<Pair<Int, VideoMedia>>, selectedIndex: Int, enabled: Boolean,
        positions: Map<String, Long> = emptyMap()) {
        val indexChanged = this.selectedIndex != selectedIndex
        var changed = this.enabled != enabled
        this.selectedIndex = selectedIndex
        this.enabled = enabled
        windowMedia = media.toList()
        windowPositions = positions.toMap()
        // 缓存回退正在播放的当前项不再重复 prepare，以免占住串行预加载队列的队首。
        // 已经完成预加载的当前项仍保留在 manager 中，直到正常移出窗口。
        val desired = media.filter {
            !it.second.live && (enabled || it.first == selectedIndex) &&
                (it.first != selectedIndex || it.second.sourceKey in ready)
        }
            .distinctBy { it.second.sourceKey }
        val ids = desired.map { it.second.sourceKey }.toSet()
        items.keys.toList().filter { it !in ids }.forEach { id ->
            changed = true
            removeRegistration(id)
        }
        desired.forEach { (index, value) ->
            val previous = items[value.sourceKey]
            val positionMs = (positions[value.sourceKey] ?: 0L).coerceAtLeast(0L)
            val positionChanged = startPositions[value.sourceKey] != positionMs
            // 当前已交接的 period 属于播放引擎，进度推进时不重新登记或释放它。
            // 离开当前项后，使用保存的精确进度建立下一代预加载目标。
            if (previous != null && isSourceInUse(value.sourceKey)) {
                if (engines.active.currentMediaItem?.mediaId == value.sourceKey) return@forEach
                if (positionChanged) engines.cancelWarmup()
                else return@forEach
            }
            if (previous == null || rankings[value.sourceKey] != index || positionChanged) {
                val item = item(value, Any())
                changed = true
                if (previous != null) {
                    manager.remove(previous)
                    ready.remove(value.sourceKey)
                }
                items[value.sourceKey] = item
                rankings[value.sourceKey] = index
                startPositions[value.sourceKey] = positionMs
                progress[value.sourceKey] = PreloadProgress(SystemClock.elapsedRealtime(), startupTargetMs)
                manager.add(item, index)
            }
        }
        // setCurrentPlayingIndex 本身会 invalidate；相同窗口的宿主/分页通知无需重新调度。
        if (indexChanged) manager.setCurrentPlayingIndex(selectedIndex)
        else if (changed) manager.invalidate()
    }

    fun sourceFor(media: VideoMedia, index: Int = selectedIndex, positionMs: Long = 0L): MediaSource {
        val item = items[media.sourceKey] ?: item(media)
        lastPlaybackPreloadHit = false
        if (media.live) {
            lastPlaybackDiagnostics = PreloadDiagnostics(reason = PreloadReason.LIVE)
            return liveFactory.createMediaSource(item)
        }
        // 只有达到 sample 起播目标才交接 wrapper；未完成时仍走原共享缓存回退。
        val positionMatches = startPositions[media.sourceKey] == positionMs
        val preloaded = if (isPreloaded(media, positionMs)) manager.getMediaSource(item) else null
        lastPlaybackPreloadHit = preloaded != null
        if (preloaded != null) progress[media.sourceKey]?.finishRequest(SystemClock.elapsedRealtime())
        lastPlaybackDiagnostics = diagnosticsFor(media,
            if (media.sourceKey in items && !positionMatches) PreloadReason.POSITION_MISMATCH
            else if (media.sourceKey in ready && preloaded == null) PreloadReason.SOURCE_UNAVAILABLE else null)
        // 回退就是播放接管边界：先撤销旧预加载，再让调用方排入新源的 prepare。
        // 不等后续窗口刷新，避免同一内容的旧加载与普通缓存源交错准备。
        // 先冻结诊断；删除登记令牌后，旧完成/错误回调不能复活该目标。
        if (preloaded == null) removeRegistration(media.sourceKey)
        PlayerLog.d("VideoPreload playback index=$index hit=${preloaded != null} reason=${lastPlaybackDiagnostics.reason}")
        return preloaded ?: playbackFactory.createMediaSource(item)
    }

    private fun removeRegistration(id: String) {
        val item = items.remove(id)
        rankings.remove(id)
        startPositions.remove(id)
        ready.remove(id)
        progress.remove(id)
        item?.let(manager::remove)
    }

    fun diagnosticsFor(media: VideoMedia, reason: String? = null): PreloadDiagnostics =
        progress[media.sourceKey]?.snapshot(SystemClock.elapsedRealtime(), reason)
            ?.copy(startPositionMs = startPositions[media.sourceKey] ?: 0L)
            ?: PreloadDiagnostics(reason = reason ?: PreloadReason.NOT_REGISTERED)

    fun readMeterFor(media: VideoMedia): VideoReadStatistics? = if (media.live) null else reads.find(media.sourceKey)

    private fun adjacentStartupReady(): Boolean {
        val adjacent = rankings.filterValues { abs(it - selectedIndex) == 1 }.keys
        return adjacent.isNotEmpty() && adjacent.all {
            (progress[it]?.completedTargetMs ?: 0L) >= startupTargetMs
        }
    }

    private fun isCurrentRegistration(item: MediaItem): Boolean {
        val token = item.localConfiguration?.tag ?: return false
        return items[item.mediaId]?.localConfiguration?.tag === token
    }

    private fun isSourceInUse(id: String): Boolean =
        engines.warmedMediaKey == id || engines.active.currentMediaItem?.mediaId == id

    fun stopPreloading() {
        handler.removeCallbacks(extendPreload)
        handler.removeCallbacks(rebuildCancelledPreloads)
        val wasEnabled = enabled
        enabled = false
        engines.cancelWarmup(releaseEngine = true)
        if (wasEnabled) manager.invalidate()
    }

    fun release() {
        handler.removeCallbacks(extendPreload)
        handler.removeCallbacks(rebuildCancelledPreloads)
        enabled = false
        onPreloadReady = null
        engines.release()
        manager.release()
        items.clear()
        rankings.clear()
        startPositions.clear()
        ready.clear()
        progress.clear()
        reads.clear()
        windowMedia = emptyList()
        windowPositions = emptyMap()
    }

    fun isPreloaded(media: VideoMedia, positionMs: Long = 0L) =
        media.sourceKey in ready && startPositions[media.sourceKey] == positionMs

    private fun item(media: VideoMedia, token: Any? = null) =
        MediaItem.Builder().setUri(media.url).setMediaId(media.sourceKey).setTag(token).build()

    companion object {
        private const val LOAD_CONTROL_MIN_BUFFER_MS = 5_000
        private const val LOAD_CONTROL_MAX_BUFFER_MS = 20_000
        private const val LOAD_CONTROL_BUFFER_FOR_PLAYBACK_MS = 500
    }
}
