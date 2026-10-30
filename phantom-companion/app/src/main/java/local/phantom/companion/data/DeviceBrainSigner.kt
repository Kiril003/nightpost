package local.phantom.companion.data

import local.phantom.companion.core.ai.route.BrainRequestSigner
import local.phantom.companion.core.net.crypto.Ed25519

/**
 * Підписує запити до `/api/brain` ключем особи пристрою ([DeviceIdentityStore]),
 * ніколи не торкаючись гаманця Solana.
 */
class DeviceBrainSigner internal constructor(
    private val identity: () -> DeviceIdentityStore.Identity,
) : BrainRequestSigner {

    constructor(identityStore: DeviceIdentityStore) : this(identityStore::get)

    override val publicKeyB64: String
        get() = identity().publicKeyB64

    override fun sign(message: ByteArray): String =
        java.util.Base64.getEncoder().withoutPadding().encodeToString(Ed25519.sign(identity().privateKey, message))
}
