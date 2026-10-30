package local.phantom.companion.data

import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerOutbox
import local.phantom.companion.core.solana.SolanaNotice

/** З ким є розмова 1:1: хоч одне сказане слово в будь-який бік. Групи й наша ж адреса не рахуються. */
internal fun talkedWith(outbox: List<PeerMessage>, me: String): Set<String> =
    outbox.asSequence()
        .filter { PeerOutbox.isSpoken(it) && !SolanaNotice.isSilent(it.body) }
        .map { if (it.fromId == me) it.toId else it.fromId }
        .filter { it.isNotBlank() && it != me }
        .toSet()
