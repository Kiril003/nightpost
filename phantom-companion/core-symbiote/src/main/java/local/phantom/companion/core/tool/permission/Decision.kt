package local.phantom.companion.core.tool.permission

/**
 * Persisted decision for a given tool ID. Controls how [ToolPermissionGate]
 * behaves on subsequent calls without prompting the operator again.
 *
 * - [ALWAYS] — дозволено назавжди; більше не питає.
 * - [NEVER]  — заборонено назавжди; ворота повертають [GateOutcome.Denied].
 * - [ASK]    — типове, незбережене. Для [local.phantom.companion.core.tool.ToolRisk.READ]
 *   означає «виконуй», для решти — «спитай оператора».
 * - [ONCE]   — «цього разу»: виконати й НЕ зберігати рішення.
 */
enum class Decision {
    ALWAYS,
    NEVER,
    ASK,
    ONCE,
}
