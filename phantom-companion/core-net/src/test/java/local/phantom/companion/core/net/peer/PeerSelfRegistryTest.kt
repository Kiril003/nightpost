package local.phantom.companion.core.net.peer

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Модуль додає свій рід разом зі злиттям, не правлячи кодека; невідомий рід
 * від новішої збірки не валить стару.
 */
class PeerSelfRegistryTest {

    /** Рід «зовнішнього модуля»: тримає найбільше значення на ключ. */
    private object Highest : PeerSelf.Kind {
        override val wire = "test_highest"
        override fun key(op: PeerSelf.Operation) = op.data["k"]!!.jsonPrimitive.content
        override fun merge(a: PeerSelf.Operation, b: PeerSelf.Operation) =
            if (a.data["n"]!!.jsonPrimitive.long >= b.data["n"]!!.jsonPrimitive.long) a else b
    }

    private fun op(wire: String, k: String, n: Long, at: Long = n) =
        PeerSelf.Operation(wire, at, JsonObject(mapOf("k" to JsonPrimitive(k), "n" to JsonPrimitive(n))))

    @Test fun `зареєстрований модулем рід складається, читається і зливається`() {
        PeerSelf.register(Highest)
        val ops = listOf(op("test_highest", "a", 3), op("test_highest", "a", 9), op("test_highest", "b", 1))
        val frame = PeerSelf.encode(PeerSelf.Pack("e4224c6eea5fb652", 1, ops))
        assertNotNull(frame)
        val folded = PeerSelf.fold(PeerSelf.decode(frame!!)!!.ops)
        assertEquals(9L, folded["test_highest" to "a"]!!.data["n"]!!.jsonPrimitive.long)
        assertEquals(1L, folded["test_highest" to "b"]!!.data["n"]!!.jsonPrimitive.long)
    }

    @Test fun `порядок і повтор не міняють злиття`() {
        PeerSelf.register(Highest)
        val lww = PeerSelf.lastWriterWins("test_lww") { it.data["k"]!!.jsonPrimitive.content }
        PeerSelf.register(lww)
        val ops = listOf(
            op("test_highest", "a", 3), op("test_highest", "a", 9), op("test_lww", "x", 5, at = 10),
            op("test_lww", "x", 7, at = 10), op("test_lww", "x", 1, at = 20),
        )
        val expected = PeerSelf.fold(ops)
        for (seed in 1..20) {
            val shuffled = (ops + ops.shuffled(java.util.Random(seed.toLong()))).shuffled(java.util.Random(seed * 31L))
            assertEquals(expected, PeerSelf.fold(shuffled))
        }
    }

    @Test fun `незареєстрований рід не складається і тихо пропускається`() {
        val stranger = op("test_never_registered", "a", 1)
        assertNull(PeerSelf.encode(PeerSelf.Pack("e4224c6eea5fb652", 1, listOf(stranger))))
        PeerSelf.register(Highest)
        val folded = PeerSelf.fold(listOf(stranger, op("test_highest", "a", 2)))
        assertEquals(setOf("test_highest" to "a"), folded.keys)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `рід із кривим іменем не реєструється`() {
        PeerSelf.register(PeerSelf.lastWriterWins("PH-SLF:") { "x" })
    }
}
