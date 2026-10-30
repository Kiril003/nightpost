package local.phantom.companion.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import local.phantom.companion.R
import local.phantom.companion.core.data.country.CountryPack
import local.phantom.companion.core.data.country.CountryPackRegistry
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.ui.home.PaneSlot
import local.phantom.companion.ui.home.RowGroup
import local.phantom.companion.ui.home.SubScreen

/**
 * Licensing sweep, brief 05 B5 — «Про дані та ліцензії».
 *
 * The app bundles OSM-derived POI/transit/oblast extracts and renders
 * OSM-based basemap tiles; ODbL 1.0 requires that attribution and license
 * terms stay reachable from inside the product, not just in a repo
 * README. The live map carries its own attribution in
 * `MapInstrumentPlate` (feature-stream, MapControls.kt), which is drawn
 * unconditionally — MapLibre's built-in attribution control is switched
 * OFF in `MapCanvas`, so it is emphatically NOT what satisfies the
 * requirement. This doc used to name that disabled widget, and the plate
 * that replaced it was itself gated behind an open tools panel: both
 * halves pointed at each other and neither held (measured 30.08, fixed
 * the same day, guarded by MapAttributionAlwaysVisibleTest).
 *
 * This screen is the other half — a static, scrollable ledger of every
 * license-bearing dependency the app ships, reached from Профіль › Допомога.
 *
 * Static by design: nothing here reads live state, so the screen is a
 * plain read — no ViewModel, no persistence.
 */
@Composable
fun DataLicensesScreen(onBack: () -> Unit) {
    SubScreen(title = stringResource(R.string.help_sources), onBack = onBack) {
        RowGroup(caption = stringResource(R.string.lic_map)) {
            PaneSlot { Prose(stringResource(R.string.lic_map_body)) }
        }

        RowGroup(caption = stringResource(R.string.lic_bundled)) {
            PaneSlot {
                Column {
                    // Рядок на правовласника з атрибуцій самих паків: пак, доданий
                    // пізніше, зʼявляється тут сам. Шляхів файлів людина не бачить.
                    val groups = remember { bundledAttributions(CountryPackRegistry.packs) }
                    groups.forEach { (attribution, kinds) ->
                        LicenseAssetRow(
                            name = attribution,
                            detail = kinds.map { stringResource(it) }.joinToString(", ")
                                .replaceFirstChar { it.uppercaseChar() },
                        )
                    }
                    Note(stringResource(R.string.lic_bundled_note))
                }
            }
        }

        RowGroup(caption = stringResource(R.string.lic_feeds)) {
            PaneSlot {
                Column {
                    // Так само з пакiв: застосунок називає ті стрічки, які реально
                    // оголошені, а не ті, що колись були вписані на цей екран.
                    local.phantom.companion.core.data.country.CountryPackRegistry.packs.forEach { pack ->
                        pack.alarmFeeds.forEach { feed ->
                            LicenseAssetRow(
                                name = feed.host,
                                detail = stringResource(
                                    when (feed.role) {
                                        local.phantom.companion.core.data.country.AlarmFeedRole.REGION_STATE ->
                                            R.string.lic_feed_alerts
                                        local.phantom.companion.core.data.country.AlarmFeedRole.TYPED ->
                                            R.string.lic_feed_threats
                                        else -> R.string.lic_feed_details
                                    },
                                ),
                            )
                        }
                    }
                    LicenseAssetRow(name = "RainViewer", detail = stringResource(R.string.lic_feed_radar))
                    LicenseAssetRow(name = "Open-Meteo", detail = stringResource(R.string.lic_feed_weather))
                }
            }
        }

        RowGroup(caption = stringResource(R.string.lic_fonts)) {
            PaneSlot { Prose(stringResource(R.string.lic_fonts_body)) }
        }

        RowGroup(caption = stringResource(R.string.lic_model)) {
            PaneSlot {
                LicenseAssetRow(name = stringResource(R.string.lic_model_wmm_name), detail = stringResource(R.string.lic_model_wmm))
            }
        }

        RowGroup(caption = stringResource(R.string.lic_engines)) {
            PaneSlot {
                Column {
                    LicenseAssetRow(name = "MapLibre Native", detail = "BSD-2")
                    LicenseAssetRow(name = "Filament", detail = "Apache-2.0")
                    LicenseAssetRow(name = "ONNX Runtime", detail = "MIT")
                    LicenseAssetRow(name = "Vosk", detail = "Apache-2.0")
                }
            }
        }
    }
}

/** One bundled asset / feed / engine line: the name, then a short detail under it. */
@Composable
private fun LicenseAssetRow(name: String, detail: String) {
    val c = LocalPhantomColors.current
    Column(modifier = Modifier.padding(vertical = PhantomSpace.s1)) {
        Text(text = name, style = PhantomText.body, color = c.text)
        Text(text = detail, style = PhantomText.footnote, color = c.text3)
    }
}

@Composable
private fun Prose(text: String) {
    val c = LocalPhantomColors.current
    Text(text = text, style = PhantomText.callout, color = c.text2, modifier = Modifier.padding(vertical = PhantomSpace.s1))
}

@Composable
private fun Note(text: String) {
    val c = LocalPhantomColors.current
    Text(text = text, style = PhantomText.footnote, color = c.text3, modifier = Modifier.padding(top = PhantomSpace.s2))
}

/**
 * Вбудовані дані, зведені за правовласником: атрибуція (як її вимагає
 * ліцензія) → що саме з того джерела лежить у застосунку. Похідні асети
 * (векторний індекс) окремим рядком не йдуть — їхні джерела вже названо.
 */
internal fun bundledAttributions(packs: List<CountryPack>): List<Pair<String, List<Int>>> {
    val out = LinkedHashMap<String, MutableList<Int>>()
    fun add(attribution: String, @StringRes kind: Int?) {
        if (attribution.isBlank() || kind == null) return
        val kinds = out.getOrPut(attribution) { mutableListOf() }
        if (kind !in kinds) kinds += kind
    }
    packs.forEach { pack ->
        pack.sources.forEach { add(it.attribution, assetKindRes(pack, it.asset)) }
        pack.scheduleAssets.forEach { add(it.attribution, R.string.lic_schedule) }
    }
    return out.map { it.key to it.value.toList() }
}

@StringRes
private fun assetKindRes(pack: CountryPack, asset: String?): Int? = when {
    asset == null -> null
    asset == pack.poiAsset -> R.string.lic_poi
    asset == pack.streetsAsset -> R.string.lic_streets
    asset == pack.placesAsset -> R.string.lic_places
    asset == pack.settlementsAsset -> R.string.lic_settlements
    asset == pack.metroAsset || asset == pack.surfaceAsset -> R.string.lic_transit
    asset == pack.regionGeometryAsset -> R.string.lic_oblasts
    asset == pack.officialFacilitiesAsset -> R.string.lic_official_health
    asset == pack.invincibilityAsset -> R.string.lic_invincibility_points
    pack.scheduleAssets.any { it.asset == asset } -> R.string.lic_schedule
    else -> null
}
