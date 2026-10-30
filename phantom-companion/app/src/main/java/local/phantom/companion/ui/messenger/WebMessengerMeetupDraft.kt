package local.phantom.companion.ui.messenger

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.tool.impl.MeetupDraftAsk

/**
 * Чернетка зустрічі чи рахунку від помічника → відкритий аркуш у вебі.
 *
 * Помічник нічого не надсилає: він кладе в чернетку те, що почув («збери
 * Олену й Петра в Cafe X о 19:00»), а «Запросити» натискає людина
 * (`docs/scene/MEETUP_CONTRACT.md` §6). Дорога та сама, що в
 * `__phantomOpenChat`: стан у [pending], його забирає
 * [WebMessengerHost] і штовхає у веб викликом `window.__phantomOpenMeetupDraft(json)`.
 *
 * Форма JSON — закрита (`nativeMeetupDraft.ts`):
 *  - `{people?: string[], place?: string, at?: string|number}` — чернетка зустрічі;
 *  - `{split: "claimed"|"equal"}` — «розділи рахунок».
 * Імена тут НЕ звіряються з контактами й заклад не вгадується: це робить веб
 * з контактів і переліку закладів, а незнайоме лишається чесним «не знайшов».
 */
object WebMessengerMeetupDraft {
    /**
     * Прохання помічника: готовий JSON і мить, після якої воно вже не діє.
     * Без строку чернетка, замовлена, поки месенджер був закритий, відкрилась би
     * через години посеред чогось іншого — людина вже не памʼятає, що просила.
     */
    data class Ask(val json: String, val expiresAtMs: Long)

    /** Прохання, яке веб ще не взяв. Одне в черзі: нове заступає старе. */
    val pending = MutableStateFlow<Ask?>(null)

    /** Скільки прохання чекає, поки месенджер відкриють. */
    const val TTL_MS = 5 * 60_000L

    /** Скільки разів і з яким кроком хост пробує, поки сторінка ще піднімається. */
    const val TRIES = 20
    const val RETRY_MS = 500L

    private const val MAX_PEOPLE = 12
    private const val MAX_TEXT = 120
    private val SPLIT_MODES = setOf("claimed", "equal")
    private val VENUE_ID = Regex("^[0-9a-f]{16}$")

    /** Чернетка зустрічі. Порожня (ні людей, ні місця, ні часу) — теж чернетка: аркуш відкриється чистим. */
    fun meetup(people: List<String>, place: String?, at: String?): String = buildJsonObject {
        val names = people.map { clip(it) }.filter { it.isNotEmpty() }.distinct().take(MAX_PEOPLE)
        if (names.isNotEmpty()) put("people", JsonArray(names.map(::JsonPrimitive)))
        clip(place.orEmpty()).takeIf { it.isNotEmpty() }?.let { put("place", it) }
        clip(at.orEmpty()).takeIf { it.isNotEmpty() }?.let { put("at", it) }
    }.toString()

    /** Мітка з мапи для чернетки: точка (5 знаків, ~1 м) і підпис адреси, якщо він є. */
    fun pin(lat: Double, lon: Double, label: String?, venueId: String? = null): String? {
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val round = { v: Double -> Math.round(v * 1e5) / 1e5 }
        return buildJsonObject {
            put("pin", buildJsonObject {
                put("lat", round(lat))
                put("lon", round(lon))
                clip(label.orEmpty()).takeIf { it.isNotEmpty() }?.let { put("label", it) }
                venueId?.takeIf { VENUE_ID.matches(it) }?.let { put("venue", it) }
            })
        }.toString()
    }

    /** «Зустрітися тут» з мапи: відкрити чернетку з цією точкою; `false` — точка не на Землі. */
    fun meetHere(lat: Double, lon: Double, label: String?, nowMs: Long = System.currentTimeMillis(), venueId: String? = null): Boolean =
        pin(lat, lon, label, venueId)?.also { open(it, nowMs) } != null

    /** «Розділи рахунок»: лише два режими, інше — `null`, а не вгадування. */
    fun split(mode: String): String? =
        mode.trim().lowercase().takeIf { it in SPLIT_MODES }?.let { buildJsonObject { put("split", it) }.toString() }

    /**
     * Покласти прохання в чергу: хост відкриє аркуш, щойно веб зможе — але не
     * пізніше за [TTL_MS]. Кличе помічник ([MeetupCreateTool], [BillSplitTool]).
     */
    fun open(json: String, nowMs: Long = System.currentTimeMillis()) {
        pending.value = Ask(json, nowMs + TTL_MS)
    }

    /**
     * Двері для інструментів помічника (`meetup.create`, `bill.split`): прохання
     * стає чернеткою в черзі хоста. Нічого не надсилає. Режим, якого веб не знає,
     * — виняток, а не мовчки порожня чернетка: інструмент скаже про це людині.
     */
    fun asked(ask: MeetupDraftAsk) {
        open(
            when (ask) {
                is MeetupDraftAsk.Meetup -> meetup(ask.people, ask.place, ask.at)
                is MeetupDraftAsk.Split -> split(ask.mode) ?: throw IllegalArgumentException("bill split mode: ${ask.mode}")
            },
        )
    }

    /**
     * Що виконує хост у WebView. Рядок цілком — з лапками, тож жодне слово
     * людини не стає кодом. Відповідь — одне з трьох слів: `missing` (веб ще
     * не підняв двері), `opened` (аркуш відкрито), `refused` (двері є, але
     * відмовили: скажімо, ділити в цій розмові нічого — веб уже сказав про це людині).
     */
    fun script(json: String): String =
        "(function(){var f=window.__phantomOpenMeetupDraft;" +
            "if(typeof f!=='function')return 'missing';" +
            "return f(${JsonPrimitive(json)})?'opened':'refused';})()"

    enum class Outcome { OPENED, REFUSED, MISSING, EXPIRED }

    /** `evaluateJavascript` віддає JSON: рядок приходить у лапках, збій чи `null` — це «двері не відповіли». */
    fun outcome(raw: String?): Outcome = when (raw?.trim('"')) {
        "opened" -> Outcome.OPENED
        "refused" -> Outcome.REFUSED
        else -> Outcome.MISSING
    }

    /**
     * Донести чернетку до веба. `missing` — сторінка ще піднімається: пробуємо
     * знову до [TRIES] разів. `opened` і `refused` — відповідь: прохання
     * знімається з черги, бо повтор після відмови лише повторив би сповіщення
     * веба і воскресав би з кожним новим WebView. Не дочекались дверей —
     * прохання лишається в черзі до наступної сторінки, але лише до свого строку:
     * прострочене знімається, не відкриваючись ([Outcome.EXPIRED]).
     */
    suspend fun deliver(
        ask: Ask,
        run: suspend (script: String) -> String?,
        pause: suspend (ms: Long) -> Unit = { kotlinx.coroutines.delay(it) },
        now: () -> Long = System::currentTimeMillis,
    ): Outcome {
        repeat(TRIES) {
            if (now() >= ask.expiresAtMs) {
                pending.compareAndSet(ask, null)
                return Outcome.EXPIRED
            }
            val result = outcome(run(script(ask.json)))
            if (result != Outcome.MISSING) {
                pending.compareAndSet(ask, null)
                return result
            }
            pause(RETRY_MS)
        }
        return Outcome.MISSING
    }

    private fun clip(s: String): String = s.replace(Regex("\\s+"), " ").trim().take(MAX_TEXT)
}
