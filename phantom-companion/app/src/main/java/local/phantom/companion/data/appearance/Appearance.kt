package local.phantom.companion.data.appearance

import kotlinx.serialization.Serializable
import local.phantom.companion.core.design.theme.ACCENT_DEFAULT
import local.phantom.companion.core.design.tokens.PhantomAccents

/** Тло розмови: нічого, одне з вбудованих (`webmsgr/wallpapers/<id>.webp`) або своє фото. */
@Serializable
data class Wallpaper(val kind: String = NONE, val id: String? = null, val file: String? = null) {

    fun valid(): Boolean = when (kind) {
        NONE -> id == null && file == null
        BUILTIN -> id in BUILTINS && file == null
        PHOTO -> file != null && FILE_NAME.matches(file) && id == null
        else -> false
    }

    companion object {
        const val NONE = "none"
        const val BUILTIN = "builtin"
        const val PHOTO = "photo"
        val None = Wallpaper()
        val BUILTINS = listOf("contour", "sand", "sage", "slate", "clay", "dusk", "stone")
        /** Типове тло — рівний папір чи графіт теми; рельєф і решта — лише власним вибором. */
        val Default = None
        fun builtin(id: String) = Wallpaper(BUILTIN, id = id)
        fun photo(file: String) = Wallpaper(PHOTO, file = file)
        val FILE_NAME = Regex("^[a-z]+-[a-z0-9]+\\.jpg$")
    }
}

/** Окремо для Дня й Ночі: на світлому полотні фото глушать менше, ніж на темному. */
@Serializable
data class Shade(val day: Int, val night: Int) {
    fun of(dark: Boolean) = if (dark) night else day
    fun with(dark: Boolean, value: Int) = if (dark) copy(night = value) else copy(day = value)
}

/** Профіль › Вигляд — для однієї своєї особи. Тема й розмір тексту живуть окремо (PhantomViewModel). */
@Serializable
data class Appearance(
    val accent: String = ACCENT_DEFAULT,
    val wallpaper: Wallpaper = Wallpaper.Default,
    val wallpaperPicked: Boolean = false,
    val chats: Map<String, Wallpaper> = emptyMap(),
    val dim: Shade = Shade(day = 55, night = 80),
    val blur: Shade = Shade(day = 0, night = 0),
    val bubbles: String = COMFORTABLE,
    val list: String = COMFORTABLE,
) {
    fun wallpaperFor(chatId: String?): Wallpaper = chatId?.let { chats[it] } ?: wallpaper

    /** Імена фото, на які ще є посилання; решту файлів у теці можна прибрати. */
    fun files(): Set<String> =
        (listOf(wallpaper) + chats.values).mapNotNull { it.file }.toSet()

    fun clean(): Appearance = Appearance(
        accent = accent.takeIf { id -> PhantomAccents.any { it.id == id } } ?: ACCENT_DEFAULT,
        wallpaper = wallpaper.takeIf { wallpaperPicked && it.valid() } ?: Wallpaper.Default,
        wallpaperPicked = wallpaperPicked,
        chats = chats.filter { (id, w) -> id.isNotBlank() && id.length <= 200 && w.valid() },
        dim = Shade(dim.day.coerceIn(0, DIM_MAX), dim.night.coerceIn(0, DIM_MAX)),
        blur = Shade(blur.day.coerceIn(0, BLUR_MAX), blur.night.coerceIn(0, BLUR_MAX)),
        bubbles = bubbles.takeIf { it in DENSITIES } ?: COMFORTABLE,
        list = list.takeIf { it in DENSITIES } ?: COMFORTABLE,
    )

    companion object {
        const val COMFORTABLE = "comfortable"
        const val COMPACT = "compact"
        val DENSITIES = setOf(COMFORTABLE, COMPACT)
        const val DIM_MAX = 80
        const val BLUR_MAX = 20
        /** Вбудований візерунок — лише фон: завіса зʼїдає ще половину лишку (як appearance.ts). */
        const val PATTERN_QUIET = 0.5f

        fun scrim(dim: Int, kind: String): Float {
            val d = dim / 100f
            return if (kind == Wallpaper.BUILTIN) d + (1f - d) * PATTERN_QUIET else d
        }
    }
}
