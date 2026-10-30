package local.phantom.companion.core.solana

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import local.phantom.companion.core.design.tokens.DayColors
import local.phantom.companion.core.design.tokens.NightColors
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletCardInkTest {
    private val me = SolanaKey.of(ByteArray(32) { (it * 7 + 3).toByte() })!!

    private fun contrast(fg: Color, bg: Color): Double {
        val a = fg.compositeOver(bg).luminance() + 0.05
        val b = bg.luminance() + 0.05
        return maxOf(a, b) / minOf(a, b).toDouble()
    }

    @Test
    fun `напис на картці монети читається вдень і вночі`() {
        for (c in listOf(DayColors, NightColors)) for (coin in Coin.entries) {
            val look = CardLook.of(WalletCard.Money(coin, 1_250_000L, Cluster.DEVNET, me), c)
            val day = if (c.isDark) "night" else "day"
            assertTrue("$coin $day ink ${contrast(look.ink, look.base)}", contrast(look.ink, look.base) >= 4.5)
            assertTrue("$coin $day ink2 ${contrast(look.ink2, look.base)}", contrast(look.ink2, look.base) >= 4.5)
        }
    }
}
