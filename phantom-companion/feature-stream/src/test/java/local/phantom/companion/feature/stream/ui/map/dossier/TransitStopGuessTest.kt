package local.phantom.companion.feature.stream.ui.map.dossier

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Табло відправлень та маршрути» на ЗАРЯДНІЙ СТАНЦІЇ.
 *
 * 18.09.2026, телефон власника, Острава: картка транспорту висіла на
 * `VŠB CPIT` — зарядці для авто. Гейт шукав слова-підказки ПІДРЯДКОМ у
 * склеєному рядку `категорія + підкатегорія + назва`, а серед слів були
 * «station» і «станці». «Зарядна станція» містить обидва, тобто проходила
 * двічі.
 *
 * Ціна хибного спрацювання не «зайвий запит», як обіцяв старий коментар: людина
 * бачить на зарядці обіцянку розкладу й тисне в порожнечу.
 *
 * Тут тримається обидва боки: те, що зупинкою НЕ Є, і те, що нею Є — бо
 * полагодити перше, зламавши друге, означає повернути стару скаргу
 * «тапнув зупинку, а інфи нема».
 */
class TransitStopGuessTest {

    private fun target(category: String? = null, sub: String? = null, name: String? = null) =
        DossierTarget(lat = 49.8337, lon = 18.1606, nameHint = name, categoryHint = category, subcategoryHint = sub)

    @Test
    fun `зарядна станція не є зупинкою — ані українською, ані тегом`() {
        assertFalse(
            "«зарядна станція» ловилась на підрядок «станці»",
            looksLikeTransitStop(emptyMap(), target(category = "зарядна станція", name = "VŠB CPIT")),
        )
        assertFalse(
            "amenity=charging_station ловився на підрядок «station»",
            looksLikeTransitStop(
                mapOf("amenity" to "charging_station"),
                target(category = "charging_station", name = "VŠB CPIT"),
            ),
        )
    }

    @Test
    fun `інші «станції» та «station» теж не обіцяють розкладу`() {
        val notStops = listOf(
            "заправна станція" to "ОККО",
            "насосна станція" to null,
            "метеостанція" to null,
            "power_station" to null,
            "police_station" to null,
            "fire_station" to null,
        )
        for ((category, name) in notStops) {
            assertFalse(
                "«$category» не є зупинкою громадського транспорту",
                looksLikeTransitStop(emptyMap(), target(category = category, name = name)),
            )
        }
    }

    @Test
    fun `бізнес-центр не возить автобусів`() {
        // "bus" підрядком сидить усередині "business".
        assertFalse(
            "«business centre» ловився на підрядок «bus»",
            looksLikeTransitStop(emptyMap(), target(category = "business centre", name = "Ostrava Business Park")),
        )
    }

    @Test
    fun `справжні зупинки далі впізнаються — тегом`() {
        val stops = listOf(
            mapOf("highway" to "bus_stop"),
            mapOf("railway" to "tram_stop"),
            mapOf("railway" to "station"),
            mapOf("public_transport" to "platform"),
            mapOf("amenity" to "bus_station"),
            mapOf("amenity" to "ferry_terminal"),
        )
        for (tags in stops) {
            assertTrue("$tags — це зупинка", looksLikeTransitStop(tags, target()))
        }
    }

    @Test
    fun `справжні зупинки далі впізнаються — словом, разом з відмінками`() {
        val stops = listOf(
            "зупинка", "зупинки", "на зупинці", "автобусна зупинка",
            "залізничний вокзал", "вокзал", "станція метро", "метро",
            "трамвайна зупинка", "тролейбусна зупинка",
            "bus stop", "tram stop", "railway halt", "ferry platform",
            "залізнична станція", "автобусна станція", "central station",
        )
        for (hint in stops) {
            assertTrue("«$hint» — це зупинка", looksLikeTransitStop(emptyMap(), target(category = hint)))
        }
    }
}
