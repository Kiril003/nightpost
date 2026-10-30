package local.phantom.companion.ui.messenger

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import local.phantom.companion.R
import local.phantom.companion.core.net.peer.PeerCard
import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerMark
import local.phantom.companion.core.net.peer.PeerMessage

/**
 * Позначки стану на картці зустрічі й рахунку (`PH-CST:`,
 * `docs/scene/MEETUP_CONTRACT.md` §2): що приймає міст, куди ляже позначка і
 * як вона повертається на рядок картки полем `card_marks`.
 *
 * Зміст значень знає лише веб; тут — закритий словник форми, щоб веб не міг
 * покласти в дріт довільний рядок під виглядом позначки.
 *
 * Найтонше місце — ГРУПА. Лист у групі — це N окремих копій, кожна зі своїм id
 * ([PeerGroup.collapse]), тож id копії на сусідньому апараті інший. Тому ціль
 * позначки в групі — ключ віяра (автор + мить), той самий, яким група
 * закріплює рядок ([PeerGroup.Pin]); у розмові вдвох — сам id рядка.
 */
object WebMessengerCardMarks {
    /** Дотик веба: `value` зі словника й явне `on` (напрям вгадувати не можна). */
    data class Ask(val value: String, val on: Boolean)

    sealed interface Parsed {
        data class Ok(val ask: Ask) : Parsed
        data class Bad(val code: String) : Parsed
    }

    const val BAD_BODY = "bad_body"
    const val BAD_VALUE = "bad_value"

    private val ORGANIZER_ONLY = Regex("cancel:1|at:[0-9]{1,10}")

    /** Закритий словник значень, `fullmatch`: `$` не пропускає кінцевий `\n`. */
    private val VALUES = listOf(
        Regex("rsvp:(yes|maybe|no)"),
        Regex("eta:[0-9]{1,10}"),
        Regex("dish:[A-Za-z0-9_-]{1,36}"),
        Regex("keep:1"),
        Regex("here:1"),
        Regex("plus:1"),
        Regex("slot:[0-2]"),
        Regex("lock:[0-2]"),
        Regex("bring:[^\\r\\n]{1,58}"),
        Regex("cancel:1"),
        Regex("at:[0-9]{1,10}"),
        Regex("spread:1"),
        Regex("cash:[0-9a-f]{16}"),
    )

