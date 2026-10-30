package local.phantom.companion.core.solana

import kotlinx.coroutines.test.runTest
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Підпис закритого речення гаманцем — для балів. Доводиться порядок і межі:
 * спершу згода, потім ключ; відмова не розшифровує нічого; зовнішній гаманець
 * тут мовчки не «підписує» — він чесно каже, що не вміє; а підпис справжній,
 * його звіряє той самий Ed25519, що й сервер.
 */
class WalletStatementTest {

    private val seed = ByteArray(32) { (it + 32).toByte() }
    private val sentence = "phantom-points-settled|v2|…".toByteArray()

    /** Звіряє так само, як це робить сервер: чистий Ed25519 над байтами речення. */
    private fun verifies(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        Ed25519Signer().apply { init(false, Ed25519PublicKeyParameters(publicKey, 0)); update(message, 0, message.size) }
            .verifySignature(signature)

    @Test
    fun `згода раніше за ключ, а підпис справжній`() = runTest {
        val order = mutableListOf<String>()
        val outcome = WalletStatement.run(
            kind = WalletKind.BUILT_IN,
            confirm = { order += "confirm"; true },
            keypair = { order += "keypair"; Ed25519Keypair(seed) },
        ) { sign -> order += "use"; sign(sentence) }
        assertEquals(listOf("confirm", "keypair", "use"), order)
        val signature = (outcome as StatementOutcome.Signed).value
        assertEquals(64, signature.size)
        val publicKey = Ed25519Keypair(seed).publicKey.bytes
        assertTrue("підпис гаманця не зійшовся з ключем", verifies(publicKey, sentence, signature))
        // Той самий підписувач не підписує чужого речення тією самою відповіддю.
        assertFalse(verifies(publicKey, "інше речення".toByteArray(), signature))
    }

    @Test
    fun `відмова у відбитку не розшифровує ключа і не кличе виклик`() = runTest {
        var touched = false
        val outcome = WalletStatement.run(
            kind = WalletKind.BUILT_IN,
            confirm = { false },
            keypair = { touched = true; Ed25519Keypair(seed) },
        ) { touched = true; ByteArray(0) }
        assertEquals(StatementOutcome.Declined, outcome)
        assertFalse("ключ розшифровано без згоди", touched)
    }

    @Test
    fun `Keystore не прийняв розблокування — це відмова, а не падіння`() = runTest {
        var used = false
        val outcome = WalletStatement.run(
            kind = WalletKind.BUILT_IN,
            confirm = { true },
            keypair = { throw VaultLocked() },
        ) { used = true; ByteArray(0) }
        assertEquals(StatementOutcome.Declined, outcome)
        assertFalse(used)
    }

    @Test
    fun `зовнішній гаманець не підписує речень і навіть не питає відбитка`() = runTest {
        var asked = false
        val outcome = WalletStatement.run(
            kind = WalletKind.EXTERNAL,
            confirm = { asked = true; true },
            keypair = { fail("зовнішній ключ не наш") as Nothing },
        ) { fail("підписувати нічим") as Nothing }
        assertEquals(StatementOutcome.Unsupported, outcome)
        assertFalse(asked)
    }

    @Test
    fun `без гаманця — NoWallet`() = runTest {
        val outcome = WalletStatement.run(
            kind = null,
            confirm = { fail("нікого питати") as Nothing },
            keypair = { fail("нічого розшифровувати") as Nothing },
        ) { fail("підписувати нічим") as Nothing }
        assertEquals(StatementOutcome.NoWallet, outcome)
    }

    @Test
    fun `виклик отримує підписувач лише на свій час — повертає його власне значення`() = runTest {
        val outcome = WalletStatement.run(
            kind = WalletKind.BUILT_IN,
            confirm = { true },
            keypair = { Ed25519Keypair(seed) },
        ) { sign -> sign(sentence).size to sentence.size }
        assertEquals(64 to sentence.size, (outcome as StatementOutcome.Signed).value)
    }
}
