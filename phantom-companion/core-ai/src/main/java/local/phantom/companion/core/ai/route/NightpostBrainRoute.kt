package local.phantom.companion.core.ai.route

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIError
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.AIRouter
import local.phantom.companion.core.ai.BrainProfile
import local.phantom.companion.core.ai.BrainProfiles
import local.phantom.companion.core.ai.Route
import local.phantom.companion.core.ai.RoutingStrategy

/**
 * Розум для того, хто не має ні свого ключа, ні ПК: той самий запит Gemini, але
 * на `<base>/api/brain`, з номером установки замість ключа. Ключ живе на сервері.
 *
 * Що знає про сервер ([Standing]):
 * - [Standing.UP] — сам сказав `brain: true` (або вже відповів на питання);
 * - [Standing.NONE] — сам сказав `brain: false` без причини, чи це старий сервер без
 *   `/api/brain`: нічого не питаємо десять хвилин;
 * - [Standing.SPENT] — розум є, але ліміт на сьогодні вичерпано (`reason` статусу чи
 *   429 `daily_*`): людина чує про ліміт, а не «додай ключ»;
 * - [Standing.UNKNOWN] — статус не прочитано (мережі немає, повільно, 5xx, сторінка
 *   перехоплювача). Це НЕ «розуму немає»: маршрут лишається, питання йде по-справжньому
 *   і або проходить, або чесно каже «немає мережі».
 *
 * [allowed] = false («лише на пристрої», власний ключ) — жодного звернення, навіть по
 * статус. Статус не несе номера установки: «якщо сервер каже, що розуму в нього
 * нема, більше нічого не йде» — обіцянка сторінки безпеки.
 */
/**
 * Підписувач запитів до `/api/brain` ключем особи пристрою (Ed25519 з DeviceIdentityStore).
 * Живе в `:core-ai` як вузький контракт, щоб модуль не залежав від `:app`.
 */
interface BrainRequestSigner {
    val publicKeyB64: String
    fun sign(message: ByteArray): String
    fun newNonce(): String = NightpostBrainRoute.newNonceHex()
    fun nowMs(): Long = System.currentTimeMillis()
}

/**
 * Постачальник токена Play Integrity для `/api/brain/attest`.
 * Сьогодні повертає `null` (key-only атестація); коли бібліотеку Play Integrity буде
 * підключено, достатньо передати реалізацію в `AppContainer`.
 */
fun interface IntegrityTokenProvider {
    suspend fun requestToken(requestHashHex: String): String?
}

/** Рівень пройденої атестації установки (`key_only` або `integrity`). */
enum class BrainAttestKind(val wire: String, val strength: Int) {
    NONE("", 0),
    KEY_ONLY("key_only", 1),
    INTEGRITY("integrity", 2);

    fun isAtLeast(required: BrainAttestKind): Boolean = strength >= required.strength

    companion object {
        fun fromWire(raw: String?): BrainAttestKind = when (raw) {
            INTEGRITY.wire -> INTEGRITY
            KEY_ONLY.wire -> KEY_ONLY
            else -> NONE
        }
    }
}

/** Памʼять про те, яку саме атестацію (`/api/brain/attest`) пройшла ця установка. */
class BrainAttestedStore(
    val readKind: () -> BrainAttestKind,
    val writeKind: (BrainAttestKind) -> Unit,
) {
    val read: () -> Boolean
        get() = { readKind() != BrainAttestKind.NONE }

    val write: (Boolean) -> Unit
        get() = { done -> writeKind(if (done) BrainAttestKind.KEY_ONLY else BrainAttestKind.NONE) }

    companion object {
        fun ofBoolean(read: () -> Boolean, write: (Boolean) -> Unit): BrainAttestedStore = BrainAttestedStore(
            readKind = { if (read()) BrainAttestKind.KEY_ONLY else BrainAttestKind.NONE },
            writeKind = { kind -> write(kind != BrainAttestKind.NONE) },
        )

        const val KIND_KEY = "attested_kind_v1"
        const val LEGACY_BOOL_KEY = "attested_v1"

        fun migrate(
            readString: () -> String?,
            readLegacyBoolean: () -> Boolean,
            writeString: (String?) -> Unit,
            clearLegacyBoolean: () -> Unit = {},
        ): BrainAttestedStore = BrainAttestedStore(
            readKind = {
                val stored = readString()
                when {
                    stored != null -> BrainAttestKind.fromWire(stored)
                    readLegacyBoolean() -> {
                        writeString(BrainAttestKind.KEY_ONLY.wire)
                        clearLegacyBoolean()
                        BrainAttestKind.KEY_ONLY
                    }
                    else -> BrainAttestKind.NONE
                }
            },
            writeKind = { kind ->
                writeString(kind.wire.takeIf { it.isNotEmpty() })
                clearLegacyBoolean()
            },
        )
    }
}

