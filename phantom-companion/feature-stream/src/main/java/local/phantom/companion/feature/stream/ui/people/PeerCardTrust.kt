package local.phantom.companion.feature.stream.ui.people

import androidx.annotation.StringRes
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerRoster
import local.phantom.companion.feature.stream.R

/** Як людину додано — одним словом на картці, без ключів і кодів. */
internal object PeerCardTrust {

    /** Імені немає: порожнє або підставлений код ключа. */
    fun nameless(peer: PeerIdentity): Boolean =
        peer.name.isBlank() || peer.name == PeerRoster.shortId(peer.id)

    @StringRes
    fun label(peer: PeerIdentity, handle: String?): Int = when {
        peer.keyAlarm -> R.string.peer_card_key_changed
        peer.isVerified -> R.string.peer_card_trust_live
        peer.keySource == PeerIdentity.KeySource.SHARED -> R.string.peer_card_trust_link
        peer.keySource == PeerIdentity.KeySource.REMOTE && handle != null -> R.string.peer_card_trust_handle
        else -> R.string.peer_card_trust_none
    }
}
