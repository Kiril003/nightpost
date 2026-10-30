package local.phantom.companion.core.context.decision

import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.context.ContextInput
import local.phantom.companion.core.context.SystemState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionTreeTest {

    private val focusNode = object : DecisionNode {
        override suspend fun evaluate(input: ContextInput) =
            NodeVote(SystemState.FOCUS, 0.5f, "always focus")
    }

    private val ghostNode = object : DecisionNode {
        override suspend fun evaluate(input: ContextInput) =
            NodeVote(SystemState.GHOST, 0.7f, "always ghost")
    }

    private val testInput = ContextInput(
        nowMs = 1_000L,
        localHour = 10,
        localDayOfWeek = 2,
    )

    @Test
    fun `ghost wins with higher weight`() = runTest {
        val tree = DecisionTree(listOf(focusNode, ghostNode))
        val snapshot = tree.decide(testInput)

        assertEquals(SystemState.GHOST, snapshot.state)
    }

    @Test
    fun `confidence is winning score over total score`() = runTest {
        val tree = DecisionTree(listOf(focusNode, ghostNode))
        val snapshot = tree.decide(testInput)

        // winning_score=0.7, total=0.7+0.5=1.2 → confidence≈0.583
        val expected = 0.7f / (0.5f + 0.7f)
        assertEquals(expected, snapshot.confidence, 0.001f)
    }

    @Test
    fun `rationale contains both node messages`() = runTest {
        val tree = DecisionTree(listOf(focusNode, ghostNode))
        val snapshot = tree.decide(testInput)

        assertTrue(snapshot.rationale.contains("always focus"))
        assertTrue(snapshot.rationale.contains("always ghost"))
    }

    @Test
    fun `empty nodes returns FOCUS with zero confidence`() = runTest {
        val tree = DecisionTree(emptyList())
        val snapshot = tree.decide(testInput)

        assertEquals(SystemState.FOCUS, snapshot.state)
        assertEquals(0f, snapshot.confidence, 0.001f)
    }

    /**
     * Точний рахунок, що залив власникові стрічку 1 серпня 2026: субота дає
     * ВАРТІ 0.4 постійно, тихе приміщення дає ФОКУСУ 0.4, а MotionNode мовчить,
     * поки телефон лежить нерухомо. Мікровібрація столу через поріг 0.3 —
     * і стан перекидався кожні 30 секунд.
     */
    private fun node(state: SystemState, w: Float) = object : DecisionNode {
        override suspend fun evaluate(input: ContextInput) = NodeVote(state, w, "$state $w")
    }

    @Test
    fun `a tie leaves the incumbent where it is`() = runTest {
        val tree = DecisionTree(listOf(node(SystemState.SENTINEL, 0.4f), node(SystemState.FOCUS, 0.4f)))

        assertEquals(
            SystemState.FOCUS,
            tree.decide(testInput, incumbent = SystemState.FOCUS).state,
        )
        assertEquals(
            SystemState.SENTINEL,
            tree.decide(testInput, incumbent = SystemState.SENTINEL).state,
        )
    }

    @Test
    fun `a lead smaller than the margin does not move the state`() = runTest {
        val tree = DecisionTree(listOf(node(SystemState.SENTINEL, 0.4f), node(SystemState.FOCUS, 0.5f)))

        assertEquals(
            SystemState.SENTINEL,
            tree.decide(testInput, incumbent = SystemState.SENTINEL).state,
        )
    }

    @Test
    fun `a real lead still moves the state`() = runTest {
        val tree = DecisionTree(listOf(node(SystemState.SENTINEL, 0.4f), node(SystemState.FOCUS, 0.7f)))

        assertEquals(
            SystemState.FOCUS,
            tree.decide(testInput, incumbent = SystemState.SENTINEL).state,
        )
    }

    @Test
    fun `the winner explains the move, not whichever node voted first`() = runTest {
        // TimeOfDayNode стоїть першим у справжньому дереві, тож поки причини
        // йшли в порядку вузлів, перехід У ФОКУС пояснювався «вихідний —
        // пасивне спостереження». SystemPulseRepository бере саме firstOrNull.
        val tree = DecisionTree(listOf(node(SystemState.SENTINEL, 0.4f), node(SystemState.FOCUS, 0.9f)))
        val snapshot = tree.decide(testInput, incumbent = SystemState.SENTINEL)

        assertEquals(SystemState.FOCUS, snapshot.state)
        assertTrue(
            "перша причина мусить належати переможцю — got: ${snapshot.rationale}",
            snapshot.rationale.first().startsWith("FOCUS"),
        )
    }

    @Test
    fun `with no incumbent the strongest vote simply wins`() = runTest {
        val tree = DecisionTree(listOf(node(SystemState.SENTINEL, 0.4f), node(SystemState.FOCUS, 0.5f)))

        assertEquals(SystemState.FOCUS, tree.decide(testInput).state)
    }
}
