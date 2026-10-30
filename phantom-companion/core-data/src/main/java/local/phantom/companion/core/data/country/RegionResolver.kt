package local.phantom.companion.core.data.country

import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import java.util.Locale
import java.util.TimeZone

data class RegionSignals(
    val networkIso: String? = null,
    val simIso: String? = null,
    val timeZoneId: String? = null,
    val localeCountry: String? = null,
)

/**
 * Країна телефона без жодного дозволу: мережа, SIM, пояс, мова.
 *
 * Wi-Fi тут не допоможе: точки доступу й BSSID Android віддає лише з дозволом
 * на місце. Запит IP до чужого сервера — витік адреси, його немає свідомо.
 */
object RegionResolver {

    // Пояс Криму сильніший за мережу: окупаційний оператор віддає «ru», а Крим — Україна.
    fun resolve(signals: RegionSignals, zoneCountry: (String) -> String? = ::zoneCountry): String? =
        "UA".takeIf { signals.timeZoneId == CRIMEA_ZONE }
            ?: iso(signals.networkIso)
            ?: iso(signals.simIso)
            ?: signals.timeZoneId?.let { runCatching { zoneCountry(it) }.getOrNull() }?.let(::iso)
            ?: iso(signals.localeCountry)

    fun zoneCountry(zoneId: String): String? =
        if (zoneId in UA_ZONES) "UA" else runCatching { android.icu.util.TimeZone.getRegion(zoneId) }.getOrNull()

    fun packId(iso: String?): String? = when (iso) {
        null -> null
        UkrainePack.id -> UkrainePack.id
        in EUROPE -> EuropePack.id
        else -> null
    }

    fun read(context: Context): RegionSignals {
        val tm = runCatching { context.getSystemService(TelephonyManager::class.java) }.getOrNull()
        // CDMA віддає країну мережі ненадійно; під час польоту — порожньо.
        val network = tm?.takeIf { it.phoneType != TelephonyManager.PHONE_TYPE_CDMA }?.let { t ->
            runCatching { t.networkCountryIso }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: anySlotNetwork(t)
        }
        return RegionSignals(
            networkIso = network,
            simIso = tm?.let { runCatching { it.simCountryIso }.getOrNull() },
            timeZoneId = TimeZone.getDefault().id,
            localeCountry = Locale.getDefault().country,
        )
    }

    fun of(context: Context): String? = resolve(read(context))

    private fun anySlotNetwork(tm: TelephonyManager): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val slots = runCatching { tm.activeModemCount }.getOrDefault(0)
        return (0 until slots).firstNotNullOfOrNull { slot ->
            runCatching { tm.getNetworkCountryIso(slot) }.getOrNull()?.takeIf { it.isNotBlank() }
        }
    }

    private fun iso(raw: String?): String? =
        raw?.trim()?.uppercase(Locale.ROOT)
            ?.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } && it != "ZZ" && it != "XX" }

    private const val CRIMEA_ZONE = "Europe/Simferopol"
    private val UA_ZONES = setOf("Europe/Kyiv", "Europe/Kiev", "Europe/Uzhgorod", "Europe/Zaporozhye", CRIMEA_ZONE)

    private val EUROPE = setOf(
        "AD", "AL", "AT", "BA", "BE", "BG", "BY", "CH", "CY", "CZ", "DE", "DK", "EE", "ES", "FI", "FO",
        "FR", "GB", "GG", "GI", "GR", "HR", "HU", "IE", "IM", "IS", "IT", "JE", "LI", "LT", "LU", "LV",
        "MC", "MD", "ME", "MK", "MT", "NL", "NO", "PL", "PT", "RO", "RS", "SE", "SI", "SK", "SM", "VA", "XK",
    )
}
