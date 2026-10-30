package local.phantom.companion.ui.messenger

import local.phantom.companion.core.data.peers.PeerPoint
import local.phantom.companion.core.data.peers.ShareMode
import local.phantom.companion.feature.stream.ui.map.AlertSky
import local.phantom.companion.feature.stream.ui.map.SkyVerdict
import local.phantom.companion.feature.stream.ui.map.verdict

/**
 * Жива смуга над списком розмов (25.09): три сигнали, які є лише в PHANTOM.
 *
 * Власник: «месенджер візуально опустів». Декору назад не повертаємо — простір
 * заповнюють ФАКТИ, і кожен факт має тут рівно одне джерело:
 *
 *  - небо — [AlertSky] з `AirAlertWatch`, той самий вирок [verdict], що й у
 *    шторці та на мапі. Слово «тривога» звучить лише на [SkyVerdict.ALERT] і
 *    [SkyVerdict.ALERT_NOFEED]; усе інше (тихо, не знаю, не та країна) — мовчання
 *    смуги, а не «спокійно»: «тихо» тут ніхто не обіцяє;
 *  - люди — живі точки приймача PeerWhere (`PeerWhereHub` кладе їх у
 *    `peerPoints` з [ShareMode.WHERE]); лише ті, чий вузол у ростері, і лише
 *    не протерміновані. Координат назовні НЕ віддаємо: вебу досить слова
 *    «поруч», а мапу відкриває сам телефон ([focusOf]);
 *  - звʼязок — зонд мережі й наявність прямої адреси; слово про нього веб
 *    каже лише тоді, коли це не норма.
 *
 * Чисте: без Android, без JSON — міст перекладає [Pulse] у дріт сам.
 */
object WebMessengerPulse {

    data class Sky(
        /** Коротка назва області («Київська»), як у шторці. */
        val oblast: String,
        /** Коли тривогу оголосили; null — стрічка не сказала. */
        val sinceMs: Long?,
        /** Тривога лишається піднятою, але стрічка мовчить — слово «діє» не можна. */
        val feedSilent: Boolean,
    )

    data class Near(
        val peerId: String,
        val name: String,
        /** `same` / `near` / `far`; null — власного положення не знаємо. */
        val near: String?,
        /** `district` / `quarter` / `exact` — що людина дала. */
        val precision: String?,
        /** Час ВИМІРУ на телефоні людини. */
        val atMs: Long,
    )

    data class Link(
        /** Мережевий зонд: null — ще не міряв. */
        val online: Boolean?,
        /** Чи має цей телефон живу пряму адресу (інакше лише сховок). */
        val direct: Boolean,
    )

    data class Pulse(val sky: Sky?, val near: List<Near>, val link: Link)

    /** Небо в області людини — лише коли там тривога. */
    fun sky(sky: AlertSky, nowMs: Long, shortName: (String) -> String): Sky? {
        val v = sky.verdict(nowMs)
        if (v != SkyVerdict.ALERT && v != SkyVerdict.ALERT_NOFEED) return null
        val own = sky.ownOblast ?: return null
        return Sky(
            oblast = shortName(own),
            sinceMs = sky.oblasts[own]?.changedEpochMs?.takeIf { it in 1..nowMs },
            feedSilent = v == SkyVerdict.ALERT_NOFEED,
        )
    }

    /**
     * Хто з ваших показує вам місце зараз. Порядок: ближчі перші, потім
     * свіжіші. Той, кого немає в ростері (стенд debug-збірки), — не «ваш».
     */
    fun near(points: List<PeerPoint>, people: Set<String>, nowMs: Long): List<Near> =
        points.asSequence()
            .filter { it.share.mode == ShareMode.WHERE }
            .filter { it.nodeId.startsWith(WHERE_NODE) }
            .filter { it.threadId in people }
            .filterNot { p -> p.share.expiresAtMs?.let { it <= nowMs } ?: false }
            .distinctBy { it.threadId }
            .sortedWith(compareBy<PeerPoint> { it.near?.ordinal ?: Int.MAX_VALUE }.thenByDescending { it.atMs })
            .map {
                Near(
                    peerId = it.threadId,
                    name = it.name,
                    near = it.near?.name?.lowercase(),
                    precision = it.precision?.name?.lowercase(),
                    atMs = it.atMs,
                )
            }
            .toList()

    /** Куди навести мапу для людини: центр того, що вона дала, і масштаб за точністю. */
    data class Focus(val lat: Double, val lon: Double, val zoom: Double)

    fun focusOf(points: List<PeerPoint>, peerId: String, nowMs: Long): Focus? {
        val p = points.firstOrNull {
            it.share.mode == ShareMode.WHERE && it.threadId == peerId &&
                (it.share.expiresAtMs?.let { e -> e > nowMs } ?: true)
        } ?: return null
        val zoom = when (p.precision?.name) {
            "DISTRICT" -> 12.0
            "QUARTER" -> 14.0
            else -> 15.0
        }
        return Focus(p.lat, p.lon, zoom)
    }

    /** Те саме, що `PeerWhereHub.NODE`: точки приймача, не сеансу «місце наживо». */
    const val WHERE_NODE = "where:"
}
