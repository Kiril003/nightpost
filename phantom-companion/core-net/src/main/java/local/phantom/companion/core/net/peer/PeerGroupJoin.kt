package local.phantom.companion.core.net.peer

/**
 * Посилання-запрошення в гілку: `https://phantom-os.dev/g/#<код>`.
 *
 * Сервера, який знав би гілку за кодом, немає, тож код несе все сам: id гілки,
 * секрет з акту [PeerGroup.Setting.LINK], склад на мить копіювання, назву й
 * безадресний код того, хто ділився. Хто відкрив, приймає цей код (знайомство
 * в зворотний бік іде звичним насосом) і просить кадром [Frame.Ask]. Вирішує
 * телефон, що ділився, тим самим суддею, що й решта актів: секрет мусить
 * збігатися з чинним LINK, а в нього самого мусить лишатися право INVITE.
 * «Так» — це звичайний [PeerGroup.Act.Add]; відповідь [Frame.Answer] каже
 * людині, чим скінчилось, і саме вона дає чесну відмову після «відкликати».
 *
 * Із [PeerGroup.Setting.APPROVE] вступ стає заявкою: відповідь [Verdict.PENDING]
 * і є заявкою в журналі того, хто ділився ([pending]); [decide] дає Add і «так»
 * або [Verdict.DECLINED].
 */
object PeerGroupJoin {

    const val TAG = "PH-GJN:"
    const val WEB_PATH = "/g/"
    const val SECRET_LENGTH = 16

    private const val SEP = ':'
    private const val PART = '.'
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

    data class Link(
        val groupId: String,
        val secret: String,
        /** Склад на мить копіювання — знімок, а не живе число. */
        val count: Int,
        val name: String,
        val sharer: PeerInvite.Invite,
    )

    enum class Verdict(val wire: String) {
        JOINED("in"),
        REVOKED("gone"),
        NO_RIGHT("noright"),
        FULL("full"),
        PENDING("wait"),
        DECLINED("no"),
        ;

        companion object {
            fun of(wire: String): Verdict? = entries.firstOrNull { it.wire == wire }
        }
    }

    sealed interface Frame {
        val groupId: String
        val secret: String

        data class Ask(override val groupId: String, override val secret: String) : Frame
        data class Answer(override val groupId: String, override val secret: String, val verdict: Verdict) : Frame
    }

    fun newSecret(random: (Int) -> Int = { java.security.SecureRandom().nextInt(it) }): String =
        (1..SECRET_LENGTH).joinToString("") { ALPHABET[random(ALPHABET.length).coerceIn(0, ALPHABET.length - 1)].toString() }

    /** Адреса без мережевої адреси того, хто ділився: посилання ходить світом. */
    fun link(groupId: String, secret: String, count: Int, name: String, sharer: PeerInvite.Invite): String {
        val bare = sharer.copy(host = "", port = 0)
        val code = listOf(groupId, secret, count.toString(), b64(PeerName.cap(name)), PeerInvite.encode(bare))
            .joinToString(PART.toString())
        return "https://${PeerInvite.WEB_HOST}$WEB_PATH#$code"
    }

    fun isLink(url: String?): Boolean = fragmentOf(url) != null

    fun parse(text: String?): Link? {
        val code = fragmentOf(text) ?: text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val f = code.replace("%3A", ":").replace("%3a", ":").split(PART)
        if (f.size != 5) return null
        val groupId = f[0].takeIf(::validId) ?: return null
        val secret = f[1].takeIf { it.isNotEmpty() && PeerGroup.Setting.LINK.accepts(it) } ?: return null
        val count = f[2].toIntOrNull()?.takeIf { it in 1..PeerGroup.MAX_SPACE_MEMBERS } ?: return null
        val raw = runCatching { String(unB64(f[3]), Charsets.UTF_8) }.getOrNull() ?: return null
        val sharer = (PeerInvite.decode(f[4]) as? PeerInvite.Decoded.Ph2)?.invite ?: return null
        return Link(groupId, secret, count, PeerName.accept(raw).name.ifBlank { groupId }, sharer)
    }

    fun isJoin(body: String): Boolean = body.startsWith(TAG)

    fun encode(frame: Frame): String = TAG + when (frame) {
        is Frame.Ask -> "ask$SEP${frame.groupId}$SEP${frame.secret}"
        is Frame.Answer -> "ans$SEP${frame.groupId}$SEP${frame.secret}$SEP${frame.verdict.wire}"
    }

    fun decode(body: String): Frame? {
        if (!isJoin(body)) return null
        val f = body.removePrefix(TAG).trim().split(SEP)
        val groupId = f.getOrNull(1)?.takeIf(::validId) ?: return null
        val secret = f.getOrNull(2)?.takeIf { it.isNotEmpty() && PeerGroup.Setting.LINK.accepts(it) } ?: return null
        return when {
            f[0] == "ask" && f.size == 3 -> Frame.Ask(groupId, secret)
            f[0] == "ans" && f.size == 4 -> Verdict.of(f[3])?.let { Frame.Answer(groupId, secret, it) }
            else -> null
        }
    }

