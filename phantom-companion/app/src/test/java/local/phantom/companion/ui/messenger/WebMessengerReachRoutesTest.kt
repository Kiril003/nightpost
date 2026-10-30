package local.phantom.companion.ui.messenger

import java.io.File
import local.phantom.companion.core.net.peer.PeerIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B0 (P0-9): що міст каже вебу про досяжність і як приймає ключ з камери.
 *
 * Перша половина — жива логіка [WebMessengerReach]. Друга — GATE LAW: читає
 * міст як текст (коментарі знімаються) і доводить, що кожна ручка, яку веб
 * справді викликає, має гілку, а не падає в сигнали дзвінка з `no_call_id`.
 */
class WebMessengerReachRoutesTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("не знайшов корінь дерева (каталог feature-stream)")
    }

    private fun code(rel: String): String = File(repoRoot(), rel).readText().lineSequence()
        .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
        .joinToString("\n")

    private val bridge by lazy { code("app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt") }

    /** Гілка `when`, що починається умовою з усіма цими шматками, і чотири рядки після неї. */
    private fun branchWith(vararg parts: String): String? {
        val lines = bridge.lines()
        val i = lines.indexOfFirst { l -> l.contains("->") && parts.all { l.substringBefore("->").contains(it) } }
        return if (i < 0) null else lines.drop(i).take(6).joinToString("\n")
    }

    private fun peer(introduced: Long = 0L, met: Long = 0L) = PeerIdentity(
        id = "p1", name = "Олена", kind = PeerIdentity.Kind.PERSON,
        pubKey = "pk", dhPubKey = "dh", introducedAtMs = introduced, metAtMs = met,
    )

    // ── жива логіка ──────────────────────────────────────────────────────

    @Test
    fun `одностороннє додавання ще не звязок`() {
        assertFalse(WebMessengerReach.mutual(peer()))
        assertEquals("waiting", WebMessengerReach.wire(WebMessengerReach.mutual(peer())))
    }

    @Test
    fun `знайомство або зустріч роблять звязок взаємним`() {
        assertTrue(WebMessengerReach.mutual(peer(introduced = 5L)))
        assertTrue(WebMessengerReach.mutual(peer(met = 5L)))
        assertEquals("mutual", WebMessengerReach.wire(true))
    }

    @Test
    fun `доставлений лист теж доводить звязок, і питаємо про це лише коли треба`() {
        assertTrue(WebMessengerReach.mutual(peer()) { true })
        var asked = 0
        assertTrue(WebMessengerReach.mutual(peer(introduced = 1L)) { asked++; true })
        assertEquals("доказ уже є — журнал не гортаємо", 0, asked)
    }

    @Test
    fun `лише камера дає камеру`() {
        assertEquals(PeerIdentity.KeySource.CAMERA, WebMessengerReach.keySource("camera"))
        assertEquals(PeerIdentity.KeySource.REMOTE, WebMessengerReach.keySource(""))
        assertEquals(PeerIdentity.KeySource.REMOTE, WebMessengerReach.keySource(null))
        assertEquals(PeerIdentity.KeySource.REMOTE, WebMessengerReach.keySource("CAMERA "))
        assertEquals(PeerIdentity.KeySource.REMOTE, WebMessengerReach.keySource("verified"))
    }

    // ── досяжність: міст справді це викликає ─────────────────────────────

    @Test
    fun `рядок контакту несе session_ready і reach з одного джерела`() {
        val row = bridge.substringAfter("fun contact(p: PeerIdentity)").substringBefore("\n    private ")
        assertTrue("session_ready досі «ключ є»", row.contains(".put(\"session_ready\", mutual)"))
        assertTrue("рядок без reach", row.contains(".put(\"reach\", WebMessengerReach.wire(mutual))"))
        assertTrue("mutual не з WebMessengerReach", row.contains("WebMessengerReach.mutual(p"))
    }

    @Test
    fun `identity каже, що міст говорить reach`() {
        val id = bridge.substringAfter("private suspend fun identity()").substringBefore("\n    private ")
        assertTrue("identity без reach — веб не покаже обіцянку на першому запуску", id.contains(".put(\"reach\","))
    }

    @Test
    fun `addContact віддає accept слово джерела`() {
        val add = bridge.substringAfter("private suspend fun addContact").substringBefore("// Пряме підключення")
        assertTrue(
            "accept без джерела — камера лишається REMOTE",
            add.contains("container.mesh.accept(invite, WebMessengerReach.keySource(o.optString(\"source\")))"),
        )
    }

    // ── ручки дзвінка ────────────────────────────────────────────────────

    @Test
    fun `ready й audio-route не потрапляють у сигнали дзвінка`() {
        val verbs = bridge.substringAfter("val CALL_ROOM_VERBS = setOf(").substringBefore(")")
        assertTrue("ready не в CALL_ROOM_VERBS", verbs.contains("\"ready\""))
        assertTrue("audio-route не в CALL_ROOM_VERBS", verbs.contains("\"audio-route\""))
    }

    @Test
    fun `POST call ready каже вузлу, що веб готовий`() {
        val b = branchWith("seg[1] == \"call\"", "\"ready\"", "\"POST\"")
        assertNotNull("POST /messenger/call/ready без гілки", b)
        assertTrue(b!!.contains("container.callHub.webReady()"))
    }

    @Test
    fun `audio-route читає і перемикає той самий маршрут`() {
        val get = branchWith("seg[1] == \"call\"", "\"audio-route\"", "\"GET\"")
        assertNotNull("GET audio-route без гілки", get)
        assertTrue(get!!.contains("CallAudioRoute.shared(container.applicationContext).describe()"))
        val post = branchWith("seg[1] == \"call\"", "\"audio-route\"", "\"POST\"")
        assertNotNull("POST audio-route без гілки", post)
        assertTrue(post!!.contains("CallAudioRoute.shared(container.applicationContext).choose("))
    }

    @Test
    fun `ICE веб читає в WebMessengerIce`() {
        assertTrue(bridge.contains("seg[1] == \"ice\" && method == \"GET\" -> container.webMessengerIce.answer()"))
    }
}
