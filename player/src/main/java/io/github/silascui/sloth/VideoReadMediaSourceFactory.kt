package io.github.silascui.sloth

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSourceEventListener
import java.io.IOException

/** 每个child source固定自己的meter；其下HLS分片等数据源也共享此身份，不通过URI反向匹配。 */
@android.annotation.SuppressLint("UnsafeOptInUsageError")
internal class VideoReadMediaSourceFactory(context: Context, private val cache: Cache,
    private val registry: VideoReadRegistry,
    private val upstream: DataSource.Factory = DefaultDataSource.Factory(context.applicationContext)) : MediaSource.Factory {
    private val delegate = DefaultMediaSourceFactory(context.applicationContext)
    private val handler = Handler(Looper.getMainLooper())
    override fun getSupportedTypes(): IntArray = delegate.supportedTypes
    override fun setDrmSessionManagerProvider(provider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider): MediaSource.Factory {
        delegate.setDrmSessionManagerProvider(provider); return this
    }
    override fun setLoadErrorHandlingPolicy(policy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy): MediaSource.Factory {
        delegate.setLoadErrorHandlingPolicy(policy); return this
    }
    override fun setCmcdConfigurationFactory(factory: androidx.media3.exoplayer.upstream.CmcdConfiguration.Factory): MediaSource.Factory {
        delegate.setCmcdConfigurationFactory(factory); return this
    }
    @Deprecated("Media3 legacy subtitle parsing")
    override fun experimentalParseSubtitlesDuringExtraction(parse: Boolean): MediaSource.Factory {
        delegate.experimentalParseSubtitlesDuringExtraction(parse); return this
    }
    override fun setSubtitleParserFactory(factory: androidx.media3.extractor.text.SubtitleParser.Factory): MediaSource.Factory {
        delegate.setSubtitleParserFactory(factory); return this
    }
    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecs: Int): MediaSource.Factory {
        delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecs); return this
    }
    override fun setDownloadExecutor(executor: com.google.common.base.Supplier<androidx.media3.exoplayer.util.ReleasableExecutor>): MediaSource.Factory {
        delegate.setDownloadExecutor(executor); return this
    }
    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val meter = registry.forMedia(mediaItem.mediaId)
        val sourceId = meter.newSource()
        val dataSources = CacheDataSource.Factory().setCache(cache)
            .setUpstreamDataSourceFactory { VideoReadDataSource(upstream.createDataSource(), meter, sourceId, false) }
            .setCacheReadDataSourceFactory { VideoReadDataSource(FileDataSource.Factory().createDataSource(), meter, sourceId, true) }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // createMediaSource在调用线程中同步捕获自己的DataSource.Factory；配置仍来自同一delegate。
        return delegate.setDataSourceFactory(dataSources).createMediaSource(mediaItem).also { source ->
            source.addEventListener(handler, object : MediaSourceEventListener {
                override fun onLoadCanceled(windowIndex: Int, mediaPeriodId: MediaSource.MediaPeriodId?,
                    loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
                    meter.loadCanceled()
                    event(MediaLoadEvent.CANCELED, loadEventInfo)
                }
                override fun onLoadError(windowIndex: Int, mediaPeriodId: MediaSource.MediaPeriodId?,
                    loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData, error: IOException, wasCanceled: Boolean) {
                    meter.loadError()
                    if (wasCanceled) meter.loadCanceled()
                    event(if (wasCanceled) MediaLoadEvent.ERROR_CANCELED else MediaLoadEvent.ERROR, loadEventInfo)
                }
                override fun onLoadCompleted(windowIndex: Int, mediaPeriodId: MediaSource.MediaPeriodId?,
                    loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) = event(MediaLoadEvent.COMPLETED, loadEventInfo)
                private fun event(phase: String, info: LoadEventInfo) {
                    val snapshot = meter.snapshot(android.os.SystemClock.elapsedRealtime())
                    PlayerLog.d("VideoReadLoad processId=${android.os.Process.myPid()} slotId=${meter.slotId} sourceId=$sourceId taskId=${info.loadTaskId} phase=$phase atMs=${info.elapsedRealtimeMs} durationMs=${info.loadDurationMs} mediaBytes=${info.bytesLoaded} snapshotAtMs=${snapshot.atMs} cacheBytes=${snapshot.cacheBytes} networkBytes=${snapshot.networkBytes} localBytes=${snapshot.localBytes} unknownBytes=${snapshot.unknownBytes} active=${snapshot.activeCount}")
                }
            })
        }
    }
}
