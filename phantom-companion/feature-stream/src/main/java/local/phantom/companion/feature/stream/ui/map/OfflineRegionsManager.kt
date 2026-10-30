package local.phantom.companion.feature.stream.ui.map

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import local.phantom.companion.core.geo.place.PlaceNaming
import local.phantom.companion.core.geo.place.PlacePackPlanner
import local.phantom.companion.feature.stream.ui.map.source.BatchHSource
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionDefinition
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * MAP-4 — coroutine-friendly wrapper around `org.maplibre.android.offline`.
 *
 * MapLibre Native ships a comprehensive offline region API that survives
 * app kill (the underlying SQLite cache is on disk; resuming
 * setDownloadState(STATE_ACTIVE) restarts in-progress downloads).
 * The Java API is callback-based; this wrapper presents:
 *   - `regions: StateFlow<List<RegionInfo>>` — live list of all packs
 *     with name + completed/total + on-disk size, refreshable on demand.
 *   - `suspend fun createRegion(...)` — kicks off a download for a bbox
 *     against the current style. Returns the OfflineRegion handle.
 *   - `suspend fun delete(id: Long)` — removes a pack and its tiles.
 *   - `suspend fun refresh()` — re-poll all regions for status.
 *
 * Operator-facing names are encoded into the metadata blob as JSON
 * (the API accepts arbitrary bytes; we use UTF-8 JSON for readability
 * in the SQLite cache should the operator ever inspect it).
 *
 * WorkManager wrapper for kill-resilience: out of scope for MAP-4. The
 * downloads are kill-resilient at the MapLibre layer (the SQLite pack
 * survives), but progress isn't actively pumped while the app is
 * background; SV-N polish would promote to a foreground service.
 */
class OfflineRegionsManager(private val context: Context) {

    init {
        // MapLibre.getInstance() is idempotent; call here so the
        // OfflineManager singleton has a live native pointer regardless
        // of which MapView opened first.
        MapLibre.getInstance(context)
        runCatching {
            OfflineManager.getInstance(context).setOfflineMapboxTileCountLimit(Long.MAX_VALUE)
        }
    }

    private val manager: OfflineManager = OfflineManager.getInstance(context)
    private val json = Json { ignoreUnknownKeys = true }

    private val _regions = MutableStateFlow<List<RegionInfo>>(emptyList())
    val regions: StateFlow<List<RegionInfo>> = _regions.asStateFlow()

    /** Розмір файлу сховища — єдине число про місце, яке підтверджується диском. */
    private val _storeBytes = MutableStateFlow(0L)
    val storeBytes: StateFlow<Long> = _storeBytes.asStateFlow()

    /**
     * Re-poll the full pack list and refresh each pack's
     * status/size in [_regions].
     */
    suspend fun refresh() {
        val all = listRegionsSuspend() ?: return
        val infos = all.map { region ->
            val meta = decodeMeta(region.metadata)
            val def = runCatching { region.definition }.getOrNull()
            val bounds = runCatching { def?.bounds }.getOrNull()
            // Правда про рамку лежить у ВИЗНАЧЕННІ регіону завжди — метадані
            // її можуть не мати (перші 16 пакетів на телефоні власника
            // зберігали саму лише назву). Тому визначення тут перше джерело, а
            // метадані — уточнення, а не умова.
            val n = meta?.northLat ?: bounds?.latitudeNorth
            val e = meta?.eastLon ?: bounds?.longitudeEast
            val s = meta?.southLat ?: bounds?.latitudeSouth
            val w = meta?.westLon ?: bounds?.longitudeWest
            val area = if (n != null && e != null && s != null && w != null) {
                local.phantom.companion.feature.stream.domain.OfflineCoverage.Area(n, e, s, w)
            } else null
            // «регіон #17» більше немає: рамка є завжди, отже назва за
            // координатами є завжди. Порожнє імʼя лишається лише для пакета,
            // чиє визначення не читається взагалі, — і слово для нього дає UI,
            // а не літерал тут.
            val name = meta?.name?.takeIf { it.isNotBlank() && !isClockName(it) }
                ?: area?.let {
                    PlaceNaming.forBox(it.northLat, it.eastLon, it.southLat, it.westLon)
                }
                ?: ""
            val status = statusSuspend(region)
            RegionInfo(
                id = region.id,
                name = name,
                area = area,
                styleUrl = meta?.styleUrl ?: def?.styleURL,
                completedResourceCount = status?.completedResourceCount ?: 0L,
                requiredResourceCount = status?.requiredResourceCount ?: 0L,
                completedResourceSizeBytes = status?.completedResourceSize ?: 0L,
                isComplete = (status?.requiredResourceCount ?: 0L) > 0L &&
                    status?.completedResourceCount == status?.requiredResourceCount,
                downloadState = status?.downloadState ?: OfflineRegion.STATE_INACTIVE,
                active = status?.downloadState == OfflineRegion.STATE_ACTIVE,
                // «застаріло» needs a clock to compare against — this is the
                // only one that exists today (when WE pulled it), not OSM's own
                // edit date, which nothing in this pipeline carries yet. Honest
                // about what it means: 0L (packs saved before this field
                // existed) reads as "unknown", never as "just downloaded".
                downloadedAtMs = meta?.downloadedAtMs ?: 0L,
                placeId = meta?.placeId,
                placeNameUa = meta?.placeNameUa,
                chunkIndex = meta?.chunkIndex ?: -1,
                chunkCount = meta?.chunkCount ?: 0,
                minZoom = meta?.minZoom?.takeIf { it >= 0 } ?: def?.minZoom?.toInt() ?: -1,
                maxZoom = meta?.maxZoom?.takeIf { it >= 0 } ?: def?.maxZoom?.toInt() ?: -1,
                pausedByOperator = meta?.pausedByOperator ?: false,
                kind = kindOf(meta, def),
                routeLabel = meta?.routeLabel,
                geometryJson = meta?.geometryJson,
            )
        }
        _regions.value = infos
        _storeBytes.value = storeBytesOnDisk()
        armErrorWatch(all, infos)
    }

