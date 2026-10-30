package local.phantom.companion.core.solana

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Keystore тут — підробка з тією самою межею: привʼязаний ключ мертвий поза вікном після PIN/відбитка. */
class SeedBoxTest {

    private val words = "abandon ability able about above absent absorb abstract absurd abuse access accident"

    private class Lock(private val mark: Byte, private val needsUnlock: Boolean) : SeedLock {
        var unlocked = false
        var lies = false
        var dropped = false
        var opens = 0
        var seals = 0

        private fun gate() {
            if (needsUnlock && !unlocked) throw SecurityException("user not authenticated")
            if (dropped) throw IllegalStateException("key gone")
        }

        override fun seal(plain: ByteArray): ByteArray {
            seals++
            dropped = false
            gate()
            return byteArrayOf(mark) + plain.map { (it.toInt() xor mark.toInt()).toByte() }
        }

        override fun open(blob: ByteArray): ByteArray {
            opens++
            gate()
            check(blob[0] == mark) { "other key" }
            val out = blob.drop(1).map { (it.toInt() xor mark.toInt()).toByte() }.toByteArray()
            return if (lies) out.reversedArray() else out
        }

        override fun drop() {
            dropped = true
        }
    }

    private class Shelf : SeedShelf {
        val blobs = mutableMapOf<String, ByteArray>()
        var broken = false

        override fun get(name: String) = blobs[name]

        override fun write(put: Map<String, ByteArray>, remove: Set<String>): Boolean {
            if (broken) return false
            remove.forEach { blobs.remove(it) }
            blobs.putAll(put)
            return true
        }
    }

    private val shelf = Shelf()
    private val legacy = Lock(0x11, needsUnlock = false)
    private val bound = Lock(0x5A, needsUnlock = true)
    private var screenLock = true
    private val box = SeedBox(shelf, legacy, bound) { screenLock }

    private fun oldWallet() {
        shelf.blobs[SeedBox.LEGACY] = legacy.seal(words.toByteArray())
    }

    private fun refused(why: String, block: () -> Unit) {
        try {
            block()
            fail("the seed went through without an unlock")
        } catch (e: Exception) {
            assertEquals(why, e.message)
        }
    }

    @Test
    fun `old wallet moves under the bound key on the first real unlock and the old key goes`() {
        oldWallet()
        bound.unlocked = true
        assertEquals(words, box.open())
        assertNull(shelf.blobs[SeedBox.LEGACY])
        assertNotNull(shelf.blobs[SeedBox.BOUND])
        assertTrue(legacy.dropped)
        assertEquals(words, box.open())
    }

    @Test
    fun `once bound the seed does not open without the unlock and never falls back to the old key`() {
        oldWallet()
        bound.unlocked = true
        box.open()
        bound.unlocked = false
        val legacyOpens = legacy.opens
        refused(VaultLocked.WHY) { box.open() }
        assertEquals(legacyOpens, legacy.opens)
    }

    @Test
    fun `an old seed does not open without a fresh unlock and stays where it was`() {
        oldWallet()
        val before = shelf.blobs[SeedBox.LEGACY]
        refused(VaultLocked.WHY) { box.open() }
        assertArrayEquals(before, shelf.blobs[SeedBox.LEGACY])
        assertNull(shelf.blobs[SeedBox.BOUND])
        assertFalse(legacy.dropped)
    }

    @Test
    fun `an old seed on a phone with no screen lock is left untouched and not opened`() {
        oldWallet()
        screenLock = false
        val before = shelf.blobs[SeedBox.LEGACY]
        val opens = legacy.opens
        refused("no_screen_lock") { box.open() }
        assertArrayEquals(before, shelf.blobs[SeedBox.LEGACY])
        assertEquals(opens, legacy.opens)
        assertFalse(legacy.dropped)
        assertTrue(box.exists)
    }

    @Test
    fun `a bound key that gives back other bytes never replaces the old blob`() {
        oldWallet()
        bound.unlocked = true
        bound.lies = true
        refused(VaultLocked.WHY) { box.open() }
        assertNotNull(shelf.blobs[SeedBox.LEGACY])
        assertNull(shelf.blobs[SeedBox.BOUND])
        assertFalse(legacy.dropped)
    }

    @Test
    fun `a failed write keeps the old blob and the old key`() {
        oldWallet()
        bound.unlocked = true
        shelf.broken = true
        assertEquals(words, box.open())
        assertNotNull(shelf.blobs[SeedBox.LEGACY])
        assertFalse(legacy.dropped)
    }

    @Test
    fun `without a screen lock no wallet is made at all`() {
        screenLock = false
        bound.unlocked = true
        refused("no_screen_lock") { box.seal(words) }
        assertTrue(shelf.blobs.isEmpty())
        assertEquals(0, legacy.seals)
        assertEquals(0, bound.seals)
        assertFalse(box.exists)
    }

    @Test
    fun `a lock added later binds the old seed at the next unlock`() {
        oldWallet()
        screenLock = false
        refused("no_screen_lock") { box.open() }
        screenLock = true
        bound.unlocked = true
        assertEquals(words, box.open())
        assertNull(shelf.blobs[SeedBox.LEGACY])
        assertNotNull(shelf.blobs[SeedBox.BOUND])
    }

    @Test
    fun `a wallet made right after an unlock is bound from the start`() {
        bound.unlocked = true
        box.seal(words)
        assertNull(shelf.blobs[SeedBox.LEGACY])
        assertNotNull(shelf.blobs[SeedBox.BOUND])
        assertTrue(box.exists)
    }

    @Test
    fun `a wallet made with no fresh unlock is refused, never parked on the key without auth`() {
        val legacySeals = legacy.seals
        refused(VaultLocked.WHY) { box.seal(words) }
        assertTrue(shelf.blobs.isEmpty())
        assertEquals(legacySeals, legacy.seals)
    }

    @Test
    fun `restoring words without an unlock keeps the current seed and never leaves two`() {
        bound.unlocked = true
        box.seal(words)
        bound.unlocked = false
        val other = "zoo zone zero youth young yellow year wrong write wrist worth world"
        refused(VaultLocked.WHY) { box.seal(other) }
        assertNull(shelf.blobs[SeedBox.LEGACY])
        bound.unlocked = true
        assertEquals(words, box.open())
        box.seal(other)
        assertEquals(other, box.open())
        assertNull(shelf.blobs[SeedBox.LEGACY])
    }
}
