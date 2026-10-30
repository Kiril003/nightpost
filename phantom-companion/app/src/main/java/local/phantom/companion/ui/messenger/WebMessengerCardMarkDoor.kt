package local.phantom.companion.ui.messenger

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import local.phantom.companion.core.net.peer.PeerMessage

/**
 * `POST /messenger/messages/{id}/marks {value, on}` — дотик по картці зустрічі чи
 * рахунку (`docs/scene/MEETUP_CONTRACT.md` §2): позначка `PH-CST:` у дріт.
 *
 * Кадр складає МІСТ із закритого словника ([WebMessengerCardMarks.parse]), готове
 * тіло з веба не береться ніколи. Їде лише тим, хто в розмові, має ключ І сам
 * повідомив у профілі, що вміє позначки сцени (збірка 18): старій збірці кадр
 * показався б сирим дротом і сповіщенням на кожен дотик. Тим, кому не поїхав
 * (без ключа, стара збірка, відправка не вдалась), відповідь називає їх у
 * `missed` — тиха розбіжність часток рахунку гірша за видиму.
 *
 * Клас без Android: журнал, ключі й відправка приходять замками, тож дорогу від
 * тіла запиту до JSON відповіді доводить звичайний JVM-тест на фікстурі журналу,
 * а не пошук рядків у вихідному коді.
 */
class WebMessengerCardMarkDoor(
    private val log: () -> List<PeerMessage>,
    private val me: () -> String,
    /** Id контактів, до яких є ключ шифрування. */
    private val keyed: () -> Set<String>,
    /** Id контактів, що самі повідомили про підтримку позначок сцени. */
    private val scene: () -> Set<String>,
    /** Покласти кадр до цієї людини; `null` — відправка не відбулась. */
    private val send: (to: String, frame: String) -> PeerMessage?,
    private val words: (Int) -> String,
) {
    fun handle(messageId: String, body: String?): String {
        val ask = when (val p = WebMessengerCardMarks.parse(body)) {
            is WebMessengerCardMarks.Parsed.Ok -> p.ask
            is WebMessengerCardMarks.Parsed.Bad -> return fail(422, p.code, words(WebMessengerCardMarks.Why.BAD_MARK.res))
        }
        val plan = WebMessengerCardMarks.plan(log(), me(), messageId, ask, keyed(), scene())
        val go = when (plan) {
            is WebMessengerCardMarks.Plan.Refuse -> return fail(plan.status, plan.code, words(plan.why.res))
            is WebMessengerCardMarks.Plan.Send -> plan
        }
        val sentTo = go.to.filter { send(it, go.frame) != null }
        if (sentTo.isEmpty()) {
            val why = WebMessengerCardMarks.Why.SEND_FAILED
            return fail(503, "send_failed", words(why.res))
        }
        val marks = WebMessengerCardMarks.of(WebMessengerCardMarks.fold(log()), go.target, go.members)
        val missed = WebMessengerCardMarks.missed(go, sentTo)
        return WebMessengerReply.ok(
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(messageId),
                    "card_marks" to WebMessengerCardMarks.json(marks),
                    "missed" to JsonArray(missed.map(::JsonPrimitive)),
                ),
            ),
        )
    }

    private fun fail(status: Int, code: String, detail: String) = WebMessengerReply.fail(status, code, detail)
}
