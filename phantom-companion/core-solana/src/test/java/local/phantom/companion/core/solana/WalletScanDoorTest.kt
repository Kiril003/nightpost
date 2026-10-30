package local.phantom.companion.core.solana

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Аудит 14.10 №1: у шапці гаманця і в «Кому» стоїть те саме «Сканувати», що й на «Сьогодні». */
class WalletScanDoorTest {

    private fun source(name: String): String {
        val here = File("").absoluteFile
        val root = listOf(here, File(here, "core-solana"), File(here, "phantom-companion/core-solana"))
            .firstOrNull { File(it, "src/main/java/local/phantom/companion/core/solana").isDirectory }
            ?: fail("core-solana module root not found from $here") as Nothing
        return File(root, "src/main/java/local/phantom/companion/core/solana/$name").readText()
    }

    @Test
    fun `wallet header and the send picker open the one scanner`() {
        val wallet = source("WalletActivity.kt")
        val header = wallet.substringAfter("WalletHeader(stringResource(R.string.sol_title)").substringBefore("PullToRefreshBox(")
        assertTrue("у шапці гаманця немає «Сканувати»", header.contains("if (onBack == null) SolanaLayer.config?.scan?.let { WalletScanButton(it) }"))
        val picker = wallet.substringAfter("SendPicker(").substringBefore("RequestSheet(")
        assertTrue("«Кому» не веде в сканер", picker.contains("onScan = SolanaLayer.config?.scan"))

        val kit = source("WalletKit.kt").substringAfter("fun WalletScanButton(").substringBefore("\n}\n")
        assertTrue("кнопка менша за 48 dp", kit.contains(".size(PhantomSpace.touchMinAndroid)"))
        assertTrue(kit.contains("contentDescription = stringResource(R.string.sol_scan)"))

        assertTrue(source("SolanaLayer.kt").contains("val scan: (() -> Unit)? = null"))
    }
}
