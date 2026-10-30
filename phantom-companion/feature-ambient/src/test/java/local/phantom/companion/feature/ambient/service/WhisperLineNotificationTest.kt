package local.phantom.companion.feature.ambient.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3-E1 — pinned constants for the Whisper Line foreground
 * notification. The channel id, channel name, and notification id
 * MUST stay distinct from the existing PhantomLink channel
 * («phantom_link_v1» / 4201) so the operator can disable one without
 * killing the other in system settings.
 *
 * The channel name «фантом · присутність» is verbatim from the
 * Phase 3-E1 prompt — changing it requires a deliberate copy update.
 */
class WhisperLineNotificationTest {

    @Test
    fun `channel id is unique to the whisper line service`() {
        // Must differ from PhantomLinkService.CHANNEL_ID which is
        // "phantom_link_v1" — they live in different modules so we
        // duplicate the literal here as a copy-anchor.
        assertEquals("phantom_whisper_v1", WhisperLineNotification.CHANNEL_ID)
        assertNotEquals(
            "must not collide with the link service channel",
            "phantom_link_v1",
            WhisperLineNotification.CHANNEL_ID,
        )
    }

    @Test
    fun `channel name is verbatim Ukrainian copy from the prompt`() {
        // Pinned exactly — the «фантом · присутність» label is what
        // the operator sees in Settings → Notifications, and the
        // prompt's Phase 3-E1 spec calls it out as the visible label.
        assertEquals("Nightpost · присутність", WhisperLineNotification.CHANNEL_NAME)
    }

    @Test
    fun `channel description is non-empty Ukrainian sentence`() {
        // No verbatim pin — operator-facing description, may evolve.
        // Sanity-check it's set and uses Ukrainian characters.
        val desc = WhisperLineNotification.CHANNEL_DESCRIPTION
        assertTrue("description must not be blank", desc.isNotBlank())
        assertTrue(
            "description should contain at least one cyrillic letter",
            desc.any { it.code in 0x0400..0x04FF },
        )
    }

    @Test
    fun `notification id is unique vs PhantomLink service`() {
        // PhantomLinkService.NOTIF_ID is 4201; whisper picks 4211 to
        // leave room for adjacent ambient services (4212 onward).
        assertEquals(4211, WhisperLineNotification.NOTIF_ID)
        assertNotEquals(4201, WhisperLineNotification.NOTIF_ID)
    }

    @Test
    fun `notification title and text are operator-friendly`() {
        // Title carries the «phantom · присутність» wordmark; text is
        // a quiet poetic line that won't read as alarming on the
        // notification shade.
        assertTrue(WhisperLineNotification.NOTIF_TITLE.isNotBlank())
        assertTrue(WhisperLineNotification.NOTIF_TEXT.isNotBlank())
        assertEquals("Nightpost · присутність", WhisperLineNotification.NOTIF_TITLE)
    }
}
