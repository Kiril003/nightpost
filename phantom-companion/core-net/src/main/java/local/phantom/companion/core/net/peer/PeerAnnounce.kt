package local.phantom.companion.core.net.peer

/**
 * Whether an arriving line has any business in the notification shade.
 *
 * Pure because the rule is the whole of it, and it used to be half-applied: the
 * one-to-one path silenced the conversation already on screen, the group path
 * silenced nothing. Reading a group buzzed the phone in the operator's own
 * hand, once per line, which is how people learn to turn the channel off.
 */
object PeerAnnounce {

    /**
     * [key] is what the shade groups on — the group for a group line, the
     * sender for a private one. The two must never collide, or a group's
     * entry would swallow the private conversation with the same person.
     */
    data class Notice(
        val key: String,
        val fromId: String,
        val groupId: String?,
        val groupName: String?,
        /** The words, never the wire form the words arrived wrapped in. */
        val text: String,
        /** Автор надіслав «без звуку» ([PeerMessage.quiet]). */
        val quiet: Boolean = false,
        /** Автор групи позначив мене @ ([PeerMessage.mentions]): згадка проходить крізь «без звуку». */
        val mentionsMe: Boolean = false,
    )

    /**
     * Null means stay quiet: the operator is already looking at it.
     *
     * [selfId] — власний ключ. Свій лист повертається тими самими дверима, і на
     * екрані блокування 11.09 стояли ВЛАСНІ тестові листи тестувальника.
     *
     * [isHuman] — чи автор людина. Відповіді самого PHANTOM і агентів Колегії
     * мають місцеві імена («peer_phantom_agent», «agent_…»), а не відбиток
     * ключа: у шторці вони читались як «peer·phan ⚡ Аналіз запиту: «D»».
     * Сповіщення — це «тобі написали», а не «пристрій щось склав».
     */
    fun notice(
        message: PeerMessage,
        visibleChat: String?,
        visibleGroup: String?,
        selfId: String = "",
        isHuman: (String) -> Boolean = { true },
    ): Notice? {
        if (selfId.isNotBlank() && message.fromId == selfId) return null
        if (!isHuman(message.fromId)) return null
        // Одне фото — це сотні шматків, і кожен заходить сюди тими самими
        // дверима, що й слово. Без цього рядка телефон дзвонив би 171 раз на
        // одну фотографію: не «забагато сповіщень», а вимкнений канал назавжди.
        // Дзвінок має власний голос — повноекранне сповіщення з двома кнопками,
        // яке підіймає апарат. Рядок у шторці був би другим сповіщенням про ту
        // саму подію, і в ньому стояв би сирий дріт замість тексту листа.
        // PeerFrames.Kind.CALL_SIGNAL і Kind.HIST уже несуть reachesConversation=
        // false, тож silent() нижче гасить дзвінки й переказ минулого (він весь
        // про те, що вже сказано давно) тим самим шляхом, що й фрагменти файлу —
        // окремі PeerCall.isCall()/PeerHistory.isPack() тут були б другою,
        // вужчою копією тієї самої умови.
        if (silent(message.body)) return null
        val group = PeerGroup.decode(message.body)
        if (group != null) {
            if (visibleGroup == group.group.id) return null
            // Рядок каналу простору — те саме слово людини, лише з адресою
            // каналу попереду; дзвонить сказане, а не адреса.
            val said = if (PeerGroup.isChannelLine(group.text)) {
                PeerGroup.channelLine(group.text)?.text ?: return null
            } else {
                group.text
            }
            if (silent(said)) return null
            // Склад ключів новачкові — до 31 рядка поспіль, у кожному base64
            // чужого конверта. Дії над складом лишаються чутними, як були:
            // ними людина й дізнається, що когось додали чи прибрали.
            if (PeerGroup.isMachineLine(message.body)) return null
            return Notice(
                key = group.group.id,
                fromId = message.fromId,
                groupId = group.group.id,
                groupName = group.group.name,
                text = said,
                quiet = message.quiet,
                mentionsMe = selfId.isNotBlank() && selfId in message.mentions,
            )
        }
        if (visibleChat == message.fromId) return null
        return Notice(
            key = message.fromId,
            fromId = message.fromId,
            groupId = null,
            groupName = null,
            text = message.body,
            quiet = message.quiet,
        )
    }

    /** Смайл на МІЙ рядок: сам кадр мовчить у [notice], говорить лише ця дорога. */
    data class Reacted(val notice: Notice, val emoji: String, val quoted: String?)

    fun reaction(
        message: PeerMessage,
        log: List<PeerMessage>,
        visibleChat: String?,
        visibleGroup: String?,
        selfId: String,
        isHuman: (String) -> Boolean = { true },
    ): Reacted? {
        if (selfId.isBlank() || message.fromId == selfId || !isHuman(message.fromId)) return null
        val mark = PeerMark.decode(message.body)
            ?.takeIf { it.what == PeerMark.What.REACTION && it.on } ?: return null
        val mine = log.firstOrNull {
            it.id == mark.targetId && it.fromId == selfId && it.toId == message.fromId && PeerGroup.decode(it.body) == null
        }
        if (mine != null) {
            if (visibleChat == message.fromId) return null
            return Reacted(
                Notice(message.fromId, message.fromId, null, null, mine.body, message.quiet),
                mark.value,
                quote(mine.body),
            )
        }
        // Ціль у групі — `автор_мить`, спільна для всіх копій рядка.
        val said = log.firstOrNull { it.fromId == selfId && "${it.fromId}_${it.sentAtMs}" == mark.targetId }
            ?.let { PeerGroup.decode(it.body) } ?: return null
        if (message.fromId !in said.group.memberIds || visibleGroup == said.group.id) return null
        return Reacted(
            Notice(said.group.id, message.fromId, said.group.id, said.group.name, said.text, message.quiet),
            mark.value,
            quote(said.text),
        )
    }

    private const val QUOTE_MAX = 60

    private fun quote(text: String): String? {
        if (PeerFrames.kindOf(text) != null) return null
        val t = text.trim().replace(Regex("""\s+"""), " ").takeIf { it.isNotEmpty() } ?: return null
        return if (t.length > QUOTE_MAX) t.take(QUOTE_MAX - 1).trimEnd() + "…" else t
    }

    /**
     * Службовий кадр, який людині нема чого читати у шторці: шматок файла,
     * жива позначка, її зупинка, системний рядок групи. Рід питається в
     * [PeerFrames], щоб новий кадр не міг зʼявитись у дроті повз цю відмову.
     */
    private fun silent(body: String): Boolean {
        val kind = PeerFrames.kindOf(body) ?: return false
        return when (kind) {
            PeerFrames.Kind.GROUP -> false
            PeerFrames.Kind.SYSTEM -> true
            else -> !kind.reachesConversation
        }
    }
}
