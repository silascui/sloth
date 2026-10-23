package io.github.silascui.sloth

/** Serial main-thread delivery also preserves ordering when an observer issues another command. */
internal class PlaybackStateStore(initial: PlaybackState = PlaybackState()) {
    private data class Event(val revision: Long, val state: PlaybackState)
    private var revision = 0L
    private class Observer(val callback: (PlaybackState) -> Unit) {
        private var lastRevision = -1L
        fun deliver(event: Event) {
            if (event.revision <= lastRevision) return
            lastRevision = event.revision
            callback(event.state)
        }
    }
    private val observers = LinkedHashSet<Observer>()
    private val pending = ArrayDeque<Event>()
    private var dispatching = false
    private var closed = false
    private var closing = false
    var value = initial
        private set
    val hasObservers get() = observers.isNotEmpty()

    fun observe(callback: (PlaybackState) -> Unit): AutoCloseable {
        val observer = Observer(callback)
        if (!closed && !closing) observers += observer
        observer.deliver(Event(revision, value))
        return AutoCloseable { observers -= observer }
    }

    fun emit(state: PlaybackState) {
        if (closed || closing || value == state) return
        value = state
        pending.addLast(Event(++revision, state))
        drain()
    }

    private fun drain() {
        if (dispatching) return
        dispatching = true
        try {
            while (pending.isNotEmpty()) {
                val next = pending.removeFirst()
                observers.toList().forEach { if (it in observers) it.deliver(next) }
            }
        } finally {
            dispatching = false
            if (closing && pending.isEmpty()) {
                closed = true
                observers.clear()
            }
        }
    }

    fun close(state: PlaybackState) {
        if (closed || closing) return
        closing = true
        if (value != state) {
            value = state
            pending.addLast(Event(++revision, state))
        }
        drain()
    }
}
