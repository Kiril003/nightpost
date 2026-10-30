package local.phantom.companion.core.tool.impl

import kotlinx.coroutines.delay
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

/**
 * R5.1 — lets a long, sequential mission pace itself. The model calls
 * `device.wait` to pause between steps (wait for an app to finish loading,
 * for a download, for a scheduled moment) without burning a tool-chain
 * turn on a busy-loop. Streams progress so the operator sees PHANTOM is
 * deliberately waiting, and is cancellable — the operator's «скасувати»
 * cancels the launching scope and the delay unwinds cleanly.
 *
 * Capped at [MAX_SECONDS] per call (chain calls for longer). Needs no
 * permission — it only sleeps.
 */
class WaitTool : Tool {

    override val id = "device.wait"

    override val description =
        "Пауза на N секунд перед наступним кроком (поки завантажиться екран, докачається файл, " +
            "настане момент). Максимум $MAX_SECONDS с за виклик — більше проси повторним викликом; " +
            "інструмент сам скаже, якщо обрізав. Став між діями на пристрої замість вгадування часу."

    override val argsSchema: JsonObject = buildJsonObject {
        put("seconds", JsonPrimitive("number"))
        put("reason", JsonPrimitive("string?"))
    }

    override val permissions: Set<ToolPermission> = emptySet()

    override val risk: ToolRisk = ToolRisk.READ

    override val streaming: Boolean = true

    override val manifest = FeatureManifest(
        id = "tool.device.wait",
        tier = 1,
        displayName = "Очікування",
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

        val requested = args["seconds"]?.jsonPrimitive?.content?.toDoubleOrNull()
            ?: return ToolResult.Err(
                "bad_args",
                "не вказано seconds — скільки чекати. Повтори виклик із числом секунд (макс. $MAX_SECONDS за раз).",
                elapsed(),
            )
        if (requested <= 0.0) {
            return ToolResult.Err(
                "bad_args",
                "seconds=$requested — пауза має бути більшою за нуль. " +
                    "Якщо чекати не треба, просто не викликай device.wait.",
                elapsed(),
            )
        }
        val seconds = requested.coerceAtMost(MAX_SECONDS.toDouble())
        val reason = args["reason"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

        val totalMs = (seconds * 1000).toLong()
        val label = reason?.let { "чекаю: $it" } ?: "чекаю"

        // Tick roughly once a second so the trace animates and cancellation
        // is responsive; the final partial tick tops up to totalMs.
        var slept = 0L
        while (slept < totalMs) {
            val chunk = minOf(TICK_MS, totalMs - slept)
            delay(chunk)
            slept += chunk
            emit(ToolEvent.Progress(slept.toFloat() / totalMs, "$label · ${slept / 1000}/${totalMs / 1000}с"))
        }

        // A request over the cap used to be trimmed in silence: the model asked
        // for 600s, got 300s, and was told «почекав» with no number to compare.
        val capped = seconds < requested
        val capNote = if (capped) {
            " Просили ${"%.0f".format(requested)}с, але за раз даю не більше $MAX_SECONDS — " +
                "виклич ще раз, якщо треба довше."
        } else {
            ""
        }
        return ToolResult.Ok(
            output = "Почекав ${"%.0f".format(seconds)}с" + (reason?.let { " ($it)" } ?: "") + "." + capNote,
            structured = buildJsonObject {
                put("waitedSeconds", seconds)
                put("requestedSeconds", requested)
                put("capped", capped)
                put("maxSeconds", MAX_SECONDS)
                if (reason != null) put("reason", reason)
            },
            durationMs = elapsed(),
        )
    }

    companion object {
        const val MAX_SECONDS = 300
        const val TICK_MS = 1_000L
    }
}
