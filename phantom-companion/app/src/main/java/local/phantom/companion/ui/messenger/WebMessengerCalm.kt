package local.phantom.companion.ui.messenger

import local.phantom.companion.core.net.peer.PeerMessage

/**
 * Три числа рядка переліку, з яких веб складає спокійний вхідний потік.
 *
 * Правило живе у вебі й видно людині: «Важливе» — розмова вдвох, де листи
 * ходили в ОБИДВА боки за 30 днів; група, де людина мовчить 30 днів і де
 * багато листів, згортається в один тихий рядок. Для цього вебові бракувало
 * рівно двох фактів, яких `last_author` не несе: коли писала сама людина і
 * коли їй писали востаннє, — і скільки листів було за вікно.
 *
 * Міст не вирішує, що важливе: він лише рахує. Вирішує видиме правило у вебі.
 */
object WebMessengerCalm {
    const val WINDOW_MS: Long = 30L * 24 * 60 * 60 * 1000

    data class Pulse(
        /** Останній власний лист; null — людина тут не писала ніколи. */
        val selfAtMs: Long?,
        /** Останній лист від будь-кого іншого; null — ніхто. */
        val peerAtMs: Long?,
        /** Скільки листів (своїх і чужих) лягло за останні 30 днів. */
        val letters30d: Int,
    )

    /** [lines] — уже відфільтровані видимі листи однієї розмови. */
    fun pulse(lines: List<PeerMessage>, me: String, nowMs: Long): Pulse {
        var self: Long? = null
        var peer: Long? = null
        var recent = 0
        for (m in lines) {
            if (m.fromId == me) {
                if (self == null || m.sentAtMs > self) self = m.sentAtMs
            } else if (peer == null || m.sentAtMs > peer) {
                peer = m.sentAtMs
            }
            if (nowMs - m.sentAtMs <= WINDOW_MS) recent++
        }
        return Pulse(self, peer, recent)
    }
}
