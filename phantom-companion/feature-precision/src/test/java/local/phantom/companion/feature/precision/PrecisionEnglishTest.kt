package local.phantom.companion.feature.precision

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.core.gnss.Constellation
import local.phantom.companion.core.gnss.FixSource
import local.phantom.companion.core.mobility.TransportMode
import local.phantom.companion.core.net.roadpack.RoadPackLinkClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** An English phone must not see Cyrillic on the precision screen (owner 29.10). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class PrecisionEnglishTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val cyrillic = Regex("[\\u0400-\\u04FF]")

    @Test
    fun `every string of the module is Latin under en`() {
        val bad = R.string::class.java.fields.filter { f ->
            val v = ctx.getString(f.getInt(null))
            f.name.startsWith("precision_") && cyrillic.containsMatchIn(v)
        }.map { it.name }
        assertTrue("Cyrillic under en: $bad", bad.isEmpty())
    }

    @Test
    fun `labels built in code are Latin under en`() {
        val all = FixSource.values().map { sourceLabel(ctx, it) } +
            TransportMode.values().map { modeLabel(ctx, it) } +
            Constellation.values().map { constellationLabel(ctx, it) } +
            listOf(formatKm(ctx, 5.0), formatKm(ctx, 42.0), formatKm(ctx, 12_270.0)) +
            ctx.resources.getQuantityString(R.plurals.precision_roads_imported, 3, 3) +
            roadPackOfferLabel(
                ctx,
                RoadPackLinkClient.RoadPackManifest(
                    pack_id = "p", label = "Ukraine", format_version = 1, format_supported = true,
                    baked_at = "2026-07-24T10:41:16Z", bytes_total = 1_500_000_000L,
                    content_hash = "x", way_count = 1,
                ),
            )
        val bad = all.filter { cyrillic.containsMatchIn(it) }
        assertTrue("Cyrillic under en: $bad", bad.isEmpty())
        assertEquals("12 thousand km", formatKm(ctx, 12_270.0))
    }
}
