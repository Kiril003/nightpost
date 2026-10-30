package local.phantom.companion.data.appearance

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Вигляд кожної своєї особи — у власній теці `appearance/<відбиток id>/`:
 * `look.json` і фото тла й обкладинки. Звичайні файли, не зашифроване сховище:
 * акцент мусить бути відомий до першого кадру, тож холодний старт читає теку
 * останньої особи (`last`), ще не питаючи ключів.
 */
class AppearanceStore(private val root: File, private val me: () -> String) {

    sealed class Target(val prefix: String) {
        data object Everywhere : Target("wall")
        data class Chat(val id: String) : Target("chat")
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile private var slot: String = runCatching { File(root, LAST).readText().trim() }.getOrDefault("")

    private val _state = MutableStateFlow(read(slot))
    val state: StateFlow<Appearance> = _state.asStateFlow()

    /** Особа вже відома: перейти на її теку. Кличуть з IO. */
    @Synchronized
    fun bind() {
        val id = me().trim()
        if (id.isEmpty()) return
        val s = slotOf(id)
        if (s == slot) return
        slot = s
        root.mkdirs()
        File(root, LAST).writeText(s)
        _state.value = read(s)
    }

    @Synchronized
    fun update(change: (Appearance) -> Appearance): Appearance {
        bind()
        val next = change(_state.value).clean()
        val dir = dir().apply { mkdirs() }
        val tmp = File(dir, "$LOOK.tmp")
        tmp.writeText(json.encodeToString(next))
        tmp.renameTo(File(dir, LOOK))
        _state.value = next
        dir.listFiles()?.forEach { f ->
            if (Wallpaper.FILE_NAME.matches(f.name) && f.name !in next.files()) f.delete()
        }
        return next
    }

    /** [jpeg] уже стиснуте ([AppearancePhotos]); нове імʼя щоразу, щоб веб не взяв старе з кешу. */
    @Synchronized
    fun putPhoto(target: Target, jpeg: ByteArray): Appearance {
        bind()
        val name = "${target.prefix}-${System.currentTimeMillis().toString(36)}${(seq++ % 36).toString(36)}.jpg"
        File(dir().apply { mkdirs() }, name).writeBytes(jpeg)
        val photo = Wallpaper.photo(name)
        return update { a ->
            when (target) {
                Target.Everywhere -> a.copy(wallpaper = photo, wallpaperPicked = true)
                is Target.Chat -> a.copy(chats = a.chats + (target.id to photo))
            }
        }
    }

    /** null — розмова знову бере загальне тло. */
    fun setChat(chatId: String, wallpaper: Wallpaper?): Appearance = update { a ->
        a.copy(chats = if (wallpaper == null) a.chats - chatId else a.chats + (chatId to wallpaper))
    }

    fun file(name: String): File? =
        name.takeIf { Wallpaper.FILE_NAME.matches(it) }?.let { File(dir(), it) }?.takeIf { it.isFile }

    private var seq = 0

    private fun dir() = File(root, slot.ifEmpty { "_" })

    private fun read(s: String): Appearance =
        runCatching { json.decodeFromString<Appearance>(File(File(root, s.ifEmpty { "_" }), LOOK).readText()).clean() }
            .getOrDefault(Appearance())

    companion object {
        private const val LOOK = "look.json"
        private const val LAST = "last"

        fun slotOf(id: String): String =
            MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }
}
