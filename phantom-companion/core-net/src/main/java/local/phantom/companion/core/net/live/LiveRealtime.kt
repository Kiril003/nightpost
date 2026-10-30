package local.phantom.companion.core.net.live

import java.net.URLEncoder
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import local.phantom.companion.core.net.geosrc.GeoHttp

/**
 * ATLAS-4 — протокол живого сокета (Supabase Realtime = Phoenix-канали).
 *
 * Тут лежить лише форма: як виглядає рукостискання, як виглядає кадр і як з
 * кадру дістати мітку. Життєвий цикл сокета — у [LiveMarksRealtime], злиття —
 * у репозиторії.
 *
 * Чесність: живий сокет ≠ свіжі дані. Сокет доставляє те, що трапилось ПОКИ
 * він тримався; усе, що він проґавив (розрив, перезапуск, мітка з іншого
 * району), доносить звіряльне опитування. Тому сокет — прискорювач, а не
 * джерело істини, і жодна гілка нижче не має права зупинити опитування.
 */

/** Що сокет каже назовні. Нічого, окрім цих трьох фактів, він не знає. */
sealed interface LiveStreamEvent {

    /** Канал приєднався до топіка. Означає «підписка є», не «дані свіжі». */
    data object Connected : LiveStreamEvent

    /** Нова мітка. [receivedAtMs] — момент прийому, для заміру затримки. */
    data class Inserted(val mark: LiveMark, val receivedAtMs: Long) : LiveStreamEvent

    /** Канал відпав. Мовчазна подія: оператор про це не дізнається. */
    data class Dropped(val why: String) : LiveStreamEvent
}

/**
 * Порт живого сокета. Холодний потік: підписка існує рівно доти, доки її
 * збирають, — саме тому «сокет живе, поки відкрита мапа» не потребує жодного
 * окремого вимикача.
 */
interface LiveMarksStreamPort {
    val isConfigured: Boolean

    fun stream(): Flow<LiveStreamEvent>
}

/** Двонапрямний текстовий канал. Існує, щоб протокол тестувався без сокета. */
interface LiveWire {
    suspend fun send(text: String)

    /** Наступний текстовий кадр; `null` — інший бік закрив канал. */
    suspend fun receive(): String?

    suspend fun close()
}

fun interface LiveWireOpener {
    /** Відкриває канал. Виняток означає «не вийшло» — його ловить викликач. */
    suspend fun open(url: String): LiveWire
}

/**
 * ЄДИНЕ місце, де anon-ключ лягає в URL, і причина, з якої правило
 * «ключ лише в заголовках» тут згинається.
 *
 * Рукостискання WebSocket не має тіла, а заголовки на ньому ставити не
 * можна — Supabase Realtime читає ключ виключно з query. Обійти це можна
 * лише відмовившись від Realtime.
 *
 * Ціна цього винятку сплачується тут-таки: URL збирається в одній функції,
 * назовні не віддається і НІКОЛИ не пишеться в журнал — усе, що йде в
 * журнал, проходить крізь [scrub].
 */
internal object RealtimeHandshake {

    /** Версія серіалізатора Phoenix. 1.0.0 — кадр-обʼєкт, 2.0.0 — кадр-масив. */
    const val VSN: String = "1.0.0"

    fun url(config: LiveMarksConfig): String {
        val host = config.baseUrl.trim().trimEnd('/').removePrefix("https://").removePrefix("http://")
        val key = URLEncoder.encode(config.anonKey, "UTF-8")
        return "wss://$host/realtime/v1/websocket?apikey=$key&vsn=$VSN"
    }

    /** Те саме, але придатне для журналу. Інший вигляд URL не існує. */
    fun scrub(url: String): String = KEY_QUERY.replace(url, "apikey=***")

    private val KEY_QUERY = Regex("apikey=[^&\\s]*")
}

/** Кадр Phoenix. `ref` приходить рядком, а на розсилках — `null`. */
@Serializable
internal data class PhxFrame(
    val topic: String = "",
    val event: String = "",
    val payload: JsonObject? = null,
    val ref: String? = null,
)

internal object PhxCodec {

    const val TOPIC: String = "realtime:public:${LiveMarksConfig.TABLE}"

