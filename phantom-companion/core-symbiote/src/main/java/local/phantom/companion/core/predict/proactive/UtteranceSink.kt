package local.phantom.companion.core.predict.proactive

/** Where a decided utterance goes; the real impl routes to voice + card later. */
fun interface UtteranceSink {
    suspend fun emit(utterance: ProactiveUtterance, frame: ProactiveFrame)
}
