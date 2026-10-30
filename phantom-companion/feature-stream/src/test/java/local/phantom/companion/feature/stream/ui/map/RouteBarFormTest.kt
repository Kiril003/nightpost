package local.phantom.companion.feature.stream.ui.map

import java.io.File
import local.phantom.companion.core.nav.RoutePlanner
import local.phantom.companion.core.net.map.RouteBasis
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * СМУГА МАРШРУТУ — форма, ухвалена 18.09 за вироком власника.
 *
 * Дослівно: «кнопка старту неохайна і велика. кнопки скинути, обліт, надіслати
 * маршрут, список маневрів і прибуття все воно виглядає по-… вибір пересування
 * теж можна гарніше оформити», і окремо про рядок під маршрутом: «"перевіряй
 * очима…" нафіга мені ця інформація? … воно заважає».
 *
 * Сторож тримає ДВІ речі, і друга важливіша за першу.
 *
 *  1. **Рядка-наказу більше немає.** Ані ключа, ані гілки, що його малює.
 *  2. **Жодна дія не загубилась разом із ним.** Прибирання рядка й прибирання
 *     кнопок — один рух руки в одному файлі, і найдешевший спосіб зробити
 *     панель гарною — це викинути з неї половину. Тому кожна з пʼяти названих
 *     власником дій перевіряється поіменно: маневри, обліт, надіслати, скинути,
 *     прибуття. І сам ФАКТ про клас доріг — теж: він мусить лишитись читним у
 *     деталях, інакше це не переїзд, а тихе видалення.
 *
 * **Чому джерело, а не композиція.** Робologістичний прогін на цій машині
 * лягає (UnsatisfiedLinkError на ARM), тож досяжність доводиться так само, як
 * її доводять інші сторожі мапи: читанням джерела. Сліпа пляма названа чесно —
 * тест бачить, що виклик НАПИСАНО, і не бачить, що комірку видно оком.
 *
 * **Чому тут є доказ власного червоного.** Кожен помічник — чиста функція над
 * рядком, тож у тестах `…ловить…` йому згодовується завідомо погане джерело, і
 * він мусить упасть. Без цього зелений прогін нічого не доводить: він так само
 * зелений, коли регекс не збігається ні з чим.
 */
class RouteBarFormTest {

    // ── чисті помічники (їх же й перевіряє доказ червоного) ──────────────

