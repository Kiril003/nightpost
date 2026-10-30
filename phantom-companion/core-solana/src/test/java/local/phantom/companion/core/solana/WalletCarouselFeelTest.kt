package local.phantom.companion.core.solana

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletCarouselFeelTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "core-solana/src/main/java").isDirectory }

    @Test
    fun `cardScale interpolates and clamps at 6 percent`() {
        assertEquals(1f, cardScale(0f), 1e-5f)
        assertEquals(0.97f, cardScale(0.5f), 1e-5f)
        assertEquals(0.97f, cardScale(-0.5f), 1e-5f)
        assertEquals(0.94f, cardScale(2f), 1e-5f)
    }

    @Test
    fun `SettleTicker skips initial page and duplicate pages`() {
        var ticks = 0
        val ticker = SettleTicker { ticks++ }
        listOf(0, 0, 1, 1, 2).forEach(ticker::onSettled)
        assertEquals(2, ticks)

        var single = 0
        SettleTicker { single++ }.onSettled(3)
        assertEquals(0, single)
    }

    @Test
    fun `WalletCardsUi uses spring fling settle ticker and spring flip`() {
        val src = File(root, "core-solana/src/main/java/local/phantom/companion/core/solana/WalletCardsUi.kt").readText()
        assertTrue(src.contains("PagerDefaults.flingBehavior("))
        assertTrue(src.contains("snapshotFlow { pager.settledPage }"))
        assertFalse(src.contains("tween(FLIP_MS)"))
    }
}
