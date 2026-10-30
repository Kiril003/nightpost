package local.phantom.companion.feature.stream.ui.map.dossier

import local.phantom.companion.core.data.poi.TilePlaces

/**
 * ЛОКАЛЬНЕ ПЕРШЕ.
 *
 * Публічний `overpass-api.de` віддав 408 байт за 7 265 мс (замір на пристрої).
 * Сім секунд — це не «повільна мережа», це вирок картці: усе, що встигає
 * побачити оператор, — застиглий прямокутник. Тому Overpass більше не
 * ПЕРШИЙ, а ОСТАННІЙ, і йде він за коротким таймаутом, поки картка вже
 * показує те, що знає без жодного байта.
 *
 * Порядок оголошення [DossierSourceKind] і є порядком довіри.
 */
internal enum class DossierSourceKind {
    /** POI, які мапа вже намалювала. Повні OSM-теги, нуль запитів. */
    VIEWPORT,

    /** Газетир: усе, що PHANTOM колись бачив. Без тегів, зате офлайн. */
    GAZETTEER,

    /** Дорожня сітка мапчера — назва вулиці під пальцем. */
    ROAD_MESH,

    /** Мережа. Остання інстанція. */
    OVERPASS,
}

/** Один локальний кандидат під тапом. */
internal data class DossierLocalHit(
    val source: DossierSourceKind,
    val name: String?,
    val lat: Double,
    val lon: Double,
    val distanceM: Double,
    val osmId: Long? = null,
    val categoryHint: String? = null,
    val subcategoryHint: String? = null,
    val tags: Map<String, String> = emptyMap(),
) {
    /** Теги на руках — Overpass уже нічого не додасть. Плитка дає лише назву й клас, тож вона не рахується. */
    val isAuthoritative: Boolean get() = tags.isNotEmpty() && !TilePlaces.isTile(tags)
}

/**
 * Скільки метрів навколо тапу джерело ще вважається відповіддю САМЕ на цей
 * тап. Дорожня сітка вужча за решту навмисно: вулиця під пальцем — це вулиця,
 * на якій стоїш, а не та, що за рогом.
 */
internal fun sourceReachM(source: DossierSourceKind): Double = when (source) {
    DossierSourceKind.VIEWPORT -> 45.0
    DossierSourceKind.GAZETTEER -> 45.0
    DossierSourceKind.ROAD_MESH -> 25.0
    DossierSourceKind.OVERPASS -> 45.0
}

/**
 * Переможець серед локальних кандидатів: спершу за джерелом, і лише потім за
 * відстанню. Іменований POI за 40 м бʼє вулицю за 3 м — палець цілився в
 * значок, а не в асфальт під ним. Безіменний кандидат вибуває: «Точка на
 * мапі» краще за вигадану назву.
 */
internal fun pickLocalHit(hits: List<DossierLocalHit>): DossierLocalHit? {
    val live = hits.filter { !it.name.isNullOrBlank() && it.distanceM <= sourceReachM(it.source) }
    val winner = live.minWithOrNull(compareBy({ it.source.ordinal }, { it.distanceM })) ?: return null
    if (winner.isAuthoritative) return winner
    // Мапа малює плитку, а години й телефон того самого місця лежать у паку.
    val twin = live
        .filter {
            it !== winner && it.isAuthoritative && it.name.equals(winner.name, ignoreCase = true) &&
                haversineMeters(winner.lat, winner.lon, it.lat, it.lon) <= TilePlaces.SAME_PLACE_M
        }
        .minByOrNull { it.distanceM } ?: return winner
    return winner.copy(tags = twin.tags, osmId = twin.osmId ?: winner.osmId)
}

/**
 * ЧЕСНИЙ ПОРОЖНІЙ СТАН.
 *
 * «Деталі місця не завантажились» — це заява про МЕРЕЖУ, і поки вона
 * друкувалась і над мовчазним Overpass, і над обʼєктом, про який у світі
 * просто нема даних, картка брехала в половині випадків. Це різні речі й
 * різні дії оператора: у першому випадку варто спробувати ще, у другому —
 * не варто ніколи.
 */
internal enum class DossierDetailVerdict {
    /** Ще нічого не знайдено — навіть локально. Скелетон. */
    PENDING,

    /** Локальне джерело відповіло, мережа добирає глибше. */
    DEEPENING,

    /** Теги на руках. */
    PRESENT,

    /** Джерела відповіли — і в них про цей обʼєкт нічого нема. */
    NO_DATA,

    /** Джерело не відповіло (таймаут/виняток). Єдиний випадок, де є сенс у «ще раз». */
    LOOKUP_FAILED,
}

internal fun detailVerdict(dossier: PlaceDossier): DossierDetailVerdict = when {
    dossier.isPending(DossierSlot.DETAIL) -> DossierDetailVerdict.PENDING
    dossier.isDeepening(DossierSlot.DETAIL) -> DossierDetailVerdict.DEEPENING
    dossier.tags.isNotEmpty() -> DossierDetailVerdict.PRESENT
    dossier.hasFailed(DossierSlot.DETAIL) -> DossierDetailVerdict.LOOKUP_FAILED
    else -> DossierDetailVerdict.NO_DATA
}
