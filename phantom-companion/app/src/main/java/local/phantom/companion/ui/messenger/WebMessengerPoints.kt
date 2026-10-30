package local.phantom.companion.ui.messenger

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.R
import local.phantom.companion.core.truth.H3
import local.phantom.companion.data.PointsApi
import local.phantom.companion.data.PointsClaims
import local.phantom.companion.data.VenueClient

/**
 * `/messenger/points/…` — двері веба до балів (`docs/scene/MEETUP_CONTRACT.md` §8).
 *
 *  - `POST checkin {meetup_id, venue_id, at_ms}` — «Я на місці». Веб місця не
 *    дає: клітинку r8 рахує телефон зі свого місця, і підписана заява несе
 *    лише її (не координати). Без чесного місця — відмова словом, не відмітка.
 *  - `POST payment {signature, ref}` — перша власна підтверджена частка. Адресу
 *    гаманця бере телефон зі свого гаманця, а не з веба; транзакцію й гроші
 *    сервер читає з мережі сам. Адреса звʼязується з ключем пристрою лише тут,
 *    коли людина платить (рішення власника). Оплату доводить ще й підпис самого
 *    гаманця — це відбиток пальця, а його веб не вимагає сам: міст лише ПРИЙМАЄ
 *    заявку в [PointsClaims] і відповідає «ще не зараховано», а відбиток просить
 *    Профіль, коли людина натисне «Зарахувати». Підписати гаманцем звідси
 *    неможливо за побудовою: цей клас не знає про підпис гаманця нічого.
 *  - `GET me` — власні бали, підписом; чужих не віддає ніхто.
 *
 * Бали телефон ніде не називає й не рахує. Дверей купити, переказати чи вивести
 * бали тут немає: решта шляхів — 404.
 */
