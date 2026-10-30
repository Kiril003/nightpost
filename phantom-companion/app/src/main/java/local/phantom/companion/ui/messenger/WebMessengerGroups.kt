package local.phantom.companion.ui.messenger

import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerGroupRoles
import local.phantom.companion.core.net.peer.PeerHistory
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerRoomLive
import local.phantom.companion.core.net.peer.PeerRoster

/**
 * Групи для веба.
 *
 * Меш уміє групи давно — ними жив рідний екран. Міст їх не бачив узагалі:
 * ростер фільтрувався до людей, кожна розмова була «dm», а `sendToGroup`
 * не кликався жодного разу. Тут нічого не винаходиться — існуюче виводиться
 * назовні тими самими розбирачами, якими його читав рідний екран.
 *
 * Группа не має власного сховища: [PeerGroup.known] складає її з листів, тож
 * група без жодного листа не існує. Саме тому створення вимагає людей — лист
 * нікуди не піде, і група зникне з першим перезапуском.
 *
 * JSON тут немає навмисно, як і в [WebMessengerKinds]: у модулі `app` юніт-тест
 * бачить від `org.json` заглушку, тож усе, що варто перевіряти, перевіряється
 * значенням.
 */
internal object WebMessengerGroups {

    /** Рід розмови. Веб читає його як `type` і малює групу інакше за людину. */
    const val KIND = "group"

    /** Дія над складом у полях листа — щоб веб міг її колись відрізнити. */
    const val ACT = "system_act"

    /** Кого стосується дія «add»: id доданого, не того, хто додав. Лише локально, у рядок веба. */
    const val ACT_TARGET = "system_act_target"

    fun all(log: List<PeerMessage>): List<PeerGroup.Group> = PeerGroup.known(log)

    fun find(log: List<PeerMessage>, id: String): PeerGroup.Group? =
        all(log).firstOrNull { it.id == id }

    /** Один рядок на сказане, а не одна копія на учасника. Події простору — не рядки. */
    fun feed(log: List<PeerMessage>, groupId: String): List<PeerMessage> =
        PeerGroup.collapse(log, groupId).filterNot { PeerGroup.isSpaceLine(it.body) }

    fun replyWithin(log: List<PeerMessage>, groupId: String, id: String): String =
        id.takeIf { it.isNotBlank() && log.any { m -> m.id == it && PeerGroup.decode(m.body)?.group?.id == groupId } }.orEmpty()

    fun foldedReplyId(log: List<PeerMessage>, groupId: String, replyToId: String): String {
        if (replyToId.isBlank()) return ""
        val origin = log.firstOrNull { it.id == replyToId && PeerGroup.decode(it.body)?.group?.id == groupId } ?: return replyToId
        return feed(log, groupId).firstOrNull {
            it.fromId == origin.fromId &&
                it.sentAtMs == origin.sentAtMs &&
                it.body == origin.body
        }?.id ?: origin.id
    }

    /** Те, що набрала людина, з-під конверта групи й адреси каналу. */
    fun said(body: String): String? {
        val text = PeerGroup.decode(body)?.text ?: return null
        return if (PeerGroup.isChannelLine(text)) PeerGroup.channelLine(text)?.text else text
    }

    /** Канал простору, у який сказано рядок, — або null для звичайної гілки. */
    fun channelOf(body: String): String? = PeerGroup.decode(body)?.text?.let { PeerGroup.channelLine(it)?.channelId }

    fun actOf(body: String): PeerGroup.Act? = said(body)?.let { PeerGroup.decodeAct(it) }

    /**
     * Кому саме поїхала кожна копія одного сказаного рядка.
     *
     * Для розмови вдвох доля листа одна. У групі їх стільки, скільки людей, і
     * вони РІЗНІ: одному доставлено, другий чекає, до третього ключа немає
     * взагалі. Копія — це окремий [PeerMessage] зі своїм [PeerMessage.toId],
     * тож правда про кожного тут не рахується, а просто є.
     *
     * Читається лише власний віяр: чужий рядок цей телефон бачить однією
     * копією — своєю, — і про решту не знає нічого.
     */
    fun fan(
        log: List<PeerMessage>,
        groupId: String,
        fromId: String,
        sentAtMs: Long,
    ): List<Copy> = copies(log, groupId, fromId, sentAtMs).map { Copy(it.toId, it.id, it.state) }

