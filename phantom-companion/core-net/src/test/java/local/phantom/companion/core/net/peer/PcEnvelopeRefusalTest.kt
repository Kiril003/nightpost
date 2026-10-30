package local.phantom.companion.core.net.peer

import java.io.File
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Конверт компʼютера впізнається як чужий рід — і НЕ стає контактом.
 *
 * Скло ПК склеює `phantom://invite/` з base64url двійкового конверта: перші
 * три байти `PHI`, далі версія, довжини, ключ. Двокрапок там немає, тож розбір
 * давав `parts.size == 2`, і телефон казав «код запрошення неповний — попроси
 * надіслати ще раз». Код був цілий і з нашого ж ПК; людина йшла просити
 * переслати те саме.
 *
 * Головне тут не текст, а безпека: якби конверт десь приймався як контакт,
 * зʼявилася б друга дорога ПК↔телефон повз доказ спарування, де сітьові ключі
 * їдуть усередині `mobile-pair-v1`. Тому сторож валить не «текст не той», а
 * «чужий конверт узагалі пройшов».
 */
class PcEnvelopeRefusalTest {

    /** Справжня форма з ПК: `PHI` + версія + довжина ключа + ключ + адреса + імʼя. */
    private fun pcEnvelope(name: String = "PHANTOM PC", host: String = "10.0.0.7:8443"): String {
        val key = ByteArray(32) { (it * 7).toByte() }
        val out = ArrayList<Byte>()
        out += 'P'.code.toByte(); out += 'H'.code.toByte(); out += 'I'.code.toByte()
        out += 1.toByte()
        out += (key.size shr 8).toByte(); out += (key.size and 0xFF).toByte()
        out += key.toList()
        val h = host.toByteArray(); out += h.size.toByte(); out += h.toList()
        val n = name.toByteArray(); out += n.size.toByte(); out += n.toList()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    @Test
    fun `a pc envelope is recognised as another kind, not as a broken invite`() {
        assertEquals(PeerInvite.Decoded.PcEnvelope, PeerInvite.decode(pcEnvelope()))
        assertEquals(
            "довший конверт із іншим імʼям теж має впізнаватись",
            PeerInvite.Decoded.PcEnvelope,
            PeerInvite.decode(pcEnvelope(name = "Робочий стіл Кирила", host = "192.168.1.50:8443")),
        )
    }

    @Test
    fun `no road turns the envelope into a contact`() {
        val decoded = PeerInvite.decode(pcEnvelope())
        assertTrue(
            "конверт ПК прийнято як запрошення — зʼявилась друга дорога повз спарування",
            decoded !is PeerInvite.Decoded.Ph2,
        )
        assertNotEquals(PeerInvite.Decoded.LegacyPh1, decoded)
        // Стан навмисно без полів: приймати нічого.
        assertTrue(decoded is PeerInvite.Decoded.PcEnvelope)
    }

    @Test
    fun `a foreign blob with other first bytes stays not-an-invite`() {
        // Зустрічний: сторож мусить уміти почервоніти. Ті самі довжина й форма,
        // інші перші байти — це просто чужий текст, і відповідь інша.
        val alien = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(byteArrayOf('X'.code.toByte(), 'Y'.code.toByte(), 'Z'.code.toByte()) + ByteArray(40))
        assertEquals(PeerInvite.Decoded.NotInvite, PeerInvite.decode(alien))
        assertEquals(PeerInvite.Decoded.NotInvite, PeerInvite.decode("просто текст без коду"))
        assertEquals(PeerInvite.Decoded.NotInvite, PeerInvite.decode(""))
    }

    @Test
    fun `the phone answers with the language and the road, in both tongues`() {
        val root = repoRoot()
        val activity = File(root, "app/src/main/java/local/phantom/companion/MainActivity.kt").readText()
        assertTrue("активність не знає нового стану", activity.contains("Decoded.PcEnvelope ->"))
        assertTrue("відмова знову каже «неповний»", activity.contains("R.string.invite_link_pc"))

        listOf("values-uk", "values").forEach { dir ->
            val xml = File(root, "app/src/main/res/$dir/strings.xml").readText()
            assertTrue("$dir не має invite_link_pc", xml.contains("name=\"invite_link_pc\""))
        }
        val uk = File(root, "app/src/main/res/values-uk/strings.xml").readText()
        val line = uk.substringAfter("name=\"invite_link_pc\">").substringBefore("</string>")
        // Мова І дорога в одному реченні: що це за код і що з ним робити.
        // Слово шукаємо без апострофа: у ресурсах він ASCII та екранований
        // (`компʼютера`), і сторож не має падати від способу його написання —
        // саме на цьому він і почервонів, коли я привела рядок до стилю файлу.
        assertTrue("відмова не називає рід коду", Regex("комп.{0,2}ютер|ПК").containsMatchIn(line))
        assertTrue("відмова не називає дорогу", line.contains("спарувати") || line.contains("QR") || line.contains("Пристрої"))
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "core-net").isDirectory) dir = dir.parentFile
        return dir ?: throw AssertionError("корінь репозиторію не знайдено")
    }
}
