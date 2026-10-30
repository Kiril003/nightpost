package local.phantom.companion.core.net.intent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import local.phantom.companion.core.net.peer.PeerIntent
import local.phantom.companion.core.net.siren.SirenKeys
import local.phantom.companion.core.net.siren.SirenSnapshot
import java.util.Base64

/**
 * Журнал «Мої прохання» для вебу — рівно та форма, яку читає
 * `phantom-os/src/frontend/src/services/intentsApi.ts` (IntentRow).
 *
 * Відповідь розібрано тут, бо лише телефон знає op і ключ: тривоги — області
 * й вік даних, «хто на звʼязку» — хвилини, текст — як є. Вебу лишається
 * сказати це словами, нічого не вигадуючи.
 */
object IntentView {

    @Serializable
    data class Try(val lane: String, @SerialName("at_ms") val atMs: Long)

    @Serializable
    data class Alerts(
        val oblasts: List<String>,
        val mine: Boolean,
        @SerialName("since_min") val sinceMin: Int?,
        @SerialName("age_s") val ageS: Int,
    )

    @Serializable
    data class Presence(
        @SerialName("minutes_ago") val minutesAgo: Int?,
        val lane: Int,
        @SerialName("my_last_state") val myLastState: Int?,
    )

    @Serializable
    data class Siren(
        /** Підпис закріпленого ключа зійшовся. Ні — знімок не показують зовсім. */
        val verified: Boolean,
        val fresh: Boolean = false,
        @SerialName("age_min") val ageMin: Long? = null,
        val oblasts: List<String> = emptyList(),
        @SerialName("observed_at") val observedAt: Long? = null,
    )

    @Serializable
    data class Answer(
        val rung: Int,
        val status: Int,
        val lane: String,
        @SerialName("at_ms") val atMs: Long,
        val text: String? = null,
        val alerts: Alerts? = null,
        val presence: Presence? = null,
        val siren: Siren? = null,
    )

    @Serializable
    data class Row(
        val id: String,
        val op: String,
        val title: String,
        @SerialName("created_ms") val createdMs: Long,
        @SerialName("deadline_ms") val deadlineMs: Long,
        val phase: String,
        val tries: List<Try>,
        @SerialName("relay_allowed") val relayAllowed: Boolean,
        val answer: Answer?,
    )

    @Serializable
    data class Page(val intents: List<Row>)

    private val JSON = Json { encodeDefaults = true; explicitNulls = true }

    fun opName(op: Int): String = when (op) {
        PeerIntent.OP_ALERTS -> "alerts"
        PeerIntent.OP_WHO_ONLINE -> "who_online"
        PeerIntent.OP_ASK -> "ask"
        PeerIntent.OP_PAGE_GIST -> "page_gist"
        PeerIntent.OP_SIREN -> "siren"
        else -> "op_$op"
    }

    fun row(
        r: IntentRecord,
        keys: Map<Int, ByteArray> = SirenKeys.PINNED,
        nowS: Long = System.currentTimeMillis() / 1000,
    ): Row = Row(
        id = r.idHex,
        op = opName(r.op),
        title = r.title,
        createdMs = r.createdMs,
        deadlineMs = r.deadlineMs,
        phase = r.phase.name,
        tries = r.tries.map { Try(it.lane, it.atMs) },
        relayAllowed = r.replyTo and PeerIntent.Lane.RELAY != 0,
        answer = r.answer?.let { answer(r.op, it, keys, nowS) },
    )

    private fun answer(op: Int, a: AnswerRecord, keys: Map<Int, ByteArray>, nowS: Long): Answer {
        val body = runCatching { Base64.getDecoder().decode(a.bodyB64) }.getOrDefault(ByteArray(0))
        val base = Answer(a.rung, a.status, a.lane, a.atMs)
        if (a.status != PeerIntent.Status.OK) return base
        return when (op) {
            PeerIntent.OP_ALERTS -> PeerIntent.decodeAlerts(body)?.let {
                base.copy(alerts = Alerts(it.oblasts.toList().sortedBy(PeerIntent.OBLAST_ORDER::indexOf), it.myType != 0, it.sinceMin, it.ageS))
            } ?: base
            PeerIntent.OP_WHO_ONLINE -> PeerIntent.decodePresence(body)?.let {
                base.copy(presence = Presence(it.minutesAgo, it.lane, it.myLastState))
            } ?: base
            PeerIntent.OP_SIREN -> base.copy(siren = siren(body, keys, nowS))
            else -> base.copy(text = String(body, Charsets.UTF_8))
        }
    }

    fun siren(raw: ByteArray, keys: Map<Int, ByteArray>, nowS: Long): Siren {
        val snap = SirenSnapshot.verify(raw, keys, nowS) ?: return Siren(verified = false)
        return Siren(
            verified = true,
            fresh = snap.fresh(nowS),
            ageMin = snap.ageMinutes(nowS),
            oblasts = snap.alerts.keys.toList(),
            observedAt = snap.observedAt,
        )
    }

    fun page(
        records: List<IntentRecord>,
        keys: Map<Int, ByteArray> = SirenKeys.PINNED,
        nowS: Long = System.currentTimeMillis() / 1000,
    ): String = JSON.encodeToString(Page(records.map { row(it, keys, nowS) }))
}
