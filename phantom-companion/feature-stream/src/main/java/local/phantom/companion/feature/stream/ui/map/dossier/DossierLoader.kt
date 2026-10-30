package local.phantom.companion.feature.stream.ui.map.dossier

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import local.phantom.companion.core.net.map.OpeningHours
import local.phantom.companion.core.net.map.PlaceDossierClient
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.MapText

/**
 * P1 — «миттєва локальна картка, прогресивне збагачення».
 *
 * Було: `loadPlaceDossier` чекав УСІ джерела і повертав один результат, а
 * лист тримав повноекранний спінер «Збираю досьє…» до 15 секунд. Це не
 * повільна мережа — це неправильна архітектура: назва, категорія й
 * координата вже лежали в руках у момент тапу, а картка все одно нічого не
 * показувала, поки Overpass не відповість.
 *
 * Стало: потік знімків.
 *  • T+0, без жодного байта мережі — [instantDossier]: назва, категорія,
 *    координата, тобто все, чого досить, щоб «Маршрут» працював одразу.
 *  • Далі кожен слот доливається окремо, зі своїм таймаутом. Повільна
 *    Вікіпедія не затримує години роботи, мертвий Overpass не ховає адресу.
 *  • Повне досьє лягає в [DossierCache], тож друге відкриття миттєве.
 *
 * Хвиль дві, і це не оптимізація, а залежність: маршрути зупинки й «схоже
 * поруч» будуються з ТЕГІВ, яких до відповіді Overpass просто нема. Усе,
 * що від тегів не залежить (адреса, будівля, стаття-кандидат), стартує в
 * першій хвилі паралельно.
 */
