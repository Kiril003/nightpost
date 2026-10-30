package local.phantom.companion.feature.stream.ui.map

/**
 * Стан іменованого місця, зібраний із його частин.
 *
 * Одне місце — це N окремих пакетів MapLibre; операторові треба бачити одне
 * число. Найважливіше тут — [complete]: воно вимагає, щоб УСІ заплановані
 * частини існували й були завершені. Пакет, у якого частина не створилася або
 * не докачалася, ніколи не має права називатися «завантажено».
 */
internal data class PlacePackStatus(
    val placeId: String,
    val nameUa: String,
    val minZoom: Int,
    val maxZoom: Int,
    /** Скільки частин було заплановано (з метаданих будь-якої з них). */
    val plannedChunks: Int,
    /** Скільки частин реально існує на пристрої. */
    val presentChunks: Int,
    val doneChunks: Int,
    val activeChunks: Int,
    val completedBytes: Long,
    val completedResources: Long,
    val requiredResources: Long,
    val styleUrl: String?,
) {
    val complete: Boolean
        get() = plannedChunks > 0 && presentChunks == plannedChunks && doneChunks == plannedChunks

    /** Частка за ресурсами, а не за частинами — вона рухається щосекунди. */
    val fraction: Float
        get() = if (requiredResources <= 0L) 0f
        else (completedResources.toFloat() / requiredResources.toFloat()).coerceIn(0f, 1f)

    val percent: Int get() = (fraction * 100f).toInt()

    val downloading: Boolean get() = activeChunks > 0 && !complete
}

internal object OfflinePlaces {

    fun group(regions: List<RegionInfo>): List<PlacePackStatus> = regions
        .filter { it.placeId != null }
        .groupBy { it.placeId!! }
        .map { (id, parts) ->
            val head = parts.maxByOrNull { it.chunkCount } ?: parts.first()
            PlacePackStatus(
                placeId = id,
                nameUa = head.placeNameUa ?: head.name,
                minZoom = head.minZoom,
                maxZoom = head.maxZoom,
                plannedChunks = parts.maxOf { it.chunkCount },
                presentChunks = parts.map { it.chunkIndex }.distinct().size,
                doneChunks = parts.count { it.isComplete },
                activeChunks = parts.count { it.active && !it.isComplete },
                completedBytes = parts.sumOf { it.completedResourceSizeBytes },
                completedResources = parts.sumOf { it.completedResourceCount },
                requiredResources = parts.sumOf { it.requiredResourceCount },
                styleUrl = head.styleUrl,
            )
        }
        .sortedWith(compareByDescending<PlacePackStatus> { it.downloading }.thenBy { it.nameUa })
}
