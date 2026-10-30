package local.phantom.companion.core.data.transit

import local.phantom.companion.core.net.geosrc.SourceUnavailable

/**
 * Ярус транспортних даних, що відповідає за конкретну точку.
 *
 * Порядок оголошення — це і є порядок переваги: перший ярус, якому є що
 * сказати тут, забирає відповідь.
 */
enum class TransitPlane {
    /**
     * Перевірений GTFS-RT фід із [GtfsFeedRegistry]. ЄДИНИЙ ярус, якому
     * дозволено рухомі мітки на склі (закон 1 D8, польовий вердикт 13).
     */
    LIVE_FEED,

    /**
     * Опублікований розклад перевізника, спечений у пак (Київ —
     * «Київпастранс»). Хвилини справжні й названі конкретно; позицій немає
     * і бути не може, тож рухомих міток цей ярус НЕ дає.
     */
    PUBLISHED_SCHEDULE,

    /**
     * Змодельована мережа за інтервалами. Чесно «модель»: живить хаб і
     * досьє, на склі — лише з явним бейджем моделі, ніколи як «live», і
     * ніколи конкретною хвилиною на годиннику.
     */
    SIMULATED_MODEL,

    /**
     * Статичний розклад Transitland: зупинки, лінії, табло відправлень.
     * Позицій транспорту тут немає й бути не може — рухомих міток не дає.
     */
    STATIC_SCHEDULE,

    /** Жодного налаштованого джерела. */
    NONE,
}

/**
 * Що ми чесно можемо сказати про транспорт у цій точці.
 *
 * [absence] заповнене лише для [TransitPlane.NONE] і завжди означає «джерело
 * недоступне», а не «тут транспорту немає» — закон 2 D8. Порожній список від
 * живого джерела — це `GeoResult.Ok(emptyList())`, зовсім інший стан.
 */
data class TransitCoverage(
    val plane: TransitPlane,
    val liveFeed: GtfsCityFeed? = null,
    val absence: SourceUnavailable? = null,
) {
    /** Чи цьому ярусу дозволено рухомі мітки на склі. */
    val feedsMovingVehicles: Boolean get() = plane == TransitPlane.LIVE_FEED

    /** Чи цей ярус несе зупинки, лінії й табло відправлень. */
    val feedsSchedules: Boolean
        get() = plane == TransitPlane.PUBLISHED_SCHEDULE ||
            plane == TransitPlane.SIMULATED_MODEL ||
            plane == TransitPlane.STATIC_SCHEDULE

    /** Чи дані цього ярусу мають нести бейдж моделі. */
    val isSimulated: Boolean get() = plane == TransitPlane.SIMULATED_MODEL

    /** Звідки беруться хвилини цього ярусу — те, що підписує табло. */
    val timesOrigin: TransitTimesOrigin
        get() = when (plane) {
            TransitPlane.LIVE_FEED -> TransitTimesOrigin.MEASURED
            TransitPlane.PUBLISHED_SCHEDULE, TransitPlane.STATIC_SCHEDULE -> TransitTimesOrigin.SCHEDULED
            TransitPlane.SIMULATED_MODEL, TransitPlane.NONE -> TransitTimesOrigin.MODELLED
        }
}

/**
 * Драбина покриття транспорту — одне місце, де вирішується, чиї дані тут
 * головні.
 *
 * До ATLAS-3 відповідь була бінарна: київська модель або порожнеча, і за
 * межами модельованих меж мапа мовчала так само, як мовчить мертве джерело.
 * Тепер порожнеча має причину, а решта світу має розклад.
 */
object TransitCoverageResolver {

    /**
     * @param hasLiveClient чи викликач справді підняв GTFS-RT клієнта. Реєстр
     *   може знати фід для цієї точки, але без клієнта заявляти «live» не
     *   можна — це був би бейдж без даних.
     * @param transitlandAvailable чи налаштований ключ Transitland.
     */
    fun resolve(
        lat: Double,
        lon: Double,
        simulatedBounds: FeedBounds?,
        hasLiveClient: Boolean,
        transitlandAvailable: Boolean,
        /** Межі опублікованого розкладу пака. Він бʼє модель і не бʼє живий фід. */
        scheduleBounds: FeedBounds? = null,
    ): TransitCoverage {
        if (!lat.isFinite() || !lon.isFinite()) {
            return TransitCoverage(
                TransitPlane.NONE,
                absence = SourceUnavailable.RequestRejected("непридатна точка"),
            )
        }

        val live = GtfsFeedRegistry.feedFor(lat, lon)
        if (live != null && hasLiveClient) {
            return TransitCoverage(TransitPlane.LIVE_FEED, liveFeed = live)
        }
        // Без запасу по краях, на відміну від моделі: розклад знає рівно свої
        // зупинки, і за двадцять вісім кілометрів від крайньої він не знає нічого.
        if (scheduleBounds?.contains(lat, lon) == true) {
            return TransitCoverage(TransitPlane.PUBLISHED_SCHEDULE)
        }
        if (simulatedBounds?.contains(lat, lon, TransitRepository.COVERAGE_PAD_DEG) == true) {
            return TransitCoverage(TransitPlane.SIMULATED_MODEL)
        }
        if (transitlandAvailable) {
            return TransitCoverage(TransitPlane.STATIC_SCHEDULE)
        }
        return TransitCoverage(TransitPlane.NONE, absence = SourceUnavailable.MissingApiKey)
    }
}
