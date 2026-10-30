package local.phantom.companion.feature.stream.ui.map

import androidx.annotation.StringRes
import local.phantom.companion.core.geo.sky.SkyPhase
import local.phantom.companion.feature.stream.R

/**
 * MAP-1 — vector-tile provider catalogue.
 *
 * Each provider exposes a light + dark style URL pair that the Map
 * surface swaps via `MapboxMap.setStyle()` whenever
 * `LocalPhantomTheme.current.isDark` flips. Providers requiring an API
 * key fall through to [OpenFreeMap] when the operator hasn't set one
 * yet, so the first-run map is always functional without any setup.
 *
 * Defaults shipped:
 *   - **Обсидіан** (ATLAS-1, замовчування) — НЕ постачальник, а власний
 *     стиль поверх тих самих тайлів OpenFreeMap: два асети
 *     ([OBSIDIAN_NIGHT_STYLE] / [OBSIDIAN_DAY_STYLE]), випечені
 *     `:tools:obsidian:bake` з дизайн-токенів. Решта списку лишається
 *     вибором оператора — і запасним аеродромом, якщо власний стиль колись
 *     не піднімається.
 *   - **OpenFreeMap** — free, no key required, OSM-based vector tiles
 *     hosted at tiles.openfreemap.org. Light = Liberty (full-colour
 *     OSM), dark = Dark (true-dark, rgb(12,12,12) background — the
 *     style OpenFreeMap added after this catalogue first shipped with
 *     Positron as a grayscale stand-in).
 *   - **MapTiler** — Bright (light) + Dark Matter (dark). Free tier
 *     covers personal use up to 100 k tile loads / month.
 *   - **Stadia Maps** — Alidade Smooth + Alidade Smooth Dark. Free
 *     tier requires registration; the operator pastes the API key
 *     into Settings → Map.
 *   - **Custom** — operator-supplied style URL. No theme switching;
 *     the same URL serves both modes (operator's responsibility to
 *     pick a sensible style).
 *
 * The id strings are stable — they are persisted by [MapPreferences]
 * and surfaced in CONTRACT.md alongside the in-chat MapWidget map
 * configuration. Adding a new provider must NOT renumber existing ids.
 */
enum class TileProviderId(@StringRes val displayNameRes: Int) {
    OPENFREEMAP(displayNameRes = R.string.map_provider_openfreemap),
    MAPTILER(displayNameRes = R.string.map_provider_maptiler),
    STADIA(displayNameRes = R.string.map_provider_stadia),
    CUSTOM(displayNameRes = R.string.map_provider_custom),

    // ATLAS-1 — замовчування продукту, і водночас останній у списку: порядок
    // тут контракт, а не смак («Adding a new provider must NOT renumber
    // existing ids» вище). Стоїть окремо ще й тому, що це не постачальник —
    // тайли ті самі, свій у нього стиль.
    OBSIDIAN(displayNameRes = R.string.map_provider_obsidian),
}

/**
 * ATLAS-1 — власний стиль, випечений `:tools:obsidian:bake` у асети.
 *
 * Асет, а не URL: мапа мусить піднятись на пристрої, який ще не бачив мережі,
 * і перемикання ніч/день коштує рівно один `setStyle` з локального файла —
 * без ~70 бітмап, які тягнув за собою чужий темний стиль.
 */
/** Усі варіанти Обсидіана живуть під цим префіксом. */
const val OBSIDIAN_STYLE_PREFIX = "asset://styles/obsidian-"
const val OBSIDIAN_NIGHT_STYLE = "asset://styles/obsidian-night.json"
const val OBSIDIAN_DAY_STYLE = "asset://styles/obsidian-day.json"

/**
 * Сутінки — ніч, зсунута в тепло. Автоматичний шлях бере їх сам, за фазою неба
 * ([TileStyleUrls.resolve]): від шва −5° до −12° земля сутінкова, глибше — нічна.
 * Рейка «ТЕМНА/СВІТЛА» лишається двобічною: примусовий вибір не залежить від
 * сонця, інакше захід перевантажував би стиль заради нічого.
 */
const val OBSIDIAN_DUSK_STYLE = "asset://styles/obsidian-dusk.json"

/**
 * Мінімал — чесний мінімум, а не денний стиль під іншою назвою (рік він саме ним
 * і був). Та сама денна родина й та сама дорожня мережа, вода й підписи на повну;
 * угіддя, зони та будівлі приглушені до землі. Власних POI у стилі нема від
 * початку — крапки на мапі малюють НАШІ шари, і на мінімалі вони лишаються.
 */
const val OBSIDIAN_MINIMAL_STYLE = "asset://styles/obsidian-minimal.json"

/** Тло Обсидіана. Перший шар стилю — і водночас його підпис: за ним
 *  [speaksObsidian] відрізняє власний ґрунт від чужого. */
const val OBSIDIAN_GROUND_LAYER = "obsidian-ground"

