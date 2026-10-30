package local.phantom.companion.feature.stream.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.feature.stream.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Report, alert and map sheets on an English phone show no Cyrillic (owner 29.10). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class StreamEnglishTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val cyrillic = Regex("[\\u0400-\\u04FF]")

    @Test
    fun `moved strings are Latin under en`() {
        val bad = R.string::class.java.fields.filter { it.name.startsWith("kt_") }
            .filter { cyrillic.containsMatchIn(ctx.getString(it.getInt(null))) }.map { it.name }
        assertTrue("Cyrillic under en: $bad", bad.isEmpty())
        assertEquals("3 sections", ctx.resources.getQuantityString(R.plurals.kt_report_sections, 3, 3))
    }

    @Test
    @Config(qualifiers = "uk")
    fun `Ukrainian plurals keep their three forms`() {
        val r = ctx.resources
        assertEquals("1 розділ", r.getQuantityString(R.plurals.kt_report_sections, 1, 1))
        assertEquals("3 розділи", r.getQuantityString(R.plurals.kt_report_sections, 3, 3))
        assertEquals("5 розділів", r.getQuantityString(R.plurals.kt_report_sections, 5, 5))
    }
}
