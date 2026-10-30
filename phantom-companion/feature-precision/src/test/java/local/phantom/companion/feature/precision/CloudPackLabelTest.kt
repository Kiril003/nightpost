package local.phantom.companion.feature.precision

import org.junit.Assert.assertEquals
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "uk")
class CloudPackLabelTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `sizes at or above one gigabyte render in gigabytes with one decimal`() {
        assertEquals("1,3 ГБ", byteSizeLabel(ctx, 1_300_000_000L))
        assertEquals("5,9 ГБ", byteSizeLabel(ctx, 5_900_000_000L))
    }

    @Test
    fun `sizes under one gigabyte render in whole megabytes`() {
        assertEquals("288 МБ", byteSizeLabel(ctx, 288_000_000L))
    }

    @Test
    fun `catalog rows without a positive size are refused before ever being shown`() {
        assertEquals(
            false,
            local.phantom.companion.core.net.pack.WorldPackCatalogEntry(
                id = "x",
                kind = local.phantom.companion.core.net.pack.WorldPackKind.BASEMAP,
                country = "UA",
                labelUa = "Х",
                bytes = 0L,
            ).wellFormed,
        )
    }
}
