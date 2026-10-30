package local.phantom.companion.core.sensor.nav

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Внесений пакет мусить збудити кеш коридорів.
 *
 * ЩО ЦЕ СТЕРЕЖЕ. `RoadPackImporter` відкриває ВЛАСНЕ сховище, вливає в нього
 * цілу область і закриває. Кеш коридорів звіряється з `revision` того
 * примірника, що живе в контейнері застосунку. Обидва примірники ведуть до
 * одного файла — тож дані мінялись, а лічильник, який про це повідомляє, ні:
 *
 *   1. `bulkImport` не чіпав `revision` взагалі (чіпав лише `put`);
 *   2. `revision` було полем ПРИМІРНИКА, тож навіть якби чіпав — збудив би
 *      не той обʼєкт, що його читають.
 *
 * Наслідок був обмежений пʼятихвилинним віком запису в кеші, тобто людина
 * їхала вчорашньою картою по свіжих даних «лише кілька хвилин». Це не
 * контракт, а везіння, і саме на цьому шві сьогодні ставлять київський пакет.
 */
@RunWith(RobolectricTestRunner::class)
class MeshRevisionTest {

    private fun ctx() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun ways(fromId: Long, n: Int): List<RoadWay> = (0 until n).map { i ->
        RoadWay(
            id = fromId + i,
            points = listOf(
                50.45 + i * 0.001 to 30.52,
                50.45 + i * 0.001 to 30.523,
            ),
            tags = mapOf("highway" to "residential"),
        )
    }

    @Test
    fun `пакет, влитий іншим примірником, будить лічильник контейнера`() {
        val container = RoadMeshStore(ctx(), "revision_probe.db")
        val before = container.revision

        // Рівно те, що робить імпортер: своє сховище, свій життєвий цикл.
        val importer = RoadMeshStore(ctx(), "revision_probe.db")
        try {
            importer.bulkImport(ways(1_000, 20))
        } finally {
            importer.close()
        }

        assertNotEquals(
            "мережа змінилась, а кеш коридорів про це не дізнається",
            before,
            container.revision,
        )
        container.close()
    }

    @Test
    fun `звичайний запис теж будить`() {
        val s = RoadMeshStore(ctx(), "revision_probe2.db")
        val before = s.revision
        s.put(ways(2_000, 5))
        assertTrue("put мусить лишатись помітним", s.revision > before)
        s.close()
    }
}
