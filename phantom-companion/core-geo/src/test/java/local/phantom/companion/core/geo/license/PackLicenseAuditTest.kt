package local.phantom.companion.core.geo.license

import local.phantom.companion.core.data.country.CountryPackRegistry
import local.phantom.companion.core.data.country.PackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ліцензія пака — поле, яке ламає встановлення, а не примітка в манiфесті.
 *
 * `LayerCatalogTest` ловить порушення в НАШОМУ каталозі на збірці; цей тест
 * ловить їх у паку, який приїде після збірки. Вбудований пак ходить тим самим
 * аудитом — інакше правило перевіряло б лише чужі паки.
 */
class PackLicenseAuditTest {

    @Test
    fun `вбудовані паки чисті перед тим самим firewall`() {
        CountryPackRegistry.packs.forEach { pack ->
            assertEquals(
                "пак «${pack.id}» не проходить власний ліцензійний аудит",
                emptyList<String>(),
                PackLicenseAudit.violations(pack.sources),
            )
        }
    }

    @Test
    fun `кожен асет пака названий у ліцензіях`() {
        CountryPackRegistry.packs.forEach { pack ->
            val declared = pack.sources.mapNotNull { it.asset }.toSet()
            val assets = listOfNotNull(
                pack.regionGeometryAsset,
                pack.regionIndexAsset,
                pack.poiAsset,
                pack.metroAsset,
                pack.surfaceAsset,
                pack.officialFacilitiesAsset,
                pack.invincibilityAsset,
            )
            assertEquals(
                "асети пака «${pack.id}» без ліцензійної картки",
                emptyList<String>(),
                assets.filterNot { it in declared },
            )
        }
    }

    @Test
    fun `джерело із забороною комерційного використання не встановлюється`() {
        val out = PackLicenseAudit.violations(
            listOf(
                PackSource(
                    id = "pl-restricted",
                    title = "Закрите джерело",
                    source = "Якийсь державний портал",
                    license = "RESTRICTED_NONCOMMERCIAL",
                    role = "OVERLAY",
                    attribution = "хтось",
                ),
            ),
        )
        assertTrue(out.any { "pl-restricted" in it })
    }

    @Test
    fun `ODbL без атрибуції не встановлюється`() {
        val out = PackLicenseAudit.violations(
            listOf(
                PackSource(
                    id = "pl-osm",
                    title = "Межі воєводств",
                    source = "OpenStreetMap",
                    license = "ODBL",
                    role = "OVERLAY",
                ),
            ),
        )
        assertTrue(out.any { "pl-osm" in it })
    }

    @Test
    fun `невідома ліцензія — не «мабуть, вільно»`() {
        val out = PackLicenseAudit.violations(
            listOf(
                PackSource(
                    id = "pl-mystery",
                    title = "Щось",
                    source = "Хтось",
                    license = "TOTALLY_FINE_TRUST_ME",
                    role = "OVERLAY",
                ),
            ),
        )
        assertTrue(out.any { "pl-mystery" in it })
    }

    @Test
    fun `комерційний шар, зшитий з OSM, ламає встановлення`() {
        val out = PackLicenseAudit.violations(
            listOf(
                PackSource(
                    id = "pl-own",
                    title = "Наші дані",
                    source = "PHANTOM",
                    license = "PROPRIETARY",
                    role = "PROPRIETARY_DATA",
                    joinsOsmTopology = true,
                ),
            ),
        )
        assertTrue(out.any { "pl-own" in it })
    }
}
