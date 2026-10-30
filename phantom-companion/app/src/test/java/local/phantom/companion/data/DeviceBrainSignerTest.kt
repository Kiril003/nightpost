package local.phantom.companion.data

import local.phantom.companion.core.ai.route.NightpostBrainRoute
import local.phantom.companion.core.net.crypto.Ed25519
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceBrainSignerTest {

    @Test
    fun `підпис запиту до розуму сервер перевіряє ключем пристрою`() {
        val pair = Ed25519.generateKeypair()
        val pub = java.util.Base64.getEncoder().withoutPadding().encodeToString(pair.publicKey)
        val signer = DeviceBrainSigner { DeviceIdentityStore.Identity("id", pub, pair.privateKey, "", ByteArray(32)) }
        val msg = "${NightpostBrainRoute.BRAIN_SIG_PREFIX}|$pub|${"0".repeat(32)}|1760000000000|status|${"e".repeat(64)}"
            .toByteArray(Charsets.UTF_8)

        val sig = java.util.Base64.getDecoder().decode(signer.sign(msg))

        assertTrue(Ed25519.verify(pair.publicKey, msg, sig))
    }
}