    /** Службовий топік серцебиття. Відповіді на ньому — доказ, що канал живий. */
    const val PHOENIX: String = "phoenix"

    const val JOIN_REF: String = "1"

    const val EVENT_REPLY: String = "phx_reply"
    const val EVENT_CHANGES: String = "postgres_changes"
    const val EVENT_ERROR: String = "phx_error"
    const val EVENT_CLOSE: String = "phx_close"

    /**
     * Друге, справжнє підтвердження. `phx_reply` на приєднання каже лише
     * «топік прийнято»; чи піднялась підписка на зміни в Postgres, сервер
     * повідомляє окремим кадром «Subscribed to PostgreSQL». Між цими двома
     * і живе стан «сокет відкритий, а змін не буде ніколи».
     */
    const val EVENT_SYSTEM: String = "system"

    /**
     * Приєднання до топіка.
     *
     * `access_token` дублює ключ із рукостискання. Для anon він НЕ обовʼязковий
     * (перевірено на живому проєкті: підписка встає й без нього — ключ у query
     * уже автентифікує зʼєднання). Стоїть тут тому, що це єдине місце, куди
     * ляже токен користувача, коли anon піде.
     *
     * Фільтр за районом тут не виражається: `postgres_changes` вміє рівність
     * колонки, а район — це префікс геохешу. Відсів робить клієнт.
     */
    fun join(token: String): String = buildJsonObject {
        put("topic", TOPIC)
        put("event", "phx_join")
        putJsonObject("payload") {
            putJsonObject("config") {
                putJsonObject("broadcast") { put("self", false) }
                putJsonObject("presence") { put("key", "") }
                putJsonArray("postgres_changes") {
                    addJsonObject {
                        put("event", "INSERT")
                        put("schema", "public")
                        put("table", LiveMarksConfig.TABLE)
                    }
                }
            }
            put("access_token", token)
        }
        put("ref", JOIN_REF)
    }.toString()

    fun heartbeat(ref: String): String = buildJsonObject {
        put("topic", PHOENIX)
        put("event", "heartbeat")
        putJsonObject("payload") {}
        put("ref", ref)
    }.toString()

    fun decode(text: String): PhxFrame? =
        runCatching { GeoHttp.json.decodeFromString(PhxFrame.serializer(), text) }.getOrNull()

    fun isOk(frame: PhxFrame): Boolean = frame.payload?.str("status") == "ok"

    /** Причина відмови в приєднанні, як її називає сервер. */
    fun refusal(frame: PhxFrame): String {
        val response = frame.payload?.get("response") as? JsonObject
        return response?.str("reason") ?: response?.str("message") ?: frame.payload?.str("status") ?: "відмова"
    }

    /**
     * Скільки підписок на зміни сервер СПРАВДІ видав.
     *
     * `status: ok` на приєднанні означає лише «топік прийнято». Якщо таблиці
     * немає в публікації `supabase_realtime`, відповідь усе одно «ok», але
     * список привʼязок порожній — і канал житиме вічно, не принісши нічого.
     * Це найчастіша помилка налаштування, і вона мовчазна.
     */
    fun grantedBindings(frame: PhxFrame): Int {
        val response = frame.payload?.get("response") as? JsonObject ?: return 0
        return (response["postgres_changes"] as? JsonArray)?.size ?: 0
    }

    /** Що саме сервер сказав про підписку — для журналу й причини розриву. */
    fun systemNote(frame: PhxFrame): String =
        frame.payload?.str("message") ?: frame.payload?.str("status") ?: "без деталей"

    /**
     * Мітка з розсилки. `record` — форма дроту, `new` — форма supabase-js;
     * приймаються обидві, бо ця межа не наша й міняється не з нами.
     */
    fun insertedMark(frame: PhxFrame): LiveMark? {
        val data = frame.payload?.get("data") as? JsonObject ?: return null
        if (data.str("type") != "INSERT") return null
        if (data.str("table") != LiveMarksConfig.TABLE) return null
        val record = (data["record"] ?: data["new"]) as? JsonObject ?: return null
        return runCatching {
            GeoHttp.json.decodeFromJsonElement(MarkRowDto.serializer(), record)
        }.getOrNull()?.toDomain()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
