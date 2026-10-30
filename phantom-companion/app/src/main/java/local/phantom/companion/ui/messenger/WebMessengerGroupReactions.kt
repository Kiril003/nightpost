package local.phantom.companion.ui.messenger

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import local.phantom.companion.R
import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerMark
import local.phantom.companion.core.net.peer.PeerMessage

/**
 * Смайл під рядком групи (`PH-REA:`). Ціль позначки — ключ віяра `автор_мить`
 * ([WebMessengerCardMarks.targetOf]), спільний для всіх копій одного сказаного рядка.
 */
internal class WebMessengerGroupReactions(
    private val log: () -> List<PeerMessage>,
    private val me: () -> String,
    private val keyed: () -> Set<String>,
    private val send: (to: String, frame: String) -> PeerMessage?,
) {
    enum class Why(val res: Int) {
        NO_IDENTITY(R.string.msgr_bridge_no_identity),
        NO_MESSAGE(R.string.msgr_bridge_no_message),
        NOT_IN_GROUP(R.string.msgr_group_gone),
        BAD_BODY(R.string.msgr_bridge_bad_body),
        EMPTY(R.string.msgr_bridge_empty_text),
        BAD_MARK(R.string.msgr_bridge_reaction_unreadable),
        NO_KEY(R.string.msgr_bridge_peer_no_key),
    }

    sealed interface Outcome {
        data class Done(val line: PeerMessage, val target: String, val on: Boolean, val missed: List<String>) : Outcome
        data class Refused(val status: Int, val code: String, val why: Why) : Outcome
    }

    fun handle(group: PeerGroup.Group, messageId: String, body: String?): Outcome {
        val log = log()
        val me = me()
        val o = body?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject
            ?: return Outcome.Refused(400, "bad_body", Why.BAD_BODY)
        val emoji = (o["emoji"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            ?: return Outcome.Refused(400, "empty", Why.EMPTY)
        if (me.isBlank()) return Outcome.Refused(503, "no_identity", Why.NO_IDENTITY)
        val copy = log.firstOrNull {
            it.id == messageId &&
                PeerGroup.decode(it.body)?.group?.id == group.id &&
                (it.fromId == me || it.toId == me)
        } ?: return Outcome.Refused(404, "no_message", Why.NO_MESSAGE)
        if (PeerGroup.standing(log, group, me) != PeerGroup.Standing.IN) {
            return Outcome.Refused(409, "not_in_group", Why.NOT_IN_GROUP)
        }
        val line = WebMessengerGroups.feed(log, group.id).firstOrNull {
            it.fromId == copy.fromId && it.sentAtMs == copy.sentAtMs && it.body == copy.body
        } ?: copy
        val target = WebMessengerCardMarks.targetOf(line)
        val recipients = group.memberIds.filter { it != me }.distinct()
        val keys = keyed()
        val (withKey, keyless) = recipients.partition { it in keys }
        if (withKey.isEmpty()) return Outcome.Refused(409, "no_key", Why.NO_KEY)
        val on = !PeerMark.holds(log, PeerMark.What.REACTION, target, emoji, me)
        val frame = PeerMark.encode(PeerMark.Mark(PeerMark.What.REACTION, target, on, emoji))
            ?: return Outcome.Refused(422, "bad_mark", Why.BAD_MARK)
        val sentTo = withKey.filter { send(it, frame) != null }
        if (sentTo.isEmpty()) return Outcome.Refused(503, "no_identity", Why.NO_IDENTITY)
        return Outcome.Done(line, target, on, missed = keyless + withKey.filter { it !in sentTo })
    }

    companion object {
        fun of(byEmoji: Map<String, List<String>>, members: Collection<String>): Map<String, List<String>> =
            byEmoji.mapValues { (_, a) -> a.filter { it in members } }.filterValues { it.isNotEmpty() }

        fun onLine(
            folded: Map<String, Map<String, List<String>>>,
            line: PeerMessage,
            members: Collection<String>,
        ): Map<String, List<String>> = of(folded[WebMessengerCardMarks.targetOf(line)].orEmpty(), members)
    }
}
