package local.phantom.companion.feature.stream.ui.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.cos
import kotlin.math.sin
import local.phantom.companion.core.geo.sky.SkyPhase
import local.phantom.companion.core.geo.sky.SolarAlmanac

/**
 * Solar-driven map atmosphere. One physical quantity — the sun's elevation for a
 * (lat, lon, instant) — carries all three axes at once: hour of day (hour angle),
 * season (declination from the date) and place (latitude). Near the equator the
 * sun rides high all day; a northern winter barely lifts it. The map reads this
 * instead of a clock bucket, so dusk is a slide, not an 18:00 cliff to black.
 *
 * Ефемериду цей файл більше НЕ рахує: висоту, азимут і фазу дає
 * [SolarAlmanac.sky]. Тут лишається тільки градієнт — те, чого нема більше
 * ніде. Свою копію формул (наближення NOAA) знято в ATLAS-1: вона розходилася
 * з альманахом до 0,46°, і мапа могла назвати ніччю іншу хвилину, ніж показник
 * придатного світла на тій самій панелі.
 */
data class SkyState(
    val elevationDeg: Double,
    /** Clockwise from true north. 180° = due south, where a northern noon sun sits. */
    val azimuthDeg: Double,
    /** Яку з трьох земель просити в основи; див. таблицю порогів у [SkyPhase]. */
    val phase: SkyPhase,
    /** Full-screen tint washed over the whole basemap; alpha 0 = clear midday. */
    val scrim: Color,
    /** Morning limb (before solar noon) — dawn tints cooler than dusk. */
    val morning: Boolean,
    /**
     * Unit vector **toward** the sun: x east, y up, z south. Below the horizon
     * y goes negative — the caller decides what night means.
     *
     * Nothing reads it today: its consumer was the Filament city, deleted with
     * the rest of the dead render path. Kept because it is derived from the
     * same solar maths as [scrim] above, not carried separately.
     */
    val sunX: Float,
    val sunY: Float,
    val sunZ: Float,
) {
    /** Base map wants the dark style below the seam (the scrim masks the swap). */
    val dark: Boolean get() = phase.dark
}

private val CLEAR = Color(0x00FFCB8A)
private val WARM = Color(0x1AFFCB8A)      // sun still up, first warmth
private val GOLDEN = Color(0x30FF9A4E)    // golden hour at the horizon
private val DUSK = Color(0x47E0703C)      // civil twilight, ember (evening)
private val DAWN = Color(0x47E68A86)      // civil twilight, rose (morning)
private val NAUTICAL = Color(0x6E3B3E78)  // indigo, sun well down
private val NIGHT = Color(0x850C1228)     // deep blue-black, not flat black

fun skyFor(latDeg: Double, lonDeg: Double, epochMs: Long): SkyState {
    val fix = SolarAlmanac.sky(epochMs, latDeg, lonDeg)
    val elevation = Math.toRadians(fix.elevationDeg)
    val azimuth = Math.toRadians(fix.azimuthDeg)
    // Multiplied out of the two angles, never divided by cos(elevation): the
    // components are unit length by construction, overhead sun included.
    val up = sin(elevation)
    val flat = cos(elevation)
    return SkyState(
        elevationDeg = fix.elevationDeg,
        azimuthDeg = fix.azimuthDeg,
        phase = fix.phase,
        scrim = scrimFor(fix.elevationDeg, fix.morning),
        morning = fix.morning,
        sunX = (flat * sin(azimuth)).toFloat(),
        sunY = up.toFloat(),
        sunZ = (-flat * cos(azimuth)).toFloat(),
    )
}

private fun scrimFor(e: Double, morning: Boolean): Color {
    val twilight = if (morning) DAWN else DUSK
    val stops = listOf(
        10.0 to CLEAR,
        4.0 to WARM,
        0.0 to GOLDEN,
        -4.0 to twilight,
        -9.0 to NAUTICAL,
        -14.0 to NIGHT,
    )
    if (e >= stops.first().first) return stops.first().second
    if (e <= stops.last().first) return stops.last().second
    for (i in 0 until stops.size - 1) {
        val (hiE, hiC) = stops[i]
        val (loE, loC) = stops[i + 1]
        if (e in loE..hiE) return lerp(hiC, loC, ((hiE - e) / (hiE - loE)).toFloat())
    }
    return NIGHT
}
