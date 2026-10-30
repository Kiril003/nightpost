package local.phantom.companion.service

import androidx.annotation.StringRes
import local.phantom.companion.R
import local.phantom.companion.core.voice.route.VoiceFallback

/** Причина підміни голосу — лише для екрана «Голос»; у шторку це не йде. */
object VoiceFallbackNotice {

    /** MainActivity читає це і веде в Налаштування → Голос. */
    const val EXTRA_OPEN_VOICE = "phantom.open_voice"

    @StringRes
    fun reasonLabel(reason: VoiceFallback.Reason): Int = when (reason) {
        VoiceFallback.Reason.LIVE_NOT_LOADED -> R.string.voice_fallback_reason_not_loaded
        VoiceFallback.Reason.LIVE_FAILED -> R.string.voice_fallback_reason_failed
        VoiceFallback.Reason.LIVE_UNPROVEN -> R.string.voice_fallback_reason_unproven
        VoiceFallback.Reason.NO_VOICE -> R.string.voice_fallback_reason_none
    }
}
