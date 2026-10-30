package local.phantom.companion.self

import android.content.Context
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.getAndUpdate
import local.phantom.companion.R
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.AIRouter
import local.phantom.companion.core.data.locale.ReplyLanguage
import local.phantom.companion.core.data.memory.MemoryFactStore
import local.phantom.companion.ui.messenger.PhantomSelfRoute

object PhantomSelfAndroid {

    fun buildCommitments(
        context: Context,
        store: CommitmentStore,
        peers: local.phantom.companion.data.PeerRosterStore,
    ): CommitmentService {
        val app = context.applicationContext
        return CommitmentService(
            store = store,
            roster = {
                peers.peers.value
                    .filter { it.kind == local.phantom.companion.core.net.peer.PeerIdentity.Kind.PERSON }
                    .map { p ->
                        CommitmentResolver.RosterPerson(
                            id = p.id,
                            name = p.name,
                            circle = peers.circleOf(p.id),
                            lastSeenMs = p.lastSeenMs,
                        )
                    }
            },
            onScheduled = { dueMs -> PhantomVisits.arm(app, dueMs) },
            appContext = app,
        )
    }

    fun build(
        context: Context,
        facts: MemoryFactStore,
        ledger: local.phantom.companion.core.memory.Ledger? = null,
        onChange: () -> Unit = {},
        commitments: CommitmentService? = null,
        reminders: ReminderSink? = null,
        router: () -> AIRouter,
    ): PhantomSelf {
        val app = context.applicationContext
        // Мова відповіді — мова месенджера (ReplyLanguage), а не системи; береться
        // щоразу: людина може змінити мову Nightpost, не перезапускаючи процес.
        val render: (Say) -> String = { say -> words(ReplyLanguage.context(app), say) }
        val hasBrain: suspend () -> Boolean = {
            runCatching { router().availableRoutes().isNotEmpty() }.getOrDefault(false)
        }
        val memory = SelfMemory(facts, ledger, onChange)
        val tutor = CSharpTutor(
            state = PrefsTutorState(app),
            memory = memory,
            skillText = { topic, ok ->
                render(Say(if (ok) Line.SKILL_OK else Line.SKILL_SHAKY, topic.name))
            },
            reminders = object : CSharpTutor.Reminders {
                override suspend fun set(text: Say, why: Say, atMs: Long): String =
                    PhantomVisits.offerLesson(app, render(text), render(why), atMs)
                override suspend fun cancel(id: String) = PhantomVisits.withdrawLesson(app)
            },
            secondLook = { topic, code -> if (hasBrain()) secondLook(router(), render(Say(topic.task)), code) else null },
        )
        // Без моделі «де укриття» бере інструмент на телефоні (BrainChain → правила),
        // тож памʼять тут не відповідає «розуму немає» замість нього.
        val rules = local.phantom.companion.core.ai.route.OnDeviceToolRules()
        return PhantomSelf(
            memory = memory,
            tutor = tutor,
            hasBrain = hasBrain,
            render = render,
            initiative = PhantomVisits.initiative(app, commitments?.store),
            toolAsk = rules::recognizes,
            phoneAsk = rules::asksPhone,
            commitments = commitments,
            reminders = reminders,
            english = {
                ReplyLanguage.locale(ReplyLanguage.picked(app)).language == java.util.Locale.ENGLISH.language
            },
        )
    }

    private suspend fun secondLook(router: AIRouter, task: String, code: String): String? = once(
        router,
        prompt = "Задача: $task\n\nКод учня:\n$code",
        system = "Ти вчитель C#. Одним коротким реченням українською скажи, чи розвʼязує код задачу " +
            "і що саме виправити, якщо ні. Без коду, без вступу.",
        maxTokens = 120,
    )?.take(400)

    /** Разове питання до розуму без журналу розмови; null — думати нема чим або не вийшло. */
    suspend fun once(router: AIRouter, prompt: String, system: String?, maxTokens: Int = 700): String? {
        val intent = AIIntent.Text(prompt = prompt, systemPrompt = system, maxTokens = maxTokens, temperature = 0.2f)
        val sb = StringBuilder()
        var final: String? = null
        var failed = false
        router.complete(intent).collect { chunk ->
            when (chunk) {
                is AIChunk.Token -> sb.append(chunk.text)
                is AIChunk.Final -> final = chunk.text
                is AIChunk.ErrorChunk -> failed = true
                else -> Unit
            }
        }
        if (failed) return null
        return (final?.takeIf { it.isNotBlank() } ?: sb.toString()).trim().ifBlank { null }
    }

