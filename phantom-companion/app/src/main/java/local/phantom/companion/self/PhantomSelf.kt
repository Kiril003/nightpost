package local.phantom.companion.self

/**
 * Те, що PHANTOM робить сам, до будь-якої моделі: памʼятає людину, забуває на
 * прохання, вчить C#. Працює й тоді, коли думати нема чим.
 */
fun interface ReminderSink {
    suspend fun add(text: String, atMs: Long): String
}

class PhantomSelf(
    val memory: SelfMemory,
    val tutor: CSharpTutor,
    private val hasBrain: suspend () -> Boolean,
    private val render: (Say) -> String,
    private val initiative: Initiative? = null,
    /**
     * Чи візьмуть репліку правила інструментів на телефоні (укриття, маршрут…).
     * Тоді «розуму немає» — неправда: відповідь дасть інструмент, а не модель.
     */
    private val toolAsk: (String) -> Boolean = { false },
    /** Питання про сам телефон (додатки, календар): памʼять тут не свідок. */
    private val phoneAsk: (String) -> Boolean = toolAsk,
    val commitments: CommitmentService? = null,
    private val reminders: ReminderSink? = null,
    private val english: () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** Готова відповідь PHANTOM або null — тоді слово за моделлю. */
    suspend fun answer(text: String): String? {
        val said = hear(text) ?: return null
        return said.joinToString("\n\n") { render(it) }
    }

    suspend fun hear(text: String): List<Say>? {
        visitReply(text.trim())?.let { return it }
        initiative?.answered()
        val zone = commitments?.zoneId() ?: java.time.ZoneId.systemDefault()
        reminders?.let { sink ->
            ReminderSpeech.match(text, now(), zone)?.let { ask ->
                val id = runCatching { sink.add(ask.text, ask.atMs) }.getOrDefault("")
                if (id.isNotEmpty()) {
                    return listOf(
                        Say(
                            Line.REMINDER_SET,
                            ask.text,
                            ReminderSpeech.whenLabel(ask.atMs, now(), zone, english()),
                        ),
                    )
                }
            }
        }
        if (tutor.starts(text)) initiative?.unmute(Initiative.Kind.LESSON)
        tutor.hear(text)?.let { return it }
        val rosterNames = commitments?.rosterNames().orEmpty()
        return when (val heard = SelfSpeech.read(text, now(), zone, rosterNames)) {
            is SelfSpeech.Heard.WhatDoYouKnow -> whatIKnow()
            SelfSpeech.Heard.WhatDidIPromise -> whatIPromised()
            SelfSpeech.Heard.CommitmentDone -> when (val res = commitments?.doneDue(now()) ?: CommitmentResolver.DoneDue.None) {
                is CommitmentResolver.DoneDue.Done ->
                    listOf(Say(Line.COMMITMENT_MARKED_DONE, res.commitment.personName, res.commitment.what))
                is CommitmentResolver.DoneDue.Ambiguous -> {
                    val lines = res.candidates.joinToString("\n") { c ->
                        if (c.what.isBlank()) "• ${c.personName}" else "• ${c.personName}: ${c.what}"
                    }
                    listOf(Say(Line.COMMITMENT_DONE_WHICH), Say(Line.RAW, lines))
                }
                CommitmentResolver.DoneDue.None ->
                    if (hasBrain() || toolAsk(text)) null else listOf(Say(Line.NO_BRAIN))
            }
            is SelfSpeech.Heard.Commitment -> handleCommitment(text, heard.parsed)
            is SelfSpeech.Heard.Remember -> {
                memory.remember(heard.fact, SelfMemory.From.SELF)
                listOf(Say(Line.REMEMBERED, heard.fact.text))
            }
            is SelfSpeech.Heard.Forget -> {
                val gone = memory.forgetMatching(heard.query)
                listOf(if (gone == null) Say(Line.FORGOT_NOTHING, heard.query) else Say(Line.FORGOT, gone.text))
            }
            is SelfSpeech.Heard.ForgetAll -> {
                val n = memory.forgetAll()
                listOf(if (n == 0) Say(Line.FORGOT_ALL_EMPTY) else Say(Line.FORGOT_ALL))
            }
            is SelfSpeech.Heard.Facts -> {
                heard.facts.forEach { memory.remember(it, SelfMemory.From.HEARD) }
                if (hasBrain()) null else listOf(Say(Line.HEARD_NO_BRAIN, heard.facts.joinToString("; ") { it.text }))
            }
            // Питання про власника без опори в памʼяті — «не знаю» без моделі (як на ПК).
            SelfSpeech.Heard.Nothing -> (if (phoneAsk(text)) null else memory.dontKnow(text))?.let { listOf(Say(Line.RAW, it)) }
                ?: if (hasBrain() || toolAsk(text)) null else listOf(Say(Line.NO_BRAIN))
        }
    }

    private suspend fun whatIKnow(): List<Say> {
        val known = memory.known()
        if (known.isEmpty()) return listOf(Say(Line.KNOWS_NOTHING))
        val (old, fresh) = known.partition { it.old }
        val out = ArrayList<Say>()
        out += Say(Line.KNOWS_HEADER)
        if (fresh.isNotEmpty()) out += Say(Line.RAW, fresh.joinToString("\n") { "• ${it.text} — ${render(Say(fromLine(it.from)))}" })
        if (old.isNotEmpty()) {
            out += Say(Line.KNOWS_OLD)
            out += Say(Line.RAW, old.joinToString("\n") { "• ${it.text}" })
        }
        out += Say(Line.KNOWS_FOOTER)
        return out
    }

    private suspend fun handleCommitment(text: String, parsed: CommitmentSpeech.ParsedCommitment): List<Say>? {
        val svc = commitments ?: return if (hasBrain()) null else listOf(Say(Line.NO_BRAIN))
        return when (val res = svc.record(text, parsed)) {
            is CommitmentResolver.Resolution.Saved -> {
                val c = res.commitment
                if (parsed.whenLine != null) {
                    listOf(Say(Line.COMMITMENT_SAVED_DUE, c.personName, c.what, parsed.whenLine))
                } else {
                    listOf(Say(Line.COMMITMENT_SAVED, c.personName, c.what))
                }
            }
            is CommitmentResolver.Resolution.Ambiguous -> {
                listOf(Say(Line.COMMITMENT_CHOOSE, parsed.person, parsed.what))
            }
        }
    }

    private fun whatIPromised(): List<Say> {
        val open = commitments?.listOpen().orEmpty()
        if (open.isEmpty()) return listOf(Say(Line.COMMITMENT_NONE))
        val lines = open.joinToString("\n") { c -> "• ${c.personName}: ${c.what}" }
        return listOf(Say(Line.COMMITMENT_LIST_HEADER), Say(Line.RAW, lines))
    }

    private fun visitReply(t: String): List<Say>? {
        val ini = initiative ?: return null
        val came = ini.lastWaiting() ?: return null
        return when {
            NOT_NOW.any { it.containsMatchIn(t) } -> {
                ini.notNow(came, now())
                listOf(Say(Line.VISIT_NOT_NOW))
            }
            DONT.any { it.containsMatchIn(t) } -> {
                ini.dontDoThis(came.kind)
                listOf(Say(Line.VISIT_DONT))
            }
            else -> null
        }
    }

    suspend fun promptMemory(query: String = ""): String = memory.promptBlock(query)

    companion object {
        private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)
        private val NOT_NOW = listOf(rx("^(?:не\\s+зараз|пізніше|згодом)(?:\\s|$|[.!,])"), rx("^(?:not\\s+now|later)(?:\\s|$|[.!,])"))
        private val DONT = listOf(
            rx("^(?:так\\s+не\\s+роби|не\\s+треба\\s+так|не\\s+нагадуй|більше\\s+не\\s+приходь)(?:\\s|$|[.!,])"),
            rx("^(?:don'?t\\s+do\\s+(?:this|that))(?:\\s|$|[.!,])"),
        )

        fun fromLine(from: SelfMemory.From) = when (from) {
            SelfMemory.From.SELF -> Line.FROM_SELF
            SelfMemory.From.HEARD -> Line.FROM_HEARD
            SelfMemory.From.GUESS -> Line.FROM_GUESS
        }
    }
}
