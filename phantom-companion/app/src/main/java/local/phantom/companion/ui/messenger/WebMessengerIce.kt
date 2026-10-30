package local.phantom.companion.ui.messenger

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.io.File
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import local.phantom.companion.BuildConfig
import local.phantom.companion.R
import local.phantom.companion.data.AppContainer

class WebMessengerIce(
    private val filesDir: File,
    private val baseUrl: String,
    private val inbound: () -> Boolean,
    private val say: (Int, Any?) -> String,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = 4_000,
) {

    // Таймаут фіксований на все життя інстанса (constructor-param), тож
    // ставиться в плагін клієнта, а не на запит: ktor читає `timeout {}` з
    // атрибутів запиту лише коли плагін встановлено, а значення в install{}
    // уже й так рівно те, що нам треба.
    private val http by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = timeoutMs
                connectTimeoutMillis = timeoutMs
            }
        }
    }

    /** Відповідь сервера + коли її взято й до якої миті її можна віддавати. */
    private class Cached(val fetchedAt: Long, val expiresAt: Long, val result: JsonObject)

    @Volatile
    private var cache: Cached? = null

    suspend fun answer(): String = buildJsonObject {
        put("ok", true)
        put("status", 200)
        put("data", config())
    }.toString()

    suspend fun config(): JsonObject {
        selfHosted()?.let { return it }
        return fromServer()
    }

    /**
     * Контракт SRV (`GET /api/ice`): сервер сам вирішує, чи є TURN, і сам
     * мінтить credential — телефон лише звіряє форму відповіді, а не
     * довіряє заявленому `turn`/`username`/`credential` без пари. Кеш — до
     * половини `ttl`, щоб короткоживучий TURN-пароль не переживав власний вік
     * у кеші телефона; порожня відповідь (сервер лежить/спить) кешується
     * коротко (NEGATIVE_CACHE_MS), а не на кожен виклик підряд, але й не на
     * довше за одну холодну побудову Fly. `inbound` і `ttl` у кеш НЕ йдуть
     * готовими: їх пишемо в кожну відповідь заново. `inbound` — бо слухач
     * сітки встає пізніше за перший запит з вебу, і кешований TURN на 30 хв
     * інакше заморожував би старе «вхідні не приймаються» на весь той час.
     * `ttl` — бо веб тримає пару ще пів власного `ttl` від миті отримання, і
     * якби кеш віддавав той самий `ttl`, що й при першому запиті, вік пари
     * складався б двічі й вона доходила б до `RTCPeerConnection` на межі
     * спливу. Тож у веб їде залишок життя пари, а не її початкова довжина.
     */
    private suspend fun fromServer(): JsonObject {
        if (baseUrl.isBlank()) return empty(say(R.string.msgr_bridge_no_license_host, null))
        cache?.let { if (nowMs() < it.expiresAt) return fresh(it.result, it.fetchedAt) }
        // `reached` розрізняє дві відмови, які раніше кешувались однаково: сервер
        // ВІДПОВІВ (навіть «ні») — варто почекати NEGATIVE_CACHE_MS, щоб не бити
        // сплячу Fly-машину щоразу. Мережа НЕДОСяжна (DNS/TLS/таймаут/конект) —
        // може минути за секунду (метро, Wi-Fi-перемикання), і 30 с кешу тут
        // складались би з ще 60 с кешу на вебі (iceRoads.ts) у до 90 с без ICE
        // на щойно повернутій мережі. Тому недосяжність НЕ кешується взагалі.
        var reached = true
        val result = try {
            val response = http.get("$baseUrl/api/ice")
            if (response.status.value in 200..299) sanitize(response.bodyAsText())
            else empty(say(R.string.msgr_bridge_ice_http, response.status.value))
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnknownHostException) {
            reached = false
            empty(say(R.string.msgr_bridge_ice_dns, e.message ?: baseUrl))
        } catch (e: SSLException) {
            reached = false
            empty(say(R.string.msgr_bridge_ice_tls, e.message ?: baseUrl))
        } catch (e: Exception) {
            reached = false
            empty(
                if (isTimeout(e)) say(R.string.msgr_bridge_ice_timeout, baseUrl)
                else say(R.string.msgr_bridge_ice_connect, e.message ?: e::class.simpleName),
            )
        }
        val fetchedAt = nowMs()
        if (reached) {
            val ttl = (result["ttl"] as? JsonPrimitive)?.longOrNull ?: 0L
            cache = Cached(fetchedAt, fetchedAt + (if (ttl > 0) ttl * 500L else NEGATIVE_CACHE_MS), result)
        }
        return fresh(result, fetchedAt)
    }

    // `inbound` читається в момент відповіді, а не в момент, коли записано кеш;
    // `ttl` — це те, що лишилось від життя пари, а не те, з чим її видали.
    // Лічимо від різниці годинників телефона (взято → віддаємо), а не від
    // абсолютного часу: перекошений годинник телефона тут нічого не псує.
    private fun fresh(result: JsonObject, fetchedAt: Long): JsonObject = buildJsonObject {
        result.forEach { (k, v) -> if (k != "inbound" && k != "ttl") put(k, v) }
        val ttl = (result["ttl"] as? JsonPrimitive)?.longOrNull ?: 0L
        put("ttl", (ttl - (nowMs() - fetchedAt).coerceAtLeast(0L) / 1000L).coerceAtLeast(0L))
        put("inbound", inbound())
    }

    private fun isTimeout(e: Throwable): Boolean = e::class.simpleName?.contains("Timeout", ignoreCase = true) == true

    // Переписує відповідь сервера в НАШ перелік полів: що сервер не сказав
    // явно (secret, чужі ключі), у веб не потрапляє навіть якщо сервер
    // колись помилково це покладе в тіло.
    private fun sanitize(body: String): JsonObject {
        val raw = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return empty(say(R.string.msgr_bridge_ice_bad_answer, null))
        val kept = ((raw["iceServers"] as? JsonArray) ?: JsonArray(emptyList()))
            .mapNotNull { (it as? JsonObject)?.let(::sanitizedServer) }
        val turnEntry = kept.firstOrNull { it.username != null }
        val ttl = lifetimeS(turnEntry, (raw["ttl"] as? JsonPrimitive)?.longOrNull ?: 0L)
        return buildJsonObject {
            put("iceServers", buildJsonArray {
                kept.forEach { s ->
                    add(buildJsonObject {
                        put("urls", buildJsonArray { s.urls.forEach { add(JsonPrimitive(it)) } })
                        s.username?.let { put("username", it) }
                        s.credential?.let { put("credential", it) }
                    })
                }
            })
            put("turn", turnEntry != null)
            put("ttl", ttl)
            put("inbound", inbound())
            // Лише з TURN-запису: бридж раніше слав relay_host разом із TURN,
            // ніколи сам собою — STUN-тільки хост тут брехав би про те саме.
            turnEntry?.urls?.firstOrNull()?.let(::hostOf)?.let { put("relay_host", it) }
            raw.str("detail")?.let { put("detail", it) }
        }
    }

    private class SanitizedServer(val urls: List<String>, val username: String?, val credential: String?)

    // TURN-REST `username` — це МОМЕНТ СПЛИНУ (сервер мінтить credential з
    // `expiry unix` у username, coturn допускає ще й `expiry:імʼя`), а не
    // тривалість. Заявлений сервером `ttl` — його власна довжина життя пари
    // в мить видачі; якщо username каже менше, правда там. Більше за заявлений
    // `ttl` ніколи: годинник телефона може відставати, і тоді «до спливу ще
    // десять днів» — це брехня про пару, видану на годину.
    private fun lifetimeS(turnEntry: SanitizedServer?, declared: Long): Long {
        val expiresAtS = turnEntry?.username?.substringBefore(':')?.toLongOrNull()
            ?: return declared
        val left = (expiresAtS - nowMs() / 1000L).coerceAtLeast(0L)
        return if (declared > 0) minOf(declared, left) else left
    }

    // TURN без пари username+credential — сервер або недомінтив, або цей
    // запис чужий; викидаємо весь запис, не лише бракуючі поля: половинчаста
    // TURN-адреса у веб гірша за її відсутність (ICE все одно відмовить).
    private fun sanitizedServer(entry: JsonObject): SanitizedServer? {
        val urls = urlsOf(entry["urls"]).filter(::isIceScheme)
        if (urls.isEmpty()) return null
        if (urls.none(::isTurnUrl)) return SanitizedServer(urls, null, null)
        val username = entry.str("username") ?: return null
        val credential = entry.str("credential") ?: return null
        return SanitizedServer(urls, username, credential)
    }

    private fun urlsOf(element: JsonElement?): List<String> = when (element) {
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> listOfNotNull(element.contentOrNull)
        else -> emptyList()
    }

    private fun isIceScheme(url: String): Boolean =
        listOf("stun:", "stuns:", "turn:", "turns:").any { url.startsWith(it, ignoreCase = true) }

    private fun isTurnUrl(url: String): Boolean =
        url.startsWith("turn:", ignoreCase = true) || url.startsWith("turns:", ignoreCase = true)

    private fun hostOf(url: String): String? {
        val afterScheme = url.substringAfter(':', "").ifBlank { return null }
        val authority = afterScheme.substringBefore('?').removePrefix("//")
        return authority.substringBefore(':').substringBefore('/').takeIf { it.isNotBlank() }
    }

    private fun empty(detail: String): JsonObject = buildJsonObject {
        put("iceServers", buildJsonArray {})
        put("turn", false)
        put("ttl", 0)
        put("inbound", inbound())
        put("detail", detail)
    }

    private suspend fun selfHosted(): JsonObject? {
        val cfg = withContext(Dispatchers.IO) { turnJson() } ?: return null
        val host = cfg.str("host") ?: return null
        val secret = cfg.str("secret") ?: return null
        val port = (cfg["port"] as? JsonPrimitive)?.intOrNull ?: 3478
        val ttl = (cfg["ttl"] as? JsonPrimitive)?.longOrNull ?: 3600L
        val username = (nowMs() / 1000L + ttl).toString()
        return buildJsonObject {
            put("iceServers", buildJsonArray {
                add(buildJsonObject { put("urls", buildJsonArray { add(JsonPrimitive("stun:$host:$port")) }) })
                add(buildJsonObject {
                    put("urls", buildJsonArray {
                        add(JsonPrimitive("turn:$host:$port?transport=udp"))
                        add(JsonPrimitive("turn:$host:$port?transport=tcp"))
                    })
                    put("username", username)
                    put("credential", hmacSha1(secret, username))
                })
            })
            put("turn", true)
            put("ttl", ttl)
            put("inbound", inbound())
            put("relay_host", host)
        }
    }

    // The relay address and secret live in app data, never in the APK.
    private fun turnJson(): JsonObject? = runCatching {
        val file = File(filesDir, "turn.json")
        if (!file.isFile || file.length() == 0L) return null
        Json.parseToJsonElement(file.readText()) as? JsonObject
    }.getOrNull()

    private fun JsonObject.str(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    companion object {
        // Fly-машина ретранслятора auto-stop; перший запит після сну будить
        // її довше за цей інтервал. Коротка негативна кеш-полиця не бʼє
        // сервер на кожен виклик, але й не заморожує відмову на півгодини.
        private const val NEGATIVE_CACHE_MS = 30_000L

        fun of(container: AppContainer): WebMessengerIce {
            val ctx = container.applicationContext
            return WebMessengerIce(
                filesDir = ctx.filesDir,
                baseUrl = BuildConfig.LICENSE_BASE_URL,
                inbound = { container.mesh.isListening },
                say = { id, arg -> if (arg == null) ctx.getString(id) else ctx.getString(id, arg) },
            )
        }

        fun hmacSha1(key: String, message: String): String {
            val mac = javax.crypto.Mac.getInstance("HmacSHA1")
            mac.init(javax.crypto.spec.SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1"))
            return java.util.Base64.getEncoder().encodeToString(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
        }
    }
}
