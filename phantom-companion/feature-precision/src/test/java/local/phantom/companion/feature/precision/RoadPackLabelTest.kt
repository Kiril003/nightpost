package local.phantom.companion.feature.precision

import local.phantom.companion.core.net.roadpack.RoadPackLinkClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 2 (map-plan) — the manifest-driven offer string, pinned.
 *
 * The whole point of Phase 2 is that the phone never hardcodes «Україна,
 * 1,5 ГБ, спечено 24.07» — it reads those numbers from the PC's manifest.
 * This test is the one place that promise is checked mechanically rather
 * than by eye.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "uk")
class RoadPackLabelTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun manifest(
        label: String = "Україна",
        bytesTotal: Long = 1_500_000_000L,
        bakedAt: String = "2026-07-24T10:41:16Z",
        formatSupported: Boolean = true,
    ) = RoadPackLinkClient.RoadPackManifest(
        pack_id = "ukraine",
        label = label,
        format_version = 1,
        format_supported = formatSupported,
        baked_at = bakedAt,
        bytes_total = bytesTotal,
        content_hash = "sha256:deadbeef",
        way_count = 2_582_970,
    )

    @Test
    fun `offer string is built from manifest fields, not hardcoded`() {
        val label = roadPackOfferLabel(ctx, manifest())
        assertEquals("Оновити дорожній пакет: Україна, 1,5 ГБ, спечено 24.07", label)
    }

    @Test
    fun `a different pack produces a genuinely different string`() {
        val label = roadPackOfferLabel(
            ctx,
            manifest(label = "Київ", bytesTotal = 44_000_000L, bakedAt = "2026-07-24T10:41:16Z"),
        )
        assertEquals("Оновити дорожній пакет: Київ, 44 МБ, спечено 24.07", label)
    }

    @Test
    fun `sizes under one gigabyte render in megabytes`() {
        val label = roadPackOfferLabel(ctx, manifest(bytesTotal = 899_000_000L))
        assert(label.contains("899 МБ")) { "expected megabytes, got: $label" }
    }

    @Test
    fun `an unparseable baked_at omits the date clause instead of showing garbage`() {
        val label = roadPackOfferLabel(ctx, manifest(bakedAt = ""))
        assertEquals("Оновити дорожній пакет: Україна, 1,5 ГБ", label)
    }

    @Test
    fun `date parsing pulls day-month from the iso timestamp`() {
        assertEquals("24.07", roadPackBakedDateLabel("2026-07-24T10:41:16Z"))
        assertEquals("01.01", roadPackBakedDateLabel("2026-01-01T00:00:00Z"))
    }

    @Test
    fun `a malformed timestamp is null, never a crash or a wrong date`() {
        assertNull(roadPackBakedDateLabel(""))
        assertNull(roadPackBakedDateLabel("not-a-date"))
        assertNull(roadPackBakedDateLabel("2026"))
    }
}
