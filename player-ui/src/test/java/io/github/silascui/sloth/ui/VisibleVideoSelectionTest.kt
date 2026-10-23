package io.github.silascui.sloth.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VisibleVideoSelectionTest {
    @Test fun largestVisibleAreaWinsRegardlessOfCurrentSelection() {
        assertEquals(2, selectVisibleVideo(listOf(VisibleVideo(0, 120), VisibleVideo(2, 180), VisibleVideo(1, 30)), 0))
    }
    @Test fun equalAreasKeepTheCurrentVideoToAvoidRestartingPlayback() {
        assertEquals(1, selectVisibleVideo(listOf(VisibleVideo(0, 100), VisibleVideo(1, 100)), 1))
    }
    @Test fun equalAreasWithoutTheCurrentVideoChooseTheFirstVisibleCandidate() {
        assertEquals(3, selectVisibleVideo(listOf(VisibleVideo(3, 100), VisibleVideo(4, 100)), 0))
    }
    @Test fun recycledAndInvisibleCandidatesCannotWin() {
        assertEquals(2, selectVisibleVideo(listOf(VisibleVideo(-1, 900), VisibleVideo(0, 0), VisibleVideo(1, -5), VisibleVideo(2, 1)), 0))
    }
    @Test fun noVisibleVideosClearsSelection() {
        assertNull(selectVisibleVideo(emptyList(), 0))
        assertNull(selectVisibleVideo(listOf(VisibleVideo(0, 0)), 0))
    }
    @Test fun areaComparisonDoesNotOverflowInt() {
        assertEquals(1, selectVisibleVideo(listOf(VisibleVideo(0, Int.MAX_VALUE.toLong()), VisibleVideo(1, Int.MAX_VALUE.toLong() + 1)), 0))
    }
}
