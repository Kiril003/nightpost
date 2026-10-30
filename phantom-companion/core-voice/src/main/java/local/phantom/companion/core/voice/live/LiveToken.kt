package local.phantom.companion.core.voice.live

import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Одноразовий ключ до Gemini Live: ОДНА сесія, почати до [startBeforeMs].
 *
 * Ключ власника на телефон не їде, якщо його там не поклала сама людина: ПК
 * видає токен (`POST /api/v1/live/token`), а коли телефон тримає власний ключ —
 * токен карбує сам телефон, тим самим запитом, що й ПК.
 */
data class LiveToken(
    val token: String,
    val model: String,
    val expiresAtMs: Long,
    val startBeforeMs: Long,
    val source: Source,
) {
    enum class Source { PC, OWNER_KEY, SERVER }

    /** Чи встигнемо ПОЧАТИ сесію: запас на рукостискання WebSocket і setup. */
    fun canStartAt(nowMs: Long): Boolean =
        nowMs + START_MARGIN_MS < startBeforeMs && nowMs < expiresAtMs

    override fun toString(): String = "LiveToken(${source.name}, model=$model, start<$startBeforeMs)"

    companion object {
        const val START_MARGIN_MS = 5_000L
    }
}

sealed interface Mint {
    data class Got(val token: LiveToken) : Mint

    /** Джерело відповіло, але токена не дало — словами, чому. Ключа тут не буває. */
    data class Refused(val why: String) : Mint
}

fun interface LiveTokenSource {
    suspend fun mint(nowMs: Long): Mint
}

/**
 * Токени по черзі джерел (ПК, власний ключ телефона, сервер).
 *
 * Токен одноразовий (`uses: 1`), тож [take] віддає його рівно раз: кожне
 * перепідключення (goAway, обрив) бере новий. [prefetch] карбує наперед, щоб
 * перепідключення не чекало мережі.
 */
class LiveTokens(
    private val sources: List<LiveTokenSource>,
    private val now: () -> Long,
) {
    @Volatile
    private var spare: LiveToken? = null

    suspend fun take(): Mint {
        spare?.let { s ->
            spare = null
            if (s.canStartAt(now())) return Mint.Got(s)
        }
        if (sources.isEmpty()) return Mint.Refused(NO_SOURCE)
        val whys = mutableListOf<String>()
        for (source in sources) {
            val minted = try {
                source.mint(now())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Mint.Refused("збій: ${e.javaClass.simpleName}")
            }
            when (minted) {
                is Mint.Got ->
                    if (minted.token.canStartAt(now())) return minted else whys += STALE
                is Mint.Refused -> whys += minted.why
            }
        }
        return Mint.Refused(whys.joinToString("; "))
    }

    suspend fun prefetch() {
        if (spare?.canStartAt(now()) == true) return
        (take() as? Mint.Got)?.let { spare = it.token }
    }

    companion object {
        const val NO_SOURCE = "немає ні спареного компʼютера, ні ключа Gemini на телефоні"
        const val STALE = "токен прийшов уже простроченим"
    }
}

/** Відповідь ПК: `{"token","model","expires_at","start_before"}`. */
class PcTokenSource(
    /** (HTTP-код, тіло) або null, коли телефон ні з чим не спарений. */
    private val fetch: suspend () -> Pair<Int, String>?,
) : LiveTokenSource {

    override suspend fun mint(nowMs: Long): Mint {
        val (code, body) = try {
            fetch() ?: return Mint.Refused("телефон не спарений з компʼютером")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Mint.Refused("компʼютер недосяжний")
        }
        val obj = runCatching { LiveJson.parseToJsonElement(body).jsonObject }.getOrNull()
        if (code != 200) {
            val detail = obj?.get("detail") as? JsonObject
            val reason = detail?.get("code")?.jsonPrimitive?.contentOrNull
            return Mint.Refused(
                when {
                    reason == "no_key" -> "на компʼютері не задано ключ Gemini"
                    code == 401 -> "компʼютер не впізнав телефон"
                    reason == "upstream_unreachable" -> "компʼютер не дістав Gemini"
                    else -> "компʼютер не видав токен (HTTP $code)"
                },
            )
        }
        val token = obj?.get("token")?.jsonPrimitive?.contentOrNull.orEmpty()
        if (token.isBlank()) return Mint.Refused("компʼютер відповів без токена")
        val model = obj?.get("model")?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: DEFAULT_MODEL
        val expires = isoMs(obj?.get("expires_at")?.jsonPrimitive?.contentOrNull)
            ?: return Mint.Refused("компʼютер не сказав, до коли живе токен")
        val startBefore = isoMs(obj?.get("start_before")?.jsonPrimitive?.contentOrNull) ?: expires
        return Mint.Got(LiveToken(token, model, expires, startBefore, LiveToken.Source.PC))
    }
}

