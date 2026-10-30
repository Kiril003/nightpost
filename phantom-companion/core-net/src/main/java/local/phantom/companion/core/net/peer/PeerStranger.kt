package local.phantom.companion.core.net.peer

/**
 * A frame addressed to this phone that this phone could not open.
 *
 * In a group this is the failure that costs the most and shows the least. Six
 * people talk; one of them never scanned you; their lines alone fail to open
 * on your phone and are dropped without a trace. Your copy of the conversation
 * diverges from everybody else's, permanently, and every screen keeps looking
 * correct. A crew whose shared picture is not shared, and nobody knows.
 *
 * Everything here is CLAIMED, not proven. The sender id rides in the envelope
 * in the clear and the frame never opened, so anybody on the LAN can put any
 * id in that field. It is enough to say «щось не дійшло» and name who it said
 * it was from; it must never be rendered as more than that.
 */
data class PeerStranger(
    val claimedId: String,
    /** Already in the roster — so the key is stale or missing, not an outsider. */
    val inRoster: Boolean,
    val firstAtMs: Long,
    val lastAtMs: Long,
    val count: Int,
) {
    companion object {

        /** A flood must not become a memory leak; the newest are the useful ones. */
        const val MAX_KEPT = 8

        /** Newest first, one entry per claimed id — a repeat is a count, not a row. */
        fun note(
            seen: List<PeerStranger>,
            claimedId: String,
            inRoster: Boolean,
            atMs: Long,
        ): List<PeerStranger> {
            if (claimedId.isBlank()) return seen
            val was = seen.firstOrNull { it.claimedId == claimedId }
            val next = PeerStranger(
                claimedId = claimedId,
                inRoster = inRoster,
                firstAtMs = was?.firstAtMs ?: atMs,
                lastAtMs = atMs,
                count = (was?.count ?: 0) + 1,
            )
            return (listOf(next) + seen.filterNot { it.claimedId == claimedId }).take(MAX_KEPT)
        }

        /** Adding somebody answers their drops — the notice has done its work. */
        fun forget(seen: List<PeerStranger>, claimedId: String): List<PeerStranger> =
            seen.filterNot { it.claimedId == claimedId }
    }
}
