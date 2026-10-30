package local.phantom.companion.core.solana

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import java.util.Locale
import local.phantom.companion.core.design.theme.AmberNight
import local.phantom.companion.core.design.theme.SunriseWarm
import local.phantom.companion.core.design.tokens.DayColors
import local.phantom.companion.core.design.tokens.NightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Рішення вигляду гаманця й пари токенів, якими він пише: доводиться без композиції. */
class WalletLookTest {

    private val me = SolanaKey.of(ByteArray(32) { (it * 7 + 1).toByte() })!!
    private val note = CashNote(seed = "s", nonce = "n", value = "5")
    private val spent = CashNote(seed = "s2", nonce = "n2", value = "5", voucherSig = "sig", voucherUnits = 5_000_000)

    private fun built(backedUp: Boolean = true, notes: List<CashNote> = emptyList()) = WalletState(
        kind = WalletKind.BUILT_IN,
        address = me,
        builtIn = me,
        backedUp = backedUp,
        cash = CashPocket(coin = "usdc", units = 20_000_000, notes = notes, checkedAtMs = 1),
    )

    @Test
    fun `одна заливка на екран — спершу слова, потім кишеня, потім ніде`() {
        assertEquals(WalletLook.Primary.BACKUP, WalletLook.primary(built(backedUp = false)))
        assertEquals(WalletLook.Primary.FILL_POCKET, WalletLook.primary(built()))
        assertEquals(WalletLook.Primary.FILL_POCKET, WalletLook.primary(built(notes = listOf(spent))))
        assertEquals(WalletLook.Primary.NONE, WalletLook.primary(built(notes = listOf(note))))
        assertEquals(WalletLook.Primary.NONE, WalletLook.primary(built().copy(kind = WalletKind.EXTERNAL)))
        // Адреси ще немає — кишеню наповнювати нічим, заливки теж немає.
        assertEquals(WalletLook.Primary.NONE, WalletLook.primary(WalletState(backedUp = true)))
    }

    @Test
    fun `знак світиться бурштином лише без бурштинової кнопки поруч`() {
        assertFalse(WalletLook.markLit(built(backedUp = false)))
        assertFalse(WalletLook.markLit(built()))
        assertTrue(WalletLook.markLit(built(notes = listOf(note))))
        // Пари бренду (палітра np): на чорнилі #E2A048, на світлому лінія бурштину #A76811 (вигляд D), як brand_mark; без світла — колір тексту.
        assertEquals(Color(0xFFE2A048), WalletLook.markInk(NightColors, lit = true))
        assertEquals(Color(0xFFA76811), WalletLook.markInk(DayColors, lit = true))
        assertEquals(NightColors.text, WalletLook.markInk(NightColors, lit = false))
    }

    @Test
    fun `суми пишуться комою мови і табличними цифрами`() {
        val uk = Locale.forLanguageTag("uk")
        assertEquals("12,5", WalletLook.shown(12_500_000, Coin.USDC, uk))
        assertEquals("12.5", WalletLook.shown(12_500_000, Coin.USDC, Locale.US))
        assertEquals("0", WalletLook.shown(0, Coin.SOL, uk))
        assertEquals("5 USDC", WalletLook.sum(5_000_000, Coin.USDC, uk))
        assertEquals("0,000000001 SOL", WalletLook.sum(1, Coin.SOL, uk))
        assertEquals("tnum", PhantomTextProbe.tnumFeature())
    }

    @Test
    fun `велика сума — перша монета з грошима`() {
        val (lead, rest) = WalletLook.split(mapOf(Coin.USDC to 0L, Coin.EURC to 0L, Coin.SOL to 1_500_000_000L))!!
        assertEquals(Coin.SOL to 1_500_000_000L, lead)
        assertEquals(listOf(Coin.USDC to 0L, Coin.EURC to 0L), rest)
        assertEquals(Coin.USDC, WalletLook.split(mapOf(Coin.SOL to 1L, Coin.USDC to 12L))!!.first.first)
        // Порожній гаманець — чесний нуль першої монети, а не вигадана сума.
        assertEquals(Coin.USDC to 0L, WalletLook.split(mapOf(Coin.USDC to 0L, Coin.SOL to 0L))!!.first)
        assertNull(WalletLook.split(emptyMap()))
    }

    @Test
    fun `кишеня рахує вільне й купюри, які ще можна дати`() {
        val cash = CashPocket(coin = "usdc", units = 20_000_000, notes = listOf(note, note, spent))
        assertEquals(15_000_000L, WalletLook.freeUnits(cash))
        assertEquals(2, WalletLook.freeNotes(cash))
        assertEquals(0L, WalletLook.freeUnits(CashPocket(units = 1, notes = listOf(spent))))
    }

    @Test
    fun `ніч за замовчуванням, а тема застосунку — як є`() {
        val fallback = WalletLook.skin(null)
        assertEquals(AmberNight, fallback.spec)
        assertTrue(fallback.spec.isDark)
        val day = WalletSkin(SunriseWarm, 1.15f)
        assertEquals(day, WalletLook.skin(day))
    }

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun `кожен напис гаманця читається вдень і вночі`() {
        for (c in listOf(NightColors, DayColors)) {
            val name = if (c.isDark) "ніч" else "день"
            fun text(label: String, ink: Color, ground: Color) =
                assertTrue("$name: $label ${contrast(ink, ground)}", contrast(ink, ground) >= 4.5)
            text("головна дія", c.onAccent, c.accent)
            text("дія у відтінку світла", c.accentText, c.accentSoft)
            text("тиха дія", c.text, c.overlay)
            text("друге слово на плиті", c.text2, c.raised)
            text("тихий підпис на плиті", c.text3, c.raised)
            text("основна мережа", c.warning, c.raised)
            text("хибна сума", c.danger, c.raised)
            text("сума в полі", c.text, c.surface)
            text("монета в полі", c.text2, c.surface)
            // Знак — графіка, не текст: йому досить 3:1 на чорнилі чи папері.
            assertTrue("$name: знак ${contrast(WalletLook.markInk(c, true), c.canvas)}", contrast(WalletLook.markInk(c, true), c.canvas) >= 3.0)
        }
    }
}

/** Ознака табличних цифр — та сама, що в токенах веба (`tabular-nums`). */
private object PhantomTextProbe {
    fun tnumFeature(): String = local.phantom.companion.core.design.theme.PhantomText.display.tnum().fontFeatureSettings.orEmpty()
}
