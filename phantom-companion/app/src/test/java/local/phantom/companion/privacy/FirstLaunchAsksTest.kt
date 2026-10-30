package local.phantom.companion.privacy

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Свіже встановлення не зустрічає людину чергою системних вікон. Мікрофон,
 * місце й камеру питає функція, якій вони потрібні, у мить дотику; на старті —
 * щонайбільше сповіщення, і то після рядка «навіщо».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FirstLaunchAsksTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Тиша після чужого вікна — спільний стан процесу; кожен тест починає без неї. */
    @org.junit.Before
    fun noRecentAnswer() {
        local.phantom.companion.core.design.permission.PermissionMoment.answeredAtMs = 0L
    }

    @Test
    fun `перший запуск питає щонайбільше сповіщення`() {
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), LaunchAsks.permissions(sdk = 34))
        assertEquals(emptyList<String>(), LaunchAsks.permissions(sdk = 32))
    }

    @Test
    fun `питання без відповіді не вважається заданим - смерть процесу його не з'їдає`() {
        assertTrue(NotificationAsk.due(context, NotificationAsk.Source.LAUNCH))

        assertTrue("жодна відповідь не прийшла - питаємо знову", NotificationAsk.due(context, NotificationAsk.Source.LAUNCH))
        NotificationAsk.answered(context, NotificationAsk.Source.LAUNCH)
        assertFalse(NotificationAsk.due(context, NotificationAsk.Source.LAUNCH))
    }

    @Test
    fun `«Не зараз» на старті лишає одну спробу - коли людина вперше пише чи телефонує`() {
        NotificationAsk.answered(context, NotificationAsk.Source.LAUNCH)

        assertTrue(NotificationAsk.due(context, NotificationAsk.Source.ENGAGEMENT))
        assertTrue("питання вилітає до екрана", NotificationAsk.onEngagement(context))
        NotificationAsk.answered(context, NotificationAsk.Source.ENGAGEMENT)

        assertFalse("двох спроб досить", NotificationAsk.due(context, NotificationAsk.Source.ENGAGEMENT))
        assertFalse(NotificationAsk.onEngagement(context))
    }

    /** FR-5 (ніч 09.10): «ні» на місце на мапі — і одразу картка сповіщень. */
    @Test
    fun `щойно відповіли на інше вікно - сповіщення хвилину не питають`() {
        val moment = local.phantom.companion.core.design.permission.PermissionMoment
        moment.answered(nowMs = 1_000_000L)
        try {
            assertFalse("картка сповіщень поверх щойно сказаного «ні»", NotificationAsk.due(context, NotificationAsk.Source.LAUNCH, nowMs = 1_010_000L))
            assertFalse(NotificationAsk.due(context, NotificationAsk.Source.ENGAGEMENT, nowMs = 1_010_000L))
            assertTrue("за хвилину питання знову законне", NotificationAsk.due(context, NotificationAsk.Source.LAUNCH, nowMs = 1_000_000L + moment.QUIET_MS))
        } finally {
            moment.answeredAtMs = 0L
        }
    }

    @Test
    fun `дозвіл уже є - ніхто не питає`() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertFalse(NotificationAsk.due(context, NotificationAsk.Source.LAUNCH))
        assertFalse(NotificationAsk.onEngagement(context))
    }

    @Test
    fun `до Android 13 сповіщення не питають зовсім`() {
        assertFalse(NotificationAsk.due(context, NotificationAsk.Source.LAUNCH, sdk = 32))
    }
}
