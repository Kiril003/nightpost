package local.phantom.companion.assistant

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import local.phantom.companion.core.ai.tool.MoneyTool
import local.phantom.companion.core.net.peer.PeerCard
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.solana.Amounts
import local.phantom.companion.core.solana.CashVoucher
import local.phantom.companion.core.solana.Cluster
import local.phantom.companion.core.solana.PaymentFrame
import local.phantom.companion.self.Commitment

/**
 * «Що там з Олею?» — одна картка з тим, що між вами вже лежить на телефоні:
 * остання репліка, мої відкриті обіцянки, перекази в обидва боки. Без моделі.
 * Імені немає серед людей — питання йде далі, до розуму.
 */
object AssistantPersonCard {

    data class Said(val text: String, val atMs: Long, val mine: Boolean)
    data class Promise(val what: String, val dueMs: Long?)
    data class Flow(val coin: String, val sent: String, val got: String)
    data class Glance(
        val peerId: String,
        val name: String,
        val atMs: Long,
        val last: Said?,
        val promises: List<Promise>,
        val money: List<Flow>,
        val test: Boolean,
    )

    /** Слова імені з питання або null, якщо це не питання про людину. */
    fun who(prompt: String): List<String>? {
        val q = prompt.lowercase()
            .replace(APOSTROPHES, "'")
            .replace(ASSISTANT_NAME_LEAD, "")
            .replace(TAIL, "")
            .replace(SPACES, " ")
            .trim()
        val said = ASKS.firstNotNullOfOrNull { it.matchEntire(q)?.groupValues?.get(1) } ?: return null
        val words = said.split(' ').filter { it.isNotBlank() }
        return words.takeIf { it.size in 1..3 }
    }

    /** Кілька людей з таким іменем — та, з ким говорили останньою: повне імʼя на картці скаже, хто це. */
    fun <T> pick(said: List<String>, people: List<T>, name: (T) -> String, lastAtMs: (T) -> Long): T? =
        people.filter { MoneyTool.nameMatches(said, name(it)) }.maxByOrNull(lastAtMs)

    fun glance(
        peerId: String,
        name: String,
        conversation: List<PeerMessage>,
        isText: (String) -> Boolean,
        open: List<Commitment>,
        sent: List<PaymentFrame>,
        cluster: Cluster,
        nowMs: Long,
    ): Glance {
        val last = conversation.lastOrNull { isText(it.body) && it.body.isNotBlank() }
            ?.let { Said(excerpt(it.body), it.sentAtMs, mine = it.fromId != peerId) }
        val promises = open
            .filter { c -> c.peerId == peerId || (c.peerId == null && MoneyTool.nameMatches(c.personName.split(' ').filter { it.isNotBlank() }, name)) }
            .sortedWith(compareBy<Commitment> { it.dueMs ?: Long.MAX_VALUE }.thenBy { it.createdMs })
            .take(MAX_PROMISES)
            .map { Promise(it.what.ifBlank { it.text }, it.dueMs) }
        val got = conversation
            .filter { it.fromId == peerId }
            .mapNotNull { PaymentFrame.decode(it.body) ?: CashVoucher.decode(it.body)?.payment }
        val coins = (sent + got).map { it.coin }.distinct()
        val money = coins.map { coin ->
            Flow(
                coin = coin.symbol,
                sent = Amounts.format(sent.filter { it.coin == coin }.sumOf { it.units }, coin),
                got = Amounts.format(got.filter { it.coin == coin }.sumOf { it.units }, coin),
            )
        }
        val test = (sent + got).any { it.cluster != Cluster.MAINNET } || (money.isEmpty() && cluster != Cluster.MAINNET)
        return Glance(peerId, name, nowMs, last, promises, money, test)
    }

    fun body(g: Glance): String {
        val payload = buildJsonObject {
            put("v", 1)
            put("peer", g.peerId)
            put("name", g.name)
            put("at", g.atMs)
            if (g.last == null) put("last", JsonNull) else putJsonObject("last") {
                put("text", g.last.text)
                put("at", g.last.atMs)
                put("mine", g.last.mine)
            }
            putJsonArray("promises") {
                g.promises.forEach { p -> addJsonObject { put("what", p.what); if (p.dueMs == null) put("due", JsonNull) else put("due", p.dueMs) } }
            }
            putJsonArray("money") {
                g.money.forEach { f -> addJsonObject { put("coin", f.coin); put("sent", f.sent); put("got", f.got) } }
            }
            put("test", g.test)
        }.toString()
        return requireNotNull(PeerCard.encode(PeerCard.Body(PeerCard.Card.GLANCE, payload)))
    }

    private fun excerpt(text: String): String {
        val flat = text.trim().replace(SPACES, " ")
        return if (flat.length <= MAX_TEXT) flat else flat.take(MAX_TEXT - 1).trimEnd() + "…"
    }

    private const val MAX_TEXT = 140
    private const val MAX_PROMISES = 3

    private val APOSTROPHES = Regex("[’ʼ`´‘]")
    private val SPACES = Regex("\\s+")
    private val TAIL = Regex("[?!.…\\s]+$")

    private val ASKS = listOf(
        Regex("^(?:а\\s+)?(?:що|шо)\\s+(?:там\\s+|нового\\s+|у\\s+нас\\s+|в\\s+нас\\s+)?(?:з|із|зі)\\s+(.+)$"),
        Regex("^(?:а\\s+)?як\\s+(?:там\\s+|справи\\s+)?(?:з|із|зі)\\s+(.+)$"),
        Regex("^what's\\s+(?:up|new|going\\s+on)\\s+with\\s+(.+)$"),
        Regex("^co\\s+(?:je\\s+)?(?:nového\\s+)?s\\s+(.+)$"),
    )
}
