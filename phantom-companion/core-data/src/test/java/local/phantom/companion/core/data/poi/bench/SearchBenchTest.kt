package local.phantom.companion.core.data.poi.bench

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import local.phantom.companion.core.data.poi.PoiHit
import local.phantom.companion.core.data.poi.PoiRepository
import local.phantom.companion.core.data.geocoder.ForgeGeocoder
import local.phantom.companion.core.data.geocoder.ForgePackFixture
import local.phantom.companion.core.data.geocoder.JdbcPackDb
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Прилад пошуку: 30 запитів людини × 4 міста над тим довідником, що їде в APK,
 * справжньою SQLite з FTS4. Таблицю пише в build/reports/search-bench.md.
 *
 * Мілісекунди тут — ПК, JVM, тепла база; на A52 інше число, але той самий порядок кроків.
 *
 * Стелю [CEILING_MS] міряє НАЙМЕНШИЙ із пʼяти замірів ПРОЦЕСОРНОГО часу пошуку, а не
 * медіана стіни, і заміри йдуть колами. Машина спільна (12 потоків, ядра P і E):
 * повний прогін ганяє модулі паралельно, поруч рендерять фільм і печуть 3D. 04.10 при
 * навантаженні ~20 той самий «Софійський собор» мав медіану стіни 123 мс у повному
 * прогоні й 188 мс сам, а медіану процесора 117 мс — сусіди забирають і чергу до
 * процесора (стіна), і саме ядро (гіперпотік, повільніші E-ядра), тож процесорний час
 * теж росте. Найменший замір — той, якому сусіди завадили найменше, а справжня зайва
 * робота пошуку додається до КОЖНОГО заміру, отже й до найменшого. Стіну таблиця
 * показує поруч — для читача, не для вироку.
 */
class SearchBenchTest {

    @get:Rule
    val temp = TemporaryFolder()

    data class Row(
        val city: City,
        val probe: Probe,
        val found: Boolean,
        val right: Boolean,
        val top: PoiHit?,
        val ms: Double,
        val wallMs: Double,
        val verdict: String,
    )

    @Test
    fun `search instrument over the shipped corpus`() {
        // Пакет Молдови стоїть на телефоні весь час: у Києві й Празі він не сміє відповідати за них.
        val md = ForgeGeocoder.over(JdbcPackDb(ForgePackFixture.unpack(temp.newFolder("md")).absolutePath))!!
        val repo = PoiRepository(
            poiDao = UaCorpus.NoCache,
            gazetteer = UaCorpus.dao(),
            offlinePack = { UaCorpus.pack },
            forgeGeocoders = { listOf(md) },
        )
        val corpus = runBlocking { UaCorpus.dao().size() }
        assertTrue("довідник підозріло малий: $corpus", corpus > 400_000)

        // Прилад, що не бачить роботи, — сторож, що дивиться в порожнє: стеля мовчки
        // стала б «0 мс < 100 мс». Пошук іде на Dispatchers.IO, не в потоці тесту, тож
        // контроль — рівно там: 20 мс процесора на потоці IO мусять бути видні заміру.
        if (!(threadBean.call("isThreadCpuTimeSupported") as Boolean) || !(threadBean.call("isThreadCpuTimeEnabled") as Boolean)) {
            fail("JVM не рахує процесорний час потоків — стелю пошуку нічим міряти")
        }
        val control = cpuMs { runBlocking { withContext(Dispatchers.IO) { burn(CONTROL_MS) } } }
        assertTrue("прилад бачить лише $control мс із $CONTROL_MS мс роботи на Dispatchers.IO", control >= CONTROL_MS - 1)
        val cities = SearchProbes.CITIES + SearchProbes.CHISINAU
        val plan = cities.flatMap { city -> SearchProbes.forCity(city).map { probe -> city to probe } }
        // Заміри йдуть КОЛАМИ, а не підряд: кожен запит — раз за коло, тож його пʼять
        // замірів розкидані на весь прогін приладу, а не на пів секунди, яку може
        // цілком зʼїсти сплеск сусіда. Перше коло — розігрів (JIT, кеш бази) і в
        // замір не йде.
        val samples = plan.map { Samples() }
        val loadBefore = LoadGuard.load()
        repeat(RUNS) { round ->
            plan.forEachIndexed { i, (city, probe) -> samples[i].take(repo, city, probe, warmup = round == 0) }
        }
        val rows = plan.mapIndexed { i, (city, probe) -> samples[i].row(city, probe) }
        val report = render(cities, rows, corpus)
        File("build/reports").mkdirs()
        File("build/reports/search-bench.md").writeText(report)
        println(report)

        for (city in cities.filter { it.hasPack }) {
            val mine = rows.filter { it.city == city }
            val right = mine.count { it.right }
            assertTrue("${city.name}: правильне перше місце лише в $right із ${mine.size}", right >= FLOOR_RIGHT)
        }
        val abroad = rows.filter { !it.city.hasPack }
        assertTrue(
            "за кордоном без пакета не можна видавати українське як знахідку: " +
                abroad.filter { it.top != null }.map { "${it.probe.query} → ${it.top?.name}" },
            abroad.none { it.top != null },
        )
        LoadGuard.assumeQuiet("пошук", maxOf(loadBefore, LoadGuard.load()))
        for (city in cities.filter { it.hasPack }) {
            val slow = rows.filter { it.city == city && it.ms > CEILING_MS }
            assertTrue("${city.name}: повільні (мс процесора) ${slow.map { "${it.probe.query} ${it.ms}" }}", slow.isEmpty())
        }
    }

