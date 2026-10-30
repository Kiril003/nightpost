package local.phantom.companion.feature.stream.ui.map.dossier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «відчинено · 7 хв пішки · безкоштовно» — вирок 15.09: платність вирішує
 * «зайти чи ні» так само, як статус і відстань, тож стоїть поруч із ними в
 * піку, а не чекає розкриття картки.
 */
class DossierPeakLineTest {

    private val words = PeakWords(
        openUntil = { "opens until $it" },
        open = "open",
        closedUntil = { "closed until $it" },
        closed = "closed",
        floor = { "$it floor" },
        inContainer = { "in $it" },
        walk = WalkWords(
            minutes = { "$it m" },
            hours = { "$it h" },
            hoursMinutes = { h, m -> "$h h $m m" },
        ),
        feePaid = "paid",
        feeFree = "free",
    )

    private fun dossierWithFee(fee: String?): PlaceDossier {
        val tags = fee?.let { mapOf("fee" to it) } ?: emptyMap()
        val target = DossierTarget(lat = 49.83, lon = 18.29)
        val entity = entityRefOf(tags = tags)
        return PlaceDossier(
            target = target,
            title = "?",
            kindLabel = null,
            category = entity.category,
            entity = entity,
            address = null,
            tags = tags,
            openStatus = null,
            rawHours = null,
            building = null,
            reference = null,
            transitLines = emptyList(),
            similar = emptyList(),
        )
    }

    @Test
    fun `fee=no додає сегмент «безкоштовно» у пік`() {
        val segments = peakSegments(dossierWithFee("no"), origin = null, words = words)
        assertTrue(segments.any { it.text == "free" })
    }

    @Test
    fun `fee=yes додає сегмент «платно» у пік`() {
        val segments = peakSegments(dossierWithFee("yes"), origin = null, words = words)
        assertTrue(segments.any { it.text == "paid" })
    }

    @Test
    fun `без тега fee пік не вигадує платність`() {
        val segments = peakSegments(dossierWithFee(null), origin = null, words = words)
        assertTrue(segments.none { it.text == "paid" || it.text == "free" })
    }

    @Test
    fun `платність — останній сегмент, після відстані`() {
        val origin = 49.80 to 18.12
        val segments = peakSegments(dossierWithFee("no"), origin = origin, words = words)
        assertEquals("free", segments.last().text)
    }

    @Test
    fun `the line starts with a capital, whatever case the kind label has`() {
        val d = dossierWithFee(null).copy(kindLabel = "аптека")
        assertEquals("Аптека", peakSegments(d, origin = null, words = words).first().text)
    }
}
