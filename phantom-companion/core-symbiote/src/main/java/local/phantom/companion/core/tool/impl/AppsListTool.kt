package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.JsonArray
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
 * R5.1 — the model's app catalogue. Lists / searches the operator's
 * launchable apps so `launch` and `open_app` get an EXACT package name
 * instead of a hallucinated one. The `[APPS:]` context header caps at
 * 50 entries; this tool serves the full list on demand and filters by
 * label or package substring.
 *
 * The provider is injected as a plain lambda (label → package pairs)
 * so this module stays independent of `:core-ai`'s InstalledApp type.
 */
class AppsListTool(
    private val listApps: () -> List<Pair<String, String>>,
) : Tool {

    override val id = "apps.list"

    override val description =
        "List apps installed on this phone (label + exact package name). " +
            "Optional query filters by name/package substring. " +
            "Call this FIRST when you need a package name for launch/open_app " +
            "and are not 100% sure it exists."

    override val argsSchema: JsonObject = buildJsonObject {
        put("query", JsonPrimitive("string?"))
        put("limit", JsonPrimitive("number?"))
    }

    override val permissions: Set<ToolPermission> = setOf(ToolPermission.LAUNCHER)

    override val risk: ToolRisk = ToolRisk.READ

    override val streaming: Boolean = false

    override val manifest = FeatureManifest(
        id = "tool.apps.list",
        tier = 1,
        displayName = "Застосунки",
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
            return ToolResult.PermissionDenied(ToolPermission.LAUNCHER, elapsed())
        }

        val query = args["query"]?.jsonPrimitive?.content?.trim()?.lowercase().orEmpty()
        val limit = (args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: DEFAULT_LIMIT)
            .coerceIn(1, MAX_LIMIT)

        // A thrown catalogue read used to come back as an empty list, so a broken
        // PackageManager was reported to the model as «this phone has no apps».
        val all = runCatching { listApps() }.getOrElse { e ->
            return ToolResult.Err(
                "catalogue_error",
                "не зміг прочитати перелік застосунків (${e.message ?: "збій PackageManager"}). " +
                    "Це НЕ означає, що застосунків нема — не роби такого висновку; спробуй ще раз " +
                    "або попроси оператора назвати застосунок словами.",
                elapsed(),
            )
        }
        if (all.isEmpty()) {
            return ToolResult.Err(
                "no_apps",
                "перелік застосунків порожній — package-visibility зламано або список ще не прогрівся. " +
                    "Не вигадуй назву пакета: спитай оператора, як застосунок називається на екрані.",
                elapsed(),
            )
        }

        val matched = if (query.isEmpty()) all else all.filter { (label, pkg) ->
            label.lowercase().contains(query) || pkg.lowercase().contains(query)
        }

        if (matched.isEmpty()) {
            // Near-misses help the model self-correct without another round.
            val hints = AppNameResolver.suggest(query, all, max = 5)
            val hintText = if (hints.isEmpty()) {
                " Схожого теж нема — виклич apps.list без query і подивись перелік."
            } else {
                " Схожі: " + hints.joinToString("; ") { "${it.first}=${it.second}" } +
                    " — якщо це воно, візьми пакет звідси."
            }
            return ToolResult.Err(
                "not_found",
                "«$query» немає серед ${all.size} встановлених застосунків.$hintText",
                elapsed(),
            )
        }

        val shown = matched.take(limit)
        val truncated = matched.size > shown.size
        val head = if (query.isEmpty()) {
            "Застосунків: ${all.size}, показано ${shown.size}."
        } else {
            "За «$query»: ${matched.size}, показано ${shown.size}."
        }
        val lines = shown.joinToString("\n") { (label, pkg) -> "$label = $pkg" }
        val suffix = if (truncated) "\n… і ще ${matched.size - shown.size} (звузь query)" else ""

        return ToolResult.Ok(
            output = "$head\n$lines$suffix",
            structured = buildJsonObject {
                put("total", matched.size)
                put("installed", all.size)
                put("returned", shown.size)
                put("truncated", truncated)
                if (query.isNotEmpty()) put("query", query)
                put(
                    "apps",
                    JsonArray(
                        shown.map { (label, pkg) ->
                            buildJsonObject {
                                put("label", label)
                                put("package", pkg)
                            }
                        }
                    ),
                )
            },
            durationMs = elapsed(),
        )
    }

    companion object {
        const val DEFAULT_LIMIT = 40
        const val MAX_LIMIT = 200
    }
}

/**
 * Shared label→package resolution used by [AppsListTool] hints and
 * [LaunchTool]'s human-name `query` arg. Match ladder: exact label →
 * label startsWith → label contains → package contains. All matching is
 * case-insensitive; ties keep provider order (alphabetical by label).
 */
object AppNameResolver {

    /** Best single match for [query], or null. */
    fun resolve(query: String, apps: List<Pair<String, String>>): Pair<String, String>? {
        val q = query.trim().lowercase()
        if (q.isEmpty() || apps.isEmpty()) return null
        apps.firstOrNull { it.first.lowercase() == q }?.let { return it }
        apps.firstOrNull { it.first.lowercase().startsWith(q) }?.let { return it }
        apps.firstOrNull { it.first.lowercase().contains(q) }?.let { return it }
        return apps.firstOrNull { it.second.lowercase().contains(q) }
    }

    /** Loose suggestions for a failed lookup — any token overlap counts. */
    fun suggest(
        query: String,
        apps: List<Pair<String, String>>,
        max: Int,
    ): List<Pair<String, String>> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val tokens = q.split(Regex("[\\s._-]+")).filter { it.length >= 3 }
        if (tokens.isEmpty()) return emptyList()
        return apps.filter { (label, pkg) ->
            val hay = (label + " " + pkg).lowercase()
            tokens.any { hay.contains(it) }
        }.take(max)
    }
}
