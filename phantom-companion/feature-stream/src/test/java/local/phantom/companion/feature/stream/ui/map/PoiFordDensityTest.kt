package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.feature.stream.ui.map.atlas.SceneMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Правда, якої стільки, що крізь неї не видно країни».
 *
 * Заміряно на національному корпусі місць 16.09 (175 685 рядків, витяг усієї
 * України): рід `ford` дає 25 991 рядок, із них 25 871 з ІДЕНТИЧНИМ іменем
 * «Брід» — кожне сьоме місце в країні. Брід не є місцем, куди йдуть: це
 * властивість дороги, і PHANTOM уже розповідає про неї двічі — шаром «Броди»
 * (вмикає оператор) і рядком обережності маршруту.
 *
 * Той самий клас, що дворові барʼєри у [PoiBarrierDensityTest]: дані
 * правильні, шар — ні.
 */
class PoiFordDensityTest {

    private var nextId = 1L

    private fun poi(
        name: String,
        category: String,
        sub: String? = null,
        lat: Double = 50.45,
        lon: Double = 30.52,
    ) = PoiEntity(
        id = nextId++,
        lat = lat,
        lon = lon,
        name = name,
        category = category,
        subcategory = sub,
        tags = emptyMap(),
    )

    /** Рівно ті пари рід/підвид, які дає національний корпус. */
    private fun fords() = listOf(
        poi("Брід", "ford", "yes"),
        poi("Брід (камені)", "ford", "stepping_stones"),
        poi("Брід (сезонний)", "ford", "seasonal"),
        poi("Брід (пересихає)", "ford", "intermittent"),
        poi("Брід", "ford", "level_crossing"),
        poi("Брід", "ford", "stream"),
    )

    @Test
    fun `жоден брід не потрапляє на суцільний шар місць`() {
        fords().forEach { ford ->
            assertFalse(
                "«${ford.name}» (${ford.category}/${ford.subcategory}) мусив лишитись шару «Броди»",
                POIOverlay.drawableOnGround(ford),
            )
        }
    }

    /**
     * ЧОМУ рід, а не імʼя: закритий словник імен зловив би «Брід» і проґавив
     * «Брід (сезонний)» — рівно та вада, через яку гілка «інакше» в інших
     * місцях цього дерева вже одного разу почала брехати.
     */
    @Test
    fun `правило тримається на роді, а не на списку імен`() {
        val odd = poi("Брід (пересихає)", "ford", "intermittent")
        assertTrue("саме імʼя нічим не завинило", POIOverlay.poiRenderable(odd.name))
        assertFalse("а рід — завинив", POIOverlay.drawableOnGround(odd))
    }

    /**
     * Брід у ІНШОМУ роді — це вже не переправа, а памʼятка чи джерело під тим
     * самим словом (корпус має по одному такому). Вони лишаються: їх показує
     * не шар бродів, а саме цей.
     */
    @Test
    fun `однойменна памʼятка не є бродом і лишається на мапі`() {
        assertTrue(POIOverlay.drawableOnGround(poi("Брід", "tourism", "attraction")))
        assertTrue(POIOverlay.drawableOnGround(poi("Брід", "historic", "battlefield")))
        assertTrue(POIOverlay.drawableOnGround(poi("Брід", "natural", "spring")))
    }

    /** Місто Броди на Львівщині — населений пункт, а не переправа. */
    @Test
    fun `місто Броди не плутається з переправою`() {
        assertTrue(POIOverlay.drawableOnGround(poi("Броди", "place", "city")))
    }

    @Test
    fun `решта корпусу шару не втратила`() {
        listOf(
            poi("Аптека", "amenity", "pharmacy"),
            poi("Джерело", "natural", "spring"),
            poi("Криниця", "man_made", "water_well"),
            poi("Туалет", "amenity", "toilets"),
            poi("АТБ-Маркет", "shop", "supermarket"),
        ).forEach {
            assertTrue("«${it.name}» зник із шару разом із бродами", POIOverlay.drawableOnGround(it))
        }
    }

    /** Лінза місць бере той самий заслін — інакше брід повернувся б через неї. */
    @Test
    fun `лінза місць теж не пускає бродів`() {
        val mixed = fords() + poi("Аптека", "amenity", "pharmacy")
        val shown = PlaceLens.select(mixed, SceneMode.CALM, zoom = 16.0)
        assertEquals(1, shown.size)
        assertEquals("Аптека", shown.single().name)
    }

    /**
     * Брід лишається знаним для ПОШУКУ й досʼє — вони живляться репозиторієм,
     * не цим шаром. Перевірка стоїть тут, щоб зняття заслону на землі ніхто не
     * прийняв за «бродів більше немає в застосунку».
     */
    @Test
    fun `заслін стосується лише шару, не словника застосунку`() {
        assertTrue(
            "«ford» мусить лишатись у роді, який знає решта застосунку",
            "ford" in POIOverlay.OWN_LAYER_CATEGORIES,
        )
    }
}
