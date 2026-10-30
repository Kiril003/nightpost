package local.phantom.companion.core.tool.impl

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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
 * Launches an Android Activity by package name, human app name (`query`),
 * explicit Intent action, or data URI. Extras are parsed from the `extras`
 * JsonObject (string/int/long/boolean auto-detected from JsonPrimitive).
 * Sensitive package families (banking, password managers) are blocklisted
 * and return [ToolResult.PermissionDenied].
 *
 * R5.1 — `query` resolves a human name («телеграм», "YouTube") to a package
 * through [AppNameResolver] over the injected [listApps] catalogue, and a
 * failed package lookup answers with near-miss suggestions instead of a
 * bare not_found, so the model self-corrects in one round.
 *
 * Requires [ToolPermission.LAUNCHER] in [ToolContext.approvedPermissions].
 *
 * @param applicationContext Application context used for startActivity.
 * @param listApps Installed-app catalogue (label → package), same source as
 *   [AppsListTool].
 */
class LaunchTool(
    private val applicationContext: Context,
    private val listApps: () -> List<Pair<String, String>> = { emptyList() },
) : Tool {

    override val id = "launch"

    override val description =
        "Відкрити застосунок: package (точний пакет) або query (людська назва — розпізнається " +
            "по списку встановлених), або надіслати намір через action/data/extras. " +
            "Банківські застосунки й сховища паролів заблоковані назавжди. " +
            "Якщо не певен, що застосунок є — спершу apps.list. " +
            "/ Launch an app by package, human name, or explicit Intent."

    override val argsSchema: JsonObject = buildJsonObject {
        put("package", kotlinx.serialization.json.JsonPrimitive("string?"))
        put("query", kotlinx.serialization.json.JsonPrimitive("string?"))
        put("action", kotlinx.serialization.json.JsonPrimitive("string?"))
        put("data", kotlinx.serialization.json.JsonPrimitive("string?"))
        put("extras", kotlinx.serialization.json.JsonPrimitive("object?"))
        put("category", kotlinx.serialization.json.JsonPrimitive("string?"))
    }

    override val permissions: Set<ToolPermission> = setOf(ToolPermission.LAUNCHER)

    override val risk: ToolRisk = ToolRisk.DEVICE

    override val streaming: Boolean = false

    override val manifest = FeatureManifest(
        id = "tool.launch",
        tier = 1,
        displayName = "Запуск застосунку",
        erodesOperatorAgency = false,
        exfiltratesPrivate = false,
        actsWithoutConsent = false,
        abusableAgainstWeak = false,
        sunsetAfterDaysIdle = 180,
        surfaces = setOf(FeatureManifest.Surface.STREAM),
    )

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val startMs = System.currentTimeMillis()
        val elapsed: () -> Long = { System.currentTimeMillis() - startMs }

        if (ToolPermission.LAUNCHER !in context.approvedPermissions) {
            // PermissionDenied carries no message; without this emit the card
            // showed «denied» and nothing an operator could act on.
            emit(
                ToolEvent.Notice(
                    "Не запускав: дозвіл «запуск застосунків» не наданий цьому виклику. " +
                        "Дозволь його оператору у списку дозволів інструментів — або відкрий застосунок вручну.",
                ),
            )
            return ToolResult.PermissionDenied(ToolPermission.LAUNCHER, elapsed())
        }

        val rawPkg = args["package"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val queryArg = args["query"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val actionStr = args["action"]?.jsonPrimitive?.content
        val dataStr = args["data"]?.jsonPrimitive?.content
        val extrasJson = args["extras"]?.jsonObject
        val categoryStr = args["category"]?.jsonPrimitive?.content

        // R5.1 — resolve human name → package. Also rescue a `package` arg
        // that is actually a label (models pass "YouTube" as package).
        val apps = runCatching { listApps() }.getOrDefault(emptyList())
        val pkg: String? = when {
            rawPkg != null && rawPkg.contains('.') -> rawPkg
            rawPkg != null -> AppNameResolver.resolve(rawPkg, apps)?.second ?: rawPkg
            queryArg != null ->
                AppNameResolver.resolve(queryArg, apps)?.second
                    ?: return ToolResult.Err(
                        "not_found",
                        notFoundMessage(queryArg, apps),
                        elapsed(),
                    )
            else -> null
        }

        // Blocklist check — applies to package arg and to resolved package
        if (pkg != null && isBlocklisted(pkg)) {
            emit(ToolEvent.Notice(blockedNotice(pkg)))
            return ToolResult.PermissionDenied(ToolPermission.LAUNCHER, elapsed())
        }

        val intent: Intent = if (pkg != null && actionStr == null && dataStr == null && extrasJson == null) {
            // Simple package launch
            applicationContext.packageManager?.getLaunchIntentForPackage(pkg)
                ?: return ToolResult.Err("not_found", notFoundMessage(pkg, apps), elapsed())
        } else {
            // Explicit intent
            Intent(actionStr ?: Intent.ACTION_VIEW).apply {
                if (dataStr != null) data = Uri.parse(dataStr)
                if (pkg != null) setPackage(pkg)
                extrasJson?.forEach { (key, value) ->
                    val prim = value as? JsonPrimitive ?: return@forEach
                    when {
                        prim.isString -> putExtra(key, prim.content)
                        prim.content.toBooleanStrictOrNull() != null ->
                            putExtra(key, prim.content.toBooleanStrictOrNull()!!)
                        prim.content.toLongOrNull() != null -> {
                            val l = prim.content.toLong()
                            if (l in Int.MIN_VALUE..Int.MAX_VALUE) putExtra(key, l.toInt())
                            else putExtra(key, l)
                        }
                        else -> putExtra(key, prim.content)
                    }
                }
                if (categoryStr != null) addCategory(categoryStr)
            }
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // Resolve final package name for block-check
        val resolvedPkg = intent.`package`
            ?: applicationContext.packageManager?.let { intent.resolveActivity(it)?.packageName }
            ?: pkg
            ?: "unknown"

        if (resolvedPkg != "unknown" && isBlocklisted(resolvedPkg)) {
            emit(ToolEvent.Notice(blockedNotice(resolvedPkg)))
            return ToolResult.PermissionDenied(ToolPermission.LAUNCHER, elapsed())
        }

        val label = apps.firstOrNull { it.second == resolvedPkg }?.first
        val resolvedFrom = when {
            queryArg != null -> "назва «$queryArg»"
            rawPkg != null && rawPkg != resolvedPkg -> "назва «$rawPkg»"
            rawPkg != null -> "пакет"
            else -> "намір (action/data)"
        }
        return try {
            applicationContext.startActivity(intent)
            ToolResult.Ok(
                // "launched" claimed more than startActivity proves: the intent
                // left, the system decides what comes up.
                output = "Відкрив ${label ?: resolvedPkg}" +
                    (if (label != null) " ($resolvedPkg)" else "") + ".",
                structured = buildJsonObject {
                    put("launched", true)
                    put("package", resolvedPkg)
                    label?.let { put("label", it) }
                    put("resolved_from", resolvedFrom)
                    actionStr?.let { put("action", it) }
                },
                durationMs = elapsed(),
            )
        } catch (e: android.content.ActivityNotFoundException) {
            ToolResult.Err(
                "not_found",
                "не відкрив: у системі немає застосунку, який приймає цей намір" +
                    (actionStr?.let { " ($it)" } ?: "") +
                    ". Потрібен встановлений застосунок для цієї дії — виклич apps.list, щоб побачити наявні.",
                elapsed(),
            )
        } catch (e: Exception) {
            ToolResult.Err(
                "launch_error",
                "не відкрив $resolvedPkg: ${e.message ?: "система відхилила запуск"}. " +
                    "Спробуй запустити застосунок вручну.",
                elapsed(),
            )
        }
    }

    /** A bank or vault refusal must name itself; «denied» reads as a missing
     *  permission the operator would then try, pointlessly, to grant. */
    private fun blockedNotice(pkg: String): String =
        "Не відкривав $pkg: банківські застосунки й сховища паролів заблоковані назавжди — " +
            "я не торкаюсь грошей і ключів. Відкрий його сам."

    private fun notFoundMessage(wanted: String, apps: List<Pair<String, String>>): String {
        val hints = AppNameResolver.suggest(wanted, apps, max = 5)
        val hintText = if (hints.isEmpty()) {
            " Викличи apps.list щоб побачити встановлені застосунки."
        } else {
            " Схожі встановлені: " + hints.joinToString("; ") { "${it.first}=${it.second}" }
        }
        return "застосунок «$wanted» не знайдено.$hintText"
    }

    private fun isBlocklisted(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return BANK_PATTERNS.any { lower.contains(it) } ||
            PASSWORD_MANAGER_PATTERNS.any { lower.contains(it) }
    }

    companion object {
        /**
         * Package-name substrings that identify banking apps.
         * Extend as new apps are discovered — keep lowercase.
         */
        private val BANK_PATTERNS = listOf(
            "privatbank", "monobank", "oschadbank", "abank",
            "revolut", "wise.transferwise", "paypal",
            "com.chase", "com.wellsfargo", "com.bankofamerica",
            "com.citibank", "com.usbank", "hsbc", "barclays",
            "lloyds", "natwest", "santander",
        )

        /**
         * Package-name substrings that identify password managers.
         */
        private val PASSWORD_MANAGER_PATTERNS = listOf(
            "lastpass", "onepassword", "1password",
            "bitwarden", "keepass", "dashlane",
            "nordpass", "keeper", "roboform",
        )
    }
}