    /**
     * Скільки сховище MapLibre важить НА ДИСКУ.
     *
     * Не сума `completedResourceSize` по регіонах: 17.08.2026 на телефоні
     * власника та сума давала 2590 МБ, а файл — 1828 МБ. Різниця не в
     * дублікатах тайлів (їх лише 71 МБ), а в шрифтах і спрайтах: 396 спільних
     * ресурсів на 18 МБ лежать у сховищі один раз, але кожен з 65 регіонів
     * рахує їх собі повністю — 1101 МБ, яких немає. Число зверху — це `length()`
     * файлу, і його ніхто не виводить.
     */
    private fun storeBytesOnDisk(): Long = runCatching {
        context.filesDir.listFiles { f -> f.name.startsWith(STORE_FILE) }
            ?.sumOf { it.length() } ?: 0L
    }.getOrDefault(0L)

    /** Скільки вільного там, де лежить сховище. */
    fun usableDiskBytes(): Long = runCatching { context.filesDir.usableSpace }.getOrDefault(0L)

    /**
     * Помилки завантаження, які досі йшли лише в лог: пакет вічно «тягнувся», і
     * жодне слово не доходило до ока.
     *
     * Спостерігач чіпляється ЛИШЕ до активних пакетів і ЛИШЕ на помилку —
     * `onStatusChanged` навмисно порожній. Інакше кожен приїханий тайл писав би
     * у StateFlow, а це рекомпозиція панелі кількасот разів на секунду.
     */
    val errors: StateFlow<Map<Long, PackError>> get() = _errors.asStateFlow()

    private val _errors = MutableStateFlow<Map<Long, PackError>>(emptyMap())
    private val watched = HashMap<Long, OfflineRegion>()

    private fun armErrorWatch(all: Array<OfflineRegion>, infos: List<RegionInfo>) {
        val busy = infos.filter { it.active && !it.isComplete }.map { it.id }.toSet()
        watched.keys.toList().forEach { id ->
            if (id !in busy) {
                runCatching { watched.remove(id)?.setObserver(null) }
            }
        }
        all.forEach { region ->
            if (region.id !in busy || watched.containsKey(region.id)) return@forEach
            val observer = object : OfflineRegion.OfflineRegionObserver {
                override fun onStatusChanged(status: OfflineRegionStatus) = Unit
                override fun onError(error: OfflineRegionError) {
                    _errors.value = _errors.value + (region.id to PackError.Failed(error.message))
                }
                override fun mapboxTileCountLimitExceeded(limit: Long) {
                    _errors.value = _errors.value + (region.id to PackError.TooManyTiles(limit))
                }
            }
            runCatching {
                region.setObserver(observer)
                watched[region.id] = region
            }
        }
    }

    /**
     * Відпустити всіх спостерігачів. Спостерігач MapLibre тримає нативне
     * посилання, і збирач сміття JVM його не бачить.
     */
    fun detachObservers() {
        watched.values.forEach { runCatching { it.setObserver(null) } }
        watched.clear()
    }

    /** «Спробувати ще» — стирає помилку і знову озброює пакет. */
    suspend fun retry(id: Long): Boolean {
        _errors.value = _errors.value - id
        val region = listRegionsSuspend().orEmpty().firstOrNull { it.id == id } ?: return false
        decodeMeta(region.metadata)?.takeIf { it.pausedByOperator }?.let {
            updateMetadataSuspend(region, encodeMeta(it.copy(pausedByOperator = false)))
        }
        val ok = runCatching { region.setDownloadState(OfflineRegion.STATE_ACTIVE) }.isSuccess
        runCatching { refresh() }
        return ok
    }

