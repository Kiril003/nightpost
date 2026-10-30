package local.phantom.companion.feature.stream.ui.map.source

internal object BatchHSource {
    const val POI_NAME_CAMERA = "Камера"
    const val POI_NAME_BARRIER = "Шлагбаум"
    const val POI_NAME_GATE = "Ворота"

    val UK_ADDRESS_PREFIXES =
        Regex("(?iu)(?<![\\p{L}\\p{N}])(вул\\.|вулиця|просп\\.|проспект|пров\\.|провулок|буд\\.|будинок|б\\.)\\s*")

    val LEADING_ADDRESS_ABBREV =
        Regex("^(?iu)(ул\\.|улица|ul\\.|ulica|str\\.|pl\\.)\\s+")

    val CLOCK_REGION_NAME = Regex("""^регіон\s*·\s*\d{2}\.\d{2}\s+\d{2}:\d{2}$""")

    val LEGACY_CORRIDOR_NAME = Regex("""^[Кк]оридор\s*[:·]\s*.*?(\d+)/(\d+)\s*$""")
}
