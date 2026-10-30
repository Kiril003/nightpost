package local.phantom.companion.core.predict.proactive

/**
 * Routes a decided [ProactiveUtterance] to the operator. Voice-forward by the
 * operator's setting: anything the model judged worth saying is spoken and also
 * left as a card. AMBIENT is the model's own lever for a quieter note — card and
 * ambient ribbon, no speech — so it can choose not to interrupt.
 */
class ProactiveUtteranceSink(
    private val speak: suspend (String) -> Unit,
    private val card: suspend (ProactiveUtterance, ProactiveFrame) -> Unit,
    private val whisper: suspend (String) -> Unit = {},
) : UtteranceSink {
    override suspend fun emit(utterance: ProactiveUtterance, frame: ProactiveFrame) {
        card(utterance, frame)
        when (utterance.urgency) {
            Urgency.AMBIENT -> whisper(utterance.say)
            Urgency.NOTABLE, Urgency.CRITICAL -> speak(utterance.say)
        }
    }
}
