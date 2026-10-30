package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk

/**
 * Emits a raw widget event that the UI layer converts into a
 * [StreamItem.Widget] for display in the conversation stream.
 *
 * No permission gate (foundational rendering primitive — no destructive surface).
 *
 * To keep `:core-tool` free of any UI dependency, the actual widget
 * creation is injected as [emitWidget]. AppContainer (Phase 1.E) binds
 * the real implementation (typically posting onto the StreamViewModel
 * event bus); tests inject a fake that records calls and returns a
 * canned widget ID.
 *
 * Args:
 * - `type`    — widget type string (e.g. `"chart"`, `"map"`, `"timer"`).
 * - `payload` — JSON object with the widget-specific payload.
 *
 * @param emitWidget Lambda that receives (type, payloadJson) and returns
 *   the assigned widget ID. The payload is serialised to a JSON string
 *   before passing so the lambda stays UI-framework-agnostic.
 */
class WidgetTool(
    private val emitWidget: (type: String, payload: String) -> String,
) : Tool {

    override val id = "widget"

    override val description =
        "Показати типізований віджет у стрічці (chart, map, timer, weather, progress, task-list, …). " +
            "type — тип віджета, payload — обʼєкт із його даними. Клади сюди лише числа, які справді " +
            "маєш: віджет намалює будь-що, що передаси, і оператор прочитає це як виміряне."

    override val argsSchema: JsonObject = buildJsonObject {
        put("type", kotlinx.serialization.json.JsonPrimitive("string"))
        put("payload", kotlinx.serialization.json.JsonPrimitive("object"))
    }

    override val permissions: Set<ToolPermission> = emptySet()

    override val risk: ToolRisk = ToolRisk.READ

    override val streaming: Boolean = false

    override val manifest = FeatureManifest(
        id = "tool.widget",
        tier = 1,
        displayName = "Віджет",
        erodesOperatorAgency = false,
        exfiltratesPrivate = false,
        actsWithoutConsent = false,
        abusableAgainstWeak = false,
        sunsetAfterDaysIdle = -1, // foundational — never auto-sunset
        surfaces = setOf(FeatureManifest.Surface.STREAM),
    )

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val startMs = System.currentTimeMillis()
        val elapsed: () -> Long = { System.currentTimeMillis() - startMs }

        val type = args["type"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.Err(
                "bad_args",
                "не вказано type — невідомо, який віджет малювати. Повтори виклик із type " +
                    "(chart, map, timer, weather, progress, task-list…).",
                elapsed(),
            )

        val payloadElement = args["payload"]
            ?: return ToolResult.Err(
                "bad_args",
                "немає payload — «$type» нічим наповнити, а порожній віджет гірший за речення. " +
                    "Повтори виклик із даними або відповідай прозою.",
                elapsed(),
            )

        val payloadObj = payloadElement as? JsonObject
            ?: return ToolResult.Err(
                "bad_args",
                "payload має бути обʼєктом JSON, а не рядком чи списком. Повтори виклик " +
                    "із {\"ключ\": значення}.",
                elapsed(),
            )

        if (payloadObj.isEmpty()) {
            return ToolResult.Err(
                "bad_args",
                "payload порожній — «$type» намалюється порожньою рамкою. Або поклади дані, " +
                    "або скажи відповідь словами.",
                elapsed(),
            )
        }

        val payloadJson = Json.encodeToString(JsonObject.serializer(), payloadObj)

        val widgetId = runCatching { emitWidget(type, payloadJson) }
            .getOrElse { e ->
                return ToolResult.Err(
                    "widget_error",
                    "стрічка не прийняла віджет «$type» (${e.message ?: "невідома причина"}). " +
                        "Наступний крок: подай ту саму відповідь текстом.",
                    elapsed(),
                )
            }

        return ToolResult.Ok(
            output = "Показав віджет «$type».",
            structured = buildJsonObject {
                put("widgetId", widgetId)
                put("type", type)
                put("fields", payloadObj.size)
            },
            durationMs = elapsed(),
        )
    }
}
