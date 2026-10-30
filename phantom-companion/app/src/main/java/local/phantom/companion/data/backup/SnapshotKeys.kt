package local.phantom.companion.data.backup

import android.content.Context
import android.util.Base64
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.DeleteBytesRequest
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import local.phantom.companion.core.data.vault.VaultCipher

internal interface SnapshotKeys {
    fun local(): ByteArray?
    fun cloud(): ByteArray?
    fun keepLocal(key: ByteArray)
    fun keepCloud(key: ByteArray): Boolean
    fun forget()
}

internal class PickedKey(val key: ByteArray, val inCloud: Boolean)

// Without Google Play services the copy still gets sealed with the Keystore-only key; it just cannot follow to a new phone.
internal fun SnapshotKeys.pick(): PickedKey {
    val cloud = runCatching { cloud() }.getOrNull()
    local()?.let { mine ->
        return PickedKey(mine, (cloud != null && cloud.contentEquals(mine)) || runCatching { keepCloud(mine) }.getOrDefault(false))
    }
    if (cloud != null) {
        keepLocal(cloud)
        return PickedKey(cloud, true)
    }
    val fresh = AutoSnapshot.newKey()
    keepLocal(fresh)
    return PickedKey(fresh, runCatching { keepCloud(fresh) }.getOrDefault(false))
}

// The key never goes into the Auto Backup file: it lives in noBackupFilesDir under a
// Keystore key, and travels only through Block Store (end-to-end encrypted by Google).
internal class DeviceSnapshotKeys(private val context: Context) : SnapshotKeys {

    private val file get() = File(context.noBackupFilesDir, "autobackup_key")
    private val client by lazy { Blockstore.getClient(context) }

    override fun local(): ByteArray? = runCatching {
        Base64.decode(VaultCipher.deviceDefault().decrypt(file.readText()), Base64.NO_WRAP)
    }.getOrNull()?.takeIf { it.size == AutoSnapshot.KEY_BYTES }

    override fun cloud(): ByteArray? = runCatching {
        val request = RetrieveBytesRequest.Builder().setKeys(listOf(KEY)).build()
        await(client.retrieveBytes(request)).blockstoreDataMap[KEY]?.bytes
    }.getOrNull()?.takeIf { it.size == AutoSnapshot.KEY_BYTES }

    override fun keepLocal(key: ByteArray) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(VaultCipher.deviceDefault().encrypt(Base64.encodeToString(key, Base64.NO_WRAP)))
        }
    }

    override fun keepCloud(key: ByteArray): Boolean = runCatching {
        val data = StoreBytesData.Builder().setKey(KEY).setBytes(key).setShouldBackupToCloud(true).build()
        await(client.storeBytes(data))
        true
    }.getOrDefault(false)

    override fun forget() {
        runCatching { await(client.deleteBytes(DeleteBytesRequest.Builder().setKeys(listOf(KEY)).build())) }
        file.delete()
    }

    private fun <T> await(task: Task<T>): T = Tasks.await(task, 15, TimeUnit.SECONDS)

    private companion object {
        const val KEY = "np.autobackup.key.v1"
    }
}

internal enum class CloudBackupState { OFF, HELD, WEAK_NO_LOCK, PHONE_ONLY, OK }

// Without a screen lock Google holds both the copy and its Block Store key: still better than losing everything, so say it.
internal fun cloudBackupState(on: Boolean, held: Boolean, deviceSecure: Boolean, cloudKey: Boolean): CloudBackupState = when {
    held -> CloudBackupState.HELD
    !on -> CloudBackupState.OFF
    !deviceSecure -> CloudBackupState.WEAK_NO_LOCK
    !cloudKey -> CloudBackupState.PHONE_ONLY
    else -> CloudBackupState.OK
}

internal class CloudBackupSetting(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("phantom_autobackup", Context.MODE_PRIVATE)
    private val _on = MutableStateFlow(prefs.getBoolean(ON, true))
    val on: StateFlow<Boolean> = _on
    private val _cloudKey = MutableStateFlow(prefs.getBoolean(CLOUD_KEY, true))
    val cloudKey: StateFlow<Boolean> = _cloudKey

    fun cloudKey(ok: Boolean) {
        if (_cloudKey.value == ok) return
        prefs.edit().putBoolean(CLOUD_KEY, ok).apply()
        _cloudKey.value = ok
    }

    fun set(on: Boolean) {
        prefs.edit().putBoolean(ON, on).apply()
        _on.value = on
    }

    private companion object {
        const val ON = "cloud_on"
        const val CLOUD_KEY = "cloud_key"
    }
}
