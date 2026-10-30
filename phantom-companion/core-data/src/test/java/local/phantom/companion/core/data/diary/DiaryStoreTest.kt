package local.phantom.companion.core.data.diary

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiaryStoreTest {

    private lateinit var dao: FakeDecisionEntryDao
    private lateinit var store: DiaryStore

    @Before
    fun setup() {
        dao = FakeDecisionEntryDao()
        store = DiaryStore(dao)
    }

    @Test
    fun `saveNew assigns id and timestamp via injected factories`() = runTest {
        var nowCalls = 0
        var idCalls = 0
        val saved = store.saveNew(
            title = "переїхати в lhasa на зиму",
            context = "кашель не пройшов 6 тижнів · потрібно гірське повітря",
            status = DecisionStatus.ACTIVE,
            clock = { nowCalls++; 1_700_000_000_000L },
            idFactory = { idCalls++; "deterministic-id" },
        )
        assertEquals("deterministic-id", saved.id)
        assertEquals(1_700_000_000_000L, saved.createdAt)
        assertEquals(DecisionStatus.ACTIVE, saved.status)
        assertEquals(1, nowCalls)
        assertEquals(1, idCalls)

        val readBack = store.getEntry(saved.id)
        assertNotNull(readBack)
        assertEquals("переїхати в lhasa на зиму", readBack?.title)
    }

    @Test
    fun `getEntries flow emits new entry after saveNew`() = runTest {
        store.saveNew(
            title = "облишити проєкт sentinel",
            context = "team did not grow",
            status = DecisionStatus.REVISIT,
            clock = { 1L },
            idFactory = { "a" },
        )
        val list = store.getEntries().first()
        assertEquals(1, list.size)
        assertEquals(DecisionStatus.REVISIT, list[0].status)
    }

    @Test
    fun `update overwrites the same id without changing the row count`() = runTest {
        val saved = store.saveNew(
            title = "перейти на on-device моделі",
            context = "cloud-залежність ризик",
            status = DecisionStatus.ACTIVE,
            clock = { 1L },
            idFactory = { "fixed" },
        )
        store.update(saved.copy(status = DecisionStatus.DONE))

        val list = store.getEntries().first()
        assertEquals(1, list.size)
        assertEquals(DecisionStatus.DONE, list[0].status)
    }

    @Test
    fun `delete removes the entry`() = runTest {
        store.saveNew("doomed", "бувай", DecisionStatus.ACTIVE, { 1L }, { "x" })
        store.delete("x")
        assertNull(store.getEntry("x"))
        assertTrue(store.getEntries().first().isEmpty())
    }

    @Test
    fun `DecisionStatus tokens are verbatim Ukrainian lowercase`() {
        // Pin the enum-like constants — the diary surface filters on these
        // raw strings; if anyone "translates" them the alert UI breaks.
        assertEquals("актуально", DecisionStatus.ACTIVE)
        assertEquals("переглянути", DecisionStatus.REVISIT)
        assertEquals("завершено", DecisionStatus.DONE)
        assertEquals(
            listOf("актуально", "переглянути", "завершено"),
            DecisionStatus.ALL,
        )
    }

    private class FakeDecisionEntryDao : DecisionEntryDao {
        private val entries = mutableMapOf<String, DecisionEntry>()
        private val flow = MutableStateFlow<List<DecisionEntry>>(emptyList())

        override fun getAll(): Flow<List<DecisionEntry>> = flow

        override suspend fun getById(id: String): DecisionEntry? = entries[id]

        override suspend fun insert(entry: DecisionEntry) {
            entries[entry.id] = entry
            flow.value = entries.values.sortedByDescending { it.createdAt }
        }

        override suspend fun delete(id: String) {
            entries.remove(id)
            flow.value = entries.values.sortedByDescending { it.createdAt }
        }
    }
}
