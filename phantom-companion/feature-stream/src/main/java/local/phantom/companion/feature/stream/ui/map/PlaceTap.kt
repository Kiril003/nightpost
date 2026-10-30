package local.phantom.companion.feature.stream.ui.map

import kotlin.math.cos
import kotlin.math.hypot
import local.phantom.companion.feature.stream.ui.map.dossier.DossierTarget

internal object PlaceTap {
    const val SAME_PLACE_M = 25.0

    fun samePlace(a: DossierTarget, b: DossierTarget): Boolean {
        if (a.venueId != null && b.venueId != null) return a.venueId == b.venueId
        val an = a.nameHint?.trim()?.takeIf { it.isNotEmpty() }
        val bn = b.nameHint?.trim()?.takeIf { it.isNotEmpty() }
        if (an != null && bn != null && !an.equals(bn, ignoreCase = true)) return false
        val dy = (a.lat - b.lat) * M_PER_DEG
        val dx = (a.lon - b.lon) * M_PER_DEG * cos(Math.toRadians(a.lat))
        return hypot(dx, dy) <= SAME_PLACE_M
    }

    fun next(open: DossierTarget?, tapped: DossierTarget?): DossierTarget? =
        if (open != null && tapped != null && samePlace(open, tapped) && !richer(tapped, open)) open else tapped

    private fun richer(t: DossierTarget, o: DossierTarget): Boolean =
        (t.venueId != null && o.venueId == null) || (t.rating != null && t.rating != o.rating)

    private const val M_PER_DEG = 111_320.0
}