    fun route(context: Context, self: () -> PhantomSelf, router: () -> AIRouter): PhantomSelfRoute {
        val app = context.applicationContext
        return PhantomSelfRoute(
            self = self,
            label = { words(ReplyLanguage.context(app), Say(PhantomSelf.fromLine(it))) },
            ask = { prompt, system ->
                val r = router()
                if (runCatching { r.availableRoutes().isNotEmpty() }.getOrDefault(false)) runCatching { once(r, prompt, system) }.getOrNull() else null
            },
            noMind = { ReplyLanguage.context(app).getString(R.string.self_ask_no_mind) },
            takeOpen = { PhantomVisits.openChat.getAndUpdate { null } },
            voice = { want ->
                val v = local.phantom.companion.PhantomApp.container().phantomVoice
                when (want) {
                    true -> when {
                        androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO) !=
                            android.content.pm.PackageManager.PERMISSION_GRANTED -> false to app.getString(R.string.voice_no_permission)
                        v.start() -> true to ""
                        else -> false to app.getString(R.string.voice_mic_off)
                    }
                    false -> { v.stop(); false to "" }
                    null -> v.on.value to ""
                }
            },
            memoryState = { runCatching { local.phantom.companion.PhantomApp.container().memoryRuntime.state() }.getOrNull() },
            isOwnerMessage = { chatId, messageId ->
                runCatching {
                    val mesh = local.phantom.companion.PhantomApp.container().mesh
                    val me = mesh.selfIdFlow.value
                    me.isNotBlank() && (
                        mesh.conversation(chatId).any { it.id == messageId && it.fromId == me } ||
                            local.phantom.companion.ui.messenger.WebMessengerGroups
                                .feed(mesh.outbox.value, chatId)
                                .any { it.id == messageId && it.fromId == me }
                        )
                }.getOrDefault(false)
            },
        )
    }

    fun words(context: Context, say: Say): String {
        if (say.line == Line.RAW) return say.args.joinToString("")
        val args = say.args.map { a -> if (a is Line) words(context, Say(a)) else a }.toTypedArray()
        return context.getString(res(say.line), *args)
    }

    private fun res(line: Line): Int = when (line) {
        Line.RAW -> error("raw")
        Line.REMEMBERED -> R.string.self_remembered
        Line.HEARD_NO_BRAIN -> R.string.self_heard_no_brain
        Line.FORGOT -> R.string.self_forgot
        Line.FORGOT_NOTHING -> R.string.self_forgot_nothing
        Line.FORGOT_ALL -> R.string.self_forgot_all
        Line.FORGOT_ALL_EMPTY -> R.string.self_forgot_all_empty
        Line.KNOWS_NOTHING -> R.string.self_knows_nothing
        Line.KNOWS_HEADER -> R.string.self_knows_header
        Line.KNOWS_FOOTER -> R.string.self_knows_footer
        Line.KNOWS_OLD -> R.string.self_knows_old
        Line.NO_BRAIN -> R.string.self_no_brain
        Line.FROM_SELF -> R.string.self_from_self
        Line.FROM_HEARD -> R.string.self_from_heard
        Line.FROM_GUESS -> R.string.self_from_guess
        Line.TUTOR_HELLO -> R.string.tutor_hello
        Line.TUTOR_Q_VARS -> R.string.tutor_q_vars
        Line.TUTOR_Q_LOOPS -> R.string.tutor_q_loops
        Line.TUTOR_RIGHT -> R.string.tutor_right
        Line.TUTOR_WRONG -> R.string.tutor_wrong
        Line.TUTOR_TASK_INTRO -> R.string.tutor_task_intro
        Line.TUTOR_SEND_CODE -> R.string.tutor_send_code
        Line.TASK_VARS -> R.string.task_vars
        Line.TASK_COND -> R.string.task_cond
        Line.TASK_LOOPS -> R.string.task_loops
        Line.TASK_METHODS -> R.string.task_methods
        Line.TASK_ARRAYS -> R.string.task_arrays
        Line.TUTOR_PASS -> R.string.tutor_pass
        Line.TUTOR_FAIL -> R.string.tutor_fail
        Line.TUTOR_RETRY -> R.string.tutor_retry
        Line.TUTOR_READ_NOT_RUN -> R.string.tutor_read_not_run
        Line.TUTOR_SECOND_LOOK -> R.string.tutor_second_look
        Line.TUTOR_TOMORROW -> R.string.tutor_tomorrow
        Line.TUTOR_MORE -> R.string.tutor_more
        Line.TUTOR_REVIEW -> R.string.tutor_review
        Line.TUTOR_REVIEW_RIGHT -> R.string.tutor_review_right
        Line.TUTOR_REVIEW_WRONG -> R.string.tutor_review_wrong
        Line.TUTOR_NOTHING_TO_REVIEW -> R.string.tutor_nothing_to_review
        Line.TUTOR_STOP -> R.string.tutor_stop
        Line.TUTOR_REMINDER -> R.string.tutor_reminder
        Line.VISIT_WHY_LESSON -> R.string.visit_why_lesson
        Line.VISIT_WHY_REMINDER -> R.string.visit_why_reminder
        Line.VISIT_NOT_NOW -> R.string.visit_not_now
        Line.VISIT_DONT -> R.string.visit_dont
        Line.VISIT_TITLE -> R.string.visit_title
        Line.ISSUE_BRACES -> R.string.tutor_issue_braces
        Line.ISSUE_PARENS -> R.string.tutor_issue_parens
        Line.ISSUE_SEMICOLON -> R.string.tutor_issue_semicolon
        Line.ISSUE_QUOTES -> R.string.tutor_issue_quotes
        Line.ISSUE_NEED -> R.string.tutor_issue_need
        Line.TOPIC_VARS -> R.string.tutor_topic_vars
        Line.TOPIC_COND -> R.string.tutor_topic_cond
        Line.TOPIC_LOOPS -> R.string.tutor_topic_loops
        Line.TOPIC_METHODS -> R.string.tutor_topic_methods
        Line.TOPIC_ARRAYS -> R.string.tutor_topic_arrays
        Line.SKILL_OK -> R.string.tutor_skill_ok
        Line.SKILL_SHAKY -> R.string.tutor_skill_shaky
        Line.COMMITMENT_SAVED -> R.string.commitment_saved
        Line.COMMITMENT_SAVED_DUE -> R.string.commitment_saved_due
        Line.COMMITMENT_CHOOSE -> R.string.commitment_choose
        Line.COMMITMENT_NONE -> R.string.commitment_none
        Line.COMMITMENT_LIST_HEADER -> R.string.commitment_list_header
        Line.COMMITMENT_MARKED_DONE -> R.string.commitment_marked_done
        Line.COMMITMENT_DONE_WHICH -> R.string.commitment_done_which
        Line.WHEN_TODAY -> R.string.commitment_when_today
        Line.WHEN_TOMORROW -> R.string.commitment_when_tomorrow
        Line.WHEN_DAY_AFTER_TOMORROW -> R.string.commitment_when_day_after
        Line.REMINDER_SET -> R.string.self_reminder_set
    }
}

