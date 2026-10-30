package local.phantom.companion.ui.messenger

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.data.AppContainer
import local.phantom.companion.data.CalendarPermit
import local.phantom.companion.data.PhoneCalendar
import local.phantom.companion.self.CommitmentService
import local.phantom.companion.self.DayAgenda

class WebMessengerDay(
    private val reminders: Reminders,
    private val promises: CommitmentService,
    private val calendar: Calendar,
    private val plans: Plans = Plans { emptyList() },
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val alerts: Alerts = Alerts.Unknown,
) {
    /** Чи дійдуть нагадування: сповіщення застосунку й точний час. null — стан невідомий. */
    interface Alerts {
        fun notifications(): Boolean?
        fun exact(): Boolean?
        fun open(kind: String): Boolean

        object Unknown : Alerts {
            override fun notifications(): Boolean? = null
            override fun exact(): Boolean? = null
            override fun open(kind: String): Boolean = false
        }
    }

    fun interface Reminders {
        suspend fun pending(): List<DayAgenda.ReminderLine>
    }

    fun interface Plans {
        suspend fun rows(): List<DayAgenda.PlanLine>
    }

    interface Calendar {
        fun granted(): Boolean
        fun instances(fromMs: Long, toMs: Long): List<DayAgenda.CalendarEvent>
        fun ask(): Boolean
    }

    fun claims(seg: List<String>): Boolean = seg.size >= 2 && seg[1] == "day"

    suspend fun handle(method: String, seg: List<String>, body: String?): String = runCatching {
        run {
            when {
                seg.size == 2 && seg[1] == "day" && method == "GET" -> ok(today())
                seg.size == 5 && seg[1] == "day" && seg[2] == "promises" && seg[4] == "done" && method == "POST" -> promiseDone(seg[3])
                seg.size == 4 && seg[1] == "day" && seg[2] == "promises" && method == "DELETE" -> promiseDrop(seg[3])
                seg.size == 4 && seg[1] == "day" && seg[2] == "calendar" && seg[3] == "ask" && method == "POST" -> ok(askCalendar())
                seg.size == 4 && seg[1] == "day" && seg[2] == "alerts" && seg[3] == "open" && method == "POST" -> openAlerts(body)
                else -> fail(404, "not_found")
            }
        }
    }.getOrElse { fail(500, "day_error") }

    internal suspend fun today(): JsonElement {
        val nowMs = now()
        val z = zone()
        val granted = calendar.granted()
        val events = if (granted) {
            val date = Instant.ofEpochMilli(nowMs).atZone(z).toLocalDate()
            val fromMs = date.minusDays(1).atStartOfDay(z).toInstant().toEpochMilli()
            val toMs = date.plusDays(2).atStartOfDay(z).toInstant().toEpochMilli()
            calendar.instances(fromMs, toMs)
        } else {
            null
        }
        val items = DayAgenda.today(
            nowMs = nowMs,
            zone = z,
            reminders = reminders.pending(),
            promises = promises.listOpen(),
            events = events,
            plans = plans.rows(),
        )
        return buildJsonObject {
            put("now_ms", nowMs)
            put("calendar", if (granted) "granted" else "denied")
            alerts.notifications()?.let { put("notifications", it) }
            alerts.exact()?.let { put("exact_alarms", it) }
            put(
                "items",
                buildJsonArray {
                    items.forEach { item ->
                        add(
                            buildJsonObject {
                                put("id", item.id)
                                put("kind", item.kind.wire)
                                put("title", item.title)
                                put("at_ms", item.atMs)
                                if (item.endMs != null) put("end_ms", item.endMs) else put("end_ms", JsonNull)
                                put("all_day", item.allDay)
                                if (item.peerId != null) put("peer_id", item.peerId) else put("peer_id", JsonNull)
                                put("overdue", item.overdue)
                            },
                        )
                    }
                },
            )
        }
    }

    private fun promiseDone(id: String): String {
        if (id.isEmpty() || !promises.done(id)) return fail(404, "not_found")
        return ok(
            buildJsonObject {
                put("id", id)
                put("state", "done")
            },
        )
    }

    private fun promiseDrop(id: String): String {
        if (id.isEmpty() || !promises.drop(id)) return fail(404, "not_found")
        return ok(
            buildJsonObject {
                put("id", id)
                put("state", "dropped")
            },
        )
    }

    private fun openAlerts(body: String?): String {
        val kind = runCatching {
            (kotlinx.serialization.json.Json.parseToJsonElement(body ?: "{}") as? kotlinx.serialization.json.JsonObject)
                ?.get("kind")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        }.getOrNull()
        if (kind != "notifications" && kind != "exact") return fail(400, "bad_kind")
        return ok(buildJsonObject { put("opened", alerts.open(kind)) })
    }

    private fun askCalendar(): JsonElement =
        if (calendar.granted()) {
            buildJsonObject {
                put("asked", false)
                put("granted", true)
            }
        } else {
            val asked = calendar.ask()
            buildJsonObject {
                put("asked", asked)
                put("granted", false)
            }
        }

    private fun ok(data: JsonElement): String = buildJsonObject {
        put("ok", true)
        put("status", 200)
        put("data", data)
    }.toString()

    private fun fail(status: Int, code: String): String = buildJsonObject {
        put("ok", false)
        put("status", status)
        put("code", code)
        put("detail", "")
    }.toString()

    companion object {
        fun of(container: AppContainer): WebMessengerDay {
            val phoneCalendar = PhoneCalendar(container.applicationContext)
            return WebMessengerDay(
                reminders = Reminders {
                    container.reminderStore.pending(200).map {
                        DayAgenda.ReminderLine(it.id, it.text, it.triggerAtMs)
                    }
                },
                promises = container.commitments,
                calendar = object : Calendar {
                    override fun granted(): Boolean = phoneCalendar.granted()
                    override fun instances(fromMs: Long, toMs: Long): List<DayAgenda.CalendarEvent> =
                        phoneCalendar.instances(fromMs, toMs)
                    override fun ask(): Boolean = CalendarPermit.ask()
                },
                zone = container.commitments::zoneId,
                alerts = object : Alerts {
                    private val ctx = container.applicationContext
                    override fun notifications(): Boolean? =
                        runCatching { androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled() }.getOrNull()
                    override fun exact(): Boolean? =
                        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
                            true
                        } else {
                            runCatching { (ctx.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager).canScheduleExactAlarms() }.getOrNull()
                        }
                    override fun open(kind: String): Boolean = runCatching {
                        val intent = if (kind == "exact" && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:" + ctx.packageName))
                        } else {
                            android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        }
                        ctx.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        true
                    }.getOrDefault(false)
                },
            )
        }
    }
}
