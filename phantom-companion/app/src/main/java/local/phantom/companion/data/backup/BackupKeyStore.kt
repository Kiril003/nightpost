package local.phantom.companion.data.backup

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import local.phantom.companion.data.EncryptedSlot
import local.phantom.companion.data.PeerLogSlot

class BackupKeyStore internal constructor(private val slot: PeerLogSlot) {

    constructor(context: Context) : this(
        EncryptedSlot(context.applicationContext ?: context, FILE_NAME, durable = true),
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun load(): BackupFormat.SealedKey? {
        val raw = slot.read(KEY_SEALED) ?: return null
        return runCatching { json.decodeFromString<BackupFormat.SealedKey>(raw) }.getOrNull()
    }

    fun save(key: BackupFormat.SealedKey) {
        slot.write(KEY_SEALED, json.encodeToString(key))
    }

    fun clear() {
        slot.write(KEY_SEALED, null)
    }

    fun backupUri(): String? = slot.read(KEY_URI)

    fun setBackupUri(uri: String?) {
        slot.write(KEY_URI, uri)
    }

    fun lastBackupEpochMs(): Long = slot.read(KEY_LAST_EPOCH_MS)?.toLongOrNull() ?: 0L

    fun setLastBackupEpochMs(epochMs: Long) {
        slot.write(KEY_LAST_EPOCH_MS, epochMs.toString())
    }

    fun restoredAtMs(): Long = slot.read(KEY_RESTORED_MS)?.toLongOrNull() ?: 0L

    fun setRestoredAtMs(epochMs: Long) {
        slot.write(KEY_RESTORED_MS, epochMs.toString())
    }

    companion object {
        const val FILE_NAME = "nightpost_backup_key_v1"
        private const val KEY_SEALED = "sealed_key"
        private const val KEY_URI = "backup_uri"
        private const val KEY_LAST_EPOCH_MS = "last_backup_epoch_ms"
        private const val KEY_RESTORED_MS = "restored_at_ms"

        fun inMemory(): BackupKeyStore {
            val map = LinkedHashMap<String, String>()
            return BackupKeyStore(
                object : PeerLogSlot {
                    override fun read(key: String): String? = map[key]
                    override fun write(key: String, value: String?) {
                        if (value == null) map.remove(key) else map[key] = value
                    }
                },
            )
        }
    }
}