    /**
     * Дорога групового рядка.
     *
     * Про чужий віяр цей телефон не знає нічого, і [WebMessengerKinds.road] так
     * і відповість — але спершу перебере ВЕСЬ журнал заради відповіді «не
     * знаю». Тут це коротке замикання ціни, а не друге правило: сама відповідь
     * і далі складається там.
     */
    fun road(log: List<PeerMessage>, groupId: String, m: PeerMessage, meId: String): String? =
        if (m.fromId != meId) null else WebMessengerKinds.road(copies(log, groupId, meId, m.sentAtMs), meId)

    /** Ті самі копії рядками черги — з них читається й дорога, і доля. */
    fun copies(
        log: List<PeerMessage>,
        groupId: String,
        fromId: String,
        sentAtMs: Long,
    ): List<PeerMessage> =
        log.filter {
            it.fromId == fromId &&
                it.sentAtMs == sentAtMs &&
                PeerGroup.decode(it.body)?.group?.id == groupId
        }

    data class Copy(val toId: String, val messageId: String, val state: PeerMessage.State)

    /**
     * Склад групи — засвідчений, але не авторитетний.
     *
     * Склад їде в заголовку КОЖНОГО листа, тож учасник, чиї листи в цього
     * телефона не відкриваються, все одно названий чужими листами. Але це
     * означає «той, кого ти додав, стверджує цей склад», а не факт про світ:
     * [inRoster] = false — це твердження про ТВІЙ список, єдине, що пристрій
     * справді може перевірити.
     */
    fun members(
        group: PeerGroup.Group,
        roster: List<PeerIdentity>,
        meId: String,
    ): List<Member> {
        val known = roster.associateBy { it.id }
        return group.memberIds.map { id ->
            val peer = known[id]
            Member(
                id = id,
                name = peer?.name?.takeIf { it.isNotBlank() } ?: PeerRoster.shortId(id),
                inRoster = peer != null,
                verified = (peer?.verifiedAtMs ?: 0L) > 0L,
                hasKey = peer?.dhPubKey?.isNotBlank() == true,
                isMe = id == meId,
            )
        }
    }

    data class Member(
        val id: String,
        val name: String,
        val inRoster: Boolean,
        val verified: Boolean,
        /** Без ключа копія не запечатається — цьому не дійде взагалі. */
        val hasKey: Boolean,
        val isMe: Boolean,
    )

    /**
     * Кого ще можна додати: люди з ключем, яких у групі ще немає.
     *
     * Без ключа додавання виглядало б зробленим, а кожна копія до нього
     * помирала б у черзі — мовчазна поразка з боку складу.
     */
    fun addable(
        group: PeerGroup.Group,
        roster: List<PeerIdentity>,
    ): List<PeerIdentity> =
        roster.filter {
            it.kind == PeerIdentity.Kind.PERSON &&
                it.id !in group.memberIds &&
                it.dhPubKey.isNotBlank()
        }

    fun isFull(group: PeerGroup.Group, state: PeerGroup.State?): Boolean =
        group.memberIds.size >= (state?.cap ?: PeerGroup.MAX_MEMBERS)

    // ── жива розмова ─────────────────────────────────────────────────────

    /** [faces] — інші люди в розмові; себе веб не малює обличчям, а [mine] каже, що я там. */
    data class Live(val callId: String, val startedAtMs: Long, val faces: List<String>, val mine: Boolean)

    fun live(board: Map<String, PeerRoomLive.Room>, groupId: String, meId: String, nowMs: Long): Live? {
        val room = PeerRoomLive.view(board, groupId, nowMs) ?: return null
        return Live(
            callId = room.callId,
            startedAtMs = room.startedAtMs,
            faces = room.faces.keys.filter { it != meId },
            mine = meId.isNotBlank() && meId in room.faces,
        )
    }

    // ── минуле ───────────────────────────────────────────────────────────

    /**
     * Провал у минулому гілки і чи є в кого його попросити.
     *
     * Окремими полями, а не новим значенням [WebMessengerKinds.NOTICE]: гілка
     * «інакше» в мостовому розбирачі повідомлень каже «цей застосунок ще не
     * вміє показати цей вміст», і новий код поїхав би саме туди — брехнею про
     * рід, який тут прямо перед очима.
     */
    data class Past(
        /**
         * Мить найранішого рядка, який телефон МАЄ, — не дата вступу: її він не
         * знає. Нуль означає «жодного», і речення про це інше.
         */
        val fromMs: Long,
        /** Скільки людей із гілки цей телефон справді може попросити. */
        val askable: Int,
        /** Коли прохання поїхало. Нуль — ще не просили. */
        val askedAtMs: Long,
    ) {
        val canAsk: Boolean get() = askable > 0
    }

