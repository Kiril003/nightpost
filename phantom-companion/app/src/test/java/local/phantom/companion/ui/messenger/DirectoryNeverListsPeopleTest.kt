package local.phantom.companion.ui.messenger

import java.io.File
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.peer.PeerDirectory
import local.phantom.companion.core.net.peer.PeerIntro
import local.phantom.companion.core.net.peer.PeerInvite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Довідник не вміє віддати список людей — і це доводиться, а не обіцяється.
 *
 * Питання власника було саме про це: «список всіх користувачів?? щоб мошеники
 * по всім працювали??». Відповідь тримається на двох речах, і жодна з них не
 * правило доступу. Перша — форма видачі: [PeerDirectory.rows] має рівно дві
 * гілки, порожню й одну, і другої тут не написано. Друга — досяжність: міст
 * будує масив ЛИШЕ з цієї функції й ніде не бере реєстр, тож навіть маючи всіх
 * знайомих під рукою, він їх у відповідь не покладе.
 *
 * Перевірка форми була б зеленою і в мертвому коді, тому обидві потрібні.
 *
 * Сама поведінка JSON тут не ганяється: `org.json` у `:app` — заглушка
 * android.jar, і на JVM вона кидає, а не відповідає. Тому кадр перевіряється
 * джерелом, а числа «нуль або один» — справжнім викликом чистої функції.
 *
 * Читає чужі файли як сирий ввід-вивід — невидимо для up-to-date Gradle.
 * Ганяти з `--rerun-tasks`, як робить scripts/verify.sh.
 */
class DirectoryNeverListsPeopleTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "core-net").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь дерева не знайдено") as Nothing
    }

    private fun source(path: String): String {
        val file = File(repoRoot(), path)
        assertTrue("$path не знайдено — перевірка вже нічого не боронить", file.isFile)
        return file.readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\\n]*"), "")
    }

    private val bridge = "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt"
    private val directory = "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerDirectory.kt"

    private fun card(name: String): PeerInvite.Invite {
        val keys = Ed25519.generateKeypair()
        val pub = java.util.Base64.getEncoder().withoutPadding().encodeToString(keys.publicKey)
        val dh = java.util.Base64.getEncoder().withoutPadding()
            .encodeToString(ByteArray(32) { it.toByte() })
        return PeerInvite.Invite(
            peerId = PeerIntro.fingerprint(pub),
            name = name,
            pubKey = pub,
            dhPubKey = dh,
            host = "",
            port = 0,
        )
    }

    @Test
    fun `an empty answer is zero rows and a hit is exactly one`() {
        assertEquals(0, PeerDirectory.rows("marta_k", null, "").size)

        val one = card("Марта")
        val rows = PeerDirectory.rows("marta_k", one, PeerInvite.encode(one))
        assertEquals(1, rows.size)
        assertEquals("marta_k", rows[0].handle)
        assertEquals(one.peerId, rows[0].id)

        // Не імʼя — теж нуль: сервера ми навіть не питали.
        assertEquals(0, PeerDirectory.rows("Марта", one, PeerInvite.encode(one)).size)
    }

    @Test
    fun `the bridge answers the directory only by handing it over`() {
        val text = source(bridge)
        val lines = text.lines().filter { it.contains("seg[1] == \"directory\"") }
        assertEquals("гілка довідника в мосту мусить бути рівно одна", 1, lines.size)
        assertTrue(
            "міст відповідає про довідник сам: ${lines[0].trim()}",
            lines[0].contains("directory.route("),
        )
    }

    @Test
    fun `the users array is built from the one-or-none function and nothing else`() {
        val text = source(directory)
        assertTrue("масив користувачів не бере рядків із PeerDirectory.rows", text.contains("PeerDirectory.rows("))
        assertEquals(
            "масив у відповіді мусить бути один — другий нікому не підзвітний",
            1,
            Regex("""JSONArray\(""").findAll(text).count(),
        )
        val loops = Regex("""(\w+)\.forEach""").findAll(text).map { it.groupValues[1] }.toList()
        assertEquals("масив наповнює лише те, що повернула rows(...): $loops", listOf("rows"), loops)
    }

    @Test
    fun `no directory route can reach the roster at all`() {
        val text = source(directory)
        for (door in listOf("container.peers", ".peers.value", "PeerRoster.people", "roster")) {
            assertFalse(
                "довідник дістався реєстру через «$door» — звідти список людей уже можна віддати",
                text.contains(door),
            )
        }
    }
}
