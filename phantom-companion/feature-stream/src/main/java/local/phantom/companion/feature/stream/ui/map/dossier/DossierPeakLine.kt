package local.phantom.companion.feature.stream.ui.map.dossier

import local.phantom.companion.core.net.map.OpeningHours
import local.phantom.companion.feature.stream.ui.map.streetOf

/**
 * Пік картки одним рядком: «магазин · електроніка · 1 поверх · у «Retroville» ·
 * за розкладом відчинено до 22:00 · 280 м · ≈ 4 хв пішки».
 *
 * Було: вид місця окремим кікером, відстань окремим рядом, стан окремим чіпом —
 * три поверхи по 28–40dp над першим фактом. Стало: сегменти одного рядка;
 * кожен існує рівно тоді, коли існує його факт (Закон 1).
 *
 * Стан НІКОЛИ не каже «Відчинено» голим словом: `opening_hours` описує розклад,
 * а не блекаут, тож формулювання завжди «за розкладом …».
 */
internal enum class PeakTone { NEUTRAL, POSITIVE, DANGER }

internal data class PeakSegment(val text: String, val tone: PeakTone = PeakTone.NEUTRAL)

/**
 * Слова піку — щоб функція лишалась чистою і покривалась JVM-тестом, а рядки
 * жили в ресурсах. Кожне поле віддає вже зібраний український текст.
 */
internal data class PeakWords(
    val openUntil: (String) -> String,
    val open: String,
    val closedUntil: (String) -> String,
    val closed: String,
    val floor: (String) -> String,
    val inContainer: (String) -> String,
    val walk: WalkWords,
    val feePaid: String,
    val feeFree: String,
)

internal fun peakSegments(
    dossier: PlaceDossier,
    origin: Pair<Double, Double>?,
    words: PeakWords,
): List<PeakSegment> = buildList {
    (dossier.kindLabel ?: dossier.category.labelUk)
        .takeIf { it.isNotBlank() }
        ?.let { add(PeakSegment(it.replaceFirstChar { ch -> ch.uppercase() })) }
    streetOf(dossier.address, dossier.title)?.let { add(PeakSegment(it)) }

    dossier.level?.let { add(PeakSegment(words.floor(it))) }
    dossier.containerName
        ?.takeIf { it != dossier.title }
        ?.let { add(PeakSegment(words.inContainer(it))) }

    when (val status = dossier.openStatus) {
        is OpeningHours.Status.Open -> add(
            PeakSegment(
                text = status.untilMinuteOfDay
                    ?.let { words.openUntil(OpeningHours.fmtMinute(it)) }
                    ?: words.open,
                tone = PeakTone.POSITIVE,
            ),
        )
        is OpeningHours.Status.Closed -> add(
            PeakSegment(
                text = status.opensAtMinuteOfDay
                    ?.let { words.closedUntil(OpeningHours.fmtMinute(it)) }
                    ?: words.closed,
                tone = PeakTone.DANGER,
            ),
        )
        else -> Unit
    }

    proximity(origin, dossier.target.lat, dossier.target.lon)
        ?.let { add(PeakSegment("${formatDistance(it.meters)} · ${walkEtaText(it.walkMinutes, words.walk)}")) }

    // «відчинено · 7 хв пішки · безкоштовно» — вирок 15.09: платність теж
    // вирішує «зайти чи ні», тож стоїть у піку, а не чекає розкриття.
    when (dossier.tags["fee"]) {
        "yes" -> add(PeakSegment(words.feePaid))
        "no" -> add(PeakSegment(words.feeFree, tone = PeakTone.POSITIVE))
        else -> Unit
    }
}
