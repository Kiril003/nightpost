package local.phantom.companion.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Друга половина вади, яку [RouteRegistrationTest] у своєму ж KDoc визнає
 * непідйомною: «маршрут є, але з UI на нього ніхто не переходить».
 *
 * 07.09 виміряно: `Routes.Projects` існував рівно двічі — в оголошенні й у
 * `composable(...)`. Екран лагодили 05.09 (збереження, `onOpen`), обидва
 * сторожі навігації були зелені, а дверей не було. Так само мовчки стояв
 * `"Творення"` в обох таблицях просторів після того, як його плитку зняли.
 *
 * Тому це ПЕРЕПИС, а не заборона: множина маршрутів без дверей звіряється з
 * оголошеною нижче, кожен — із причиною. Нові двері так само валять тест, як і
 * нові безхатьки: і те, і те має бути рішенням, а не побічним ефектом.
 *
 * Евристика навмисно груба й описана тут повністю, бо тест, чиє мовчання ніхто
 * не вміє пояснити, гірший за відсутній. Не рахуються за двері: порівняння
 * (`route == Routes.X.path`), якорі стеку (`popUpTo`, `popBackStack`,
 * `getBackStackEntry`) і сам рядок реєстрації в графі. Глибокі посилання зі
 * шторки евристика не бачить — вони приходять ззовні застосунку, і саме тому
 * стоять у переписі з причиною, а не в тиші.
 */
class RouteDoorCensusTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "feature-stream").isDirectory }
        ?: error("не знайшла кореня сховища: жоден предок не має теки feature-stream")

    /**
     * Маршрут → чому в нього немає дверей із застосунку. Порожньої причини не
     * буває: якщо нема що написати, то й маршруту бути не має.
     */
    // 07.09: PeerChat, PeerGroupChat і MessengerThread зникли разом із другим
    // месенджером — не «дістали двері», а перестали існувати. Перепис мусить
    // худнути так само чесно, як росте: маршрут без дверей, якого вже немає,
    // це не борг, а привид.
    // 02.10: Creator, Whisper, Motion і Projects зникли разом з оболонкою
    // острова — маршрутів більше немає, тож і причин для них.
    // 04.10 (profile-hub): Messenger знято разом із маршрутом — кандидат дочекався.
    // Settings і Memory пішли теж, але вони мали двері, тож переліку не чіпали.
    private val doorless: Map<String, String> = mapOf(
        "PeerCard" to "відчиняється запитом peerCardRequest через Routes.PeerCard.of(id) " +
            "(посилання запрошення, картка з месенджера) — евристика бачить лише .path",
    )

    private val referenced = Regex("""Routes\.([A-Za-z0-9]+)\.(?:path|createRoute)""")
    private val registration = Regex("""composable\(\s*(?:route\s*=\s*)?Routes\.([A-Za-z0-9]+)\.path""")

    private fun declared(): Set<String> =
        Regex("""data object\s+([A-Za-z0-9]+)\s*:\s*Routes\(""")
            .findAll(File(root, "app/src/main/java/local/phantom/companion/navigation/Routes.kt").readText())
            .map { it.groupValues[1] }
            .toSet()

    private fun withDoors(): Set<String> {
        val graph = File(root, "app/src/main/java/local/phantom/companion/navigation/PhantomNavGraph.kt")
        val found = mutableSetOf<String>()
        listOf("app", "feature-stream", "core-design").forEach { module ->
            val dir = File(root, "$module/src/main")
            if (!dir.isDirectory) return@forEach
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val text = if (file == graph) {
                    registration.replace(file.readText(), "composable(REGISTERED")
                } else {
                    file.readText()
                }
                text.lineSequence().forEach { line ->
                    val s = line.trim()
                    val notADoor = "==" in s || "startsWith" in s || "popUpTo" in s ||
                        "popBackStack" in s || "getBackStackEntry" in s ||
                        s.startsWith("//") || s.startsWith("*")
                    if (!notADoor) referenced.findAll(line).forEach { found += it.groupValues[1] }
                }
            }
        }
        return found
    }

    @Test
    fun `перепис маршрутів без дверей збігається з оголошеним`() {
        val measured = (declared() - withDoors()).sorted()
        assertEquals(
            "змінився набір маршрутів, у які з застосунку не веде НІЩО.\n" +
                "Зʼявився новий — або додай двері, або впиши сюди з причиною.\n" +
                "Зник — прибери з переліку разом із причиною.\n",
            doorless.keys.sorted(),
            measured,
        )
    }

    @Test
    fun `кожна причина щось каже`() {
        doorless.forEach { (route, reason) ->
            assertTrue("$route стоїть у переписі без причини", reason.length > 20)
        }
    }

    @Test
    fun `евристика бачить справжні двері`() {
        // Сторож, що не знайшов жодних дверей, був би зелений на будь-якому
        // дереві — включно з деревом, де навігації немає взагалі.
        val doors = withDoors()
        assertTrue("евристика не бачить дверей у Ліцензію — регулярка згнила", "SettingsLicense" in doors)
        assertTrue("евристика не бачить дверей у дім", "Home" in doors)
        // 02.10 маршрутів 21 (дзвінок PHANTOM став накладкою домівки, свого
        // маршруту не має), без дверей 2 — двері мусить бачити всі 19.
        // 04.10 (profile-hub): мінус Settings, Memory і Messenger — маршрутів 18,
        // без дверей 1 (PeerCard), тож двері мусить бачити всі 17.
        assertTrue("евристика знайшла підозріло мало дверей: ${doors.size}", doors.size >= 17)
    }
}
