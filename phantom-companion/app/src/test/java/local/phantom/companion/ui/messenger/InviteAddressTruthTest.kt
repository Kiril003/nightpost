package local.phantom.companion.ui.messenger

import java.io.File
import local.phantom.companion.core.net.peer.PeerInvite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Кадр «хто я» не має права мовчати про те, що прямої адреси немає.
 *
 * 04.09 о 02:02 «Ваше запрошення» на A52 давало рядок із зовнішньою IP апарата
 * і портом 0: слухача немає, доставка йде сховком, а IP усе одно називала
 * кожному, кому показали QR, мережу, у якій людина зараз стоїть. Рядок від неї
 * очищено в [PeerInvite.encode]; тут доводиться друга половина — що міст не
 * віддає ту саму IP окремим полем повз код, і що веб має чим сказати правду
 * словом, а не прочерком.
 *
 * Поведінка кадру тут не ганяється: `org.json` у `:app` — заглушка з
 * android.jar, і на JVM вона кидає, а не відповідає. Тому форма кадру
 * перевіряється джерелом, а правило під нею — справжнім викликом чистої
 * функції з core-net, тієї самої, яку кличе міст.
 */
class InviteAddressTruthTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "core-net").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь дерева не знайдено") as Nothing
    }

    private val relPath =
        "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt"

    private fun source(): String {
        val f = File(repoRoot(), relPath)
        assertTrue("$relPath не знайдено — перевірка вже нічого не боронить", f.isFile)
        return f.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
    }

    private val listening = PeerInvite.Invite(
        peerId = "747aa842fd358e5f",
        name = "PHANTOM-A52",
        pubKey = "11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo",
        dhPubKey = "hSDwCYkwp1R0i33ctD73Wg2/Og0mOBr066SpjqqbTmo",
        host = "192.168.0.14",
        port = 47_331,
    )
    private val silent = listening.copy(host = "158.196.227.74", port = 0)

    @Test
    fun `міст віддає адресу окремим полем, і рахує її не сам`() {
        val src = source()
        assertTrue(
            "у кадрі /messenger/identity немає поля address — вебові нема з чого " +
                "сказати «прямої адреси немає»",
            src.contains(""".put("address", """),
        )
        assertTrue(
            "адресу мусить рахувати PeerInvite.address — друга формула розійшлася б " +
                "із тим, що лежить у самому коді запрошення",
            src.contains("PeerInvite.address("),
        )
        // Сире `inv.host` у кадрі — це рівно та IP, яку зрізано з рядка.
        assertFalse("host у bundle береться повз правило", src.contains("""inv?.host"""))
        assertFalse("port у bundle береться повз правило", src.contains("""inv?.port"""))
    }

    @Test
    fun `без живого порту адреси немає ні полем, ні в коді`() {
        assertNull(PeerInvite.address(silent))
        val body = String(
            java.util.Base64.getUrlDecoder().decode(PeerInvite.encode(silent).split(":")[1]),
            Charsets.UTF_8,
        )
        assertFalse("IP поїхала в коді: $body", body.contains("158.196.227.74"))
    }

    @Test
    fun `з живим портом адреса їде як host двокрапка port`() {
        assertEquals("192.168.0.14:47331", PeerInvite.address(listening))
    }
}