    /**
     * Одноразове прибирання старих метаданих. Ідемпотентне за умовою, тому
     * прапорця «вже робили» не потрібно: після першого проходу жоден пакет під
     * умову не підпадає, і виклик коштує один `listOfflineRegions`.
     *
     * Викликається З ОДНОГО місця — офлайн-панелі, єдиної, де ці пакети видно.
     * Дві інстанції, що пишуть метадані наввипередки, дали б два різні імені
     * одному пакету, тому другого виклику бути не повинно.
     */
    suspend fun migrateLegacyMetadata(): Int {
        var touched = 0
        listRegionsSuspend().orEmpty().forEach { region ->
            // Не декодується в наш тип → не наш пакет, і чіпати його метадані
            // ми не маємо права.
            val meta = decodeMeta(region.metadata) ?: return@forEach
            if (meta.placeId != null) return@forEach
            val def = runCatching { region.definition }.getOrNull() ?: return@forEach
            val bounds = runCatching { def.bounds }.getOrNull() ?: return@forEach

            val legacyCorridor = BatchHSource.LEGACY_CORRIDOR_NAME.find(meta.name)
            // Для СТАРИХ пакетів тип визначення нічого не каже: коридори тоді
            // різалися на прямокутні ланки, і всі 77 пакетів на телефоні
            // власника — `tileregion`. Єдиний слід типу в них — назва, і саме
            // тому міграція мусить прочитати її раніше, ніж її ж і затре.
            val kind = meta.kind
                ?: if (legacyCorridor != null) KIND_CORRIDOR else kindFromDefinitionType(def.type)
            val name = if (isClockName(meta.name) || legacyCorridor != null) {
                PlaceNaming.forBox(
                    northLat = bounds.latitudeNorth,
                    eastLon = bounds.longitudeEast,
                    southLat = bounds.latitudeSouth,
                    westLon = bounds.longitudeWest,
                )
            } else {
                meta.name
            }
            val fixed = meta.copy(
                name = name,
                kind = kind,
                northLat = meta.northLat ?: bounds.latitudeNorth,
                eastLon = meta.eastLon ?: bounds.longitudeEast,
                southLat = meta.southLat ?: bounds.latitudeSouth,
                westLon = meta.westLon ?: bounds.longitudeWest,
                styleUrl = meta.styleUrl ?: def.styleURL,
                minZoom = if (meta.minZoom >= 0) meta.minZoom else def.minZoom.toInt(),
                maxZoom = if (meta.maxZoom >= 0) meta.maxZoom else def.maxZoom.toInt(),
                // Стара назва «Коридор: активний маршрут 3/12» несе номер
                // ланки, але не несе цілі — вигадати «до Львова» з неї нема з
                // чого, і routeLabel лишається порожнім чесно.
                chunkIndex = if (meta.chunkIndex >= 0) meta.chunkIndex
                else legacyCorridor?.groupValues?.get(1)?.toIntOrNull()?.minus(1) ?: -1,
                chunkCount = if (meta.chunkCount > 0) meta.chunkCount
                else legacyCorridor?.groupValues?.get(2)?.toIntOrNull() ?: 0,
            )
            if (fixed == meta) return@forEach
            if (updateMetadataSuspend(region, encodeMeta(fixed))) touched++
        }
        if (touched > 0) runCatching { refresh() }
        return touched
    }

    private fun kindOf(meta: RegionMetadata?, def: OfflineRegionDefinition?): PackKind = when {
        meta?.placeId != null -> PackKind.Place
        meta?.kind == KIND_CORRIDOR -> PackKind.Corridor
        meta?.kind == KIND_VIEW -> PackKind.View
        // Для НОВИХ пакетів тип визначення — теж дані, і надійніші за префікс у
        // назві: коридор створюється геометрією, клапоть — пірамідою по рамці.
        // Старі коридори були прямокутними ланками, і їх розбирає міграція.
        def?.type == TYPE_SHAPE -> PackKind.Corridor
        meta != null && BatchHSource.LEGACY_CORRIDOR_NAME.containsMatchIn(meta.name) -> PackKind.Corridor
        else -> PackKind.View
    }

    private fun kindFromDefinitionType(type: String?): String =
        if (type == TYPE_SHAPE) KIND_CORRIDOR else KIND_VIEW

    /** Скільки цей пристрій уже качав на ресурс. `null` — вибірки ще нема. */
    internal fun deviceAverage(): PlacePackPlanner.DeviceAverage? {
        val infos = _regions.value
        return PlacePackPlanner.deviceAverage(
            completedResources = infos.sumOf { it.completedResourceCount },
            completedBytes = infos.sumOf { it.completedResourceSizeBytes },
        )
    }

