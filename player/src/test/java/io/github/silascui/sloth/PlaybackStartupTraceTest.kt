package io.github.silascui.sloth

import org.junit.Assert.*
import org.junit.Test

class PlaybackStartupTraceTest {
    @Test fun warmDecodeSnapshotDoesNotInventInitializationOrLoseUnknownValues() {
        val warm = WarmupTiming(10L, 2_000L)
        warm.inputFormat(30L, 1920, -1, 1)
        warm.decodeCounters(52, 47, 1)
        warm.frame(210L)
        warm.inputFormat(220L, 640, 480, 0)
        warm.decodeCounters(99, 98, 2)
        val trace = PlaybackStartupTrace(1, 150L, 160L, true, warmEngineReused = true, warmup = warm)
        val sample = trace.onFirstFrame(210L)!!
        assertEquals(20L, sample.warmupInputFormatMs)
        assertEquals(180L, sample.warmupFormatToFirstFrameMs)
        assertEquals(1, sample.warmupDecoderReuseResult)
        assertEquals(52, sample.warmupQueuedInputBuffers)
        assertEquals(47, sample.warmupSkippedOutputBuffers)
        assertEquals(1, sample.warmupRenderedOutputBuffers)
        assertEquals(1920, sample.warmupVideoWidth)
        assertNull(sample.warmupVideoHeight)
        assertEquals(0, sample.warmupDecoderInitializations)
        val unknown = PlaybackStartupTrace(0, 0L, 1L, false).onFirstFrame(2L)!!
        assertNull(unknown.warmupInputFormatMs)
        assertNull(unknown.warmupQueuedInputBuffers)
    }

    @Test fun renderedFrameCarriesAttemptIdentityAndSignedReadyOrdering() {
        val attempt = PlaybackAttempt(2, 100L)
        val trace = PlaybackStartupTrace(2, 100L, 110L, true, attempt = attempt)
        trace.onReady(140L)
        val sample = trace.onFirstFrame(130L, 150L)!!
        assertEquals(attempt.id, sample.attemptId)
        assertEquals(-10L, sample.readyToFirstFrameMs)
        assertEquals(20L, sample.frameCallbackDelayMs)
        assertNull(sample.warmupLeadMs)
    }

    @Test fun preloadMissDiagnosticsAreCapturedWithoutClaimingDiskCacheMiss() {
        val trace = PlaybackStartupTrace(1, 100L, 110L, false,
            preload = PreloadDiagnostics("in_progress", 500L, 0L, 20L, 70L), warmupReason = "not_warmed")
        val sample = trace.onFirstFrame(180L)!!
        assertEquals("in_progress", sample.preloadReason)
        assertEquals(500L, sample.preloadRequestedMs)
        assertEquals(0L, sample.preloadCompletedMs)
        assertEquals(20L, sample.preloadQueueMs)
        assertEquals(70L, sample.preloadLoadMs)
        assertEquals("not_warmed", sample.warmupReason)
    }

    @Test fun queuedWarmFrameFromBeforeHandoffMeasuresWhenItBecomesAvailable() {
        val trace = PlaybackStartupTrace(1, 110L, 120L, true, warmEngineReused = true)
        val sample = trace.onFirstFrame(100L, availableAtMs = 125L)!!
        assertTrue(sample.warmFrameReused)
        assertTrue(sample.warmEngineReused)
        assertEquals(15L, sample.selectionToFirstFrameMs)
        assertNull(trace.onFirstFrame(130L))
    }

    @Test fun warmFramePresentationIsExplicitlyDistinguishedFromPostSelectionDecoding() {
        val trace = PlaybackStartupTrace(1, 100L, 110L, true, warmFrameReused = true)
        val sample = trace.onFirstFrame(112L)!!
        assertTrue(sample.warmFrameReused)
        assertEquals(12L, sample.selectionToFirstFrameMs)
        assertEquals(0, sample.decoderInitializations)
        assertNull(trace.onFirstFrame(150L))
    }

    @Test fun measuresSelectionAndPreparationSeparately() {
        val trace = PlaybackStartupTrace(2, 100L, 125L, true)
        trace.onReady(200L)
        trace.onReady(300L)
        trace.onDecoderInitialized(30L)
        val sample = trace.onFirstFrame(230L)!!
        assertEquals(25L, sample.selectionToPrepareMs)
        assertEquals(75L, sample.prepareToReadyMs)
        assertEquals(130L, sample.selectionToFirstFrameMs)
        assertEquals(1, sample.decoderInitializations)
        assertEquals(30L, sample.decoderInitializationMs)
    }

    @Test fun firstFrameSnapshotsStagesWithoutKeepingMutableCounters() {
        val stages = PlaybackStartupTrace.Stages().apply { targetBindMs = 12L; sourceSetMs = 5L }
        val trace = PlaybackStartupTrace(0, 100L, 120L, true, stages)
        val sample = trace.onFirstFrame(150L)!!
        stages.targetBindMs = 99L
        assertEquals(12L, sample.targetBindMs)
        assertEquals(5L, sample.sourceSetMs)
    }

    @Test fun firstFrameCanPrecedeReadyAndDoesNotDoubleCountRebind() {
        val trace = PlaybackStartupTrace(0, 100L, 110L, false)
        assertNull(trace.onFirstFrame(109L))
        val sample = trace.onFirstFrame(120L)!!
        assertNull(sample.prepareToReadyMs)
        assertEquals(20L, sample.selectionToFirstFrameMs)
        assertNull(trace.onFirstFrame(140L))
    }
    @Test fun readSnapshotIncludesPreloadAndFreezesOnlyPostPrepareDelta() {
        val meter = VideoReadStatistics()
        val request = meter.begin(meter.newSource(), false, 10L)
        meter.classify(request, true)
        meter.read(request, 100, 10L, 20L)
        var now = 20L
        val trace = PlaybackStartupTrace(0, 20L, 20L, true, readSnapshot = { meter.snapshot(now) })
        meter.read(request, 40, 20L, 30L); now = 30L
        val sample = trace.onFirstFrame(30L)!!
        meter.read(request, 99, 30L, 40L)
        assertEquals(140L, sample.read!!.snapshot.networkBytes)
        assertEquals(40L, sample.read!!.networkBytesAfterPrepare)
        assertEquals(20L, sample.read!!.baselineAtMs)
        assertNull(PlaybackStartupTrace(0, 1L, 1L, false).onFirstFrame(2L)!!.read)
    }

}
