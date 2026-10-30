package local.phantom.companion.ui.messenger

import java.io.File
import local.phantom.companion.core.net.peer.PeerWhere
import local.phantom.companion.core.net.peer.PeerWhereGrid
import local.phantom.companion.data.PeerWhereView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Дзеркало «Де я» між мостом і вебом: ті самі шляхи, ключі й слова з обох боків. */
class WebMessengerWhereContractTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("корінь дерева не знайдено")
    }

    private val sample = PeerWhereView(
        whyNot = PeerWhereView.WhyNot.KEY_ALARM,
        mine = PeerWhereView.Mine(PeerWhere.Precision.QUARTER, 5L),
        theirs = PeerWhereView.Theirs(PeerWhere.Precision.EXACT, PeerWhereGrid.Nearness.NEAR, 1L, 2L),
    )

    @Suppress("UNCHECKED_CAST")
    private fun keysOf(m: Map<String, Any?>, prefix: String = ""): Set<String> = m.flatMap { (k, v) ->
        listOf(prefix + k) + ((v as? Map<String, Any?>)?.let { keysOf(it, "$k.") } ?: emptySet())
    }.toSet()

    @Test
    fun `міст шле рівно ці поля`() {
        assertEquals(
            setOf(
                "now_ms", "eligible", "why_not", "mine", "met_at_ms", "theirs",
                "mine.precision", "mine.until_ms", "mine.stand", "mine.meet",
                "theirs.precision", "theirs.near", "theirs.at_ms", "theirs.expires_at_ms", "theirs.stand",
            ),
            keysOf(WebMessengerWhere.fields(sample, 0L)),
        )
    }

    @Test
    fun `веб чекає ті самі поля, слова і строки`() {
        val src = listOfNotNull(
            System.getenv("PHANTOM_WEB_SRC")?.let(::File),
            File(repoRoot().parentFile, "phantom-os/src/frontend/src"),
        ).firstOrNull { File(it, "services/peerWhere.ts").isFile }
        assumeTrue("поруч немає дерева веб-месенджера", src != null)
        val web = File(src, "services/peerWhere.ts").readText()

        keysOf(WebMessengerWhere.fields(sample, 0L)).map { it.substringAfter('.') }.forEach {
            assertTrue("веб не знає поля «$it»", Regex("""\b$it\??:""").containsMatchIn(web))
        }
        PeerWhere.Precision.entries.forEach { assertTrue("веб не знає «${WebMessengerWhere.word(it)}»", "'${WebMessengerWhere.word(it)}'" in web) }
        PeerWhereGrid.Nearness.entries.forEach { assertTrue("веб не знає «${WebMessengerWhere.nearWord(it)}»", "'${WebMessengerWhere.nearWord(it)}'" in web) }
        PeerWhereView.WhyNot.entries.forEach { assertTrue("веб не знає причини «${it.name.lowercase()}»", "'${it.name.lowercase()}'" in web) }
        WebMessengerWhere.SPANS_MIN.forEach { assertTrue("веб не пропонує $it хв", Regex("""minutes:\s*$it\b""").containsMatchIn(web)) }
        assertTrue("веб ходить не тим шляхом", "/messenger/${WebMessengerWhere.SEGMENT}/" in web)
        assertTrue("веб не слухає поштовху з телефона", "__phantomWhereChanged" in web)
        assertTrue(
            "поштовх телефона зветься інакше, ніж слухає веб",
            "__phantomWhereChanged" in File(repoRoot(), "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerHost.kt").readText(),
        )
    }

    @Test
    fun `аркуш місця не відкриває нативної картки людини`() {
        val bridge = File(repoRoot(), "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt").readText()
        val route = bridge.substringAfter("private fun whereRoute(").substringBefore("\n    private fun ")
        assertTrue("whereRoute не знайдено", route.contains("verb == \"map\""))
        assertTrue("where веде в Routes.PeerCard", "Routes.PeerCard" !in route)
        assertTrue("where знову вимагає звірки", "not_proven" !in route)
    }
}
