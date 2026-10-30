package local.phantom.companion.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Двері закладів для моста: у тестах підміняються, у застосунку — [VenueClient]. */
interface VenueApi {
    suspend fun near(areas: List<String>): VenueClient.Answer
    suspend fun venue(id: String): VenueClient.Answer
    suspend fun rate(id: String, stars: Int): VenueClient.Answer

    /** Запити відвідувачів до сторінок власника; [ack] — ті, що телефон уже показав. */
    suspend fun requests(ack: List<String>): VenueClient.Answer = VenueClient.Answer.NotEnabled
}

/**
 * Те, що телефон каже серверу закладів (`platform-site/server/venues.py`).
 *
 * Веб у телефоні сервера не бачить (CSP `connect-src 'self'`), тож усе йде
 * мостом і через цей клієнт. Він сам нічого не вигадує:
 *  - кривий вхід (код закладу не з 16 hex, зірки поза 1..5, область не r6)
 *    не доходить до мережі взагалі: чужих шляхів тут не складають;
 *  - оцінку підписує ключ заяв ([deviceSigner] → [ClaimSigner], не ключ месенджера), а речення — те саме, що в
 *    сервера, звірене з `tests/vectors/venue_rate_vectors.json`;
 *  - кожна відмова — окремий [Answer], а не «порожній успіх».
 */
