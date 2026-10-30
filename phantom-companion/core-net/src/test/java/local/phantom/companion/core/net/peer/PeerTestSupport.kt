package local.phantom.companion.core.net.peer

import local.phantom.companion.core.net.crypto.X25519

/** The invite inside a decode result, or null for anything that is not one. */
internal fun inviteIn(text: String?): PeerInvite.Invite? =
    (PeerInvite.decode(text) as? PeerInvite.Decoded.Ph2)?.invite

internal fun dhKeypair(): X25519.Keypair = X25519.generateKeypair()

internal fun b64(bytes: ByteArray): String =
    java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)
