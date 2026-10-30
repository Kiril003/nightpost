package local.phantom.companion.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import local.phantom.companion.core.net.map.GeoEndpointsConfig
import local.phantom.companion.core.net.map.GeocoderKind
import local.phantom.companion.feature.stream.ui.map.TileProviderId

private const val PREFS_NAME = "phantom_map_prefs"
private const val PREFS_FALLBACK_NAME = "phantom_map_prefs_fallback"
private const val KEY_PROVIDER_ID = "provider_id"
private const val KEY_API_KEY = "api_key"
private const val KEY_CUSTOM_URL = "custom_url"
private const val KEY_ROUTING_TEMPLATE = "geo_routing_template"
private const val KEY_GEOCODE_BASE_URL = "geo_geocode_base_url"
private const val KEY_GEOCODER_KIND = "geo_geocoder_kind"
private const val KEY_RASTER_TILE_TEMPLATE = "geo_raster_tile_template"
private const val KEY_COUNTRY_REMEMBERED = "country_remembered_id"
private const val KEY_COUNTRY_OVERRIDE = "country_override_id"
private const val KEY_OPERATOR_ROLE = "operator_role"
private const val KEY_UA_ALERTS = "ua_air_alerts"

/**
 * MAP-1 — operator-controlled tile provider, key, and custom-style URL.
 *
 * Mirrors [VoicePreferences] in shape: Keystore-backed
 * EncryptedSharedPreferences with a plaintext fallback in case the
 * Android Keystore is unavailable (corrupted after reinstall, MIUI
 * Keystore bug, BouncyCastle clash). API keys are real secrets, so
 * Keystore is the right home — but the fallback exists so a degraded
 * Keystore doesn't brick the map.
 *
 * Defaults: [TileProviderId.OPENFREEMAP] with no key and no custom URL.
 * That guarantees a working first-run map without any operator setup.
 *
 * The pref Flows drive both the live MapScreen (theme-aware style
 * swap) and Settings → Map (provider picker chips, key field).
 */
class MapPreferences(context: Context) {

    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse { t ->
        Log.e("MapPreferences", "EncryptedSharedPreferences init failed — using plaintext fallback", t)
        context.getSharedPreferences(PREFS_FALLBACK_NAME, Context.MODE_PRIVATE)
    }

    private val _providerId = MutableStateFlow(loadProvider())
    val providerId: StateFlow<TileProviderId> = _providerId.asStateFlow()

    private val _apiKey = MutableStateFlow(prefs.getString(KEY_API_KEY, null))
    val apiKey: StateFlow<String?> = _apiKey.asStateFlow()

    private val _customUrl = MutableStateFlow(prefs.getString(KEY_CUSTOM_URL, null))
    val customUrl: StateFlow<String?> = _customUrl.asStateFlow()

    // GEO-LAW (2026-07-20, licensing sweep) — operator-entered online geo
    // endpoints. Empty/absent per field falls back to the self-hosted local
    // stack ONLY in debug builds (see [geoEndpointsConfig]); release builds
    // with nothing entered stay disabled — honest failure, never a silently
    // hardwired public server.
    private val _routingTemplate = MutableStateFlow(prefs.getString(KEY_ROUTING_TEMPLATE, null))
    val routingTemplate: StateFlow<String?> = _routingTemplate.asStateFlow()

    private val _geocodeBaseUrl = MutableStateFlow(prefs.getString(KEY_GEOCODE_BASE_URL, null))
    val geocodeBaseUrl: StateFlow<String?> = _geocodeBaseUrl.asStateFlow()

    private val _geocoderKind = MutableStateFlow(loadGeocoderKind())
    val geocoderKind: StateFlow<GeocoderKind> = _geocoderKind.asStateFlow()

    private val _rasterTileTemplate = MutableStateFlow(prefs.getString(KEY_RASTER_TILE_TEMPLATE, null))
    val rasterTileTemplate: StateFlow<String?> = _rasterTileTemplate.asStateFlow()

    /**
     * ATLAS-3 — країна, у якій телефон БУВ минулого разу. Не здогад із того,
     * що ми спакували: гілка «в збірці один пак, отже ми там» померла, і без
     * цієї памʼяті холодний старт удома показував би глобус до першого фіксу.
     */
    private val _rememberedCountry = MutableStateFlow(
        if (prefs.contains(KEY_COUNTRY_REMEMBERED)) {
            prefs.getString(KEY_COUNTRY_REMEMBERED, null)?.takeIf { it.isNotEmpty() }
        } else {
            // Свіже встановлення: країна телефона без дозволу, а не Україна для всіх.
            local.phantom.companion.core.data.country.RegionResolver.packId(
                runCatching { local.phantom.companion.core.data.country.RegionResolver.of(context) }.getOrNull(),
            )
        }
    )
    val rememberedCountry: StateFlow<String?> = _rememberedCountry.asStateFlow()

    /** Вибір «Повітряні тривоги України»; null — за країною телефона. */
    private val _ukraineAlerts = MutableStateFlow(
        if (prefs.contains(KEY_UA_ALERTS)) prefs.getBoolean(KEY_UA_ALERTS, false) else null,
    )
    val ukraineAlerts: StateFlow<Boolean?> = _ukraineAlerts.asStateFlow()

    fun setUkraineAlerts(on: Boolean) {
        prefs.edit().putBoolean(KEY_UA_ALERTS, on).apply()
        _ukraineAlerts.value = on
    }

    /** Ручний вибір країни. Null — авто, тобто за фіксом. */
    private val _countryOverride = MutableStateFlow(prefs.getString(KEY_COUNTRY_OVERRIDE, null))
    val countryOverride: StateFlow<String?> = _countryOverride.asStateFlow()

