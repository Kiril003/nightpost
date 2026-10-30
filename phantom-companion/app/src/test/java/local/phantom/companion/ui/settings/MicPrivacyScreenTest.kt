package local.phantom.companion.ui.settings

import java.io.File
import local.phantom.companion.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicPrivacyScreenTest {

    private fun state(
        muted: Boolean = false,
        sttEnabled: Boolean = true,
        liveEnabled: Boolean = true,
        liveLevel: Float = 0f,
        duplexOpen: Boolean = false,
    ) = resolveMicPrivacyState(muted, sttEnabled, liveEnabled, liveLevel, duplexOpen)

    @Test
    fun `muted sets top state and both owners to muted`() {
        val s = state(muted = true, liveLevel = 0.8f, duplexOpen = true)
        assertEquals(R.string.mic_state_muted, s.topStateRes)
        assertEquals(null, s.activePercent)
        assertEquals(MicOwnerStatus.Muted, s.pttStatus)
        assertEquals(MicOwnerStatus.Muted, s.liveStatus)
    }

    @Test
    fun `both owners enabled and the duplex ear open - live dialogue holds the mic`() {
        val s = state(liveLevel = 0.6f, duplexOpen = true)
        assertEquals(R.string.mic_state_active, s.topStateRes)
        assertEquals(60, s.activePercent)
        assertEquals(MicOwnerStatus.Idle, s.pttStatus)
        assertEquals(MicOwnerStatus.Active, s.liveStatus)
    }

    @Test
    fun `both owners enabled and the duplex ear closed - push-to-talk holds the mic`() {
        val s = state(liveLevel = 0.42f, duplexOpen = false)
        assertEquals(42, s.activePercent)
        assertEquals(MicOwnerStatus.Active, s.pttStatus)
        assertEquals(MicOwnerStatus.Idle, s.liveStatus)
    }

    @Test
    fun `both owners disabled and not muted is idle, not off`() {
        val s = state(sttEnabled = false, liveEnabled = false)
        assertEquals(R.string.mic_state_idle, s.topStateRes)
        assertEquals(MicOwnerStatus.Disabled, s.pttStatus)
        assertEquals(MicOwnerStatus.Disabled, s.liveStatus)
    }

    @Test
    fun `a call can hold the mic with both owners disabled - top row says live, owners stay disabled`() {
        val s = state(sttEnabled = false, liveEnabled = false, liveLevel = 0.5f)
        assertEquals(R.string.mic_state_active, s.topStateRes)
        assertEquals(50, s.activePercent)
        assertEquals(MicOwnerStatus.Disabled, s.pttStatus)
        assertEquals(MicOwnerStatus.Disabled, s.liveStatus)
    }

    @Test
    fun `turning live dialogue on waits for the microphone permission`() {
        val set = mutableListOf<Boolean>()
        var asked = 0
        onLiveDialogSwitch(on = true, requestMic = { asked++ }, setEnabled = { set += it })
        assertEquals(1, asked)
        assertTrue("enabled before the permission answered: $set", set.isEmpty())

        onLiveDialogSwitch(on = true, requestMic = { granted -> granted() }, setEnabled = { set += it })
        assertEquals(listOf(true), set)
    }

    @Test
    fun `turning live dialogue off never asks`() {
        val set = mutableListOf<Boolean>()
        onLiveDialogSwitch(on = false, requestMic = { error("asked to turn off") }, setEnabled = { set += it })
        assertEquals(listOf(false), set)
    }

    @Test
    fun `the screen gates the switch, offers unmute when muted, and reads the duplex ear`() {
        val src = File("src/main/java/local/phantom/companion/ui/settings/MicPrivacyScreen.kt")
            .takeIf { it.isFile } ?: File("app/src/main/java/local/phantom/companion/ui/settings/MicPrivacyScreen.kt")
        val screen = src.readText()
        assertTrue("no microphone permission gate", screen.contains("rememberRecordAudioPermissionGate()"))
        assertTrue("the live switch skips the gate", screen.contains("onLiveDialogSwitch(on, voiceGate.requestThen"))
        assertFalse("live switch writes the pref directly", screen.contains("setLiveDialogEnabled(it)"))
        val muted = screen.substringAfter("if (muted) {", "").substringBefore("} else {")
        assertTrue("muted screen has no unmute row", muted.contains("R.string.look_live_unmute"))
        assertFalse("the unmute row skips the gate", muted.contains("setMicMuted(false)"))
        assertFalse("the mute switch unmutes past the gate", screen.contains("voicePrefs.setMicMuted(it)"))
        assertTrue("owner status ignores who holds the mic", screen.contains("VoicePhaseHub.phase"))
    }

    @Test
    fun `unmuting with live dialogue on waits for the microphone permission`() {
        val set = mutableListOf<Boolean>()
        var asked = 0
        onMicMutedSwitch(muted = false, liveEnabled = true, requestMic = { asked++ }, setMuted = { set += it })
        assertEquals(1, asked)
        assertTrue("unmuted before the permission answered: $set", set.isEmpty())

        onMicMutedSwitch(muted = false, liveEnabled = true, requestMic = { granted -> granted() }, setMuted = { set += it })
        assertEquals(listOf(false), set)
    }

    @Test
    fun `muting, or unmuting with live dialogue off, never asks`() {
        val set = mutableListOf<Boolean>()
        onMicMutedSwitch(muted = true, liveEnabled = true, requestMic = { error("asked to mute") }, setMuted = { set += it })
        onMicMutedSwitch(muted = false, liveEnabled = false, requestMic = { error("asked with live dialogue off") }, setMuted = { set += it })
        assertEquals(listOf(true, false), set)
    }
}
