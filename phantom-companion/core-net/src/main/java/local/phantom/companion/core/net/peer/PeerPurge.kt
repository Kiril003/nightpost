package local.phantom.companion.core.net.peer

/**
 * Що саме зникне — перш ніж воно зникне.
 *
 * Стирання на сітці може бути чесним лише про ОДИН телефон: у людини на тому
 * кінці може не бути ні мережі, ні бажання щось стирати, і застосунок, який
 * пише «видалено», поки її телефон тримає той самий рядок, бреше. Тож тут немає
 * жодної дії, яка тягнеться далі за це сховище, і кожна віддає число та межі
 * ЗАЗДАЛЕГІДЬ: питання «скільки і за який час» задають до того, як натиснуть,
 * а не після.
 *
 * Вибір рядків рівно той самий, яким екран їх показує, — інакше «стерти
 * розмову» одного дня забрало б щось, чого на екрані не було.
 */
object PeerPurge {

    /**
     * [count] рахує СКАЗАНЕ, а не рядки сховища: у групі на трьох одна репліка
     * лежить трьома копіями, і «зникне 12 листів» замість «чотири» — це те саме
     * перебільшення, через яке потім не вірять жодному числу.
     */
    data class Scope(
        val ids: Set<String>,
        val count: Int,
        val fromMs: Long,
        val toMs: Long,
    ) {
        val isEmpty: Boolean get() = ids.isEmpty()
    }

    val NOTHING = Scope(emptySet(), 0, 0L, 0L)

    /** Розмова на двох. Групові копії сюди не входять — так само, як на екрані. */
    fun conversation(outbox: List<PeerMessage>, peerId: String): Scope {
        val rows = PeerOutbox.conversation(outbox, peerId)
        // Шматки вкладень екран не показує, але вони — рядки тієї самої черги.
        // Лишити їх означало б і далі везти фотографію з розмови, яку людина
        // щойно стерла: рівно те «стерти, але дослати», якого тут не буває.
        val cargo = outbox.filter {
            (it.toId == peerId || it.fromId == peerId) && PeerFile.isChunk(it.body)
        }
        return scopeOf(rows, rows.size, cargo.map { it.id })
    }

    fun group(outbox: List<PeerMessage>, groupId: String): Scope {
        val rows = outbox.filter { m ->
            val d = PeerGroup.decode(m.body) ?: return@filter false
            d.group.id == groupId && !isPendingLeave(m, d.text)
        }
        val cargo = rows.mapNotNull { PeerGroup.decode(it.body)?.text?.let(PeerFile::describe)?.blobId }
            .distinct()
            .flatMap { chunkIds(outbox, it) }
        return scopeOf(rows, PeerGroup.collapse(rows, groupId).size, cargo)
    }

    private fun isPendingLeave(m: PeerMessage, text: String): Boolean =
        PeerGroup.decodeAct(text) == PeerGroup.Act.Leave &&
            (m.state == PeerMessage.State.QUEUED ||
                m.state == PeerMessage.State.SENT ||
                m.state == PeerMessage.State.HELD)

    /** Одна репліка: у групі це весь її віяр, а не та копія, по якій тиснули. */
    fun line(outbox: List<PeerMessage>, message: PeerMessage): Scope {
        val scope = bare(outbox, message)
        val traces = outbox.filter { m ->
            PeerRevise.isEdit(m.body) && (PeerRevise.decode(m.body) as? PeerRevise.Edit)?.targetId in scope.ids
        }
        return if (traces.isEmpty()) scope else scope.copy(ids = scope.ids + traces.map { it.id })
    }

    private fun bare(outbox: List<PeerMessage>, message: PeerMessage): Scope {
        // Стерта фотографія мусить забрати й свої шматки — інакше вона й далі
        // їде до людини, яка вважає її стертою.
        PeerFile.describe(message.body)?.let { att ->
            return scopeOf(listOf(message), 1, chunkIds(outbox, att.blobId))
        }
        val decoded = PeerGroup.decode(message.body)
            ?: return scopeOf(listOf(message), 1)
        val groupId = decoded.group.id
        val copies = outbox.filter {
            it.fromId == message.fromId &&
                it.sentAtMs == message.sentAtMs &&
                it.body == message.body &&
                PeerGroup.decode(it.body)?.group?.id == groupId
        }
        val cargo = PeerFile.describe(decoded.text)?.let { chunkIds(outbox, it.blobId) }.orEmpty()
        return scopeOf(copies.ifEmpty { listOf(message) }, 1, cargo)
    }

    /**
     * Чи піде разом із цими рядками й сама група. Група — це її листи, тож
     * останній стертий забирає її зі списку; повернути її може будь-який новий
     * лист від будь-кого, і саме це треба сказати вголос, а не показати потім.
     */
    fun removesGroup(outbox: List<PeerMessage>, scope: Scope, groupId: String): Boolean =
        PeerGroup.known(outbox).any { it.id == groupId } &&
            PeerGroup.known(PeerOutbox.delete(outbox, scope.ids)).none { it.id == groupId }

    /** Чи лишаться листи цієї людини десь іще, коли її прибрати з реєстру. */
    fun sharesGroup(outbox: List<PeerMessage>, peerId: String): Boolean =
        PeerGroup.known(outbox).any { peerId in it.memberIds }

    private fun chunkIds(outbox: List<PeerMessage>, blobId: String): List<String> =
        outbox.filter { PeerFile.head(it.body)?.blobId == blobId }.map { it.id }

    /**
     * [cargo] — рядки, які підуть, але яких людина не бачила й не рахує.
     * Час і число лишаються від видимого: у вікні підтвердження стоїть те, що
     * сказала людина, а не те, скільки службових рядків це важить.
     */
    private fun scopeOf(
        rows: List<PeerMessage>,
        count: Int,
        cargo: List<String> = emptyList(),
    ): Scope =
        // Порожньо ВИДИМО — це ще не порожньо: лист-опис могли стерти окремо, і
        // тоді від фотографії лишились самі шматки. Вийти тут з NOTHING означало
        // б лишити їх у черзі назавжди — розмова стерта, а вона все ще їде.
        if (rows.isEmpty() && cargo.isEmpty()) {
            NOTHING
        } else if (rows.isEmpty()) {
            Scope(ids = cargo.toSet(), count = 0, fromMs = 0L, toMs = 0L)
        } else {
            Scope(
                ids = rows.map { it.id }.toSet() + cargo,
                count = count,
                fromMs = rows.minOf { it.sentAtMs },
                toMs = rows.maxOf { it.sentAtMs },
            )
        }
}
