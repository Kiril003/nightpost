package local.phantom.companion.ui.messenger

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Звірка платника й «один підпис — одна картка» живуть лише тоді, коли міст каже, ЧИЯ картка. */
class SolanaCardReachTest {

    private fun source(path: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "app").isDirectory) dir = dir.parentFile
        val f = File(dir ?: fail("корінь не знайдено") as Nothing, path)
        assertTrue("$path не знайдено", f.isFile)
        return f.readText()
    }

    @Test
    fun everyCardTheBridgeServesNamesItsAuthorAndMessage() {
        val bridge = source("app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt")
        val calls = Regex("""solana\.decorate\(([^)]*)\)""").findAll(bridge).map { it.groupValues[1] }.toList()
        assertEquals("дві віддачі рядка: 1:1 і група", 2, calls.size)
        calls.forEach { assertTrue("без автора й id: $it", it.endsWith("m.fromId, m.id")) }
        val solana = source("app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerSolana.kt")
        assertTrue(solana.contains("SolanaLayer.cardStatus("))
        assertTrue(!solana.contains("SolanaLayer::statusOf"))
    }

    @Test
    fun theNetworkComingBackWakesWaitingChecks() {
        val wiring = source("app/src/main/java/local/phantom/companion/data/SolanaWiring.kt")
        assertTrue(wiring.contains("networkProbe.online") && wiring.contains("SolanaLayer.recheckPending()"))
        assertTrue(wiring.contains("AddressSharing.recipients("))
    }

    /** Збірка 18: `chat_id`/`ref` з вебу доїжджають до екрана гаманця, а картка — у групу через сітку. */
    @Test
    fun theBillRouteReachesTheWalletAndTheGroup() {
        val solana = source("app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerSolana.kt")
        assertTrue(solana.contains("WalletActivity.EXTRA_PAY_CHAT") && solana.contains("WalletActivity.EXTRA_PAY_REF"))
        assertTrue("група звіряється до відкриття гаманця", solana.contains("WebMessengerGroups.find(") && solana.contains("chatRefusal("))
        val wiring = source("app/src/main/java/local/phantom/companion/data/SolanaWiring.kt")
        val chat = wiring.substringAfter("sendToChat = {", "").substringBefore("\n            },")
        assertTrue("sendToChat іде сіткою в групу", chat.contains("mesh.sendToGroup("))
        assertTrue("лише в групу, де я ще є", chat.contains("PeerGroup.Standing.IN"))
        // Адресу гаманця отримують усі в групі, де я є: інакше мою картку там ніхто не звірить.
        val members = wiring.substringAfter("chatMembers = {", "").substringBefore("\n            },")
        assertTrue("учасники групи — з сітки", members.contains("WebMessengerGroups.find(") && members.contains(".others("))
        assertTrue("лише група, де я ще є", members.contains("PeerGroup.Standing.IN"))
        // Екстри гаманця — з тієї самої чистої функції, яку звіряє WebMessengerSolanaPayAskTest.
        assertTrue(solana.contains("walletExtras(ask).forEach"))
    }

    /** Журнал переказів, з яким звіряється сторож, читає і групи — з того самого журналу сітки. */
    @Test
    fun theGuardHistoryReadsGroupPayments() {
        val money = source("app/src/main/java/local/phantom/companion/data/MoneyAssistant.kt")
        assertTrue(money.contains("sentPaymentsOf(mesh.outbox.value, me, people, ::sharedWalletOf)"))
        val wiring = source("app/src/main/java/local/phantom/companion/data/SolanaWiring.kt")
        assertTrue(wiring.contains("sent = { sentPayments() }"))
    }

    /** Веб читає гаманець тими ж викликами, що й рідний екран: баланси, історія, люди. */
    @Test
    fun theWebReadsTheSameWalletThroughMe() {
        val solana = source("app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerSolana.kt")
        assertTrue(solana.contains("seg[2] == \"me\" && method == \"GET\""))
        assertTrue(solana.contains("SolanaLayer.activity(it)") && solana.contains("WalletMe.lines(byCoin, people)"))
        assertTrue(solana.contains("WalletMeReply.of(SolanaLayer.state.value"))
    }
}
