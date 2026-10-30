package local.phantom.companion.core.voice.route

/**
 * One hand-off from PHANTOM's live voice to the system voice, or to silence.
 * The router used to do this without a trace; the operator heard the OEM
 * robot read an air-raid alert and had no way to learn why.
 */
data class VoiceFallback(
    val reason: Reason,
    val text: String,
    val atMs: Long,
    val detail: String? = null,
) {
    enum class Reason {
        LIVE_NOT_LOADED,
        LIVE_FAILED,
        /** Urgent cue given to the system voice because the live one has not proven a fast first audio. */
        LIVE_UNPROVEN,
        NO_VOICE,
    }
}
