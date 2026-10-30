package local.phantom.companion.core.nav

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ON-TIME VOICE — the speaker's three rules, each a repair of the
 * 2026-07-18 drive («поки все генерується і озвучується, я вже в іншому
 * повороті»): stale cues die in the queue, the newest tier of a maneuver
 * evicts the waiting older one, and a NOW cue preempts a lesser utterance.
 */
class NavCueSpeakerTest {

    @Test
    fun `stale cue dies in the queue and a fresh one speaks`() = runTest {
        val spoken = mutableListOf<Pair<String, Boolean>>()
        val speaker = NavCueSpeaker(
            scope = backgroundScope,
            speak = { t, u -> spoken += t to u },
            stopSpeaking = {},
            clockMs = { currentTime },
        )
        speaker.start()
        speaker.submit(
            NavCue("старий поворот", NavCue.Urgency.SOON, bornMs = currentTime - 30_000, ttlMs = 10_000),
        )
        runCurrent()
        assertTrue("an expired cue was spoken", spoken.isEmpty())

        speaker.submit(NavCue("свіжий поворот", NavCue.Urgency.SOON, bornMs = currentTime))
        runCurrent()
        assertEquals(listOf("свіжий поворот" to false), spoken)
    }

    @Test
    fun `newer cue with the same key evicts the waiting one`() = runTest {
        val spoken = mutableListOf<String>()
        val firstDone = Channel<Unit>()
        val speaker = NavCueSpeaker(
            scope = backgroundScope,
            speak = { t, _ ->
                spoken += t
                if (t == "дайджест") firstDone.receive() // a long utterance
            },
            stopSpeaking = {},
            clockMs = { currentTime },
        )
        speaker.start()
        speaker.submit(NavCue("дайджест", NavCue.Urgency.SOON, bornMs = currentTime))
        runCurrent() // the digest is now mid-utterance
        // Two tiers of the SAME maneuver stack up behind it…
        speaker.submit(NavCue("через 250 метрів", NavCue.Urgency.SOON, key = "step-3", bornMs = currentTime))
        speaker.submit(NavCue("через 60 метрів", NavCue.Urgency.SOON, key = "step-3", bornMs = currentTime))
        firstDone.send(Unit)
        runCurrent()
        // …and only the LATEST truth is spoken, once.
        assertEquals(listOf("дайджест", "через 60 метрів"), spoken)
    }

    @Test
    fun `NOW preempts a lesser utterance and takes the urgent path`() = runTest {
        val spoken = mutableListOf<Pair<String, Boolean>>()
        var interrupted = false
        val playback = Channel<Unit>()
        val speaker = NavCueSpeaker(
            scope = backgroundScope,
            speak = { t, u ->
                spoken += t to u
                // The digest "plays" until stopSpeaking cuts it, the way a
                // real TtsEngine.stop() makes an in-flight speak() return.
                if (t == "довгий дайджест") playback.receive()
            },
            stopSpeaking = {
                interrupted = true
                playback.trySend(Unit)
            },
            clockMs = { currentTime },
        )
        speaker.start()
        speaker.submit(NavCue("довгий дайджест", NavCue.Urgency.INFO, bornMs = currentTime))
        runCurrent()
        speaker.submit(NavCue("Поверніть праворуч", NavCue.Urgency.NOW, bornMs = currentTime))
        runCurrent()
        assertTrue("the digest was not preempted", interrupted)
        assertEquals("Поверніть праворуч" to true, spoken.last())
    }

    @Test
    fun `a NOW cue outranks earlier queued lesser cues`() = runTest {
        val spoken = mutableListOf<String>()
        val firstDone = Channel<Unit>()
        val speaker = NavCueSpeaker(
            scope = backgroundScope,
            speak = { t, _ ->
                spoken += t
                if (t == "перший") firstDone.receive()
            },
            stopSpeaking = {},
            clockMs = { currentTime },
        )
        speaker.start()
        speaker.submit(NavCue("перший", NavCue.Urgency.SOON, bornMs = currentTime))
        runCurrent()
        speaker.submit(NavCue("попередження", NavCue.Urgency.INFO, bornMs = currentTime))
        speaker.submit(NavCue("тривога", NavCue.Urgency.INFO, bornMs = currentTime))
        speaker.submit(NavCue("Поверніть ліворуч", NavCue.Urgency.NOW, bornMs = currentTime))
        firstDone.send(Unit)
        runCurrent()
        // The turn beats both INFO cues that arrived before it; the INFO
        // cues keep their own order after it.
        assertEquals(listOf("перший", "Поверніть ліворуч", "попередження", "тривога"), spoken)
    }
}
