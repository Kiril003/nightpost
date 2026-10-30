package local.phantom.companion.core.data.poi.bench

import local.phantom.companion.core.data.poi.PoiHit

data class City(val name: String, val lat: Double, val lon: Double, val hasPack: Boolean)

class Probe(val query: String, val kind: String, val right: (PoiHit) -> Boolean)

object SearchProbes {

    val KYIV = City("Київ", 50.4501, 30.5234, hasPack = true)
    val LVIV = City("Львів", 49.8419, 24.0316, hasPack = true)
    val KHARKIV = City("Харків", 49.9935, 36.2304, hasPack = true)
    val PRAHA = City("Прага", 50.0875, 14.4213, hasPack = false)

    val CITIES = listOf(KYIV, LVIV, KHARKIV, PRAHA)

    /** За кордоном, але з пакетом кузні (зріз MD, що лежить у тестових ресурсах). */
    val CHISINAU = City("Кишинів", 47.0245, 28.8323, hasPack = true)

    private fun sub(vararg s: String): (PoiHit) -> Boolean = { it.subcategory in s }
    private fun named(vararg roots: String): (PoiHit) -> Boolean = { hit ->
        val n = hit.name.lowercase().replace('\'', 'ʼ').replace('’', 'ʼ')
        roots.any { it.lowercase() in n }
    }
    private fun either(a: (PoiHit) -> Boolean, b: (PoiHit) -> Boolean): (PoiHit) -> Boolean = { a(it) || b(it) }

    private val FOOD = sub("restaurant", "cafe", "fast_food", "food_court")
    private val STATION = either(sub("station"), { hit ->
        hit.category != "street" && Regex("(вокзал|nádraží|station)(?![а-яіїєґa-z])").containsMatchIn(hit.name.lowercase())
    })
    private val MCD = named("mcdonald", "макдональдс")

    private fun chain(city: City, praha: String, chisinau: String, ua: String) = when (city) {
        PRAHA -> praha
        CHISINAU -> chisinau
        else -> ua
    }

    private fun common(city: City): List<Probe> = listOf(
        Probe("аптека", "категорія", sub("pharmacy")),
        Probe(chain(city, "Albert", "Linella", "АТБ"), "мережа", named("атб", "atb", "albert", "linella")),
        Probe("вокзал", "категорія", STATION),
        Probe("кава", "категорія", sub("cafe")),
        Probe("поїсти", "синонім", FOOD),
        Probe("банкомат", "категорія", sub("atm")),
        Probe("заправка", "категорія", sub("fuel")),
        Probe("супермаркет", "категорія", sub("supermarket")),
        Probe("пошта", "категорія", sub("post_office")),
        Probe(chain(city, "Zásilkovna", "Нова пошта", "Нова пошта"), "мережа", named("нова пошта", "zásilkovna", "nova poshta")),
        Probe("лікарня", "категорія", sub("hospital")),
        Probe("туалет", "категорія", sub("toilets")),
        Probe("готель", "категорія", sub("hotel", "hostel", "guest_house")),
        Probe("ресторан", "категорія", sub("restaurant")),
        Probe("аптіка", "описка", sub("pharmacy")),
        Probe("apteka", "трансліт", sub("pharmacy")),
        Probe("pharmacy", "англійська", sub("pharmacy")),
        Probe("coffee", "англійська", sub("cafe")),
        Probe("railway station", "англійська", STATION),
        Probe("поліція", "категорія", sub("police")),
        Probe("де переночувати", "синонім", sub("hotel", "hostel", "guest_house", "motel", "apartment")),
        Probe(chain(city, "Billa", "Merci", "Сільпо"), "мережа", named("сільпо", "silpo", "billa", "merci")),
        Probe("Макдональдс", "мережа", MCD),
        Probe("McDonalds", "англійська", MCD),
        Probe("банк", "категорія", sub("bank")),
    )

    private fun own(city: City): List<Probe> = when (city) {
        KYIV -> listOf(
            Probe("Хрещатик 22", "адреса", named("хрещатик")),
            Probe("Khreshchatyk", "трансліт", named("хрещатик")),
            Probe("Хрешатик", "описка", named("хрещатик")),
            Probe("Софійський собор", "заклад", named("софійськ")),
            Probe("Sofiivska", "трансліт", named("софіївськ")),
        )
        LVIV -> listOf(
            Probe("Городоцька 12", "адреса", named("городоцьк")),
            Probe("Horodotska", "трансліт", named("городоцьк")),
            Probe("Городоцка", "описка", named("городоцьк")),
            Probe("Оперний театр", "заклад", named("опер")),
            Probe("Rynok", "трансліт", named("ринок")),
        )
        KHARKIV -> listOf(
            Probe("Сумська 10", "адреса", named("сумськ")),
            Probe("Sumska", "трансліт", named("сумськ")),
            Probe("Сумская", "описка", named("сумськ")),
            Probe("Держпром", "заклад", named("держпром")),
            Probe("Derzhprom", "трансліт", named("держпром")),
        )
        CHISINAU -> listOf(
            Probe("Ștefan cel Mare 12", "адреса", named("ștefan cel mare", "stefan cel mare")),
            Probe("Stefan cel Mare", "трансліт", named("ștefan cel mare", "stefan cel mare")),
            Probe("Stefen cel Mare", "описка", named("ștefan cel mare", "stefan cel mare")),
            Probe("Gara Auto Centrală", "заклад", named("gara auto")),
            Probe("Линелла", "кирилиця", named("linella", "линелла")),
        )
        else -> listOf(
            Probe("Václavské náměstí 1", "адреса", named("václavské")),
            Probe("Vaclavske namesti", "трансліт", named("václavské")),
            Probe("Vaclavskee", "описка", named("václavské")),
            Probe("Karlův most", "заклад", named("karlův")),
            Probe("Карлів міст", "кирилиця", named("karlův")),
        )
    }

    fun forCity(city: City): List<Probe> = common(city) + own(city)
}
