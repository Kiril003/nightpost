package local.phantom.companion.ui.messenger

import local.phantom.companion.core.net.peer.PeerIdentity

/**
 * Кого з покликаних нова група реально бере — і кого НЕ взяла та чому.
 *
 * Раніше міст мовчки викидав зі складу кожного, до кого немає ключа: група
 * складалась без Петра, а екран казав «створено». Тимчасова група зустрічі
 * живе тим, що учасники дістають одне одного, тож мовчазний викид — це
 * зустріч, на яку половину не покликали (`messenger-fix-plan.md` §5.1).
 * Тепер викинутих названо, і веб показує їх на імʼя.
 */
object WebMessengerGroupInvite {
    /** Людина в записній книжці є, але ключа шифрування від неї немає. */
    const val NO_KEY = "no_key"

    /** Такого id у записній книжці немає зовсім. */
    const val UNKNOWN = "unknown"

    data class Dropped(val id: String, val name: String?, val reason: String)

    data class Split(val members: List<String>, val dropped: List<Dropped>)

    /** Порядок і в складі, і серед викинутих — як у `wanted`; повтори й сам я не рахуються. */
    fun split(wanted: List<String>, roster: List<PeerIdentity>, me: String): Split {
        val members = ArrayList<String>()
        val dropped = ArrayList<Dropped>()
        for (id in wanted.distinct()) {
            if (id == me) continue
            val peer = roster.firstOrNull { it.id == id }
            when {
                peer == null -> dropped += Dropped(id, null, UNKNOWN)
                peer.dhPubKey.isBlank() -> dropped += Dropped(id, peer.name, NO_KEY)
                else -> members += id
            }
        }
        return Split(members, dropped)
    }
}
