package local.phantom.companion.core.truth

/** Шестикутник H3 восьмої роздільності (ребро ~461 м). Рушій його не рахує, лише порівнює. */
@JvmInline
value class CellId(val value: String)

@JvmInline
value class VoterId(val value: String)

/** Обʼєкт мапи ([objectId]) завжди несе і свою комірку — інакше не перевірити присутність. */
data class Place(val cell: CellId, val objectId: String? = null)

data class FactKey(val place: Place, val kind: FactKind)

/**
 * [networkId] — сервер виводить сам із мережі запиту (сіль доби, /24 чи /48),
 * клієнт його не надсилає. [invitedBy] — хто впустив, якщо вхід був через запрошення.
 */
data class Voter(
    val id: VoterId,
    val createdAtMs: Long,
    val networkId: String? = null,
    val invitedBy: VoterId? = null,
)

/** Пристрій заявляє, що був у комірці факту протягом вікна. Інших комірок він не шле. */
data class Presence(val cell: CellId, val fromMs: Long, val toMs: Long) {
    fun covers(cell: CellId, atMs: Long, slackMs: Long): Boolean =
        this.cell == cell && atMs >= fromMs - slackMs && atMs <= toMs + slackMs
}

data class Observation(
    val voter: VoterId,
    val key: FactKey,
    val reading: Reading,
    val atMs: Long,
    val presence: Presence? = null,
)

/** Чотири дії людини. Усі зводяться до одного — свідчення з показом і часом. */
sealed interface Claim {
    data class Assert(val reading: Reading) : Claim
    data object Confirm : Claim
    data object Deny : Claim
    data class Update(val reading: Reading) : Claim
}

enum class TruthState { LIKELY, UNKNOWN, STALE }

data class Verdict(
    val key: FactKey,
    val state: TruthState,
    val reading: Reading?,
    val probability: Double,
    val mass: Double,
    val supporters: Int,
    val lastSupportAtMs: Long?,
    val newestAtMs: Long?,
    val dissent: Boolean,
    val contested: Boolean = false,
) {
    /** «Ймовірно так / ймовірно ні» для двозначних видів; для черги — null. */
    val yes: Boolean?
        get() = if (state == TruthState.LIKELY && key.kind.positive != null) reading == key.kind.positive else null
}

/**
 * Підтвердити можна лише те, що рушій уже вважає ймовірним; заперечити — лише
 * двозначне. Інакше «підтверджую» стало б свідченням невідомо про що.
 */
fun Claim.toReading(kind: FactKind, current: Verdict?): Reading? = when (this) {
    is Claim.Assert -> reading.takeIf { it in kind.readings }
    is Claim.Update -> reading.takeIf { it in kind.readings }
    Claim.Confirm -> current?.takeIf { it.state == TruthState.LIKELY }?.reading
    Claim.Deny -> current?.takeIf { it.state == TruthState.LIKELY }?.reading?.let(kind::opposite)
}
