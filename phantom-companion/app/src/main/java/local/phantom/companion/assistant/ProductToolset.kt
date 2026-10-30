package local.phantom.companion.assistant

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk

enum class Approval { READ, LOCAL_WRITE, PROPOSAL }

class ProductToolset(
    registry: List<Tool>,
    sources: ProductSources = ProductSources(),
) {
    private val byRegistryId = registry.associateBy { it.id }
    private val readTools = ProductReadTools.all(sources).associateBy { it.id }

    val tools: List<Tool> = SPECS.map { spec ->
        val base = when {
            spec.a6 -> readTools.getValue(spec.cardId)
            spec.backingId != null -> byRegistryId[spec.backingId]
                ?: StubTool(spec.cardId, spec.description, spec.fallbackSchema, spec.approval)
            else -> StubTool(spec.cardId, spec.description, spec.fallbackSchema, spec.approval)
        }
        CardToolWrapper(
            backing = base,
            cardId = spec.cardId,
            enDescription = spec.description,
            approval = spec.approval,
        )
    }

    private val byCardId = tools.associateBy { it.id }

    val offered: List<Tool> = SPECS.mapNotNull { spec ->
        if (spec.a6 || (spec.backingId != null && spec.backingId in byRegistryId)) {
            byCardId[spec.cardId]
        } else {
            null
        }
    }

    private val offeredByCardId = offered.associateBy { it.id }
    private val offeredByBackingId = offered.mapNotNull { tool ->
        val backing = SPEC_BY_ID[tool.id]?.backingId ?: return@mapNotNull null
        backing to tool
    }.toMap()

    fun get(id: String): Tool? = byCardId[id]

    fun resolve(name: String): Tool? = offeredByCardId[name] ?: offeredByBackingId[name]

    fun approvalOf(id: String): Approval? = SPEC_BY_ID[id]?.approval

    fun backingId(id: String): String? = SPEC_BY_ID[id]?.backingId

    private data class Spec(
        val cardId: String,
        val backingId: String?,
        val a6: Boolean,
        val approval: Approval,
        val description: String,
        val fallbackSchema: JsonObject,
    )

    private class CardToolWrapper(
        private val backing: Tool,
        private val cardId: String,
        private val enDescription: String,
        private val approval: Approval,
    ) : Tool by backing {
        override val id: String get() = cardId
        override val description: String get() = enDescription

        override suspend fun execute(
            args: JsonObject,
            context: ToolContext,
            emit: suspend (ToolEvent) -> Unit,
        ): ToolResult {
            val result = backing.execute(args, context, emit)
            if (approval != Approval.PROPOSAL) return result
            return when (result) {
                is ToolResult.Ok -> {
                    val existing = result.structured
                    val withDraft = if (existing != null && "kind" in existing) {
                        existing
                    } else {
                        buildJsonObject {
                            put("kind", "draft")
                            existing?.forEach { (k, v) -> put(k, v) }
                        }
                    }
                    result.copy(structured = withDraft)
                }
                is ToolResult.Err,
                is ToolResult.PermissionDenied,
                is ToolResult.Cancelled,
                -> result
            }
        }
    }

    private class StubTool(
        override val id: String,
        override val description: String,
        override val argsSchema: JsonObject,
        approval: Approval,
    ) : Tool {
        override val permissions: Set<ToolPermission> = emptySet()
        override val risk: ToolRisk = when (approval) {
            Approval.READ, Approval.LOCAL_WRITE -> ToolRisk.READ
            Approval.PROPOSAL -> ToolRisk.OUTWARD
        }
        override val streaming: Boolean = false
        override val manifest: FeatureManifest = FeatureManifest(
            id = "tool.$id",
            tier = 1,
            displayName = id,
        )

        override suspend fun execute(
            args: JsonObject,
            context: ToolContext,
            emit: suspend (ToolEvent) -> Unit,
        ): ToolResult = ToolResult.Err(
            code = "unavailable",
            message = "$id is currently unavailable.",
            durationMs = 0L,
        )
    }

    companion object {
        private fun schema(vararg pairs: Pair<String, String>): JsonObject =
            JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

        private val SPECS: List<Spec> = listOf(
            Spec(
                cardId = "day.today",
                backingId = null,
                a6 = true,
                approval = Approval.READ,
                description = "Read today's agenda items (reminders, promises, plans, calendar events).",
                fallbackSchema = schema(),
            ),
            Spec(
                cardId = "calendar.list",
                backingId = "calendar.list",
                a6 = false,
                approval = Approval.READ,
                description = "List upcoming local calendar events.",
                fallbackSchema = schema("days" to "number?"),
            ),
            Spec(
                cardId = "task.list",
                backingId = "task.list",
                a6 = false,
                approval = Approval.READ,
                description = "List current tasks and checklist items.",
                fallbackSchema = schema("includeDone" to "boolean?"),
            ),
            Spec(
                cardId = "reminder.list",
                backingId = "reminder.list",
                a6 = false,
                approval = Approval.READ,
                description = "List pending time-based reminders.",
                fallbackSchema = schema("limit" to "number?"),
            ),
            Spec(
                cardId = "memory.list",
                backingId = "memory.list",
                a6 = false,
                approval = Approval.READ,
                description = "List saved personal memory facts.",
                fallbackSchema = schema("query" to "string?"),
            ),
            Spec(
                cardId = "people.list",
                backingId = "people.list",
                a6 = false,
                approval = Approval.READ,
                description = "List people in the operator's contact roster.",
                fallbackSchema = schema("query" to "string?"),
            ),
            Spec(
                cardId = "circles.list",
                backingId = null,
                a6 = true,
                approval = Approval.READ,
                description = "List people and the social circle each belongs to.",
                fallbackSchema = schema(),
            ),
            Spec(
                cardId = "place.search",
                backingId = "geo.search",
                a6 = false,
                approval = Approval.READ,
                description = "Search places or addresses by name.",
                fallbackSchema = schema("query" to "string"),
            ),
            Spec(
                cardId = "place.nearby",
                backingId = "geo.nearby",
                a6 = false,
                approval = Approval.READ,
                description = "Find nearby places around the current location.",
                fallbackSchema = schema("category" to "string", "radiusM" to "number?"),
            ),
            Spec(
                cardId = "where.people",
                backingId = null,
                a6 = true,
                approval = Approval.READ,
                description = "Read coarse sharing level and freshness for people sharing location.",
                fallbackSchema = schema(),
            ),
            Spec(
                cardId = "wallet.balance",
                backingId = null,
                a6 = true,
                approval = Approval.READ,
                description = "Read own short wallet address and coin balances.",
                fallbackSchema = schema(),
            ),
            Spec(
                cardId = "map.viewport",
                backingId = null,
                a6 = true,
                approval = Approval.READ,
                description = "Read the active map viewport summary when the map is open.",
                fallbackSchema = schema(),
            ),
            Spec(
                cardId = "chat.unread_summary",
                backingId = null,
                a6 = false,
                approval = Approval.READ,
                description = "Summarize unread chat messages across conversations.",
                fallbackSchema = schema("limit" to "number?"),
            ),
            Spec(
                cardId = "reminder.set",
                backingId = "reminder.add",
                a6 = false,
                approval = Approval.LOCAL_WRITE,
                description = "Set a local reminder at a scheduled time.",
                fallbackSchema = schema("text" to "string", "triggerAtMs" to "number"),
            ),
            Spec(
                cardId = "reminder.cancel",
                backingId = "reminder.remove",
                a6 = false,
                approval = Approval.LOCAL_WRITE,
                description = "Cancel a pending local reminder by id.",
                fallbackSchema = schema("id" to "string"),
            ),
            Spec(
                cardId = "calendar.add",
                backingId = "calendar.add",
                a6 = false,
                approval = Approval.LOCAL_WRITE,
                description = "Add an event to the local calendar.",
                fallbackSchema = schema("title" to "string", "startAtMs" to "number"),
            ),
            Spec(
                cardId = "task.add",
                backingId = "task.add",
                a6 = false,
                approval = Approval.LOCAL_WRITE,
                description = "Add a new task to the local task list.",
                fallbackSchema = schema("title" to "string"),
            ),
            Spec(
                cardId = "memory.remember",
                backingId = "memory.save",
                a6 = false,
                approval = Approval.LOCAL_WRITE,
                description = "Save a fact to personal memory.",
                fallbackSchema = schema("fact" to "string"),
            ),
            Spec(
                cardId = "memory.forget",
                backingId = "memory.forget",
                a6 = false,
                approval = Approval.LOCAL_WRITE,
                description = "Remove a saved fact from personal memory.",
                fallbackSchema = schema("id" to "string"),
            ),
            Spec(
                cardId = "meetup.create",
                backingId = "meetup.create",
                a6 = false,
                approval = Approval.PROPOSAL,
                description = "Open a meetup invitation draft for operator confirmation.",
                fallbackSchema = schema("title" to "string?", "who" to "string?"),
            ),
            Spec(
                cardId = "bill.split",
                backingId = "bill.split",
                a6 = false,
                approval = Approval.PROPOSAL,
                description = "Open a bill-split draft for operator confirmation.",
                fallbackSchema = schema("amount" to "string", "title" to "string?"),
            ),
            Spec(
                cardId = "people.draft",
                backingId = "people.draft",
                a6 = false,
                approval = Approval.PROPOSAL,
                description = "Prepare a message draft to a contact without sending it.",
                fallbackSchema = schema("person" to "string", "text" to "string"),
            ),
            Spec(
                cardId = "pay.draft",
                backingId = null,
                a6 = false,
                approval = Approval.PROPOSAL,
                description = "Prepare a payment transfer draft for operator signature.",
                fallbackSchema = schema("recipient" to "string", "amount" to "string", "coin" to "string?"),
            ),
            Spec(
                cardId = "request.draft",
                backingId = null,
                a6 = false,
                approval = Approval.PROPOSAL,
                description = "Prepare a payment request draft to share with a contact.",
                fallbackSchema = schema("amount" to "string", "coin" to "string?", "note" to "string?"),
            ),
            Spec(
                cardId = "share.location.draft",
                backingId = null,
                a6 = false,
                approval = Approval.PROPOSAL,
                description = "Prepare a location-sharing draft for operator approval.",
                fallbackSchema = schema("person" to "string", "precision" to "string?"),
            ),
        )

        private val SPEC_BY_ID: Map<String, Spec> = SPECS.associateBy { it.cardId }

        val IDS: List<String> = SPECS.map { it.cardId }
    }
}
