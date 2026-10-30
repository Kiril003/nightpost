package local.phantom.companion.core.data.geocoder

import android.content.Context
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface ForgeGeocoderImport {
    data class Success(val info: ForgeGeocoderInfo) : ForgeGeocoderImport
    data class Failure(val message: String) : ForgeGeocoderImport
}

/**
 * Приймання пакета геокодера: спершу відкрити й прочитати, і лише потім
 * покласти на місце. Пакет, який не читається, не має ставати встановленим —
 * інакше пошук мовчки повертав би порожнечу замість «шукати нема де».
 */
object ForgeGeocoderImporter {

    fun importFromFile(context: Context, file: File): ForgeGeocoderImport {
        val probe = ForgeGeocoder.open(file.absolutePath)
            ?: return ForgeGeocoderImport.Failure("пакет місць не читається цією збіркою")
        val meta = probe.meta
        probe.close()

        val country = meta["country"].orEmpty()
        val dir = ForgeGeocoderStore.installDir(context).apply { mkdirs() }
        // Імʼя від КРАЇНИ, а не одне на всіх. Доти другий внесений пакет мовчки
        // зʼїдав перший: файл був один, і цей `delete` стирав чужу країну.
        val dest = File(dir, ForgeGeocoderStore.fileNameFor(country))
        if (dest.exists() && !dest.delete()) {
            return ForgeGeocoderImport.Failure("не зміг звільнити місце під пакет місць")
        }
        if (!file.renameTo(dest)) {
            runCatching { file.copyTo(dest, overwrite = true) }
                .onFailure { return ForgeGeocoderImport.Failure("не зміг покласти пакет місць: ${it.message}") }
            file.delete()
        }
        return ForgeGeocoderImport.Success(
            ForgeGeocoderInfo(
                path = dest.absolutePath,
                country = country,
                builtAt = meta["built_at"].orEmpty(),
                places = placesOf(meta["kinds"]),
                bytes = dest.length(),
            ),
        )
    }

    private fun placesOf(kinds: String?): Int {
        val text = kinds?.takeIf { it.isNotBlank() } ?: return 0
        val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return 0
        return json.values.sumOf { it.jsonPrimitive.content.toIntOrNull() ?: 0 }
    }
}
