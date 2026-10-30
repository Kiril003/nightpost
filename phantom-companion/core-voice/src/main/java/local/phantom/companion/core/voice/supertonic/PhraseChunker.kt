package local.phantom.companion.core.voice.supertonic

/**
 * Splits an utterance into pipeline segments for streaming synthesis.
 *
 * Supertonic is flow-matching: it emits a whole segment's PCM in one
 * shot, not sample-by-sample. "Streaming" therefore means *segment
 * pipelining* — synthesize segment k+1 while segment k plays. Two
 * policies fall out of that:
 *
 *  - **Ramped segments** ([rampLimits]): time-to-first-audio is *exactly*
 *    the synthesis time of segment one, so the opener is cut hard. Later
 *    segments grow, because by then playback of the opener is buying us
 *    the time to synthesize them — the pipeline is running a lead.
 *  - **Model ceiling** ([maxChars]): the reference implementation caps
 *    chunks at 300 characters; beyond that reading accuracy degrades.
 *
 * The ramp is the whole streaming trick, and getting it wrong silently
 * disables streaming: the first version used a 140-char opener, so a
 * 137-char reply fell through as ONE segment and the operator waited out
 * the *entire* utterance's synthesis (7.8 s of silence for 12.8 s of
 * speech) before hearing a sound. A segment that is never split cannot be
 * pipelined. Measured on an A56: ~94 ms of speech per character, so a
 * 60-char opener is ~5.6 s of audio bought for ~2 s of wait, and every
 * segment after it is free — hidden entirely behind playback.
 *
 * Boundaries prefer sentence enders (`.` `!` `?` `…`), then clause
 * punctuation (`,` `;` `:` `—`), then whitespace — a phrase is never
 * torn mid-word.
 */
class PhraseChunker(
    private val maxChars: Int = 300,
    /** Per-segment ceilings, shortest first; beyond the ramp, [maxChars]. */
    private val rampLimits: List<Int> = DEFAULT_RAMP,
) {

    fun split(text: String): List<String> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()

        val segments = ArrayList<String>()
        var rest = clean
        while (rest.isNotEmpty()) {
            val limit = rampLimits.getOrElse(segments.size) { maxChars }
            if (rest.length <= limit) {
                segments.add(rest)
                break
            }
            val cut = findCut(rest, limit)
            segments.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trim()
        }
        return segments.filter { it.isNotEmpty() }
    }

    /** Best boundary at or before [limit]; never returns 0. */
    private fun findCut(text: String, limit: Int): Int {
        val window = text.substring(0, limit)
        for (candidates in listOf(SENTENCE_ENDERS, CLAUSE_BREAKS)) {
            val at = window.indexOfLast { it in candidates }
            // Boundary char stays with the left segment. Ignore a
            // boundary in the first few chars — a 3-char "segment"
            // costs a synth round-trip and buys nothing.
            if (at >= MIN_SEGMENT_CHARS) return at + 1
        }
        val space = window.lastIndexOf(' ')
        if (space >= MIN_SEGMENT_CHARS) return space + 1
        return limit // single unbroken token longer than the limit — hard cut
    }

    companion object {
        const val MIN_SEGMENT_CHARS = 8

        /**
         * Opener short enough to be heard almost at once; second segment
         * moderate; everything after rides the [maxChars] ceiling because
         * playback is already covering the synthesis.
         *
         * 60 was still ~2 s of staring at silence (the field measurement in
         * the class doc). In a live conversation that is the whole gap, so
         * the opener is cut to a clause — long enough to be a phrase, short
         * enough that the pipeline starts running its lead immediately.
         */
        val DEFAULT_RAMP = listOf(28, 90, 200)

        private val SENTENCE_ENDERS = charArrayOf('.', '!', '?', '…')
        private val CLAUSE_BREAKS = charArrayOf(',', ';', ':', '—')
    }
}
