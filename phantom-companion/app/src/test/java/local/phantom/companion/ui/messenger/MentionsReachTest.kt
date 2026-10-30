package local.phantom.companion.ui.messenger

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** P10: вибрані в композері люди доходять із листа групи в дріт і назад у рядок вебу. */
class MentionsReachTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("не знайшов корінь дерева (каталог feature-stream)")
    }

    private fun code(rel: String): String = File(repoRoot(), rel).readText().lineSequence()
        .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
        .joinToString("\n")

    private val app = "app/src/main/java/local/phantom/companion"

    @Test
    fun `лист групи бере mentions з тіла і везе в кожну дорогу`() {
        val bridge = code("$app/ui/messenger/WebMessengerBridge.kt")
        assertTrue(bridge.contains("""val mentions = o.optJSONArray("mentions")"""))
        assertTrue(bridge.contains("""sendToGroup(group, framed(composed), replyToId, o.optBoolean("quiet"), readers, mentions, fw, qt)"""))
        assertTrue(bridge.contains("""sendToGroup(group, framed(text), replyToId, o.optBoolean("quiet"), readers, mentions, fw, qt)"""))
        assertTrue(bridge.contains("""sendTypedToGroup(group, text, replyToId, o.optBoolean("quiet"), mentions, fw, qt)"""))
        assertTrue("рядок групи не віддає mentions вебу", bridge.contains("""o.put("mentions", JSONArray(m.mentions))"""))
    }

    @Test
    fun `сітка кладе лише учасників групи в mn`() {
        val mesh = code("$app/data/PeerMesh.kt")
        assertTrue(mesh.contains("val named = mentions.filter { it in group.memberIds && it != me }"))
        assertTrue(mesh.contains("mentions = named,"))
    }
}
