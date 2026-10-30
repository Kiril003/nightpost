package local.phantom.companion.core.voice.supertonic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnicodeTokenizerTest {

    /**
     * Mirrors the real indexer's shape: flat array, -1 = unmapped.
     * Mapped ids here are the codepoint itself so assertions read
     * naturally; only the entries the tests need are non-negative.
     */
    private fun fakeIndexer(vararg mapped: Char): IntArray {
        val arr = IntArray(0x10000) { -1 }
        for (c in mapped) arr[c.code] = c.code
        // language-tag plumbing + space, always in the real vocab
        for (c in "<>/uk ") arr[c.code] = c.code
        return arr
    }

    @Test
    fun `wraps text in language tags`() {
        val t = UnicodeTokenizer(fakeIndexer('а'))
        val ids = t.encode("а", "uk")
        val expected = "<uk>а</uk>".map { it.code.toLong() }.toLongArray()
        assertArrayEquals(expected, ids)
    }

    @Test
    fun `NFKD decomposes ukrainian yi into base plus diaeresis`() {
        // «ї» U+0457 is UNMAPPED in the real vocab; its NFKD parts are
        // mapped. The tokenizer must decompose or Ukrainian is broken.
        val arr = fakeIndexer('і')
        arr[0x0308] = 0x0308 // combining diaeresis
        val t = UnicodeTokenizer(arr)
        val ids = t.encode("ї", "uk").toList()
        assertTrue("base і missing", 'і'.code.toLong() in ids)
        assertTrue("combining diaeresis missing", 0x0308L in ids)
        assertTrue("raw ї leaked through", 0x0457L !in ids)
    }

    @Test
    fun `NFKD decomposes ukrainian short i into base plus breve`() {
        val arr = fakeIndexer('и')
        arr[0x0306] = 0x0306 // combining breve
        val t = UnicodeTokenizer(arr)
        val ids = t.encode("й", "uk").toList()
        assertTrue('и'.code.toLong() in ids)
        assertTrue(0x0306L in ids)
    }

    @Test
    fun `unmapped codepoints are dropped not crashed`() {
        val t = UnicodeTokenizer(fakeIndexer('а'))
        val ids = t.encode("а₴а", "uk").toList() // ₴ unmapped
        assertEquals(2, ids.count { it == 'а'.code.toLong() })
        assertTrue(0x20B4L !in ids)
    }

    @Test
    fun `emoji and supplementary plane stripped before NFKD`() {
        val t = UnicodeTokenizer(fakeIndexer('о', 'к'))
        val ids = t.encode("ок 🚀", "uk")
        val expected = "<uk>ок</uk>".map { it.code.toLong() }.toLongArray()
        assertArrayEquals(expected, ids)
    }

    @Test
    fun `typographic symbols normalised and whitespace collapsed`() {
        val t = UnicodeTokenizer(fakeIndexer('а', '-', '"'))
        val ids = t.encode("«а»  —   а", "uk")
        val expected = "<uk>\"а\" - а</uk>".map { it.code.toLong() }.toLongArray()
        assertArrayEquals(expected, ids)
    }

    @Test
    fun `blank input yields empty ids not bare tags`() {
        val t = UnicodeTokenizer(fakeIndexer())
        assertEquals(0, t.encode("   ", "uk").size)
    }
}
