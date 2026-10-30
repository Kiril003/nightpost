package local.phantom.companion.core.tool.impl

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
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
 * Device-settings mutations: brightness, DND, volume, flashlight.
 *
 * Requires [ToolPermission.SETTINGS_WRITE] in [ToolContext.approvedPermissions].
 *
 * Action-specific system permissions are checked at runtime and reported
 * via [ToolResult.Err] with a descriptive hint so the operator knows what
 * to grant in Android Settings → Special app access.
 *
 * Supported actions:
 * - `brightness` — `value` 0..1 float → SCREEN_BRIGHTNESS 0..255.
 *   Requires `Settings.System.canWrite(context)`.
 * - `dnd` — `value` "none"|"priority"|"alarms"|"all" → interruption filter.
 *   Requires `NotificationManager.isNotificationPolicyAccessGranted`.
 * - `volume` — `stream` "media"|"notification"|"ring" (default "media");
 *   absolute `value` 0..1 float OR relative `delta` -1..1 float
 *   (R5 — «тихіше»/«гучніше» without knowing the current level).
 * - `flashlight` — `value` "true"|"false" → torch on/off via first flash camera.
 * - `airplane_mode` — always returns not_supported (needs root).
 *
 * @param applicationContext Application context — used for system service lookups.
 */