    fun rememberCountry(id: String?) {
        if (_rememberedCountry.value == id) return
        if (id == null) {
            prefs.edit().putString(KEY_COUNTRY_REMEMBERED, "").apply()
        } else {
            prefs.edit().putString(KEY_COUNTRY_REMEMBERED, id).apply()
        }
        _rememberedCountry.value = id
    }

    fun setCountryOverride(id: String?) {
        prefs.edit().putString(KEY_COUNTRY_OVERRIDE, id).apply()
        _countryOverride.value = id
    }

    /** Ким людина себе назвала. Від цього залежить, що пропонує мапа. */
    private val _operatorRole = MutableStateFlow(
        local.phantom.companion.core.data.country.OperatorRole.of(
            prefs.getString(KEY_OPERATOR_ROLE, null),
        ),
    )
    val operatorRole: StateFlow<local.phantom.companion.core.data.country.OperatorRole> =
        _operatorRole.asStateFlow()

    fun setOperatorRole(role: local.phantom.companion.core.data.country.OperatorRole) {
        prefs.edit().putString(KEY_OPERATOR_ROLE, role.name).apply()
        _operatorRole.value = role
        local.phantom.companion.core.data.country.CountryPackRegistry.setRole(role)
    }

    private fun loadGeocoderKind(): GeocoderKind {
        val raw = prefs.getString(KEY_GEOCODER_KIND, null)
        return GeocoderKind.values().firstOrNull { it.name == raw } ?: GeocoderKind.NOMINATIM
    }

    // ATLAS-1: замовчування — власний стиль. Явно збережений чужий вибір
    // лишається в силі: людина, яка сама попросила Liberty, її й отримує.
    private fun loadProvider(): TileProviderId {
        val raw = prefs.getString(KEY_PROVIDER_ID, TileProviderId.OBSIDIAN.name)
        return TileProviderId.values().firstOrNull { it.name == raw }
            ?: TileProviderId.OBSIDIAN
    }

    fun setProvider(provider: TileProviderId) {
        prefs.edit().putString(KEY_PROVIDER_ID, provider.name).apply()
        _providerId.value = provider
    }

    fun setApiKey(value: String?) {
        prefs.edit().putString(KEY_API_KEY, value).apply()
        _apiKey.value = value
    }

    fun setCustomUrl(value: String?) {
        prefs.edit().putString(KEY_CUSTOM_URL, value).apply()
        _customUrl.value = value
    }

    fun setRoutingTemplate(value: String?) {
        prefs.edit().putString(KEY_ROUTING_TEMPLATE, value).apply()
        _routingTemplate.value = value
    }

    fun setGeocodeBaseUrl(value: String?) {
        prefs.edit().putString(KEY_GEOCODE_BASE_URL, value).apply()
        _geocodeBaseUrl.value = value
    }

    fun setGeocoderKind(value: GeocoderKind) {
        prefs.edit().putString(KEY_GEOCODER_KIND, value.name).apply()
        _geocoderKind.value = value
    }

    fun setRasterTileTemplate(value: String?) {
        prefs.edit().putString(KEY_RASTER_TILE_TEMPLATE, value).apply()
        _rasterTileTemplate.value = value
    }

    /**
     * GEO-LAW — the config every online geo client resolves through
     * ([GeoEndpoints.config][local.phantom.companion.core.net.map.GeoEndpoints]).
     * A blank/absent stored field defaults, in DEBUG builds only, to the
     * self-hosted local stack
     * ([GeoEndpointsConfig.selfHostedLocal] at [BuildConfig.GEO_DEV_HOST] —
     * the OSRM/Nominatim containers under `~/phantom_ai/geo-stack/`). Release
     * builds default to null (disabled), matching the GEO-LAW contract that a
     * hardwired server never ships silently. The public community demo servers
     * ([GeoEndpointsConfig.devFallback]) are no longer an auto-default — they
     * remain only as a manual URL an operator can paste in Settings.
     */
    fun geoEndpointsConfig(): GeoEndpointsConfig {
        val fallback = geoFallbackFor(
            debug = local.phantom.companion.BuildConfig.DEBUG,
            devHost = local.phantom.companion.BuildConfig.GEO_DEV_HOST,
        )
        return GeoEndpointsConfig(
            routingTemplate = _routingTemplate.value?.takeIf { it.isNotBlank() }
                ?: fallback.routingTemplate,
            geocodeBaseUrl = _geocodeBaseUrl.value?.takeIf { it.isNotBlank() }
                ?: fallback.geocodeBaseUrl,
            geocoderKind = _geocoderKind.value,
            rasterTileTemplate = _rasterTileTemplate.value?.takeIf { it.isNotBlank() }
                ?: fallback.rasterTileTemplate,
        )
    }
}

/**
 * Що підставити, коли оператор не вписав жодного сервера.
 *
 * Реліз — DISABLED, і це не сувора формальність: [GeoEndpointsConfig.devFallback]
 * — це демо-OSRM FOSSGIS, публічний Nominatim і tile.openstreetmap.org, кожен із
 * яких прямо забороняє продуктові застосунки. Ця гілка стояла в релізі й робила
 * з ліцензійної помилки стандарт. Вимкнено — чесна відмова: офлайн-роутер і
 * газетир працюють, а мапа каже, що онлайн-сервіс не налаштовано.
 *
 * Винесено з класу, щоб гілку релізу можна було перевірити тестом: усередині
 * [MapPreferences] вона недосяжна — юніт-тести бачать лише `BuildConfig.DEBUG`.
 */
internal fun geoFallbackFor(debug: Boolean, devHost: String): GeoEndpointsConfig =
    if (debug) GeoEndpointsConfig.selfHostedLocal(devHost) else GeoEndpointsConfig.DISABLED
