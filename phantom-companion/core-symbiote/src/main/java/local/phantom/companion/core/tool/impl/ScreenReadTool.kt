package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk

/**
 * R5.1 — the model's eyes between actions. Returns a compact snapshot of
 * the foreground app's interactive elements (clickable / editable /
 * scrollable, with labels, resource-ids and tap coordinates) so a device-
 * control flow can observe → act → observe instead of firing a whole
 * sequence blind.
 *
 * The snapshot source is injected as a lambda so `:core-symbiote` stays
 * free of the `:feature-godmode` AccessibilityService types. AppContainer
 * binds it to `AccessibilityServiceActionRegistry.snapshotUi()?.render()`.
 * A null return means the service is unbound (Accessibility not granted).
 */
class ScreenReadTool(
    private val snapshot: () -> String?,
) : Tool {

    override val id = "screen.read"

    override val description =
        "Прочитати поточний екран: застосунок на передньому плані та його інтерактивні елементи " +
            "(натискні/редаговані, підписи, id, координати дотику). Виклич, щоб ПЕРЕВІРИТИ результат " +
            "попередньої дії перед наступним кроком або спланувати дотик. Це знімок на мить виклику — " +
            "екран міг змінитись відтоді. Потребує увімкненого сервісу доступності Nightpost."

    override val argsSchema: JsonObject = buildJsonObject { }

    override val permissions: Set<ToolPermission> = setOf(ToolPermission.ACCESSIBILITY)

    override val risk: ToolRisk = ToolRisk.READ // лише дивиться на чужий екран, не торкається

    override val streaming: Boolean = false

    override val manifest = FeatureManifest(
        id = "tool.screen.read",
        tier = 1,
        displayName = "Читання екрана",
        erodesOperatorAgency = false,
        exfiltratesPrivate = false,
        actsWithoutConsent = false,
        abusableAgainstWeak = false,
        sunsetAfterDaysIdle = 90,
        surfaces = setOf(FeatureManifest.Surface.STREAM),
    )

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val startMs = System.currentTimeMillis()
        val elapsed: () -> Long = { System.currentTimeMillis() - startMs }

        if (ToolPermission.ACCESSIBILITY !in context.approvedPermissions) {
            return ToolResult.PermissionDenied(ToolPermission.ACCESSIBILITY, elapsed())
        }

        val takenAt = System.currentTimeMillis()
        val rendered = runCatching { snapshot() }.getOrNull()
            ?: return ToolResult.Err(
                "accessibility_off",
                "не бачу екран — сервіс доступності Nightpost вимкнено. Скажи оператору увімкнути: " +
                    "Налаштування → Спеціальні можливості → Nightpost. Поки він вимкнений, " +
                    "жодної дії на екрані підтвердити не можу.",
                elapsed(),
            )

        // A blank render used to arrive as a successful empty answer, and the
        // model read that as "екран порожній" instead of "я нічого не побачив".
        if (rendered.isBlank()) {
            return ToolResult.Err(
                "screen_empty",
                "сервіс доступності відповів, але не віддав жодного елемента — так буває на захищених " +
                    "екранах (банк, пароль) і поки застосунок малюється. Наступний крок: device.wait 1с " +
                    "і повтори; порожнечу не трактуй як порожній екран.",
                elapsed(),
            )
        }

        return ToolResult.Ok(
            output = rendered,
            structured = buildJsonObject {
                put("chars", rendered.length)
                put("lines", rendered.lines().count { it.isNotBlank() })
                put("as_of_ms", takenAt)
                put("source", "сервіс доступності")
                put("confidence", "знімок на мить виклику")
            },
            durationMs = elapsed(),
        )
    }
}
