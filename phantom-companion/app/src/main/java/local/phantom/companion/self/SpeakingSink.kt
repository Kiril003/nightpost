package local.phantom.companion.self

import local.phantom.companion.core.voice.stream.IncrementalSpeaker

/**
 * Голос однієї відповіді в живій розмові: токени — в мовця, прохід після
 * інструментів — з чистого аркуша, підсумок — промовним рядком.
 */
internal class SpeakingSink(
    private val speaker: IncrementalSpeaker,
    private val actingLine: String,
    private val codeInChat: String,
    private val closing: () -> Unit = {},
) : VoiceSink {

    @Volatile
    var messageId: String? = null
        private set

    override fun begin(messageId: String) {
        this.messageId = messageId
    }

    override fun token(delta: String) = speaker.append(delta)

    override fun acting() = speaker.say(actingLine)

    override fun nextRound() = speaker.restart()

    override fun done(text: String?, messageId: String) {
        this.messageId = messageId
        closing()
        speaker.close(text?.let { PhantomVoice.speakable(it, codeInChat) })
    }

    override fun failed(reason: String) {
        closing()
        speaker.close(reason)
    }
}