class NightpostBrainRoute(
    base: String,
    private val installId: () -> String,
    private val allowed: () -> Boolean,
    private val httpClient: HttpClient = GeminiCloudRoute.defaultClient(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val signer: BrainRequestSigner? = null,
    private val integrityTokenProvider: IntegrityTokenProvider = IntegrityTokenProvider { null },
    private val attestedStore: BrainAttestedStore? = null,
    private val profilesStore: (String) -> Unit = {},
    private val uiLanguage: () -> String? = { null },
    private val firstTryMs: Long = RetryingAIRouter.FIRST_TRY_MS,
    private val clock: CoroutineContext = Dispatchers.Default,
) : AIRouter, LastResort {

    private val root = base.trim().trimEnd('/')
    @Volatile private var attestedKindInMemory: BrainAttestKind =
        attestedStore?.readKind() ?: BrainAttestKind.NONE
    private val attestMutex = Mutex()

    private val brain = GeminiCloudRoute(
        apiKeyProvider = { null },
        httpClient = httpClient,
        endpointBase = "$root/api/brain",
        requestHeaders = { model, profile, bodyBytes -> buildBrainHeaders(model, bodyBytes, profile) },
    )

    /** Що відомо про сервер зараз (див. опис класу); [OFF] — звертатись до нього не можна взагалі. */
    enum class Standing { OFF, NONE, UP, SPENT, UNKNOWN }

    private class Verdict(val state: Standing, val untilMs: Long, val reason: String? = null)

    @Volatile private var verdict: Verdict? = null
    private val asking = Mutex()

    /** Маршрут є, поки сервер не сказав «розуму немає»; «не знаю» його не ховає. */
    override suspend fun availableRoutes(): Set<Route> = when (known()?.state) {
        null, Standing.NONE -> emptySet()
        else -> setOf(Route.NIGHTPOST_SERVER)
    }

    /** Що сервер сказав про себе; з цього рядок «Розум» у Профілі каже людині правду в кожному з випадків. */
    suspend fun standing(): Standing = known()?.state ?: Standing.OFF

    /** Сервер САМ сказав, що розум є й ліміт не вичерпано. */
    suspend fun isUp(): Boolean = standing() == Standing.UP

    override fun complete(intent: AIIntent): Flow<AIChunk> = flow {
        val heard = known()
        if (heard == null || heard.state == Standing.NONE) {
            emit(AIChunk.ErrorChunk(cause = AIError.NoBrain(), route = Route.NIGHTPOST_SERVER))
            return@flow
        }
        if (heard.state == Standing.SPENT) {
            emit(spentChunk(heard.reason))
            return@flow
        }
        RetryingAIRouter(
            delegate = asking(brain),
            fallback = asking(brain),
            firstTryMs = firstTryMs,
            clock = clock,
            fallbackOn = { it.httpCode in 500..599 && bodyOf(it.body).brain != false },
        ).complete(intent).collect { emit(ours(it)) }
    }

    private fun asking(route: GeminiCloudRoute): AIRouter = object : AIRouter {
        override suspend fun availableRoutes(): Set<Route> = setOf(Route.NIGHTPOST_SERVER)
        override fun complete(intent: AIIntent): Flow<AIChunk> = flow {
            val initialAttestOk = if (signer != null) ensureAttested(force = false) else true
            var needsReattest = false
            // streamOnce, а не complete(): без черги офлайн-запитів збій мережі там стає
            // «хмара відповіла помилкою 0», а людині треба почути, що мережі немає.
            route.streamOnce(intent).offlineAsNoNetwork().collect { chunk ->
                if (signer != null && initialAttestOk && !needsReattest && isAttestFirst(chunk)) {
                    needsReattest = true
                } else {
                    emit(chunk)
                }
            }
            if (needsReattest) {
                markAttested(BrainAttestKind.NONE)
                ensureAttested(force = true)
                emitAll(route.streamOnce(intent).offlineAsNoNetwork())
            }
        }
    }

    private fun Flow<AIChunk>.offlineAsNoNetwork(): Flow<AIChunk> = catch { t ->
        if (t !is IOException) throw t
        android.util.Log.w("NightpostBrain", "stream failed", t)
        emit(AIChunk.ErrorChunk(cause = AIError.NoNetwork(), route = Route.NIGHTPOST_SERVER))
    }

    private fun isAttestFirst(chunk: AIChunk): Boolean {
        val cause = (chunk as? AIChunk.ErrorChunk)?.cause as? AIError.CloudRejected ?: return false
        return cause.httpCode == 403 && bodyOf(cause.body).error == "attest_first"
    }

    private fun buildBrainHeaders(
        model: String,
        bodyBytes: ByteArray,
        profile: BrainProfile? = null,
    ): Map<String, String> {
        val base = LinkedHashMap<String, String>()
        base[DEVICE_HEADER] = installId()
        if (profile != null) {
            base[PROFILE_HEADER] = profile.wire
        }
        uiLanguage()?.substringBefore('-')?.lowercase()?.takeIf { it in UI_LANGUAGES }?.let { base[LANG_HEADER] = it }
        val s = signer ?: return base
        val pub = s.publicKeyB64
        val nonce = s.newNonce()
        val ts = s.nowMs()
        val bodyHash = sha256Hex(bodyBytes)
        val msg = "$BRAIN_SIG_PREFIX|$pub|$nonce|$ts|$model|$bodyHash".toByteArray(Charsets.UTF_8)
        val sig = s.sign(msg)
        base[KEY_HEADER] = pub
        base[TS_HEADER] = ts.toString()
        base[NONCE_HEADER] = nonce
        base[SIG_HEADER] = sig
        return base
    }

    private fun markAttested(kind: BrainAttestKind) {
        attestedKindInMemory = kind
        attestedStore?.writeKind?.invoke(kind)
    }

    private suspend fun ensureAttested(force: Boolean): Boolean {
        val s = signer ?: return true
        if (!force && attestedKindInMemory == BrainAttestKind.INTEGRITY) return true
        return attestMutex.withLock {
            if (!force && attestedKindInMemory == BrainAttestKind.INTEGRITY) return@withLock true
            val pub = s.publicKeyB64
            val nonce = s.newNonce()
            val ts = s.nowMs()
            val msg = "$ATTEST_PREFIX|$pub|$nonce|$ts".toByteArray(Charsets.UTF_8)
            val reqHash = sha256Hex(msg)
            val integrityToken = runCatching { integrityTokenProvider.requestToken(reqHash) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
            val deliverableKind = if (integrityToken != null) BrainAttestKind.INTEGRITY else BrainAttestKind.KEY_ONLY
            if (!force && attestedKindInMemory.isAtLeast(deliverableKind)) return@withLock true
            val sig = s.sign(msg)
            val bodyJson = buildJsonObject {
                put("pub_key", JsonPrimitive(pub))
                put("nonce", JsonPrimitive(nonce))
                put("issued_ms", JsonPrimitive(ts))
                put("signature", JsonPrimitive(sig))
                if (integrityToken != null) {
                    put("integrity_token", JsonPrimitive(integrityToken))
                }
            }.toString()
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    withTimeout(STATUS_TIMEOUT_MS) {
                        val res = httpClient.post("$root/api/brain/attest") {
                            contentType(ContentType.Application.Json)
                            setBody(bodyJson)
                        }
                        res.status == HttpStatusCode.OK
                    }
                }.getOrDefault(false)
            }
            if (ok) {
                markAttested(deliverableKind)
            }
            ok
        }
    }

    /** null — звертатись до сервера взагалі не можна ([allowed], порожня адреса). */
    private suspend fun known(): Verdict? {
        if (root.isEmpty() || !allowed()) return null
        fresh()?.let { return it }
        return asking.withLock {
            fresh()?.let { return@withLock it }
            ask().also { verdict = it }
        }
    }

    private fun fresh(): Verdict? = verdict?.takeIf { nowMs() < it.untilMs }

    /** Статус сервера: з підписом особи пристрою, якщо підписувач є. */
    private suspend fun ask(): Verdict = withContext(Dispatchers.IO) {
        try {
            withTimeout(STATUS_TIMEOUT_MS) {
                val res = httpClient.get("$root/api/brain/status") {
                    if (signer != null) {
                        buildBrainHeaders("status", ByteArray(0)).forEach { (name, value) -> header(name, value) }
                    }
                }
                when {
                    res.status.value >= 500 -> unknown()
                    res.status != HttpStatusCode.OK -> {
                        val said = runCatching { bodyOf(res.bodyAsText()) }.getOrNull()
                        when {
                            res.status == HttpStatusCode.Forbidden && said?.error == "attest_first" ->
                                Verdict(Standing.UP, nowMs() + TTL_MS)
                            res.status == HttpStatusCode.NotFound || said?.brain == false ->
                                Verdict(Standing.NONE, nowMs() + TTL_MS)
                            // Годинник, портал, ліміт — «не знаємо», а не «розуму немає» на 10 хвилин.
                            else -> {
                                android.util.Log.w("NightpostBrain", "status ${res.status.value} ${said?.error.orEmpty()}")
                                unknown()
                            }
                        }
                    }
                    else -> {
                        val said = bodyOf(res.bodyAsText())
                        val up = said.brain
                        val reason = said.reason
                        if (up != null) {
                            BrainProfiles.accept(said.doc)
                            profilesStore(BrainProfiles.saved())
                        }
                        when {
                            up == null -> unknown()
                            up -> Verdict(Standing.UP, nowMs() + TTL_MS)
                            reason != null && reason in SPENT_REASONS -> Verdict(Standing.SPENT, nowMs() + TTL_MS, reason)
                            else -> Verdict(Standing.NONE, nowMs() + TTL_MS)
                        }
                    }
                }
            }
        } catch (t: TimeoutCancellationException) {
            unknown()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            android.util.Log.w("NightpostBrain", "status failed", t)
            unknown()
        }
    }

    private fun unknown() = Verdict(Standing.UNKNOWN, nowMs() + RETRY_MS)

    private fun spentChunk(reason: String?) = AIChunk.ErrorChunk(
        cause = AIError.CloudRejected(429, """{"error":"${reason ?: "daily_limit"}"}"""),
        route = Route.NIGHTPOST_SERVER,
    )

    // Сервер каже про себе тілом: `{"brain":false}` — розуму нема; `{"error":"daily_*"}` —
    // ліміт; решта (`{"error":"upstream"}`) — збій Google, який минеться. Код сам нічого
    // не доводить: 401/403/503 може принести й відмова Google, і тоді гасити маршрут
    // нема за що, а «перевір свій ключ» людині без ключа казати не можна.
    private fun ours(chunk: AIChunk): AIChunk = when (chunk) {
        is AIChunk.Token -> proven(chunk.copy(route = Route.NIGHTPOST_SERVER))
        is AIChunk.Final -> proven(chunk.copy(route = Route.NIGHTPOST_SERVER))
        is AIChunk.ToolCall -> proven(chunk.copy(route = Route.NIGHTPOST_SERVER))
        is AIChunk.Notice -> chunk.copy(route = Route.NIGHTPOST_SERVER)
        is AIChunk.Grounding -> chunk.copy(route = Route.NIGHTPOST_SERVER)
        is AIChunk.ErrorChunk -> {
            val cause = chunk.cause
            if (cause is AIError.CloudRejected) {
                val said = bodyOf(cause.body)
                when {
                    said.brain == false -> {
                        verdict = Verdict(Standing.NONE, nowMs() + TTL_MS)
                        chunk.copy(cause = AIError.CloudRejected(503, cause.body), route = Route.NIGHTPOST_SERVER)
                    }
                    cause.httpCode == 429 && said.error in SPENT_REASONS -> {
                        verdict = Verdict(Standing.SPENT, nowMs() + TTL_MS, said.error)
                        chunk.copy(route = Route.NIGHTPOST_SERVER)
                    }
                    // 401/403 від нашого сервера — не про ключ людини: у неї ключа немає.
                    cause.httpCode in LOOKS_LIKE_NO_BRAIN ->
                        chunk.copy(cause = AIError.CloudRejected(502, cause.body), route = Route.NIGHTPOST_SERVER)
                    else -> chunk.copy(route = Route.NIGHTPOST_SERVER)
                }
            } else {
                chunk.copy(route = Route.NIGHTPOST_SERVER)
            }
        }
    }

    /** Відповідь прийшла — сервер є, хоч би що казав статус, який не вдалося прочитати. */
    private fun <T : AIChunk> proven(chunk: T): T {
        if (verdict?.state == Standing.UNKNOWN) verdict = Verdict(Standing.UP, nowMs() + TTL_MS)
        return chunk
    }

    companion object {
        /** Сервер (brain.py) приймає й стару назву X-Phantom-Device — від телефонів до збірки 18. */
        const val DEVICE_HEADER = "X-Nightpost-Device"
        const val PROFILE_HEADER = "X-Nightpost-Profile"
        const val LANG_HEADER = "X-Nightpost-Lang"
        /** Мова месенджера, згорнута до мови (`pt-BR` → `pt`); brain.py знає всі ці. */
        private val UI_LANGUAGES = setOf("uk", "en", "cs", "es", "pt", "de", "fr", "pl", "tr", "it", "id", "in")
        const val KEY_HEADER = "X-Nightpost-Key"
        const val TS_HEADER = "X-Nightpost-Ts"
        const val NONCE_HEADER = "X-Nightpost-Nonce"
        const val SIG_HEADER = "X-Nightpost-Sig"
        const val ATTEST_PREFIX = "NP-ATTEST-v1"
        const val BRAIN_SIG_PREFIX = "NP-BRAIN-v1"

        private const val TTL_MS = 10 * 60_000L
        private const val RETRY_MS = 60_000L
        private const val STATUS_TIMEOUT_MS = 4_000L
        private val LOOKS_LIKE_NO_BRAIN = setOf(401, 403, 503)
        private val SPENT_REASONS = setOf("daily_limit", "daily_budget")
        private val ID_SHAPE = Regex("[A-Za-z0-9_-]{22,64}")
        private val json = Json { ignoreUnknownKeys = true }

        private class Said(val doc: JsonObject?, val brain: Boolean?, val error: String?, val reason: String?)

        private fun bodyOf(body: String): Said {
            val doc: JsonObject? = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            return Said(
                doc = doc,
                brain = (doc?.get("brain") as? JsonPrimitive)?.booleanOrNull,
                error = (doc?.get("error") as? JsonPrimitive)?.contentOrNull,
                reason = (doc?.get("reason") as? JsonPrimitive)?.contentOrNull,
            )
        }

        /** Тіло відмови сервера: на сьогодні (пристрою чи всіх) все, а не «зачекай хвилину». */
        fun isSpent(body: String): Boolean = bodyOf(body).error in SPENT_REASONS

        /**
         * Чи можна взагалі звертатись до сервера Nightpost. Людина з живим власним
         * ключем питає Google під ним. Мертвий ключ ([OwnKeyGuard]: відмова, прострочення,
         * квота) не глушить помічника — тоді відповідає сервер. «Лише на пристрої» не пускає нікуди.
         */
        fun mayAsk(routing: RoutingStrategy, ownKey: String?, ownKeyDead: Boolean = false): Boolean =
            routing != RoutingStrategy.LocalOnly && (ownKey.isNullOrBlank() || ownKeyDead)

        /** 128 випадкових біт, base64url: не з особи, не з заліза. */
        fun newInstallId(): String {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        fun keepInstallId(read: () -> String?, write: (String) -> Unit): String =
            read()?.takeIf { ID_SHAPE.matches(it) } ?: newInstallId().also(write)

        /** 16 випадкових байтів у 32 шістнадцяткових цифрах нижнього регістру. */
        fun newNonceHex(): String {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
