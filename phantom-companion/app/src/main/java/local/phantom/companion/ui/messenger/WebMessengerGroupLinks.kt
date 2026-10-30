package local.phantom.companion.ui.messenger

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerGroupJoin
import local.phantom.companion.core.net.peer.PeerGroupRoles
import local.phantom.companion.core.net.peer.PeerInvite
import local.phantom.companion.core.net.peer.PeerMessage

/**
 * `/messenger/group-links/…` — посилання-запрошення в гілку ([PeerGroupJoin]).
 *
 * `{id}` GET/POST/DELETE — побачити, створити наново, відкликати (акт LINK).
 * `open` — що за посиланням і де я щодо нього; `join` — попросити вступ.
 * [absorb] — відповідь телефона, що ділився: «так» стає актом Add.
 * `/messenger/group-requests/{id}` GET — заявки до мене; POST `{peer, accept}` — рішення.
 */
internal class WebMessengerGroupLinks(private val host: Host) {

    interface Host {
        fun me(): String
        fun log(): List<PeerMessage>
        fun myInvite(): PeerInvite.Invite?
        fun knows(peerId: String): Boolean
        suspend fun accept(invite: PeerInvite.Invite)
        fun send(toId: String, body: String): Boolean
        fun sendAct(group: PeerGroup.Group, act: PeerGroup.Act): Boolean
        fun newSecret(): String = PeerGroupJoin.newSecret()
        fun nameOf(peerId: String): String = ""
    }

    fun claims(seg: List<String>): Boolean = seg.size == 3 && (seg[1] == "group-links" || seg[1] == REQUESTS)

    suspend fun handle(method: String, seg: List<String>, body: String?): String = runCatching {
        when {
            seg[1] == REQUESTS && method == "GET" -> requests(seg[2])
            seg[1] == REQUESTS && method == "POST" -> decide(seg[2], body)
            seg[1] == REQUESTS -> fail(404, "not_found")
            seg[2] == "open" && method == "POST" -> open(body)
            seg[2] == "join" && method == "POST" -> join(body)
            method == "GET" -> current(seg[2])
            method == "POST" -> setLink(seg[2], host.newSecret())
            method == "DELETE" -> setLink(seg[2], "")
            else -> fail(404, "not_found")
        }
    }.getOrElse { fail(500, "group_link_failed") }

    fun absorb(message: PeerMessage) {
        val reply = PeerGroupJoin.reply(host.log(), host.me(), message) ?: return
        val group = reply.group
        val add = reply.add
        if (add != null && group != null && !host.sendAct(group, PeerGroup.Act.Add(add))) return
        host.send(reply.to, reply.answer)
    }

    private fun requests(groupId: String): String {
        val log = host.log()
        val state = PeerGroup.stateOf(log, groupId) ?: return fail(404, "no_group")
        val me = host.me()
        val may = PeerGroupRoles.may(state.roles, state.group.memberIds, me, PeerGroupRoles.Power.INVITE, state.cap)
        val list = if (may) PeerGroupJoin.pending(log, me, groupId) else emptyList()
        return ok(
            buildJsonObject {
                put("may", may)
                put("ask", state.setting(PeerGroup.Setting.APPROVE) == PeerGroup.ON)
                put(
                    "requests",
                    kotlinx.serialization.json.JsonArray(
                        list.map { r ->
                            buildJsonObject {
                                put("peer", r.peerId)
                                put("name", host.nameOf(r.peerId))
                                put("at", r.atMs)
                            }
                        },
                    ),
                )
            },
        )
    }

