package local.phantom.companion.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Двері балів для моста: у тестах підміняються, у застосунку — [PointsClient]. */
interface PointsApi {
    suspend fun checkin(meetupId: String, venueId: String, cell: String, meetupAtMs: Long): VenueClient.Answer
    /**
     * Перша оплачена частка. [walletSign] — підпис гаманця під ТИМ САМИМ реченням,
     * що й підпис пристрою: його дає лише згода людини (див. [PointsClaims]).
     */
    suspend fun settled(
        cluster: String,
        tx: String,
        address: String,
        ref: String,
        walletSign: (ByteArray) -> ByteArray,
    ): VenueClient.Answer
    suspend fun me(): VenueClient.Answer
}

/**
 * Телефон → сервер балів (`platform-site/server/points.py`, `/points/…`).
 *
 * Сервер дає бали сам і сам читає транзакцію в мережі; телефон лише називає
 * дію й ставить підпис ключа заяв під ЗАКРИТИМ реченням кожного роду
 * (`checkin_message`, `settled_message`, `me_message`). Клієнт ніколи не
 * називає кількість балів, а дверей «купити / переказати / вивести» немає
 * ні тут, ні на сервері.
 *
 * Оплату доводять ДВА підписи під одним реченням (v2): пристрою і самого
 * гаманця — інакше чужу транзакцію, видиму всім у мережі, можна було б
 * привласнити. Підпис гаманця клієнт не створює: його дає виклик, який уже
 * дістав згоду людини (відбиток/PIN). Лише devnet — mainnet не доходить
 * ні до підпису, ні до мережі.
 *
 * Підписувач і відповіді — ті самі, що в [VenueClient]: речення складає цей
 * клас, а не веб, тож це не оракул підпису під довільним рядком.
 */
class PointsClient(
    private val baseUrl: String,
    private val signer: VenueClient.DeviceSigner,
    private val http: VenueClient.Http = VenueClient.Http { _, _, _ -> null },
) : PointsApi {

    override suspend fun checkin(meetupId: String, venueId: String, cell: String, meetupAtMs: Long): VenueClient.Answer {
        if (!UUID.matches(meetupId) || !VENUE.matches(venueId) || !CELL.matches(cell) || meetupAtMs < 0) {
            return VenueClient.Answer.BadInput
        }
        return post("/points/checkin", DeviceClaim.CheckIn(meetupId, venueId, cell, meetupAtMs)) {
            put("meetup_id", meetupId)
            put("venue_id", venueId)
            put("cell", cell)
            put("meetup_at", meetupAtMs)
        }
    }

    override suspend fun settled(
        cluster: String,
        tx: String,
        address: String,
        ref: String,
        walletSign: (ByteArray) -> ByteArray,
    ): VenueClient.Answer {
        if (cluster != "devnet") return VenueClient.Answer.BadInput
        if (!TX.matches(tx) || !ADDRESS.matches(address) || !UUID.matches(ref)) return VenueClient.Answer.BadInput
        val claim = DeviceClaim.Settled(cluster, tx, address, ref)
        return post("/points/settled", claim) { proof ->
            // Гаманець підписує РІВНО те речення, що й пристрій: його складає сама
            // заява з тих самих ключа, числа й часу — ні слова збоку.
            val sentence = claim.sentence(proof.pubKey, proof.nonce, proof.issuedMs)
            put("cluster", cluster)
            put("tx", tx)
            put("address", address)
            put("ref", ref)
            put("wallet_signature", java.util.Base64.getEncoder().encodeToString(walletSign(sentence.toByteArray(Charsets.UTF_8))))
        }
    }

    override suspend fun me(): VenueClient.Answer =
        post("/points/me", DeviceClaim.Me) {}

    private suspend fun post(
        path: String,
        claim: DeviceClaim,
        fields: kotlinx.serialization.json.JsonObjectBuilder.(VenueClient.Proof) -> Unit,
    ): VenueClient.Answer {
        val root = baseUrl.trim().trimEnd('/').takeIf { it.isNotEmpty() } ?: return VenueClient.Answer.NotEnabled
        val proof = signer.sign(claim) ?: return VenueClient.Answer.NoKey
        val body = buildJsonObject {
            put("pub_key", proof.pubKey)
            put("nonce", proof.nonce)
            put("issued_ms", proof.issuedMs)
            put("signature", proof.signature)
            fields(proof)
        }.toString()
        return answerOf(http.call("POST", root + path, body))
    }

    /**
     * У балів «немає такого» не буває: відмови (не в закладі, не ваша оплата) — це 422
     * зі словами. Тож будь-яке 404 — на сервері ще немає самих дверей `/points/…`
     * (голий FastAPI чи сторінка сайту), і це «не ввімкнено», а не «немає звʼязку».
     */
    private fun answerOf(reply: Pair<Int, String>?): VenueClient.Answer =
        VenueClient.answerOf(reply).let { if (it == VenueClient.Answer.Missing) VenueClient.Answer.NotEnabled else it }

    companion object {
        private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val VENUE = Regex("[A-Za-z0-9_-]{1,64}")
        private val CELL = Regex("[0-9a-f]{15}")
        private val TX = Regex("[1-9A-HJ-NP-Za-km-z]{64,90}")
        private val ADDRESS = Regex("[1-9A-HJ-NP-Za-km-z]{32,44}")

        // Речення — байт у байт `points.checkin_message / settled_message / me_message`.
        fun checkinSentence(pub: String, nonce: String, issuedMs: Long, meetupId: String, venueId: String, cell: String, meetupAt: Long) =
            listOf("phantom-points-checkin", "v1", pub, nonce, issuedMs, meetupId, venueId, cell, meetupAt).joinToString("|")

        fun settledSentence(pub: String, nonce: String, issuedMs: Long, cluster: String, tx: String, address: String, ref: String) =
            listOf("phantom-points-settled", "v2", pub, nonce, issuedMs, cluster, tx, address, ref).joinToString("|")

        fun meSentence(pub: String, nonce: String, issuedMs: Long) =
            "phantom-points-me|v1|$pub|$nonce|$issuedMs"
    }
}