    /**
     * Розкласти іменоване місце на частини й поставити кожну в чергу.
     *
     * Повторний виклик для того самого місця й стилю нічого не дублює: уже
     * створена частина знаходиться за (`placeId`, `chunkIndex`, `styleUrl`) і
     * лише переводиться в ACTIVE. Це і є відновлення після обриву — пакет
     * MapLibre живе в SQLite, тож повтор продовжує з того місця, де стало.
     */
    internal suspend fun createPlacePacks(
        plan: PlacePackPlanner.Plan,
        styleUrl: String,
        allowMetered: Boolean = true,
    ): Int {
        val place = plan.place
        // Другий рубіж. Пікер уже не показує глибину, яка не влізе, — але
        // контроль, який стоїть лише в UI, це контроль, який одного дня обійдуть
        // із коду.
        if (!OfflineBudget.fitsDisk(plan.bytes, usableDiskBytes())) return 0
        val existing = listRegionsSuspend().orEmpty().mapNotNull { region ->
            val meta = decodeMeta(region.metadata) ?: return@mapNotNull null
            if (meta.placeId != place.id || meta.styleUrl != styleUrl) return@mapNotNull null
            if (meta.minZoom != plan.minZoom || meta.maxZoom != plan.maxZoom) return@mapNotNull null
            meta.chunkIndex to region
        }.toMap()

        val go = OfflineConditions.check(context, allowMetered) == OfflineConditions.Verdict.GO
        val pixelRatio = context.resources.displayMetrics.density
        var armed = 0
        for (chunk in plan.chunks) {
            val already = existing[chunk.index]
            // `continue` may not cross an inline lambda here, so the region is
            // built first and the skip decided at the loop's own level.
            val made = already ?: run {
                val definition = OfflineTilePyramidRegionDefinition(
                    styleUrl,
                    LatLngBounds.from(chunk.northLat, chunk.eastLon, chunk.southLat, chunk.westLon),
                    plan.minZoom.toDouble(),
                    plan.maxZoom.toDouble(),
                    pixelRatio,
                )
                createRegionSuspend(
                    definition,
                    encodeName(
                        name = "${place.nameUa} · ${chunk.index + 1}/${plan.chunkCount}",
                        bounds = OfflineBounds(
                            northLat = chunk.northLat,
                            eastLon = chunk.eastLon,
                            southLat = chunk.southLat,
                            westLon = chunk.westLon,
                        ),
                        styleUrl = styleUrl,
                        placeId = place.id,
                        placeNameUa = place.nameUa,
                        chunkIndex = chunk.index,
                        chunkCount = plan.chunkCount,
                        minZoom = plan.minZoom,
                        maxZoom = plan.maxZoom,
                    ),
                )
            }
            val region = made ?: continue
            // Явне «Завантажити» знімає паузу, яку оператор ставив раніше —
            // інакше автовідновлення обходило б власне замовлення оператора.
            if (already != null) {
                decodeMeta(already.metadata)?.takeIf { it.pausedByOperator }?.let {
                    updateMetadataSuspend(already, encodeMeta(it.copy(pausedByOperator = false)))
                }
            }
            if (go) {
                runCatching { region.setDownloadState(OfflineRegion.STATE_ACTIVE) }
                armed++
            }
        }
        runCatching { refresh() }
        return armed
    }

    /**
     * Знову озброїти кожен незавершений пакет, якщо умови це дозволяють.
     * Викликається з екрана раз на кілька секунд — саме це робить завантаження
     * таким, що переживає обрив звʼязку без дій оператора.
     *
     * Раніше сюди потрапляли лише частини іменованих місць, тож коридор, який
     * впав разом зі звʼязком, лишався лежати назавжди. Тепер відновлюється все,
     * у чого є [RegionMetadata.kind] або `placeId`; пакети без жодного з них —
     * це метадані, яких ми не писали, і чіпати їх не наша справа.
     */
    internal suspend fun resumeIncompletePlacePacks(allowMetered: Boolean = false): Int {
        if (OfflineConditions.check(context, allowMetered) != OfflineConditions.Verdict.GO) return 0
        var touched = 0
        listRegionsSuspend().orEmpty().forEach { region ->
            val meta = decodeMeta(region.metadata) ?: return@forEach
            if (meta.placeId == null && meta.kind == null) return@forEach
            // Пауза, яку поставив оператор, переживає і перезапуск: інакше цей
            // самий цикл через шість секунд знову вмикав би те, що він вимкнув.
            if (meta.pausedByOperator) return@forEach
            // Пакет, який уже показує «помилка · спробувати ще», не озброюємо
            // мовчки: інакше слово на екрані обіцяє дію, яку цикл щойно зробив
            // сам, і кнопка стає декорацією.
            if (_errors.value.containsKey(region.id)) return@forEach
            val status = statusSuspend(region) ?: return@forEach
            val complete = status.requiredResourceCount > 0L &&
                status.completedResourceCount == status.requiredResourceCount
            if (complete || status.downloadState == OfflineRegion.STATE_ACTIVE) return@forEach
            runCatching { region.setDownloadState(OfflineRegion.STATE_ACTIVE) }
            touched++
        }
        return touched
    }

    /**
     * Пауза оператора для одного пакета — коридора чи клаптя. Раніше її мали
     * лише іменовані місця, і зупинити коридор було нічим.
     */
    internal suspend fun setPaused(id: Long, paused: Boolean): Boolean {
        val region = listRegionsSuspend().orEmpty().firstOrNull { it.id == id } ?: return false
        val meta = decodeMeta(region.metadata) ?: return false
        runCatching {
            region.setDownloadState(
                if (paused) OfflineRegion.STATE_INACTIVE else OfflineRegion.STATE_ACTIVE,
            )
        }
        val ok = updateMetadataSuspend(region, encodeMeta(meta.copy(pausedByOperator = paused)))
        runCatching { refresh() }
        return ok
    }

