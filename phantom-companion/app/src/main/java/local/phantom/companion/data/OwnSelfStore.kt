package local.phantom.companion.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Мої копії — пристрої, яким я передав особу (docs/ONE_SELF.md, інваріант 2).
 *
 * Саме цей список, а не позначка «мій пристрій» у реєстрі, скаже синхрону, чиїм
 * кадрам вірити: позначку людина ставить руками будь-кому. Ключів тут немає —
 * лише імʼя вузла й коли передано.
 */
class OwnSelfStore internal constructor(private val slot: PeerLogSlot) {

    constructor(context: Context) : this(EncryptedOwnSelfSlot(context))

    @Serializable
    data class Copy(val nodeId: String, val handedAtMs: Long)

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Copy.serializer())

    @Synchronized
    fun copies(): List<Copy> {
        val raw = slot.read(KEY)?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    fun isMine(nodeId: String): Boolean = nodeId.isNotBlank() && copies().any { it.nodeId == nodeId }

    @Synchronized
    fun record(nodeId: String, atMs: Long) {
        write(copies().filterNot { it.nodeId == nodeId } + Copy(nodeId, atMs))
    }

    @Synchronized
    fun forget(nodeId: String) {
        write(copies().filterNot { it.nodeId == nodeId })
    }

    private fun write(next: List<Copy>) {
        slot.write(KEY, if (next.isEmpty()) null else json.encodeToString(serializer, next))
    }

    internal companion object {
        const val FILE_NAME = "phantom_own_self_v1"
        const val KEY = "copies"
    }
}

private class EncryptedOwnSelfSlot(context: Context) : PeerLogSlot {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            OwnSelfStore.FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun read(key: String): String? = prefs.getString(key, null)

    override fun write(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}
