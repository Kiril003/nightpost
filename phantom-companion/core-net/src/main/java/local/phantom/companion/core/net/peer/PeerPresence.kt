package local.phantom.companion.core.net.peer

import kotlinx.coroutines.delay

/**
 * The clock that makes presence expire.
 *
 * [PeerRoster.LIVE_WINDOW_MS] is a rule about the passage of time, and nothing
 * in the app was passing any: «зараз поруч» was computed once when a screen
 * composed and then held that answer until some unrelated redraw happened to
 * disturb it. A roster claiming somebody is in earshot who walked off twenty
 * minutes ago is worse than one that claims nothing, and anything built on top
 * of reachability — a filter, a fan-out expectation — inherits the lie.
 */
object PeerPresence {

    /**
     * Far under [PeerRoster.LIVE_WINDOW_MS], so the phrase never trails the
     * fact by more than a glance. One wake every five seconds while a screen
     * is actually in front of somebody — not a frame loop, not a sensor.
     */
    const val TICK_MS = 5_000L

    /**
     * Emits now, then every [periodMs]. The leading emission is the point:
     * returning to a screen after an hour must be honest on the first frame
     * rather than five seconds into it.
     */
    suspend fun tick(
        periodMs: Long = TICK_MS,
        now: () -> Long = System::currentTimeMillis,
        onTick: (Long) -> Unit,
    ) {
        while (true) {
            onTick(now())
            delay(periodMs)
        }
    }
}
