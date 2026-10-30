package local.phantom.companion.net

import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.bytesToB64
import local.phantom.companion.core.net.crypto.randomBytes
import local.phantom.companion.data.PairedDeviceStore
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Пропуск до вузла — і що з ним робити, коли строк добіг кінця.
 *
 * Термін токена не читав ніхто: телефон возив мертвий JWT, вузол відповідав
 * 401, і жоден виклик того 401 не розрізняв. Місяць після паринга месенджер
 * просто замовкав, і зовні це виглядало як «вузол недосяжний».
 */
class NodeAuth(
    private val api: PhantomApi,
    private val store: PairedDeviceStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val gate = Mutex()

    /** Токен, придатний просто зараз. Строк, що добігає, продовжуємо мовчки. */
    suspend fun token(): String? {
        val paired = store.load() ?: return null
        if (!expiresSoon(paired.expiresAt, nowMs())) return paired.deviceJwt
        // Вузол може бути недосяжний саме зараз — старий токен ще живий добу,
        // тож віддаємо його, а не порожнечу.
        return refresh(paired.deviceJwt) ?: paired.deviceJwt
    }

    /**
     * Продовження строку підписом Ed25519 (routes_pair.py `pair_refresh`).
     *
     * [stale] — токен, який щойно не спрацював. Поки ми стояли під замком,
     * сусідній виклик міг уже все зробити; другий підпис тоді лише спалив би
     * ще один запит і перезаписав свіжий токен старішим.
     */
    suspend fun refresh(stale: String?): String? = gate.withLock {
        val paired = store.load() ?: return@withLock null
        if (stale != null && paired.deviceJwt != stale) return@withLock paired.deviceJwt

        val nonce = bytesToB64(randomBytes(NONCE_BYTES))
        val signature = runCatching {
            bytesToB64(Ed25519.sign(paired.privRaw(), refreshChallenge(paired.deviceId, nonce)))
        }.getOrNull() ?: return@withLock null

        val fresh = runCatching {
            api.refresh(api.node(), PhantomApi.RefreshRequest(paired.deviceJwt, nonce, signature))
        }.getOrNull() ?: return@withLock null

        store.update { it.copy(deviceJwt = fresh.device_jwt, expiresAt = fresh.expires_at) }
        fresh.device_jwt
    }

    /** Виклик до вузла живим токеном, з одним повтором після 401. */
    suspend fun call(block: suspend (String) -> HttpResponse): HttpResponse =
        callWithRefresh(::token, { stale -> refresh(stale) }, block)
}

/**
 * Один виклик і рівно один повтор після продовження строку.
 *
 * Без сховища й без Android — саме тому цей шматок можна перевірити тестом,
 * а не тільки на живому телефоні.
 */
internal suspend fun callWithRefresh(
    token: suspend () -> String?,
    refresh: suspend (String) -> String?,
    call: suspend (String) -> HttpResponse,
): HttpResponse {
    val first = token() ?: throw PhantomApiException("not paired")
    val res = call(first)
    if (res.status != HttpStatusCode.Unauthorized) return res
    val second = refresh(first) ?: return res
    return call(second)
}

/** Те, що телефон підписує, доводячи вузлу, що це він. */
internal fun refreshChallenge(deviceId: String, nonceB64: String): ByteArray =
    "$deviceId:$nonceB64".toByteArray(Charsets.UTF_8)

/**
 * Чи пора продовжувати наперед. Нерозбірливий рядок — не привід ходити до
 * вузла щоразу: строк тоді доживе до 401, і його підбере повтор.
 */
internal fun expiresSoon(expiresAtIso: String, nowMs: Long): Boolean {
    val expMs = parseInstantMs(expiresAtIso) ?: return false
    return expMs - nowMs < REFRESH_AHEAD_MS
}

/** Вузол віддає час і з поясом, і без нього — приймаємо обидва, голий рахуємо UTC. */
internal fun parseInstantMs(iso: String): Long? {
    val text = iso.trim().takeIf { it.isNotBlank() } ?: return null
    runCatching { return OffsetDateTime.parse(text).toInstant().toEpochMilli() }
    runCatching { return Instant.parse(text).toEpochMilli() }
    runCatching { return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli() }
    return null
}

/** Доба запасу: телефон міг спати, і прокинутись треба вже з живим пропуском. */
internal const val REFRESH_AHEAD_MS = 24 * 60 * 60 * 1000L

private const val NONCE_BYTES = 16

/** Вузол кладе слухавку цим кодом, коли не приймає токен (main.py). */
internal const val WS_CLOSE_UNAUTHORIZED = 4401
