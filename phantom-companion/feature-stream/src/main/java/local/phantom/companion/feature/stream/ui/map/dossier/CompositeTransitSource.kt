package local.phantom.companion.feature.stream.ui.map.dossier

/** Кілька джерел ліній зупинки, зведені в одне. */
internal class CompositeTransitSource(
    private vararg val sources: DossierTransitSource,
) : DossierTransitSource {
    override suspend fun transitAt(lat: Double, lon: Double, radiusM: Int): DossierTransit? {
        val results = sources.mapNotNull { runCatching { it.transitAt(lat, lon, radiusM) }.getOrNull() }
        if (results.isEmpty()) return null
        return results.reduce { a, b ->
            a.copy(
                lines = mergeTransitLines(a.lines, b.lines),
                interchanges = (a.interchanges + b.interchanges).distinct(),
                serviceStartSec = a.serviceStartSec ?: b.serviceStartSec,
                serviceEndSec = a.serviceEndSec ?: b.serviceEndSec,
                bothDirections = a.bothDirections || b.bothDirections,
            )
        }
    }
}
