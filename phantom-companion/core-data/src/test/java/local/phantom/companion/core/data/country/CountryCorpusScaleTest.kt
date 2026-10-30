package local.phantom.companion.core.data.country

import java.io.File
import local.phantom.companion.core.data.poi.OfflinePlacePack
import local.phantom.companion.core.data.poi.OfflineStreetPack
import local.phantom.companion.core.data.poi.PoiGazetteerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ПАМʼЯТЬ — ГОЛОВНА МЕЖА, НЕ ДИСК.
 *
 * «НАЙБІЛЬШОЇ КРАЇНИ» НЕ ІСНУЄ — стелі дві, і вони в РІЗНИХ країнах. Виміряно
 * 17.09 на всьому європейському випіку (24 країни, `bake_europe.py --status`):
 * найбільше місць у Німеччини — 802 589, найбільше вулиць у Франції —
 * 1 912 492, і найважчий пак у роздачі теж французький (36,0 МБ проти 33,7 МБ
 * німецького). Числа нижче взяті саме як ці два максимуми, тож усі 24 країни
 * лежать ПІД ними. Було б природно взяти саму Німеччину — і французькі
 * вулиці, на 39 % довші, не міряв би ніхто.
 *
 * Питання при таких довжинах не «чи влізе на диск», а «чи не покладе це апарат
 * на 2 ГБ».
 *
 * ЩО САМЕ ТУТ ДОВОДИТЬСЯ. Не швидкість і не правильність розбору — їх тримають
 * `OfflinePlacePackTest`/`OfflineStreetPackTest`. Тут одна властивість, і вона
 * єдина, що відрізняє потік від списку: ЖИВА ПАМʼЯТЬ НЕ ЗАЛЕЖИТЬ ВІД ДОВЖИНИ
 * КОРПУСУ. Корпус, збільшений удесятеро, мусить коштувати стільки ж утриманої
 * купи; якби хтось замінив `chunkLines` на «розібрати цілком у список» —
 * рівно те, що робить [local.phantom.companion.core.data.poi.OfflinePoiPack]
 * і що правильно на 21 тисячі рядків міста, — це число зросло б удесятеро
 * разом із корпусом, і тест почервонів би.
 *
 * ЧОМУ СИНТЕТИЧНИЙ КОРПУС, А НЕ КРАЇНА З ДИСКА. 50 МБ TSV не належать
 * репозиторію, а тест, який тихо пропускається без них, — це зелень, яка не
 * вміє почервоніти. Форма рядка тут дослівно та сама, що в печі (шість колонок
 * для місць, чотири для вулиць), і на справжніх файлах Німеччини цей же код
 * прогнано окремо — числа в описі хвилі.
 */
class CountryCorpusScaleTest {

    @get:Rule
    val temp = TemporaryFolder()

    /**
     * Скільки памʼяті вважається «нічого не втримано».
     *
     * Партія — 500 рядків ([OfflinePlacePack.chunkLines]), рядок газетира з
     * назвою й ключовими словами — сотні байтів, тож партія в леті це одиниці
     * сотень кілобайтів. 24 МБ — це запас у два порядки на шум JIT, буфери
     * читача й невизначеність моменту збірки сміття; водночас це вдесятеро
     * менше за те, що втримав би СПИСОК навіть на меншому з двох корпусів.
     */
    private val ceilingBytes = 24L * 1024 * 1024

    @Test
    fun `жива памʼять засіву не росте разом із корпусом місць`() {
        val small = measurePlaces(rows = 80_258)
        val large = measurePlaces(rows = 802_589)

        assertEquals(80_258, small.rows)
        assertEquals(802_589, large.rows)
        report("місця", small, large)

        assertTrue(
            "утримано ${large.retainedMb} МБ на ${large.rows} рядках — це вже не потік",
            large.retained < ceilingBytes,
        )
        // Десятикратний корпус коштує стільки ж живої памʼяті. Саме це
        // твердження ламається першим, якщо потік стане списком.
        assertTrue(
            "памʼять поїхала за корпусом: ${small.retainedMb} МБ → ${large.retainedMb} МБ",
            large.retained < small.retained + ceilingBytes,
        )
    }