class VenueClient(
    private val baseUrl: String,
    private val signer: DeviceSigner,
    private val http: Http = Http { _, _, _ -> null },
    private val owner: OwnerSigner = OwnerSigner { null },
) : VenueApi {
    /** Заява пристрою: ключ, разове число, час, підпис (base64). */
    data class Proof(val pubKey: String, val nonce: String, val issuedMs: Long, val signature: String)

    /**
     * Підписує ЗАЯВУ ([DeviceClaim]) або каже `null` (ключа нема). Речення
     * складає сама заява — підписувач не бере готового рядка ззовні.
     */
    fun interface DeviceSigner {
        fun sign(claim: DeviceClaim): Proof?
    }

    /**
     * Підпис ключем ОСОБИ (той, яким телефон входить на сайт QR-ом): лише він
     * каже серверу, чиї це сторінки. Речення складає клієнт ([requestsSentence]),
     * підписувач отримує лише функцію від (ключ, число, час). Немає ключа — `null`.
     */
    fun interface OwnerSigner {
        fun sign(sentence: (pub: String, nonce: String, issuedMs: Long) -> String): Proof?
    }

    /** Один запит: (код, тіло) або `null`, коли мережі не було. */
    fun interface Http {
        suspend fun call(method: String, url: String, json: String?): Pair<Int, String>?
    }

    sealed interface Answer {
        data class Ok(val json: String) : Answer
        /** [reason] — код відмови від сервера (`not_at_venue`…) у безпечній формі, або null. */
        data class Refused(val status: Int, val uk: String?, val en: String?, val reason: String? = null) : Answer
        data object Missing : Answer
        data object NotEnabled : Answer
        data object Unreachable : Answer
        data object NoKey : Answer
        data object BadInput : Answer
    }

    override suspend fun near(areas: List<String>): Answer {
        if (areas.isEmpty() || areas.size > MAX_AREAS || !areas.all { AREA.matches(it) }) return Answer.BadInput
        return get("/venues/near?cell=" + areas.joinToString(","))
    }

    override suspend fun venue(id: String): Answer {
        if (!VENUE_ID.matches(id)) return Answer.BadInput
        return get("/venues/$id")
    }

    override suspend fun rate(id: String, stars: Int): Answer {
        if (!VENUE_ID.matches(id) || stars !in 1..5) return Answer.BadInput
        val root = root() ?: return Answer.NotEnabled
        val proof = signer.sign(DeviceClaim.VenueRate(id, stars)) ?: return Answer.NoKey
        val body = buildJsonObject {
            put("pub_key", proof.pubKey)
            put("nonce", proof.nonce)
            put("issued_ms", proof.issuedMs)
            put("signature", proof.signature)
            put("stars", stars)
        }.toString()
        return answer(http.call("POST", "$root/venues/$id/rate", body))
    }

    override suspend fun requests(ack: List<String>): Answer {
        if (ack.size > MAX_ACK || !ack.all { VENUE_ID.matches(it) }) return Answer.BadInput
        val root = root() ?: return Answer.NotEnabled
        val proof = owner.sign { pub, nonce, at -> requestsSentence(pub, nonce, at, ack) } ?: return Answer.NoKey
        val body = buildJsonObject {
            put("pub_key", proof.pubKey)
            put("nonce", proof.nonce)
            put("issued_ms", proof.issuedMs)
            put("signature", proof.signature)
            put("ack", kotlinx.serialization.json.JsonArray(ack.map { JsonPrimitive(it) }))
        }.toString()
        return answer(http.call("POST", "$root/venues/requests/pull", body))
    }

    private suspend fun get(path: String): Answer {
        val root = root() ?: return Answer.NotEnabled
        return answer(http.call("GET", root + path, null))
    }

    private fun root(): String? = baseUrl.trim().trimEnd('/').takeIf { it.isNotEmpty() }

    private fun answer(reply: Pair<Int, String>?): Answer = answerOf(reply)

    companion object {
        private val VENUE_ID = Regex("[0-9a-f]{16}")
        private val AREA = Regex("[0-9a-f]{15}")
        private const val MAX_AREAS = 7
        private const val MAX_ACK = 200
        private const val NO_ROUTE = "Not Found"

        /** Речення під підписом оцінки — байт у байт `venues.rate_message`. */
        fun rateSentence(pub: String, nonce: String, issuedMs: Long, venueId: String, stars: Int): String =
            "phantom-venue-rate|v1|$pub|$nonce|$issuedMs|$venueId|$stars"

        /** Речення під підписом читання запитів — байт у байт `venue_requests.pull_message`. */
        fun requestsSentence(pub: String, nonce: String, issuedMs: Long, ack: List<String>): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(ack.joinToString(",").toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return "phantom-venue-requests|v1|$pub|$nonce|$issuedMs|$digest"
        }

        /** Підписувач ключем особи з [DeviceIdentityStore]; разове число — 32 hex, як чекає сервер. */
        fun ownerSigner(identity: () -> DeviceIdentityStore.Identity?): OwnerSigner = OwnerSigner { sentence ->
            val id = identity() ?: return@OwnerSigner null
            val raw = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            val nonce = raw.joinToString("") { "%02x".format(it) }
            val at = System.currentTimeMillis()
            val sig = runCatching {
                local.phantom.companion.core.net.crypto.Ed25519.sign(id.privateKey, sentence(id.publicKeyB64, nonce, at).toByteArray(Charsets.UTF_8))
            }.getOrNull() ?: return@OwnerSigner null
            Proof(id.publicKeyB64, nonce, at, java.util.Base64.getEncoder().encodeToString(sig))
        }

        /**
         * Підписувач заяв закладів і балів. Сам НЕ підписує: бере підпис у
         * [prove] — у застосунку це [ClaimSigner.prove] з окремим ключем заяв
         * (№6.4). Приймає лише [DeviceClaim] — закритий перелік заяв із власним
         * реченням, а не довільний рядок. Немає ключа — `null`.
         */
        fun deviceSigner(prove: (DeviceClaim) -> PeerMesh.AttachmentProof?): DeviceSigner =
            DeviceSigner { claim ->
                prove(claim)?.let { Proof(it.pubKey, it.nonce, it.issuedMs, it.signature) }
            }

        /**
         * Адреса серверу сцени: [licenseBase], лише коли двері ввімкнено
         * (`BuildConfig.SCENE_SERVER`), інакше порожня — і клієнт не робить жодного
         * запиту. Одне місце для обох клієнтів, щоб прапорець не можна було
         * обійти одним із них.
         */
        fun sceneBase(enabled: Boolean, licenseBase: String): String = if (enabled) licenseBase else ""

        /** Код відмови сервера: лише `[a-z0-9_]`, щоб не стати чужим словом у `code` відповіді. */
        private val REASON = Regex("[a-z0-9_]{1,40}")

        /**
         * Відповідь сервера словами [Answer]. Спільна з [PointsClient]: обидва
         * сервери кажуть відмову однаково (`{refused, message_uk, message_en}`
         * або голе `detail` FastAPI).
         */
        internal fun answerOf(reply: Pair<Int, String>?): Answer {
            val (status, text) = reply ?: return Answer.Unreachable
            val obj = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            if (status in 200..299) return if (obj != null) Answer.Ok(text) else Answer.Unreachable
            val detail = (obj?.get("detail") as? JsonPrimitive)?.contentOrNull
            // Голий «Not Found» FastAPI — на сервері ще немає самих дверей, а не закладу.
            if (status == 404) return if (detail == NO_ROUTE) Answer.NotEnabled else Answer.Missing
            val refused = (obj?.get("refused") as? JsonPrimitive)?.contentOrNull
            // 503 без причини — на сервері не підключено самих дверей. 503 З причиною
            // (`network_unavailable`: мережа Solana не відповіла) — двері є, відмова
            // тимчасова; «не ввімкнено» тут було б неправдою.
            if (status == 503 && refused == null) return Answer.NotEnabled
            return if (refused != null) {
                Answer.Refused(
                    status,
                    (obj?.get("message_uk") as? JsonPrimitive)?.contentOrNull,
                    (obj?.get("message_en") as? JsonPrimitive)?.contentOrNull,
                    refused.takeIf { REASON.matches(it) },
                )
            } else {
                Answer.Refused(status, null, null)
            }
        }

        /** Живий транспорт: один запит, мережа на IO, будь-яка помилка — `null` («мережі не було»). */
        fun urlConnection(timeoutMs: Int = 10_000): Http = Http { method, url, json ->
            withContext(Dispatchers.IO) {
                runCatching {
                    val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    try {
                        c.requestMethod = method
                        c.connectTimeout = timeoutMs
                        c.readTimeout = timeoutMs
                        c.setRequestProperty("Accept", "application/json")
                        if (json != null) {
                            c.doOutput = true
                            c.setRequestProperty("Content-Type", "application/json")
                            c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                        }
                        val code = c.responseCode
                        val stream = if (code >= 400) c.errorStream else c.inputStream
                        code to (stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
                    } finally {
                        c.disconnect()
                    }
                }.getOrNull()
            }
        }
    }
}
