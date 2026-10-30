package local.phantom.companion.feature.stream.ui.map

import androidx.compose.runtime.compositionLocalOf
import local.phantom.companion.core.geo.sky.SkyPhase

/**
 * MAP-5 — operator-controlled tile config exposed to every map surface
 * (the full-screen [MapScreen]; the in-chat map card left with the old
 * stream on 04.10).
 *
 * Threaded as a [CompositionLocal] so a map surface doesn't need to change
 * signature when the operator picks a new provider/key in Settings → Map.
 * MainActivity installs the live config by observing `AppContainer.mapPrefs`;
 * a map painted while the operator is mid-edit reflects the latest pick
 * within one recomposition.
 *
 * The default fallback ([MapStyleConfig.Default]) is OpenFreeMap with
 * no key, matching [TileProviderId.OPENFREEMAP] — so previews and tests
 * that never install the live provider still render real OSM tiles
 * instead of falling back to the empty `demotiles.maplibre.org` style.
 */
data class MapStyleConfig(
    val providerId: TileProviderId,
    val apiKey: String?,
    val customUrl: String?,
) {
    /**
     * Resolved style URL for the active theme. Reuses the same
     * [resolveStyleUrls] catalogue [MapScreen] uses so both surfaces
     * never drift apart on a provider swap.
     */
    fun resolveStyleUrl(isDark: Boolean): String =
        resolveStyleUrls(providerId, apiKey, customUrl)
            // Дві землі, не три: сюди приходять поверхні без власної позиції
            // (віджет), а без позиції сонця сутінків не порахувати.
            .resolve(if (isDark) SkyPhase.NIGHT else SkyPhase.DAY)

    /** URL, під яким живуть офлайн-пакети. Не той самий, що [resolveStyleUrl]:
     *  див. [offlineStyleUrlFor]. */
    fun resolveOfflineStyleUrl(): String = offlineStyleUrlFor(providerId, apiKey, customUrl)

    companion object {
        val Default = MapStyleConfig(
            providerId = TileProviderId.OBSIDIAN,
            apiKey = null,
            customUrl = null,
        )
    }
}

val LocalMapStyleConfig = compositionLocalOf { MapStyleConfig.Default }

/**
 * QA-fix 2026-07-17 — the ONE font stack our own symbol layers may use.
 *
 * Any SymbolLayer with a textField but no textFont inherits MapLibre's
 * default stack («Open Sans Regular,Arial Unicode MS Regular»), which the
 * OpenFreeMap glyph server does not host — every glyph range 404s and the
 * label text silently never renders (Cyrillic street names included; the
 * 2026-07-17 QA drive's logcat was full of those 404s). «Noto Sans
 * Regular» is hosted by OpenFreeMap, MapTiler and the MapLibre demo tiles
 * alike, so it is safe across every [TileProviderId].
 */
val MAP_TEXT_FONT = arrayOf("Noto Sans Regular")
