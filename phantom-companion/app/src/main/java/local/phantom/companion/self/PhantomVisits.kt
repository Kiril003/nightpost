package local.phantom.companion.self

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import local.phantom.companion.PhantomApp
import local.phantom.companion.R
import local.phantom.companion.service.NotificationLook
import local.phantom.companion.core.data.reminders.ReminderEntity
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.data.AppContainer
import local.phantom.companion.service.ReminderAlarms
import local.phantom.companion.ui.messenger.AgencySessionManager

/** Як прихід PHANTOM стає сповіщенням, листом у розмові і відповіддю людини. */
object PhantomVisits {

    const val CHANNEL_ID = "phantom_visits_v1"
    internal const val ACTION_DUE = "local.phantom.companion.action.VISIT_DUE"
    private const val WORK = "phantom_visit"
    private const val ACTION_NOT_NOW = "local.phantom.companion.action.VISIT_NOT_NOW"
    private const val ACTION_DONT = "local.phantom.companion.action.VISIT_DONT"
    private const val ACTION_SWIPED = "local.phantom.companion.action.VISIT_SWIPED"
    private const val EXTRA_ID = "visit_id"
    private const val TAG = "PhantomVisits"

    /** Розмова, яку веб-месенджер має відкрити, щойно зможе. */
    val openChat = MutableStateFlow<String?>(null)

    fun initiative(
        context: Context,
        commitments: CommitmentStore? = null,
        onDueCommitment: ((Commitment, Initiative.Visit) -> Unit)? = null,
    ): Initiative {
        val app = context.applicationContext
        val english = local.phantom.companion.core.data.locale.ReplyLanguage.locale(
            local.phantom.companion.core.data.locale.ReplyLanguage.picked(app),
        ).language == java.util.Locale.ENGLISH.language
        return Initiative(
            store = PrefsInitiativeStore(app),
            commitments = commitments,
            dueWord = { dueMs, nowMs, zone -> CommitmentSpeech.whenWord(dueMs, nowMs, zone, english) },
            onDueCommitment = onDueCommitment,
        )
    }

    fun offerLesson(context: Context, body: String, why: String, atMs: Long): String {
        val id = UUID.randomUUID().toString()
        initiative(context).offer(Initiative.Visit(id, Initiative.Kind.LESSON, LESSON_KEY, body, why, atMs))
        arm(context, atMs)
        return id
    }

    fun withdrawLesson(context: Context) = initiative(context).withdraw(LESSON_KEY)

    /** Нагадування людини йде тією самою чергою, але приходить у свій час завжди. */
    suspend fun remind(context: Context, container: AppContainer, reminder: ReminderEntity): Boolean = runCatching {
        val app = context.applicationContext
        initiative(app).offer(reminderVisit(reminder.id, reminder.text))
        run(app, container)
        true
    }.onFailure { Log.w(TAG, "reminder through visits failed", it) }.getOrDefault(false)

    internal fun earliestDueMs(
        state: Initiative.State,
        openCommitments: List<Commitment>,
        requestedAtMs: Long? = null,
        nowMs: Long = 0L,
    ): Long? {
        val queueMin = state.queue
            .asSequence()
            .filter { it.kind !in state.muted }
            .filter { it.kind != Initiative.Kind.LESSON || requestedAtMs == null || it.dueAtMs > nowMs }
            .map { it.dueAtMs }
            .minOrNull()
        val commitmentMin = openCommitments
            .asSequence()
            .filter { it.state == CommitmentState.OPEN && it.id !in state.announcedCommitments }
            .mapNotNull { it.dueMs }
            .minOrNull()
        return listOfNotNull(requestedAtMs, queueMin, commitmentMin).minOrNull()
    }

    internal fun isReminderNotificationKind(kind: Initiative.Kind): Boolean =
        kind == Initiative.Kind.REMINDER || kind == Initiative.Kind.COMMITMENT

