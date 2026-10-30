package local.phantom.companion.feature.stream.ui.map.here

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.phantom.companion.core.truth.CellId
import local.phantom.companion.core.truth.FactKind
import local.phantom.companion.core.truth.H3
import local.phantom.companion.core.truth.Intake
import local.phantom.companion.core.truth.IntakeGate
import local.phantom.companion.core.truth.Place
import local.phantom.companion.core.truth.Reading
import local.phantom.companion.core.truth.Submission
import local.phantom.companion.core.truth.Verdict
import local.phantom.companion.feature.stream.ui.map.AlertSky
import local.phantom.companion.feature.stream.ui.map.holdsReportsAt

enum class HereReach { LOADING, ON, OFF, UNREACHABLE }

data class HereRow(val kind: FactKind, val verdict: Verdict?, val held: Boolean, val sent: Boolean)

sealed interface HereNotice {
    data class Refused(val uk: String?, val en: String?) : HereNotice
    data class Limited(val minutes: Int) : HereNotice
    data object NoKey : HereNotice
    data object Failed : HereNotice
}

data class HereNowState(
    val cell: CellId? = null,
    val reach: HereReach = HereReach.LOADING,
    val rows: List<HereRow> = emptyList(),
    val skyHolds: Boolean = false,
    val notice: HereNotice? = null,
)

class HereNowDesk(
    private val port: HereTruthPort,
    private val sky: StateFlow<AlertSky>,
    private val oblastAt: (Double, Double) -> String?,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val offMain: kotlin.coroutines.CoroutineContext = Dispatchers.Default,
) {
    private data class Held(val cell: CellId, val lat: Double, val lon: Double, val kind: FactKind, val reading: Reading, val atMs: Long)

    private val _state = MutableStateFlow(HereNowState())
    val state: StateFlow<HereNowState> = _state.asStateFlow()

    private var point: Pair<Double, Double>? = null
    private var verdicts: Map<FactKind, Verdict> = emptyMap()
    private val held = mutableListOf<Held>()
    private val sent = mutableSetOf<Pair<CellId, FactKind>>()
    private var job: Job? = null

    init {
        scope.launch { sky.collect { onSky() } }
    }

    fun open(lat: Double, lon: Double) {
        val cell = H3.cell(lat, lon)
        point = lat to lon
        verdicts = emptyMap()
        _state.value = HereNowState(cell = cell, reach = HereReach.LOADING)
        job?.cancel()
        job = scope.launch {
            val holds = holdsAt(lat, lon)
            val reply = port.area(H3.area(cell))
            if (point != lat to lon) return@launch
            val reach = when (reply) {
                is AreaReply.Verdicts -> {
                    verdicts = reply.list.filter { it.key.place.cell == cell && it.key.place.objectId == null }
                        .associateBy { it.key.kind }
                    HereReach.ON
                }
                AreaReply.NotEnabled -> HereReach.OFF
                AreaReply.Unreachable -> HereReach.UNREACHABLE
            }
            _state.update { it.copy(reach = reach, skyHolds = holds, rows = rows(cell)) }
        }
    }

    fun close() {
        job?.cancel()
        point = null
    }

    fun report(kindWire: String, readingWire: String) {
        val (lat, lon) = point ?: return
        val cell = _state.value.cell ?: return
        if (_state.value.reach != HereReach.ON) return
        val gate = IntakeGate.check(Submission(kindWire, readingWire, Place(cell)))
        if (gate is Intake.Refused) {
            _state.update { it.copy(notice = HereNotice.Refused(gate.reason.uk, gate.reason.en)) }
            return
        }
        val ok = gate as Intake.Accepted
        if (ok.kind !in FactKind.CELL) return
        scope.launch {
            if (ok.kind.embargoDuringAlert && holdsAt(lat, lon)) {
                held.removeAll { it.cell == cell && it.kind == ok.kind }
                held += Held(cell, lat, lon, ok.kind, ok.reading, now())
                _state.update { it.copy(skyHolds = true, notice = null, rows = rows(cell)) }
                return@launch
            }
            val notice = send(cell, ok.kind, ok.reading)
            _state.update { it.copy(notice = notice, rows = rows(cell)) }
        }
    }

    private suspend fun send(cell: CellId, kind: FactKind, reading: Reading): HereNotice? =
        when (val r = port.observe(cell, kind, reading)) {
            ObserveReply.Accepted -> { sent += cell to kind; null }
            is ObserveReply.Refused -> HereNotice.Refused(r.uk, r.en)
            is ObserveReply.Limited -> HereNotice.Limited((r.retryAfterS + 59) / 60)
            ObserveReply.NoKey -> HereNotice.NoKey
            ObserveReply.NotEnabled -> { _state.update { it.copy(reach = HereReach.OFF) }; null }
            ObserveReply.Unreachable -> HereNotice.Failed
        }

    private suspend fun onSky() {
        val t = now()
        held.removeAll { t - it.atMs > it.kind.halfLifeMs }
        for (h in held.toList()) {
            if (holdsAt(h.lat, h.lon)) continue
            if (port.observe(h.cell, h.kind, h.reading) == ObserveReply.Accepted) sent += h.cell to h.kind
            held.remove(h)
        }
        val (lat, lon) = point ?: return
        val holds = holdsAt(lat, lon)
        _state.update { s -> s.copy(skyHolds = holds, rows = s.cell?.let(::rows) ?: s.rows) }
    }

    private suspend fun holdsAt(lat: Double, lon: Double): Boolean {
        val oblast = withContext(offMain) { runCatching { oblastAt(lat, lon) }.getOrNull() }
        return sky.value.holdsReportsAt(oblast, now())
    }

    private fun rows(cell: CellId): List<HereRow> = FactKind.CELL.map { k ->
        HereRow(k, verdicts[k], held.any { it.cell == cell && it.kind == k }, (cell to k) in sent)
    }
}
