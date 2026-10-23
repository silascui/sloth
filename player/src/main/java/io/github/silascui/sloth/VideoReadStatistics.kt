package io.github.silascui.sloth

import java.util.concurrent.atomic.AtomicLong

/** 同一资源组内单项媒体的有界读取观测；不保存 URI、业务身份、请求头或异常文本。 */
internal class VideoReadStatistics {
    val slotId = ids.incrementAndGet()
    data class Request(val sourceId: Long, val requestId: Long, var kind: String, val startedAtMs: Long,
        var openedAtMs: Long? = null, var firstByteAtMs: Long? = null, var endedAtMs: Long? = null,
        var bytes: Long = 0, var readMs: Long = 0, var failed: Boolean = false)
    data class Snapshot(val slotId: Long, val atMs: Long, val cacheBytes: Long, val networkBytes: Long,
        val localBytes: Long, val unknownBytes: Long, val sourceCount: Long, val openCount: Long,
        val closeCount: Long, val activeCount: Long, val maxActiveCount: Long,
        val networkOpenMs: Long, val networkReadMs: Long, val loadCanceledCount: Long,
        val loadErrorCount: Long, val droppedRequests: Long, val requests: List<Request>)
    data class Frame(val snapshot: Snapshot, val baselineAtMs: Long,
        val cacheBytesAfterPrepare: Long, val networkBytesAfterPrepare: Long)
    private val requests = ArrayDeque<Request>()
    private var cacheBytes = 0L
    private var networkBytes = 0L
    private var localBytes = 0L
    private var unknownBytes = 0L
    private var sourceCount = 0L
    private var opens = 0L
    private var closes = 0L
    private var maxActive = 0L
    private var networkOpenMs = 0L
    private var networkReadMs = 0L
    private var canceled = 0L
    private var errors = 0L
    private var dropped = 0L

    @Synchronized fun newSource(): Long { sourceCount++; return ids.incrementAndGet() }
    @Synchronized fun begin(sourceId: Long, cache: Boolean, atMs: Long): Request {
        val request = Request(sourceId, ids.incrementAndGet(), if (cache) ReadSourceKind.CACHE else ReadSourceKind.UNKNOWN, atMs)
        opens++
        maxActive = maxOf(maxActive, opens - closes)
        if (requests.size == MAX_REQUESTS) { requests.removeFirst(); dropped++ }
        requests.addLast(request)
        return request
    }
    @Synchronized fun classify(request: Request, network: Boolean) {
        if (request.kind != ReadSourceKind.CACHE) request.kind = if (network) ReadSourceKind.NETWORK else ReadSourceKind.LOCAL
    }
    @Synchronized fun opened(request: Request, atMs: Long) {
        request.openedAtMs = atMs
        if (request.kind == ReadSourceKind.NETWORK) networkOpenMs += (atMs - request.startedAtMs).coerceAtLeast(0L)
    }
    @Synchronized fun read(request: Request, count: Int, startedAtMs: Long, endedAtMs: Long) {
        val duration = (endedAtMs - startedAtMs).coerceAtLeast(0L)
        request.readMs += duration
        if (request.kind == ReadSourceKind.NETWORK) networkReadMs += duration
        if (count <= 0) return
        if (request.firstByteAtMs == null) request.firstByteAtMs = endedAtMs
        request.bytes += count
        when (request.kind) {
            ReadSourceKind.CACHE -> cacheBytes += count
            ReadSourceKind.NETWORK -> networkBytes += count
            ReadSourceKind.LOCAL -> localBytes += count
            else -> unknownBytes += count
        }
    }
    @Synchronized fun end(request: Request, atMs: Long, failed: Boolean = false) {
        request.failed = request.failed || failed
        if (request.endedAtMs != null) return
        request.endedAtMs = atMs
        closes++
    }
    @Synchronized fun failure(request: Request) { request.failed = true }
    @Synchronized fun loadCanceled() { canceled++ }
    @Synchronized fun loadError() { errors++ }
    @Synchronized fun snapshot(atMs: Long) = Snapshot(slotId, atMs, cacheBytes, networkBytes, localBytes,
        unknownBytes, sourceCount, opens, closes, opens - closes, maxActive, networkOpenMs,
        networkReadMs, canceled, errors, dropped, requests.map { it.copy() })

    companion object {
        private val ids = AtomicLong()
        private const val MAX_REQUESTS = 24
    }
}

/** 只持有最近16项；在用数据源及首帧trace各持有自己的meter，不因表淘汰串入其他媒体。 */
internal class VideoReadRegistry {
    private val entries = LinkedHashMap<String, VideoReadStatistics>(MAX_TRACKED_MEDIA, MAP_LOAD_FACTOR, true)
    @Synchronized fun forMedia(key: String): VideoReadStatistics = entries.getOrPut(key) {
        if (entries.size == MAX_TRACKED_MEDIA) entries.remove(entries.keys.first())
        VideoReadStatistics()
    }
    @Synchronized fun find(key: String): VideoReadStatistics? = entries[key]
    @Synchronized fun clear() = entries.clear()
    private companion object {
        private const val MAX_TRACKED_MEDIA = 16
        private const val MAP_LOAD_FACTOR = 0.75F
    }

}
