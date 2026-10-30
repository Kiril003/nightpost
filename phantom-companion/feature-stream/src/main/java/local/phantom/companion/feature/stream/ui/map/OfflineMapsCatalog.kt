package local.phantom.companion.feature.stream.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.StateFlow
import local.phantom.companion.feature.stream.R

/**
 * Офлайн-мапи одним списком — публічні двері до пакетів для екранів поза цим
 * модулем (Профіль › Офлайн-мапи в `:app`).
 *
 * До цього файла список жив двічі: в офлайн-панелі на мапі (групи, пауза,
 * «прибрати групу», місце цілим) і в Профілі (плаский перелік шматків, кошик
 * одним дотиком без підтвердження). Профіль не бачив ані місць, ані помилок:
 * усе, що вміє місце — видалитись цілим, стати на паузу, — у менеджері
 * `internal`, а сам менеджер і [OfflinePlaces] сюди не належать. Тепер список
 * один — у Профілі, — а мапа лишає собі лише те, що працює з поточним видом
 * (зберегти вид, коридор маршруту, вибір місця, шари з ПК).
 *
 * Тут немає власного стану: це тонка обгортка над тим самим
 * [OfflineRegionsManager], яким уже користується застосунок.
 */
class OfflineMapsCatalog(private val manager: OfflineRegionsManager) {

    val regions: StateFlow<List<RegionInfo>> get() = manager.regions

    /** Помилки, які видно оку, — за id шматка. */
    val errors: StateFlow<Map<Long, PackError>> get() = manager.errors

    suspend fun refresh() = manager.refresh()

    /**
     * Старі пакети отримують тип і назву з власного визначення. Без цього
     * давній коридор лежав би серед «ділянок мапи», а не «уздовж маршрутів».
     * Ідемпотентно — безпечно на кожному відкритті списку.
     */
    suspend fun migrateLegacyMetadata(): Int = manager.migrateLegacyMetadata()

    /** Місце — одна одиниця: усі його частини йдуть разом, а не шматок за шматком. */
    suspend fun deletePlace(placeId: String): Int = manager.deletePlace(placeId)

    suspend fun pausePlace(placeId: String): Int = manager.pausePlace(placeId)

    suspend fun resumePlace(placeId: String): Int = manager.resumePlace(placeId)

    /** «Спробувати ще» для місця — лише ті частини, що справді впали. */
    suspend fun retryPlace(placeId: String): Int {
        val failed = manager.regions.value
            .filter { it.placeId == placeId && manager.errors.value.containsKey(it.id) }
        failed.forEach { manager.retry(it.id) }
        return failed.size
    }

    suspend fun setPaused(id: Long, paused: Boolean): Boolean = manager.setPaused(id, paused)

    suspend fun retry(id: Long): Boolean = manager.retry(id)

    suspend fun delete(id: Long): Boolean = manager.delete(id)

    /** «Прибрати всі» в групі — по одному, бо MapLibre видаляє пакет за пакетом. */
    suspend fun deleteAll(ids: List<Long>): Int = ids.count { manager.delete(it) }

    companion object {
        /**
         * Три групи власності. Місце збирається з частин ([OfflinePlaces.group]);
         * коридори й ділянки — свіжіші вгорі.
         */
        fun group(
            regions: List<RegionInfo>,
            errors: Map<Long, PackError> = emptyMap(),
        ): OfflineMapsGroups {
            val failedPlaces = regions
                .filter { it.placeId != null && errors.containsKey(it.id) }
                .mapNotNull { it.placeId }
                .toSet()
            return OfflineMapsGroups(
                places = OfflinePlaces.group(regions).map { it.toEntry(failed = it.placeId in failedPlaces) },
                corridors = regions.filter { it.kind == PackKind.Corridor && it.placeId == null }
                    .sortedByDescending { it.downloadedAtMs },
                views = regions.filter { it.kind == PackKind.View && it.placeId == null }
                    .sortedByDescending { it.downloadedAtMs },
            )
        }
    }
}

/** Іменоване місце, зібране з частин, — те, що бачить людина: одна назва, одне число. */
data class OfflinePlaceEntry(
    val placeId: String,
    val nameUa: String,
    val minZoom: Int,
    val maxZoom: Int,
    val plannedChunks: Int,
    val doneChunks: Int,
    val completedBytes: Long,
    val percent: Int,
    val complete: Boolean,
    val downloading: Boolean,
    /** Хоч одна частина впала — рядок мусить сказати це, а не «пауза». */
    val failed: Boolean,
    val styleUrl: String?,
)

