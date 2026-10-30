package local.phantom.companion.self

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.data.AppEventBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TalkEntryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        TalkEntry.resetForTest()
    }

    @Test
    fun `the talk action opens the call`() {
        val events = AppEventBus()
        val handled = TalkEntry.route(context, TalkEntry.intent(context), events)
        assertTrue(handled)
        assertTrue(events.phantomCallOpen.value)
    }

    @Test
    fun `other intents are left alone`() {
        val events = AppEventBus()
        assertFalse(TalkEntry.route(context, null, events))
        assertFalse(events.phantomCallOpen.value)

        val viewIntent = Intent(Intent.ACTION_VIEW)
        assertFalse(TalkEntry.route(context, viewIntent, events))
        assertFalse(events.phantomCallOpen.value)
    }

    @Test
    fun `the shortcut opens the talk action`() {
        val events = AppEventBus()
        TalkEntry.route(context, null, events)
        val shortcut = ShortcutManagerCompat.getDynamicShortcuts(context).firstOrNull { it.id == "talk" }
        assertNotNull(shortcut)
        assertEquals(TalkEntry.ACTION, shortcut?.intent?.action)
    }
}
