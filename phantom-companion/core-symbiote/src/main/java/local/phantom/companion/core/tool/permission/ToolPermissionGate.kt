package local.phantom.companion.core.tool.permission

import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolRisk

/**
 * Ворота між моделлю і [Tool.execute]. Судять за [Tool.risk], не за дозволами.
 *
 * Правила:
 * - Коли діє «Зупинити все» ([SymbioteStop] / `BodyLadder.stopped`), кожен
 *   інструмент (навіть [ToolRisk.READ] і збережене [Decision.ALWAYS]) відмовляє.
 * - Читання не питає, дія з наслідком питає.
 * - Для `http`: не-GET запити завжди питають (`askEveryCall = true`), а
 *   [Decision.ALWAYS] привʼязується лише до пари `host + GET` (без піддоменів).
 *
 * @param ask `null` означає «нема кому спитати» (безголовий агент без Стрічки).
 *   Тоді ризиковий інструмент ВІДМОВЛЯЄТЬСЯ, а не виконується мовчки; збережене
 *   [Decision.ALWAYS] працює й без питальника.
 */
class ToolPermissionGate(
    private val store: ToolPermissionStore,
    private val ask: (suspend (ToolAsk) -> Decision)? = null,
    private val isStopped: () -> Boolean = { SymbioteStop.stopped.value },
) {

    private fun halted(): Boolean = isStopped() || SymbioteStop.stopped.value

    suspend fun check(tool: Tool, argsPreview: String): GateOutcome {
        if (halted()) return denied(tool, "усе зупинено на телефоні")

        val stored = store.decision(tool.id)
        if (stored == Decision.NEVER) return denied(tool, "заборонений оператором")

        val httpTarget = if (tool.id == HTTP_TOOL_ID) parseHttpTarget(argsPreview) else null
        if (httpTarget != null) {
            if (!httpTarget.validUrl || httpTarget.host.isNullOrBlank()) {
                return denied(tool, "некоректна адреса")
            }
            val hostNeverKey = httpTarget.scopedHostNeverKey
            val grantKey = httpTarget.scopedGrantKey
            if ((hostNeverKey != null && store.decision(hostNeverKey) == Decision.NEVER) ||
                (grantKey != null && store.decision(grantKey) == Decision.NEVER)
            ) {
                return denied(tool, "заборонений оператором")
            }
        }

        if (!tool.risk.needsConsent) return GateOutcome.Granted(tool.permissions, ConsentPath.READ)

        val effectiveAskEveryCall = if (httpTarget != null) {
            tool.askEveryCall || !httpTarget.isGetWithHost
        } else {
            tool.askEveryCall
        }

        val grantKey: String? = if (httpTarget != null) {
            httpTarget.scopedGrantKey
        } else {
            tool.id
        }

        if (!effectiveAskEveryCall && grantKey != null && store.decision(grantKey) == Decision.ALWAYS) {
            return GateOutcome.Granted(tool.permissions, ConsentPath.ALWAYS)
        }

        val asker = ask ?: return denied(
            tool,
            "діє на ${target(tool.risk)}, а спитати нікого — виклик прийшов без Стрічки",
        )

        val schemaKeys = tool.argsSchema.keys.takeIf { it.isNotEmpty() }
        val decision = asker(
            ToolAsk(
                toolId = tool.id,
                description = tool.description,
                risk = tool.risk,
                argsPreview = argsPreview,
                askEveryCall = effectiveAskEveryCall,
                allowedKeys = schemaKeys,
            ),
        )

        if (halted()) return denied(tool, "усе зупинено на телефоні")

        return when (decision) {
            Decision.ONCE -> GateOutcome.Granted(tool.permissions, ConsentPath.ONCE)
            Decision.ALWAYS -> if (effectiveAskEveryCall || grantKey == null) {
                GateOutcome.Granted(tool.permissions, ConsentPath.ONCE)
            } else {
                store.setDecision(grantKey, Decision.ALWAYS)
                GateOutcome.Granted(tool.permissions, ConsentPath.ALWAYS)
            }
            Decision.NEVER -> {
                val hostNever = httpTarget?.scopedHostNeverKey
                if (httpTarget != null && hostNever != null) {
                    store.setDecision(hostNever, Decision.NEVER)
                    httpTarget.scopedGrantKey?.let { store.setDecision(it, Decision.NEVER) }
                } else {
                    store.setDecision(tool.id, Decision.NEVER)
                }
                denied(tool, "оператор заборонив назавжди")
            }
            Decision.ASK -> denied(tool, "оператор не дозволив")
        }
    }

    private fun target(risk: ToolRisk): String = when (risk) {
        ToolRisk.DEVICE -> "пристрій"
        ToolRisk.OUTWARD -> "світ поза пристроєм"
        ToolRisk.READ -> "нічого"
    }

    private fun denied(tool: Tool, why: String) = GateOutcome.Denied(
        reason = "«${tool.id}» — $why, я його не запускав. Наступний крок: зробити те саме " +
            "іншим інструментом, або хай оператор змінить рішення в Налаштуваннях → Інструменти.",
    )

    companion object {
        private const val HTTP_TOOL_ID = "http"

        internal fun parseHttpTarget(argsPreview: String): HttpTarget.CallTarget =
            HttpTarget.parseFromArgsPreview(argsPreview)

        internal fun extractHost(urlString: String): String? =
            HttpTarget.extractHost(urlString)
    }
}
