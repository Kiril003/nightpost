package local.phantom.companion.core.data.geocoder

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ForgeGeocoderInfo(
    val path: String,
    val country: String,
    val builtAt: String,
    val places: Int,
    val bytes: Long,
)

/**
 * Які пакети кузні внесено. Слухач на самому [prefs], а не поле: пакет вносить
 * `:feature-precision`, а читає пошук мапи — це різні інстанси в різних
 * композиціях.
 *
 * СКІЛЬКИ ПАКЕТІВ. До 12.09 рівно один, і другий мовчки зʼїдав перший: файл
 * звався `geocoder.sqlite`, а імпортер його видаляв перед тим, як покласти
 * новий. Людина в Кошицях, якій потрібні SK, UA і PL одночасно, отримувала
 * останній внесений — без жодної скарги, бо «пакет встановлено» і був. Тепер
 * ключ — КРАЇНА: `geocoder-sk.sqlite` поруч із `geocoder-ua.sqlite`.
 */
class ForgeGeocoderStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _installed = MutableStateFlow(load())
    val installed: StateFlow<List<ForgeGeocoderInfo>> = _installed.asStateFlow()

    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _installed.value = load() }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun save(info: ForgeGeocoderInfo) {
        val key = keyOf(info.country)
        prefs.edit()
            .putStringSet(KEY_COUNTRIES, countries() + key)
            .putString("$KEY_PATH.$key", info.path)
            .putString("$KEY_COUNTRY.$key", info.country)
            .putString("$KEY_BUILT_AT.$key", info.builtAt)
            .putInt("$KEY_PLACES.$key", info.places)
            .putLong("$KEY_BYTES.$key", info.bytes)
            .apply()
        _installed.value = load()
    }

    fun installedPaths(): List<String> = load().map { it.path }

    /** Сумісність: перший шлях. Нові споживачі беруть [installedPaths]. */
    fun installedPath(): String? = installedPaths().firstOrNull()

    private fun countries(): Set<String> = prefs.getStringSet(KEY_COUNTRIES, emptySet()).orEmpty()

    /**
     * Старий одинарний запис приймається як пакет невідомої країни — інакше
     * оновлення застосунку виглядало б як зникнення вже внесеного пакета.
     */
    private fun legacy(): ForgeGeocoderInfo? {
        val path = prefs.getString(KEY_PATH, null) ?: return null
        if (!File(path).isFile) return null
        return ForgeGeocoderInfo(
            path = path,
            country = prefs.getString(KEY_COUNTRY, null).orEmpty(),
            builtAt = prefs.getString(KEY_BUILT_AT, null).orEmpty(),
            places = prefs.getInt(KEY_PLACES, 0),
            bytes = prefs.getLong(KEY_BYTES, 0L),
        )
    }

    private fun load(): List<ForgeGeocoderInfo> {
        val rows = countries().mapNotNull { key ->
            val path = prefs.getString("$KEY_PATH.$key", null) ?: return@mapNotNull null
            if (!File(path).isFile) return@mapNotNull null
            ForgeGeocoderInfo(
                path = path,
                country = prefs.getString("$KEY_COUNTRY.$key", null).orEmpty(),
                builtAt = prefs.getString("$KEY_BUILT_AT.$key", null).orEmpty(),
                places = prefs.getInt("$KEY_PLACES.$key", 0),
                bytes = prefs.getLong("$KEY_BYTES.$key", 0L),
            )
        }
        val old = legacy()?.takeIf { legacyRow -> rows.none { it.path == legacyRow.path } }
        return (rows + listOfNotNull(old)).sortedBy { it.country }
    }

    companion object {
        private const val PREFS_NAME = "phantom_forge_geocoder"
        private const val KEY_PATH = "path"
        private const val KEY_COUNTRY = "country"
        private const val KEY_BUILT_AT = "built_at"
        private const val KEY_PLACES = "places"
        private const val KEY_BYTES = "bytes"
        private const val KEY_COUNTRIES = "countries"

        /** Ключ файла й запису. Порожня країна не має ставати порожнім іменем. */
        fun keyOf(country: String): String =
            country.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "unknown" }

        fun fileNameFor(country: String): String = "geocoder-${keyOf(country)}.sqlite"

        /** Імʼя одинарного пакета до 12.09 — лишається читним, новим не пишеться. */
        const val FILE_NAME = "geocoder.sqlite"

        fun installDir(context: Context): File = File(context.filesDir, "forge-geocoder")
    }
}