    fun past(
        log: List<PeerMessage>,
        group: PeerGroup.Group,
        roster: List<PeerIdentity>,
        meId: String,
    ): Past {
        val mine = feed(log, group.id).filterNot { PeerGroup.isMachineLine(it.body) }
        // Та сама відмова, що в PeerMesh.askHistory: гілка, з якої вийшли, не
        // просить. Інакше кнопка стоїть і мовчки не робить нічого.
        val askable = if (meId.isBlank() || meId !in group.memberIds) {
            0
        } else {
            val keyed = roster.filter { it.dhPubKey.isNotBlank() }.map { it.id }.toHashSet()
            group.memberIds.count { it != meId && it in keyed }
        }
        return Past(
            fromMs = mine.minOfOrNull { it.sentAtMs } ?: 0L,
            askable = askable,
            askedAtMs = mine.filter { it.fromId == meId && PeerHistory.isAsk(it.body) }
                .maxOfOrNull { it.sentAtMs } ?: 0L,
        )
    }

    /**
     * Переказане минуле — рядками, які НЕ вміють сказати «доставлено».
     *
     * Стану тут немає за побудовою, і це не недогляд: [PeerMessage.State] має
     * `DELIVERED`, тож переказаний рядок, доданий у стрічку як лист, сказав би
     * «доставлено» про те, чого ця пара телефонів одне одному не посилала.
     * Підпис накриває адресата ([PeerSealing]) — копія для третього підпису
     * автора нести не може, і жодне поле цього не обійде.
     */
    data class Retold(
        val id: String,
        val fromId: String,
        val fromName: String,
        val sentAtMs: Long,
        val text: String,
        val viaId: String,
        val viaName: String,
        /** Мить переказу — свідчення старіє окремо від сказаного. */
        val viaAtMs: Long,
    )

    fun retold(
        log: List<PeerMessage>,
        groupId: String,
        roster: List<PeerIdentity>,
    ): List<Retold> {
        val known = roster.associateBy { it.id }
        fun named(id: String) = known[id]?.name?.takeIf { it.isNotBlank() } ?: PeerRoster.shortId(id)
        return PeerHistory.retold(log, groupId).map {
            Retold(
                id = it.line.id,
                fromId = it.line.fromId,
                fromName = named(it.line.fromId),
                sentAtMs = it.line.sentAtMs,
                text = it.line.text,
                viaId = it.viaId,
                viaName = named(it.viaId),
                viaAtMs = it.viaAtMs,
            )
        }
    }

    /** Налаштування гілки для веба; ключі — ті самі, що на дроті. */
    fun settingsJson(state: PeerGroup.State?): org.json.JSONObject {
        val o = org.json.JSONObject()
        PeerGroup.Setting.entries.forEach { key ->
            val value = state?.setting(key) ?: PeerGroup.DEFAULTS.getValue(key)
            o.put(key.wire, value)
        }
        return o
    }

    /** Тіло PATCH → акти. Невідомий ключ чи хибне значення — null для всього тіла. */
    fun settingActs(o: org.json.JSONObject): List<PeerGroup.Act.Set>? =
        o.keys().asSequence().toList().map { k ->
            val key = PeerGroup.Setting.of(k) ?: return null
            val value = o.opt(k)?.toString()?.trim().orEmpty()
            if (!key.accepts(value)) return null
            PeerGroup.Act.Set(key, value)
        }

    sealed interface PostGate {
        data object Open : PostGate

        data object AdminsOnly : PostGate

        data class Slow(val waitSeconds: Int) : PostGate
    }

    /**
     * Чи можна сказати слово в гілку зараз. Адмін і вище — завжди; у каналі
     * решта лише читає; повільний режим рахує від власного останнього рядка.
     */
    fun mayPost(log: List<PeerMessage>, groupId: String, me: String, nowMs: Long): PostGate {
        val state = PeerGroup.stateOf(log, groupId) ?: return PostGate.Open
        if (state.roles.of(me).rank >= PeerGroupRoles.Role.ADMIN.rank) return PostGate.Open
        if (state.isChannel) return PostGate.AdminsOnly
        val slowMs = state.slowSeconds * 1000L
        if (slowMs <= 0L) return PostGate.Open
        val mine = feed(log, groupId).lastOrNull { it.fromId == me && actOf(it.body) == null } ?: return PostGate.Open
        val left = mine.sentAtMs + slowMs - nowMs
        return if (left > 0L) PostGate.Slow(((left + 999L) / 1000L).toInt()) else PostGate.Open
    }
}