    /** Зупинити всі частини місця — оператор вирішив, що зараз не час. */
    internal suspend fun pausePlace(placeId: String): Int = setPlacePaused(placeId, true)

    /** Знову пустити саме це місце — і жодне інше. */
    internal suspend fun resumePlace(placeId: String, allowMetered: Boolean = false): Int {
        val armed = setPlacePaused(placeId, false)
        if (OfflineConditions.check(context, allowMetered) == OfflineConditions.Verdict.GO) {
            resumeIncompletePlacePacks(allowMetered)
        }
        return armed
    }

    private suspend fun setPlacePaused(placeId: String, paused: Boolean): Int {
        var touched = 0
        listRegionsSuspend().orEmpty().forEach { region ->
            val meta = decodeMeta(region.metadata) ?: return@forEach
            if (meta.placeId != placeId) return@forEach
            runCatching {
                region.setDownloadState(
                    if (paused) OfflineRegion.STATE_INACTIVE else OfflineRegion.STATE_ACTIVE,
                )
            }
            updateMetadataSuspend(region, encodeMeta(meta.copy(pausedByOperator = paused)))
            touched++
        }
        runCatching { refresh() }
        return touched
    }

    private suspend fun updateMetadataSuspend(region: OfflineRegion, bytes: ByteArray): Boolean =
        suspendCancellableCoroutine { cont ->
            region.updateMetadata(
                bytes,
                object : OfflineRegion.OfflineRegionUpdateMetadataCallback {
                    override fun onUpdate(p0: ByteArray) = cont.resume(true)
                    override fun onError(p0: String) {
                        Log.w(TAG, "updateMetadata failed: $p0")
                        cont.resume(false)
                    }
                },
            )
        }

