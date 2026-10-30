package local.phantom.companion.core.solana

import android.annotation.SuppressLint
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL

internal object VaultKeys {

    const val UNLOCK_WINDOW_S = 15

    @SuppressLint("NewApi")
    fun spec(alias: String, sdk: Int = Build.VERSION.SDK_INT): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .apply {
                if (sdk >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(UNLOCK_WINDOW_S, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(UNLOCK_WINDOW_S)
                }
            }
            .build()

    /** Лише те, що Keystore визнає розблокуванням. */
    fun authenticators(sdk: Int = Build.VERSION.SDK_INT): Int =
        if (sdk >= Build.VERSION_CODES.R) {
            BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        } else {
            // androidx не пускає STRONG|DEVICE_CREDENTIAL на API 29; слабкий відбиток тут пройде вікно, але Keystore ключ не відчинить.
            BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        }
}
