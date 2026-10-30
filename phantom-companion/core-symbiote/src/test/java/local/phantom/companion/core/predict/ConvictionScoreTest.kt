package local.phantom.companion.core.predict

import org.junit.Test

class ConvictionScoreTest {

    @Test
    fun `1 to 10 is accepted`() {
        for (n in 1..10) ConvictionScore("d", n, 0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero rejected`() {
        ConvictionScore("d", 0, 0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `eleven rejected`() {
        ConvictionScore("d", 11, 0L)
    }
}
