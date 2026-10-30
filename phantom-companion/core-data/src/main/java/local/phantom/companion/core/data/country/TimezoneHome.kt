package local.phantom.companion.core.data.country

/**
 * Перший кадр мапи, поки фікса ще немає.
 *
 * FR-7 (ніч 09.10): обидва телефони в Чехії (пояс Europe/Prague, мережа cz),
 * а мапа відкривалась на Берліні — центр пака «Європа» на 100 км. До першого
 * фікса людина бачила чужу країну й мусила тиснути «Я тут». Пояс телефона
 * знає, де він, без жодного дозволу: перший кадр — країна поясу.
 *
 * Порядок: ручний вибір країни → пояс → пак → глобус.
 */
object TimezoneHome {

    /** Видно країну й сусідів, але не континент. */
    const val COUNTRY_ZOOM = 6.5

    fun of(timezoneId: String?): MapHome? =
        timezoneId?.let { ZONES[it] }?.let { (lat, lon) -> MapHome(lat = lat, lon = lon, zoom = COUNTRY_ZOOM) }

    /** Центри поясів (ті самі, що в LivingSun застосунку). */
    val ZONES: Map<String, Pair<Double, Double>> = mapOf(
        "Europe/Kyiv" to (50.45 to 30.52),
        "Europe/Kiev" to (50.45 to 30.52),
        "Europe/Uzhgorod" to (48.62 to 22.30),
        "Europe/Zaporozhye" to (47.84 to 35.14),
        "Europe/Simferopol" to (44.95 to 34.10),
        "Europe/Chisinau" to (47.01 to 28.86),
        "Europe/Warsaw" to (52.23 to 21.01),
        "Europe/Bucharest" to (44.43 to 26.10),
        "Europe/Vilnius" to (54.69 to 25.28),
        "Europe/Riga" to (56.95 to 24.11),
        "Europe/Tallinn" to (59.44 to 24.75),
        "Europe/Helsinki" to (60.17 to 24.94),
        "Europe/Prague" to (50.08 to 14.44),
        "Europe/Bratislava" to (48.15 to 17.11),
        "Europe/Budapest" to (47.50 to 19.04),
        "Europe/Berlin" to (52.52 to 13.40),
        "Europe/Vienna" to (48.21 to 16.37),
        "Europe/Paris" to (48.86 to 2.35),
        "Europe/London" to (51.51 to -0.13),
        "Europe/Madrid" to (40.42 to -3.70),
        "Europe/Lisbon" to (38.72 to -9.14),
        "Europe/Rome" to (41.90 to 12.50),
        "Europe/Amsterdam" to (52.37 to 4.90),
        "Europe/Stockholm" to (59.33 to 18.07),
        "Europe/Oslo" to (59.91 to 10.75),
        "Europe/Istanbul" to (41.01 to 28.98),
        "Asia/Tbilisi" to (41.72 to 44.79),
        "America/New_York" to (40.71 to -74.01),
        "America/Toronto" to (43.65 to -79.38),
        "America/Chicago" to (41.88 to -87.63),
        "America/Los_Angeles" to (34.05 to -118.24),
    )
}
