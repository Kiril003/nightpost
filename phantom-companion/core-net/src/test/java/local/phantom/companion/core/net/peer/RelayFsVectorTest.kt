package local.phantom.companion.core.net.peer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Конверт v5 на векторі, який рахує ПК (`phantom-os/src/backend/scripts/relay_fs_vector.py`,
 * звіряє `tests/test_relay_fs.py`). Одне ядро — це однакові байти, а не однакові назви.
 */
class RelayFsVectorTest {

    private val v: JsonObject = run {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("relay_fs_vector.json"))
        Json.parseToJsonElement(stream.reader(Charsets.UTF_8).readText()).jsonObject
    }

    private fun s(vararg path: String): String {
        var node = v
        for (i in 0 until path.size - 1) node = node[path[i]]!!.jsonObject
        return node[path.last()]!!.jsonPrimitive.content
    }

    private fun b(vararg path: String) = Base64.getDecoder().decode(s(*path))
    private val a get() = RelayFs.Identity(b("a", "ed"), b("a", "dh"))
    private val bb get() = RelayFs.Identity(b("b", "ed"), b("b", "dh"))
    private val spkId get() = v["spk"]!!.jsonObject["id"]!!.jsonPrimitive.long
    private val opkId get() = v["opk"]!!.jsonObject["id"]!!.jsonPrimitive.long

    @Test fun `мітки X3DH і листа — ті самі, що на ПК`() {
        assertEquals(s("x3dh_info"), String(RelayFs.X3DH_INFO))
        assertEquals(s("letter_info"), String(RelayFs.LETTER_INFO))
        assertTrue("підпис SPK контекстом ПК", RelayFs.verifySpk(bb.ed, spkId, b("spk", "pub"), b("spk", "sig")))
        assertArrayEquals(b("spk", "sig"), RelayFs.signSpk(b("b", "ed_priv"), spkId, b("spk", "pub")))
    }

    @Test fun `телефон запечатує байт у байт як ПК — з OPK і лише з SPK`() {
        val plain = s("plaintext").toByteArray(Charsets.UTF_8)
        val withOpk = RelayFs.seal(plain, "aaaa", "bbbb", a, b("a", "dh_priv"), bb, spkId, b("spk", "pub"), opkId, b("opk", "pub"), b("with_opk", "ek_priv"))
        assertEquals(s("with_opk", "frame"), withOpk)
        val spkOnly = RelayFs.seal(plain, "aaaa", "bbbb", a, b("a", "dh_priv"), bb, spkId, b("spk", "pub"), null, null, b("spk_only", "ek_priv"))
        assertEquals(s("spk_only", "frame"), spkOnly)
    }

    @Test fun `телефон відчиняє кадри ПК`() {
        for (case in listOf("with_opk", "spk_only")) {
            val got = RelayFs.open(s(case, "frame"), bb, b("b", "dh_priv"), a, { b("spk", "priv") }, { b("opk", "priv") })
            assertNotNull(case, got)
            assertEquals(s("plaintext"), String(got!!.plaintext, Charsets.UTF_8))
        }
        assertNull(RelayFs.open(s("with_opk", "frame"), bb, b("b", "dh_priv"), a, { b("spk", "priv") }, { null }))
    }
}
