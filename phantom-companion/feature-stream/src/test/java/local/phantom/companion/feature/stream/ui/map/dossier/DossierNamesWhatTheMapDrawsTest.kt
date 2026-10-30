package local.phantom.companion.feature.stream.ui.map.dossier

import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.core.data.poi.PlaceTier
import local.phantom.companion.core.data.poi.PlaceTiers
import local.phantom.companion.feature.stream.ui.map.MapText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Дзеркало контракту між ШАРОМ і ДОСЬЄ: те, що мапа обіцяє тримати на склі
 * під тиском, вона мусить уміти й НАЗВАТИ, коли по ньому торкнулись.
 *
 * ЧОМУ ЦЕ ПОТРІБНО САМЕ ЗАРАЗ. Доти жодне джерело й жодна криниця на скло не
 * потрапляли — стеля кадру викидала їх першими, — тож прогалина в словнику
 * досьє нічого не коштувала: те, чого не видно, не торкаються. Коло по родах
 * підняло їх на скло, і прогалина стала видимою. Заміряно цією ж перевіркою
 * перед лагодженням — одинадцять родів, які мапа тримає під тиском, картка
 * називала «просто місцем», без жодного слова про вид:
 *
 *   ambulance_station, car_repair, community_centre, generator, spring,
 *   substation, subway, toilets, transformer, tyres, water_well
 *
 * Тобто вода, енергія в блекаут і станція швидкої. Це та сама вада, що вже
 * названа в домі: закритий словник, у якого є гілка «інакше», і ця гілка тихо
 * стверджує неправду.
 *
 * ДВІ РІЗНІ ПЛАНКИ, і різниця між ними названа вголос, а не прихована.
 *
 *  - СЛОВО ВИДУ вимагається від УСЬОГО, що переживає розрідження. Картка без
 *    жодного слова про вид не каже нічого — а для безіменного джерела вид це
 *    все, що вона скаже взагалі (30% корпусу країни йде без імені).
 *  - КАТЕГОРІЯ (а з нею й акцент картки) вимагається лише від КРИТИЧНОГО
 *    ярусу. Підстанція, шиномонтаж і туалет лишаються «Місцем» не з недогляду:
 *    жодна з наявних категорій не описує їх чесно, а вигадати дві нові фарби
 *    в палітру — рішення про вигляд, і воно належить власникові, не цьому
 *    файлу. Слово виду вони при цьому мають, тож картка мовчазною не виходить.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "uk")
class DossierNamesWhatTheMapDrawsTest {

    @Before
    fun setUp() {
        MapText.bind(ApplicationProvider.getApplicationContext())
    }

    private val underPressure: Set<String> = PlaceTiers.CRITICAL_KEYS + PlaceTiers.SUSTAIN_KEYS

    /**
     * `subcategoryHint` — рівно той шлях, яким рід приходить із тапу:
     * `MapCanvasWiring` бере `subcategory` з ознак піна й кладе в ціль досьє.
     */
    @Test
    fun `every kind the map keeps under pressure has a Ukrainian kind label`() {
        val silent = underPressure.filter { kindLabelUk(emptyMap(), subcategoryHint = it) == null }.sorted()
        assertEquals("рід без українського слова у досьє: $silent", emptyList<String>(), silent)
    }

    @Test
    fun `every critical kind has a category of its own`() {
        val generic = PlaceTiers.CRITICAL_KEYS
            .filter { dossierCategoryOf(emptyMap(), subcategoryHint = it) == DossierCategory.PLACE }
            .sorted()
        assertEquals("критичний рід виходить «просто місцем»: $generic", emptyList<String>(), generic)
    }

    /** Жодне слово виду не сміє бути англійським токеном — саме так помер «LIBRARY». */
    @Test
    fun `no kind label leaks a raw OSM token`() {
        val latin = underPressure
            .mapNotNull { key -> kindLabelUk(emptyMap(), subcategoryHint = key)?.let { key to it } }
            .filter { (_, label) -> label.any { it in 'a'..'z' || it in 'A'..'Z' } }
        assertEquals("сирий токен OSM у картці: $latin", emptyList<Pair<String, String>>(), latin)
    }

    /**
     * Вода — окремо й поіменно, бо саме вона була приводом. У корпусі країни
     * 12 193 водні точки, і воду шукають не за назвою: вид — це вся картка.
     */
    @Test
    fun `water says what it is`() {
        assertEquals("джерело", kindLabelUk(emptyMap(), subcategoryHint = "spring"))
        assertEquals("криниця", kindLabelUk(emptyMap(), subcategoryHint = "water_well"))
        assertEquals("питна вода", kindLabelUk(emptyMap(), subcategoryHint = "drinking_water"))
        listOf("spring", "water_well", "drinking_water").forEach {
            assertEquals(
                "вода мусить читатись безпекою, а не «місцем»: $it",
                DossierCategory.SAFETY,
                dossierCategoryOf(emptyMap(), subcategoryHint = it),
            )
        }
    }

    /**
     * Теги сильніші за хінт — і мусять давати ТЕ САМЕ слово. Рядок корпусу
     * тегів не несе (крім `_ref`), а живий Overpass несе; картка не сміє
     * перемальовуватись з однієї назви на іншу, коли доїде мережа.
     */
    @Test
    fun `tags and hints agree on the word`() {
        assertEquals("джерело", kindLabelUk(mapOf("natural" to "spring")))
        assertEquals("криниця", kindLabelUk(mapOf("man_made" to "water_well")))
        assertEquals("підстанція", kindLabelUk(mapOf("power" to "substation")))
        assertEquals(DossierCategory.SAFETY, dossierCategoryOf(mapOf("natural" to "spring")))
        assertEquals(DossierCategory.HEALTH, dossierCategoryOf(mapOf("amenity" to "ambulance_station")))
    }

    /**
     * Джерело з краном — це питна вода, і саме так воно й має підписатись:
     * `amenity` стоїть у черзі ключів ПЕРЕД `natural`, тож точніше виграє.
     */
    @Test
    fun `a tapped spring with a tap is drinking water`() {
        assertEquals(
            "питна вода",
            kindLabelUk(mapOf("natural" to "spring", "amenity" to "drinking_water")),
        )
    }

    /**
     * Громадський центр — НЕ «пункт незламності». PHANTOM веде окремий реєстр
     * пунктів; вивести пункт із самого лише тега означало б обіцяти тепло й
     * світло там, де їх ніхто не обіцяв.
     */
    @Test
    fun `a community centre does not promise heat and light`() {
        val label = kindLabelUk(emptyMap(), subcategoryHint = "community_centre")
        assertEquals("громадський центр", label)
        assertTrue("картка обіцяє незламність за одним тегом", !label!!.contains("незламн"))
    }

    /** Перевірка мусить справді щось накривати, а не радіти порожній множині. */
    @Test
    fun `the guard covers a real set of kinds`() {
        assertTrue("стережених родів підозріло мало: ${underPressure.size}", underPressure.size >= 30)
        assertTrue(PlaceTiers.CRITICAL_KEYS.size >= 15)
        assertEquals(PlaceTier.CRITICAL, PlaceTiers.of("natural", "spring"))
    }
}