    fun parse(body: String?): Parsed {
        val o = runCatching { Json.parseToJsonElement(body.orEmpty()) }.getOrNull() as? JsonObject
            ?: return Parsed.Bad(BAD_BODY)
        val value = (o["value"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return Parsed.Bad(BAD_BODY)
        // Лише справжній булів: рядок "true" — не напрям.
        val on = (o["on"] as? JsonPrimitive)?.takeIf { !it.isString }?.content
            ?.let { if (it == "true") true else if (it == "false") false else null }
            ?: return Parsed.Bad(BAD_BODY)
        if (VALUES.none { it.matches(value) }) return Parsed.Bad(BAD_VALUE)
        return Parsed.Ok(Ask(value, on))
    }

    /**
     * Ціль позначки. Група: `автор_мить` (копії одного рядка мають
     * різні id, але спільні автора й мить). Вдвох: id рядка.
     */
    fun targetOf(line: PeerMessage): String =
        if (PeerGroup.decode(line.body) != null) "${line.fromId}_${line.sentAtMs}" else line.id

    /** Кому їде позначка: усім учасникам розмови, крім мене. */
    fun recipients(line: PeerMessage, group: PeerGroup.Group?, me: String): List<String> =
        if (group != null) {
            group.memberIds.filter { it != me }.distinct()
        } else {
            listOf(if (line.fromId == me) line.toId else line.fromId).filter { it != me }
        }

    /** Журнал → «ціль → значення → автори». Лише позначки стану, не смайли. */
    fun fold(log: List<PeerMessage>): Map<String, Map<String, List<String>>> =
        PeerMark.foldAll(log, PeerMark.What.CARD_STATE, single = false)

    /** Позначки однієї цілі, але лише від тих, хто в розмові: чужий не голосує. */
    fun of(
        folded: Map<String, Map<String, List<String>>>,
        target: String,
        allowed: Collection<String>,
    ): Map<String, List<String>> =
        folded[target].orEmpty()
            .mapValues { (_, authors) -> authors.filter { it in allowed } }
            .filterValues { it.isNotEmpty() }

    /** Кадр у дріт, або null, коли ціль чи значення в позначку не лізуть. */
    fun frame(target: String, ask: Ask): String? =
        PeerMark.encode(PeerMark.Mark(PeerMark.What.CARD_STATE, target, ask.on, ask.value))

    /** Чому дотик не поїхав: слово → рядок у strings.xml. Тест читає слово, людина — рядок. */
    enum class Why(val res: Int) {
        NO_IDENTITY(R.string.msgr_bridge_no_identity),
        NO_MESSAGE(R.string.msgr_bridge_no_message),
        NOT_IN_GROUP(R.string.msgr_group_gone),
        NOT_CARD(R.string.msgr_scene_not_card),
        BAD_MARK(R.string.msgr_scene_mark_bad),
        NOT_ORGANIZER(R.string.msgr_plan_not_organizer),
        NO_KEY(R.string.msgr_bridge_peer_no_key),

        /** Ніхто з адресатів не повідомив, що вміє позначки сцени (збірка 18): старій збірці кадр показався б сирим дротом. */
        PEER_OLD(R.string.msgr_scene_peer_old),

        /** Кадр склався, але жодна відправка не відбулась. НЕ «немає особи»: то була б неправда. */
        SEND_FAILED(R.string.msgr_scene_mark_failed),
    }

    /** Рішення про дотик ДО сокета: або відмова з кодом, або кадр і кому він їде. */
    sealed interface Plan {
        data class Refuse(val status: Int, val code: String, val why: Why) : Plan

        /**
         * [to] — хто має ключ і сказав, що вміє позначки сцени: лише їм кадр і їде.
         * [keyless] — без ключа; [older] — з ключем, але версія застосунку невідома
         * або старіша за 18. Обом позначка не поїде (вони розійдуться з рештою, і це
         * треба сказати): старій збірці кадр `PH-CST:` показався б сирим дротом, а
         * дотик — сповіщенням.
         */
        data class Send(
            val target: String,
            val frame: String,
            val group: PeerGroup.Group?,
            val members: List<String>,
            val to: List<String>,
            val keyless: List<String>,
            val older: List<String> = emptyList(),
        ) : Plan
    }

    /**
     * Усе, що міст вирішує про дотик, без Android: чий це рядок, чи я в групі,
     * чи це картка, кому їде й кому не поїде. `keyed` — id контактів із ключем;
     * `scene` — id тих, хто сам повідомив у профілі, що вміє позначки сцени.
     */
    fun plan(
        log: List<PeerMessage>,
        me: String,
        messageId: String,
        ask: Ask,
        keyed: Set<String>,
        scene: Set<String>,
    ): Plan {
        if (me.isBlank()) return Plan.Refuse(503, "no_identity", Why.NO_IDENTITY)
        val line = log.firstOrNull { it.id == messageId && (it.fromId == me || it.toId == me) }
            ?: return Plan.Refuse(404, "no_message", Why.NO_MESSAGE)
        val named = PeerGroup.decode(line.body)?.group
        val group = named?.let { WebMessengerGroups.find(log, it.id) }
        if (named != null && group == null) return Plan.Refuse(404, "no_message", Why.NO_MESSAGE)
        if (group != null && PeerGroup.standing(log, group, me) != PeerGroup.Standing.IN) {
            return Plan.Refuse(409, "not_in_group", Why.NOT_IN_GROUP)
        }
        val said = if (group != null) WebMessengerGroups.said(line.body).orEmpty() else line.body
        val fields = WebMessengerKinds.fields(said)
        if (fields[WebMessengerKinds.CARD_FIELD] !is String) {
            return Plan.Refuse(422, "not_card", Why.NOT_CARD)
        }
        if (ORGANIZER_ONLY.matches(ask.value)) {
            if (fields[WebMessengerKinds.KIND] != PeerCard.Card.MEETUP.webType) {
                return Plan.Refuse(422, "bad_mark", Why.BAD_MARK)
            }
            if (line.fromId != me) {
                return Plan.Refuse(403, "not_organizer", Why.NOT_ORGANIZER)
            }
        }
        val target = targetOf(line)
        val frame = frame(target, ask) ?: return Plan.Refuse(422, "bad_mark", Why.BAD_MARK)
        val everyone = recipients(line, group, me)
        val (withKey, keyless) = everyone.partition { it in keyed }
        val (to, older) = withKey.partition { it in scene }
        if (to.isEmpty()) {
            return if (withKey.isEmpty()) Plan.Refuse(409, "no_key", Why.NO_KEY) else Plan.Refuse(409, "peer_old", Why.PEER_OLD)
        }
        return Plan.Send(target, frame, group, group?.memberIds ?: listOf(line.fromId, line.toId), to, keyless, older)
    }

    /** Хто не дістане позначку: без ключа, на старій збірці, плюс ті, чия відправка повернула нічого. */
    fun missed(plan: Plan.Send, sentTo: Collection<String>): List<String> =
        plan.keyless + plan.older + plan.to.filter { it !in sentTo }

    /** `card_marks` у JSON: `{"<значення>": ["<автор>", …]}` — форма, яку читає веб. */
    fun json(marks: Map<String, List<String>>): JsonObject =
        JsonObject(marks.mapValues { (_, authors) -> JsonArray(authors.map(::JsonPrimitive)) })

    /**
     * Позначки на РЯДКУ стрічки: ціль рядка, автори — лише учасники розмови,
     * а `cancel:1` та `at:<хвилини>` — лише від автора картки.
     */
    fun marksOf(
        folded: Map<String, Map<String, List<String>>>,
        line: PeerMessage,
        members: Collection<String>,
    ): Map<String, List<String>> =
        of(folded, targetOf(line), members)
            .mapValues { (value, authors) ->
                if (ORGANIZER_ONLY.matches(value)) authors.filter { it == line.fromId } else authors
            }
            .filterValues { it.isNotEmpty() }

    /**
     * Позначки на РЯДКУ стрічки: ціль рядка, автори — лише учасники розмови.
     * [folded] — згорнутий журнал на всю розмову (один прохід, не на кожен рядок).
     */
    fun onLine(folded: Map<String, Map<String, List<String>>>, line: PeerMessage, members: Collection<String>): JsonObject =
        json(marksOf(folded, line, members))
}
