package local.phantom.companion.feature.stream.ui.map.country

import local.phantom.companion.core.data.country.CountryPackRegistry
import local.phantom.companion.core.data.country.MapHome

/**
 * ATLAS-3 — Київ помер як універсальна константа.
 *
 * `KYIV_LAT to KYIV_LON` стояв у дванадцяти місцях як «нейтральний центр»: у
 * Барселоні мапа відкривалась над Хрещатиком, а пошук «поруч» рахував радіус
 * від чужої столиці. Тепер центр каже пак, і коли пака нема — глобус, чесно.
 *
 * Порядок у місцях виклику лишається той самий: спершу те, на що оператор
 * дивиться (вʼюпорт/камера), потім його фікс, і лише тоді дім.
 */
internal fun countryHome(): MapHome = CountryPackRegistry.home()

internal fun countryHomePoint(): Pair<Double, Double> = countryHome().let { it.lat to it.lon }
