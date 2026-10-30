package local.phantom.companion.core.data.places

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.data.spatial.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ламана як названий обʼєкт: збереження, читання, правка, видалення.
 *
 * Сховище тут на підробленому DAO — правила входу (брама імені, стеля вершин,
 * пошкоджений рядок) живуть у сховищі, а не в SQL. Що таблиця справді існує на
 * диску й переживає оновлення, доводить [local.phantom.companion.core.data
 * .Migration21To22DataTest], який відкриває реальний файл через Room.
 */
class DrawnLineStoreTest {

    private class FakeDao : DrawnLineDao {
        val rows = mutableMapOf<String, DrawnLineEntity>()
        private val flow = MutableStateFlow<List<DrawnLineEntity>>(emptyList())

        override suspend fun upsert(line: DrawnLineEntity) {
            rows[line.id] = line
            flow.value = sorted()
        }

        override suspend fun getAll(): List<DrawnLineEntity> = sorted()
        override fun allFlow(): Flow<List<DrawnLineEntity>> = flow
        override suspend fun byId(id: String): DrawnLineEntity? = rows[id]
        override suspend fun labels(): List<String> = rows.values.map { it.label }
        override suspend fun deleteById(id: String): Int {
            val had = rows.remove(id) != null
            flow.value = sorted()
            return if (had) 1 else 0
        }

        override suspend fun count(): Int = rows.size

        private fun sorted() = rows.values.sortedByDescending { it.createdAt }
    }

    private fun store(dao: DrawnLineDao = FakeDao()) = DrawnLineStore(dao)

    private val kyiv = GeoPoint(50.4501, 30.5234)
    private val podil = GeoPoint(50.4650, 30.5190)
    private val lavra = GeoPoint(50.4350, 30.5570)

    @Test
    fun `збережена лінія читається назад з тими самими вершинами`() = runTest {
        val store = store()

        val saved = store.save("Обхід по набережній", listOf(kyiv, podil, lavra))

        assertTrue(saved is DrawnLineStore.SaveResult.Saved)
        val id = (saved as DrawnLineStore.SaveResult.Saved).id
        val read = store.byId(id)
        assertNotNull(read)
        assertEquals("Обхід по набережній", read!!.label)
        assertEquals(listOf(kyiv, podil, lavra), read.points)
        assertTrue(read.readable)
    }

    @Test
    fun `лінія з однієї вершини не зберігається`() = runTest {
        val store = store()

        val result = store.save("Спроба", listOf(kyiv))

        assertEquals(DrawnLineStore.SaveResult.TooFew(1), result)
        assertEquals(0, store.count())
    }

    @Test
    fun `безіменна лінія не зберігається`() = runTest {
        val store = store()

        // Та сама брама, що й у міток: те, що живе роками, мусить мати імʼя.
        assertEquals(DrawnLineStore.SaveResult.Unnamed, store.save("  ", listOf(kyiv, podil)))
        assertEquals(DrawnLineStore.SaveResult.Unnamed, store.save("лінія", listOf(kyiv, podil)))
        assertEquals(0, store.count())
    }

    @Test
    fun `дві лінії з однаковим імʼям не перезаписують одна одну`() = runTest {
        val store = store()

        store.save("Обхід Оболоні", listOf(kyiv, podil))
        val second = store.save("Обхід Оболоні", listOf(podil, lavra))

        assertEquals("Обхід Оболоні (2)", (second as DrawnLineStore.SaveResult.Saved).label)
        assertEquals(2, store.count())
    }

    @Test
    fun `видалена лінія зникає, сусідня лишається`() = runTest {
        val store = store()
        val first = store.save("Перша", listOf(kyiv, podil)) as DrawnLineStore.SaveResult.Saved
        store.save("Друга", listOf(podil, lavra))

        assertTrue(store.remove(first.id))
        assertNull(store.byId(first.id))
        assertEquals(1, store.count())
        assertEquals("Друга", store.list().single().label)
    }

    @Test
    fun `видалення неіснуючої лінії каже ні, а не мовчить`() = runTest {
        assertFalse(store().remove("нема такої"))
    }

    @Test
    fun `правка вершин переписує лінію й лишає імʼя`() = runTest {
        val store = store()
        val saved = store.save("Обхід", listOf(kyiv, podil)) as DrawnLineStore.SaveResult.Saved

        store.replaceVertices(saved.id, listOf(kyiv, podil, lavra))

        val read = store.byId(saved.id)!!
        assertEquals(listOf(kyiv, podil, lavra), read.points)
        assertEquals("Обхід", read.label)
    }

    @Test
    fun `правка не може лишити одну вершину`() = runTest {
        val store = store()
        val saved = store.save("Обхід", listOf(kyiv, podil)) as DrawnLineStore.SaveResult.Saved

        val result = store.replaceVertices(saved.id, listOf(kyiv))

        assertEquals(DrawnLineStore.SaveResult.TooFew(1), result)
        assertEquals(2, store.byId(saved.id)!!.points.size)
    }

    @Test
    fun `зсув середньої вершини зберігається`() = runTest {
        val store = store()
        val saved = store.save("Обхід", listOf(kyiv, podil, lavra)) as DrawnLineStore.SaveResult.Saved
        val moved = GeoPoint(50.4700, 30.5300)

        store.replaceVertices(saved.id, listOf(kyiv, moved, lavra))

        val read = store.byId(saved.id)!!
        assertEquals(listOf(kyiv, moved, lavra), read.points)
        // Крайні на місці: зсунули середню, а не переклали лінію.
        assertEquals(kyiv, read.points.first())
        assertEquals(lavra, read.points.last())
    }

    @Test
    fun `видалення середньої зберігає порядок решти`() = runTest {
        val store = store()
        val fourth = GeoPoint(50.4200, 30.5000)
        val saved = store.save("Обхід", listOf(kyiv, podil, lavra, fourth))
            as DrawnLineStore.SaveResult.Saved

        store.replaceVertices(saved.id, listOf(kyiv, lavra, fourth))

        // Порядок — це і є ламана: переставлені вершини дають іншу лінію тієї
        // самої довжини, і жодне число про це не скаже.
        assertEquals(listOf(kyiv, lavra, fourth), store.byId(saved.id)!!.points)
    }

    @Test
    fun `перейменування бере нове імʼя й відмовляє порожньому`() = runTest {
        val store = store()
        val saved = store.save("Стара", listOf(kyiv, podil)) as DrawnLineStore.SaveResult.Saved

        assertEquals(DrawnLineStore.SaveResult.Unnamed, store.rename(saved.id, " "))
        assertEquals("Стара", store.byId(saved.id)!!.label)

        store.rename(saved.id, "Нова")
        assertEquals("Нова", store.byId(saved.id)!!.label)
    }

    @Test
    fun `пошкоджений рядок вершин видно, а не мовчазна порожня лінія`() = runTest {
        val dao = FakeDao()
        dao.upsert(
            DrawnLineEntity(
                id = "битий",
                label = "Битий",
                vertices = "50.45,30.52;це не координати",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

        val line = store(dao).byId("битий")!!

        // Порожній список сам по собі виглядав би як «лінія без вершин»;
        // саме прапорець відрізняє збій від порожнечі.
        assertFalse(line.readable)
        assertTrue(line.points.isEmpty())
    }
}
