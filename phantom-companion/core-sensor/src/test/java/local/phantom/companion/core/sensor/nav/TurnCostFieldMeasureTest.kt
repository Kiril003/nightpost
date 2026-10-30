package local.phantom.companion.core.sensor.nav

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * ПРИЛАД, не сторож. Міряє поворотну вартість на справжньому київському паку
 * по шести звичайних поїздках і друкує таблицю; жодного вироку не виносить.
 *
 * Запуск (коридори готує scripts/extract_trip_corridors.py):
 *   ./gradlew :core-sensor:testDebugUnitTest --rerun --offline \
 *     --tests '*TurnCostFieldMeasure*' -Dphantom.tripsDir=/шлях/до/trips
 *
 * Без `-Dphantom.tripsDir` мовчки пропускається — і саме тому це НЕ ворота:
 * пропущений тест зелений на вигляд.
 *
 * ЗАМІРЯНО 18.09.2026 на `kyiv-v5`, профіль АВТО. Стара мірка (ключ — рівність
 * назв) проти геометричної; «нараховано» — скільки поворотів оплачено,
 * «справжніх» — скільки з них мають кут > 30° у вузлі графа:
 *
 *   поїздка                 довжина м      час с      нараховано/справжніх
 *   Святошин→Лівобережна   21469→21469  2575→2299          28/5  →  5/5
 *   Голосіїв→Виноградар    19006→19010  2266→2135         23/11  → 12/12
 *   дім власника→Майдан    12995→12995  1569→1449          17/7  →  7/7
 *   Позняки→Майдан         13461→13461  1731→1611         22/10  → 12/12
 *   Оболонь→Либідська      13850→13850  1739→1535          22/4  →  5/5
 *   Теремки→Лукʼянівка     16356→16588  2108→1898         31/17  → 12/12
 *
 * Разом: 143 оплачені повороти, з них справжніх 54 — тобто 89 вигаданих, по
 * 25 м і по 12 с кожен: 2225 м і 1068 с вигаданої вартості на шість поїздок
 * (371 м і 178 с на поїздку). Шість справжніх поворотів при цьому були
 * безкоштовні. Пʼять поїздок із шести лишились ТІЄЮ САМОЮ дорогою і лише
 * втратили вигадані хвилини; шоста (Теремки) подовшала на 232 м і при цьому
 * стала на 210 с швидшою, бо обійшла пʼять справжніх поворотів.
 */
class TurnCostFieldMeasureTest {

    private val straightMaxDeg = 30.0
    private val uTurnMinDeg = 150.0

    private class Trip(val name: String, val ways: List<RoadWay>, val from: Pair<Double, Double>, val to: Pair<Double, Double>)

    private fun readTrip(f: File): Trip {
        val lines = f.readLines()
        val head = lines.first().removePrefix("#").split('\t')
        val ways = ArrayList<RoadWay>(lines.size)
        for (l in lines.drop(1)) {
            val c = l.split('\t')
            if (c.size < 5) continue
            val pts = c[3].split(';').mapNotNull {
                val p = it.split(','); if (p.size == 2) p[0].toDouble() to p[1].toDouble() else null
            }
            if (pts.size < 2) continue
            val tags = c[4].split('|').mapNotNull {
                val kv = it.split('='); if (kv.size == 2) kv[0] to kv[1] else null
            }.toMap()
            ways += RoadWay(c[0].toLong(), pts, c[1].ifBlank { null }, c[2] == "1", tags)
        }
        return Trip(head[0], ways, head[1].toDouble() to head[2].toDouble(), head[3].toDouble() to head[4].toDouble())
    }

    /** Скільки доріг торкається кожного вузла — той самий ключ, що й у графа. */
    private fun junctionKeys(ways: List<RoadWay>): Map<Long, Int> {
        val m = HashMap<Long, Int>(ways.size * 4)
        for (w in ways) for (p in w.points) {
            val k = (Math.round(p.first * 1e5) shl 32) xor (Math.round(p.second * 1e5) and 0xFFFFFFFFL)
            m[k] = (m[k] ?: 0) + 1
        }
        return m
    }