internal fun placeDossierFlow(
    target: DossierTarget,
    sources: DossierSources,
    cache: DossierCache? = null,
    /** Актив пристрою для зупинок/станцій. null — лишається сама мережа. */
    transitSource: DossierTransitSource? = null,
): Flow<PlaceDossier> = channelFlow {
    // Кеш — єдиний шлях, де картка приходить одразу повною.
    cache?.get(target)?.let {
        send(it)
        return@channelFlow
    }

    var snapshot = instantDossier(target)
    send(snapshot)

    // Знімок правлять кілька корутин; мутекс робить кожну правку атомарною
    // і зберігає порядок надсилання.
    val guard = Mutex()
    suspend fun update(edit: (PlaceDossier) -> PlaceDossier) {
        guard.withLock {
            snapshot = edit(snapshot)
            send(snapshot)
        }
    }

    /**
     * Один слот: таймаут, чесна невдача, зняття з [PlaceDossier.pending].
     *
     * `Result?` замість простого `T?` навмисно — це рівно та різниця, яку
     * картка мусить показувати по-різному: «джерело мовчить» (таймаут або
     * виняток → [PlaceDossier.failed], пропозиція повторити) проти «джерело
     * відповіло, що тут нічого нема» (Закон 1 → просто відсутня секція).
     */
    suspend fun <T : Any> fill(
        slot: DossierSlot,
        fetch: suspend () -> T?,
        apply: (PlaceDossier, T) -> PlaceDossier,
    ) {
        val outcome: Result<T?>? = withTimeoutOrNull(SOURCE_TIMEOUT_MS) { runCatching { fetch() } }
        update { current ->
            val next = outcome?.getOrNull()?.let { apply(current, it) } ?: current
            next.copy(
                pending = next.pending - slot,
                failed = if (outcome == null || outcome.isFailure) next.failed + slot else next.failed,
            )
        }
    }

    // `coroutineScope` навколо хвиль — не косметика: він приєднує всіх дітей,
    // тож нижче `snapshot` гарантовано фінальний і його можна класти в кеш.
    coroutineScope {

        // ── Хвиля 1 — усе, що не залежить від тегів.
        launch {
            fill(DossierSlot.ADDRESS, { sources.reverseGeocode(target.lat, target.lon) }) { d, address ->
                val primaryTitle = if (d.title == FALLBACK_TITLE || d.title.isBlank()) {
                    val parts = address.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    when {
                        parts.size >= 2 && (parts[1].any { c -> c.isDigit() } || parts[1].length <= 6) ->
                            "${parts[0]}, ${parts[1]}"
                        parts.isNotEmpty() -> parts[0]
                        else -> d.title
                    }
                } else {
                    d.title
                }
                d.copy(
                    address = tagAddress(d.tags) ?: address,
                    title = primaryTitle,
                )
            }
        }
        launch {
            fill(DossierSlot.BUILDING, { sources.buildingAt(target.lat, target.lon) }) { d, b ->
                d.copy(building = b)
            }
        }

        // Стаття-кандидат від геопошуку їде паралельно, але вердикт їй виносить
        // друга хвиля — судити її можна лише проти сутності з тегів.
        val nearestArticle = CompletableDeferred<DossierArticle?>()
        launch {
            nearestArticle.complete(
                withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                    runCatching { sources.nearestArticle(target.lat, target.lon, target.nameHint) }
                        .getOrNull()
                },
            )
        }

        // ── Хвиля 2 — усе, що росте з тегів зіставленого елемента.
        launch {
            // Спершу локальне: намальовані POI, газетир, сітка доріг. Нуль
            // мережі, тож картка отримує особистість місця за мілісекунди.
            val local = pickLocalHit(
                withTimeoutOrNull(LOCAL_TIMEOUT_MS) {
                    runCatching { sources.localHits(target.lat, target.lon, target.nameHint) }
                        .getOrDefault(emptyList())
                }.orEmpty(),
            )
            if (local != null) {
                update { d ->
                    d.applyMatch(local.asMatch(), target).copy(
                        pending = d.pending - DossierSlot.DETAIL,
                        // Локальний збіг із тегами — це вже повна відповідь;
                        // без тегів картка чесно каже «шукаю глибше».
                        deepening = if (local.isAuthoritative) {
                            d.deepening
                        } else {
                            d.deepening + DossierSlot.DETAIL
                        },
                    )
                }
            }

            var match: DossierMatch? = local?.asMatch()
            if (local?.isAuthoritative != true) {
                val details = withTimeoutOrNull(OVERPASS_DETAIL_TIMEOUT_MS) {
                    runCatching { sources.namedAround(target.lat, target.lon) }.getOrNull()
                }
                val best = details?.let { bestMatch(it, target.nameHint) }
                if (best != null) match = best.asMatch()
                update { d ->
                    val next = if (best != null) d.applyMatch(match!!, target) else d
                    next.copy(
                        pending = next.pending - DossierSlot.DETAIL,
                        deepening = next.deepening - DossierSlot.DETAIL,
                        // Збій — лише мовчання джерела. Порожня відповідь
                        // означає «даних нема», і це інший стан картки.
                        failed = if (details == null) next.failed + DossierSlot.DETAIL else next.failed,
                    )
                }
            }

            val tags = match?.tags ?: emptyMap()
            val entity = entityRefOf(
                tags = tags,
                nameHint = match?.nameFallback ?: target.nameHint,
                categoryHint = match?.categoryHint ?: target.categoryHint,
                subcategoryHint = match?.subcategoryHint ?: target.subcategoryHint,
                osmId = match?.osmId,
            )

            val isStop = looksLikeTransitStop(tags, target)
            val isContainer = tags["shop"] == "mall" ||
                tags["amenity"] in setOf("marketplace", "bus_station") ||
                tags["building"] in setOf("retail", "mall", "commercial")
            if (isStop) {
                update { it.copy(similarTitle = STATION_NEARBY_TITLE) }
            }

            launch {
                if (!isStop) {
                    update { it.copy(pending = it.pending - DossierSlot.TRANSIT) }
                    return@launch
                }
                val lat = match?.lat ?: target.lat
                val lon = match?.lon ?: target.lon

                // Актив першим і БЕЗ таймауту мережі: 241 маршрут і три лінії
                // метро вже лежать на пристрої, тож офлайн-зупинка більше не
                // порожня, поки Overpass думає.
                val local = transitSource?.let {
                    runCatching { it.transitAt(lat, lon, LOCAL_STOP_RADIUS_M) }.getOrNull()
                }
                if (local != null) update { it.copy(transit = local) }

                fill(
                    DossierSlot.TRANSIT,
                    {
                        // Ширше за старі 40 м: тап по базовій мапі рідко лягає
                        // точно на вузол зупинки, а платформа станції тягнеться
                        // на десятки метрів.
                        sources.stopRoutes(lat, lon, STOP_RADIUS_M)
                    },
                ) { d, lines ->
                    val merged = mergeTransitLines(
                        local = d.transit?.lines.orEmpty(),
                        live = lines.map { it.asTransitLine() },
                    )
                    d.copy(
                        transitLines = lines,
                        transit = (d.transit ?: DossierTransit(emptyList())).copy(lines = merged),
                    )
                }
            }

            // Вміст контейнера / контейнер закладу — обидва з активу пристрою.
            launch {
                val lat = match?.lat ?: target.lat
                val lon = match?.lon ?: target.lon
                when {
                    isContainer -> fill(
                        DossierSlot.INSIDE,
                        { sources.venuesAround(lat, lon, INSIDE_RADIUS_M) },
                    ) { d, pois ->
                        d.copy(
                            inside = pois
                                .filter { it.id != match?.osmId && it.name != d.title }
                                .map(::insidePlaceOf),
                        )
                    }
                    // Поверх без назви контейнера — половина відповіді на
                    // «куди йти», тож контейнер шукається лише коли поверх є.
                    tags["level"] != null -> fill(
                        DossierSlot.INSIDE,
                        { sources.nearby(lat, lon, CONTAINER_RADIUS_M, CONTAINER_SELECTOR) },
                    ) { d, malls ->
                        d.copy(containerName = malls.firstOrNull()?.name)
                    }
                    else -> update { it.copy(pending = it.pending - DossierSlot.INSIDE) }
                }
            }

            // Маршрут до ВХОДУ, а не в центроїд: у Ретровіля 13 entrance-вузлів,
            // і двері — єдине «всередину», яке дані взагалі дозволяють.
            launch {
                val doors = runCatching {
                    sources.entrances(match?.lat ?: target.lat, match?.lon ?: target.lon, ENTRANCE_RADIUS_M)
                }.getOrDefault(emptyList())
                if (doors.isEmpty()) return@launch
                val nearest = doors.minByOrNull {
                    haversineMeters(target.lat, target.lon, it.first, it.second)
                } ?: return@launch
                update { it.copy(routePoint = nearest) }
            }

            launch {
                // Питання до контейнера — вміст, а не конкуренти: «схоже
                // поруч» для мола пропонувало інші моли.
                val selector = when {
                    isStop -> STATION_SERVICES_SELECTOR
                    isContainer -> null
                    else -> similarSelector(tags)
                }
                if (selector == null) {
                    update { it.copy(pending = it.pending - DossierSlot.SIMILAR) }
                    return@launch
                }
                fill(
                    DossierSlot.SIMILAR,
                    {
                        sources.nearby(
                            target.lat,
                            target.lon,
                            if (isStop) STATION_SERVICES_RADIUS_M else SIMILAR_RADIUS_M,
                            selector,
                        ).filter { it.id != match?.osmId }.take(SIMILAR_LIMIT)
                    },
                ) { d, list -> d.copy(similar = list) }
            }

            launch {
                val reference = withTimeoutOrNull(REFERENCE_TIMEOUT_MS) {
                    runCatching { resolveReference(entity, sources, nearestArticle.await()) }.getOrNull()
                }
                update { d ->
                    d.copy(
                        reference = reference,
                        pending = d.pending - DossierSlot.REFERENCE,
                        // Порожня довідка — не збій: у більшості місць статті
                        // просто нема. Збоєм вважаємо лише таймаут самої гілки.
                        failed = if (reference == null && !nearestArticle.isCompleted) {
                            d.failed + DossierSlot.REFERENCE
                        } else {
                            d.failed
                        },
                    )
                }
            }
        }
    }

    // Повне досьє — у кеш: друге відкриття цього місця буде миттєвим і вже
    // заповненим. Незавершене [DossierCache.put] відхилить сам.
    cache?.put(target, snapshot)
    // Свідомо БЕЗ awaitClose: `channelFlow` закривається сам, коли блок і всі
    // його діти завершились. `awaitClose` тут чекав би на скасування збирача
    // і `last()` ніколи б не повернувся.
}

