package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.sensor.nav.LocationTrust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The word must be slower than the verdict — but never slower on the way UP.
 * The scar this guards is CameraDirector.kt:296-311: LocationTrust flaps on
 * single-frequency hardware, and anything that reacts to every flap becomes
 * noise the operator learns to ignore.
 */
class TrustWordHysteresisTest {

    @Test
    fun `a worsening sky is never delayed`() {
        assertTrue(verdictWorsens(LocationTrust.TRUSTED, LocationTrust.SPOOFED))
        assertTrue(verdictWorsens(LocationTrust.DEGRADED, LocationTrust.JAMMED))
        assertTrue(verdictWorsens(null, LocationTrust.DEGRADED))
        assertTrue(verdictWorsens(LocationTrust.JAMMED, LocationTrust.SPOOFED))
    }

    @Test
    fun `a calming sky has to hold before the word softens`() {
        assertFalse(verdictWorsens(LocationTrust.SPOOFED, LocationTrust.TRUSTED))
        assertFalse(verdictWorsens(LocationTrust.JAMMED, LocationTrust.DEGRADED))
        assertFalse(verdictWorsens(LocationTrust.DEGRADED, null))
    }

    @Test
    fun `no verdict and a clear sky are equally silent, so neither interrupts`() {
        assertFalse(verdictWorsens(null, LocationTrust.TRUSTED))
        assertFalse(verdictWorsens(LocationTrust.TRUSTED, null))
    }

    @Test
    fun `severity is a strict ladder — every state is worse than the one before`() {
        val ladder = listOf(
            LocationTrust.TRUSTED,
            LocationTrust.DEGRADED,
            LocationTrust.JAMMED,
            LocationTrust.SPOOFED,
        )
        // Свідомо ПОЗА драбиною: «шукаю небо» — це не ступінь поганого неба, а
        // його відсутність. Поставити цей стан на драбину означало б, що
        // застосунок може «підвищити тривогу» до нього — а тривоги там немає.
        val offLadder = listOf(LocationTrust.SEARCHING)
        // Перевірка повноти лишається тією самою по суті: новий стан не може
        // зʼявитись і бути тихо забутим — його треба або поставити на драбину,
        // або назвати позадрабинним ТУТ, свідомо.
        assertTrue(
            "кожен LocationTrust мусить бути або на драбині, або названий поза нею",
            (ladder + offLadder).containsAll(LocationTrust.entries),
        )
        for (state in offLadder) {
            assertEquals(
                "$state стоїть поза драбиною, тож важить як чисте небо — нуль",
                trustSeverity(LocationTrust.TRUSTED),
                trustSeverity(state),
            )
        }
        for (i in 1 until ladder.size) {
            assertTrue(
                "${ladder[i]} must outrank ${ladder[i - 1]}",
                trustSeverity(ladder[i]) > trustSeverity(ladder[i - 1]),
            )
        }
    }

    @Test
    fun `погіршення чекає коротко, поліпшення — довго`() {
        // Миттєвий напис і був спамом: 34 з 57 епізодів недовіри в записі
        // 04.08 коротші за 4 с і разом важать 85 с із 3318.
        assertEquals(
            ALARM_HOLD_MS,
            steadyHoldMs(LocationTrust.TRUSTED, LocationTrust.JAMMED),
        )
        assertEquals(
            ALARM_HOLD_MS,
            steadyHoldMs(LocationTrust.JAMMED, LocationTrust.SPOOFED),
        )
        assertEquals(
            CALM_HOLD_MS,
            steadyHoldMs(LocationTrust.SPOOFED, LocationTrust.TRUSTED),
        )
    }

    @Test
    fun `тривога чекає менше за заспокоєння, але не нуль`() {
        assertTrue("миттєвий напис — це той самий спам", ALARM_HOLD_MS > 0L)
        assertTrue("тривога не може бути повільнішою за відбій", ALARM_HOLD_MS < CALM_HOLD_MS)
    }
}
