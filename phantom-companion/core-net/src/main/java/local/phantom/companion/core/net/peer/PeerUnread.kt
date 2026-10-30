package local.phantom.companion.core.net.peer

/**
 * One number, counted in one place.
 *
 * The orb's badge counted one-to-one only while the island's counted groups as
 * well, so the same fact wore two different numbers on the same screen. That
 * does not make anyone look twice — it teaches them that neither number means
 * anything, and then the count that mattered goes unread too.
 */
object PeerUnread {

    const val CAP = 99

    /**
     * Рівно те, що месенджер уміє відчинити — а вміє він поки лише розмову на двох.
     *
     * Групи рахувались і тут: доки в застосунку стояв рідний груповий екран,
     * число вело в нього. 06.09 рідні екрани розмови прибрано (месенджер один,
     * веб), а веб-міст груп не знає: `WebMessengerBridge` фільтрує ростер до
     * `Kind.PERSON` і кожну розмову позначає `"dm"`. Лишити групи в лічильнику
     * означало б показати число, під яким немає екрана.
     *
     * Повернути рядок `PeerGroup.known(...)` сюди — перше, що має зробити
     * хвиля, яка навчить міст груп; інакше груповий лист прийде мовчки.
     */
    fun total(
        roster: List<PeerIdentity>,
        outbox: List<PeerMessage>,
    ): Int = PeerRoster.people(roster).sumOf {
        PeerOutbox.unread(outbox, it.id, it.readUpToMs)
    }

    /**
     * Плашка вкладки «Чати»: справжні непрочитані листи плюс розмови, які
     * людина САМА позначила «непрочитаним» у месенджері ([marked]). Позначена
     * розмова важить одиницю — як крапка в її рядку, а не число, — і лише
     * коли справжніх непрочитаних у ній немає: інакше одна розмова рахувалась
     * би двічі.
     */
    fun withMarks(
        roster: List<PeerIdentity>,
        outbox: List<PeerMessage>,
        marked: Set<String>,
    ): Int {
        val unreadBy = PeerRoster.people(roster).associate { it.id to PeerOutbox.unread(outbox, it.id, it.readUpToMs) }
        return unreadBy.values.sum() + marked.count { (unreadBy[it] ?: 0) == 0 }
    }

    /** Null is «no badge», never «0» — a zero badge is a badge. */
    fun badge(total: Int): String? = when {
        total <= 0 -> null
        total > CAP -> "$CAP+"
        else -> total.toString()
    }
}