/** Зіставлений під тапом елемент — байдуже, з газетира він чи з Overpass. */
internal data class DossierMatch(
    val source: DossierSourceKind,
    val lat: Double,
    val lon: Double,
    val osmId: Long?,
    val tags: Map<String, String>,
    val nameFallback: String? = null,
    val categoryHint: String? = null,
    val subcategoryHint: String? = null,
)

private fun DossierLocalHit.asMatch() = DossierMatch(
    source = source,
    lat = lat,
    lon = lon,
    osmId = osmId,
    tags = tags,
    nameFallback = name,
    categoryHint = categoryHint,
    subcategoryHint = subcategoryHint,
)

private fun PlaceDossierClient.PlaceDetail.asMatch() = DossierMatch(
    source = DossierSourceKind.OVERPASS,
    lat = lat,
    lon = lon,
    osmId = osmId,
    tags = tags,
)

/** Влити зіставлений елемент у знімок: назва, категорія, теги, години. */
private fun PlaceDossier.applyMatch(m: DossierMatch, target: DossierTarget): PlaceDossier {
    val categoryHint = m.categoryHint ?: target.categoryHint
    val subcategoryHint = m.subcategoryHint ?: target.subcategoryHint
    val entity = entityRefOf(
        tags = m.tags,
        nameHint = m.nameFallback ?: target.nameHint,
        categoryHint = categoryHint,
        subcategoryHint = subcategoryHint,
        osmId = m.osmId,
    )
    val rawHours = m.tags["opening_hours"]
    return copy(
        title = entity.name ?: title,
        kindLabel = kindLabelUk(m.tags, categoryHint, subcategoryHint),
        category = entity.category,
        entity = entity,
        tags = m.tags,
        address = tagAddress(m.tags) ?: address,
        rawHours = rawHours,
        openStatus = rawHours?.let { OpeningHours.statusNow(it) },
        identitySource = m.source,
    )
}

