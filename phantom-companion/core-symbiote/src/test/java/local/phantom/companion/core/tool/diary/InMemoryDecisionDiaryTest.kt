package local.phantom.companion.core.tool.diary

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InMemoryDecisionDiaryTest {

    private lateinit var diary: InMemoryDecisionDiary

    @Before
    fun setUp() {
        diary = InMemoryDecisionDiary()
    }

    private fun entry(id: String, toolId: String = "shell", outcome: String = "ok") =
        DecisionEntry(
            id = id,
            timestamp = System.currentTimeMillis(),
            toolId = toolId,
            args = "{}",
            outcome = outcome,
            outputDigest = "output",
            agentId = null,
            agentDepth = 0,
        )

    @Test
    fun `recent returns empty list when diary is empty`() = runTest {
        assertTrue(diary.recent().isEmpty())
    }

    @Test
    fun `write and read single entry`() = runTest {
        val e = entry("1")
        diary.write(e)
        val result = diary.recent()
        assertEquals(1, result.size)
        assertEquals("1", result[0].id)
    }

    @Test
    fun `recent returns newest first`() = runTest {
        diary.write(entry("first"))
        diary.write(entry("second"))
        diary.write(entry("third"))
        val result = diary.recent()
        assertEquals("third", result[0].id)
        assertEquals("second", result[1].id)
        assertEquals("first", result[2].id)
    }

    @Test
    fun `recent respects limit parameter`() = runTest {
        repeat(10) { diary.write(entry("e$it")) }
        val result = diary.recent(limit = 3)
        assertEquals(3, result.size)
    }

    @Test
    fun `default limit is 50`() = runTest {
        repeat(60) { diary.write(entry("e$it")) }
        val result = diary.recent()
        assertEquals(50, result.size)
    }

    @Test
    fun `FIFO eviction keeps MAX_ENTRIES cap`() = runTest {
        repeat(InMemoryDecisionDiary.MAX_ENTRIES + 10) { i ->
            diary.write(entry("e$i"))
        }
        val result = diary.recent(limit = InMemoryDecisionDiary.MAX_ENTRIES + 10)
        assertEquals(InMemoryDecisionDiary.MAX_ENTRIES, result.size)
    }

    @Test
    fun `oldest entries are evicted first`() = runTest {
        repeat(InMemoryDecisionDiary.MAX_ENTRIES + 1) { i ->
            diary.write(entry("e$i"))
        }
        val allIds = diary.recent(limit = InMemoryDecisionDiary.MAX_ENTRIES).map { it.id }
        // "e0" (the very first) must have been evicted.
        assertTrue("e0 should be evicted", !allIds.contains("e0"))
        // The last written entry must be present.
        val lastId = "e${InMemoryDecisionDiary.MAX_ENTRIES}"
        assertTrue("last entry must be present", allIds.contains(lastId))
    }
}
