package local.phantom.companion.core.net.peer

/**
 * Власні правки як операції — порівнянням того, що лежить у сховищах, з книгою.
 *
 * Місця й лінії пишуть десяток рук (пошук, голос, аркуш точки, інструменти
 * PHANTOM); гачок у кожну з них забувся б у одинадцятій. Тому одна точка
 * спостереження: що змінилось відносно книги — те й правка цього пристрою.
 * Чуже застосування спершу пише книгу, потім сховище, тож луни не буває.
 */
object SelfMapDiff {

    fun local(
        book: SelfMap.Book,
        places: List<SelfMap.Place>,
        routes: List<SelfMap.Route>,
        settings: Map<String, String>,
        stamp: SelfMap.Stamp,
    ): List<SelfMap.Op> {
        val out = ArrayList<SelfMap.Op>()
        places.forEach { p ->
            val held = book.places[p.id]
            if (held == null || held.gone || !samePlace(held, p)) out += p.copy(stamp = stamp, gone = false)
        }
        val placeIds = places.map { it.id }.toSet()
        book.places.values.filter { !it.gone && it.id !in placeIds }.forEach { out += SelfMap.Place(it.id, stamp, gone = true) }
        routes.forEach { r ->
            val held = book.routes[r.id]
            if (held == null || held.gone || held.label != r.label || held.points != r.points) out += r.copy(stamp = stamp, gone = false)
        }
        val routeIds = routes.map { it.id }.toSet()
        book.routes.values.filter { !it.gone && it.id !in routeIds }.forEach { out += SelfMap.Route(it.id, stamp, gone = true) }
        settings.forEach { (k, v) -> if (book.settings[k]?.value != v) out += SelfMap.Setting(k, stamp, v) }
        book.settings.values.filter { it.value != null && it.key !in settings }.forEach { out += SelfMap.Setting(it.key, stamp, null) }
        return out
    }

    private fun samePlace(a: SelfMap.Place, b: SelfMap.Place): Boolean =
        a.label == b.label && a.category == b.category && a.lat == b.lat && a.lon == b.lon &&
            a.address == b.address && a.note == b.note
}
