package local.phantom.companion.core.tool.impl

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
 * Tier 23 — parks an unformed thought into the operator's Mental Park.
 *
 * No permission gate (cognitive aid, no destructive surface).
 *
 * To avoid a dependency from `:core-tool` onto `:core-offload`, the actual
 * [MentalPark.add] call is injected as [parkAdd]. AppContainer (Phase 1.E)
 * binds the real implementation; tests inject a fake that returns a canned ID.
 *
 * @param parkAdd Lambda that stores the item and returns its assigned ID.
 *   Signature: `(text: String, anchor: String?, category: String?) -> String`
 */
class MentalParkTool(
    private val parkAdd: (text: String, anchor: String?, category: String?) -> String,
) : Tool {

    override val id = "mental_park"

    override val description =
        "Відкласти несформовану думку в Ментальний парк: вона дозріє й повернеться в спокійну хвилину. " +
            "text — сама думка, anchor — до чого привʼязана, category — до якої теми. " +
            "/ Park an unformed thought in the Mental Park."

    override val argsSchema: JsonObject = buildJsonObject {
        put("text", kotlinx.serialization.json.JsonPrimitive("string"))
        put("anchor", kotlinx.serialization.json.JsonPrimitive("string?"))
        put("category", kotlinx.serialization.json.JsonPrimitive("string?"))
    }

    override val permissions: Set<ToolPermission> = emptySet()

    override val risk: ToolRisk = ToolRisk.READ

    override val streaming: Boolean = false

    override val manifest = FeatureManifest(
        id = "tool.mental_park",
        tier = 23,
        displayName = "Ментальний парк",
        erodesOperatorAgency = false,
        exfiltratesPrivate = false,
        actsWithoutConsent = false,
        abusableAgainstWeak = false,
        sunsetAfterDaysIdle = 365,
        surfaces = setOf(FeatureManifest.Surface.STREAM),
    )

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val startMs = System.currentTimeMillis()
        val elapsed: () -> Long = { System.currentTimeMillis() - startMs }

        val text = args["text"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return ToolResult.Err(
                "bad_args",
                "нічого не відклав: не передано text. Скажи саму думку — порожню я не зберігаю.",
                elapsed(),
            )

        val anchor = args["anchor"]?.jsonPrimitive?.content
        val category = args["category"]?.jsonPrimitive?.content

        val id = runCatching { parkAdd(text, anchor, category) }
            .getOrElse { e ->
                return ToolResult.Err(
                    "park_error",
                    "думку не відклав: сховище парку відмовило (${e.message ?: "причина невідома"}). " +
                        "Вона НЕ збережена — повтори або запиши деінде.",
                    elapsed(),
                )
            }

        val preview = text.take(60) + if (text.length > 60) "…" else ""
        return ToolResult.Ok(
            output = "Відклав у Ментальний парк: «$preview». Повернеться, коли буде спокійніше.",
            structured = buildJsonObject {
                put("parked", true)
                put("id", id)
                put("preview", preview)
                put("chars", text.length)
                if (anchor != null) put("anchor", anchor)
                if (category != null) put("category", category)
            },
            durationMs = elapsed(),
        )
    }
}
