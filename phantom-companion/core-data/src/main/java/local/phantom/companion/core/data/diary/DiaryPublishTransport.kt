package local.phantom.companion.core.data.diary

/**
 * Phase 3-D3 — abstraction over the WS link's send path. [DiaryStore]
 * lives in :core-data which deliberately doesn't depend on :app's
 * [PhantomLink]; instead it consumes this interface, and the :app
 * side wires a concrete `PhantomLinkDiaryTransport` that delegates
 * through [PhantomLink.send] + [PhantomLink.connected].
 *
 * Contract:
 *  - [isConnected] is a cheap read of the link's status. Used by the
 *    write path to short-circuit straight to the queue when offline,
 *    avoiding a futile `send` attempt.
 *  - [send] returns true on confirmed-sent, false on transport
 *    failure. A false return causes the caller to enqueue. The
 *    coroutine should not throw on transport failure — return false
 *    and let the queue handle retry.
 */
interface DiaryPublishTransport {
    val isConnected: Boolean
    suspend fun send(verb: String, payload: String): Boolean
}
