package io.github.silascui.sloth

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@android.annotation.SuppressLint("UnsafeOptInUsageError")
@RunWith(AndroidJUnit4::class)
class VideoReadDataSourceTest {
    @Test fun realCacheSeparatesUpstreamAndCachedBytesWithoutDoubleCounting() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(context.cacheDir, "video-read-test-${System.nanoTime()}").apply { mkdirs() }
        val database = StandaloneDatabaseProvider(context)
        val cache = SimpleCache(folder, LeastRecentlyUsedCacheEvictor(1_048_576L), database)
        val meter = VideoReadStatistics()
        val sourceId = meter.newSource()
        val opens = AtomicInteger()
        val bytes = ByteArray(65_536) { (it % 251).toByte() }
        val upstream = DataSource.Factory { object : BaseDataSource(true) {
            private var spec: DataSpec? = null
            private var offset = 0
            override fun open(dataSpec: DataSpec): Long {
                spec = dataSpec; offset = dataSpec.position.toInt()
                transferInitializing(dataSpec); transferStarted(dataSpec); opens.incrementAndGet()
                return (bytes.size - offset).toLong()
            }
            override fun read(buffer: ByteArray, start: Int, length: Int): Int {
                if (offset == bytes.size) return C.RESULT_END_OF_INPUT
                val count = minOf(length, bytes.size - offset)
                bytes.copyInto(buffer, start, offset, offset + count); offset += count
                bytesTransferred(count)
                return count
            }
            override fun getUri(): Uri? = spec?.uri
            override fun close() { if (spec != null) { transferEnded(); spec = null } }
        } }
        val factory = CacheDataSource.Factory().setCache(cache)
            .setUpstreamDataSourceFactory { VideoReadDataSource(upstream.createDataSource(), meter, sourceId, false) }
            .setCacheReadDataSourceFactory { VideoReadDataSource(FileDataSource.Factory().createDataSource(), meter, sourceId, true) }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val spec = DataSpec(Uri.parse("https://example.invalid/read-fixture"))
        try {
            repeat(2) {
                val source = factory.createDataSource()
                val output = java.io.ByteArrayOutputStream()
                try {
                    source.open(spec)
                    val buffer = ByteArray(8_192)
                    while (true) {
                        val count = source.read(buffer, 0, buffer.size)
                        if (count == C.RESULT_END_OF_INPUT) break
                        output.write(buffer, 0, count)
                    }
                } finally { source.close() }
                assertArrayEquals(bytes, output.toByteArray())
            }
            val snapshot = meter.snapshot(android.os.SystemClock.elapsedRealtime())
            assertEquals(1, opens.get())
            assertEquals(bytes.size.toLong(), snapshot.networkBytes)
            assertEquals(bytes.size.toLong(), snapshot.cacheBytes)
            assertEquals(0L, snapshot.localBytes); assertEquals(0L, snapshot.unknownBytes)
            assertEquals(0L, snapshot.activeCount)
            assertEquals(0L, snapshot.loadCanceledCount)
        } finally { cache.release(); database.close(); folder.deleteRecursively() }
    }

    @Test fun openFailurePropagatesSameExceptionAndDoesNotInventCloseOrCancel() {
        val error = IOException("fixture failure")
        val headers = mapOf("X-Fixture" to listOf("preserved"))
        var closes = 0
        val delegate = object : BaseDataSource(true) {
            override fun open(spec: DataSpec): Long { transferInitializing(spec); throw error }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw AssertionError("unexpected read")
            override fun getUri(): Uri? = null
            override fun getResponseHeaders(): Map<String, List<String>> = headers
            override fun close() { closes++ }
        }
        val meter = VideoReadStatistics()
        val source = VideoReadDataSource(delegate, meter, meter.newSource(), false)
        assertSame("response headers must pass through the diagnostic wrapper", headers, source.responseHeaders)
        try {
            source.open(DataSpec(Uri.parse("https://example.invalid/failure")))
            fail("expected original IOException")
        } catch (actual: IOException) { assertSame(error, actual) }
        assertEquals(1L, meter.snapshot(0L).activeCount)
        source.close()
        val snapshot = meter.snapshot(0L)
        assertEquals(1, closes); assertEquals(0L, snapshot.activeCount)
        assertEquals(0L, snapshot.loadCanceledCount); assertTrue(snapshot.requests.single().failed)
        assertEquals("network", snapshot.requests.single().kind)
    }
}