    private fun decide(groupId: String, body: String?): String {
        val o = runCatching { Json.parseToJsonElement(body ?: "").jsonObject }.getOrNull() ?: return fail(400, "bad_body")
        val peer = (o["peer"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return fail(400, "bad_body")
        val accept = (o["accept"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: return fail(400, "bad_body")
        val reply = PeerGroupJoin.decide(host.log(), host.me(), groupId, peer, accept) ?: return fail(404, "no_request")
        when (reply.verdict) {
            PeerGroupJoin.Verdict.NO_RIGHT -> return fail(403, "no_right")
            PeerGroupJoin.Verdict.FULL -> return fail(409, "full")
            else -> Unit
        }
        val group = reply.group
        val add = reply.add
        if (add != null && group != null && !host.sendAct(group, PeerGroup.Act.Add(add))) return fail(503, "unsent")
        if (!host.send(reply.to, reply.answer)) return fail(503, "unsent")
        return requests(groupId)
    }

    private fun current(groupId: String): String {
        val state = PeerGroup.stateOf(host.log(), groupId) ?: return fail(404, "no_group")
        val me = host.me()
        val may = PeerGroupRoles.may(state.roles, state.group.memberIds, me, PeerGroupRoles.Power.INVITE, state.cap)
        val secret = state.setting(PeerGroup.Setting.LINK)
        val invite = host.myInvite()
        val link = if (may && secret.isNotEmpty() && invite != null) {
            PeerGroupJoin.link(groupId, secret, state.group.memberIds.size, state.group.name, invite)
        } else {
            ""
        }
        return ok(
            buildJsonObject {
                put("may", may)
                put("link", link)
                put("ready", invite != null)
            },
        )
    }

    private fun setLink(groupId: String, secret: String): String {
        val log = host.log()
        val state = PeerGroup.stateOf(log, groupId) ?: return fail(404, "no_group")
        val act = PeerGroup.Act.Set(PeerGroup.Setting.LINK, secret)
        PeerGroup.refuse(log, groupId, host.me(), act)?.let { return fail(403, "no_right_" + it.name.lowercase(java.util.Locale.US)) }
        if (!host.sendAct(state.group, act)) return fail(503, "unsent")
        return current(groupId)
    }

    private fun linkOf(body: String?): PeerGroupJoin.Link? {
        val o = runCatching { Json.parseToJsonElement(body ?: "").jsonObject }.getOrNull() ?: return null
        val raw = (o["link"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return PeerGroupJoin.parse(raw)
    }

    private fun open(body: String?): String {
        val link = linkOf(body) ?: return fail(400, "bad_link")
        return ok(view(link))
    }

    private suspend fun join(body: String?): String {
        val link = linkOf(body) ?: return fail(400, "bad_link")
        val me = host.me()
        val now = PeerGroupJoin.standing(host.log(), me, link)
        if (now.stage == PeerGroupJoin.Stage.JOINED) return ok(view(link))
        if (me.isBlank() || host.myInvite() == null) return fail(503, "not_ready")
        if (link.sharer.peerId == me) return fail(409, "own_link")
        if (!host.knows(link.sharer.peerId)) host.accept(link.sharer)
        if (!host.send(link.sharer.peerId, PeerGroupJoin.encode(PeerGroupJoin.Frame.Ask(link.groupId, link.secret)))) {
            return fail(503, "unsent")
        }
        return ok(view(link))
    }

    private fun view(link: PeerGroupJoin.Link): JsonObject {
        val log = host.log()
        val standing = PeerGroupJoin.standing(log, host.me(), link)
        val members = PeerGroup.stateOf(log, link.groupId)?.group?.memberIds?.size
        return buildJsonObject {
            put("group_id", link.groupId)
            put("name", link.name)
            put("count", if (standing.stage == PeerGroupJoin.Stage.JOINED && members != null) members else link.count)
            put("sharer", link.sharer.name)
            put("stage", standing.stage.wire)
            standing.why?.let { put("why", it.wire) }
        }
    }

    private companion object {
        const val REQUESTS = "group-requests"
    }

    private fun ok(data: JsonObject) = WebMessengerReply.ok(data)

    private fun fail(status: Int, code: String) = WebMessengerReply.fail(status, code, "")
}
