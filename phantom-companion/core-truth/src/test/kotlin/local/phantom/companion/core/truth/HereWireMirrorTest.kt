package local.phantom.companion.core.truth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ті самі файли лежать у `platform-site/server/tests/vectors/` і читаються
 * `test_truth_here_mirror.py`; той тест і стереже, що копії однакові.
 */
class HereWireMirrorTest {

    private fun resource(name: String): JsonObject =
        Json.parseToJsonElement(checkNotNull(javaClass.classLoader?.getResourceAsStream(name)).reader().readText()).jsonObject

    @Test fun `комірка телефона — та сама, що в h3-js, на кожній точці`() {
        val rows = resource("h3_vectors.json")["rows"]!!.jsonArray
        assertTrue("векторів ${rows.size}", rows.size >= 1000)
        val wrong = rows.map { it.jsonArray }.filter { r ->
            val lat = r[0].jsonPrimitive.double
            val lng = r[1].jsonPrimitive.double
            val r8 = r[2].jsonPrimitive.content
            H3.latLngToCell(lat, lng, 8) != r8 ||
                H3.latLngToCell(lat, lng, 6) != r[3].jsonPrimitive.content ||
                H3.parent(r8, 6) != r[4].jsonPrimitive.content ||
                H3.area(H3.cell(lat, lng)) != r[4].jsonPrimitive.content
        }
        assertEquals("розійшлись: ${wrong.take(5)}", 0, wrong.size)
    }

    @Test fun `підписане речення — слово в слово observe_message сервера`() {
        val cases = resource("truth_observe_vectors.json")["cases"]!!.jsonArray
        assertTrue(cases.size >= 4)
        for (c in cases.map { it.jsonObject }) {
            fun str(k: String) = c[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
            val presence = (c["presence"] as? JsonArray)?.let { it[0].jsonPrimitive.long to it[1].jsonPrimitive.long }
            assertEquals(
                str("sentence"),
                TruthWire.observeMessage(
                    str("pub_key")!!, str("nonce")!!, c["issued_ms"]!!.jsonPrimitive.long, str("cell")!!,
                    str("object"), str("kind")!!, str("claim")!!, str("reading"), presence, str("note"),
                ),
            )
        }
    }
}