class SettingsTool(
    private val applicationContext: Context,
) : Tool {

    override val id = "settings"

    override val description =
        "Змінити налаштування пристрою: action — brightness (value 0..1), dnd " +
            "(value none|priority|alarms|all), volume (stream media|notification|ring, value 0..1 " +
            "АБО delta -1..1), flashlight (value true|false). Повертає стан ПІСЛЯ зміни, зчитаний " +
            "із системи, а не те, що просили. Частина дій потребує окремих системних дозволів — " +
            "тоді інструмент скаже, який саме екран відкрити."

    override val argsSchema: JsonObject = buildJsonObject {
        put("action", kotlinx.serialization.json.JsonPrimitive("string"))
        // `any?`, а не `any`: volume приймає АБО value, АБО delta, і сам це
        // перевіряє. Поки схема вимагала value беззастережно, ToolExecutor
        // відсікав виклик ДО handleVolume — чипи Скрині «тихіше»/«гучніше»
        // слали {action: volume, delta: ±0.15} і отримували «не вистачає
        // обовʼязкового value» за 0 мс. Решта дій вимагають value кожна у
        // своєму обробнику, з власним поясненням, тож нічого не послаблено.
        put("value", kotlinx.serialization.json.JsonPrimitive("any?"))
        put("stream", kotlinx.serialization.json.JsonPrimitive("string?"))
        put("delta", kotlinx.serialization.json.JsonPrimitive("number?"))
    }

    override val permissions: Set<ToolPermission> = setOf(ToolPermission.SETTINGS_WRITE)

    override val risk: ToolRisk = ToolRisk.DEVICE

    override val streaming: Boolean = false

    override val manifest = FeatureManifest(
        id = "tool.settings",
        tier = 1,
        displayName = "Налаштування",
        erodesOperatorAgency = false,
        exfiltratesPrivate = false,
        actsWithoutConsent = false,
        abusableAgainstWeak = false,
        sunsetAfterDaysIdle = 120,
        surfaces = setOf(FeatureManifest.Surface.STREAM),
    )

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val startMs = System.currentTimeMillis()
        val elapsed: () -> Long = { System.currentTimeMillis() - startMs }

        if (ToolPermission.SETTINGS_WRITE !in context.approvedPermissions) {
            return ToolResult.PermissionDenied(ToolPermission.SETTINGS_WRITE, elapsed())
        }

        val action = args["action"]?.jsonPrimitive?.content
            ?: return ToolResult.Err(
                "bad_args",
                "не вказано action. Доступні: $SUPPORTED_ACTIONS. Повтори виклик з одним із них.",
                elapsed(),
            )

        return when (action.lowercase()) {
            "brightness" -> handleBrightness(args, elapsed)
            "dnd" -> handleDnd(args, elapsed)
            "volume" -> handleVolume(args, elapsed)
            "flashlight" -> handleFlashlight(args, elapsed)
            "airplane_mode" -> ToolResult.Err(
                "not_supported",
                "режим польоту Android застосунку не віддає — жодним дозволом це не відкривається. " +
                    "Наступний крок: виклич launch з android.settings.AIRPLANE_MODE_SETTINGS, " +
                    "оператор перемкне сам.",
                elapsed(),
            )
            else -> ToolResult.Err(
                "bad_args",
                "не знаю дії «$action». Доступні: $SUPPORTED_ACTIONS. Повтори виклик з однією з них.",
                elapsed(),
            )
        }
    }

    // -------------------------------------------------------------------------
    // Brightness
    // -------------------------------------------------------------------------

    private fun handleBrightness(args: JsonObject, elapsed: () -> Long): ToolResult {
        val valuePrim = args["value"]?.jsonPrimitive
            ?: return ToolResult.Err(
                "bad_args",
                "для brightness потрібен value — частка від 0 до 1 (0.5 = половина). Повтори виклик з ним.",
                elapsed(),
            )
        val float = valuePrim.content.toFloatOrNull()
            ?: return ToolResult.Err(
                "bad_args",
                "value=«${valuePrim.content}» не число. Потрібна частка від 0 до 1, напр. 0.4.",
                elapsed(),
            )
        if (float < 0f || float > 1f) {
            return ToolResult.Err(
                "bad_args",
                "value=$float поза межами 0..1. Це частка яскравості, не відсотки — 40% це 0.4.",
                elapsed(),
            )
        }

        if (!Settings.System.canWrite(applicationContext)) {
            // Речення для людини попереду, машинний хвіст «Grant at:» позаду —
            // саме його системний промпт учить віддавати в launch(action=…).
            return ToolResult.Err(
                "permission_required",
                "Система не пускає мене до яскравості, доки ти не даси дозвіл " +
                    "«Зміна системних налаштувань». Grant at: " +
                    "android.settings.action.MANAGE_WRITE_SETTINGS package:${applicationContext.packageName}",
                elapsed(),
            )
        }

        val brightnessInt = (float * 255).toInt().coerceIn(0, 255)
        val written = runCatching {
            Settings.System.putInt(
                applicationContext.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightnessInt,
            )
        }.getOrDefault(false)
        if (!written) {
            return ToolResult.Err(
                "write_rejected",
                "система відхилила запис яскравості попри наданий дозвіл (так роблять деякі оболонки, " +
                    "напр. MIUI). Наступний крок: хай оператор виставить її шторкою.",
                elapsed(),
            )
        }
        // Auto-brightness silently overrides the write a moment later; without
        // this the tool reports success for a change the operator never sees.
        val auto = runCatching {
            Settings.System.getInt(
                applicationContext.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
            ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        }.getOrDefault(false)
        val readBack = runCatching {
            Settings.System.getInt(applicationContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrNull()
        val percent = ((readBack ?: brightnessInt) * 100 / 255)
        val autoNote = if (auto) {
            " Увімкнено автояскравість, тож датчик світла перебʼє це за секунди — скажи оператору " +
                "вимкнути її, якщо треба зафіксувати."
        } else {
            ""
        }
        return ToolResult.Ok(
            output = "Яскравість — $percent%.$autoNote",
            structured = buildJsonObject {
                put("action", "brightness")
                put("value", float)
                put("raw", brightnessInt)
                put("percent", percent)
                readBack?.let { put("read_back_raw", it) }
                put("auto_brightness", auto)
                put("applied", true)
            },
            durationMs = elapsed(),
        )
    }

    // -------------------------------------------------------------------------
    // DND (Do Not Disturb)
    // -------------------------------------------------------------------------

    private fun handleDnd(args: JsonObject, elapsed: () -> Long): ToolResult {
        val valuePrim = args["value"]?.jsonPrimitive
            ?: return ToolResult.Err(
                "bad_args",
                "для dnd потрібен value: none (все чути), priority (лише важливе), alarms (лише будильники), " +
                    "all (тиша). Повтори виклик з одним із них.",
                elapsed(),
            )
        val mode = valuePrim.content.lowercase()

        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
            as? android.app.NotificationManager
            ?: return ToolResult.Err(
                "not_available",
                "система не віддала менеджер сповіщень — режимом «Не турбувати» звідси не керувати. " +
                    "Наступний крок: хай оператор перемкне його шторкою.",
                elapsed(),
            )

        if (!nm.isNotificationPolicyAccessGranted) {
            return ToolResult.Err(
                "permission_required",
                "Не можу чіпати «Не турбувати», доки ти не даси доступ до " +
                    "режиму сповіщень. Grant at: " +
                    "android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS",
                elapsed(),
            )
        }

        val filter = when (mode) {
            "none" -> android.app.NotificationManager.INTERRUPTION_FILTER_ALL
            "priority" -> android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
            "alarms" -> android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS
            "all" -> android.app.NotificationManager.INTERRUPTION_FILTER_NONE
            else -> return ToolResult.Err(
                "bad_args",
                "не знаю режиму «$mode». Доступні: none, priority, alarms, all. Повтори виклик з одним із них.",
                elapsed(),
            )
        }

        nm.setInterruptionFilter(filter)
        val actual = runCatching { nm.currentInterruptionFilter }.getOrNull()
        val applied = actual == filter
        val words = mapOf(
            "none" to "усі сповіщення чути",
            "priority" to "лише важливі",
            "alarms" to "лише будильники",
            "all" to "повна тиша",
        )
        if (!applied) {
            return ToolResult.Err(
                "not_applied",
                "попросив режим «$mode», але система лишилась на іншому — деякі оболонки блокують " +
                    "зміну ззовні. Наступний крок: хай оператор перемкне «Не турбувати» шторкою.",
                elapsed(),
            )
        }
        return ToolResult.Ok(
            output = "«Не турбувати»: ${words[mode]} (режим $mode).",
            structured = buildJsonObject {
                put("action", "dnd")
                put("mode", mode)
                put("filter", filter)
                put("state", words[mode] ?: mode)
                put("applied", true)
            },
            durationMs = elapsed(),
        )
    }

    // -------------------------------------------------------------------------
    // Volume
    // -------------------------------------------------------------------------

    private fun handleVolume(args: JsonObject, elapsed: () -> Long): ToolResult {
        val streamArg = args["stream"]?.jsonPrimitive?.content?.lowercase() ?: "media"
        val delta = args["delta"]?.jsonPrimitive?.content?.toFloatOrNull()
        val float = args["value"]?.jsonPrimitive?.content?.toFloatOrNull()
        if (delta == null && float == null) {
            return ToolResult.Err(
                "bad_args",
                "для volume потрібен або value (0..1, абсолютна гучність), або delta (-1..1, «тихіше»/«гучніше»). " +
                    "Повтори виклик з одним із них.",
                elapsed(),
            )
        }
        if (float != null && (float < 0f || float > 1f)) {
            return ToolResult.Err(
                "bad_args",
                "value=$float поза межами 0..1. Це частка від максимуму, не відсотки — 30% це 0.3.",
                elapsed(),
            )
        }
        if (delta != null && (delta < -1f || delta > 1f)) {
            return ToolResult.Err(
                "bad_args",
                "delta=$delta поза межами -1..1. Це зсув часткою від максимуму: -0.2 = «тихіше на пʼяту».",
                elapsed(),
            )
        }

        // Validate stream arg BEFORE acquiring AudioManager so bad_args fires first.
        val streamType = when (streamArg) {
            "media" -> AudioManager.STREAM_MUSIC
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "ring" -> AudioManager.STREAM_RING
            else -> return ToolResult.Err(
                "bad_args",
                "не знаю потоку «$streamArg». Доступні: media (музика й відео), notification (сповіщення), " +
                    "ring (дзвінок). Повтори виклик з одним із них.",
                elapsed(),
            )
        }

        val am = applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ToolResult.Err(
                "not_available",
                "система не віддала звуковий сервіс — гучністю звідси не керувати. " +
                    "Наступний крок: хай оператор змінить її боковими кнопками.",
                elapsed(),
            )

        val max = am.getStreamMaxVolume(streamType)
        val before = am.getStreamVolume(streamType)
        val target = if (delta != null) {
            (before + delta * max).toInt().coerceIn(0, max)
        } else {
            (float!! * max).toInt().coerceIn(0, max)
        }
        am.setStreamVolume(streamType, target, 0)
        // DND and some OEM policies reject the write silently; before/after is
        // the only honest way to know whether anything actually moved.
        val after = runCatching { am.getStreamVolume(streamType) }.getOrDefault(target)
        val percent = if (max > 0) after * 100 / max else 0
        val streamWord = mapOf(
            "media" to "музики", "notification" to "сповіщень", "ring" to "дзвінка",
        )[streamArg] ?: streamArg

        if (after != target && before == after) {
            return ToolResult.Err(
                "not_applied",
                "гучність $streamWord лишилась $percent% — система не прийняла зміну (часто через " +
                    "режим «Не турбувати»). Наступний крок: спершу вимкни dnd, потім повтори.",
                elapsed(),
            )
        }
        return ToolResult.Ok(
            output = "Гучність $streamWord — $percent% ($after з $max).",
            structured = buildJsonObject {
                put("action", "volume")
                put("stream", streamArg)
                float?.let { put("value", it) }
                delta?.let { put("delta", it) }
                put("raw", after)
                put("max", max)
                put("percent", percent)
                put("raw_before", before)
                put("applied", after == target)
            },
            durationMs = elapsed(),
        )
    }

    // -------------------------------------------------------------------------
    // Flashlight
    // -------------------------------------------------------------------------

    private fun handleFlashlight(args: JsonObject, elapsed: () -> Long): ToolResult {
        val valuePrim = args["value"]?.jsonPrimitive
            ?: return ToolResult.Err(
                "bad_args",
                "для flashlight потрібен value: true (увімкнути) або false (вимкнути). Повтори виклик з ним.",
                elapsed(),
            )
        val on = valuePrim.content.toBooleanStrictOrNull()
            ?: return ToolResult.Err(
                "bad_args",
                "value=«${valuePrim.content}» — очікую true або false.",
                elapsed(),
            )

        val cm = applicationContext.getSystemService(Context.CAMERA_SERVICE)
            as? android.hardware.camera2.CameraManager
            ?: return ToolResult.Err(
                "not_available",
                "система не віддала камеру — ліхтариком звідси не керувати. " +
                    "Наступний крок: хай оператор увімкне його шторкою.",
                elapsed(),
            )

        val cameraId = try {
            cm.cameraIdList.firstOrNull { id ->
                val chars = cm.getCameraCharacteristics(id)
                val hasFlash = chars.get(
                    android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE,
                ) ?: false
                hasFlash
            } ?: return ToolResult.Err(
                "not_available",
                "на цьому пристрої немає камери зі спалахом — ліхтарика тут просто нема. " +
                    "Не обіцяй його оператору.",
                elapsed(),
            )
        } catch (e: android.hardware.camera2.CameraAccessException) {
            return ToolResult.Err(
                "camera_error",
                "камера зайнята або недоступна (${e.message ?: "CameraAccessException"}). " +
                    "Наступний крок: закрий застосунок камери і повтори.",
                elapsed(),
            )
        }

        return try {
            cm.setTorchMode(cameraId, on)
            ToolResult.Ok(
                output = if (on) "Ліхтарик увімкнено." else "Ліхтарик вимкнено.",
                structured = buildJsonObject {
                    put("action", "flashlight")
                    put("on", on)
                    put("state", if (on) "увімкнено" else "вимкнено")
                    put("cameraId", cameraId)
                    put("applied", true)
                },
                durationMs = elapsed(),
            )
        } catch (e: android.hardware.camera2.CameraAccessException) {
            ToolResult.Err(
                "camera_error",
                "ліхтарик не перемкнувся (${e.message ?: "камера зайнята"}). " +
                    "Наступний крок: закрий застосунок камери і повтори.",
                elapsed(),
            )
        }
    }

    private companion object {
        const val SUPPORTED_ACTIONS = "brightness, dnd, volume, flashlight"
    }
}