/**
 * P0 — довідка, зібрана НАВКОЛО СУТНОСТІ.
 *
 * Порядок доказів той самий, що й у [judgeWikiCandidate], тільки тут ми ще
 * й ходимо по них у мережу:
 *  1. Тег `wikipedia` — тягнемо саме ту статтю, яку назвала мапа.
 *  2. Тег `wikidata` — Q-ідентифікатор → назва статті → сама стаття.
 *  3. Геопошук — лише як КАНДИДАТ, і лише після вердикту.
 *
 * Якщо сутнісної статті нема, найближча НЕ підміняє її, а їде в картку з
 * вердиктом [WikiVerdict.NEARBY] і підписом «ПОРУЧ». Стаття про проспект
 * ніколи не стане довідкою про бібліотеку.
 */
internal suspend fun resolveReference(
    entity: PoiEntityRef,
    sources: DossierSources,
    nearest: DossierArticle?,
): DossierReference? {
    entity.wikipedia?.let { ref ->
        val article = runCatching { sources.articleByTitle(ref) }.getOrNull()
        if (article != null) {
            val verdict = judgeWikiCandidate(entity, article.asCandidate(fromEntityTag = true))
            if (verdict == WikiVerdict.ENTITY) return DossierReference(article, verdict)
        }
    }
    entity.wikidataId?.let { qid ->
        val ref = runCatching { sources.articleTitleForWikidata(qid) }.getOrNull()
        val article = ref?.let { runCatching { sources.articleByTitle(it) }.getOrNull() }
        if (article != null) {
            val verdict = judgeWikiCandidate(entity, article.asCandidate(fromEntityTag = true))
            if (verdict == WikiVerdict.ENTITY) return DossierReference(article, verdict)
        }
    }
    val candidate = nearest ?: return null
    val verdict = judgeWikiCandidate(entity, candidate.asCandidate(fromEntityTag = false))
    return if (verdict == WikiVerdict.NONE) null else DossierReference(candidate, verdict)
}

