package local.phantom.companion.service

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.nav.NavCue
import local.phantom.companion.feature.stream.ui.map.AirAlertNotice
import local.phantom.companion.feature.stream.ui.map.AirAlertNoticeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тривога промовляється без живої Activity: [AirAlertVoice] не знає ні
 * Context, ні екрана — лише прогрів і чергу. Те, що він робить, доводиться
 * тут, на JVM; те, що саме служба ним володіє, — в [AirAlertVoiceOwnerTest].
 */
class AirAlertVoiceTest {

    private fun notice(kind: AirAlertNoticeKind) = AirAlertNotice(
        oblast = "Київська область",
        kind = kind,
        phrase = "Повітряна тривога — Київська область.",
        atEpochMs = 1_000L,
    )

    @Test
    fun `тривога прогрівається ДО черги і йде терміновою`() = runTest {
        val log = mutableListOf<String>()
        val cues = mutableListOf<NavCue>()
        val voice = AirAlertVoice(
            prewarm = { log += "prewarm:$it" },
            submit = { log += "submit"; cues += it },
            normalize = { it.uppercase() },
            clockMs = { 42L },
        )

        voice.announce(notice(AirAlertNoticeKind.RAISED))

        assertEquals(listOf("prewarm:" + notice(AirAlertNoticeKind.RAISED).phrase.uppercase(), "submit"), log)
        val cue = cues.single()
        assertEquals(NavCue.Urgency.NOW, cue.urgency)
        assertEquals(AirAlertVoice.KEY, cue.key)
        assertEquals(42L, cue.bornMs)
        assertEquals(notice(AirAlertNoticeKind.RAISED).phrase, cue.text)
    }

    @Test
    fun `відбій не перебиває розмову`() = runTest {
        val cues = mutableListOf<NavCue>()
        val voice = AirAlertVoice(prewarm = {}, submit = { cues += it }, normalize = { it })

        voice.announce(notice(AirAlertNoticeKind.CLEARED))

        assertEquals(NavCue.Urgency.SOON, cues.single().urgency)
    }

    @Test
    fun `завислий прогрів не тримає тривогу довше за бюджет`() = runTest {
        val cues = mutableListOf<NavCue>()
        val voice = AirAlertVoice(
            prewarm = { delay(600_000L) },
            submit = { cues += it },
            normalize = { it },
            prewarmBudgetMs = 5_000L,
        )

        voice.announce(notice(AirAlertNoticeKind.RAISED))

        assertTrue("черга не отримала тривоги після вичерпаного бюджету", cues.size == 1)
        assertTrue("прогрів чекали довше за бюджет", currentTime <= 5_000L)
    }

    @Test
    fun `падіння прогріву не вбиває тривогу`() = runTest {
        val cues = mutableListOf<NavCue>()
        val voice = AirAlertVoice(
            prewarm = { throw IllegalStateException("model missing") },
            submit = { cues += it },
            normalize = { it },
        )

        voice.announce(notice(AirAlertNoticeKind.RAISED))

        assertEquals(1, cues.size)
    }
}