/**
 * Телефон тримає ключ власника сам (його вписала людина в налаштуваннях) — карбує
 * той самий одноразовий токен, що й ПК. Ключ іде лише в заголовок до Google.
 */
class OwnerKeyTokenSource(
    private val key: () -> String?,
    /** (url, ключ, json-тіло) → (HTTP-код, тіло). */
    private val post: suspend (url: String, apiKey: String, body: String) -> Pair<Int, String>,
    private val model: String = DEFAULT_MODEL,
) : LiveTokenSource {

    override suspend fun mint(nowMs: Long): Mint {
        val apiKey = key()?.trim().orEmpty()
        if (apiKey.isEmpty()) return Mint.Refused("на телефоні немає ключа Gemini")
        val expires = nowMs + TOKEN_TTL_MS
        val startBefore = nowMs + START_WINDOW_MS
        val (code, body) = try {
            post(AUTH_TOKENS_URL, apiKey, mintBody(nowMs))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Mint.Refused("Gemini недосяжний з телефона")
        }
        if (code != 200) return Mint.Refused("Gemini не видав токен (HTTP $code)")
        val name = runCatching {
            LiveJson.parseToJsonElement(body).jsonObject["name"]?.jsonPrimitive?.contentOrNull
        }.getOrNull().orEmpty()
        if (name.isBlank()) return Mint.Refused("Gemini відповів без токена")
        return Mint.Got(LiveToken(name, model, expires, startBefore, LiveToken.Source.OWNER_KEY))
    }

    companion object {
        /** Той самий запит, що на ПК (`routes_live_token.py`): один раз, хвилина на старт, 30 хв життя. */
        fun mintBody(nowMs: Long): String = buildJsonObject {
            put("uses", 1)
            put("expireTime", iso(nowMs + TOKEN_TTL_MS))
            put("newSessionExpireTime", iso(nowMs + START_WINDOW_MS))
        }.toString()
    }
}

class ServerTokenSource(
    private val fetch: suspend () -> Pair<Int, String>?,
) : LiveTokenSource {

    override suspend fun mint(nowMs: Long): Mint {
        val (code, body) = try {
            fetch() ?: return Mint.Refused("server not allowed")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Mint.Refused("server unreachable")
        }
        if (code != 200) {
            return Mint.Refused(
                when (code) {
                    401 -> "server did not recognise the device"
                    429 -> "live limit reached"
                    503 -> "server has no live voice"
                    else -> "server refused (HTTP $code)"
                },
            )
        }
        val obj = runCatching { LiveJson.parseToJsonElement(body).jsonObject }.getOrNull()
        val token = obj?.get("token")?.jsonPrimitive?.contentOrNull.orEmpty()
        if (token.isBlank()) return Mint.Refused("server replied without token")
        val model = obj?.get("model")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return Mint.Refused("server replied without model")
        val expires = isoMs(obj?.get("expires_at")?.jsonPrimitive?.contentOrNull)
            ?: return Mint.Refused("server replied without expires_at")
        val startBefore = isoMs(obj?.get("start_before")?.jsonPrimitive?.contentOrNull) ?: expires
        return Mint.Got(LiveToken(token, model, expires, startBefore, LiveToken.Source.SERVER))
    }
}

const val DEFAULT_MODEL = "gemini-3.8-live"
const val AUTH_TOKENS_URL = "https://generativelanguage.googleapis.com/v1beta/auth_tokens"
const val TOKEN_TTL_MS = 30 * 60_000L
const val START_WINDOW_MS = 60_000L

internal val LiveJson = Json { ignoreUnknownKeys = true }

internal fun iso(ms: Long): String = Instant.ofEpochMilli(ms - ms % 1000).toString()

internal fun isoMs(s: String?): Long? = s?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
