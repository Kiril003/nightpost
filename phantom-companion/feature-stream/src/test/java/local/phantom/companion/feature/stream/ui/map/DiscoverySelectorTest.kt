package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.net.map.TagSelector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Жоден селектор каталогу не мав перевірки — і 08.09 у ньому оселився
 * `highway=footway[tunnel=yes]`. `OverpassQueries.around` загортає кожну
 * клаузу у ВЛАСНІ дужки (`node[$клауза]`), тож дужка всередині клаузи дає
 * `node[highway=footway[tunnel=yes]]` — Overpass відповідає HTTP 400 на
 * КОЖЕН запит, а `OverpassClient` перетворює це на «Джерело мовчить»,
 * невідрізненне від обриву мережі. Офлайн той самий рядок вимагає тег зі
 * значенням, буквально рівним `footway[tunnel=yes]`, тобто не збігається
 * ніколи. Чіп-обманка: натискається, і завжди порожньо.
 *
 * Тест міряє КОЖЕН запис каталогу, а не той один: `NearbyCatsTest` рахує
 * кількість і підписи, а рядок запиту не дивився ніхто.
 *
 * Друга половина угоди — що `OverpassQueries.around` загортає клаузу у ВЛАСНІ
 * дужки — живе в `:core-net` (`OverpassNestedBracketTest`): сам `around` там
 * `internal`, і межу модуля заради тесту не відкривають.
 */
class DiscoverySelectorTest {

    @Test
    fun `кожен селектор каталогу розбирається на клаузи`() {
        MapDiscovery.all.forEach { cat ->
            val parts = TagSelector.parts(cat.selector)
            assertTrue("«${cat.label}»: порожній селектор", parts.isNotEmpty())
            parts.forEach { part ->
                assertFalse(
                    "«${cat.label}»: клауза «$part» несе дужку — Overpass відповість HTTP 400 на кожен запит",
                    '[' in part || ']' in part,
                )
            }
            assertTrue(
                "«${cat.label}»: жодна клауза не стала тегом — офлайн-пак не збігатиметься ніколи",
                TagSelector.clauses(cat.selector).isNotEmpty(),
            )
        }
    }

}
