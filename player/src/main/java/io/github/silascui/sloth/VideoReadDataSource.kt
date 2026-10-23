package io.github.silascui.sloth

import android.os.SystemClock
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/** 仅旁观read返回的媒体字节，不把TLS/HTTP开销当作媒体流量，不改变读长、缓存键或重试。 */
@android.annotation.SuppressLint("UnsafeOptInUsageError")
internal class VideoReadDataSource(private val delegate: DataSource, private val meter: VideoReadStatistics,
    private val sourceId: Long, private val cache: Boolean) : DataSource {
    private var request: VideoReadStatistics.Request? = null
    init {
        delegate.addTransferListener(object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                request?.let { meter.classify(it, isNetwork) }
            }
            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                request?.let { meter.classify(it, isNetwork) }
            }
            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) = Unit
            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        })
    }
    override fun addTransferListener(transferListener: TransferListener) = delegate.addTransferListener(transferListener)
    override fun getUri(): Uri? = delegate.uri
    // Java默认接口方法不会由Kotlin by可靠转发，必须保留上游响应头供原加载逻辑使用。
    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders
    override fun open(dataSpec: DataSpec): Long {
        val current = meter.begin(sourceId, cache, SystemClock.elapsedRealtime())
        request = current
        return try {
            delegate.open(dataSpec).also { meter.opened(current, SystemClock.elapsedRealtime()) }
        } catch (error: IOException) {
            meter.failure(current)
            throw error
        }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            delegate.read(buffer, offset, length).also { count ->
                request?.let { meter.read(it, count, startedAt, SystemClock.elapsedRealtime()) }
            }
        } catch (error: IOException) {
            request?.let {
                meter.read(it, 0, startedAt, SystemClock.elapsedRealtime())
                meter.failure(it)
            }
            throw error
        }
    }
    override fun close() {
        try { delegate.close() } catch (error: IOException) {
            request?.let(meter::failure)
            throw error
        } finally {
            request?.let { meter.end(it, SystemClock.elapsedRealtime()) }
            request = null
        }
    }
}