    /** Тіло оголошення [name] за балансом дужок — від першої `{` до її пари. */
    internal fun blockOf(source: String, name: String): String? {
        val at = source.indexOf(name)
        if (at < 0) return null
        val open = source.indexOf('{', at)
        if (open < 0) return null
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
        }
        return null
    }

    /** Яких із [needles] немає в [block]. Порожньо — усі на місці. */
    internal fun missingIn(block: String?, needles: List<String>): List<String> =
        if (block == null) needles else needles.filterNot { block.contains(it) }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь репозиторію не знайдено") as Nothing
    }

    private fun source(rel: String): String {
        val f = File(repoRoot(), rel)
        assertTrue("$rel зник — сторож більше нічого не стереже", f.isFile)
        return f.readText()
    }

    private val band by lazy {
        source("feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/MapCommandSheet.kt")
    }
    private val details by lazy {
        source(
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/nav/ManeuverListSheet.kt",
        )
    }

    private fun variant(basis: String, postedShare: Double = 0.0) = RouteVariant(
        points = listOf(50.0 to 30.0, 50.1 to 30.1),
        distanceMeters = 811.0,
        durationSeconds = 600.0,
        steps = emptyList(),
        speedsMps = emptyList(),
        profile = RouteProfile.FOOT,
        speedBasis = basis,
        postedShare = postedShare,
    )

    private fun info() = MapViewModel.RouteInfo(
        distanceMeters = 811.0,
        durationSeconds = 600.0,
        profile = RouteProfile.FOOT,
        destLat = 49.8337,
        destLon = 18.1606,
        destName = "VŠB CPIT",
        viaCount = 0,
        itinerary = null,
    )

    // ── 1. рядка-наказу немає ────────────────────────────────────────────

    @Test
    fun `припущені швидкості більше не піднімають застереження на смузі`() {
        val chosen = RoutePlanner.Option(
            variant = variant("ASSUMED"),
            intel = RoutePlanner.Intel.Unavailable,
        )
        val cautions = routeCautions(info(), chosen, deadlineLine = null, worstRegister = null)
        assertEquals("смуга мовчить про клас доріг — застережень бути не має", emptyList<RouteCaution>(), cautions)
    }

    @Test
    fun `ключа route_offline_classless немає в жодній мові й у жодній гілці`() {
        listOf("values-uk", "values").forEach { dir ->
            val xml = source("feature-stream/src/main/res/$dir/strings.xml")
            assertFalse("$dir ще тримає route_offline_classless", xml.contains("route_offline_classless"))
        }
        assertFalse("смуга ще малює route_offline_classless", band.contains("route_offline_classless"))
        assertFalse("смуга ще знає OfflineClassless як стан", band.contains("RouteCaution.OfflineClassless"))
    }

    // ── 2. факт живий у деталях ──────────────────────────────────────────

    @Test
    fun `деталі маршруту кажуть, звідки взялися хвилини`() {
        val basis = RouteBasis.summary(variant("ASSUMED"))
        assertNotNull("RouteBasis мовчить про припущений час", basis)
        assertTrue("речення не називає припущення: $basis", basis!!.contains("припущення"))
        // Компактно й БЕЗ наказу людині — саме це й було виміряно як «заважає».
        assertFalse("у деталі переїхав наказ, а не факт: $basis", basis.contains("перевіряй"))
        assertTrue("речення розрослося в абзац: $basis", basis.length <= 80)

        assertTrue(
            "деталі маршруту не читають RouteBasis — факт нікуди не переїхав, його просто зняли",
            details.contains("RouteBasis.summary(variant)"),
        )
        assertFalse("деталі ще показують сиру назву константи рушія", details.contains("label = variant.speedBasis"))
    }

    @Test
    fun `виміряний час мовчить там, де мовчав і раніше`() {
        assertEquals(null, RouteBasis.summary(variant("")))
    }

    // ── 3. пʼять дій власника — жодна не загубилась ──────────────────────

    @Test
    fun `усі пʼять дій досяжні зі смуги`() {
        val actions = blockOf(band, "private fun RouteActions(")
        assertNotNull("ряду дій немає — дії розсипались назад по панелі", actions)
        assertEquals(
            "у ряду дій бракує: " + missingIn(actions, ACTION_KEYS),
            emptyList<String>(),
            missingIn(actions, ACTION_KEYS),
        )

        // «Прибуття» — не дієслово над маршрутом, а його межа, тож живе на
        // рядку прибуття. Але досяжним воно мусить лишитись так само.
        val arrive = blockOf(band, "private fun ArriveRow(")
        assertNotNull("рядка прибуття немає", arrive)
        assertEquals(
            "у рядку прибуття бракує: " + missingIn(arrive, ARRIVE_KEYS),
            emptyList<String>(),
            missingIn(arrive, ARRIVE_KEYS),
        )
    }

    @Test
    fun `кожна дія справді викликається, а не лише названа`() {
        val actions = blockOf(band, "private fun RouteActions(")
        listOf("onOpenManeuverList", "onFlyover", "onShare", "onClear").forEach { cb ->
            assertTrue("ряд дій не бере $cb", actions!!.contains(cb))
        }
        // Оголошення + виклик. Один збіг означав би написаний і мертвий ряд —
        // рівно та вада, за якою в цьому дереві вже ганялись двічі.
        assertEquals(
            "ряд дій написаний, але ніде не викликається",
            2,
            Regex("""RouteActions\(""").findAll(band).count(),
        )
    }

    // ── 4. ієрархія: заливка одна на всю смугу ───────────────────────────

    @Test
    fun `залите місце в смузі рівно одне — старт`() {
        // 03.10 (аркуші мапи v1.2): «старт» більше не стара PhantomChip Solid, а
        // MapPillPrimary — `accent` з чорнилом `onAccent`. Пін переїхав разом із
        // ним: рахуються обидва написання заливки, разом їх рівно одна.
        val chipSolids = Regex("""ChipEmphasis\.Solid""").findAll(band).count()
        val pillPrimaries = Regex("""MapPillPrimary\(""").findAll(band).count()
        val solids = chipSolids + pillPrimaries
        assertEquals(
            "у смузі $solids залитих кнопок. Власник назвав саме це: «надіслати маршрут» " +
                "важило стільки ж, скільки «старт». Залита лишається одна дія.",
            1,
            solids,
        )
        val answer = blockOf(band, "private fun AnswerRow(")
        assertTrue("залита кнопка — не «старт»", answer!!.contains("R.string.route_start"))
        assertTrue("«старт» — не залита пігулка v1.2", answer.contains("MapPillPrimary("))
    }

    @Test
    fun `старт більше не має підлоги ширини`() {
        assertFalse("«старт» знову розтягують мінімальною шириною", band.contains("START_MIN_W"))
    }

    // ── 5. пігулка GPS: суфікс не переноситься по літері ─────────────────

    @Test
    fun `суфікс сумніву в пігулці GPS не ламається на стовпчик`() {
        val chip = source(
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/TrustChip.kt",
        )
        val row = blockOf(chip, "TrustGlyph(trust = toned")
            ?: chip.substringAfter("TrustGlyph(trust = toned").substringBefore("if (explaining)")
        assertTrue(
            "речення не зважене: незважений текст бере всю ширину, і «±30 м» лишається " +
                "нуль — саме так суфікс і посипався стовпчиком по літері",
            row.contains("weight(1f, fill = false)"),
        )
        assertTrue("речення може розповзтись на два рядки", row.contains("maxLines = 1"))
    }

    // ── 6. корона панелі: назва, а не сирі градуси ───────────────────────

    @Test
    fun `заголовок панелі починається з імені мети`() {
        val crown = blockOf(band, "private fun RouteCrown(")
        assertNotNull("корони панелі немає", crown)
        val nameAt = crown!!.indexOf("name ?:")
        val coordsAt = crown.indexOf("text = coords")
        assertTrue("у короні немає імені мети", nameAt >= 0)
        assertTrue("у короні немає координат — їх читають у рацію, вони мусять лишитись", coordsAt >= 0)
        assertTrue("сирі градуси знову стоять над назвою місця", nameAt < coordsAt)
        assertFalse("стара корона з самих координат жива", band.contains("DestinationCoords("))
    }

    // ── доказ власного червоного ─────────────────────────────────────────

    @Test
    fun `сторож ловить дію, викинуту з ряду`() {
        val crippled = """
            private fun RouteActions() {
                add(R.string.route_maneuvers)
                add(R.string.route_flyover)
                add(R.string.peer_route_share_chip)
            }
        """.trimIndent()
        val block = blockOf(crippled, "private fun RouteActions(")
        assertEquals(listOf("R.string.route_clear"), missingIn(block, ACTION_KEYS))
    }

    @Test
    fun `сторож ловить ряд дій, якого немає взагалі`() {
        assertEquals(ACTION_KEYS, missingIn(blockOf("fun nothing() { }", "private fun RouteActions("), ACTION_KEYS))
    }

    @Test
    fun `сторож ловить обірване тіло`() {
        assertEquals(null, blockOf("private fun RouteActions() { add(1)", "private fun RouteActions("))
    }

    private companion object {
        /** Чотири дієслова над маршрутом — рівно ті, що назвав власник. */
        val ACTION_KEYS = listOf(
            "R.string.route_maneuvers",
            "R.string.route_flyover",
            "R.string.peer_route_share_chip",
            "R.string.route_clear",
        )

        /** «Прибуття»: показати час і дати ручку відліку в обидва боки. */
        val ARRIVE_KEYS = listOf(
            "R.string.route_arrive_by",
            "R.string.route_arrive_clear",
            "onSetDeadline",
            "onClearDeadline",
        )
    }
}