    private fun angleDeg(a: Pair<Double, Double>, b: Pair<Double, Double>, c: Pair<Double, Double>): Double {
        val k = Math.cos(Math.toRadians(b.first))
        val ae = (b.second - a.second) * k; val an = b.first - a.first
        val be = (c.second - b.second) * k; val bn = c.first - b.first
        val la = Math.hypot(ae, an); val lb = Math.hypot(be, bn)
        if (la <= 0.0 || lb <= 0.0) return 0.0
        return Math.toDegrees(Math.acos(((ae * be + an * bn) / (la * lb)).coerceIn(-1.0, 1.0)))
    }

    @Test
    fun `поворотна вартість на шести київських поїздках`() {
        val dir = System.getProperty("phantom.tripsDir")
        assumeTrue("немає -Dphantom.tripsDir — вимір пропущено", dir != null)
        val files = File(dir!!).listFiles { f: File -> f.name.endsWith(".tsv") }?.sortedBy { it.name }
        assumeTrue("у $dir немає коридорів", !files.isNullOrEmpty())

        println("ТРИП\tДОРІГ\tДОВЖИНА_М\tЧАС_С\tВУЗЛІВ\tЗАЯВЛЕНО_ЗА_НАЗВОЮ\tЗ_НИХ_СПРАВЖНІХ\tСПРАВЖНІХ_УСЬОГО\tРОЗВОРОТІВ\tМС")
        for (f in files!!) {
            val t = readTrip(f)
            val graph = RoadGraph(t.ways)
            val t0 = System.nanoTime()
            val traced = LocalRouter().let { r ->
                val g = graph
                val from = g.near(t.from.first, t.from.second, LocalRouter.SNAP_RADIUS_M, LocalRouter.SNAP_CANDIDATES)
                    .firstOrNull { !RoadProfile.blocked(it.way.tags, RoadProfile.TravelMode.CAR) }
                val to = g.near(t.to.first, t.to.second, LocalRouter.SNAP_RADIUS_M, LocalRouter.SNAP_CANDIDATES)
                    .firstOrNull { !RoadProfile.blocked(it.way.tags, RoadProfile.TravelMode.CAR) }
                if (from == null || to == null) null else g.routeTraced(
                    from, to, respectOneway = true,
                    wayCostFactor = { w ->
                        if (RoadProfile.blocked(w.tags, RoadProfile.TravelMode.CAR)) LocalRouter.BLOCKED_PENALTY
                        else RoadProfile.costFactor(w.tags, RoadProfile.TravelMode.CAR)
                    },
                )
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (traced == null) {
                println("${t.name}\t${t.ways.size}\tМАРШРУТУ НЕМА\t-\t-\t-\t-\t-\t-\t$ms")
                continue
            }
            val route = LocalRouter().route(
                graph, t.from.first, t.from.second, t.to.first, t.to.second,
                mode = RoadProfile.TravelMode.CAR,
            )
            val pts = traced.points
            val jn = junctionKeys(t.ways)
            var byName = 0; var byNameReal = 0; var real = 0; var u = 0
            for (i in 1 until pts.size - 1) {
                val k = (Math.round(pts[i].first * 1e5) shl 32) xor (Math.round(pts[i].second * 1e5) and 0xFFFFFFFFL)
                val isJunction = (jn[k] ?: 0) >= 2
                val a = angleDeg(pts[i - 1], pts[i], pts[i + 1])
                val wPrev = traced.segmentWays.getOrNull(i - 1)
                val wNext = traced.segmentWays.getOrNull(i)
                val nameChange = wPrev != null && wNext != null && wPrev.id != wNext.id &&
                    !(!wNext.name.isNullOrBlank() && wNext.name.equals(wPrev.name, ignoreCase = true))
                if (nameChange) { byName++; if (a > straightMaxDeg) byNameReal++ }
                if (isJunction && a > straightMaxDeg) real++
                if (isJunction && a > uTurnMinDeg) u++
            }
            println(
                "${t.name}\t${t.ways.size}\t${"%.0f".format(route?.distanceMeters ?: 0.0)}\t" +
                    "${"%.0f".format(route?.durationSeconds ?: 0.0)}\t${pts.size}\t" +
                    "$byName\t$byNameReal\t$real\t$u\t$ms",
            )
        }
    }
}
