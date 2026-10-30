package local.phantom.companion.core.license

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LicenseKeyTest {

    private val real = "PHTM-4ZQ8M-K2WVX-J7T0N-" + checksumOf("4ZQ8MK2WVXJ7T0N")

    @Test
    fun `our own key passes`() {
        assertTrue(isWellFormed(real))
    }

    @Test
    fun `a typo dies here, not on the server`() {
        val typo = real.dropLast(1) + if (real.last() == 'A') 'B' else 'A'
        assertFalse(isWellFormed(typo))
        assertFalse(isWellFormed("PHTM-4ZQ8M-K2WVX-J7T0"))
        assertFalse(isWellFormed("HELLO"))
    }

    @Test
    fun `spaces, case and the long dash people paste do not break it`() {
        val messy = " phtm–" + real.removePrefix("PHTM-").lowercase().replace("-", "-")
        assertTrue(isWellFormed(messy.replace(" ", "")))
        assertEquals(LicenseKey.normalize(real), LicenseKey.normalize(real.lowercase()))
    }

    @Test
    fun `the raw key is never what we store`() {
        val hash = LicenseKey.hash(real)
        assertEquals(64, hash.length)
        assertFalse(hash.contains("4ZQ8M"))
    }

    private fun isWellFormed(key: String) = LicenseKey.isWellFormed(key)

    private fun checksumOf(body: String): String {
        val crockford = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(body.toByteArray(Charsets.UTF_8))
        return digest.take(5).joinToString("") { crockford[(it.toInt() and 0xFF) % 32].toString() }
    }
}
