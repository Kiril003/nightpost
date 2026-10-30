package local.phantom.companion.license

import android.content.Context
import android.provider.Settings
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

class LicenseVault(context: Context) {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    val deviceFingerprint: String = run {
        @Suppress("HardwareIds")
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        ).orEmpty()
        MessageDigest.getInstance("SHA-256")
            .digest("phantom:${context.packageName}:$androidId".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    var certificate: String?
        get() = prefs.getString(KEY_CERT, null)
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_CERT) else putString(KEY_CERT, value)
        }.apply()

    var policy: String?
        get() = prefs.getString(KEY_POLICY, null)
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_POLICY) else putString(KEY_POLICY, value)
        }.apply()

    var licenseKey: String?
        get() = prefs.getString(KEY_KEY, null)
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_KEY) else putString(KEY_KEY, value)
        }.apply()

    var lastCheckInMs: Long
        get() = prefs.getLong(KEY_CHECK_IN, 0L)
        set(value) = prefs.edit().putLong(KEY_CHECK_IN, value).apply()

    /** Найвищий номер збірки, від оновлення до якої вже відмовились. */
    var dismissedUpdateBuild: Int
        get() = prefs.getInt(KEY_UPDATE_SEEN, 0)
        set(value) = prefs.edit().putInt(KEY_UPDATE_SEEN, value).apply()

    fun firstRunMs(nowMs: Long): Long {
        val stored = prefs.getLong(KEY_FIRST_RUN, 0L)
        if (stored > 0L) return stored
        prefs.edit().putLong(KEY_FIRST_RUN, nowMs).apply()
        return nowMs
    }

    fun clearCertificate() {
        prefs.edit().remove(KEY_CERT).remove(KEY_KEY).remove(KEY_CHECK_IN).apply()
    }

    private companion object {
        const val FILE_NAME = "phantom_license_v1"
        const val KEY_CERT = "certificate"
        const val KEY_POLICY = "policy"
        const val KEY_KEY = "license_key"
        const val KEY_CHECK_IN = "last_check_in"
        const val KEY_FIRST_RUN = "first_run"
        const val KEY_UPDATE_SEEN = "update_dismissed_build"
    }
}
