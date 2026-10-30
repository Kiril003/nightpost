package local.phantom.companion.feature.stream.ui.map.source

internal object BatchDSource {
    val TRANSIT_HINT_PREFIXES = listOf(
        "зупин", "вокзал", "метро", "трамва", "автобус", "тролейбус", "потяг", "поїзд",
    )

    val STATION_WORDS = setOf(
        "station", "stations", "станція", "станції", "станцією", "станцій",
    )

    val STATION_QUALIFIER_PREFIXES = listOf(
        "залізнич", "автобус", "трамва", "тролейбус", "приміськ", "центральн", "метро",
    )
}
