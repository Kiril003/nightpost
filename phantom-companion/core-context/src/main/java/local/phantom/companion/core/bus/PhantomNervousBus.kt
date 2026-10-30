package local.phantom.companion.core.bus

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Central event stream that collects, relays, and archives all events
 * across the PHANTOM Companion organism.
 */
class PhantomNervousBus {
    private val _events = MutableSharedFlow<PhantomEvent>(
        replay = REPLAY_BUFFER,
        extraBufferCapacity = EXTRA_BUFFER
    )
    val events: SharedFlow<PhantomEvent> = _events.asSharedFlow()

    /**
     * Publishes a new event to the nervous system.
     */
    fun publish(event: PhantomEvent) {
        _events.tryEmit(event)
    }

    companion object {
        private const val REPLAY_BUFFER = 64
        private const val EXTRA_BUFFER = 128
    }
}
