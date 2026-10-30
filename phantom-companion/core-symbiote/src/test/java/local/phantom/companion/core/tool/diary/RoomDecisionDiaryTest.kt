package local.phantom.companion.core.tool.diary

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.data.toolaudit.ToolAuditDao
import local.phantom.companion.core.data.toolaudit.ToolAuditEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RoomDecisionDiaryTest {

    private val dao = FakeToolAuditDao()

    private fun entry(
        id: String,
        timestamp: Long,
        toolId: String = "shell",
        agentId: String? = null,
        risk: String = "read",
        decision: String? = null,
    ) = DecisionEntry(
        id = id,
        timestamp = timestamp,
        toolId = toolId,
        args = "{}",
        outcome = "ok",
        outputDigest = "вивід",
        agentId = agentId,
        agentDepth = if (agentId == null) 0 else 1,
        risk = risk,
        decision = decision,
    )

    @Test
    fun `три записи читаються назад найновішим уперед, з усіма полями`() = runTest {
        val diary = RoomDecisionDiary(dao)
        diary.write(entry("a", timestamp = 1_000L, toolId = "map.route"))
        diary.write(
            entry(
                id = "b",
                timestamp = 2_000L,
                toolId = "torch.on",
                agentId = "sub-1",
                risk = "device",
                decision = "once",
            ),
        )
        diary.write(entry("c", timestamp = 3_000L, toolId = "sms.send", risk = "outward", decision = "denied"))

        val read = diary.recent(10)

        assertEquals(listOf("c", "b", "a"), read.map { it.id })

        val torch = read.single { it.id == "b" }
        assertEquals("torch.on", torch.toolId)
        assertEquals("device", torch.risk)
        assertEquals("once", torch.decision)
        assertEquals("sub-1", torch.agentId)
        assertEquals(1, torch.agentDepth)
        assertEquals(2_000L, torch.timestamp)

        val route = read.single { it.id == "a" }
        assertNull("ворота не зважували цей виклик — null мусить лишитись null", route.decision)
        assertNull(route.agentId)
    }

    @Test
    fun `recent шанує limit і віддає саме найновіші`() = runTest {
        val diary = RoomDecisionDiary(dao)
        repeat(5) { i -> diary.write(entry("e$i", timestamp = 100L * i)) }

        assertEquals(listOf("e4", "e3"), diary.recent(2).map { it.id })
    }

    @Test
    fun `обрізання видаляє найстаріші рядки, а не лише спиняє ріст`() = runTest {
        val diary = RoomDecisionDiary(dao, keep = 3, pruneEvery = 1)
        repeat(6) { i -> diary.write(entry("e$i", timestamp = 100L * i)) }

        assertEquals(3, dao.stored().size)
        assertEquals(listOf("e5", "e4", "e3"), dao.stored().map { it.id })
        assertTrue(
            "найстаріші рядки мусять зникнути з таблиці, а не просто не читатись",
            dao.stored().none { it.id in setOf("e0", "e1", "e2") },
        )
    }

    @Test
    fun `перший запис процесу зрізає хвіст, що лишився від попереднього`() = runTest {
        repeat(5) { i -> dao.seed(entry("старий$i", timestamp = 10L * i)) }

        // pruneEvery завідомо більший за кількість записів: якби обрізання
        // чекало на лічильник, хвіст із минулого запуску тут би вцілів.
        RoomDecisionDiary(dao, keep = 2, pruneEvery = 1_000).write(entry("новий", timestamp = 999L))

        assertEquals(listOf("новий", "старий4"), dao.stored().map { it.id })
    }

    @Test
    fun `збій сховища не валить виклик, і наступний запис усе одно лягає`() = runTest {
        val diary = RoomDecisionDiary(dao)
        dao.failNextInserts = true

        diary.write(entry("втрачений", timestamp = 1L))

        dao.failNextInserts = false
        diary.write(entry("вцілів", timestamp = 2L))

        assertEquals(
            "після проковтнутого збою щоденник мусить лишитись робочим",
            listOf("вцілів"),
            diary.recent(10).map { it.id },
        )
        assertEquals("обидва записи мусили дійти до сховища", 2, dao.insertCalls)
    }

    @Test
    fun `скасування не ковтається разом зі збоями`() = runTest {
        val diary = RoomDecisionDiary(dao)
        dao.cancelNextInsert = true

        try {
            diary.write(entry("скасований", timestamp = 1L))
            fail("CancellationException мусить пройти наскрізь: інакше скасована петля йде далі")
        } catch (expected: CancellationException) {
            assertEquals("скасовано ззовні", expected.message)
        }
    }

    /** Дзеркало контракту [ToolAuditDao]: те саме сортування, ті самі видалення. */
    private class FakeToolAuditDao : ToolAuditDao {

        private val rows = mutableListOf<Pair<Long, ToolAuditEntity>>()
        private var nextRowId = 1L

        var insertCalls = 0
        var failNextInserts = false
        var cancelNextInsert = false

        fun stored(): List<ToolAuditEntity> = sorted().map { it.second }

        fun seed(entry: DecisionEntry) {
            rows += nextRowId++ to ToolAuditEntity(
                id = entry.id,
                timestamp = entry.timestamp,
                toolId = entry.toolId,
                args = entry.args,
                outcome = entry.outcome,
                outputDigest = entry.outputDigest,
                agentId = entry.agentId,
                agentDepth = entry.agentDepth,
                risk = entry.risk,
                decision = entry.decision,
            )
        }

        override suspend fun insert(entry: ToolAuditEntity) {
            insertCalls++
            if (cancelNextInsert) {
                cancelNextInsert = false
                throw CancellationException("скасовано ззовні")
            }
            if (failNextInserts) throw IllegalStateException("диск повний")
            rows.removeAll { it.second.id == entry.id }
            rows += nextRowId++ to entry
        }

        override suspend fun recent(limit: Int): List<ToolAuditEntity> =
            sorted().take(limit).map { it.second }

        override suspend fun pruneToNewest(keep: Int) {
            val survivors = sorted().take(keep)
            rows.retainAll(survivors.toSet())
        }

        private fun sorted(): List<Pair<Long, ToolAuditEntity>> = rows.sortedWith(
            compareByDescending<Pair<Long, ToolAuditEntity>> { it.second.timestamp }
                .thenByDescending { it.first },
        )
    }
}
