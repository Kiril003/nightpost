package local.phantom.companion.vm

import kotlinx.serialization.json.JsonObject

// ---------------------------------------------------------------------------
// PhantomLink channel event data classes
//
// These mirror the server→client wire shapes defined in
// docs/COMPANION_CONTRACT.md §3 (Channel inventory).  UI consumers may not
// exist yet for every channel — the flows are wired now so that any future
// screen can simply collect without re-touching the ViewModel or PhantomLink.
// ---------------------------------------------------------------------------

// ── context ─────────────────────────────────────────────────────────────────
// channel: "context" / type: "preferences_changed"
// payload: {changes:{...}, preferences:{...}}
data class ContextSnapshot(
    val changes: JsonObject?,
    val preferences: JsonObject?,
    val raw: JsonObject,
)

// ── familiar ─────────────────────────────────────────────────────────────────
// channel: "familiar" / type: "manifestation"
// payload: varies (manifestation pose/intent) — no fixed schema in contract
data class FamiliarSnapshot(
    val type: String,        // event type verb (e.g. "manifestation")
    val raw: JsonObject,
)

// ── pair ──────────────────────────────────────────────────────────────────────
// channel: "pair" / type: "claimed" | "revoked"
// claimed payload: {device_id, device_name, device_model, platform, user_id,
//                   profile_id, profile_display_name, paired_at}
// revoked payload: {device_id, reason}
data class PairEvent(
    val kind: String,           // "claimed" | "revoked"
    val deviceId: String?,
    val deviceName: String?,
    val userId: String?,
    val reason: String?,        // revoked only
    val raw: JsonObject,
)

// ── agent.stream ──────────────────────────────────────────────────────────────
// channel: "agent.stream" / types include:
//   proactive.cycle | proactive.pending_action | proactive.action_fired |
//   emotion.updated | task.report_acknowledged | inner-monologue events
data class AgentEvent(
    val kind: String,       // event type verb
    val text: String?,
    val emotion: String?,
    val raw: JsonObject,
)

// ── chat ─────────────────────────────────────────────────────────────────────
// channel: "chat" / type: "message" | "stream" | "message.proactive"
// message payload: {message, session_id}
// stream payload:  {message_id, delta, done, message?}
data class ChatEvent(
    val kind: String,           // "message" | "stream" | "message.proactive"
    val sessionId: String?,
    val messageId: String?,
    val delta: String?,
    val done: Boolean?,
    val raw: JsonObject,
)

// ── map ───────────────────────────────────────────────────────────────────────
// channel: "map" / type: any map mutation op verb
// ops: wardriving_update, plan_route, add_layer, add_marker, flyto, set_view,
//      enable_layer, disable_layer, isochrone, optimize_visit, snap_track,
//      explain_view, open_map, snapshot
data class MapEvent(
    val op: String,         // mutation op verb (the "type" field)
    val raw: JsonObject,
)

// ── companion_control ────────────────────────────────────────────────────────
// channel: "companion_control" / type: verb ("open_route" | "open_card" |
//   "navigate" | "focus_screen" | "lock_vault" | "ping")
// payload: {verb, payload, target_device_id?}
data class ControlVerb(
    val verb: String,
    val payload: JsonObject?,
    val targetDeviceId: String?,
    val raw: JsonObject,
)

// ── handoff ───────────────────────────────────────────────────────────────────
// channel: "handoff" / type: "created" | "accepted" | "rejected" | "cancelled"
// payload: HandoffOut shape
data class HandoffEvent(
    val kind: String,           // "created" | "accepted" | "rejected" | "cancelled"
    val handoffId: String?,
    val handoffKind: String?,   // map_route | vault_card | chat_thread | ...
    val status: String?,
    val raw: JsonObject,
)

// ── misc (catch-all for lower-priority channels) ──────────────────────────────
// Channels: profile_sync, drive, settings, background_events,
//           standing_orders, _meta — not modelled individually but never dropped.
data class MiscEvent(
    val channel: String,
    val type: String,
    val raw: JsonObject,
)

// ── messenger ────────────────────────────────────────────────────────────────
// channel: "messenger" / type: "message:new"
// data: MessageOut — те саме тіло, що віддає REST
// (routes_messenger.py `_message_out`), тож [raw] розбирається
// MessengerNodeApi.Message і другого опису форми не заводимо.
data class MessengerEvent(
    val kind: String,
    val conversationId: String?,
    val messageId: String?,
    val seq: Int?,
    val raw: JsonObject,
)
