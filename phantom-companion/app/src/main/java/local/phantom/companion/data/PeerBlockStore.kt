package local.phantom.companion.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import local.phantom.companion.core.net.peer.PeerBlock

/**
 * Кого цей телефон не слухає — на диску цього телефона.
 *
 * Не на сервері, і це не економія: список заблокованих каже, КОГО людина
 * боїться, і це рівно той факт, по який ідуть до сервера. Тут його немає й не
 * буде — блокування не потребує нічиєї згоди, тож і питати нема кого.
 *
 * Зашифрований у спокої з тієї ж причини, з якої зашифрований реєстр: телефон
 * можуть забрати, і тоді «кого я заблокував» — це список людей, яких варто
 * розпитати. Окремий файл від реєстру навмисно: реєстр чистять і звіряють з
 * дротом, а цей засув — місцеве рішення, і воно не має гинути разом з чиїмось
 * рядком.
 *
 * Памʼяті не довіряє нічого: рішення читається з диска при народженні і
 * пишеться наскрізь при кожній зміні, бо саме перезапуску й чекав би той, чиї
 * листи знову почали б заходити.
 */
class PeerBlockStore internal constructor(private val slot: PeerLogSlot) {

    constructor(context: Context) : this(EncryptedBlockSlot(context))

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Чи весь диск прочитано. Хибне — писати порожнечу поверх заборонено.
     *
     * Оголошене ПЕРЕД [_blocked], і це не косметика: Kotlin ставить поля в
     * порядку оголошення, тож `= true` нижче затирало б те, що [read] уже
     * дізнався про зіпсований рядок, — і сторож був би мертвий, лишившись на
     * місці. Так це й було, поки перевірка не почервоніла.
     */
    @Volatile
    private var whole = true

    private val _blocked = MutableStateFlow(read())

    /** Для екрана налаштувань: перелік, який сам оновлюється. */
    val blocked: StateFlow<Set<String>> = _blocked.asStateFlow()

    /** Питання приймача. Кличеться на КОЖЕН вхідний кадр, тож читає памʼять. */
    fun blocks(id: String): Boolean = PeerBlock.blocks(_blocked.value, id)

    fun block(id: String) = write(PeerBlock.block(_blocked.value, id))

    fun unblock(id: String) = write(PeerBlock.unblock(_blocked.value, id))

    fun clear() {
        slot.write(KEY, null)
        _blocked.value = emptySet()
    }

    /** Встановлює список заблокованих з відновленої резервної копії. */
    fun adopt(blockedSet: Set<String>) {
        write(blockedSet)
    }

    /**
     * Зіпсований рядок — це НЕ порожній список.
     *
     * Порожній означав би «нікого не заблоковано», і наступний же кадр від
     * заблокованого зайшов би в журнал, а людина про це не дізналась би ніяк.
     * Тому те, що не розібралось, лягає осторонь дослівно й лишається під
     * [KEY] незрушеним, а до першого свідомого запису ми відмовляємось писати
     * поверх нього: краще засув, що не знімається зі списку, ніж засув, що
     * тихо зник.
     */
    private fun read(): Set<String> {
        val raw = slot.read(KEY)?.takeIf { it.isNotBlank() } ?: return emptySet()
        val parsed = runCatching { json.decodeFromString<Set<String>>(raw) }.getOrNull()
        if (parsed != null) return parsed
        slot.write(DAMAGED_KEY, raw)
        whole = false
        return emptySet()
    }

    private fun write(next: Set<String>) {
        if (next.isEmpty() && !whole) return
        // Той самий список — той самий диск. [PeerBlock] повертає незмінений
        // набір на повторне блокування, і писати його вдруге означало б
        // зашифрований запис на кожен зайвий дотик.
        if (next == _blocked.value && whole) return
        slot.write(KEY, json.encodeToString(next))
        whole = true
        _blocked.value = next
    }

    internal companion object {
        const val FILE_NAME = "phantom_blocked_v1"
        const val KEY = "blocked"

        /** Те, що не розібралось, дослівно — щоб засув можна було відновити. */
        const val DAMAGED_KEY = "blocked_damaged"
    }
}

private class EncryptedBlockSlot(context: Context) : PeerLogSlot {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PeerBlockStore.FILE_NAME,
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
