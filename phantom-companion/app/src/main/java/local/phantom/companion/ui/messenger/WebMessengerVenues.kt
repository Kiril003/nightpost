package local.phantom.companion.ui.messenger

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import local.phantom.companion.R
import local.phantom.companion.core.tool.impl.LocationSnapshot
import local.phantom.companion.core.truth.H3
import local.phantom.companion.data.VenueApi
import local.phantom.companion.data.VenueClient

/**
 * `/messenger/venues/…` — двері веба до закладів.
 *
 * Веб у телефоні сервера не бачить (CSP `connect-src 'self'`), тож усе йде
 * мостом. Три речі доводяться тестом:
 *  - веб НЕ дає координат: клітинки «поруч» рахує телефон зі свого місця
 *    (область r6 і сусідні — сервер не дізнається, на яку саме вулицю дивляться);
 *  - кривий вхід (зірки не цілі 1–5) до сервера не доходить;
 *  - жодна відмова не стає «успіхом»: у неї є код і речення.
 */
class WebMessengerVenues(
    private val api: VenueApi,
    private val here: () -> Here,
    private val words: (Word) -> String,
    private val english: () -> Boolean,
) {
    /** Де я. `Refused` — відмова воріт місця (підміна), слово в слово. */
    sealed interface Here {
        data class Point(val lat: Double, val lon: Double) : Here
        data class Refused(val uk: String, val en: String) : Here
        data object None : Here
    }

    /**
     * Речення, які знає міст: слово → рядок у strings.xml (обидві мови). Рядок
     * привʼязано до слова тут, а не в таблиці хоста: розбіжна таблиця вже
     * показувала «Закладів поруч показати не можу» під «Я на місці».
     */
    enum class Word(val res: Int) {
        NO_FIX(R.string.msgr_scene_no_fix),
        BAD_STARS(R.string.msgr_scene_bad_stars),
        NO_VENUE(R.string.msgr_scene_no_venue),
        NO_KEY(R.string.msgr_scene_no_key),
        NOT_ENABLED(R.string.msgr_scene_not_enabled),
        UNREACHABLE(R.string.msgr_scene_unreachable),
        LIMITED(R.string.msgr_scene_limited),
        REFUSED(R.string.msgr_scene_refused),
        BAD_ANSWER(R.string.msgr_scene_bad_answer),
        NOT_FOUND(R.string.msgr_scene_not_found),
    }

    /**
     * Для чого потрібне місце. Слова про його відсутність окремі: одне
     * речення «закладів поруч показати не можу» під «Я на місці» брехало.
     */
    enum class Use(val noFix: Int, val weak: Int) {
        NEAR(R.string.msgr_scene_no_fix, R.string.msgr_scene_near_stale),
        CHECK_IN(R.string.msgr_scene_checkin_no_fix, R.string.msgr_scene_place_coarse),
    }

    suspend fun handle(method: String, seg: List<String>, body: String?): String = when {
        seg.size == 3 && seg[2] == "near" && method == "GET" -> near()
        seg.size == 3 && seg[2] == "requests" && method == "GET" -> answer(api.requests(emptyList()))
        seg.size == 3 && seg[2] == "requests" && method == "POST" -> ackOf(body)
            ?.let { answer(api.requests(it)) } ?: fail(422, "bad_ack", words(Word.REFUSED))
        seg.size == 3 && method == "GET" -> answer(api.venue(seg[2]))
        seg.size == 4 && seg[3] == "rate" && method == "POST" -> rate(seg[2], body)
        else -> fail(404, "not_found", words(Word.NOT_FOUND))
    }

    private suspend fun near(): String = when (val h = here()) {
        Here.None -> fail(409, "no_fix", words(Word.NO_FIX))
        is Here.Refused -> fail(409, "no_fix", if (english()) h.en else h.uk)
        is Here.Point -> answer(api.near(areasAround(h.lat, h.lon)))
    }

    private suspend fun rate(id: String, body: String?): String {
        val stars = starsOf(body) ?: return fail(422, "bad_stars", words(Word.BAD_STARS))
        return answer(api.rate(id, stars))
    }

    private fun answer(a: VenueClient.Answer): String = when (a) {
        is VenueClient.Answer.Ok -> {
            val data = runCatching { Json.parseToJsonElement(a.json) }.getOrNull()
            if (data is JsonObject) WebMessengerReply.ok(data) else fail(502, "bad_answer", words(Word.BAD_ANSWER))
        }
        is VenueClient.Answer.Refused -> when {
            a.status == 429 -> fail(429, "limited", words(Word.LIMITED))
            // Причину відмови дає сервер (`venue_unknown`, `outside_window`…) — веб
            // розрізняє їх по коду, а не по тексту, тож код їде як є.
            else -> fail(a.status, a.reason ?: "refused", (if (english()) a.en else a.uk) ?: words(Word.REFUSED))
        }
        VenueClient.Answer.Missing, VenueClient.Answer.BadInput -> fail(404, "no_venue", words(Word.NO_VENUE))
        VenueClient.Answer.NoKey -> fail(409, "no_key", words(Word.NO_KEY))
        VenueClient.Answer.NotEnabled -> fail(503, "not_enabled", words(Word.NOT_ENABLED))
        VenueClient.Answer.Unreachable -> fail(502, "unreachable", words(Word.UNREACHABLE))
    }

    private fun fail(status: Int, code: String, detail: String) = WebMessengerReply.fail(status, code, detail)

    companion object {
        /** Шлях закладів: `/messenger/venues/…` — рівно від третього сегмента. */
        fun claims(seg: List<String>): Boolean = seg.size >= 3 && seg[0] == "messenger" && seg[1] == "venues"

        /** Показані запити: `{"ack": ["<16 hex>", …]}`, не більше 200; інше — `null`. */
        fun ackOf(body: String?): List<String>? {
            val o = runCatching { Json.parseToJsonElement(body.orEmpty()) }.getOrNull() as? JsonObject ?: return null
            val arr = o["ack"] as? kotlinx.serialization.json.JsonArray ?: return null
            if (arr.size > 200) return null
            return arr.map { el -> (el as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { ACK_ID.matches(it) } ?: return null }
        }

        private val ACK_ID = Regex("[0-9a-f]{16}")

        /** Зірки — лише ціле число JSON від 1 до 5 (не рядок, не 4.5). */
        fun starsOf(body: String?): Int? {
            val o = runCatching { Json.parseToJsonElement(body.orEmpty()) }.getOrNull() as? JsonObject ?: return null
            val p = o["stars"] as? JsonPrimitive ?: return null
            if (p.isString) return null
            return p.content.toIntOrNull()?.takeIf { it in 1..5 }
        }

        /**
         * Місце з воріт місця — або відмова словом. Тут, а не в мосту, щоб правило
         * довести тестом: що саме є «місцем» для кожної потреби.
         */
        fun hereOf(snap: LocationSnapshot?, use: Use, say: (Int) -> String): Here {
            if (snap == null) return Here.None
            val lat = snap.lat
            val lon = snap.lon
            if (lat == null || lon == null) {
                val now = say(use.noFix)
                return Here.Refused(snap.refusedUa ?: now, snap.refusedEn ?: now)
            }
            val enough = when (use) {
                Use.NEAR -> nearEnough(snap.source, snap.ageMs)
                Use.CHECK_IN -> preciseEnough(snap.source, snap.accuracyM, snap.ageMs)
            }
            if (!enough) {
                val weak = say(use.weak)
                return Here.Refused(weak, weak)
            }
            return Here.Point(lat, lon)
        }

        /** Останнє відоме місце за мережею старше за це — це «де я був», а не «де я». */
        const val NEAR_MAX_AGE_MS = 10 * 60_000L

        /**
         * Чи вистачає місця для «поруч»: область r6 має ~3 км, тож похибка не
         * мірило — але здогад не годиться. Годиться місце, яке дали ворота
         * (`gate/…`, підміну вони вже відсіяли), або свіже останнє відоме за
         * мережею. Позиція за IP і днями застарілий фікс — ні: людині показали б
         * заклади іншого району або порожній список, що читається як «поруч нічого».
         */
        fun nearEnough(source: String, ageMs: Long?): Boolean = when {
            source.startsWith("gate/") -> true
            source.startsWith("last-known/") -> ageMs != null && ageMs <= NEAR_MAX_AGE_MS
            else -> false
        }

        /** Джерело позиції, якій можна довіряти клітинку r8: злитий фікс воріт місця. */
        const val PRECISE_SOURCE = "gate/fused"

        /** Фікс старший за це — вже не «я на місці», а «я тут був». */
        const val PRECISE_MAX_AGE_MS = 60_000L

        /** Клітинка r8 має ~460 м ребра; похибка більша за 100 м уже ділить фікс між сусідами. */
        const val PRECISE_MAX_ACCURACY_M = 100f

        /**
         * Чи вистачає фікса для «Я на місці»: сервер звіряє клітинку r8 до
         * знака, тож потрібен злитий фікс, свіжий і з відомою малою похибкою.
         * Мережевий, застарілий чи без похибки фікс — відмова, а не здогад під підписом.
         */
        fun preciseEnough(source: String, accuracyM: Float?, ageMs: Long?): Boolean =
            source == PRECISE_SOURCE &&
                accuracyM != null && accuracyM <= PRECISE_MAX_ACCURACY_M &&
                ageMs != null && ageMs <= PRECISE_MAX_AGE_MS

        /** Крок сітки проб: ребро r6 ~3,2 км, тож 250 м не проскакує жодного сусіда. */
        private const val STEP_M = 250.0
        private const val M_PER_DEG_LAT = 111_320.0
        private const val MAX_PROBES = 6_000

        /**
         * Область r6 мого місця і ВСІ, що з нею межують (кільце k=1), за зростанням.
         *
         * Набір залежить лише від області, в якій я стою, а не від того, де в
         * ній: сервер бачить «ця область і сусіди», а не бік чи куток, і моя
         * власна область не стоїть першою. Сусідів знаходить заливка сіткою
         * проб по всій моїй області: кожна проба за її межею — сусід. Окремої
         * «операції кільця» в [H3] немає, а виносити її заради одного виклику
         * було б другою копією геометрії.
         */
        fun areasAround(lat: Double, lon: Double): List<String> {
            val own = H3.latLngToCell(lat, lon, H3.AREA_RES)
            val cosLat = Math.cos(Math.toRadians(lat)).coerceAtLeast(0.01)
            val dLat = STEP_M / M_PER_DEG_LAT
            val dLon = STEP_M / (M_PER_DEG_LAT * cosLat)
            val ring = sortedSetOf(own)
            val seen = HashSet<Long>()
            val queue = ArrayDeque<IntArray>()
            fun key(i: Int, j: Int) = (i.toLong() shl 32) xor (j.toLong() and 0xFFFFFFFFL)
            seen += key(0, 0)
            queue.addLast(intArrayOf(0, 0))
            var probes = 0
            while (queue.isNotEmpty() && probes < MAX_PROBES) {
                val (i, j) = queue.removeFirst().let { it[0] to it[1] }
                for ((di, dj) in STEPS) {
                    val ni = i + di
                    val nj = j + dj
                    if (!seen.add(key(ni, nj))) continue
                    val la = lat + ni * dLat
                    if (la !in -89.9..89.9) continue
                    probes++
                    val cell = H3.latLngToCell(la, ((lon + nj * dLon + 540.0) % 360.0) - 180.0, H3.AREA_RES)
                    if (cell == own) queue.addLast(intArrayOf(ni, nj)) else ring += cell
                }
            }
            return ring.toList()
        }

        private val STEPS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    }
}
