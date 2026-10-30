package local.phantom.companion.core.design.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rule that stops "nothing to show" from being one word.
 *
 * Each case below is a defect this product has actually shipped, not a
 * hypothetical: a failed query reported as an empty vault, a cold Room read
 * reported as an empty vault, and a card printing a correct count above a row
 * that drew nothing.
 */
class PhantomVoidTest {

    @Test
    fun `rows outrank every other signal`() {
        assertNull(
            "a screen with rows to draw was told to draw an empty state",
            voidFace(rows = 3, loading = true, readable = false, expected = 99),
        )
    }

    @Test
    fun `a read that has not finished is not a finding`() {
        assertEquals(
            "a cold read reported as «порожньо» — the operator is told he owns " +
                "nothing while the query is still running",
            PhantomVoid.Loading,
            voidFace(rows = 0, loading = true),
        )
    }

    @Test
    fun `a failed read says it failed, never that there is nothing`() {
        assertEquals(
            "a truncated or unparseable payload reported as an empty result",
            PhantomVoid.Failed,
            voidFace(rows = 0, readable = false),
        )
    }

    @Test
    fun `a count above an empty list is a contradiction, not an emptiness`() {
        assertEquals(
            "the header promised 5 items and the list drew 0 — this is the app " +
                "contradicting itself and it must say so, not print «порожньо»",
            PhantomVoid.Mismatch,
            voidFace(rows = 0, expected = 5),
        )
    }

    @Test
    fun `a genuinely empty result is allowed to be empty`() {
        assertEquals(PhantomVoid.Empty, voidFace(rows = 0))
        assertEquals(
            "a header honestly reporting zero is not a contradiction",
            PhantomVoid.Empty,
            voidFace(rows = 0, expected = 0),
        )
    }

    @Test
    fun `an unfinished or failed read explains the gap before the count is doubted`() {
        assertEquals(
            "still reading, so the count is not yet contradicted",
            PhantomVoid.Loading,
            voidFace(rows = 0, loading = true, expected = 5),
        )
        assertEquals(
            "the read failed, which already explains the missing rows",
            PhantomVoid.Failed,
            voidFace(rows = 0, readable = false, expected = 5),
        )
    }
}
