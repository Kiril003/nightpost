package local.phantom.companion.data.applock

import android.content.Context
import local.phantom.companion.data.EncryptedSlot

/**
 * Один [AppLock] на процес: екран, служба сповіщень і приймачі шторки читають
 * той самий стан. Хеш коду лежить у зашифрованому файлі (Keystore апарата).
 */
object AppLockStore {

    private const val FILE_NAME = "phantom_app_lock"
    private const val KEY = "state"

    @Volatile private var instance: AppLock? = null

    fun of(context: Context): AppLock = instance ?: synchronized(this) {
        instance ?: run {
            val slot = EncryptedSlot(context.applicationContext, FILE_NAME, durable = true)
            AppLock(object : AppLock.Slot {
                override fun read(): String? = slot.read(KEY)
                override fun write(value: String) = slot.write(KEY, value)
            }).also { instance = it }
        }
    }
}
