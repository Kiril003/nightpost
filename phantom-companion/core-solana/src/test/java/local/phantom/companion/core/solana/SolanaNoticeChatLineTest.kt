package local.phantom.companion.core.solana

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Рядок переказу в списку розмов: «1 USDC to Daniel · Pending», а не
 * «Transfer 1 USDC → 2ZWP…oA8g · waiting for network confirmation».
 * Адреси гаманця й речення про мережу в рядку розмови немає.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SolanaNoticeChatLineTest {

    private val to = SolanaKey.of("BxsSMk2r7BPxLChLdAE7Jv7UuDbZbaBRis31PgWTDfJH")!!
    private val sig = "4WVSP661Y7sGVtg64ScknvfBFVX5rV2UqBaFRWmFwLxZJCGzdtEAnC1psHCiTYnxZgEg1KWMTDgP5o7YvJL6mZHT"
    private val frame = PaymentFrame(Cluster.DEVNET, Coin.USDC, 1_000_000, to, sig)
    private val voucher = CashVoucher(frame, ByteArray(8))

    private fun ctx(tag: String): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val cfg = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return base.createConfigurationContext(cfg)
    }

    @Test fun `переказ людині — сума, імʼя й один ярлик стану`() {
        val en = ctx("en")
        assertEquals("1 USDC to Daniel · Pending", SolanaNotice.paymentLine(en, frame, PaymentStatus.PENDING, SolanaNotice.Who("Daniel", true)))
        assertEquals("1 USDC from Daniel · Confirmed", SolanaNotice.paymentLine(en, frame, PaymentStatus.CONFIRMED, SolanaNotice.Who("Daniel", false)))
        val uk = ctx("uk")
        assertEquals("1 USDC для Daniel · Очікує", SolanaNotice.paymentLine(uk, frame, PaymentStatus.PENDING, SolanaNotice.Who("Daniel", true)))
        assertEquals("1 USDC від Daniel · Підтверджено", SolanaNotice.paymentLine(uk, frame, PaymentStatus.CONFIRMED, SolanaNotice.Who("Daniel", false)))
    }

    @Test fun `без імені лишається рід і сума, адреси немає`() {
        val line = SolanaNotice.paymentLine(ctx("en"), frame, PaymentStatus.PENDING, null)
        assertEquals("Transfer 1 USDC · Pending", line)
        assertEquals("Transfer 1 USDC · Pending", SolanaNotice.paymentLine(ctx("en"), frame, PaymentStatus.PENDING, SolanaNotice.Who(" ", true)))
    }

    @Test fun `жоден стан не кладе в рядок адресу, стрілку чи речення`() {
        for (tag in listOf("en", "uk")) for (status in PaymentStatus.entries) {
            val line = SolanaNotice.paymentLine(ctx(tag), frame, status, SolanaNotice.Who("Daniel", true))
            assertFalse(line, to.short() in line || to.base58 in line || "→" in line || "…" in line)
            assertFalse(line, "network" in line || "мереж" in line)
        }
    }

    @Test fun `купюра — слово «готівка» лишається, а стан — коротке слово`() {
        val en = ctx("en")
        assertEquals("Cash 1 USDC to Daniel · Not settled", SolanaNotice.cashLine(en, voucher, PaymentStatus.PENDING, SolanaNotice.Who("Daniel", true)))
        assertEquals("Cash 1 USDC · Confirmed", SolanaNotice.cashLine(en, voucher, PaymentStatus.CONFIRMED, null))
        assertEquals("Готівка 1 USDC від Daniel · Не зараховано", SolanaNotice.cashLine(ctx("uk"), voucher, PaymentStatus.PENDING, SolanaNotice.Who("Daniel", false)))
    }
}
