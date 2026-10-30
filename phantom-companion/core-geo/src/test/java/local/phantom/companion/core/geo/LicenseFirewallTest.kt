package local.phantom.companion.core.geo

import local.phantom.companion.core.geo.layers.LayerCatalog
import local.phantom.companion.core.geo.license.GeoLayer
import local.phantom.companion.core.geo.license.LayerRole
import local.phantom.companion.core.geo.license.LicenseClass
import local.phantom.companion.core.geo.license.LicenseFirewall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тести ліцензійного контуру.
 *
 * Це єдиний тест у проєкті, ціна провалу якого вимірюється не в багах, а в
 * юридичній вимозі відкрити комерційний код. ODbL зобовʼязує відкривати
 * ПОХІДНУ БАЗУ ДАНИХ — але не «створений твір» (відрендерений тайл, картинку,
 * результат аналізу) і не незалежний горизонтальний шар.
 *
 * Отже межа «що можна продавати» проходить не по файлах і не по модулях, а по
 * тому, чи відбувається топологічне або аналітичне обʼєднання власних даних з
 * даними OSM. Саме тому статус ліцензії — поле в коді, а порушення має ламати
 * збірку тут, у CI, а не спливати претензією через рік після релізу.
 */
class LicenseFirewallTest {

    // ————————————————————————————————————————————————————————————————
    // Бойовий каталог
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `реальний каталог шарів проходить аудит чисто`() {
        val violations = LayerCatalog.audit()
        assertTrue(
            "каталог містить ліцензійні порушення:\n" +
                violations.joinToString("\n") { "  • ${it.layerId}: ${it.rule} — ${it.detail}" },
            violations.isEmpty(),
        )
    }

    @Test
    fun `кожен шар, що вимагає атрибуції, її має`() {
        val needing = LayerCatalog.layers.filter {
            it.license in setOf(LicenseClass.ODBL, LicenseClass.CC_BY, LicenseClass.CDLA_PERMISSIVE)
        }
        assertTrue("у каталозі мають бути шари з обовʼязковою атрибуцією", needing.isNotEmpty())
        for (l in needing) {
            assertTrue("шар ${l.id} (${l.license}) лишився без атрибуції", l.attribution.isNotBlank())
        }
    }

    @Test
    fun `рядки атрибуції не дублюються і придатні до показу`() {
        val lines = LayerCatalog.attributionLines()
        assertTrue("екран «Джерела даних» не може бути порожнім", lines.isNotEmpty())
        assertEquals("рядки атрибуції дублюються", lines.size, lines.distinct().size)
        assertTrue("порожній рядок атрибуції не має сенсу", lines.none { it.isBlank() })
    }

    // ————————————————————————————————————————————————————————————————
    // Негативні: фаєрвол мусить ловити
    // ————————————————————————————————————————————————————————————————

    private fun layer(
        id: String,
        license: LicenseClass,
        role: LayerRole,
        attribution: String = "© Учасники OpenStreetMap",
        joinsOsm: Boolean = false,
    ) = GeoLayer(
        id = id,
        title = id,
        source = "тест",
        license = license,
        role = role,
        attribution = attribution,
        joinsOsmTopology = joinsOsm,
    )

    @Test
    fun `комерційний шар, зшитий з OSM топологічно, ловиться`() {
        // Найдорожча з можливих помилок: власні POI, які «трохи допомагають»
        // геометрії OSM, перетворюють комерційні дані на похідну базу.
        val bad = layer("власні-poi", LicenseClass.PROPRIETARY, LayerRole.PROPRIETARY_DATA, joinsOsm = true)

        val v = LicenseFirewall.audit(listOf(bad))

        assertTrue("зараження Share-Alike мало бути виявлене", v.any { it.rule == "share-alike-contamination" })
        assertTrue("порушення має вказувати на винний шар", v.all { it.layerId == "власні-poi" })
        assertTrue("пояснення має бути змістовним", v.first().detail.isNotBlank())
    }

    @Test
    fun `той самий шар без топологічного зшивання дозволений`() {
        // Дзеркало попереднього тесту: якщо власні дані лежать поверх карти і
        // звʼязані лише за стабільним ідентифікатором на рівні рендерингу — це
        // збірка незалежних баз, і продавати її можна.
        val ok = layer("власні-poi", LicenseClass.PROPRIETARY, LayerRole.PROPRIETARY_DATA, attribution = "")

        assertTrue("незалежний комерційний шар не мав викликати порушень",
            LicenseFirewall.audit(listOf(ok)).isEmpty())
    }

    @Test
    fun `джерело із забороною комерційного використання не проходить`() {
        // Наприклад ваги моделей, ліцензія яких прямо забороняє застосування в
        // оборонних або комерційних закритих контурах.
        val bad = layer("сегментація", LicenseClass.RESTRICTED_NONCOMMERCIAL, LayerRole.OVERLAY)

        val v = LicenseFirewall.audit(listOf(bad))

        assertTrue("заборонене джерело мало бути відхилене", v.any { it.rule == "restricted-source" })
    }

    @Test
    fun `відсутня атрибуція ловиться`() {
        val bad = layer("osm-дороги", LicenseClass.ODBL, LayerRole.BASEMAP, attribution = "  ")

        val v = LicenseFirewall.audit(listOf(bad))

        assertTrue("порожня атрибуція під ODbL — порушення", v.any { it.rule == "missing-attribution" })
    }

    @Test
    fun `похідна база не може лишатись закритою`() {
        val bad = layer("покращений-граф", LicenseClass.PROPRIETARY, LayerRole.DERIVATIVE, attribution = "")

        val v = LicenseFirewall.audit(listOf(bad))

        assertTrue("закрита похідна база — порушення", v.any { it.rule == "derivative-cannot-be-proprietary" })
    }

    @Test
    fun `суспільне надбання не вимагає атрибуції`() {
        val ok = layer("nasadem", LicenseClass.PUBLIC_DOMAIN, LayerRole.OVERLAY, attribution = "")

        assertTrue("public domain без атрибуції — законно",
            LicenseFirewall.audit(listOf(ok)).isEmpty())
    }

    @Test
    fun `аудит перевіряє весь набір, а не спиняється на першому винному`() {
        val layers = listOf(
            layer("а", LicenseClass.ODBL, LayerRole.BASEMAP, attribution = ""),
            layer("б", LicenseClass.PROPRIETARY, LayerRole.PROPRIETARY_DATA, joinsOsm = true),
            layer("в", LicenseClass.RESTRICTED_NONCOMMERCIAL, LayerRole.OVERLAY),
        )

        val ids = LicenseFirewall.audit(layers).map { it.layerId }.toSet()

        assertEquals("мали бути знайдені всі три винні шари", setOf("а", "б", "в"), ids)
    }
}
