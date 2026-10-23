package io.github.silascui.sloth

import org.junit.Assert.*
import org.junit.Test

class PlaybackStateStoreTest {
    @Test fun subscriptionsImmediatelyReceiveCurrentStateAndStopOnClose() {
        val store = PlaybackStateStore()
        val values = mutableListOf<PlaybackStatus>()
        val subscription = store.observe { values += it.status }
        store.emit(PlaybackState(status = PlaybackStatus.PLAYING))
        subscription.close()
        subscription.close()
        store.emit(PlaybackState(status = PlaybackStatus.PAUSED))
        assertEquals(listOf(PlaybackStatus.IDLE, PlaybackStatus.PLAYING), values)
        assertFalse(store.hasObservers)
    }
    @Test fun identicalStatesAreNotRedelivered() {
        val store = PlaybackStateStore()
        var calls = 0
        store.observe { calls++ }
        store.emit(PlaybackState())
        store.emit(PlaybackState())
        assertEquals(1, calls)
    }
    @Test fun commandsIssuedInsideObserversKeepAllObserversInOrder() {
        val store = PlaybackStateStore()
        store.observe { if (it.status == PlaybackStatus.PLAYING) store.emit(it.copy(status = PlaybackStatus.PAUSED)) }
        val values = mutableListOf<PlaybackStatus>()
        store.observe { values += it.status }
        store.emit(PlaybackState(status = PlaybackStatus.PLAYING))
        assertEquals(listOf(PlaybackStatus.IDLE, PlaybackStatus.PLAYING, PlaybackStatus.PAUSED), values)
    }
    @Test fun subscriberAddedDuringDispatchNeverReceivesOlderQueuedStates() {
        val store = PlaybackStateStore()
        val values = mutableListOf<PlaybackStatus>()
        store.observe {
            if (it.status == PlaybackStatus.PLAYING) {
                store.emit(it.copy(status = PlaybackStatus.BUFFERING))
                store.emit(it.copy(status = PlaybackStatus.PAUSED))
                store.observe { value -> values += value.status }
            }
        }
        store.emit(PlaybackState(status = PlaybackStatus.PLAYING))
        assertEquals(listOf(PlaybackStatus.PAUSED), values)
    }
    @Test fun closingInsideAnObserverStillDeliversTerminalStateToOtherObservers() {
        val store = PlaybackStateStore()
        val terminal = PlaybackState(status = PlaybackStatus.CLOSED)
        store.observe { if (it.status == PlaybackStatus.PLAYING) store.close(terminal) }
        val values = mutableListOf<PlaybackStatus>()
        store.observe { values += it.status }
        store.emit(PlaybackState(status = PlaybackStatus.PLAYING))
        assertEquals(listOf(PlaybackStatus.IDLE, PlaybackStatus.PLAYING, PlaybackStatus.CLOSED), values)
        assertFalse(store.hasObservers)
        store.emit(PlaybackState())
        assertEquals(terminal, store.value)
    }
    @Test fun lateSubscriberToClosedStoreReceivesTerminalStateWithoutBeingRetained() {
        val store = PlaybackStateStore()
        store.close(PlaybackState(status = PlaybackStatus.CLOSED))
        var status: PlaybackStatus? = null
        store.observe { status = it.status }
        assertEquals(PlaybackStatus.CLOSED, status)
        assertFalse(store.hasObservers)
    }
    @Test fun errorAndPlayingCapabilitiesAreDistinct() {
        val error = PlaybackState(status = PlaybackStatus.ERROR, canPlay = true, error = PlaybackFailure(1))
        assertTrue(error.canRetry)
        assertFalse(error.canPause)
        val playing = error.copy(status = PlaybackStatus.PLAYING, playWhenReady = true, error = null)
        assertTrue(playing.isPlaying)
        assertTrue(playing.canPause)
        assertFalse(playing.canRetry)
    }
}
