package local.phantom.companion.data

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerProfile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfFaceTest {

    private val dir: File = Files.createTempDirectory("self-face").toFile()
    private var active: String? = "me-1"
    private var stored = "Kyrylo"
    private val wire = mutableListOf<Pair<String, String>>()
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(200) { it.toByte() }

    private val roster = listOf(
        PeerIdentity(id = "ana", name = "ana", kind = PeerIdentity.Kind.PERSON, pubKey = "pk", dhPubKey = "dh", introducedAtMs = 1L),
    )

    private fun herald() = ProfileHerald(
        dir = dir,
        legacyRev = { 0L },
        people = { roster },
        blocked = { emptySet() },
        name = { stored },
        handle = { PeerProfile.Field.Cleared },
        send = { id, frame -> wire += id to frame; true },
    )

    private fun face(): SelfFace {
        val h = herald()
        return SelfFace(
            dir = dir,
            activeId = { active },
            rename = { n -> (n != stored).also { stored = n } },
            herald = { h },
        )
    }

    @Test
    fun `обкладинка переживає перезапуск і належить своїй особі`() = runTest {
        assertNull(face().cover())
        assertTrue(face().setCover(jpeg))
        assertArrayEquals(jpeg, face().cover()!!.readBytes())
        active = "me-2"
        assertNull("друга особа бачить чужу обкладинку", face().cover())
        active = "me-1"
        assertTrue(face().clearCover())
        assertNull(face().cover())
    }

    @Test
    fun `без особи обкладинку не пишемо нікуди`() = runTest {
        active = null
        assertFalse(face().setCover(jpeg))
        assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith("cover-") })
    }

    @Test
    fun `імʼя на місці йде в базу і кадром людям, порожнє відкидається`() = runTest {
        assertNull(face().setName("   "))
        assertEquals("Kyrylo", stored)
        assertEquals("Ira", face().setName("  Ira "))
        assertEquals("Ira", stored)
        val got = PeerProfile.decode(wire.last { it.first == "ana" }.second)!!
        assertEquals(PeerProfile.Field.Set("Ira"), got.name)
    }

    @Test
    fun `фото на місці лягає туди, звідки його читають вкладка і веб`() {
        val thumb = jpeg.copyOf(40)
        assertTrue(face().setPhoto(thumb, jpeg))
        assertArrayEquals(jpeg, herald().ownPhotoFile()!!.readBytes())
        val got = PeerProfile.decode(wire.last { it.first == "ana" }.second)!!
        assertArrayEquals(jpeg, (got.photo as PeerProfile.Field.Set).value)
    }

    @Test
    fun `фото зі старого екрана переїжджає один раз і не перебиває нове`() {
        var loads = 0
        val load: (String) -> Pair<ByteArray, ByteArray>? = { loads++; jpeg.copyOf(40) to jpeg }
        assertFalse(face().adoptLegacyPhoto(null, load))
        assertTrue(face().adoptLegacyPhoto("content://old/1", load))
        assertArrayEquals(jpeg, herald().ownPhotoFile()!!.readBytes())
        assertFalse("перенос повторився поверх уже перенесеного", face().adoptLegacyPhoto("content://old/1", load))
        assertEquals(1, loads)
    }

    @Test
    fun `мертвий старий uri нічого не стирає`() {
        assertFalse(face().adoptLegacyPhoto("content://gone") { error("revoked") })
        assertNull(herald().ownPhotoFile())
    }

    @Test
    fun `після очищення фото старий uri не воскрешає його`() {
        val f = face()
        val thumb = jpeg.copyOf(40)
        assertTrue(f.setPhoto(thumb, jpeg))
        assertTrue(f.clearPhoto())
        assertNull(herald().ownPhotoFile())
        assertFalse(f.adoptLegacyPhoto("content://old/1") { thumb to jpeg })
        assertNull(herald().ownPhotoFile())
    }

    @Test
    fun `статус і про себе правляться на місці й їдуть кадром`() {
        val f = face()
        assertTrue(f.setAbout("Тут", "Інженер"))
        assertEquals("Тут", herald().statusText)
        assertEquals("Інженер", herald().bio)
        val got = PeerProfile.decode(wire.last { it.first == "ana" }.second)!!
        assertEquals(PeerProfile.Field.Set("Тут"), got.statusText)
        assertEquals(PeerProfile.Field.Set("Інженер"), got.bio)
    }

    @Test
    fun `fit бере найбільший розмір, що влазить у стелю`() {
        val asked = mutableListOf<Int>()
        val out = SelfFace.fit(1000, listOf(512, 256)) { side, q -> asked += side; ByteArray(side * q / 20) }
        assertEquals(256 * 70 / 20, out!!.size)
        assertTrue(512 in asked)
        assertNull(SelfFace.fit(10, listOf(64)) { _, _ -> ByteArray(11) })
    }
}
