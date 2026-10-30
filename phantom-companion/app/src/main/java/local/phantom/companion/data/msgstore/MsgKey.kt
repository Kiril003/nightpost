package local.phantom.companion.data.msgstore

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/** Ключ бази розмов: 32 випадкові байти, загорнуті ключем Android Keystore. */
internal object MsgKey {

    private const val PREFS = "phantom_msg_keys"
    private const val KEY = "msg_db_key_v1"
    private const val TAG = "MsgKey"

    /**
     * Новий ключ поверх наявної бази зробив би її назавжди нечитною, тож при
     * [dbExists] і втраченому ключі база спершу йде осторонь. Keystore, що не
     * відповідає, — null: краще старі двері на цей запуск, ніж новий ключ.
     */
    fun loadOrCreate(app: Context, dbExists: Boolean): ByteArray? {
        val prefs = runCatching {
            EncryptedSharedPreferences.create(
                app,
                PREFS,
                MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.onFailure { Log.e(TAG, "сховище ключів недоступне", it) }.getOrNull() ?: return null
        prefs.getString(KEY, null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        if (dbExists) MsgBase.setAside(app.getDatabasePath(MsgBase.FILE_NAME))
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        // commit, не apply: база створюється одразу після цього рядка.
        if (!prefs.edit().putString(KEY, Base64.encodeToString(fresh, Base64.NO_WRAP)).commit()) return null
        return fresh
    }
}
