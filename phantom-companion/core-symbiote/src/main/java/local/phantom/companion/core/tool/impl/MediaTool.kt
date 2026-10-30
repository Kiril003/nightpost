package local.phantom.companion.core.tool.impl

import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import kotlinx.serialization.json.JsonObject
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

/**
 * Controls the active media session (play/pause/next/previous/stop).
 *
 * Requires [ToolPermission.MEDIA_CONTROL] in [ToolContext.approvedPermissions].
 *
 * Uses [MediaSessionManager.getActiveSessions] which requires the caller to
 * hold a live [android.service.notification.NotificationListenerService]
 * component. The component name is passed as `null` here — on Android 12+
 * this succeeds when the package holds a `MEDIA_CONTENT_CONTROL` permission
 * or has NotificationListener access. If no active sessions are found (most
 * commonly because NotificationListener access was not granted), an
 * `Err("no_active_session", …)` is returned so the operator knows what to fix.
 *
 * Supported actions: `play`, `pause`, `play_pause`, `next`, `previous`, `stop`.
 *
 * @param applicationContext Application context for [MediaSessionManager].
 */
class MediaTool(
    private val applicationContext: Context,
) : Tool {

    override val id = "media"

    override val description =
        "Керувати активним відтворенням: play, pause, play_pause, next, previous, stop. " +
            "Потребує доступу до сповіщень, щоб узагалі побачити, що грає. " +
            "/ Control the active media session."

    override val argsSchema: JsonObject = buildJsonObject {
        put("action", kotlinx.serialization.json.JsonPrimitive("string"))
    }

    override val permissions: Set<ToolPermission> = setOf(ToolPermission.MEDIA_CONTROL)

    override val risk: ToolRisk = ToolRisk.DEVICE

    override val streaming: Boolean = false

    override val manifest = FeatureManifest(
        id = "tool.media",
        tier = 1,
        displayName = "Медіа",
        erodesOperatorAgency = false,
        exfiltratesPrivate = false,
        actsWithoutConsent = false,
        abusableAgainstWeak = false,
        sunsetAfterDaysIdle = 180,
        surfaces = setOf(FeatureManifest.Surface.AMBIENT),
    )

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val startMs = System.currentTimeMillis()
        val elapsed: () -> Long = { System.currentTimeMillis() - startMs }

        if (ToolPermission.MEDIA_CONTROL !in context.approvedPermissions) {
            emit(
                ToolEvent.Notice(
                    "Нічого не перемикав: дозвіл на керування відтворенням не наданий цьому виклику. " +
                        "Дозволь інструмент «Медіа» у списку дозволів — або керуй зі шторки сповіщень.",
                ),
            )
            return ToolResult.PermissionDenied(ToolPermission.MEDIA_CONTROL, elapsed())
        }

        val action = args["action"]?.jsonPrimitive?.content?.lowercase()
            ?: return ToolResult.Err(
                "bad_args",
                "нічого не перемикав: не передано action. Дозволені: ${SUPPORTED_ACTIONS.joinToString()}.",
                elapsed(),
            )

        if (action !in SUPPORTED_ACTIONS) {
            return ToolResult.Err(
                "bad_args",
                "дії «$action» я не вмію. Дозволені: ${SUPPORTED_ACTIONS.joinToString()}.",
                elapsed(),
            )
        }

        val msm = applicationContext.getSystemService(Context.MEDIA_SESSION_SERVICE)
            as? MediaSessionManager
            ?: return ToolResult.Err(
                "not_available",
                "нічого не перемикав: система не дала доступу до медіасесій на цьому пристрої. " +
                    "Керуй відтворенням зі шторки сповіщень.",
                elapsed(),
            )

        val sessions: List<MediaController> = try {
            // null component = requires MEDIA_CONTENT_CONTROL permission or NotificationListener access
            @Suppress("DEPRECATION")
            msm.getActiveSessions(null)
        } catch (e: SecurityException) {
            return ToolResult.Err(
                "no_active_session",
                "нічого не перемикав: немає доступу до сповіщень, тому я не бачу, що грає. " +
                    "Увімкни доступ у Налаштування → Сповіщення → Доступ до сповіщень → Nightpost, і повтори.",
                elapsed(),
            )
        }

        if (sessions.isEmpty()) {
            return ToolResult.Err(
                "no_active_session",
                "нічого не перемикав: жодного активного програвача не видно. " +
                    "Або зараз нічого не грає, або не увімкнено доступ до сповіщень " +
                    "(Налаштування → Сповіщення → Доступ до сповіщень). Запусти музику й повтори.",
                elapsed(),
            )
        }

        val controller = sessions.first()
        val controls = controller.transportControls
        val stateBefore = controller.playbackState?.state
        val meta = controller.metadata
        val track = meta?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
        val artist = meta?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)

        when (action) {
            "play" -> controls.play()
            "pause" -> controls.pause()
            "play_pause" -> {
                if (stateBefore == android.media.session.PlaybackState.STATE_PLAYING) controls.pause()
                else controls.play()
            }
            "next" -> controls.skipToNext()
            "previous" -> controls.skipToPrevious()
            "stop" -> controls.stop()
        }

        val app = controller.packageName ?: "невідомий застосунок"
        val what = listOfNotNull(track, artist).joinToString(" — ").takeIf { it.isNotBlank() }
        return ToolResult.Ok(
            // "sent" is the whole truth: the player decides what happens next,
            // and it may ignore the command entirely.
            output = "Надіслав «$action» до $app" + (what?.let { " (грало: $it)" } ?: "") +
                ". Чи виконав програвач — він вирішує сам.",
            structured = buildJsonObject {
                put("action", action)
                put("packageName", app)
                track?.let { put("track", it) }
                artist?.let { put("artist", it) }
                put("state_before", playbackWord(stateBefore))
                put("sessions", sessions.size)
                put("dispatched", true)
                put("confirmed_by_player", false)
            },
            durationMs = elapsed(),
        )
    }

    private fun playbackWord(state: Int?): String = when (state) {
        android.media.session.PlaybackState.STATE_PLAYING -> "грало"
        android.media.session.PlaybackState.STATE_PAUSED -> "на паузі"
        android.media.session.PlaybackState.STATE_STOPPED -> "зупинено"
        android.media.session.PlaybackState.STATE_BUFFERING -> "буферизація"
        null -> "стан невідомий"
        else -> "інший стан"
    }

    companion object {
        private val SUPPORTED_ACTIONS = setOf("play", "pause", "play_pause", "next", "previous", "stop")
    }
}
