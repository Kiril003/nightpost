package local.phantom.companion.core.tool.permission

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

private const val PREFS_NAME = "phantom_tool_permissions"
private const val PREFS_FALLBACK_NAME = "phantom_tool_permissions_fallback"
private const val TAG = "EncryptedToolPermStore"

/**
 * Android Keystore-backed [ToolPermissionStore].
 *
 * Mirrors the [local.phantom.companion.data.AiKeyStore] pattern:
 * AES256_GCM master key from Android Keystore, AES256_SIV for pref-key
 * encryption, AES256_GCM for pref-value encryption.
 *
 * If the Keystore is unavailable (corrupted after reinstall, MIUI Keystore
 * bug, or BouncyCastle JCE clash) the init falls back to plain
 * SharedPreferences so the app boots normally. The fallback is insecure
 * (decisions stored in plaintext) but prevents a crash loop; decisions are
 * not sensitive secrets so this is an acceptable degradation.
 */
class EncryptedToolPermissionStore(context: Context) : ToolPermissionStore {

    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse { t ->
        Log.e(TAG, "EncryptedSharedPreferences init failed — using plaintext fallback", t)
        context.getSharedPreferences(PREFS_FALLBACK_NAME, Context.MODE_PRIVATE)
    }

    override fun decision(toolId: String): Decision {
        val raw = runCatching { prefs.getString(toolId, null) }.getOrNull() ?: return Decision.ASK
        return runCatching { Decision.valueOf(raw) }.getOrElse {
            // ASK видає дозвіл негайно, тож згорнути пошкоджене значення в ASK
            // означало перетворити збережене NEVER на «можна». Псування читаємо як заборону.
            Log.e(TAG, "unreadable decision for $toolId — failing closed to NEVER")
            Decision.NEVER
        }
    }

    override fun setDecision(toolId: String, decision: Decision) {
        prefs.edit().putString(toolId, decision.name).apply()
    }

    override fun clearAll() {
        prefs.edit().clear().apply()
    }
}
