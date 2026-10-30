package local.phantom.companion.feature.stream.ui.map

import kotlin.math.cos
import kotlin.math.pow

/**
 * Куди ставити камеру, коли знизу зараз відкриється аркуш.
 *
 * Результат пошуку летів у ЦЕНТР екрана, а аркуш досʼє накривав нижню
 * половину — булавка лишалась під його краєм (кадр A52 06.09). Центр камери
 * зсувається на південь на частку висоти вікна, і точка стає в середину тієї
 * половини, яку видно.
 */
internal object SheetCameraLift {
    /** Частка висоти вікна, на яку точка піднімається над центром. */
    const val SHARE = 0.25

    private const val EQUATOR_M = 40_075_016.686
    private const val TILE_PX = 512.0
    private const val DEG_M = 111_320.0

    /** Метрів на один dp екрана на цій широті й зумі (тайл 512, зум MapLibre). */
    fun metersPerDp(lat: Double, zoom: Double): Double =
        EQUATOR_M * cos(Math.toRadians(lat)) / (TILE_PX * 2.0.pow(zoom))

    fun liftedLatitude(lat: Double, zoom: Double, viewportHeightDp: Double, share: Double = SHARE): Double =
        lat - viewportHeightDp * share * metersPerDp(lat, zoom) / DEG_M
}
