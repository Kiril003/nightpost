package local.phantom.companion.core.design.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ladder is only a ladder if it is ordered and if each rung is far enough
 * from the next to mean something. Forty-seven ad-hoc values were the state
 * before it; a ladder with two rungs 0.02 apart is the same disease wearing a
 * token's name.
 */
class PhantomAlphaTest {

    @Test
    fun `the ladder ascends`() {
        val l = PhantomAlpha.ladder
        assertEquals("the ladder lost or gained a rung", 8, l.size)
        for (i in 1 until l.size) {
            assertTrue(
                "rung $i (${l[i]}) does not sit above rung ${i - 1} (${l[i - 1]}) — " +
                    "'further down the list' must always mean 'more present'",
                l[i] > l[i - 1],
            )
        }
    }

    @Test
    fun `neighbouring rungs are far enough apart to be told apart`() {
        val l = PhantomAlpha.ladder
        for (i in 1 until l.size) {
            assertTrue(
                "rungs ${l[i - 1]} and ${l[i]} are ${l[i] - l[i - 1]} apart — a step " +
                    "nobody can see is a step nobody will pick correctly",
                l[i] - l[i - 1] >= 0.05f,
            )
        }
    }

    @Test
    fun `every rung is a real opacity`() {
        PhantomAlpha.ladder.forEach {
            assertTrue("$it is not an opacity", it > 0f && it < 1f)
        }
    }

    @Test
    fun `the elevation ladder ascends and starts genuinely flat`() {
        val steps = PhantomElevation.entries
        assertEquals(
            "Flat must be 0dp: a 'flat' surface that still casts a shadow is " +
                "the reason nobody trusted depth here in the first place",
            0f,
            PhantomElevation.Flat.z.value,
            0f,
        )
        for (i in 1 until steps.size) {
            assertTrue(
                "${steps[i]} (${steps[i].z}) does not sit above ${steps[i - 1]} " +
                    "(${steps[i - 1].z})",
                steps[i].z.value > steps[i - 1].z.value,
            )
        }
    }
}
