package io.github.silascui.sloth

import org.junit.Assert.*
import org.junit.Test

class VideoReadStatisticsTest {
    @Test fun cacheNetworkLocalAndUnknownRemainSeparateAndSnapshotsFreeze() {
        val meter = VideoReadStatistics()
        val source = meter.newSource()
        val network = meter.begin(source, false, 10L)
        meter.classify(network, true); meter.opened(network, 30L)
        meter.read(network, 100, 30L, 45L)
        val before = meter.snapshot(45L)
        meter.read(network, 20, 45L, 50L); meter.end(network, 51L)
        val cache = meter.begin(source, true, 52L)
        meter.classify(cache, false); meter.opened(cache, 53L)
        meter.read(cache, 120, 53L, 54L); meter.end(cache, 55L)
        val local = meter.begin(source, false, 56L)
        meter.classify(local, false); meter.read(local, 30, 56L, 57L); meter.end(local, 58L)
        val unknown = meter.begin(source, false, 59L)
        meter.read(unknown, 5, 59L, 60L); meter.end(unknown, 61L)
        val after = meter.snapshot(61L)
        assertEquals(120L, after.networkBytes); assertEquals(120L, after.cacheBytes)
        assertEquals(30L, after.localBytes); assertEquals(5L, after.unknownBytes)
        assertEquals(20L, after.networkOpenMs); assertEquals(20L, after.networkReadMs)
        assertEquals(45L, before.requests.single().firstByteAtMs)
        assertEquals(100L, before.networkBytes); assertNull(before.requests.single().endedAtMs)
        assertEquals(0L, after.activeCount)
    }

    @Test fun closingDoesNotInventCancellationAndErrorsDoNotPretendToClose() {
        val meter = VideoReadStatistics()
        val r = meter.begin(meter.newSource(), false, 1L)
        meter.failure(r)
        assertEquals(1L, meter.snapshot(2L).activeCount)
        meter.end(r, 3L); meter.end(r, 4L)
        assertEquals(1L, meter.snapshot(4L).closeCount)
        assertEquals(0L, meter.snapshot(4L).loadCanceledCount)
        meter.loadCanceled(); meter.loadError()
        assertEquals(1L, meter.snapshot(5L).loadCanceledCount)
        assertEquals(1L, meter.snapshot(5L).loadErrorCount)
        assertTrue(meter.snapshot(5L).requests.single().failed)
    }

    @Test fun requestHistoryAndRegistryAreBoundedWithoutLosingTotalsOrOldMeterIdentity() {
        val registry = VideoReadRegistry()
        val old = registry.forMedia("old")
        val source = old.newSource()
        repeat(40) {
            val r = old.begin(source, true, it.toLong())
            old.read(r, 1, 0L, 1L); old.end(r, it + 1L)
        }
        val frozen = old.snapshot(50L)
        assertEquals(24, frozen.requests.size); assertEquals(16L, frozen.droppedRequests)
        assertEquals(40L, frozen.cacheBytes)
        repeat(16) { registry.forMedia("other-$it") }
        assertNull(registry.find("old"))
        assertEquals(40L, old.snapshot(60L).cacheBytes)
        assertNotEquals(old.slotId, registry.forMedia("old").slotId)
    }
}