data class OfflineMapsGroups(
    val places: List<OfflinePlaceEntry>,
    val corridors: List<RegionInfo>,
    val views: List<RegionInfo>,
) {
    val isEmpty: Boolean get() = places.isEmpty() && corridors.isEmpty() && views.isEmpty()

    val count: Int get() = places.size + corridors.size + views.size

    /** Одиниць, що лежать на телефоні цілком. */
    val readyCount: Int
        get() = places.count { it.complete } + corridors.count { it.isComplete } + views.count { it.isComplete }

    /** Одиниць, що ще тягнуться (або стоять на паузі посеред дороги). */
    val downloadingCount: Int
        get() = places.count { !it.complete } + corridors.count { !it.isComplete } + views.count { !it.isComplete }
}

private fun PlacePackStatus.toEntry(failed: Boolean) = OfflinePlaceEntry(
    placeId = placeId,
    nameUa = nameUa,
    minZoom = minZoom,
    maxZoom = maxZoom,
    plannedChunks = plannedChunks,
    doneChunks = doneChunks,
    completedBytes = completedBytes,
    percent = percent,
    complete = complete,
    downloading = downloading,
    failed = failed,
    styleUrl = styleUrl,
)

// Слова стану — ОДИН словник на мапу й Профіль. Рядки лишаються в ресурсах
// feature-stream, де їх уже перекладено й перевірено тестом парності.

/** Розмір словами («120 МБ»), тим самим форматом, що й на мапі. */
@Composable
fun offlineBytesLabel(bytes: Long): String = formatBytes(bytes)

/** Назва пакета так, як її читає людина: коридор — «дорога до …», решта — своє імʼя. */
@Composable
fun offlineRegionTitle(region: RegionInfo): String {
    val name = region.name.ifBlank { stringResource(R.string.map_offline_unnamed) }
    return when (region.kind) {
        PackKind.Corridor -> region.routeLabel
            ?.let { stringResource(R.string.map_offline_corridor_to, it) }
            ?: stringResource(R.string.map_offline_corridor_named, name)
        else -> name
    }
}

/**
 * Рядок стану коридора чи ділянки. Причина «чекає …» стоїть на кожному
 * незавершеному рядку, бо людина дивиться на свій пакет, а не на секцію.
 */
@Composable
fun offlineRegionLine(
    region: RegionInfo,
    error: PackError?,
    verdict: OfflineConditions.Verdict,
): String {
    val size = formatBytes(region.completedResourceSizeBytes)
    return when {
        error is PackError.TooManyTiles -> stringResource(R.string.map_offline_state_error_tiles)
        error != null -> stringResource(R.string.map_offline_state_error)
        !region.isComplete && region.pausedByOperator ->
            stringResource(R.string.map_offline_state_paused, region.percent)
        !region.isComplete && verdict != OfflineConditions.Verdict.GO -> waitLine(verdict)
        // Знаменник — оцінка з уже приїханих ресурсів. Поки не приїхало нічого,
        // оцінювати нема з чого, і рядок обходиться без неї.
        !region.isComplete && region.estimatedTotalBytes > 0L -> stringResource(
            R.string.map_offline_state_downloading,
            region.percent,
            size,
            formatBytes(region.estimatedTotalBytes),
        )
        !region.isComplete -> stringResource(R.string.map_offline_state_downloading_nototal, region.percent)
        region.downloadedAtMs > 0L -> stringResource(
            R.string.map_offline_state_ready,
            size,
            region.minZoom,
            region.maxZoom,
            SAVED_ON.format(java.util.Date(region.downloadedAtMs)),
        )
        // Дата невідома — словом, не вигаданою датою.
        else -> stringResource(R.string.map_offline_state_ready_nodate, size, region.minZoom, region.maxZoom)
    }
}

/** Рядок стану місця: «завантажено» лише тоді, коли всі частини є і всі закінчені. */
@Composable
fun offlinePlaceLine(place: OfflinePlaceEntry, verdict: OfflineConditions.Verdict): String {
    val size = formatBytes(place.completedBytes)
    return when {
        place.failed -> stringResource(R.string.map_offline_state_error)
        place.complete -> stringResource(
            R.string.map_places_state_complete,
            place.plannedChunks,
            size,
            place.minZoom,
            place.maxZoom,
        )
        verdict != OfflineConditions.Verdict.GO -> waitLine(verdict)
        place.downloading -> stringResource(
            R.string.map_places_state_downloading,
            place.percent,
            place.doneChunks,
            place.plannedChunks,
            size,
        )
        else -> stringResource(
            R.string.map_places_state_paused,
            place.percent,
            place.doneChunks,
            place.plannedChunks,
            size,
        )
    }
}

@Composable
private fun waitLine(verdict: OfflineConditions.Verdict): String = stringResource(
    when (verdict) {
        OfflineConditions.Verdict.METERED -> R.string.map_places_wait_metered
        OfflineConditions.Verdict.LOW_BATTERY -> R.string.map_places_wait_battery
        else -> R.string.map_places_wait_offline
    },
)

private val SAVED_ON by lazy {
    java.text.SimpleDateFormat("d MMM", java.util.Locale("uk", "UA"))
}