/** Лише крок розмови й теми — жодного слова людини тут не лежить. */
private class PrefsTutorState(context: Context) : CSharpTutor.TutorState {
    private val prefs = context.getSharedPreferences("phantom_self_tutor", Context.MODE_PRIVATE)

    override var step: CSharpTutor.Step
        get() = runCatching { CSharpTutor.Step.valueOf(prefs.getString("step", null) ?: "IDLE") }
            .getOrDefault(CSharpTutor.Step.IDLE)
        set(v) = prefs.edit().putString("step", v.name).apply()

    override var diagIndex: Int
        get() = prefs.getInt("diag", 0)
        set(v) = prefs.edit().putInt("diag", v).apply()

    override var topic: String?
        get() = prefs.getString("topic", null)
        set(v) = prefs.edit().putString("topic", v).apply()

    override var reminderId: String?
        get() = prefs.getString("reminder", null)
        set(v) = prefs.edit().putString("reminder", v).apply()

    override var diagnosed: Boolean
        get() = prefs.getBoolean("diagnosed", false)
        set(v) = prefs.edit().putBoolean("diagnosed", v).apply()

    override fun shaky(): Map<String, Long> =
        CSharpCourse.TOPICS.mapNotNull { t ->
            if (prefs.contains("t.${t.id}")) t.id to prefs.getLong("t.${t.id}", 0L) else null
        }.toMap()

    override fun mark(topic: String, shakyAtMs: Long?) {
        prefs.edit().putLong("t.$topic", shakyAtMs ?: 0L).apply()
    }
}
