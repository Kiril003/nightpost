package local.phantom.companion.data

import local.phantom.companion.core.ai.ModelState
import local.phantom.companion.core.ai.ModelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Живий голос у вільному ярусі качає себе сам, коли про це попросили — але
 * не раніше, не за гроші власника без його слова, не в переповнений диск і
 * не мовчки. Кожна гілка — слово.
 *
 * 05.09 privacy audit — `requested` — рядок другий, а не перший: до нього
 * `Fetch` вирішувався на самому Wi-Fi/диску, тож ~400 МБ їхали на
 * huggingface.co з холодного старту, без дотику й без ключа. Дивись
 * phantom-phone-downloads-without-a-key.
 */
class LiveVoicePolicyTest {

    private val gb = 1024L * 1024L * 1024L
    private fun absent() = ModelState(ModelType.SUPERTONIC)

    @Test
    fun `not requested is the resting state on a fresh install, whatever the network says`() {
        assertEquals(
            LiveVoiceState.NotRequested,
            decideLiveVoice(absent(), requested = false, online = true, metered = false, allowMobile = false, freeBytes = 4 * gb),
        )
        assertEquals(
            "even a perfect Wi-Fi/disk combination must not fetch without the word",
            LiveVoiceState.NotRequested,
            decideLiveVoice(absent(), requested = false, online = true, metered = false, allowMobile = true, freeBytes = 4 * gb),
        )
    }

    @Test
    fun `Wi-Fi with room on disk fetches at once, once requested`() {
        assertEquals(
            LiveVoiceState.Fetch,
            decideLiveVoice(absent(), requested = true, online = true, metered = false, allowMobile = false, freeBytes = 4 * gb),
        )
    }

    @Test
    fun `mobile data waits for the operator's second word`() {
        val waiting = decideLiveVoice(absent(), requested = true, online = true, metered = true, allowMobile = false, freeBytes = 4 * gb)
        assertTrue(waiting is LiveVoiceState.WaitingWifi)
        assertEquals(LIVE_VOICE_SIZE_MB, (waiting as LiveVoiceState.WaitingWifi).sizeMb)
    }

    @Test
    fun `the operator's word spends mobile data`() {
        assertEquals(
            LiveVoiceState.Fetch,
            decideLiveVoice(absent(), requested = true, online = true, metered = true, allowMobile = true, freeBytes = 4 * gb),
        )
    }

    @Test
    fun `a full disk is said, not attempted`() {
        val state = decideLiveVoice(absent(), requested = true, online = true, metered = false, allowMobile = true, freeBytes = 100L * 1024 * 1024)
        assertTrue(state is LiveVoiceState.NoSpace)
    }

    @Test
    fun `offline is offline before anything else about the network, once requested`() {
        assertEquals(
            LiveVoiceState.Offline,
            decideLiveVoice(absent(), requested = true, online = false, metered = true, allowMobile = true, freeBytes = 4 * gb),
        )
    }

    @Test
    fun `a present model is ready whatever the network or the request flag says`() {
        val present = ModelState(ModelType.SUPERTONIC, isPresent = true)
        assertEquals(
            LiveVoiceState.Ready,
            decideLiveVoice(present, requested = false, online = false, metered = true, allowMobile = false, freeBytes = 0L),
        )
    }

    @Test
    fun `a running transfer reports its progress instead of starting twice`() {
        val running = ModelState(ModelType.SUPERTONIC, isDownloading = true, progress = 0.37f)
        assertEquals(
            LiveVoiceState.Fetching(0.37f),
            decideLiveVoice(running, requested = true, online = true, metered = false, allowMobile = false, freeBytes = 4 * gb),
        )
    }

    @Test
    fun `a failed transfer is terminal until asked again`() {
        val failed = ModelState(ModelType.SUPERTONIC, error = "Немає мережі")
        val state = decideLiveVoice(failed, requested = true, online = true, metered = false, allowMobile = true, freeBytes = 4 * gb)
        assertEquals(LiveVoiceState.Stalled("Немає мережі"), state)
    }
}
