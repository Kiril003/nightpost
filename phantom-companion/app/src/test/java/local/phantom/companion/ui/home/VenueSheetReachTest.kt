package local.phantom.companion.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VenueSheetReachTest {
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root not found") as Nothing
    }

    private fun code(rel: String): String =
        File(repoRoot(), rel).readLines()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n")

    private val map = "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map"

    @Test
    fun `place sheet mounts the venue section for a venue target only`() {
        val sheet = code("$map/dossier/PlaceDossierSheet.kt")
        assertTrue(sheet.contains("import local.phantom.companion.feature.stream.ui.map.venue.PlaceVenueSection"))
        assertTrue(sheet.contains("target?.venueId?.let { PlaceVenueSection(it) }"))
        assertTrue(sheet.contains("placeHeroUrl(model)?.let { PlaceHero(it) }"))
        assertFalse("рейтинг прибрано зі сторінки місця (24.10)", sheet.contains("placeRatingLine("))
    }

    @Test
    fun `map tab provides the venue port to the map and still passes the venue to Meet here`() {
        val tab = code("app/src/main/java/local/phantom/companion/ui/home/MapTab.kt")
        assertTrue(tab.contains("VenuePageClient("))
        assertTrue(tab.contains("LocalVenuePort provides venuePort"))
        assertTrue(tab.contains("container.claims::prove"))
        assertTrue(tab.contains("WebMessengerMeetupDraft.meetHere(lat, lon, label, venueId = venueId)"))
    }

    @Test
    fun `venue section has no literal text and the three string files carry the same keys`() {
        val section = code("$map/venue/PlaceVenueSection.kt")
        assertFalse("Cyrillic literal in Kotlin UI code", Regex("[\\u0400-\\u04FF]").containsMatchIn(section))
        assertTrue(section.contains("testTag(\"venue-section\")"))
        assertFalse("clickable target below 48dp", Regex("""\.size\([123]\d\.dp\)\s*\.clickable""").containsMatchIn(section))

        val res = "feature-stream/src/main/res"
        fun keys(dir: String): Set<String> {
            val text = File(repoRoot(), "$res/$dir/strings_venue_card.xml").readText()
            return Regex("""<string name="([^"]+)"""").findAll(text).map { it.groupValues[1] }.toSet()
        }
        val expected = setOf(
            "venue_card_verified",
            "venue_card_hours",
            "venue_card_menu",
            "venue_card_menu_more",
            "venue_card_events",
            "venue_card_going_count",
            "venue_card_going_of",
            "venue_card_going",
            "venue_card_youre_going",
            "venue_card_full",
            "venue_card_cancelled",
            "venue_card_ended",
            "venue_card_load_failed",
            "venue_card_retry",
        )
        val en = keys("values")
        val uk = keys("values-uk")
        val cs = keys("values-cs")
        assertEquals(expected, en)
        assertEquals(en, uk)
        assertEquals(en, cs)
        val ukText = File(repoRoot(), "$res/values-uk/strings_venue_card.xml").readText()
        assertFalse("ASCII apostrophe in uk strings", Regex("""[А-ЯІЇЄҐа-яіїєґ]'[А-ЯІЇЄҐа-яіїєґ]""").containsMatchIn(ukText))
    }
}