    fun arm(context: Context, atMs: Long) {
        val app = context.applicationContext
        val nowMs = System.currentTimeMillis()
        val state = runCatching { PrefsInitiativeStore(app).load() }.getOrDefault(Initiative.State())
        val openCommitments = runCatching { PhantomApp.container().commitmentStore.listOpen() }.getOrDefault(emptyList())
        val targetMs = earliestDueMs(state, openCommitments, requestedAtMs = atMs, nowMs = nowMs) ?: atMs
        ReminderAlarms.scheduleVisit(app, targetMs)
        val delay = (targetMs - nowMs).coerceAtLeast(0L)
        val req = OneTimeWorkRequestBuilder<VisitWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
        runCatching {
            WorkManager.getInstance(app).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, req)
        }.onFailure { Log.w(TAG, "could not arm visit", it) }
    }

    suspend fun run(context: Context, container: AppContainer) {
        val dueByVisitId = HashMap<String, Commitment>()
        val ini = initiative(
            context = context,
            commitments = container.commitmentStore,
            onDueCommitment = { c, v -> dueByVisitId[v.id] = c },
        )
        repeat(4) {
            when (val d = ini.decide(Initiative.Moment(System.currentTimeMillis(), container.activeRoutePoints.value != null))) {
                is Initiative.Decision.Come -> show(context, container, d.visit, dueByVisitId[d.visit.id])
                is Initiative.Decision.Wait -> {
                    arm(context, d.atMs)
                    return
                }
                Initiative.Decision.Nothing -> return
            }
        }
    }

    private fun show(context: Context, container: AppContainer, v: Initiative.Visit, commitment: Commitment? = null) {
        val msgId = "msg_" + UUID.randomUUID().toString().take(12)
        val dueCommitment = commitment
            ?: v.key.removePrefix(Initiative.COMMITMENT_KEY_PREFIX)
                .takeIf { v.kind == Initiative.Kind.COMMITMENT }
                ?.let { container.commitmentStore.get(it) }
        if (dueCommitment != null) {
            local.phantom.companion.data.CommitmentNotes.put(
                context,
                msgId,
                local.phantom.companion.data.CommitmentCardNote.due(dueCommitment),
            )
        }
        container.mesh.ingestDirect(
            PeerMessage(
                id = msgId,
                fromId = AgencySessionManager.PHANTOM_AGENT_ID,
                toId = container.mesh.selfIdFlow.value.ifBlank { "operator_me" },
                body = visitText(v, "\n\n"),
                sentAtMs = System.currentTimeMillis(),
                state = PeerMessage.State.DELIVERED,
            ),
        )
        notify(context, v)
    }

    private fun notify(context: Context, v: Initiative.Visit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val reminder = isReminderNotificationKind(v.kind)
        if (!reminder) return
        val channel = if (reminder) ReminderAlarms.CHANNEL_ID else CHANNEL_ID
        if (reminder) ReminderAlarms.registerChannel(context) else registerChannel(context)
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(NotificationLook.smallIcon)
            .setColor(NotificationLook.accent)
            .setContentTitle(context.getString(if (reminder) R.string.reminder_title else R.string.visit_title))
            .setContentText(v.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(visitText(v, "\n")))
            .setCategory(if (reminder) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_RECOMMENDATION)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(openPhantom(context))
            .setDeleteIntent(action(context, ACTION_SWIPED, v.id))
        if (!reminder) {
            n.addAction(0, context.getString(R.string.visit_action_not_now), action(context, ACTION_NOT_NOW, v.id))
            n.addAction(0, context.getString(R.string.visit_action_dont), action(context, ACTION_DONT, v.id))
        }
        runCatching { NotificationManagerCompat.from(context).notify(idOf(v), n.build()) }
            .onFailure { Log.w(TAG, "visit notification refused", it) }
    }

    private fun registerChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.visit_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun openPhantom(context: Context): PendingIntent? = runCatching {
        // Явно на MainActivity, як і сповіщення про лист ([local.phantom.companion.service.PeerNotifications]).
        // Неявний VIEW з `phantom://` шукав фільтр, а debug-збірка ловить лише
        // `phantom-debug://`: тап по нагадуванню на A52 не робив нічого (02.10).
        val intent = Intent(context, local.phantom.companion.MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("phantom://peer/${AgencySessionManager.PHANTOM_AGENT_ID}"))
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        PendingIntent.getActivity(context, 0x5E1F, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }.getOrNull()

    private fun action(context: Context, what: String, id: String): PendingIntent {
        // id і в data: filterEquals не бачить extras, і два приходи ділили б одну дію.
        val intent = Intent(context, VisitActionReceiver::class.java)
            .setAction(what)
            .setData(Uri.parse("phantom-visit://$what/$id"))
            .putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(context, (what + id).hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun idOf(v: Initiative.Visit) = 0x5E10000 + (v.key.hashCode() and 0xFFFF)

    /** Тап по сповіщенню PHANTOM: відкрити саме його розмову і зарахувати відгук. */
    fun tapped(context: Context) {
        runCatching { initiative(context).answered() }
        openChat.value = AgencySessionManager.PHANTOM_AGENT_ID
    }

    internal fun handle(context: Context, action: String?, id: String) {
        val ini = initiative(context)
        val came = ini.state().waiting.firstOrNull { it.visit.id == id }?.visit
        when (action) {
            ACTION_SWIPED -> ini.ignored(id)
            ACTION_NOT_NOW -> came?.let {
                ini.notNow(it, System.currentTimeMillis())
                arm(context, System.currentTimeMillis() + Initiative.NOT_NOW_MS)
            }
            ACTION_DONT -> came?.let { ini.dontDoThis(it.kind) }
        }
        came?.let { NotificationManagerCompat.from(context).cancel(idOf(it)) }
    }

    private const val LESSON_KEY = "lesson.csharp"
}

class VisitWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = runCatching { PhantomApp.container() }.getOrNull() ?: return Result.retry()
        runCatching { PhantomVisits.run(applicationContext, container) }
        return Result.success()
    }
}

class VisitActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("visit_id") ?: return
        val app = context.applicationContext
        val pending = goAsync()
        val container = runCatching { PhantomApp.container() }.getOrNull()
        if (container == null) {
            if (intent.action != PhantomVisits.ACTION_DUE) {
                runCatching { PhantomVisits.handle(app, intent.action, id) }
            }
            pending.finish()
            return
        }
        container.scope.launch {
            try {
                if (intent.action == PhantomVisits.ACTION_DUE) {
                    runCatching { PhantomVisits.run(app, container) }
                } else {
                    PhantomVisits.handle(app, intent.action, id)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/** Черга приходів у SharedPreferences: лише тексти самого PHANTOM і часи. */
internal class PrefsInitiativeStore(context: Context) : Initiative.Store {
    private val prefs = context.getSharedPreferences("phantom_self_initiative", Context.MODE_PRIVATE)

    override fun load(): Initiative.State = runCatching {
        val raw = prefs.getString("state", null) ?: return Initiative.State()
        decode(Json.parseToJsonElement(raw).jsonObject)
    }.getOrDefault(Initiative.State())

    override fun save(state: Initiative.State) {
        prefs.edit().putString("state", encode(state).toString()).apply()
    }

    private fun visit(v: Initiative.Visit) = buildJsonObject {
        put("id", v.id); put("kind", v.kind.name); put("key", v.key)
        put("body", v.body); put("why", v.why); put("due", v.dueAtMs)
    }

    private fun visit(o: JsonObject) = Initiative.Visit(
        o.str("id"), Initiative.Kind.valueOf(o.str("kind")), o.str("key"), o.str("body"), o.str("why"),
        o["due"]!!.jsonPrimitive.long,
    )

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content

    private fun encode(s: Initiative.State) = buildJsonObject {
        put("queue", buildJsonArray { s.queue.forEach { add(visit(it)) } })
        put("budget", s.budget); put("day", s.day); put("used", s.usedToday)
        put("waiting", buildJsonArray { s.waiting.forEach { c -> add(buildJsonObject { put("v", visit(c.visit)); put("at", c.atMs) }) } })
        put("muted", buildJsonArray { s.muted.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.name)) } })
        put("announced", buildJsonArray { s.announcedCommitments.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
    }

    private fun decode(o: JsonObject) = Initiative.State(
        queue = (o["queue"] as? JsonArray).orEmpty().map { visit(it.jsonObject) },
        budget = o["budget"]!!.jsonPrimitive.int,
        day = o["day"]!!.jsonPrimitive.long,
        usedToday = o["used"]!!.jsonPrimitive.int,
        waiting = (o["waiting"] as? JsonArray).orEmpty().map {
            Initiative.Came(visit(it.jsonObject["v"]!!.jsonObject), it.jsonObject["at"]!!.jsonPrimitive.long)
        },
        muted = o["muted"]!!.jsonArray.map { Initiative.Kind.valueOf(it.jsonPrimitive.content) }.toSet(),
        announcedCommitments = (o["announced"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }.toSet(),
    )
}

internal fun reminderVisit(id: String, text: String): Initiative.Visit =
    Initiative.Visit("rem-$id", Initiative.Kind.REMINDER, "rem-$id", text, "", 0L)

internal fun visitText(v: Initiative.Visit, gap: String): String =
    listOf(v.body, v.why).filter { it.isNotBlank() }.joinToString(gap)
