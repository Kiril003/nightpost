package local.phantom.companion.ui.messenger

import local.phantom.companion.core.net.peer.PeerIdentity

/**
 * Що міст каже вебу про звʼязок із людиною (B0, P0-9).
 *
 * Скан запрошення додає ключ лише З ОДНОГО боку: у них нас ще немає, і доти
 * лист нікуди не дійде. «Ключ є» (`dhPubKey`) цього не відрізняє — тож
 * `session_ready` стало правдою про ДВІ сторони, а не про наш рядок.
 *
 * Доказів три, і всі засвідчені, не вгадані: знайомство прийняте
 * (`introducedAtMs`), ключ зустрічі домовлено (`metAtMs`) або наш лист їм
 * уже ДОСТАВЛЕНО — квитанцію пише лише ключ, якого без нашого рядка в
 * їхньому реєстрі не скласти. Третій тримає пари, що працювали до цієї
 * збірки: без нього вони б «чекали» довіку, хоча листуються щодня.
 */
internal object WebMessengerReach {

    fun mutual(peer: PeerIdentity, delivered: () -> Boolean = { false }): Boolean =
        peer.introducedAtMs > 0L || peer.metAtMs > 0L || delivered()

    /** Слово для вебу: `mutual` — обидва рядки на місці, `waiting` — чекаємо на них. */
    fun wire(mutual: Boolean): String = if (mutual) "mutual" else "waiting"

    /**
     * Звідки ключ. Сильну позначку дає лише слово `camera`: усе інше — слабка
     * дорога, і той, хто не сказав, може тільки недоговорити, не прихвастати.
     */
    fun keySource(raw: String?): PeerIdentity.KeySource =
        if (raw == "camera") PeerIdentity.KeySource.CAMERA else PeerIdentity.KeySource.REMOTE
}
