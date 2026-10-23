package io.github.silascui.sloth

import org.junit.Assert.*
import org.junit.Test

class PlaybackSessionTest {
    @Test fun positionIsAlwaysNonNegative() {
        val session = PlaybackSession("content", -1)
        assertEquals(0L, session.positionMs)
        session.savePosition(-100)
        assertEquals(0L, session.positionMs)
    }

    @Test fun completedContentRestartsAtBeginning() {
        val session = PlaybackSession("content", 1_000)
        session.savePosition(9_000, ended = true)
        assertEquals(0L, session.positionMs)
    }

    @Test fun positionUpdatesDoNotEraseManualPauseOrPreferences() {
        val session = PlaybackSession("content")
        session.userPaused = true
        session.speed = 2F
        session.muted = false
        session.savePosition(1_200)
        assertTrue(session.userPaused)
        assertEquals(2F, session.speed)
        assertFalse(session.muted)
        assertEquals(1_200L, session.positionMs)
    }
}