/**
 * Знімок T+0 — усе, що вже було в руках до тапу. Жодного IO: саме він
 * робить «пік» видимим за мілісекунди й дає «Маршрут», який нічого не чекає.
 */
internal fun instantDossier(target: DossierTarget): PlaceDossier {
    val entity = entityRefOf(
        tags = emptyMap(),
        nameHint = target.nameHint,
        categoryHint = target.categoryHint,
        subcategoryHint = target.subcategoryHint,
    )
    return PlaceDossier(
        target = target,
        title = entity.name ?: FALLBACK_TITLE,
        kindLabel = kindLabelUk(emptyMap(), target.categoryHint, target.subcategoryHint),
        category = entity.category,
        entity = entity,
        address = null,
        tags = emptyMap(),
        openStatus = null,
        rawHours = null,
        building = null,
        reference = null,
        transitLines = emptyList(),
        similar = emptyList(),
        pending = DossierSlot.values().toSet(),
    )
}

/**
 * Найкращий OSM-збіг під тапом: спершу той, чия назва збігається з хінтом,
 * інакше найближчий (джерело вже віддає їх за відстанню).
 */
internal fun bestMatch(
    details: List<PlaceDossierClient.PlaceDetail>,
    nameHint: String?,
): PlaceDossierClient.PlaceDetail? {
    val hint = nameHint?.let(::normalizeName)?.takeIf { it.isNotEmpty() }
    if (hint != null) {
        details.firstOrNull { detail ->
            val name = detail.tags["name"]?.let(::normalizeName) ?: return@firstOrNull false
            name == hint || name.contains(hint) || hint.contains(name)
        }?.let { return it }
    }
    return details.firstOrNull()
}

/** Назва до першого справжнього імені — не заглушка даних, а чесне «точка». */
internal val FALLBACK_TITLE: String
    get() = MapText.s(R.string.mapl_c_fallback_title)

private val STATION_NEARBY_TITLE: String
    get() = MapText.s(R.string.mapl_c_station_nearby_title)

/** Кожне джерело має свій таймаут — повільне ніколи не греблює картку. */
private const val SOURCE_TIMEOUT_MS = 8_000L

/**
 * Overpass ходить за деталями ОСТАННІМ і за коротким повідцем: заміряні
 * 7 265 мс на 408 байт — це не «дочекатись», це «застигла картка». Локальні
 * джерела вже дали, що могли; сюди йдемо лише за тегами і не чекаємо довго.
 */
private const val OVERPASS_DETAIL_TIMEOUT_MS = 4_000L

/** Локальні джерела мусять бути миттєвими; повільна БД теж не тримає картку. */
private const val LOCAL_TIMEOUT_MS = 900L

/** Довідка ходить у мережу до трьох разів (тег → Q → стаття), тож більше. */
private const val REFERENCE_TIMEOUT_MS = 12_000L

private const val STOP_RADIUS_M = 140

/**
 * Тісніше за мережевий радіус: збіг іде по вузлах зупинок активу, а не по
 * платформі. Ширше — і зупинки різних вулиць злипаються в один перелік.
 */
private const val LOCAL_STOP_RADIUS_M = 60

/**
 * Футпринт Ретровіля ~180×250 м, полігона в паку нема — тож join іде
 * відстанню від центроїда і чесно про це підписаний.
 */
private const val INSIDE_RADIUS_M = 170
private const val CONTAINER_RADIUS_M = 220
private const val CONTAINER_SELECTOR = "shop=mall"
private const val ENTRANCE_RADIUS_M = 120
private const val SIMILAR_RADIUS_M = 1_200
private const val STATION_SERVICES_RADIUS_M = 300
private const val SIMILAR_LIMIT = 6
