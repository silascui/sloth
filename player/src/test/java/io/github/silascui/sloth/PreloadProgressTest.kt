package io.github.silascui.sloth

import org.junit.Assert.*
import org.junit.Test

class PreloadProgressTest {
    @Test fun decoderReadinessUsesItsOwnCompletedSampleThreshold() {
        val original = PreloadProgress(0L, 500L)
        val candidate = PreloadProgress(0L, 250L)
        listOf(original, candidate).forEach { progress ->
            progress.request(0L, 1L); assertFalse(progress.complete(2L))
            progress.request(250L, 3L)
        }
        assertEquals("in_progress", candidate.snapshot(4L).reason)
        assertFalse(original.complete(10L))
        assertTrue(candidate.complete(10L))
        assertEquals("in_progress", original.snapshot(11L).reason)
        assertEquals("startup_ready", candidate.snapshot(11L).reason)
        candidate.request(1_000L, 12L)
        assertEquals(250L, candidate.snapshot(13L).completedTargetMs)
        assertFalse(candidate.complete(20L))
        assertEquals("buffer_ready", candidate.snapshot(21L).reason)
    }

    @Test(expected = IllegalArgumentException::class)
    fun tracksOnlyCannotBeConfiguredAsASampleReadinessThreshold() { PreloadProgress(0L, 0L) }

    @Test fun handoffStopsTheStageClockWithoutClaimingTheFullTargetCompleted() {
        val progress = PreloadProgress(0L)
        progress.request(500L, 5L); progress.complete(25L)
        progress.request(1_000L, 35L); progress.finishRequest(50L)
        val snapshot = progress.snapshot(5_000L)
        assertEquals(500L, snapshot.completedTargetMs)
        assertEquals(15L, snapshot.loadMs)
        assertEquals(20L, snapshot.startupLoadMs)
        assertEquals("startup_ready", snapshot.reason)
    }

    @Test fun registeredAndQueuedAreNotSampleReady() {
        val progress = PreloadProgress(100L)
        assertEquals("not_started", progress.snapshot(125L).reason)
        progress.request(500L, 130L)
        val snapshot = progress.snapshot(150L)
        assertEquals("in_progress", snapshot.reason)
        assertEquals(30L, snapshot.queueMs)
        assertEquals(20L, snapshot.loadMs)
        assertEquals(0L, snapshot.completedTargetMs)
    }

    @Test fun trackCompletionMustNotBeTreatedAsLoadedSamples() {
        val progress = PreloadProgress(0L)
        progress.request(0L, 10L)
        assertFalse(progress.complete(20L))
        assertEquals("tracks_only", progress.snapshot(30L).reason)
        assertEquals(0L, progress.completedTargetMs)
    }

    @Test fun startupCompletionUnlocksSamplesBeforeFullTarget() {
        val progress = PreloadProgress(0L)
        progress.request(500L, 5L)
        assertTrue(progress.complete(25L))
        val startup = progress.snapshot(30L)
        progress.request(1_000L, 35L)
        assertEquals("startup_ready", progress.snapshot(40L).reason)
        assertEquals(500L, progress.snapshot(40L).completedTargetMs)
        assertFalse(progress.complete(50L))
        assertEquals("buffer_ready", progress.snapshot(55L).reason)
        assertEquals(15L, progress.snapshot(55L).loadMs)
        assertEquals(500L, startup.completedTargetMs)
    }

    @Test fun tracksOnlyPauseDoesNotEraseAlreadyLoadedSamples() {
        val progress = PreloadProgress(0L)
        progress.request(500L, 5L); progress.complete(10L)
        progress.request(0L, 15L); progress.complete(20L)
        assertEquals(500L, progress.completedTargetMs)
        assertEquals("startup_ready", progress.snapshot(25L).reason)
    }

    @Test fun errorIsReportedWithoutClaimingCacheMissOrLosingLoadedData() {
        val progress = PreloadProgress(0L)
        progress.request(500L, 5L); progress.fail()
        assertEquals("preload_error", progress.snapshot(10L).reason)
        progress.complete(15L)
        progress.request(1_000L, 20L); progress.fail()
        assertEquals("startup_ready", progress.snapshot(25L).reason)
    }
}
