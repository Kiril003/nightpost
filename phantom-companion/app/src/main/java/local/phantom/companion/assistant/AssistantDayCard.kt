package local.phantom.companion.assistant

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.net.peer.PeerCard

/**
 * «Що в мене сьогодні?» — картка Дня одразу, без моделі й без ліміту.
 * Лише питання саме про сьогодні; «а ввечері?» чи «завтра» лишаються розуму з інструментами.
 */
object AssistantDayCard {

    fun asks(prompt: String): Boolean {
        val q = prompt.lowercase()
            .replace(APOSTROPHES, "'")
            .replace(ASSISTANT_NAME_LEAD, "")
            .replace(TAIL, "")
            .replace(SPACES, " ")
            .trim()
        return q.isNotEmpty() && ASKS.any { it.matches(q) }
    }

    fun body(nowMs: Long, zone: ZoneId): String {
        val payload = buildJsonObject {
            put("v", 1)
            put("day", Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toString())
            put("at", nowMs)
        }.toString()
        return requireNotNull(PeerCard.encode(PeerCard.Body(PeerCard.Card.DAY, payload)))
    }

    private val APOSTROPHES = Regex("[’ʼ`´‘]")
    private val SPACES = Regex("\\s+")
    private val TAIL = Regex("[?!.…\\s]+$")

    private val ASKS = listOf(
        Regex("^(?:а\\s+)?(?:що|які|яка)\\s+(?:(?:в|у)\\s+мене\\s+)?(?:плани?\\s+|справи\\s+|розклад\\s+)?(?:на\\s+)?сьогодні$"),
        Regex("^(?:плани?|справи|розклад)\\s+на\\s+сьогодні$"),
        Regex("^(?:мій|моя)\\s+(?:день|сьогодні)$"),
        Regex("^(?:what's|what is)\\s+(?:on\\s+)?(?:my\\s+)?(?:day|today|plan for today|agenda for today)$"),
        Regex("^what\\s+(?:do|have)\\s+i\\s+(?:got\\s+|have\\s+)?(?:on\\s+)?today$"),
        Regex("^(?:my\\s+day|today's\\s+plan|my\\s+plans?\\s+(?:for\\s+)?today)$"),
        Regex("^(?:co\\s+mám\\s+(?:na\\s+)?(?:dnes|dnešek)|můj\\s+den|plán\\s+na\\s+dnes)$"),
    )
}
