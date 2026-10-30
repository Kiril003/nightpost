package local.phantom.companion.data

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** K3: the phone calendar is read only with the grant, and only today's window. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneCalendarTest {

    class FakeCalendar : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor {
            queried += uri to (s.orEmpty() to o.orEmpty())
            return table()
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `rows read the columns by name and keep a missing title empty`() {
        val rows = PhoneCalendar.rows(table())
        assertEquals(2, rows.size)
        assertEquals("7:1000", rows[0].id)
        assertEquals("Dentist", rows[0].title)
        assertEquals(1_000L, rows[0].beginMs)
        assertEquals(2_000L, rows[0].endMs)
        assertFalse(rows[0].allDay)
        assertEquals("", rows[1].title)
        assertTrue(rows[1].allDay)
    }

    @Test
    fun `without the grant nothing is read and nothing throws`() {
        val calendar = PhoneCalendar(context)
        Robolectric.setupContentProvider(FakeCalendar::class.java, CalendarContract.AUTHORITY)
        queried.clear()
        assertFalse(calendar.granted())
        assertTrue(calendar.instances(0L, 10_000L).isEmpty())
        assertTrue(queried.isEmpty())
    }

    @Test
    fun `with the grant the query covers the asked window and returns the mapped events`() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CALENDAR)
        Robolectric.setupContentProvider(FakeCalendar::class.java, CalendarContract.AUTHORITY)
        queried.clear()
        val calendar = PhoneCalendar(context)
        assertTrue(calendar.granted())

        val events = calendar.instances(1_700_000_000_000L, 1_700_086_400_000L)
        assertEquals(listOf("Dentist", ""), events.map { it.title })
        assertEquals(1, queried.size)
        val (uri, rest) = queried.single()
        assertTrue(uri.toString(), uri.toString().endsWith("/1700000000000/1700086400000"))
        assertTrue(rest.first, rest.first.contains(CalendarContract.Instances.VISIBLE))
        assertTrue(rest.second, rest.second.contains(CalendarContract.Instances.BEGIN))
    }

    @Test
    fun `a provider that is missing yields an empty list instead of a crash`() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CALENDAR)
        assertTrue(PhoneCalendar(context).instances(0L, 10_000L).isEmpty())
    }

    companion object {
        val queried = mutableListOf<Pair<Uri, Pair<String, String>>>()

        fun table(): MatrixCursor = MatrixCursor(
            arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
            ),
        ).apply {
            addRow(arrayOf(7L, "Dentist", 1_000L, 2_000L, 0))
            addRow(arrayOf(9L, null, 5_000L, 9_000L, 1))
        }
    }
}