    /** Той самий суддя, що в журналі: чинний секрет і право INVITE в того, хто ділився. */
    fun judge(state: PeerGroup.State?, me: String, secret: String, requesterId: String): Verdict {
        if (state == null || me !in state.group.memberIds) return Verdict.NO_RIGHT
        val live = state.setting(PeerGroup.Setting.LINK)
        if (live.isEmpty() || live != secret) return Verdict.REVOKED
        if (!PeerGroupRoles.may(state.roles, state.group.memberIds, me, PeerGroupRoles.Power.INVITE, state.cap)) {
            return Verdict.NO_RIGHT
        }
        if (requesterId in state.group.memberIds) return Verdict.JOINED
        if (state.group.memberIds.size >= state.cap) return Verdict.FULL
        if (state.setting(PeerGroup.Setting.APPROVE) == PeerGroup.ON) return Verdict.PENDING
        return Verdict.JOINED
    }

    /** Що відповісти на вхідне прохання: кого додати (або нікого) і кадр відповіді. */
    data class Reply(val group: PeerGroup.Group?, val add: String?, val to: String, val answer: String, val verdict: Verdict)

    fun reply(log: List<PeerMessage>, me: String, incoming: PeerMessage): Reply? {
        val ask = decode(incoming.body) as? Frame.Ask ?: return null
        if (me.isBlank() || incoming.fromId == me || incoming.fromId.isBlank()) return null
        val state = PeerGroup.stateOf(log, ask.groupId)
        val verdict = judge(state, me, ask.secret, incoming.fromId)
        val add = incoming.fromId.takeIf { verdict == Verdict.JOINED && it !in state!!.group.memberIds }
        return Reply(state?.group, add, incoming.fromId, encode(Frame.Answer(ask.groupId, ask.secret, verdict)), verdict)
    }

    data class Request(val peerId: String, val secret: String, val atMs: Long)

    /** Заявки до мене: остання моя відповідь людині — «чекайте», і вона ще не в гілці. */
    fun pending(log: List<PeerMessage>, me: String, groupId: String): List<Request> {
        if (me.isBlank()) return emptyList()
        val members = PeerGroup.stateOf(log, groupId)?.group?.memberIds ?: return emptyList()
        return log
            .asSequence()
            .filter { it.fromId == me && it.toId != me && it.toId !in members }
            .mapNotNull { m -> (decode(m.body) as? Frame.Answer)?.takeIf { it.groupId == groupId }?.let { m to it } }
            .groupBy { it.first.toId }
            .mapNotNull { (peer, answers) ->
                val (m, last) = answers.maxBy { it.first.sentAtMs }
                if (last.verdict == Verdict.PENDING) Request(peer, last.secret, m.sentAtMs) else null
            }
            .sortedBy { it.atMs }
    }

    /** Рішення адміна щодо заявки. Немає заявки — `null`; «так» без права чи місця — чесна відмова. */
    fun decide(log: List<PeerMessage>, me: String, groupId: String, peerId: String, accept: Boolean): Reply? {
        val request = pending(log, me, groupId).firstOrNull { it.peerId == peerId } ?: return null
        val state = PeerGroup.stateOf(log, groupId) ?: return null
        val verdict = when {
            !accept -> Verdict.DECLINED
            me !in state.group.memberIds -> Verdict.NO_RIGHT
            !PeerGroupRoles.may(state.roles, state.group.memberIds, me, PeerGroupRoles.Power.INVITE, state.cap) -> Verdict.NO_RIGHT
            state.group.memberIds.size >= state.cap -> Verdict.FULL
            else -> Verdict.JOINED
        }
        val add = peerId.takeIf { verdict == Verdict.JOINED }
        return Reply(state.group, add, peerId, encode(Frame.Answer(groupId, request.secret, verdict)), verdict)
    }

    enum class Stage(val wire: String) { NONE("none"), ASKED("asked"), PENDING("pending"), JOINED("joined"), REFUSED("refused") }

    data class Standing(val stage: Stage, val why: Verdict? = null)

    /** Де стоїть той, хто відкрив посилання, — з журналу, без памʼяті окремо. */
    fun standing(log: List<PeerMessage>, me: String, link: Link): Standing {
        val members = PeerGroup.stateOf(log, link.groupId)?.group?.memberIds.orEmpty()
        if (me.isNotBlank() && me in members) return Standing(Stage.JOINED)
        val sharer = link.sharer.peerId
        val last = log
            .filter { it.fromId == sharer && it.toId == me }
            .mapNotNull { m -> (decode(m.body) as? Frame.Answer)?.takeIf { it.groupId == link.groupId && it.secret == link.secret }?.let { m to it } }
            .maxByOrNull { it.first.sentAtMs }
            ?.second
        if (last?.verdict == Verdict.PENDING) return Standing(Stage.PENDING)
        if (last != null && last.verdict != Verdict.JOINED) return Standing(Stage.REFUSED, last.verdict)
        val asked = log.any { m ->
            m.fromId == me && m.toId == sharer &&
                (decode(m.body) as? Frame.Ask)?.let { it.groupId == link.groupId && it.secret == link.secret } == true
        }
        return if (asked || last != null) Standing(Stage.ASKED) else Standing(Stage.NONE)
    }

    private fun fragmentOf(url: String?): String? {
        val uri = runCatching { java.net.URI(url?.trim().orEmpty()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (!uri.rawAuthority.equals(PeerInvite.WEB_HOST, ignoreCase = true)) return null
        if (uri.rawPath != WEB_PATH && uri.rawPath != WEB_PATH.trimEnd('/')) return null
        return uri.rawFragment?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun validId(id: String): Boolean =
        id.isNotEmpty() && id.length <= 64 && id.all { it.isLetterOrDigit() || it == '_' || it == '-' }

    private fun b64(text: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun unB64(text: String): ByteArray = java.util.Base64.getUrlDecoder().decode(text)
}
