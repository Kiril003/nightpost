package local.phantom.companion.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Обличчя групи живе на цьому телефоні, а не на дроті.
 *
 * У заголовок групи його покласти не можна: [local.phantom.companion.core.net.peer.PeerGroup.decode]
 * відкидає голову, в якій не рівно три поля, тож четверте зробило б кожен
 * груповий лист НЕВИДИМИМ для вже зібраних версій — не гіршим, а відсутнім.
 * Тому картинка лишається місцевою: її бачить той, хто поставив. Щоб її бачили
 * всі, потрібна зміна дроту, і це окреме рішення власника.
 */
class PeerGroupAvatarStore(context: Context) {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _avatars = MutableStateFlow(read())
    val avatars: StateFlow<Map<String, String>> = _avatars.asStateFlow()

    fun avatarOf(groupId: String): String? = _avatars.value[groupId]

    /**
     * Повертає false, коли картинка завелика — і саме false, а не обріз:
     * обрізаний data-URI не порожній, він зламаний, і людина побачила б не
     * «не влізло», а порожній квадрат без жодної причини.
     */
    fun set(groupId: String, avatar: String?): Boolean {
        val clean = avatar?.trim().orEmpty()
        if (clean.isEmpty()) {
            _avatars.value = _avatars.value - groupId
            prefs.edit().remove(groupId).apply()
            return true
        }
        if (clean.length > MAX_CHARS) return false
        _avatars.value = _avatars.value + (groupId to clean)
        prefs.edit().putString(groupId, clean).apply()
        return true
    }

    private fun read(): Map<String, String> =
        prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    private companion object {
        const val FILE_NAME = "phantom_group_avatars_v1"

        /** ~192 КБ base64: зменшена мініатюра влазить, повне фото з камери — ні. */
        const val MAX_CHARS = 256 * 1024
    }
}
