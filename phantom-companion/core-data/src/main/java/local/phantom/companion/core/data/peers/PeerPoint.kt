package local.phantom.companion.core.data.peers

/**
 * Гола поверхня обміну між месенджером і мапою. Жодних месенджерних типів і
 * жодних мапних — `PeerDomainPurityFirewallTest` тримає це на рівні імпортів.
 */
data class PeerPoint(
    val nodeId: String,
    val threadId: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    /** Час ВИМІРУ на вузлі відправника, не доставки. */
    val atMs: Long,
    /** Тік шерінгу; дірка в лічильнику = розрив сліду. */
    val seq: Int? = null,
    val accuracyM: Float? = null,
    val verified: Boolean = false,
    val share: PeerShare = PeerShare(ShareMode.ONE_SHOT),
    /** Вирок віку, запечатаний при записі; назад не відкочується. */
    val stale: Boolean = false,
    val etaHint: EtaHint? = null,
    /** Лише для положення людини: без нього це жива точка сеансу. */
    val precision: PeerPrecision? = null,
    /** Межі району чи кварталу; [lat]/[lon] тоді — центр, а не людина. */
    val box: PeerBox? = null,
    val near: PeerNear? = null,
    val motion: PeerMotion? = null,
    /** Лише з точним місцем: дім і робота з власних місць людини. */
    val spot: PeerSpot? = null,
    val lowBattery: Boolean = false,
    /** Фото людини на цьому пристрої, якщо вона його дала. */
    val face: String? = null,
)

enum class PeerMotion { STILL, FOOT, VEHICLE }

enum class PeerSpot { HOME, WORK }

enum class ShareMode { ONE_SHOT, LIVE, TRAIL, WHERE }

enum class PeerPrecision { DISTRICT, QUARTER, EXACT }

enum class PeerNear { SAME, NEAR, FAR }

data class PeerBox(val south: Double, val west: Double, val north: Double, val east: Double)

data class PeerShare(
    val mode: ShareMode,
    val expiresAtMs: Long? = null,
)

/** ETA, порахований на вузлі ВІДПРАВНИКА; [register] — його рівень упевненості. */
data class EtaHint(
    val durationS: Long,
    val distanceM: Double? = null,
    val register: String? = null,
)

const val PEER_FRESH_WINDOW_MS: Long = 90_000L

/**
 * Те саме для [ShareMode.LIVE] — і ширше не з поблажливості.
 *
 * Живий сеанс має ВЛАСНУ домовленість про тишу: відправник мовчить щонайдовше
 * `PeerLiveShare.MAX_GAP_MS` плюс такт (260 с). Судити його загальним вікном у
 * 90 с означало б бліднути на людині, яка стоїть і чесно озивається кожні
 * чотири хвилини, — точка була б «застаріла» дві третини часу. Ширше за дві
 * стелі теж не можна: тоді замовклий телефон лишався б «живим» надто довго.
 * `PeerLiveCadenceTest` тримає обидві межі.
 */
const val PEER_LIVE_FRESH_WINDOW_MS: Long = 300_000L

const val PEER_WHERE_FRESH_WINDOW_MS: Long = 15 * 60_000L

fun PeerPoint.freshAt(nowMs: Long): Boolean =
    !stale && nowMs - atMs <= when (share.mode) {
        ShareMode.LIVE -> PEER_LIVE_FRESH_WINDOW_MS
        ShareMode.WHERE -> PEER_WHERE_FRESH_WINDOW_MS
        else -> PEER_FRESH_WINDOW_MS
    }

/** Разова точка не протухає: у неї немає строку. */
fun PeerPoint.expiredAt(nowMs: Long): Boolean =
    share.expiresAtMs?.let { it <= nowMs } == true

/** Рядок «Хто мене бачить». [eligible] — чи ключ людини звірено камерою: без цього дозволу не дати. */
data class PeerSeeRow(
    val peerId: String,
    val name: String,
    val precision: PeerPrecision?,
    val eligible: Boolean,
    /** Сховались від цієї людини до цієї миті; нуль — ні. */
    val hiddenUntilMs: Long = 0L,
    val inClose: Boolean = false,
)

/** Як я показую себе всім: привид від усіх і чи казати, що телефон сідає. */
data class PeerSelfShow(val hiddenAllUntilMs: Long = 0L, val lowBattery: Boolean = false)

/** «Мій світ» для екрана: лише підсумок і клітинки, без сліду в часі. */
data class MyWorldSummary(
    val quarters: Int = 0,
    val cities: Int = 0,
    val frequent: List<Frequent> = emptyList(),
    val boxes: List<PeerBox> = emptyList(),
) {
    data class Frequent(val label: String?, val lat: Double, val lon: Double, val dwellMs: Long)
}
