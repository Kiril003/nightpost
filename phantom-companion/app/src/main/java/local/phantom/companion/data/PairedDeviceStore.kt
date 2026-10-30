package local.phantom.companion.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json

/**
 * Keystore-wrapped persistence for the device JWT, base URL, owning user
 * info, and the long-term Ed25519 key used by /pair/refresh.
 *
 * EncryptedSharedPreferences pulls a master key from Android Keystore on first
 * open; all values are AES-256-GCM-encrypted at rest. Backup is disabled
 * globally in the manifest, so even adb-restore can't lift the wrapped blobs.
 */
class PairedDeviceStore(context: Context) {

    private val prefs: SharedPreferences = run {
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

    fun load(): PairedDevice? {
        val json = prefs.getString(KEY_PAIRED, null) ?: return null
        return runCatching { Json.decodeFromString<PairedDevice>(json) }.getOrNull()
    }

    /** Emits whenever the pairing changes. Lets callers wait instead of poll. */
    val changes: Flow<Unit> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_PAIRED) trySend(Unit)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun save(device: PairedDevice) {
        prefs.edit().putString(KEY_PAIRED, Json.encodeToString(device)).apply()
    }

    fun update(transform: (PairedDevice) -> PairedDevice) {
        load()?.let { save(transform(it)) }
    }

    fun clear() {
        prefs.edit().remove(KEY_PAIRED).apply()
    }

    companion object {
        private const val FILE_NAME = "phantom_pair_v1"
        private const val KEY_PAIRED = "paired_device"
    }
}

@Serializable
data class PairedDevice(
    val lanUrl: String,
    val relayUrl: String? = null,
    /** Адреса вузла на точці зустрічі. Без неї зовнішній шлях не проклався б. */
    val relayNodeId: String? = null,
    val devNoPin: Boolean,
    val deviceId: String,
    val userId: String,
    val username: String,
    val deviceJwt: String,
    val expiresAt: String,
    /** Base64(NO_WRAP) of the long-term Ed25519 private key (32 bytes). */
    val deviceEd25519Priv: String,
    val deviceEd25519Pub: String,
    val serverCertSha256: String = "",
    /**
     * Сітьова особа вузла — те, з чого рахується адреса скриньки у сховку.
     *
     * Порожньо в записі, зробленому до 03.09.2026: спарування тоді не несло
     * жодного X25519, тож ключа каналу з таким записом не скласти взагалі.
     * Це не «щось не працює», це «нічим рахувати» — і екран мусить сказати
     * саме так, а не мовчати.
     */
    val nodePeerId: String = "",
    val nodePubKey: String = "",
    val nodeDhPubKey: String = "",
    val computerName: String = "",
) {
    /** The URL currently used for connection. Initialized to lanUrl. */
    val baseUrl: String get() = lanUrl

    /** Чи є з чого скласти СПІЛЬНУ адресу сховка. false — лише перепарувати. */
    val dropReady: Boolean
        get() = nodePeerId.isNotBlank() && nodePubKey.isNotBlank() && nodeDhPubKey.isNotBlank()

    fun privRaw(): ByteArray = Base64.decode(deviceEd25519Priv, Base64.NO_WRAP or Base64.NO_PADDING)
}
