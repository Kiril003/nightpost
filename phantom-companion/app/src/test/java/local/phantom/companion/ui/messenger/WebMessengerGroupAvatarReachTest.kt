package local.phantom.companion.ui.messenger

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.fail

/**
 * Чи обличчя групи справді ДОХОДИТЬ, а не лише вибирається.
 *
 * 14.09 вимір на бандлі, що їде в APK (`index-CIrwRVPt.js`): веб уже слав
 * `createConversation({title,kind:"group",circle,avatar,member_ids})` — поле
 * `avatar` стояло на дроті. Міст його не читав ЖОДНОГО разу: `createConversation`
 * брав із тіла лише `title` та склад, а `groupConversation` клав у відповідь
 * `avatar: JSONObject.NULL` літералом. Тобто картинку віддавали й губили двічі,
 * і в списку розмов група лишалась без обличчя назавжди.
 *
 * GATE LAW: читає файли як текст. Коментарі знімаються — сторож не має
 * зеленіти від власного пояснення.
 */
class WebMessengerGroupAvatarReachTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь дерева не знайдено") as Nothing
    }

    private fun source(rel: String): String {
        val f = File(repoRoot(), rel)
        if (!f.isFile) fail("немає файла $rel")
        return f.readText()
    }

    private fun codeOf(text: String): String = text.lineSequence()
        .filterNot { line ->
            val t = line.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
        .joinToString("\n")

    private val bridge by lazy {
        codeOf(source("app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt"))
    }

    @Test
    fun `create reads avatar from the body`() {
        assertTrue(
            "createConversation не читає avatar із тіла — веб шле, міст губить",
            bridge.contains("o.optString(\"avatar\")"),
        )
    }

    @Test
    fun `group row carries the stored avatar, not a literal null`() {
        val row = bridge.substringAfter("private fun groupConversation")
            .substringBefore("private fun ")
        assertTrue(
            "рядок групи має віддавати збережене обличчя через groupAvatars",
            row.contains("groupAvatars.avatarOf(group.id)"),
        )
        assertTrue(
            "у рядку групи лишився літеральний avatar=NULL",
            !row.contains(".put(\"avatar\", JSONObject.NULL)"),
        )
    }

    @Test
    fun `the store is wired into the container`() {
        val container = codeOf(
            source("app/src/main/java/local/phantom/companion/data/AppContainer.kt"),
        )
        assertTrue(
            "PeerGroupAvatarStore не підʼєднано в AppContainer — міст не має де взяти обличчя",
            container.contains("PeerGroupAvatarStore"),
        )
    }

    /**
     * Артефакт, а не джерело: бандл кладуть у дерево руками, тож дерево веба
     * могло поїхати вперед, а телефон — лишитись зі старим блобом.
     */
    @Test
    fun `the shipped bundle still sends avatar when it creates a group`() {
        val assets = File(repoRoot(), "app/src/main/assets/webmsgr/assets")
        val scripts = assets.listFiles { f: File -> f.name.endsWith(".js") }.orEmpty()
        assertTrue("у бандлі немає жодного .js — нема що звіряти", scripts.isNotEmpty())
        val createsGroup = scripts.any { f ->
            val t = f.readText()
            t.contains("createGroup:") && t.contains("member_ids")
        }
        assertTrue("бандл не створює груп — сторож нічого не прочитав", createsGroup)
        val sendsAvatar = scripts.any { f ->
            val t = f.readText()
            val call = t.substringAfter("createGroup:", "").substringBefore("member_ids", "")
            call.contains("avatar")
        }
        assertTrue(
            "бандл створює групу БЕЗ avatar — обличчя не доїде, хоч міст його й читає",
            sendsAvatar,
        )
    }
}