    /** Прибрати місце цілком — усі його частини разом із тайлами. */
    internal suspend fun deletePlace(placeId: String): Int {
        val victims = listRegionsSuspend().orEmpty()
            .filter { decodeMeta(it.metadata)?.placeId == placeId }
        var gone = 0
        for (region in victims) {
            val ok = suspendCancellableCoroutine { cont ->
                region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() = cont.resume(true)
                    override fun onError(p0: String) {
                        Log.w(TAG, "deletePlace($placeId) chunk failed: $p0")
                        cont.resume(false)
                    }
                })
            }
            if (ok) gone++
        }
        runCatching { refresh() }
        return gone
    }

    /**
     * Create a new offline region pack covering [bounds] against the
     * provided [styleUrl]. Starts the download immediately
     * (STATE_ACTIVE) and refreshes the [regions] flow.
     *
     * Pixel-ratio defaults to the device density so vector glyphs are
     * sized correctly on whatever screen the operator is on. The
     * [bounds] type is the MapLibre-free [OfflineBounds] so callers in
     * `:app` don't need to depend on MapLibre at compile time.
     */
    /**
     * ОДИН пакет уздовж маршруту замість купи дрібних.
     *
     * Власник: «незнаю як ти думаєш користувачі це видаляти будуть — став 1
     * коридор на маршрут, який вміщує все незавантажене і околиці для
     * перепрокладання і того, куди по дорозі заїхати можна».
     *
     * Форма — `MultiPolygon` із [RouteCorridor], а не прямокутник: рамка навколо
     * Київ — Львів це 540 × 200 км, з яких дорогою лежить смуга завширшки з
     * десяток. MapLibre вміє геометрію, і саме вона робить коридор коридором;
     * [bounds] тут потрібні лише для підпису й для показу ділянки на мапі.
     */
    suspend fun createCorridor(
        name: String,
        geometryJson: String,
        bounds: OfflineBounds,
        styleUrl: String,
        minZoom: Double = MIN_DEFAULT_ZOOM,
        maxZoom: Double = MAX_DEFAULT_ZOOM,
        allowMetered: Boolean = true,
        routeLabel: String? = null,
        estimateBytes: Long = 0L,
    ): Boolean {
        val geometry = runCatching {
            org.maplibre.geojson.MultiPolygon.fromJson(geometryJson)
        }.getOrNull() ?: return false
        if (estimateBytes > 0L &&
            !OfflineBudget.fitsDisk(estimateBytes, usableDiskBytes())
        ) {
            return false
        }
        // Один коридор на маршрут. Другий тап по тій самій кнопці досі робив
        // ще один пакет із тією самою землею: 59 з 77 пакетів на телефоні
        // власника — це саме такі повтори.
        existingCorridor(routeLabel, bounds, styleUrl)?.let { twin ->
            decodeMeta(twin.metadata)?.let { meta ->
                updateMetadataSuspend(
                    twin,
                    encodeMeta(meta.copy(pausedByOperator = false, geometryJson = geometryJson)),
                )
            }
            if (OfflineConditions.check(context, allowMetered) == OfflineConditions.Verdict.GO) {
                runCatching { twin.setDownloadState(OfflineRegion.STATE_ACTIVE) }
            }
            runCatching { refresh() }
            return true
        }
        val cappedMax = OfflineBudget.cappedMaxZoom(
            northLat = bounds.northLat,
            eastLon = bounds.eastLon,
            southLat = bounds.southLat,
            westLon = bounds.westLon,
            minZoom = minZoom.toInt(),
            requestedMax = maxZoom.toInt(),
        ).toDouble()
        val definition = org.maplibre.android.offline.OfflineGeometryRegionDefinition(
            styleUrl,
            geometry,
            minZoom,
            cappedMax,
            context.resources.displayMetrics.density,
        )
        val metadata = encodeName(
            name = name,
            bounds = bounds,
            styleUrl = styleUrl,
            minZoom = minZoom.toInt(),
            maxZoom = cappedMax.toInt(),
            kind = KIND_CORRIDOR,
            routeLabel = routeLabel,
            geometryJson = geometryJson,
        )
        val region = createRegionSuspend(definition, metadata) ?: return false
        if (OfflineConditions.check(context, allowMetered) == OfflineConditions.Verdict.GO) {
            runCatching { region.setDownloadState(OfflineRegion.STATE_ACTIVE) }
        }
        runCatching { refresh() }
        return true
    }

    /**
     * Уже збережений коридор цього ж маршруту, або `null`.
     *
     * Мітка маршруту — головний ключ; коли її немає (маршрут без названої
     * цілі), лишається рамка. [BOX_EPSILON] — приблизно сто метрів: маршрут,
     * перерахований після кроку вбік, дає ті самі рамки з точністю до шуму GPS,
     * і вимагати побітового збігу означало б не знайти жодного близнюка.
     */
    private suspend fun existingCorridor(
        routeLabel: String?,
        bounds: OfflineBounds,
        styleUrl: String,
    ): OfflineRegion? = listRegionsSuspend().orEmpty().firstOrNull { region ->
        val meta = decodeMeta(region.metadata) ?: return@firstOrNull false
        if (meta.kind != KIND_CORRIDOR || meta.styleUrl != styleUrl) return@firstOrNull false
        if (routeLabel != null && meta.routeLabel != null) {
            return@firstOrNull meta.routeLabel == routeLabel
        }
        val n = meta.northLat ?: return@firstOrNull false
        val e = meta.eastLon ?: return@firstOrNull false
        val s = meta.southLat ?: return@firstOrNull false
        val w = meta.westLon ?: return@firstOrNull false
        abs(n - bounds.northLat) <= BOX_EPSILON && abs(e - bounds.eastLon) <= BOX_EPSILON &&
            abs(s - bounds.southLat) <= BOX_EPSILON && abs(w - bounds.westLon) <= BOX_EPSILON
    }

    suspend fun createRegion(
        name: String,
        bounds: OfflineBounds,
        styleUrl: String,
        minZoom: Double = MIN_DEFAULT_ZOOM,
        maxZoom: Double = MAX_DEFAULT_ZOOM,
        allowMetered: Boolean = false,
        estimateBytes: Long = 0L,
    ): Boolean {
        if (estimateBytes > 0L &&
            !OfflineBudget.fitsDisk(estimateBytes, usableDiskBytes())
        ) {
            return false
        }
        val pixelRatio = context.resources.displayMetrics.density
        val latLngBounds = LatLngBounds.from(
            bounds.northLat,
            bounds.eastLon,
            bounds.southLat,
            bounds.westLon,
        )
        // A pack deeper than its area can afford is how a tile store reaches
        // 1,9 GB: every level quadruples the count.
        val cappedMax = OfflineBudget.cappedMaxZoom(
            northLat = bounds.northLat,
            eastLon = bounds.eastLon,
            southLat = bounds.southLat,
            westLon = bounds.westLon,
            minZoom = minZoom.toInt(),
            requestedMax = maxZoom.toInt(),
        ).toDouble()
        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl,
            latLngBounds,
            minZoom,
            cappedMax,
            pixelRatio,
        )
        val metadata = encodeName(
            name = name,
            bounds = bounds,
            styleUrl = styleUrl,
            minZoom = minZoom.toInt(),
            maxZoom = cappedMax.toInt(),
            kind = KIND_VIEW,
        )
        val region = createRegionSuspend(definition, metadata) ?: return false
        // The pack exists either way; whether it PULLS now is a separate
        // question. Started unconditionally, it competed with the operator's
        // own mobile data and drained a battery he still needed.
        if (OfflineConditions.check(context, allowMetered) == OfflineConditions.Verdict.GO) {
            runCatching { region.setDownloadState(OfflineRegion.STATE_ACTIVE) }
        }
        runCatching { refresh() }
        return true
    }

    /**
     * Resume every paused pack whose conditions now allow it, and pause the
     * ones they no longer do. Cheap enough to call on a connectivity change.
     */
    suspend fun reconcile(allowMetered: Boolean) {
        val go = OfflineConditions.check(context, allowMetered) == OfflineConditions.Verdict.GO
        listRegionsSuspend().orEmpty().forEach { region ->
            runCatching {
                region.setDownloadState(
                    if (go) OfflineRegion.STATE_ACTIVE else OfflineRegion.STATE_INACTIVE,
                )
            }
        }
    }

    /**
     * Remove the pack identified by [id]. Tiles, the metadata row,
     * and any in-progress download artifacts are wiped.
     */
    suspend fun delete(id: Long): Boolean {
        val all = listRegionsSuspend() ?: return false
        val region = all.firstOrNull { it.id == id } ?: return false
        return suspendCancellableCoroutine { cont ->
            region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() {
                    cont.resume(true)
                }
                override fun onError(p0: String) {
                    Log.w(TAG, "delete($id) failed: $p0")
                    cont.resume(false)
                }
            })
        }.also { runCatching { refresh() } }
    }

    private suspend fun listRegionsSuspend(): Array<OfflineRegion>? =
        suspendCancellableCoroutine { cont ->
            manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(p0: Array<OfflineRegion>?) {
                    cont.resume(p0)
                }
                override fun onError(p0: String) {
                    Log.w(TAG, "list failed: $p0")
                    cont.resume(null)
                }
            })
        }

    private suspend fun statusSuspend(region: OfflineRegion): OfflineRegionStatus? =
        suspendCancellableCoroutine { cont ->
            region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                override fun onStatus(p0: OfflineRegionStatus?) {
                    cont.resume(p0)
                }
                override fun onError(p0: String?) {
                    cont.resume(null)
                }
            })
        }

    private suspend fun createRegionSuspend(
        definition: org.maplibre.android.offline.OfflineRegionDefinition,
        metadata: ByteArray,
    ): OfflineRegion? = suspendCancellableCoroutine { cont ->
        manager.createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(p0: OfflineRegion) {
                    cont.resume(p0)
                }
                override fun onError(p0: String) {
                    Log.w(TAG, "create failed: $p0")
                    cont.resume(null)
                }
            },
        )
    }

    private fun encodeName(
        name: String,
        bounds: OfflineBounds? = null,
        styleUrl: String? = null,
        placeId: String? = null,
        placeNameUa: String? = null,
        chunkIndex: Int = -1,
        chunkCount: Int = 0,
        minZoom: Int = -1,
        maxZoom: Int = -1,
        kind: String? = null,
        routeLabel: String? = null,
        geometryJson: String? = null,
    ): ByteArray = encodeMeta(
        RegionMetadata(
            name = name,
            northLat = bounds?.northLat,
            eastLon = bounds?.eastLon,
            southLat = bounds?.southLat,
            westLon = bounds?.westLon,
            styleUrl = styleUrl,
            downloadedAtMs = System.currentTimeMillis(),
            placeId = placeId,
            placeNameUa = placeNameUa,
            chunkIndex = chunkIndex,
            chunkCount = chunkCount,
            minZoom = minZoom,
            maxZoom = maxZoom,
            kind = kind,
            routeLabel = routeLabel,
            geometryJson = geometryJson,
        ),
    )

    private fun encodeMeta(meta: RegionMetadata): ByteArray =
        json.encodeToString(RegionMetadata.serializer(), meta).toByteArray(Charsets.UTF_8)

    private fun decodeMeta(metadata: ByteArray?): RegionMetadata? = metadata?.let {
        runCatching {
            json.decodeFromString(RegionMetadata.serializer(), it.toString(Charsets.UTF_8))
        }.getOrNull()
    }

    @Serializable
    private data class RegionMetadata(
        val name: String,
        val northLat: Double? = null,
        val eastLon: Double? = null,
        val southLat: Double? = null,
        val westLon: Double? = null,
        val styleUrl: String? = null,
        // Absent on packs saved before this field existed; decoded via
        // ignoreUnknownKeys/default so an old pack's metadata still parses.
        val downloadedAtMs: Long = 0L,
        // Місцеві поля: заповнені лише для частин іменованого місця. Пакет
        // «поточний вид» лишається без них і саме тому не потрапляє під
        // автовідновлення та групування.
        val placeId: String? = null,
        val placeNameUa: String? = null,
        val chunkIndex: Int = -1,
        val chunkCount: Int = 0,
        val minZoom: Int = -1,
        val maxZoom: Int = -1,
        /** Пауза оператора, а не умов. Переживає перезапуск застосунку. */
        val pausedByOperator: Boolean = false,
        /**
         * `"corridor"` | `"view"` | `null`. Тип жив у ПРЕФІКСІ назви, тобто
         * ніде: рядок «коридор · Ірпінь» не можна ні згрупувати, ні
         * перекласти, ні відрізнити від клаптя, який людина так назвала.
         * Частини місця далі впізнаються за [placeId].
         */
        val kind: String? = null,
        /** Куди їхав маршрут — слово для рядка списку («до Львова»). */
        val routeLabel: String? = null,
        /**
         * GeoJSON `MultiPolygon` коридора. Без нього форму після збереження вже
         * не відновити: MapLibre віддає з визначення лише рамку геометрії.
         */
        val geometryJson: String? = null,
    )

    /**
     * Minimal observer attached to a fresh region so a UI screen can
     * react to status updates without polling. Returns the wired
     * observer — caller stores the reference and detaches via
     * `region.setObserver(null)` when done.
     */
    fun observe(
        region: OfflineRegion,
        onUpdate: (OfflineRegionStatus) -> Unit,
    ): OfflineRegion.OfflineRegionObserver {
        val observer = object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) = onUpdate(status)
            override fun onError(error: OfflineRegionError) {
                Log.w(TAG, "region observer error: ${error.message}")
            }
            override fun mapboxTileCountLimitExceeded(limit: Long) {
                Log.w(TAG, "tile-count limit exceeded: $limit")
            }
        }
        region.setObserver(observer)
        return observer
    }

    companion object {
        private const val TAG = "OfflineRegionsManager"
        const val MIN_DEFAULT_ZOOM: Double = 10.0

        /**
         * Стеля постачальника, не смак: глибше z14 джерело віддає порожньо, а
         * знаменник прогресу росте вчетверо на рівень. Було 16.
         */
        val MAX_DEFAULT_ZOOM: Double = OfflineBudget.PROVIDER_MAX_ZOOM.toDouble()

        const val KIND_CORRIDOR = "corridor"
        const val KIND_VIEW = "view"

        /** `getType()` геометричного визначення MapLibre — тобто коридор. */
        private const val TYPE_SHAPE = "shaperegion"

        /** ~100 м. Рамка з перерахованого маршруту зсувається на шум GPS. */
        private const val BOX_EPSILON = 1e-3

        private const val STORE_FILE = "mbgl-offline.db"

        /**
         * «регіон · 03.07 12:56» — назва з годинника, яких на телефоні власника
         * було 18. Місця в ній немає жодного слова.
         */
        internal fun isClockName(name: String): Boolean =
            BatchHSource.CLOCK_REGION_NAME.matches(name.trim())

        internal fun isLegacyCorridorName(name: String): Boolean =
            BatchHSource.LEGACY_CORRIDOR_NAME.containsMatchIn(name)
    }
}

