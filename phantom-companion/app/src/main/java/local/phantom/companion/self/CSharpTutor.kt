package local.phantom.companion.self

/**
 * Вчитель C#: два питання, щоб зрозуміти рівень, потім маленька задача; що
 * виходить і що хитається, лягає в памʼять, а хитке повертається через день.
 */
class CSharpTutor(
    private val state: TutorState,
    private val memory: SelfMemory,
    private val skillText: (CSharpCourse.Topic, Boolean) -> String,
    private val reminders: Reminders,
    private val secondLook: suspend (task: CSharpCourse.Topic, code: String) -> String? = { _, _ -> null },
    private val now: () -> Long = System::currentTimeMillis,
) {

    enum class Step { IDLE, DIAG, TASK, REVIEW }

    interface TutorState {
        var step: Step
        var diagIndex: Int
        var topic: String?
        var reminderId: String?
        /** id теми → час, коли її востаннє визнано хиткою; 0 — виходить. */
        fun shaky(): Map<String, Long>
        fun mark(topic: String, shakyAtMs: Long?)
        var diagnosed: Boolean
    }

    interface Reminders {
        suspend fun set(text: Say, why: Say, atMs: Long): String
        suspend fun cancel(id: String)
    }

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    private val start = listOf(
        rx("(?:вчи|навчи|навчай|повчи|вчити|вивчати|вчитися)\\s.*(?:c#|с#|сі\\s*шарп|си\\s*шарп)"),
        rx("teach\\s.*(?:c#|c\\s*sharp)"),
    )
    private val review = listOf(rx("^(?:давай\\s+)?повторимо(?:\\s|$|[.!?])"), rx("^let'?s\\s+review(?:\\s|$)"))
    private val stop = listOf(
        rx("^(?:стоп|досить|вистачить|потім|пізніше)(?:\\s|$|[.!,])"),
        rx("^(?:stop|later)(?:\\s|$|[.!,])"),
    )

    fun starts(text: String): Boolean = start.any { it.containsMatchIn(text.trim()) }

    fun active(): Boolean = state.step != Step.IDLE

    /** Відповідь учителя або null, коли слова людини не до нього. */
    suspend fun hear(text: String): List<Say>? {
        val t = text.trim()
        if (start.any { it.containsMatchIn(t) }) return begin()
        if (state.diagnosed && review.any { it.containsMatchIn(t) }) return reviewNext() ?: listOf(Say(Line.TUTOR_NOTHING_TO_REVIEW))
        if (state.step == Step.IDLE) return null
        if (stop.any { it.containsMatchIn(t) }) {
            state.step = Step.IDLE
            return listOf(Say(Line.TUTOR_STOP))
        }
        return when (state.step) {
            Step.DIAG -> diagAnswer(t)
            Step.TASK -> taskAnswer(t)
            Step.REVIEW -> reviewAnswer(t)
            Step.IDLE -> null
        }
    }

    private suspend fun begin(): List<Say> {
        reviewNext()?.let { return it }
        if (!state.diagnosed) {
            state.step = Step.DIAG
            state.diagIndex = 0
            return listOf(Say(Line.TUTOR_HELLO)) + diagQuestion(0)
        }
        return giveTask(nextTopic())
    }

    private fun diagQuestion(i: Int): List<Say> {
        val (topic, q) = CSharpCourse.DIAGNOSTIC[i]
        val line = if (topic == CSharpCourse.VARS) Line.TUTOR_Q_VARS else Line.TUTOR_Q_LOOPS
        return listOf(Say(line, i + 1, q.code))
    }

    private suspend fun diagAnswer(t: String): List<Say> {
        val i = state.diagIndex.coerceIn(0, CSharpCourse.DIAGNOSTIC.lastIndex)
        val (topic, q) = CSharpCourse.DIAGNOSTIC[i]
        val right = CSharpCourse.answered(q.answer, t)
        mark(topic, right)
        val verdict = if (right) Say(Line.TUTOR_RIGHT) else Say(Line.TUTOR_WRONG, q.answer, q.explain)
        if (i + 1 < CSharpCourse.DIAGNOSTIC.size) {
            state.diagIndex = i + 1
            return listOf(verdict) + diagQuestion(i + 1)
        }
        state.diagnosed = true
        return listOf(verdict) + giveTask(nextTopic())
    }

    private fun nextTopic(): CSharpCourse.Topic {
        val shaky = state.shaky()
        CSharpCourse.TOPICS.firstOrNull { (shaky[it.id] ?: 0L) > 0L }?.let { return it }
        CSharpCourse.TOPICS.firstOrNull { it.id !in shaky }?.let { return it }
        val last = CSharpCourse.TOPICS.indexOfFirst { it.id == state.topic }
        return CSharpCourse.TOPICS[(last + 1).mod(CSharpCourse.TOPICS.size)]
    }

    private fun giveTask(topic: CSharpCourse.Topic): List<Say> {
        state.step = Step.TASK
        state.topic = topic.id
        return listOf(Say(Line.TUTOR_TASK_INTRO, topic.name), Say(topic.task), Say(Line.TUTOR_SEND_CODE))
    }

    private suspend fun taskAnswer(t: String): List<Say>? {
        val topic = CSharpCourse.topic(state.topic) ?: return null.also { state.step = Step.IDLE }
        if (!CSharpCourse.looksLikeCode(t)) {
            state.step = Step.IDLE
            return null
        }
        val issues = CSharpCourse.check(topic, t)
        val out = ArrayList<Say>()
        if (issues.isEmpty()) {
            mark(topic, true)
            state.step = Step.IDLE
            out += Say(Line.TUTOR_PASS)
        } else {
            mark(topic, false)
            out += Say(Line.TUTOR_FAIL)
            out += issues
            out += Say(Line.TUTOR_RETRY)
            out += Say(Line.TUTOR_TOMORROW, topic.name)
        }
        out += Say(Line.TUTOR_READ_NOT_RUN)
        runCatching { secondLook(topic, t) }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
            out += Say(Line.TUTOR_SECOND_LOOK, it.trim())
        }
        if (issues.isEmpty()) out += Say(Line.TUTOR_MORE)
        return out
    }

    private fun reviewNext(): List<Say>? {
        val shaky = state.shaky().filterValues { it > 0L }
        val id = shaky.minByOrNull { it.value }?.key ?: return null
        val topic = CSharpCourse.topic(id) ?: return null
        state.step = Step.REVIEW
        state.topic = topic.id
        return listOf(Say(Line.TUTOR_REVIEW, topic.name, topic.review.code))
    }

    private suspend fun reviewAnswer(t: String): List<Say> {
        val topic = CSharpCourse.topic(state.topic) ?: return listOf(Say(Line.TUTOR_STOP)).also { state.step = Step.IDLE }
        val right = CSharpCourse.answered(topic.review.answer, t)
        mark(topic, right)
        state.step = Step.IDLE
        return if (right) {
            listOf(Say(Line.TUTOR_REVIEW_RIGHT, topic.name), Say(Line.TUTOR_MORE))
        } else {
            listOf(
                Say(Line.TUTOR_REVIEW_WRONG, topic.review.answer, topic.review.explain),
                Say(Line.TUTOR_TOMORROW, topic.name),
            )
        }
    }

    private suspend fun mark(topic: CSharpCourse.Topic, ok: Boolean) {
        state.mark(topic.id, if (ok) null else now())
        memory.rememberSkill(topic.id, skillText(topic, ok))
        rearm()
    }

    /** Одне нагадування на все хитке: про найдавнішу хитку тему, через добу. */
    private suspend fun rearm() {
        state.reminderId?.let { runCatching { reminders.cancel(it) } }
        state.reminderId = null
        val oldest = state.shaky().filterValues { it > 0L }.minByOrNull { it.value } ?: return
        val topic = CSharpCourse.topic(oldest.key) ?: return
        state.reminderId = reminders.set(Say(Line.TUTOR_REMINDER, topic.name), Say(Line.VISIT_WHY_LESSON, topic.name), now() + DAY_MS)
    }

    companion object {
        const val DAY_MS = 86_400_000L
    }
}
