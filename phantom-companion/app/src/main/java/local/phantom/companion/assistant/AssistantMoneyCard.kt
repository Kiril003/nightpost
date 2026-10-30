package local.phantom.companion.assistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import local.phantom.companion.core.net.peer.PeerCard
import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerOutbox
import local.phantom.companion.core.solana.Amounts
import local.phantom.companion.core.solana.CashVoucher
import local.phantom.companion.core.solana.Cluster
import local.phantom.companion.core.solana.Coin
import local.phantom.companion.core.solana.PaymentFrame
import local.phantom.companion.core.solana.SolanaKey

/**
 * «Скільки я відправив цього тижня?», «Хто мені винен?», «Мій баланс» — картка
 * Гроші одразу, без моделі. Усе лише з того, що вже лежить на телефоні:
 * прочитані баланси гаманця, мої перекази за 7 діб по людях і відкриті частки
 * рахунків. Порожнє лишається чесно порожнім; у тестовій мережі — «Тестові платежі».
 */
object AssistantMoneyCard {

    data class Balance(val coin: String, val amount: String)
    data class Sent(val peerId: String, val name: String, val coin: String, val amount: String)
    data class Split(
        val chatId: String,
        val peerId: String,
        val name: String,
        val coin: String,
        val amount: String,
        val owesMe: Boolean,
    )
    data class Summary(
        val atMs: Long,
        val wallet: Boolean,
        val balances: List<Balance>,
        val week: List<Sent>,
        val splits: List<Split>,
        val test: Boolean,
    )

    fun asks(prompt: String): Boolean {
        val q = prompt.lowercase()
            .replace(APOSTROPHES, "'")
            .replace(ASSISTANT_NAME_LEAD, "")
            .replace(TAIL, "")
            .replace(SPACES, " ")
            .trim()
        return q.isNotEmpty() && ASKS.any { it.matches(q) }
    }

    fun summary(
        me: String,
        people: List<Pair<String, String>>,
        walletOf: (String) -> SolanaKey?,
        log: List<PeerMessage>,
        walletReady: Boolean,
        balances: Map<Coin, Long>?,
        cluster: Cluster,
        nowMs: Long,
    ): Summary {
        val nameById = people.toMap()
        val shownBalances = if (!walletReady || balances == null) {
            emptyList()
        } else {
            Coin.on(cluster).mapNotNull { coin ->
                balances[coin]?.let { units -> Balance(coin.symbol, Amounts.format(units, coin)) }
            }
        }
        val sinceMs = nowMs - WEEK_MS
        val dated = sentSince(log, me, people.map { it.first }, walletOf, sinceMs)
        val week = dated
            .groupBy { (peerId, frame) -> peerId to frame.coin }
            .mapNotNull { (key, rows) ->
                val (peerId, coin) = key
                val total = rows.sumOf { it.second.units }
                if (total <= 0L) return@mapNotNull null
                val name = nameById[peerId]?.takeIf { it.isNotBlank() } ?: rows.first().second.to.short()
                Triple(rows.maxOf { it.third }, total, Sent(peerId, name, coin.symbol, Amounts.format(total, coin)))
            }
            .sortedWith(compareByDescending<Triple<Long, Long, Sent>> { it.first }.thenByDescending { it.second })
            .take(MAX_WEEK_ROWS)
            .map { it.third }

        val splits = openSplits(log, me, nameById).take(MAX_SPLIT_ROWS)
        val test = cluster != Cluster.MAINNET || dated.any { it.second.cluster != Cluster.MAINNET }
        return Summary(
            atMs = nowMs,
            wallet = walletReady,
            balances = shownBalances,
            week = week,
            splits = splits,
            test = test,
        )
    }

    fun body(s: Summary): String {
        val payload = buildJsonObject {
            put("v", 1)
            put("at", s.atMs)
            put("wallet", s.wallet)
            putJsonArray("balances") {
                s.balances.forEach { b -> addJsonObject { put("coin", b.coin); put("amount", b.amount) } }
            }
            putJsonArray("week") {
                s.week.forEach { w ->
                    addJsonObject {
                        put("peer", w.peerId)
                        put("name", w.name)
                        put("coin", w.coin)
                        put("amount", w.amount)
                    }
                }
            }
            putJsonArray("splits") {
                s.splits.forEach { sp ->
                    addJsonObject {
                        put("chat", sp.chatId)
                        put("peer", sp.peerId)
                        put("name", sp.name)
                        put("coin", sp.coin)
                        put("amount", sp.amount)
                        put("owesMe", sp.owesMe)
                    }
                }
            }
            put("test", s.test)
        }.toString()
        return requireNotNull(PeerCard.encode(PeerCard.Body(PeerCard.Card.MONEY, payload)))
    }

