package local.phantom.companion.core.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Сеанс синку телефон ↔ ПК за SYNC.md: спершу `POST` своїх змін з моменту
 * останнього успіху, потім `GET ?since=<курсор ПК>` до `more=false`.
 *
 * Відновлюваний: обидва курсори зберігаються в meta репліки ПІСЛЯ кожної
 * успішної сторінки, тож обрив посередині продовжується з того самого місця,
 * а повтор безпечний (ПК і репліка ідемпотентні за `id` + `lamport`).
 *
 * Дорогу ([Transport]) дає застосунок — той самий спарений канал, що й решта
 * розмов із ПК: мережа поруч, а без неї — реле до свого ПК; TLS приколото до
 * сертифіката ПК, тож реле бачить лише шифротекст.
 */
class MemorySync(
    private val ledger: Ledger,
    private val transport: Transport,
    private val pageSize: Int = 200,
    private val now: () -> Long = System::currentTimeMillis,
) {
    interface Transport {
        /** `GET /api/v1/memory/changes?since=&limit=` → (HTTP-код, тіло). */
        suspend fun pull(since: Long, limit: Int): Pair<Int, String>

        /** `POST /api/v1/memory/changes` → (HTTP-код, тіло). */
        suspend fun push(body: String): Pair<Int, String>
    }

    data class Report(val pushed: Int, val pulled: Int, val applied: Int, val ignored: Int, val ok: Boolean, val error: String? = null)

    private val json = Json { ignoreUnknownKeys = true }

    /** Свої зміни — те, що зробив телефон; прийняте з ПК назад не відлунює. */
    private fun mine(it: MemoryItem): Boolean = !it.provenance.device.startsWith("pc")

    suspend fun run(): Report {
        val st = ledger.store
        var pushed = 0
        var pulled = 0
        var applied = 0
        var ignored = 0
        try {
            // 1) свої зміни
            while (true) {
                val since = st.metaLong(PUSHED)
                val page = ledger.changes(since, pageSize, ::mine)
                if (page.changes.isNotEmpty()) {
                    val body = buildJsonObject {
                        put("device", ledger.device)
                        put("changes", buildJsonArray { page.changes.forEach { add(it) } })
                    }.toString()
                    val (code, text) = transport.push(body)
                    if (code !in 200..299) return fail(pushed, pulled, applied, ignored, "push $code ${detail(text)}")
                    val r = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                    r.long("lamport")?.let { st.observeLamport(it) }
                    pushed += page.changes.size
                }
                st.metaSet(PUSHED, page.cursor)
                if (!page.more) break
            }
            // 2) зміни ПК
            while (true) {
                val since = st.metaLong(PC_CURSOR)
                val (code, text) = transport.pull(since, pageSize)
                if (code !in 200..299) return fail(pushed, pulled, applied, ignored, "pull $code ${detail(text)}")
                val r = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                    ?: return fail(pushed, pulled, applied, ignored, "pull: not json")
                val changes = (r["changes"] as? JsonArray) ?: JsonArray(emptyList())
                // Прийняте з ПК назад не шлеться (фільтр [mine]); власні наслідки —
                // `reconcileSlot` — мають пристрій телефона й поїдуть наступним сеансом.
                val res = ledger.applyChanges(changes, device = r.str("node") ?: "pc")
                pulled += changes.size
                applied += res.applied
                ignored += res.ignored
                val next = r.long("cursor") ?: since
                st.metaSet(PC_CURSOR, next)
                if (!r.bool("more")) break
                if (next <= since) return fail(pushed, pulled, applied, ignored, "pull: cursor stuck at $since")
            }
            st.metaSet(LAST_OK, now())
            st.metaSet(LAST_TRY, now())
            st.metaSet(LAST_ERROR, "")
            return Report(pushed, pulled, applied, ignored, ok = true)
        } catch (c: java.util.concurrent.CancellationException) {
            throw c // скасування корутини — не помилка синку
        } catch (t: Throwable) {
            return fail(pushed, pulled, applied, ignored, t.message ?: t.javaClass.simpleName)
        }
    }

    /** Чесний стан для екрана «Памʼять». */
    fun state(): JsonObject = buildJsonObject {
        val st = ledger.store
        put("last_ok_ms", st.metaLong(LAST_OK).takeIf { it > 0 })
        put("last_try_ms", st.metaLong(LAST_TRY).takeIf { it > 0 })
        put("last_error", st.metaGet(LAST_ERROR)?.takeIf { it.isNotEmpty() })
        put("pending_push", ledger.changes(st.metaLong(PUSHED), 10_000, ::mine).changes.size)
        put("pc_cursor", st.metaLong(PC_CURSOR))
    }

    private fun fail(pushed: Int, pulled: Int, applied: Int, ignored: Int, why: String): Report {
        ledger.store.metaSet(LAST_ERROR, why.take(200))
        ledger.store.metaSet(LAST_TRY, now())
        return Report(pushed, pulled, applied, ignored, ok = false, error = why)
    }

    private fun detail(body: String): String =
        runCatching { json.parseToJsonElement(body).jsonObject.str("detail") }.getOrNull() ?: body.take(80)

    companion object {
        const val PUSHED = "sync.pushed_seq"
        const val PC_CURSOR = "sync.pc_cursor"
        const val LAST_OK = "sync.last_ok_ms"
        const val LAST_TRY = "sync.last_try_ms"
        const val LAST_ERROR = "sync.last_error"
    }
}
