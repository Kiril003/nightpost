package local.phantom.companion.core.tool.impl

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk
import java.util.concurrent.atomic.AtomicInteger

/**
 * Posts an Android notification via [NotificationManagerCompat].
 *
 * Requires [ToolPermission.NOTIFICATION_POST] in [ToolContext.approvedPermissions].
 *
 * The default channel `"phantom_agent_actions"` is created lazily on first
 * call. Callers may override with `channelId` to post to a pre-existing
 * operator channel, but must create that channel themselves.
 *
 * `delivered` in the structured answer is the load-bearing field: the
 * system swallows a notify() on a device where notifications are off, and
 * a tool that answers «показав» in that case is lying to the operator.
 *
 * @param applicationContext Application context for notification services.
 * @param smallIconRes Значок у шторці. Параметр, бо `R` застосунку цьому
 *   модулю не видно, а стоковий android-глиф на плашці PHANTOM — чужий.
 * @param accentColor Акцент ARGB — з тієї ж палітри, що й решта шторки.
 */
class NotificationTool(
    private val applicationContext: Context,
    private val smallIconRes: Int,
    private val accentColor: Int,
) : Tool {

    override val id = "notification"

    override val description =
        "Показати системне сповіщення: title — заголовок (обовʼязковий), body — текст, " +
            "priority — low/default/high, channelId — свій канал (типово «$DEFAULT_CHANNEL_ID»). " +
            "Лишається на цьому пристрої. Якщо сповіщення вимкнені — відмовиться і скаже це."

    override val argsSchema: JsonObject = buildJsonObject {
        put("title", JsonPrimitive("string"))
        put("body", JsonPrimitive("string?"))
        put("channelId", JsonPrimitive("string?"))
        put("priority", JsonPrimitive("string?"))
    }

    override val permissions: Set<ToolPermission> = setOf(ToolPermission.NOTIFICATION_POST)

    override val risk: ToolRisk = ToolRisk.DEVICE

    override val streaming: Boolean = false

    override val manifest = FeatureManifest(
        id = "tool.notification",
        tier = 1,
        displayName = "Сповіщення",
        erodesOperatorAgency = false,
        exfiltratesPrivate = false,
        actsWithoutConsent = false,
        abusableAgainstWeak = false,
        sunsetAfterDaysIdle = 180,
        surfaces = setOf(FeatureManifest.Surface.NOTIFICATION),
    )

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val startMs = System.currentTimeMillis()
        val elapsed: () -> Long = { System.currentTimeMillis() - startMs }

        if (ToolPermission.NOTIFICATION_POST !in context.approvedPermissions) {
            return ToolResult.PermissionDenied(ToolPermission.NOTIFICATION_POST, elapsed())
        }

        val title = args["title"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.Err(
                "bad_args",
                "Сповіщення без заголовка не показую — у шторці був би порожній рядок. " +
                    "Передай title одним рядком, деталі клади в body.",
                elapsed(),
            )

        val body = args["body"]?.jsonPrimitive?.content
        val channelId = args["channelId"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_CHANNEL_ID
        val priorityWord = args["priority"]?.jsonPrimitive?.content?.lowercase()?.trim() ?: "default"
        if (priorityWord !in KNOWN_PRIORITIES) {
            return ToolResult.Err(
                "bad_args",
                "Пріоритету «$priorityWord» не існує. Є low, default, high — назви один із них.",
                elapsed(),
            )
        }
        val priority = when (priorityWord) {
            "low" -> NotificationCompat.PRIORITY_LOW
            "high" -> NotificationCompat.PRIORITY_HIGH
            else -> NotificationCompat.PRIORITY_DEFAULT
        }

        // Null = the platform did not answer (JVM stub) — not a "no".
        val enabled = runCatching {
            NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()
        }.getOrNull()
        if (enabled == false) {
            return ToolResult.Err(
                "notifications_off",
                "Сповіщення для Nightpost вимкнені на цьому пристрої — показувати нікуди. " +
                    "Увімкни їх у Налаштуваннях → Застосунки → Nightpost → Сповіщення, тоді повтори.",
                elapsed(),
            )
        }

        ensureDefaultChannelExists()

        val notificationId = notificationIdCounter.incrementAndGet()

        fun answer(delivered: Boolean, note: String?) = ToolResult.Ok(
            output = if (delivered) {
                "Показав сповіщення «$title»" + (body?.let { " — $it" } ?: "") + "."
            } else {
                "Сповіщення «$title» не показав: ${note ?: "система його не прийняла"}."
            },
            structured = buildJsonObject {
                put("notificationId", notificationId)
                put("channelId", channelId)
                put("title", title)
                body?.let { put("body", it) }
                put("priority", priorityWord)
                put("delivered", delivered)
                put("notifications_enabled", enabled?.toString() ?: "невідомо")
                note?.let { put("note", it) }
            },
            durationMs = elapsed(),
        )

        // Builder construction and notify are both inside try so that JVM stub NPEs
        // (Notification.Builder chain returns null under isReturnDefaultValues=true) are caught.
        return try {
            val notification = NotificationCompat.Builder(applicationContext, channelId)
                .setSmallIcon(smallIconRes)
                .setColor(accentColor)
                .setContentTitle(title)
                .apply { if (body != null) setContentText(body) }
                .setPriority(priority)
                .setAutoCancel(true)
                // Текст пише агент за завданням оператора — це його вміст,
                // а не системне повідомлення.
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .build()

            NotificationManagerCompat.from(applicationContext).notify(notificationId, notification)
            answer(delivered = true, note = null)
        } catch (e: SecurityException) {
            ToolResult.Err(
                "permission_required",
                "Android не дав дозволу POST_NOTIFICATIONS (${e.message ?: "без деталей"}). " +
                    "Дай його у Налаштуваннях → Застосунки → Nightpost → Сповіщення і повтори.",
                elapsed(),
            )
        } catch (e: NullPointerException) {
            answer(delivered = false, note = "система сповіщень недоступна на цьому пристрої")
        } catch (e: Exception) {
            ToolResult.Err(
                "notify_error",
                "Не вдалось показати сповіщення: ${e.message ?: "система відмовила без пояснення"}. " +
                    "Скажи оператору голосом або текстом — сповіщення тут не спрацює.",
                elapsed(),
            )
        }
    }

    private fun ensureDefaultChannelExists() {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (nm.getNotificationChannel(DEFAULT_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                DEFAULT_CHANNEL_ID,
                "Дії помічника",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Сповіщення, які показує помічник Nightpost."
            }
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val DEFAULT_CHANNEL_ID = "phantom_agent_actions"

        private val KNOWN_PRIORITIES = setOf("low", "default", "high")

        /** Process-scoped counter — avoids duplicate IDs within a session. */
        private val notificationIdCounter = AtomicInteger(10_000)
    }
}
