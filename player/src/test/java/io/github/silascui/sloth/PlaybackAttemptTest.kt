package io.github.silascui.sloth

import org.junit.Assert.*
import org.junit.Test

class PlaybackAttemptTest {
    @Test fun supersededUnpreparedSelectionStillHasOneTerminalRecord() {
        val first = PlaybackAttempt(0, 100L)
        val second = PlaybackAttempt(1, 110L)
        first.waiting("blocked_policy")
        val end = first.finish("superseded", 120L)!!
        assertNotEquals(first.id, second.id)
        assertEquals(20L, end.elapsedMs)
        assertFalse(end.wasPrepared)
        assertEquals("blocked_policy", end.waitReason)
        assertNull(first.finish("hidden", 130L))
        assertFalse(first.prepared(140L))
    }

    @Test fun preparedCancellationIsDifferentFromNeverPreparedSelection() {
        val attempt = PlaybackAttempt(1, 100L)
        assertTrue(attempt.prepared(110L))
        assertFalse(attempt.prepared(115L))
        attempt.waiting("waiting_host")
        val end = attempt.finish("revoked", 130L)!!
        assertTrue(end.wasPrepared)
        assertEquals("preparing", end.waitReason)
        assertEquals(110L, attempt.preparedAtMs)
    }

    @Test fun cancelledAttemptCannotPublishALateFrame() {
        val attempt = PlaybackAttempt(1, 100L)
        val trace = PlaybackStartupTrace(1, 100L, 110L, true, attempt = attempt)
        attempt.finish("superseded", 120L)
        assertNull(trace.onFirstFrame(130L))
    }

    @Test fun warmupMeasurementsIncludeWorkBeforeHandoffAndSnapshotLaterFrame() {
        val warmup = WarmupTiming(50L, 2_000L).apply {
            bindMs = 4L
            decoderReady(80L, 12L)
            ready(90L)
            ready(100L)
            promotedAtMs = 110L
        }
        val trace = PlaybackStartupTrace(1, 100L, 120L, true, warmEngineReused = true,
            restorePositionMs = 2_000L, warmup = warmup)
        warmup.frame(150L)
        val sample = trace.onFirstFrame(150L, 155L)!!
        assertEquals(60L, sample.warmupLeadMs)
        assertEquals(40L, sample.warmupReadyMs)
        assertEquals(30L, sample.warmupDecoderReadyMs)
        assertEquals(100L, sample.warmupFirstFrameMs)
        assertEquals(1, sample.warmupDecoderInitializations)
        assertEquals(2_000L, sample.restorePositionMs)
        assertEquals(30L, sample.prepareToFirstFrameMs)
        assertEquals(5L, sample.frameCallbackDelayMs)
        warmup.decoderReady(160L, 20L)
        assertEquals(1, sample.warmupDecoderInitializations)
    }
}
