package local.phantom.companion.core.data.country

import java.util.Locale

data class RegionProfile(
    val country: String?,
    val alertsUa: Boolean,
    val currency: String,
    val emergency: String,
    val metric: Boolean,
) {
    companion object {
        fun of(
            signals: RegionSignals,
            overridePack: String?,
            uiLanguage: String?,
            zoneCountry: (String) -> String? = RegionResolver::zoneCountry,
        ): RegionProfile {
            val resolved = RegionResolver.resolve(signals, zoneCountry)
            val country = when (overridePack) {
                UkrainePack.id -> "UA"
                EuropePack.id -> resolved?.takeIf { RegionResolver.packId(it) == EuropePack.id }
                else -> resolved
            }
            val lang = uiLanguage?.trim()?.lowercase(Locale.ROOT)
            val alertsUa = country == "UA" || (country == null && overridePack == null && lang == "uk")
            val currency = when (country) {
                "UA" -> "UAH"
                "CZ" -> "CZK"
                "US" -> "USD"
                in EURO_COUNTRIES -> "EUR"
                else -> ""
            }
            val emergency = when (country) {
                "UA" -> "102"
                "US" -> "911"
                else -> "112"
            }
            val metric = country != "US"
            return RegionProfile(
                country = country,
                alertsUa = alertsUa,
                currency = currency,
                emergency = emergency,
                metric = metric,
            )
        }

        private val EURO_COUNTRIES = setOf(
            "AD", "AT", "BE", "CY", "DE", "EE", "ES", "FI", "FR", "GR", "HR",
            "IE", "IT", "LT", "LU", "LV", "MC", "ME", "MT", "NL", "PT", "SI",
            "SK", "SM", "VA", "XK",
        )
    }
}