    private fun sentSince(
        log: List<PeerMessage>,
        me: String,
        people: List<String>,
        walletOf: (String) -> SolanaKey?,
        sinceMs: Long,
    ): List<Triple<String, PaymentFrame, Long>> {
        if (me.isBlank()) return emptyList()
        fun frameOf(body: String) = PaymentFrame.decode(body) ?: CashVoucher.decode(body)?.payment
        val direct = people.flatMap { id ->
            PeerOutbox.conversation(log, id)
                .filter { it.fromId == me && it.sentAtMs >= sinceMs }
                .mapNotNull { m -> frameOf(m.body)?.let { Triple(id, it, m.sentAtMs) } }
        }
        val inGroups = log
            .filter { it.fromId == me && it.sentAtMs >= sinceMs && PeerGroup.isGroup(it.body) }
            .distinctBy { it.sentAtMs to it.body }
            .mapNotNull { m -> PeerGroup.decode(m.body)?.text?.let(::frameOf)?.let { it to m.sentAtMs } }
        if (inGroups.isEmpty()) return direct
        val whose = people.mapNotNull { id -> walletOf(id)?.let { it to id } }.toMap()
        return direct + inGroups.map { (f, at) -> Triple(whose[f.to].orEmpty(), f, at) }
    }

    private fun openSplits(
        log: List<PeerMessage>,
        me: String,
        nameById: Map<String, String>,
    ): List<Split> {
        if (me.isBlank()) return emptyList()
        fun frameOf(body: String): PaymentFrame? {
            val inner = PeerGroup.decode(body)?.text ?: body
            return PaymentFrame.decode(inner) ?: CashVoucher.decode(inner)?.payment
        }
        val paidByRefAndSender = HashMap<Pair<String, String>, Long>()
        for (m in log) {
            val f = frameOf(m.body) ?: continue
            val ref = f.ref ?: continue
            val key = ref to m.fromId
            paidByRefAndSender[key] = (paidByRefAndSender[key] ?: 0L) + f.units
        }

        val seenBills = HashSet<String>()
        val net = LinkedHashMap<Triple<String, String, Coin>, Long>()
        val names = HashMap<String, String>(nameById)

        for (m in log) {
            val g = PeerGroup.decode(m.body)
            val wire = g?.text ?: m.body
            val card = PeerCard.decode(wire)?.takeIf { it.what == PeerCard.Card.BILL } ?: continue
            val obj = runCatching { json.parseToJsonElement(card.payload).jsonObject }.getOrNull() ?: continue
            val billId = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
            if (!seenBills.add(billId)) continue
            val tokenStr = obj["token"]?.jsonPrimitive?.contentOrNull ?: continue
            val coin = Coin.entries.firstOrNull { it.symbol.equals(tokenStr, ignoreCase = true) } ?: continue
            val payeeObj = obj["payee"] as? JsonObject ?: continue
            if (payeeObj["kind"]?.jsonPrimitive?.contentOrNull != "peer") continue
            val payeeId = payeeObj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
            val chatId = g?.group?.id ?: if (m.fromId == me) m.toId else m.fromId

            val billPeople = (obj["people"] as? JsonArray).orEmpty().mapNotNull { el ->
                val p = el as? JsonObject ?: return@mapNotNull null
                val id = p["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val nm = p["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (nm.isNotEmpty() && names[id].isNullOrBlank()) names[id] = nm
                id
            }

            val asks = (obj["asks"] as? JsonArray)?.mapNotNull { el ->
                val a = el as? JsonObject ?: return@mapNotNull null
                val id = a["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val amt = a["amount"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val units = Amounts.toUnits(amt, coin) ?: return@mapNotNull null
                id to units
            }
            val shares: List<Pair<String, Long>> = if (!asks.isNullOrEmpty()) {
                asks
            } else {
                val items = (obj["items"] as? JsonArray).orEmpty().mapNotNull { el ->
                    val it = el as? JsonObject ?: return@mapNotNull null
                    val price = it["price"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    Amounts.toUnits(price, coin)
                }
                val total = items.sum()
                if (total <= 0L || billPeople.isEmpty()) emptyList()
                else {
                    val per = total / billPeople.size
                    val rem = total % billPeople.size
                    billPeople.sorted().mapIndexed { idx, id -> id to (per + if (idx < rem) 1L else 0L) }
                }
            }

            for ((personId, due) in shares) {
                if (personId == payeeId || due <= 0L) continue
                val paid = paidByRefAndSender[billId to personId] ?: 0L
                val left = due - paid
                if (left <= 0L) continue
                when {
                    payeeId == me && personId != me -> {
                        val k = Triple(chatId, personId, coin)
                        net[k] = (net[k] ?: 0L) + left
                    }
                    personId == me && payeeId != me -> {
                        val k = Triple(chatId, payeeId, coin)
                        net[k] = (net[k] ?: 0L) - left
                    }
                }
            }
        }

        return net.entries.mapNotNull { (k, signed) ->
            if (signed == 0L) return@mapNotNull null
            val (chatId, peerId, coin) = k
            val owesMe = signed > 0L
            val units = kotlin.math.abs(signed)
            Split(
                chatId = chatId,
                peerId = peerId,
                name = names[peerId]?.takeIf { it.isNotBlank() } ?: peerId,
                coin = coin.symbol,
                amount = Amounts.format(units, coin),
                owesMe = owesMe,
            )
        }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val WEEK_MS = 7 * 86_400_000L
    private const val MAX_WEEK_ROWS = 5
    private const val MAX_SPLIT_ROWS = 5

    private val APOSTROPHES = Regex("[’ʼ`´‘]")
    private val SPACES = Regex("\\s+")
    private val TAIL = Regex("[?!.…\\s]+$")

    private val ASKS = listOf(
        // Баланс і гаманець
        Regex("^(?:а\\s+)?(?:який\\s+)?(?:(?:у|в)\\s+мене\\s+)?(?:мій\\s+)?баланс(?:\\s+гаманця)?$"),
        Regex("^(?:а\\s+)?скільки\\s+(?:(?:в|у)\\s+мене\\s+)?(?:грошей\\s+)?(?:на\\s+рахунку|в\\s+гаманці|у\\s+гаманці|грошей)$"),
        Regex("^(?:а\\s+)?(?:що|як)\\s+(?:там\\s+)?(?:(?:в|у)\\s+мене\\s+)?(?:по\\s+грошах|з\\s+грошима|із\\s+грошима)$"),
        Regex("^(?:мої\\s+гроші|гроші\\s+за\\s+тиждень|гроші\\s+цього\\s+тижня)$"),
        // Перекази за тиждень
        Regex("^(?:а\\s+)?скільки\\s+я\\s+(?:відправив|відправила|переказав|переказала|скинув|скинула|витратив|витратила|надіслав|надіслала)(?:\\s+(?:цього\\s+тижня|за\\s+(?:цей\\s+)?тиждень))?$"),
        Regex("^(?:а\\s+)?кому\\s+я\\s+(?:відправляв|відправляла|переказував|переказувала|скидав|скидала)(?:\\s+(?:цього\\s+тижня|за\\s+(?:цей\\s+)?тиждень))?$"),
        // Борги й частки рахунків
        Regex("^(?:а\\s+)?(?:хто\\s+мені\\s+(?:щось\\s+)?винен|кому\\s+я\\s+(?:щось\\s+)?(?:винен|винна)|хто\\s+кому\\s+винен)$"),
        Regex("^(?:а\\s+)?(?:які\\s+(?:в|у)\\s+мене\\s+)?(?:борги|рахунки|розрахунки)$"),
        // English
        Regex("^(?:what's|what is)\\s+my\\s+balance$"),
        Regex("^(?:my\\s+balance|my\\s+money|money\\s+this\\s+week)$"),
        Regex("^how\\s+much\\s+(?:did\\s+i\\s+(?:send|spend)|have\\s+i\\s+(?:sent|spent))(?:\\s+this\\s+week)?$"),
        Regex("^how\\s+much\\s+(?:is\\s+(?:on|in)\\s+my\\s+(?:account|wallet)|money\\s+do\\s+i\\s+have)$"),
        Regex("^(?:who\\s+owes\\s+me|who\\s+do\\s+i\\s+owe|open\\s+splits|my\\s+splits)$"),
        // Čeština
        Regex("^(?:jaký\\s+mám\\s+zůstatek|můj\\s+zůstatek|kolik\\s+mám\\s+na\\s+účtu|peníze\\s+za\\s+týden)$"),
        Regex("^kolik\\s+jsem\\s+(?:poslal|poslala|utratil|utratila)(?:\\s+(?:tento\\s+týden|za\\s+týden))?$"),
        Regex("^(?:kdo\\s+mi\\s+dluží|komu\\s+dlužím)$"),
    )
}
