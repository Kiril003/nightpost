package local.phantom.companion.ui.messenger

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контракт «Заблокувати» між вебом і мостом — і що засув доходить до сітки.
 *
 * GATE LAW: читає файли як текст, коментарі знімаються.
 */
class WebMessengerBlockReachTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("не знайшов корінь дерева (каталог feature-stream)")
    }

    private fun code(rel: String): String = File(repoRoot(), rel).readText().lineSequence()
        .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
        .joinToString("\n")

    private val bridge by lazy { code("app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt") }

    private val segLiteral = Regex("seg\\[(\\d+)\\]\\s*==\\s*\"([^\"]+)\"")
    private val segSize = Regex("seg\\.size\\s*==\\s*(\\d+)")
    private val methodLiteral = Regex("method\\s*==\\s*\"([A-Z]+)\"")

    /** Та сама мова гілок, що в [WebBundleRoutesReachTest]; повертає тіло гілки. */
    private fun branch(method: String, path: String): String? {
        val segs = path.trim('/').split('/')
        val lines = bridge.lines()
        for ((i, line) in lines.withIndex()) {
            if (!line.contains("->") || !line.contains("seg")) continue
            val condition = line.substringBefore("->")
            val literals = segLiteral.findAll(condition).map { it.groupValues[1].toInt() to it.groupValues[2] }.toList()
            if (literals.isEmpty()) continue
            val size = segSize.find(condition)?.groupValues?.get(1)?.toInt()
            if (size != null && size != segs.size) continue
            val methods = methodLiteral.findAll(condition).map { it.groupValues[1] }.toList()
            if (methods.isNotEmpty() && method !in methods) continue
            if (literals.all { (k, v) -> k < segs.size && segs[k] == v }) {
                return (listOf(line.substringAfter("->")) + lines.drop(i + 1).take(4)).joinToString("\n")
            }
        }
        return null
    }

    @Test
    fun `чотири виклики контракту мають гілку і роблять своє`() {
        val list = branch("GET", "/messenger/blocked")
        assertNotNull("GET /messenger/blocked без маршруту", list)
        assertTrue(list!!.contains("peerBlocks.blocked.value"))

        val block = branch("POST", "/messenger/contacts/abc/block")
        assertNotNull("POST …/block без маршруту", block)
        assertTrue(block!!.contains("peerBlocks.block(seg[2])"))

        val unblock = branch("DELETE", "/messenger/contacts/abc/block")
        assertNotNull("DELETE …/block без маршруту", unblock)
        assertTrue(unblock!!.contains("peerBlocks.unblock(seg[2])"))
    }

    @Test
    fun `дзеркало вміє почервоніти`() {
        assertNull(branch("PUT", "/messenger/contacts/abc/block"))
        assertNull(branch("GET", "/messenger/contacts/abc/такого-нема"))
    }

    @Test
    fun `рядок контакту несе blocked тим самим id, що й peer_node_id`() {
        val row = bridge.substringAfter("fun contact(p: PeerIdentity)").substringBefore("\n    private ")
        assertTrue(row.contains(".put(\"peer_node_id\", p.id)"))
        assertTrue("рядок контакту без поля blocked", row.contains(".put(\"blocked\", container.peerBlocks.blocks(p.id))"))
    }

    @Test
    fun `сітка питає той самий список, що міняє міст`() {
        val container = code("app/src/main/java/local/phantom/companion/data/AppContainer.kt")
        assertTrue(container.contains("val peerBlocks: PeerBlockStore by lazy"))
        val mesh = container.substringAfter("val mesh: PeerMesh by lazy").substringBefore("\n    }")
        assertTrue("PeerMesh не отримує список заблокованих", mesh.contains("blocked = { peerBlocks.blocked.value }"))
    }
}
