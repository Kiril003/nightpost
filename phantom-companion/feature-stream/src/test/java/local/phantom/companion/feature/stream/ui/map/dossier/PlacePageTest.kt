package local.phantom.companion.feature.stream.ui.map.dossier

import local.phantom.companion.core.data.peers.PeerSeeRow
import local.phantom.companion.feature.stream.ui.map.around.AroundPlace
import local.phantom.companion.feature.stream.ui.map.around.AroundRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlacePageTest {

    private val target = DossierTarget(50.0, 14.0, nameHint = "Kavárna")
    private fun article(thumb: String?) = DossierArticle("Kavárna", "A café.", thumb, "https://w/K", null)

    @Test
    fun `photo leads the page only when the article is about this very place`() {
        val own = instantDossier(target).copy(reference = DossierReference(article("https://img/k.jpg"), WikiVerdict.ENTITY))
        val street = instantDossier(target).copy(reference = DossierReference(article("https://img/s.jpg"), WikiVerdict.NEARBY))
        val blank = instantDossier(target).copy(reference = DossierReference(article(" "), WikiVerdict.ENTITY))
        assertEquals("https://img/k.jpg", placeHeroUrl(own))
        assertNull(placeHeroUrl(street))
        assertNull(placeHeroUrl(blank))
        assertNull(placeHeroUrl(instantDossier(target)))
    }

    @Test
    fun `a place from Around you keeps its rating on the page`() {
        val rated = AroundPlace("Kavárna", 50.0, 14.0, kind = "cafe", venueId = "v1", rating = AroundRating(4.66, 6), category = "food")
        val t = rated.dossierTarget()
        assertEquals(AroundRating(4.66, 6), t.rating)
        assertEquals("Kavárna", t.nameHint)
        assertEquals("food", t.categoryHint)
        assertNull(AroundPlace("Bar", 50.0, 14.0).dossierTarget().rating)
    }

    @Test
    fun `a Nightpost venue keeps its id on the page, a map place has none`() {
        val venue = AroundPlace("Kavárna", 50.0, 14.0, kind = "cafe", venueId = "0123456789abcdef")
        assertEquals("0123456789abcdef", venue.dossierTarget().venueId)
        assertNull(AroundPlace("Bar", 50.0, 14.0).dossierTarget().venueId)
    }

    @Test
    fun `rating line is the mean and the count, nothing without ratings`() {
        val fmt = { mean: String, n: Int -> "$mean · $n ratings" }
        assertEquals("4.7 · 6 ratings", placeRatingLine(AroundRating(4.66, 6), fmt, java.util.Locale.ENGLISH))
        assertEquals("4,7 · 6 ratings", placeRatingLine(AroundRating(4.66, 6), fmt, java.util.Locale("uk")))
        assertNull(placeRatingLine(AroundRating(0.0, 0), fmt, java.util.Locale.ENGLISH))
        assertNull(placeRatingLine(null, fmt, java.util.Locale.ENGLISH))
    }

    @Test
    fun `send to a chat lists each named person once, by name`() {
        val rows = listOf(
            PeerSeeRow("b", "Olena", null, eligible = false),
            PeerSeeRow("a", "Marko", null, eligible = true),
            PeerSeeRow("a", "Marko", null, eligible = true),
            PeerSeeRow("c", " ", null, eligible = true),
        )
        assertEquals(listOf("a" to "Marko", "b" to "Olena"), placeSendPeople(rows))
    }
}