internal fun obsidianUrls() = TileStyleUrls(
    light = OBSIDIAN_DAY_STYLE,
    dark = OBSIDIAN_NIGHT_STYLE,
    dusk = OBSIDIAN_DUSK_STYLE,
)

/**
 * Resolved style URLs for a given provider, with the operator's API key (if
 * any) substituted into the URL template.
 *
 * Сутінки за замовчуванням = ніч, і це не заглушка: третя земля є тільки в
 * нашого стилю. Чужий постачальник віддає рівно дві, тож просити в нього третю
 * означало б удавати те, чого він не має.
 */
data class TileStyleUrls(
    val light: String,
    val dark: String,
    val dusk: String = dark,
) {
    fun resolve(phase: SkyPhase): String = when (phase) {
        SkyPhase.DAY, SkyPhase.GOLDEN -> light
        SkyPhase.DUSK -> dusk
        SkyPhase.NIGHT -> dark
    }
}

/**
 * Resolve the live style URLs for [provider]. Falls back to OpenFreeMap
 * when the operator selected a key-required provider and hasn't set
 * one — keeps the map functional instead of showing a broken style.
 *
 * [customUrl] is used when [provider] is [TileProviderId.CUSTOM]; if
 * blank or null we fall back to OpenFreeMap as well.
 */
fun resolveStyleUrls(
    provider: TileProviderId,
    apiKey: String?,
    customUrl: String?,
): TileStyleUrls {
    val keyOk = !apiKey.isNullOrBlank()
    return when (provider) {
        TileProviderId.OBSIDIAN -> obsidianUrls()
        TileProviderId.OPENFREEMAP -> openFreeMapUrls()
        TileProviderId.MAPTILER -> if (keyOk) mapTilerUrls(apiKey!!) else openFreeMapUrls()
        TileProviderId.STADIA -> if (keyOk) stadiaUrls(apiKey!!) else openFreeMapUrls()
        TileProviderId.CUSTOM -> {
            val url = customUrl?.takeIf { it.isNotBlank() }
            if (url != null) TileStyleUrls(light = url, dark = url) else openFreeMapUrls()
        }
    }
}

/** True when [provider] needs an API key the operator must supply. */
fun providerRequiresKey(provider: TileProviderId): Boolean = when (provider) {
    TileProviderId.MAPTILER, TileProviderId.STADIA -> true
    TileProviderId.OBSIDIAN, TileProviderId.OPENFREEMAP, TileProviderId.CUSTOM -> false
}

/**
 * URL, під яким живуть ОФЛАЙН-ПАКЕТИ — і це не завжди той URL, яким мапа
 * малюється.
 *
 * Пакет — це піраміда тайлів, а не картина: Обсидіан і Liberty беруть ті самі
 * тайли з `tiles.openfreemap.org/planet` тими самими URL, тож завантажене під
 * одним працює під другим. Тому власний стиль лишає пакети на HTTP-URL, який
 * ці тайли несе, і це дає дві речі одразу: пакети, скачані до ATLAS-1, не
 * стають «незавантаженими» через перефарбування, а завантажувач MapLibre
 * лишається на перевіреному шляху, а не на `asset://`.
 */
fun offlineStyleUrlFor(
    provider: TileProviderId,
    apiKey: String?,
    customUrl: String?,
): String = when (provider) {
    TileProviderId.OBSIDIAN -> openFreeMapUrls().light
    else -> resolveStyleUrls(provider, apiKey, customUrl).light
}

/**
 * Static fallback that always works — no key, free, OSM-based.
 *
 * Одна основа на обидві полярності, і це не спрощення, а виправлення двох
 * вимірених речей. Перша: у стилі `dark` постачальника **47 шарів проти 111** у
 * `liberty` — ні POI, ні парків, утричі менше класів землі. Темна мапа була
 * буквально неповною мапою, а не темною. Друга: пакет офлайну привʼязаний до
 * URL стилю, тож завантажене вночі не показувалось удень — те саме місце
 * доводилось качати двічі.
 *
 * Полярність тепер малює [applyGroundCartography] з власної палітри, а не
 * чужий файл стилю. Через це темний і світлий день мають РІВНО ту саму землю,
 * і один пакет служить обом.
 */
internal fun openFreeMapUrls() = TileStyleUrls(
    light = "https://tiles.openfreemap.org/styles/liberty",
    dark = "https://tiles.openfreemap.org/styles/liberty",
)

internal fun mapTilerUrls(key: String) = TileStyleUrls(
    light = "https://api.maptiler.com/maps/streets-v2/style.json?key=$key",
    dark = "https://api.maptiler.com/maps/streets-v2-dark/style.json?key=$key",
)

internal fun stadiaUrls(key: String) = TileStyleUrls(
    light = "https://tiles.stadiamaps.com/styles/alidade_smooth.json?api_key=$key",
    dark = "https://tiles.stadiamaps.com/styles/alidade_smooth_dark.json?api_key=$key",
)
