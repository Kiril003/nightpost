package local.phantom.companion.core.net.peer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Кодек «листа собі» на тому самому векторі, що й pytest ПК
 * (`phantom-os/src/backend/tests/test_peer_self_codec.py`).
 */
class PeerSelfVectorTest {

    private val v: JsonObject = run {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("peer_self_vector.json"))
        Json.parseToJsonElement(stream.reader(Charsets.UTF_8).readText()).jsonObject
    }

    private fun ops(arr: JsonArray) = arr.map {
        val o = it.jsonObject
        PeerSelf.Operation(o["op"]!!.jsonPrimitive.content, o["at"]!!.jsonPrimitive.long, o["d"]!!.jsonObject)
    }

    private val pack get() = v["pack"]!!.jsonObject.let {
        PeerSelf.Pack(it["origin"]!!.jsonPrimitive.content, it["seq"]!!.jsonPrimitive.long, ops(it["ops"]!!.jsonArray))
    }

    @Before fun registerVectorKinds() {
        for (wire in listOf("line", "read", "place", "bye")) {
            PeerSelf.register(PeerSelf.lastWriterWins(wire) { it.data["id"]?.jsonPrimitive?.content ?: wire })
        }
    }

    @Test fun `мітка кадру та сама, що у векторі`() {
        assertEquals(v["tag"]!!.jsonPrimitive.content, PeerSelf.TAG)
    }

    @Test fun `кадр — байт у байт як у векторі`() {
        assertEquals(v["frame"]!!.jsonPrimitive.content, PeerSelf.encode(pack))
    }

    @Test fun `кадр читається назад у той самий пакет`() {
        assertEquals(pack, PeerSelf.decode(v["frame"]!!.jsonPrimitive.content))
    }

    @Test fun `невідомий рід від новішої збірки читається, але не складається`() {
        val decoded = PeerSelf.decode(v["unknown_op_frame"]!!.jsonPrimitive.content)!!
        assertEquals("future", decoded.ops.single().op)
        assertFalse(decoded.ops.single().known)
        assertTrue(PeerSelf.fold(decoded.ops).isEmpty())
        assertNull(PeerSelf.encode(decoded))
    }

    @Test fun `криві кадри — null, не виняток`() {
        for (bad in v["invalid"]!!.jsonArray) assertNull(bad.toString(), PeerSelf.decode(bad.jsonPrimitive.content))
    }

    @Test fun `ділення на пакети — як у векторі і в межах розміру`() {
        val s = v["split"]!!.jsonObject
        val max = s["max_bytes"]!!.jsonPrimitive.int
        val packs = PeerSelf.pack(
            s["origin"]!!.jsonPrimitive.content,
            s["first_seq"]!!.jsonPrimitive.long,
            ops(s["ops"]!!.jsonArray),
            max,
        )
        val expected = s["expected"]!!.jsonArray.map {
            it.jsonObject["seq"]!!.jsonPrimitive.long to it.jsonObject["count"]!!.jsonPrimitive.int
        }
        assertEquals(expected, packs.map { it.seq to it.ops.size })
        for (p in packs) assertTrue(PeerSelf.encode(p)!!.toByteArray(Charsets.UTF_8).size <= max)
    }
}
