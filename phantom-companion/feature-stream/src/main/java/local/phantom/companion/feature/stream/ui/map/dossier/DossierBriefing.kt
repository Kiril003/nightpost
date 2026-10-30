package local.phantom.companion.feature.stream.ui.map.dossier

import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.MapText

internal data class DossierBriefing(
    val text: String = "",
    val phase: Phase = Phase.IDLE,
    val error: String? = null,
) {
    enum class Phase { IDLE, THINKING, STREAMING, DONE, FAILED }

    val isBusy: Boolean get() = phase == Phase.THINKING || phase == Phase.STREAMING
    val hasText: Boolean get() = text.isNotBlank()
}

internal val DOSSIER_BRIEF_SYSTEM: String
    get() = MapText.s(R.string.mapl_b_brief_system)

internal fun dossierBriefPrompt(
    dossier: PlaceDossier,
    origin: Pair<Double, Double>? = null,
    nowMs: Long = System.currentTimeMillis(),
): String = buildString {
    append(MapText.s(R.string.mapl_b_brief_place)).append(dossier.digest())
    append("\n").append(MapText.s(R.string.mapl_b_brief_category))
        .append(dossier.kindLabel ?: dossier.category.labelUk)
    val chips = dossier.quickChips()
    if (chips.isNotEmpty()) {
        append("\n").append(MapText.s(R.string.mapl_b_brief_traits)).append(chips.joinToString(", "))
    }
    dossier.extraFacts().forEach { (label, value) ->
        append("\n").append(label).append(": ").append(value)
    }
    val facts = local.phantom.companion.feature.stream.domain.BriefingFacts
    when (val status = dossier.openStatus) {
        is local.phantom.companion.core.net.map.OpeningHours.Status.Open ->
            append("\n").append(facts.LABEL_STATE)
                .append(facts.openLine(open = true, boundaryMinuteOfDay = status.untilMinuteOfDay))
        is local.phantom.companion.core.net.map.OpeningHours.Status.Closed ->
            append("\n").append(facts.LABEL_STATE)
                .append(facts.openLine(open = false, boundaryMinuteOfDay = status.opensAtMinuteOfDay))
        else -> Unit
    }
    facts.feeLine(dossier.tags["fee"])?.let { append("\n").append(facts.LABEL_FEE).append(it) }
    origin?.let { (lat, lon) ->
        append("\n").append(facts.LABEL_FROM_OPERATOR)
            .append(facts.distance(lat, lon, dossier.target.lat, dossier.target.lon))
    }
    append("\n").append(facts.LABEL_NOW).append(facts.clock(nowMs))
    val nearest = dossier.similar.firstOrNull()
    facts.similarLine(
        count = dossier.similar.size,
        nearestName = nearest?.name,
        nearestMetres = nearest?.let {
            facts.metresBetween(dossier.target.lat, dossier.target.lon, it.lat, it.lon)
        },
    )?.let { append("\n").append(facts.LABEL_SIMILAR).append(it) }
    dossier.reference?.takeIf { it.isAboutEntity }?.let {
        append("\n").append(MapText.s(R.string.mapl_b_brief_reference))
            .append(it.article.extract.take(REFERENCE_CHARS))
    }
    if (dossier.identitySource == DossierSourceKind.ROAD_MESH) {
        append("\n").append(facts.NOTE_IDENTITY_GUESSED)
    }
    append("\n\n").append(facts.QUESTION)
}

internal fun dossierBriefQuestion(dossier: PlaceDossier): String =
    MapText.s(R.string.mapl_b_brief_question, dossier.title)

private const val REFERENCE_CHARS = 400
