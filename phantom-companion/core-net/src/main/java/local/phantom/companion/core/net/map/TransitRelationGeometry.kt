package local.phantom.companion.core.net.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Геометрія транзитного відношення OSM: члени → звʼязні ланцюги.
 *
 * Відношення НЕ тримає свої дороги ні в порядку їзди, ні в напрямку їзди —
 * кожна дорога лежить у власному напрямку малювання. Тому склеювання точок
 * підряд іде вперед, стрибає назад тією самою вулицею і йде вперед знову: на
 * мапі це довгий вузький трикутник. Саме його власник назвав «каракулями»
 * 18.09 (Острава, збірка 13).
 *
 * Звідси три правила, і кожне тримає свій тест:
 *  - ролі, крім порожньої та `forward`, викидаються: платформа й зупинка — це
 *    теж `way`, а `backward` — це той самий шлях у зворотний бік;
 *  - кожна наступна дорога перевертається так, щоб її початок збігся з кінцем
 *    ланцюга;
 *  - справжній розрив починає НОВИЙ ланцюг, а не малюється хордою через місто.
 */
object TransitRelationGeometry {

    /** Спільний вузол Overpass повертає тим самим числом; допуск — на друк. */
    private const val JOIN_EPS_DEG = 1e-7

    private val DRAWN_ROLES = setOf("", "forward")

    /** Тіло відповіді Overpass `out geom` → звʼязні ланцюги точок (lat, lon). */
    fun parse(body: String, json: Json): List<List<Pair<Double, Double>>> = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val ways = buildList {
            root["elements"]?.jsonArray?.forEach { el ->
                el.jsonObject["members"]?.jsonArray?.forEach { m ->
                    val mo = m.jsonObject
                    if (mo["type"]?.jsonPrimitive?.content != "way") return@forEach
                    if (mo["role"]?.jsonPrimitive?.content.orEmpty() !in DRAWN_ROLES) return@forEach
                    val pts = (mo["geometry"]?.jsonArray ?: return@forEach).mapNotNull { g ->
                        val go = g.jsonObject
                        val la = go["lat"]?.jsonPrimitive?.doubleOrNull
                        val lo = go["lon"]?.jsonPrimitive?.doubleOrNull
                        if (la != null && lo != null) la to lo else null
                    }
                    if (pts.size >= 2) add(pts)
                }
            }
        }
        stitch(ways)
    }.getOrDefault(emptyList())

    /** Дороги в порядку членства → ланцюги, кожен звʼязний від початку до кінця. */
    fun stitch(ways: List<List<Pair<Double, Double>>>): List<List<Pair<Double, Double>>> {
        val chains = mutableListOf<List<Pair<Double, Double>>>()
        var chain = mutableListOf<Pair<Double, Double>>()
        // Поки в ланцюзі лежить рівно одна дорога, її НІХТО ще не орієнтував:
        // стик тоді законно шукається і з її початку теж.
        var unoriented = false
        for (way in ways) {
            val w = way.dedupe()
            if (w.size < 2) continue
            if (chain.isEmpty()) {
                chain = w.toMutableList()
                unoriented = true
                continue
            }
            val joined = when {
                w.first() joins chain.last() -> { chain.addAll(w.drop(1)); true }
                w.last() joins chain.last() -> { chain.addAll(w.asReversed().drop(1)); true }
                unoriented && w.first() joins chain.first() -> {
                    chain.reverse(); chain.addAll(w.drop(1)); true
                }
                unoriented && w.last() joins chain.first() -> {
                    chain.reverse(); chain.addAll(w.asReversed().drop(1)); true
                }
                else -> false
            }
            if (joined) {
                unoriented = false
            } else {
                chains += chain.toList()
                chain = w.toMutableList()
                unoriented = true
            }
        }
        if (chain.size >= 2) chains += chain.toList()
        return chains
    }

    private infix fun Pair<Double, Double>.joins(other: Pair<Double, Double>): Boolean =
        kotlin.math.abs(first - other.first) <= JOIN_EPS_DEG &&
            kotlin.math.abs(second - other.second) <= JOIN_EPS_DEG

    private fun List<Pair<Double, Double>>.dedupe(): List<Pair<Double, Double>> =
        filterIndexed { i, p -> i == 0 || !(p joins this[i - 1]) }
}
