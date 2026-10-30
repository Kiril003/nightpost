package local.phantom.companion.service

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Сітку вмикає той екран, який людина справді відкриває.
 *
 * 04.09 виявилось, що виклики [PeerMeshGate.peopleEntered]/[PeerMeshGate.peopleLeft]
 * жили в `PeopleBehindLock`, а маршрут `Routes.People.path` веде на
 * `MessengerListDestination` — тобто в релізі вони не досягались ЖОДНОГО разу.
 * `peopleOnScreen` завжди false, `shouldRun` вироджувався в «є люди в реєстрі»,
 * а `keepInStep(false)` не просто не вмикає — ЗУПИНЯЄ службу.
 *
 * Виміряно на апараті: свіжий апарат із порожнім реєстром тримає службу
 * зупиненою, `PeerLink.boundPort` = -1, і перший код запрошення несе `port = 0`
 * — перший обмін двох нових телефонів не може піти локальною мережею. О 02:02
 * порт був 0; о 12:30, коли контакти вже були, — 46779.
 *
 * ПЕРША ВЕРСІЯ ЦЬОГО СТОРОЖА БУЛА ЗЕЛЕНА НА НЕПОЛАГОДЖЕНОМУ СТВОЛІ. Вона брала
 * «тіло функції» як увесь файл, а `ChamberDestinations.kt` тримає і мертвий
 * `PeopleBehindLock`, і пʼять живих маршрутних призначень (Body, Day, Hands,
 * Intel, Strategy) — перетин виходив непорожній, і тест казав «виклик
 * досяжний», бо в тому ж файлі було щось досяжне. Тому тут [balancedBlockAfter]:
 * тіло — це саме тіло, від `{` до парної `}`. Межа, названа в заголовку, від
 * такої вади не рятує — рятує код.
 *
 * 02.10: острів і Континуум пішли. Маршрут `Routes.Home` веде на `HomeScreen`,
 * а виклики стоять у тілі його вкладки `ChatsTab`. «Розвідки» більше немає —
 * її перевірку знято разом із нею. 04.10 знято й маршрут старої назви списку
 * чатів: дверей до нього не було, і перевіряти стало нічого.
 *
 * ЩО ЦЕЙ СТОРОЖ НЕ ДОВОДИТЬ: він читає джерело, а не рендерить екран —
 * `compose-ui-test` у `gradle/libs.versions.toml` немає. Тобто доводить, що
 * виклик стоїть у тілі потрібної функції, а не що він спрацював на склі. Коли
 * зʼявиться `compose-ui-test`, замінити справжнім рендером.
 */
class MeshGateReachTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "feature-stream").isDirectory }
        ?: error("не знайшла кореня сховища: жоден предок не має теки feature-stream")

    private val navDir = File(root, "app/src/main/java/local/phantom/companion/navigation")
    private val homeDir = File(root, "app/src/main/java/local/phantom/companion/ui/home")
    private val sources = listOf(navDir, homeDir)

    private fun ktFiles(): Sequence<File> =
        sources.asSequence().flatMap { d ->
            if (!d.isDirectory) fail("немає $d — тека переїхала, сторож осліп")
            d.walkTopDown().filter { it.isFile && it.extension == "kt" }
        }

    private fun navGraph(): String {
        val f = File(navDir, "PhantomNavGraph.kt")
        if (!f.isFile) fail("немає $f — навграф перейменували або перенесли")
        return f.readText()
    }

    /** Тіло від `{` після [from] до парної `}` — саме тіло, не файл. */
    private fun balancedBlockAfter(text: String, from: Int): String {
        val open = text.indexOf('{', from)
        if (open < 0) return ""
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return text.substring(open, i + 1) }
            }
            i++
        }
        return text.substring(open)
    }

    private fun destinationOf(route: String): String {
        val text = navGraph()
        val at = text.indexOf("composable($route)")
        if (at < 0) fail("у навграфі немає composable($route) — маршрут перейменували або прибрали")
        val block = balancedBlockAfter(text, at)
        return Regex("""([A-Z][A-Za-z0-9]*)\s*\(""").find(block)?.groupValues?.get(1)
            ?: run { fail("не розібрала призначення для $route у блоці: ${block.take(120)}"); "" }
    }

    /** Тіло названої функції в навігації чи оболонці; називає, коли не знайшла або знайшла двічі. */
    private fun bodyOf(name: String): String {
        val hits = ktFiles()
            .mapNotNull { f ->
                val t = f.readText()
                val at = Regex("""fun\s+$name\s*\(""").find(t)?.range?.first
                at?.let { f.name to balancedBlockAfter(t, it) }
            }.toList()
        if (hits.isEmpty()) fail("функції $name немає в $sources — оголошене ніде або переїхало")
        if (hits.size > 1) fail("функція $name оголошена двічі: ${hits.map { it.first }} — тест узяв би першу навмання")
        return hits.single().second
    }

    private fun routedNames(): Set<String> {
        val text = navGraph()
        return Regex("""composable\(([^)]*)\)""").findAll(text).mapNotNull { m ->
            val block = balancedBlockAfter(text, m.range.last)
            Regex("""([A-Z][A-Za-z0-9]*)\s*\(""").find(block)?.groupValues?.get(1)
        }.toSet()
    }

    /** Функції, у ТІЛІ яких стоїть виклик — не «файли, де він десь є». */
    private fun holdersOf(call: String): Set<String> =
        ktFiles()
            .flatMap { f ->
                val t = f.readText()
                Regex("""fun\s+([A-Z][A-Za-z0-9]*)\s*\(""").findAll(t).mapNotNull { m ->
                    val body = balancedBlockAfter(t, m.range.last)
                    if (body.contains(call)) m.groupValues[1] else null
                }
            }.toSet()

    /** Вкладка Чатів, яку кличе саме тіло [host]. */
    private fun chatsTabOf(host: String): String {
        val body = bodyOf(host)
        return Regex("""\b(ChatsTab)\s*\(""").find(body)?.groupValues?.get(1)
            ?: run { fail("тіло $host не кличе ChatsTab — Чати перестали бути вкладкою дому"); "" }
    }

    @Test
    fun `вкладка Чати на екрані маршруту дому вмикає сітку`() {
        val dest = destinationOf("Routes.Home.path")
        val body = bodyOf(chatsTabOf(dest))
        assertTrue(
            "маршрут дому веде на $dest, а в тілі його Чатів немає peopleEntered — сітка не підніметься",
            body.contains("peopleEntered()"),
        )
        assertTrue(
            "у тілі Чатів немає peopleLeft — служба лишиться піднятою після виходу",
            body.contains("peopleLeft()"),
        )
    }

    @Test
    fun `будильник гасне разом з екраном, а не з композицією`() {
        // DisposableEffect лишив би сітку піднятою при згаслому екрані: телефон
        // у кишені світив би службою. Тест вище лишився б зеленим.
        val body = bodyOf(chatsTabOf(destinationOf("Routes.Home.path")))
        assertTrue(
            "сигнал стоїть не в LifecycleResumeEffect — згаслий екран триматиме сітку",
            body.contains("LifecycleResumeEffect"),
        )
    }

    @Test
    fun `жоден недосяжний композабл не тримає єдиний виклик`() {
        val holders = holdersOf("peopleEntered()")
        assertTrue("виклик peopleEntered не знайдено в жодному тілі", holders.isNotEmpty())
        val routed = routedNames()
        val reached = routed + routed.flatMap { r ->
            runCatching { bodyOf(r) }.getOrDefault("").let { body ->
                holders.filter { Regex("""\b$it\s*\(""").containsMatchIn(body) }
            }
        }
        assertTrue(
            "peopleEntered кличуть лише $holders, а маршрути ведуть на $routed — виклик недосяжний",
            holders.any { it in reached },
        )
    }
}
