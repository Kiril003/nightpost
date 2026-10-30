package local.phantom.companion.core.voice.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * HIGHWAY / voice — Android Auto only reliably routes
 * ASSISTANCE_NAVIGATION_GUIDANCE audio to the car speakers for an app that
 * currently HOLDS focus for it; without a request, a cue can be built and
 * played correctly and still never reach anyone — silence that shows up
 * exactly when nothing else happens to be playing, which is the reported
 * failure. GAIN_TRANSIENT_MAY_DUCK asks whatever else is playing (music) to
 * lower, not stop, for the duration of the cue.
 */
class NavAudioFocus(context: Context) {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .build()

    /** Holds focus for [body]; abandons afterwards even if [body] throws. */
    suspend fun <T> withFocus(body: suspend () -> T): T {
        runCatching { audioManager.requestAudioFocus(request) }
        try {
            return body()
        } finally {
            runCatching { audioManager.abandonAudioFocusRequest(request) }
        }
    }
}
