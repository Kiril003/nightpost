package local.phantom.companion.core.solana

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameNoteTest {

    @Test
    fun isValidBoundsAndControlChars() {
        assertFalse(FrameNote.isValid(""))
        assertTrue(FrameNote.isValid("a"))
        assertTrue(FrameNote.isValid("а".repeat(FrameNote.MAX)))
        assertFalse(FrameNote.isValid("a".repeat(FrameNote.MAX + 1)))
        assertFalse(FrameNote.isValid("hello\nworld"))
        assertFalse(FrameNote.isValid("hello\rworld"))
        assertFalse(FrameNote.isValid("hello\u0000world"))
        assertFalse(FrameNote.isValid("hello\u001fworld"))
        assertFalse(FrameNote.isValid("hello\u007fworld"))
    }

    @Test
    fun encodeDecodeRoundTripsWithSemicolonsAndPercents() {
        val original = "За каву; 50% знижка; 100%!"
        val encoded = FrameNote.encode(original)
        assertEquals("За каву%3B 50%25 знижка%3B 100%25!", encoded)
        assertFalse(encoded.contains(';'))
        assertEquals(original, FrameNote.decode(encoded))
    }

    @Test
    fun decodeRejectsInvalidEscapesAndOverlongFields() {
        val maxNote = "x".repeat(FrameNote.MAX)
        assertEquals(maxNote, FrameNote.decode(maxNote))
        assertNull(FrameNote.decode(""))
        assertNull(FrameNote.decode("%zz"))
        assertNull(FrameNote.decode("%2"))
        assertNull(FrameNote.decode("%20"))
        assertNull(FrameNote.decode("abc%"))
        assertNull(FrameNote.decode("a;b"))
        assertNull(FrameNote.decode("a\nb"))
        assertNull(FrameNote.decode("x".repeat(FrameNote.MAX + 1)))
        assertNull(FrameNote.decode("%25".repeat(FrameNote.MAX + 1)))
    }
}