class WebMessengerPoints(
    private val api: PointsApi,
    private val claims: PointsClaims,
    private val here: () -> WebMessengerVenues.Here,
    private val wallet: () -> Wallet?,
    private val words: (Word) -> String,
    private val english: () -> Boolean,
) {
    /**
     * Власний гаманець телефона: адреса base58, мережа (`devnet`/`mainnet`) і чи він
     * вбудований (лише вбудований підписує речення для балів).
     */
    data class Wallet(val address: String, val cluster: String, val builtIn: Boolean = true)

    /** Слово → рядок у strings.xml. «Я на місці» без місця має СВОЄ речення, не закладів. */
    enum class Word(val res: Int) {
        NO_FIX(R.string.msgr_scene_checkin_no_fix),
        BAD_BODY(R.string.msgr_scene_bad_body),
        NO_WALLET(R.string.msgr_scene_no_wallet),
        NO_KEY(R.string.msgr_scene_no_key),
        NOT_ENABLED(R.string.msgr_scene_not_enabled),
        UNREACHABLE(R.string.msgr_scene_unreachable),
        LIMITED(R.string.msgr_scene_limited),
        REFUSED(R.string.msgr_scene_refused),
        BAD_ANSWER(R.string.msgr_scene_bad_answer),
        NOT_FOUND(R.string.msgr_scene_not_found),
        NOT_DEVNET(R.string.msgr_scene_points_test_only),
        BUILT_IN_ONLY(R.string.msgr_scene_points_built_in),
    }

    suspend fun handle(method: String, seg: List<String>, body: String?): String = when {
        seg.size == 3 && seg[2] == "checkin" && method == "POST" -> checkin(body)
        seg.size == 3 && seg[2] == "payment" && method == "POST" -> payment(body)
        seg.size == 3 && seg[2] == "me" && method == "GET" -> answer(api.me())
        else -> fail(404, "not_found", words(Word.NOT_FOUND))
    }

    private suspend fun checkin(body: String?): String {
        val o = objectOf(body) ?: return fail(422, "bad_body", words(Word.BAD_BODY))
        val meetup = text(o, "meetup_id") ?: return fail(422, "bad_body", words(Word.BAD_BODY))
        val venue = text(o, "venue_id") ?: return fail(422, "bad_body", words(Word.BAD_BODY))
        val at = whole(o, "at_ms") ?: return fail(422, "bad_body", words(Word.BAD_BODY))
        val cell = when (val h = here()) {
            WebMessengerVenues.Here.None -> return fail(409, "no_fix", words(Word.NO_FIX))
            is WebMessengerVenues.Here.Refused -> return fail(409, "no_fix", if (english()) h.en else h.uk)
            is WebMessengerVenues.Here.Point -> H3.latLngToCell(h.lat, h.lon, H3.CELL_RES)
        }
        return answer(api.checkin(meetup, venue, cell, at))
    }

    private fun payment(body: String?): String {
        val o = objectOf(body) ?: return fail(422, "bad_body", words(Word.BAD_BODY))
        val signature = text(o, "signature") ?: return fail(422, "bad_body", words(Word.BAD_BODY))
        val ref = text(o, "ref") ?: return fail(422, "bad_body", words(Word.BAD_BODY))
        if (!PaymentRef.matches(ref)) return fail(422, "bad_body", words(Word.BAD_BODY))
        val mine = wallet() ?: return fail(409, "no_wallet", words(Word.NO_WALLET))
        // Остаточні «ні» — 422: веб після них не повторює (див. claimPaidShareOnce).
        if (mine.cluster != "devnet") return fail(422, "not_devnet", words(Word.NOT_DEVNET))
        if (!mine.builtIn) return fail(422, "built_in_only", words(Word.BUILT_IN_ONLY))
        return when (val r = claims.offer(PointsClaims.Claim(mine.cluster, signature, mine.address, ref))) {
            PointsClaims.Offered.Waiting ->
                WebMessengerReply.ok(buildJsonObject { put("status", "pending"); put("awaiting", "confirm") })
            PointsClaims.Offered.AlreadyCounted ->
                WebMessengerReply.ok(buildJsonObject { put("status", "confirmed"); put("awarded", JsonArray(emptyList())) })
            is PointsClaims.Offered.Refused ->
                fail(422, r.reason ?: "refused", (if (english()) r.en else r.uk) ?: words(Word.REFUSED))
        }
    }

    private fun answer(a: VenueClient.Answer): String = when (a) {
        is VenueClient.Answer.Ok -> {
            val data = runCatching { Json.parseToJsonElement(a.json) }.getOrNull()
            if (data is JsonObject) WebMessengerReply.ok(data) else fail(502, "bad_answer", words(Word.BAD_ANSWER))
        }
        is VenueClient.Answer.Refused ->
            if (a.status == 429) fail(429, "limited", words(Word.LIMITED))
            // `not_at_venue`, `outside_window`, `wallet_taken` — веб розрізняє їх
            // по коду: «ти не там», «зустріч уже скінчилась», «гаманець чужий».
            else fail(a.status, a.reason ?: "refused", (if (english()) a.en else a.uk) ?: words(Word.REFUSED))
        VenueClient.Answer.Missing, VenueClient.Answer.BadInput -> fail(422, "bad_body", words(Word.BAD_BODY))
        VenueClient.Answer.NoKey -> fail(409, "no_key", words(Word.NO_KEY))
        VenueClient.Answer.NotEnabled -> fail(503, "not_enabled", words(Word.NOT_ENABLED))
        VenueClient.Answer.Unreachable -> fail(502, "unreachable", words(Word.UNREACHABLE))
    }

    private fun fail(status: Int, code: String, detail: String) = WebMessengerReply.fail(status, code, detail)

    private fun objectOf(body: String?): JsonObject? =
        runCatching { Json.parseToJsonElement(body.orEmpty()) }.getOrNull() as? JsonObject

    private fun text(o: JsonObject, key: String): String? =
        (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

    /** Ціле число JSON (не рядок, не дріб). */
    private fun whole(o: JsonObject, key: String): Long? =
        (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()?.takeIf { it >= 0 }

    companion object {
        /** id рахунку, який сервер приймає в заяві (`bill.id`): uuid. */
        private val PaymentRef = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        /** Шлях балів: `/messenger/points/…`. */
        fun claims(seg: List<String>): Boolean = seg.size >= 3 && seg[0] == "messenger" && seg[1] == "points"
    }
}
