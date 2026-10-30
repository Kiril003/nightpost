package local.phantom.companion.core.solana

/** Купюра, щойно підписана для людини: що показати нагорі гаманця. */
data class Handoff(val name: String, val amount: String, val signature: String)

enum class HandoffPhase { SIGNED, SEARCHING, HANDED, NOT_FOUND, QUEUED }

/**
 * Фаза передачі з потоку станів дороги поруч. «Передано» — лише після пошуку цієї
 * купюри: HANDED, що світився ще до неї, — від минулої передачі.
 */
data class HandoffTrack(val phase: HandoffPhase, private val staleHanded: Boolean = false) {

    fun next(s: NearbyState): HandoffTrack = when {
        phase == HandoffPhase.HANDED -> this
        s == NearbyState.SEARCHING -> HandoffTrack(HandoffPhase.SEARCHING)
        s == NearbyState.HANDED -> if (staleHanded) this else HandoffTrack(HandoffPhase.HANDED)
        s == NearbyState.OFF || s == NearbyState.NO_SERVICES || s == NearbyState.NEEDS_PERMISSION -> HandoffTrack(HandoffPhase.QUEUED)
        phase == HandoffPhase.SEARCHING -> HandoffTrack(HandoffPhase.NOT_FOUND)
        else -> copy(staleHanded = false)
    }

    companion object {
        /** [nearbyNow] — чи піде купюра дорогою поруч узагалі (є сервіси й немає інтернету). */
        fun start(state: NearbyState?, nearbyNow: Boolean): HandoffTrack = when {
            state == null || !nearbyNow -> HandoffTrack(HandoffPhase.QUEUED)
            state == NearbyState.HANDED -> HandoffTrack(HandoffPhase.SIGNED, staleHanded = true)
            else -> HandoffTrack(HandoffPhase.SIGNED).next(state)
        }
    }
}
