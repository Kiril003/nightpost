package local.phantom.companion.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import local.phantom.companion.Distribution

private const val PREFS_NAME = "phantom_ai_keys"
private const val PREFS_FALLBACK_NAME = "phantom_ai_keys_fallback"
private const val KEY_GEMINI = "gemini_api_key"
private const val KEY_HF_TOKEN = "huggingface_token"

/**
 * Keystore-wrapped storage for the Gemini API key.
 *
 * Uses the same EncryptedSharedPreferences pattern as [PairedDeviceStore]:
 * AES256_GCM master key from Android Keystore, AES256_SIV for pref-key
 * encryption, AES256_GCM for pref-value encryption.
 *
 * If the Android Keystore is unavailable (corrupted after reinstall, MIUI
 * Keystore bug, or BouncyCastle JCE clash) the field build falls back to plain
 * SharedPreferences so the application still boots; store builds refuse to
 * store unsealed keys.
 *
 * Exposes [geminiKey] as a [StateFlow] so composables can react to key
 * changes (e.g. after the operator saves a new key the StreamViewModel
 * re-checks route availability via [StreamEvent.RefreshAvailability]).
 */
private fun sealedPrefs(context: Context): SharedPreferences {
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    return EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}

class AiKeyStore(
    context: Context,
    allowPlain: Boolean = Distribution.plainKeys,
    openSealed: (Context) -> SharedPreferences = ::sealedPrefs,
) {

    private val prefs: SharedPreferences? = runCatching {
        openSealed(context)
    }.getOrElse { t ->
        Log.e("AiKeyStore", "EncryptedSharedPreferences init failed", t)
        if (allowPlain) context.getSharedPreferences(PREFS_FALLBACK_NAME, Context.MODE_PRIVATE) else null
    }

    val canStore: Boolean get() = prefs != null

    private val _geminiKey = MutableStateFlow(prefs?.getString(KEY_GEMINI, null))
    private val _hfToken = MutableStateFlow(prefs?.getString(KEY_HF_TOKEN, null))

    /** Current Gemini API key, or `null` when none is configured. */
    val geminiKey: StateFlow<String?> = _geminiKey.asStateFlow()

    /**
     * Hugging Face access token for downloading gated repos (Gemma, Whisper
     * variants, etc.). When non-null, [ModelManager] adds an
     * `Authorization: Bearer <token>` header to requests against
     * `huggingface.co`.
     */
    val hfToken: StateFlow<String?> = _hfToken.asStateFlow()

    /**
     * Persists a new Gemini API key.
     *
     * Passing `null` or a blank string removes the key entirely so
     * [availableRoutes] will return an empty set and the Stream surface
     * shows the unconfigured hint.
     */
    fun saveGeminiKey(value: String?): Boolean {
        val store = prefs ?: return false
        if (value.isNullOrBlank()) {
            store.edit().remove(KEY_GEMINI).apply()
            _geminiKey.value = null
        } else {
            val trimmed = value.trim()
            store.edit().putString(KEY_GEMINI, trimmed).apply()
            _geminiKey.value = trimmed
        }
        return true
    }

    fun saveHfToken(value: String?): Boolean {
        val store = prefs ?: return false
        if (value.isNullOrBlank()) {
            store.edit().remove(KEY_HF_TOKEN).apply()
            _hfToken.value = null
        } else {
            val trimmed = value.trim()
            store.edit().putString(KEY_HF_TOKEN, trimmed).apply()
            _hfToken.value = trimmed
        }
        return true
    }
}