/** Що це за пакет. Досі жило в префіксі назви, тобто ніде. */
enum class PackKind { Place, Corridor, View }

/** Помилка, яку видно оку, а не лише logcat. */
sealed interface PackError {
    data class Failed(val message: String?) : PackError
    data class TooManyTiles(val limit: Long) : PackError
}

/**
 * UI-facing snapshot of one offline pack — free of MapLibre types so
 * Composables can consume it without importing the SDK.
 */
data class RegionInfo(
    val id: Long,
    val name: String,
    /** Where the pack is, when it was saved by a build that recorded it. */
    val area: local.phantom.companion.feature.stream.domain.OfflineCoverage.Area? = null,
    /** The basemap it covers — a pack is only good for the style it was pulled against. */
    val styleUrl: String? = null,
    val completedResourceCount: Long,
    val requiredResourceCount: Long,
    val completedResourceSizeBytes: Long,
    val isComplete: Boolean,
    val downloadState: Int,
    /** Чи качається зараз — прочитано з константи SDK, а не з магічного числа. */
    val active: Boolean = false,
    /** When this pack was pulled — see the field's own note in [refresh]. 0L = unknown (pre-dates this field). */
    val downloadedAtMs: Long = 0L,
    /** Іменоване місце, частиною якого є цей пакет. `null` — пакет «поточний вид». */
    val placeId: String? = null,
    val placeNameUa: String? = null,
    val chunkIndex: Int = -1,
    val chunkCount: Int = 0,
    /** Діапазон масштабу, з яким пакет замовляли. `-1` — невідомо (старий пакет). */
    val minZoom: Int = -1,
    val maxZoom: Int = -1,
    /** Оператор натиснув «Пауза» — не те саме, що «чекає на Wi-Fi». */
    val pausedByOperator: Boolean = false,
    /** Тип пакета з даних, а не з префікса назви. */
    val kind: PackKind = PackKind.View,
    /** Куди їхав маршрут. `null` — маршрут без названої цілі або старий пакет. */
    val routeLabel: String? = null,
    /** Форма коридора — щоб її можна було намалювати, а не показати рамкою. */
    val geometryJson: String? = null,
) {
    val progressFraction: Float
        get() = if (requiredResourceCount <= 0L) 0f
        else (completedResourceCount.toFloat() / requiredResourceCount.toFloat())
            .coerceIn(0f, 1f)

    val percent: Int get() = (progressFraction * 100f).toInt()

    val sizeMb: Double get() = completedResourceSizeBytes / (1024.0 * 1024.0)

    /**
     * Скільки пакет важитиме, якщо решта ресурсів важитиме як уже приїхані.
     * `0` — ще нічого не приїхало, і оцінювати нема з чого; рядок стану тоді
     * обходиться без знаменника, а не друкує «≈0».
     */
    val estimatedTotalBytes: Long
        get() = if (completedResourceCount <= 0L || requiredResourceCount <= 0L) 0L
        else completedResourceSizeBytes / completedResourceCount * requiredResourceCount
}
