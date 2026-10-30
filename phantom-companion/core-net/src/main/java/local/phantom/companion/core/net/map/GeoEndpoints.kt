package local.phantom.companion.core.net.map

/**
 * GEO-LAW — single source of truth for every online geo service endpoint.
 *
 * Why this exists (2026-07-20, licensing sweep, brief 05 B5): the app was
 * hardwired to the FOSSGIS OSRM demo (`routing.openstreetmap.de`) and public
 * Nominatim — both forbid production/commercial use. Every routing/geocoding
 * client now resolves its base URL through [GeoEndpoints.config] instead of a
 * hardcoded constant, so a self-hosted stack (OSRM/Valhalla + Photon, or the
 * PHANTOM OS relay) is a config change, not a code change.
 *
 * Contract:
 *  - A `null` URL means the service is NOT configured. Clients must return
 *    their honest-failure value (null / empty), never silently fall back to
 *    a public server. Offline paths (gazetteer, bundled packs) still work.
 *  - Debug builds may install [GeoEndpointsConfig.devFallback] so the field
 *    build keeps working against the community demo servers; release builds
 *    must never ship it (enforced at the single install site in
 *    AppContainer, gated on BuildConfig.DEBUG).
 *  - [GeoEndpointsConfig.routingTemplate] may contain the literal `{profile}`
 *    placeholder, replaced with the OSRM profile host slug (FOSSGIS runs one
 *    host per profile). A template without the placeholder is used as-is for
 *    all profiles (the normal self-hosted OSRM/Valhalla-osrm shape).
 *
 * This is a process-wide holder, not DI: OsrmClient alone is default-
 * constructed in six places across four modules; Phase-3 DI work replaces
 * this with constructor injection.
 */
object GeoEndpoints {
    @Volatile
    var config: GeoEndpointsConfig = GeoEndpointsConfig.DISABLED
}

data class GeoEndpointsConfig(
    /** OSRM-compatible routing base, e.g. `https://relay.example/osrm` or
     *  `https://routing.openstreetmap.de/routed-{profile}`. null = routing
     *  not configured → honest failure. */
    val routingTemplate: String?,
    /** Geocoder base URL (no trailing slash), e.g. `https://relay.example/photon`
     *  or `https://nominatim.openstreetmap.org`. null = online geocoding off
     *  (on-device gazetteer only). */
    val geocodeBaseUrl: String?,
    /** Which API dialect [geocodeBaseUrl] speaks. */
    val geocoderKind: GeocoderKind,
    /** Raster tile template for the chat RouteWidget minimap, with {z}/{x}/{y}
     *  placeholders. null = minimap renders its no-tile fallback. */
    val rasterTileTemplate: String?,
    /** Start/destination and the viewport go to public Transitous only after the person opts in. */
    val worldTransitOptIn: Boolean = false,
) {
    companion object {
        /** Commercial default: nothing configured, offline paths only. */
        val DISABLED = GeoEndpointsConfig(
            routingTemplate = null,
            geocodeBaseUrl = null,
            geocoderKind = GeocoderKind.NOMINATIM,
            rasterTileTemplate = null,
        )

        /** Community demo servers — DEV BUILDS ONLY (their usage policies
         *  forbid production/commercial apps). Kept as a manual escape hatch;
         *  [selfHostedLocal] is now the debug auto-default. */
        fun devFallback() = GeoEndpointsConfig(
            routingTemplate = "https://routing.openstreetmap.de/{profile}",
            geocodeBaseUrl = "https://nominatim.openstreetmap.org",
            geocoderKind = GeocoderKind.NOMINATIM,
            rasterTileTemplate = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        )

        /**
         * The self-hosted local dev stack — DEBUG AUTO-DEFAULT since
         * 2026-07-21. Replaces [devFallback] as the fallback so even dev
         * builds route/geocode against a legal, local, fast stack instead of
         * the throttled public demo servers (the demo latency was part of the
         * owner's «все тупить» report). Ports match
         * `~/phantom_ai/geo-stack/docker-compose.yml`:
         *   :8080/routed-{profile}  nginx → per-profile OSRM (BSD-2)
         *   :8088                   Nominatim API (self-hosted, GPL server —
         *                           a network service, not an app dependency)
         *
         * [host] is the dev host AS SEEN FROM THE DEVICE: `10.0.2.2` on the
         * emulator, the laptop's LAN IP on a physical phone. Override the
         * baked-in default with the `phantom.geoDevHost` gradle property, or
         * per-install in Settings → Мапа → Геосервіси.
         *
         * The chat minimap's raster tiles are not part of this stack; dev
         * keeps OSM raster (debug-only, personal-use OK). The MAIN map already
         * draws OpenFreeMap vector tiles — a self-hosted tile server is a
         * separate, heavier concern.
         */
        fun selfHostedLocal(host: String) = GeoEndpointsConfig(
            routingTemplate = "http://$host:8080/{profile}",
            geocodeBaseUrl = "http://$host:8088",
            geocoderKind = GeocoderKind.NOMINATIM,
            rasterTileTemplate = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        )
    }
}

enum class GeocoderKind { NOMINATIM, PHOTON }

/** The emulator's alias for the host laptop ([GeoEndpointsConfig.selfHostedLocal]'s
 *  baked-in default host). */
const val EMULATOR_HOST_ALIAS: String = "10.0.2.2"

/**
 * True when the configured geocoder is the emulator-only dev host. Off the
 * emulator that address resolves to nothing and every attempt burns a full
 * connect timeout before the next tier is tried, so clients skip it — but
 * they take this as a flag, never by sniffing their own base URL.
 */
val GeoEndpointsConfig.geocodeIsEmulatorLoopback: Boolean
    get() = geocodeBaseUrl?.contains(EMULATOR_HOST_ALIAS) == true

/** Resolve the routing base URL for an OSRM profile host slug (e.g.
 *  `routed-car`). null when routing is not configured. */
fun GeoEndpointsConfig.routingBaseFor(profileHost: String): String? =
    routingBasesFor(profileHost).firstOrNull()

/**
 * Кожна основа маршрутів, яку варто спробувати. Порожній список — своєї
 * немає, і це кінець: маршрут далі не шукається в мережі взагалі.
 *
 * Тут стояв протилежний закон, і він коштував дорого. Типова debug-основа —
 * LAN-адреса ноутбука; поза домашнім Wi-Fi вона не резолвиться, і власник
 * поскаржився «маршрути окрім вайфаю більше ніде не хоче створювати». Ліком
 * поставили публічний OSRM позаду LAN-шаблону — «щоб не падало». Наслідок
 * виміряний 20.09: OSRM кладе ОБИДВІ точки, початок і ціль, у шлях адреси
 * (`/route/v1/driving/$fromLon,$fromLat;$toLon,$toLat`), тобто щоразу, коли
 * людина не вдома, чужий сервер отримував звідки І куди вона їде. Скаргу на
 * доступність полікували дірою, більшою за скаргу.
 *
 * Доступність лікується не чужим сервером: перший у черзі — офлайновий
 * [local.phantom.companion.core.sensor.nav.LocalRouter] над дорожнім пакетом
 * (див. `routeGraphFirst`), далі своя налаштована основа, далі названа
 * відмова. Жодної підстановки за спиною оператора.
 */
fun GeoEndpointsConfig.routingBasesFor(profileHost: String): List<String> {
    val t = routingTemplate?.takeIf { it.isNotBlank() } ?: return emptyList()
    return listOf(if ("{profile}" in t) t.replace("{profile}", profileHost) else t)
}
