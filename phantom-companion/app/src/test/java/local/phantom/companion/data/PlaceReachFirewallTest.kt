package local.phantom.companion.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * КАДР МІСЦЯ МАЄ ПИСАРЯ, А НЕ ЛИШЕ ЧИТАЧІВ.
 *
 * Вимір 12.09 на голові зведення: `PeerPayload.encodePlace` мав **нуль**
 * продуктових викликів. Усі 17 влучань лежали в `/test/`. При цьому читачів
 * було троє живих — `WebMessengerKinds.fields`, `PeerChatFormat`, лічильник у
 * `PeerCardScreen` — і сім тестів зеленіли на кодеку, якого ніхто не кличе.
 *
 * Дві дороги, якими місце НЕ доїжджало до людини:
 * 1. з мапи «поділитись місцем» складало текст із посиланням на
 *    openstreetmap.org і кидало андроїдів `ACTION_SEND` — тобто несло точку
 *    власника в ЧУЖІ застосунки, а в меш не несло нікуди;
 * 2. з месенджера веб слав `kind:"geo:point"` із тілом-JSON, і міст віддавав
 *    той JSON далі СЛОВОМ людини — співрозмовник діставав у бульбашку
 *    `{"lat":50.45010,…}`, тобто доставлений протокол в очі тому, для кого
 *    його ховають.
 *
 * Компілятор не бачить жодної з них: обидві — правильний код, що робить не те.
 * Тому закон тут читається ДЖЕРЕЛОМ і АРТЕФАКТОМ, а не типами.
 *
 * GATE LAW: сторож читає джерело файлом, повз up-to-date Gradle — на валі
 * запускати з `--rerun`.
 */
class PlaceReachFirewallTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "core-net").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with core-net/ not found") as Nothing
    }

    private fun read(path: String): String {
        val f = File(repoRoot(), path)
        assertTrue("сторож осліп: немає $path", f.isFile)
        return f.readText()
    }

    private val viewModel = "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/MapViewModel.kt"
    private val sheetHost = "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/SheetHost.kt"
    private val host = "app/src/main/java/local/phantom/companion/ui/home/MapTab.kt"
    private val bridge = "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt"

    /** Кожен `.kt` продукту — тобто без `/test/` і без самого оголошення. */
    private fun productSources(): List<File> =
        repoRoot().walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .filterNot { it.path.contains("/src/test/") || it.path.contains("/src/androidTest/") }
            .filterNot { it.name == "PeerPayload.kt" }
            .toList()
            .also { assertTrue("сторож нічого не прочитав: ${it.size} файлів", it.size > 100) }

    @Test
    fun `кадр місця складають у продукті, а не лише в тестах`() {
        val writers = productSources()
            .filter { it.readText().contains("encodePlace(") }
            .map { it.name }
            .sorted()
        // Порожній перелік — це рівно той стан, у якому фронт починався:
        // кодек, три читачі, сім тестів і жодного місця, що поїхало.
        assertFalse(
            "encodePlace не кличе НІХТО в продукті — кадр місця нікому складати",
            writers.isEmpty(),
        )
        assertTrue(
            "з мапи місце не складається: $writers",
            writers.contains("MapViewModel.kt"),
        )
        assertTrue(
            "з месенджера місце не складається: $writers",
            writers.contains("WebMessengerBridge.kt"),
        )
    }

    @Test
    fun `з мапи місце доїжджає до дроту, а не лише до чужого застосунку`() {
        val sheet = read(sheetHost)
        assertTrue("на аркуші місця немає дії «надіслати контактові»", sheet.contains("map_sheet_send_place"))
        assertTrue("немає кому надсилати", sheet.contains("sendTargets"))
        assertTrue("дія намальована й нікуди не веде", sheet.contains("viewModel.sharePlace("))

        val vm = read(viewModel)
        assertTrue("дія є, кадру не збирає", vm.contains("PeerPayload.encodePlace("))
        assertTrue("кадр збирається й нікуди не їде", vm.contains("sendPeerLetter(peerId, body)"))

        // Віддача НАЗОВНІ лишається окремою дією й нікуди не зникає: вона має
        // свій сенс, і підміна одної другою була б втратою, а не лікуванням.
        assertTrue("зникла віддача назовні — це інша дія, її не заступають", sheet.contains("map_sheet_share_place"))

        assertTrue("кадр складається й ніхто його не відправляє", read(host).contains("sendPeerLetter ="))
    }

    @Test
    fun `міст складає кадр САМ, а не бере готове тіло з веба`() {
        val src = read(bridge)
        // GEO_POINT більше не стоїть тут буквально: рід дізнається через
        // [local.phantom.companion.ui.messenger.WebMessengerKinds.carry], відколи
        // перелік родів, які міст уміє повезти, став ВИЧЕРПНИМ (гілки `else`
        // немає — 12.09, той самий вал, що додав картки композера). GEO_POINT
        // там і лишився, просто в іншому файлі: `carry(GEO_POINT) == Carry.Place`.
        // Перевіряємо той самий факт — міст розпізнає місце й веде його своєю
        // дорогою, — але через дійсний маршрут коду, а не мертву позначку.
        assertTrue("міст не розбирає рід через WebMessengerKinds.carry", src.contains("WebMessengerKinds.carry("))
        assertTrue("розбір роду більше не веде до Carry.Place", src.contains("Carry.Place"))
        assertTrue("міст не складає кадру з JSON композера", src.contains("PeerPayload.encodePlace("))
        // Найтвердіша умова: складене звіряється ТИМ САМИМ розбирачем, яким
        // кадр читатиме приймач. Другий суд про дійсне місце розійшовся б із
        // першим, і в дріт поїхала б точка, якої співрозмовник не покаже.
        assertTrue("складене не звіряється розбирачем приймача", src.contains("PeerPayload.decodePlace("))
    }

    @Test
    fun `рід place має читача в бандлі телефона — картка малюється без правок веба`() {
        val scripts = File(repoRoot(), "app/src/main/assets/webmsgr")
            .walkTopDown().filter { it.isFile && it.name.endsWith(".js") }
            .map { it.readText() }
            .toList()
        assertTrue("бандла нема — сторож нічого не прочитав", scripts.size > 1)

        val missing = listOf("place_lat", "place_lon", "place_accuracy_m")
            .filter { field -> scripts.none { it.contains(field) } }
        assertEquals(
            "міст віддає поля місця, яких у бандлі не читає ніхто: $missing",
            emptyList<String>(),
            missing,
        )
        assertTrue(
            "у бандлі нема гілки на рід «place» — картка не намалюється",
            scripts.any { it.contains("\"place\"") },
        )

        // Ті самі три імені мусять стояти й з боку писаря, інакше читач у
        // бандлі чекає на поля, яких міст не кладе.
        val kinds = read("app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerKinds.kt")
        listOf("place_lat", "place_lon", "place_accuracy_m").forEach {
            assertTrue("міст не кладе поля $it", kinds.contains(it))
        }
    }

    @Test
    fun `сторож уміє почервоніти`() {
        // Негативний контроль: якби пошук був сліпий, попередні тести зеленіли б
        // на будь-чому. Тут доводиться, що він бачить ВІДСУТНЄ як відсутнє.
        val vm = read(viewModel)
        assertFalse("пошук сліпий: знаходить те, чого немає", vm.contains("PeerPayload.encodePlaceНемає("))
        assertTrue(
            "пошук сліпий: не знаходить того, що є",
            productSources().any { it.readText().contains("encodePlace(") },
        )
    }
}
