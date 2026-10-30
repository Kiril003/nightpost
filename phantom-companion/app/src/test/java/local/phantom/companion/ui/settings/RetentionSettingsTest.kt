package local.phantom.companion.ui.settings

import android.app.Application
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric: `getBestDateTimePattern` is ICU here, not the android.jar stub that returns null. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RetentionSettingsTest {

    private val sampleEpochMs: Long = Calendar.getInstance(TimeZone.getDefault()).apply {
        set(2026, Calendar.JULY, 18, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `English puts the month first`() {
        assertEquals("July 18", formatDay(sampleEpochMs, Locale.US))
    }

    @Test
    fun `Ukrainian puts the day first with the month in genitive`() {
        assertEquals("18 липня", formatDay(sampleEpochMs, Locale.forLanguageTag("uk")))
    }

    @Test
    fun `the pattern comes from the locale, not from the fallback`() {
        assertEquals("18. Juli", formatDay(sampleEpochMs, Locale.GERMAN))
    }
}
