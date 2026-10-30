package local.phantom.companion.core.data.poi

import androidx.room.TypeConverter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PoiConverters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun mapToString(value: Map<String, String>): String =
        json.encodeToString(value)

    @TypeConverter
    fun stringToMap(value: String): Map<String, String> =
        runCatching { json.decodeFromString<Map<String, String>>(value) }
            .getOrDefault(emptyMap())
}
