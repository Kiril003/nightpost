package local.phantom.companion.self

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object ReminderSpeech {
    data class Ask(
        val text: String,
        val atMs: Long,
        val whenLine: Line? = null,
    )

    private const val MAX_TEXT_CHARS = 500

    private val APOSTROPHES = Regex("['’`´]")
    private val TRAIL_PUNCT = Regex("[\\s.!?…,;]+$")
    private val MULTI_SPACE = Regex("\\s{2,}")
    private val CLOCK_FMT = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    private fun norm(text: String): String = text.replace(APOSTROPHES, "ʼ").trim()
    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private data class TriggerSpec(val rx: Regex, val immediateTimeOnly: Boolean = false)

    private val TRIGGERS = listOf(
        TriggerSpec(rx("^(?:please\\s*,?\\s*)?remind\\s+me(?=\\s|$|[,:;])\\s*(.*)$")),
        TriggerSpec(rx("^(?:please\\s*,?\\s*)?set\\s+a\\s+reminder(?=\\s|$|[,:;])\\s*(.*)$")),
        TriggerSpec(rx("^(?:please\\s*,?\\s*)?reminder\\s*:\\s*(.*)$")),
        TriggerSpec(rx("^(?:будь\\s+ласка\\s*,?\\s*)?нагадай\\s+мені(?=\\s|$|[,:;])\\s*(.*)$")),
        TriggerSpec(rx("^(?:будь\\s+ласка\\s*,?\\s*)?(?:постав|створи)(?:\\s+мені)?\\s+нагадування(?=\\s|$|[,:;])\\s*(.*)$")),
        TriggerSpec(rx("^(?:будь\\s+ласка\\s*,?\\s*)?нагадування\\s*:\\s*(.*)$")),
        TriggerSpec(rx("^(?:будь\\s+ласка\\s*,?\\s*)?нагадай(?=\\s|$|[,:;])\\s*(.*)$"), immediateTimeOnly = true),
    )

    private val LEADING_PUNCT_RX = rx("^[,:;]+\\s*")
    private val LEADING_CONNECTOR_RX = rx("^(?:" + "to|that|about|for" + "|" + "про|що|щоб" + ")(?=\\s|$)\\s*")

    fun match(
        text: String,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Ask? {
        val cleaned = norm(text)
        if (cleaned.isEmpty() || cleaned.endsWith("?")) return null

        for (spec in TRIGGERS) {
            val m = spec.rx.matchEntire(cleaned) ?: continue
            val afterTrigger = m.groupValues[1].trim()
            if (afterTrigger.isEmpty()) return null

            val hit = SpeechTime.parse(afterTrigger, nowMs, zone) ?: return null

            if (spec.immediateTimeOnly) {
                val cleanAfter = afterTrigger.replace(MULTI_SPACE, " ").trim()
                if (hit.remaining.isEmpty()) return null
                if (!cleanAfter.endsWith(hit.remaining)) return null
                val timePrefix = cleanAfter.removeSuffix(hit.remaining).trim()
                val prefixHit = SpeechTime.parse(timePrefix, nowMs, zone) ?: return null
                if (prefixHit.remaining.isNotEmpty()) return null
            }

            val reminderText = cleanReminderText(hit.remaining)
            if (reminderText.isEmpty()) return null

            return Ask(
                text = reminderText,
                atMs = hit.atMs,
                whenLine = hit.whenLine,
            )
        }
        return null
    }

    fun whenLabel(
        atMs: Long,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        english: Boolean = false,
    ): String {
        val word = CommitmentSpeech.whenWord(atMs, nowMs, zone, english)
        val clock = Instant.ofEpochMilli(atMs).atZone(zone).toLocalTime().format(CLOCK_FMT)
        return "$word $clock"
    }

    private fun cleanReminderText(raw: String): String {
        var s = raw.trim()
        while (s.isNotEmpty()) {
            val p = LEADING_PUNCT_RX.find(s)
            if (p != null) {
                s = s.substring(p.range.last + 1).trim()
                continue
            }
            val c = LEADING_CONNECTOR_RX.find(s)
            if (c != null) {
                s = s.substring(c.range.last + 1).trim()
                continue
            }
            break
        }
        s = s.replace(TRAIL_PUNCT, "").trim()
        if (s.isEmpty()) return ""
        val capped = s.take(MAX_TEXT_CHARS)
        return capped.substring(0, 1).uppercase() + capped.substring(1)
    }
}