    @Test
    fun `жива памʼять засіву не росте разом із корпусом вулиць`() {
        val small = measureStreets(rows = 191_249)
        val large = measureStreets(rows = 1_912_492)

        assertEquals(191_249, small.rows)
        assertEquals(1_912_492, large.rows)
        report("вулиці", small, large)

        assertTrue(
            "утримано ${large.retainedMb} МБ на ${large.rows} рядках — це вже не потік",
            large.retained < ceilingBytes,
        )
        assertTrue(
            "памʼять поїхала за корпусом: ${small.retainedMb} МБ → ${large.retainedMb} МБ",
            large.retained < small.retained + ceilingBytes,
        )
    }

    // ── вимір ───────────────────────────────────────────────────────────

    private class Measured(val rows: Int, val retained: Long, val millis: Long, val bytes: Long) {
        val retainedMb get() = retained / 1_048_576
    }

    private fun measurePlaces(rows: Int) = measure(
        file = placesFile(rows),
        parseGenerated = OfflinePlacePack::parseGenerated,
        chunks = OfflinePlacePack::chunkLines,
    )

    private fun measureStreets(rows: Int) = measure(
        file = streetsFile(rows),
        parseGenerated = OfflineStreetPack::parseGenerated,
        chunks = OfflineStreetPack::chunkLines,
    )

    /**
     * Споживач тут навмисне НЕ тримає партію: саме так поводиться
     * `PoiGazetteerDao.upsert` — записав і забув. Тримати її означало б
     * міряти власний тест, а не засів.
     *
     * Утримане рахується ПІСЛЯ примусової збірки на піку читання, а не
     * `totalMemory - freeMemory` під час: без збірки це число говорило б про
     * швидкість алокації, а питання тут інше — що ЛИШИЛОСЬ живим.
     */
    private fun measure(
        file: File,
        parseGenerated: (String) -> Long,
        chunks: (java.io.BufferedReader) -> Sequence<List<PoiGazetteerEntity>>,
    ): Measured {
        val baseline = usedHeapAfterGc()
        var seen = 0
        var peak = 0L
        var sink = 0
        val started = System.nanoTime()
        file.bufferedReader().use { reader ->
            parseGenerated(reader.readLine())
            for (chunk in chunks(reader)) {
                seen += chunk.size
                // Дотик до вмісту, щоб JIT не викинув побудову рядків цілком.
                sink += chunk.last().name.length
                if (seen % 200_000 == 0) {
                    peak = maxOf(peak, usedHeapAfterGc() - baseline)
                }
            }
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        peak = maxOf(peak, usedHeapAfterGc() - baseline)
        assertTrue(sink > 0)
        return Measured(seen, maxOf(peak, 0L), millis, file.length())
    }

    private fun usedHeapAfterGc(): Long {
        val rt = Runtime.getRuntime()
        repeat(3) {
            rt.gc()
            Thread.sleep(40)
        }
        return rt.totalMemory() - rt.freeMemory()
    }

    private fun report(what: String, small: Measured, large: Measured) {
        listOf(small, large).forEach {
            println(
                "$what: ${it.rows} рядків, ${it.bytes / 1_048_576} МБ файлу, " +
                    "${it.millis} мс, утримано ${it.retainedMb} МБ",
            )
        }
    }

    // ── корпус ──────────────────────────────────────────────────────────

    private fun placesFile(rows: Int): File =
        synth("places.tsv") { out ->
            out.write("generated=1788730799 count=$rows\n")
            repeat(rows) { i ->
                val lat = 47.3 + (i % 70_000) * 0.0001
                val lon = 5.9 + (i % 90_000) * 0.0001
                out.write("$lat\t$lon\tamenity\tpharmacy\tn${1_000_000 + i}\tApotheke am Markt $i\n")
            }
        }

    private fun streetsFile(rows: Int): File =
        synth("streets.tsv") { out ->
            out.write("generated=1788730906 count=$rows\n")
            repeat(rows) { i ->
                val lat = 47.3 + (i % 70_000) * 0.0001
                val lon = 5.9 + (i % 90_000) * 0.0001
                out.write("$lat\t$lon\tresidential\tFriedrichstraße $i\n")
            }
        }

    private fun synth(name: String, fill: (java.io.Writer) -> Unit): File =
        File(temp.newFolder(), name).also { f ->
            f.bufferedWriter().use(fill)
        }
}