    private inner class Samples {
        val times = ArrayList<Double>()
        val walls = ArrayList<Double>()
        var verdict: PoiRepository.OfflineSearch? = null

        fun take(repo: PoiRepository, city: City, probe: Probe, warmup: Boolean) {
            val t0 = System.nanoTime()
            val cpu = cpuMs { verdict = runBlocking { repo.searchOfflineVerdict(probe.query, city.lat, city.lon) } }
            if (warmup) return
            times += cpu
            walls += (System.nanoTime() - t0) / 1e6
        }

        fun row(city: City, probe: Probe): Row = toRow(city, probe, times, walls, verdict!!)
    }

    private fun toRow(city: City, probe: Probe, times: List<Double>, walls: List<Double>, v: PoiRepository.OfflineSearch): Row {
        val hits = v.hits.filter { it.distanceMeters <= CITY_RADIUS_M }
        val top = hits.firstOrNull()
        return Row(
            city = city,
            probe = probe,
            found = hits.isNotEmpty(),
            right = top != null && probe.right(top),
            top = top ?: v.hits.firstOrNull(),
            ms = times.min(),
            wallMs = walls.sorted().let { it[it.size / 2] },
            verdict = when (v) {
                is PoiRepository.OfflineSearch.Hits -> if (hits.isEmpty()) "далеко" else "знайдено"
                is PoiRepository.OfflineSearch.NothingHere -> "немає"
                PoiRepository.OfflineSearch.NowhereToLook -> "нема де"
            },
        )
    }

    /**
     * `java.lang.management` у тестах живе: вони йдуть на звичайній JVM. Але модуль
     * компілюється проти android.jar, де цього пакета немає, тож бін — через рефлексію.
     */
    private val threadBean: Any =
        Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
    private val beanType = Class.forName("java.lang.management.ThreadMXBean")
    private val cpuOf = beanType.getMethod("getThreadCpuTime", Long::class.javaPrimitiveType)
    private fun Any.call(name: String): Any = beanType.getMethod(name).invoke(this)

    /**
     * Процесорний час кожного живого потоку JVM тесту. Різниця двох знімків — робота
     * всіх потоків за замір (тест + Dispatchers.IO), без потоків GC і JIT: ті не
     * Java-потоки, і їхній шум сюди не входить. Потік, народжений за замір, рахується
     * від нуля.
     */
    private fun cpuByThread(): Map<Long, Long> =
        (threadBean.call("getAllThreadIds") as LongArray).associateWith { cpuOf.invoke(threadBean, it) as Long }
            .filterValues { it >= 0 }

    private fun cpuMs(block: () -> Unit): Double {
        val before = cpuByThread()
        block()
        return cpuByThread().entries.sumOf { (id, ns) -> ns - (before[id] ?: 0L) } / 1e6
    }

    /** Рівно [ms] процесора на поточному потоці — за його власним лічильником, а не за стіною. */
    private fun burn(ms: Long) {
        val id = Thread.currentThread().id
        val start = cpuOf.invoke(threadBean, id) as Long
        var sink = 0L
        while ((cpuOf.invoke(threadBean, id) as Long) - start < ms * 1_000_000) sink += sink xor 31
        check(sink != 42L)
    }

    private fun render(cities: List<City>, rows: List<Row>, corpus: Int): String = buildString {
        appendLine("# Прилад пошуку — довідник APK ($corpus рядків), ПК/JVM, тепла база, ${RUNS - 1} замірів на запит")
        appendLine()
        appendLine("мс — найменший із замірів процесорний час пошуку (за ним стеля $CEILING_MS мс); стіна — медіана, для читача.")
        appendLine()
        for (city in cities) {
            val mine = rows.filter { it.city == city }
            val pack = when {
                city == SearchProbes.CHISINAU -> "зріз пакета кузні MD"
                city.hasPack -> "пакет UA у APK"
                else -> "пакета міста на машині немає"
            }
            appendLine(
                "## ${city.name} — $pack: знайдено ${mine.count { it.found }}/${mine.size}, " +
                    "перше правильне ${mine.count { it.right }}/${mine.size}, " +
                    "медіана ${"%.1f".format(mine.map { it.ms }.sorted()[mine.size / 2])} мс, " +
                    "макс ${"%.1f".format(mine.maxOf { it.ms })} мс (стіна — макс ${"%.1f".format(mine.maxOf { it.wallMs })} мс)",
            )
            appendLine()
            appendLine("| запит | вид | вирок | перше | правильне | мс | стіна, мс |")
            appendLine("|---|---|---|---|---|---|---|")
            for (r in mine) {
                val top = r.top?.let { "${it.name} (${it.subcategory ?: it.category}, ${(it.distanceMeters / 100).toInt() / 10.0} км)" } ?: "—"
                appendLine(
                    "| ${r.probe.query} | ${r.probe.kind} | ${r.verdict} | $top | " +
                        "${if (r.right) "так" else "ні"} | ${"%.1f".format(r.ms)} | ${"%.1f".format(r.wallMs)} |",
                )
            }
            appendLine()
        }
    }

    private companion object {
        const val RUNS = 6
        const val CITY_RADIUS_M = 30_000.0
        const val FLOOR_RIGHT = 24
        const val CEILING_MS = 100.0
        const val CONTROL_MS = 20L
    }
}
