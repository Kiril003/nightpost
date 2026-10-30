package local.phantom.companion.core.net.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PinHasherTest {

    private val cheap = PinHasher.Params.Test

    @Test fun `hash then verify with correct PIN returns true`() {
        val encoded = PinHasher.hash("123456", cheap)
        assertTrue(PinHasher.verify("123456", encoded))
    }

    @Test fun `verify rejects the wrong PIN`() {
        val encoded = PinHasher.hash("123456", cheap)
        assertFalse(PinHasher.verify("123457", encoded))
        assertFalse(PinHasher.verify("000000", encoded))
        assertFalse(PinHasher.verify("12345", encoded))
    }

    @Test fun `encoded string has the PHC prefix and the parameters`() {
        val encoded = PinHasher.hash("234567", cheap)
        assertTrue(
            "expected PHC argon2id prefix, got: $encoded",
            encoded.startsWith("\$argon2id\$v=19\$m=${cheap.memoryKiB},t=${cheap.iterations},p=${cheap.parallelism}\$"),
        )
        // 6 segments separated by '$': "" / argon2id / v=19 / m,t,p / salt / hash
        assertEquals(6, encoded.split('$').size)
    }

    @Test fun `two hashes of the same PIN differ because the salt is fresh`() {
        val a = PinHasher.hash("424242", cheap)
        val b = PinHasher.hash("424242", cheap)
        assertNotEquals(a, b)
        // …but both should still verify against the original PIN.
        assertTrue(PinHasher.verify("424242", a))
        assertTrue(PinHasher.verify("424242", b))
    }

    @Test fun `same PIN + same salt is deterministic (audit log replayability)`() {
        val salt = ByteArray(cheap.saltLength) { it.toByte() }
        val a = PinHasher.hash("567890", cheap, salt = salt)
        val b = PinHasher.hash("567890", cheap, salt = salt)
        assertEquals(a, b)
    }

    @Test fun `hash rejects PIN shorter than the policy floor`() {
        // Стеля 12.09 опустилась із шести до чотирьох — троє тестувальників
        // 11.09, «пинкод 4 знака». Три цифри лишаються відмовою.
        try {
            PinHasher.hash("123", cheap)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test fun `чотири цифри приймаються, шість — стеля знайомства`() {
        assertTrue(PinHasher.hash("1234", cheap).isNotBlank())
        assertEquals(4, PinHasher.MIN_PIN_LENGTH)
        assertEquals(6, PinHasher.MAX_PIN_LENGTH)
    }

    @Test fun `hash rejects mismatched explicit salt length`() {
        try {
            PinHasher.hash("123456", cheap, salt = ByteArray(8))
            fail("expected IllegalArgumentException for short salt")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test fun `verify returns false on malformed encoded string`() {
        assertFalse(PinHasher.verify("123456", ""))
        assertFalse(PinHasher.verify("123456", "\$argon2id\$v=19\$m=64,t=1,p=1\$nope"))
        assertFalse(PinHasher.verify("123456", "\$bcrypt\$v=2a\$"))
        assertFalse(PinHasher.verify("123456", "not-a-phc-string"))
    }

    @Test fun `verify is constant-time-ish (does not throw on length mismatch)`() {
        // The constant-time helper handles unequal lengths gracefully — we just
        // confirm it doesn't crash. Real timing properties are a code-review
        // concern, not a unit-test concern.
        val encoded = PinHasher.hash("999999", cheap)
        // Truncate the trailing hash segment to force a length mismatch path.
        val parts = encoded.split('$').toMutableList()
        parts[5] = parts[5].substring(0, parts[5].length / 2)
        val mangled = parts.joinToString("\$")
        assertFalse(PinHasher.verify("999999", mangled))
    }

    @Test fun `Params rejects nonsense values`() {
        try {
            PinHasher.Params(memoryKiB = 1024, iterations = 1, parallelism = 1, hashLength = 32, saltLength = 16)
            fail("expected IllegalArgumentException for tiny memory cost")
        } catch (_: IllegalArgumentException) { /* expected */ }

        try {
            PinHasher.Params(memoryKiB = 65_536, iterations = 0, parallelism = 1, hashLength = 32, saltLength = 16)
            fail("expected IllegalArgumentException for zero iterations")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }
}
