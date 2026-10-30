package local.phantom.companion.feature.stream.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import local.phantom.companion.feature.stream.domain.OfflineCoverage
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

// design-critic §S9 — the loudest of the five honesty states: a cool-grey
// diagonal hatch over ground this device has never saved, drawn only while
// offline (see classifyOfflineTap — while online, live tiles answer for
// themselves and nothing here fires). Deliberately NOT the same hue family
// as «відмовлено» (warm amber/brown, per ux-map-design.md §3.1) — "haven't
// looked yet" and "looked, withholding on purpose" must never read as the
// same colour at a glance.
//
// design-critic §performance — at national scale the honest default is
// "not downloaded", so this has to be cheap: one small tiled bitmap pattern
// (GPU-tiled, not per-feature translucency) over a handful of rectangles
// from OfflineCoverage.uncoveredRects, not a ghost mass painted per feature.

internal const val NOT_DOWNLOADED_SOURCE = "phantom-not-downloaded-source"
internal const val NOT_DOWNLOADED_FILL = "phantom-not-downloaded-fill"
internal const val NOT_DOWNLOADED_PATTERN = "phantom-not-downloaded-pattern"

/**
 * Cool grey — the "we haven't looked" family, never the warm "refused" one.
 * The alpha lives IN the colour, so the contrast MapSymbolContrastTest
 * measures is the contrast the operator gets: the shipped #7C8798 under a
 * separate 0.4 fill-opacity would have read 1.79:1 on the dark ground had it
 * ever been wired up at all.
 */
internal fun notDownloadedHatchColor(isDark: Boolean): Int = MapSymbolPalette.resolve(isDark).hatch

private const val HATCH_TILE_DP = 28

/** Stroke as a FRACTION of the tile: a raw-px stroke inside a dp-sized tile
 *  draws a different pattern on every density class. */
private const val HATCH_STROKE_FRACTION = 4f / 28f

internal fun installNotDownloadedHatch(style: Style, context: Context, isDark: Boolean = true) {
    if (style.getImage(NOT_DOWNLOADED_PATTERN) == null) {
        val density = context.resources.displayMetrics.density
        style.addImage(
            NOT_DOWNLOADED_PATTERN,
            diagonalHatchBitmap(notDownloadedHatchColor(isDark), (HATCH_TILE_DP * density).toInt()),
        )
    }
    if (style.getSource(NOT_DOWNLOADED_SOURCE) == null) {
        style.addSource(GeoJsonSource(NOT_DOWNLOADED_SOURCE, EMPTY_FC))
    }
    if (style.getLayer(NOT_DOWNLOADED_FILL) == null) {
        val layer = FillLayer(NOT_DOWNLOADED_FILL, NOT_DOWNLOADED_SOURCE).withProperties(
            PropertyFactory.fillPattern(NOT_DOWNLOADED_PATTERN),
        )
        // Describes the GROUND, same as hillshade — sits just above the
        // basemap's own background, under every operator-facing overlay.
        val base = style.layers.firstOrNull()
        if (base != null) style.addLayerAbove(layer, base.id) else style.addLayer(layer)
    }
}

internal fun updateNotDownloadedHatch(style: Style, geoJson: String) {
    style.getSourceAs<GeoJsonSource>(NOT_DOWNLOADED_SOURCE)?.setGeoJson(geoJson)
}

/**
 * The viewport rectangle with every same-style completed pack cut out, as
 * GeoJSON rectangles (see [OfflineCoverage.uncoveredRects] for why
 * rectangles and not a polygon-with-holes). Empty FeatureCollection when
 * the viewport is fully covered — an empty source paints nothing, which is
 * exactly correct: fully covered ground gets no hatch.
 */
internal fun notDownloadedHatchGeoJson(
    view: OfflineCoverage.Area,
    coveredSameStyle: List<OfflineCoverage.Area>,
): String {
    val holes = OfflineCoverage.uncoveredRects(view, coveredSameStyle)
    if (holes.isEmpty()) return EMPTY_FC
    val features = holes.joinToString(",") { r ->
        val ring = listOf(
            r.westLon to r.southLat,
            r.eastLon to r.southLat,
            r.eastLon to r.northLat,
            r.westLon to r.northLat,
            r.westLon to r.southLat,
        ).joinToString(",") { (lon, lat) -> "[$lon,$lat]" }
        "{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[$ring]]}}"
    }
    return "{\"type\":\"FeatureCollection\",\"features\":[$features]}"
}

/**
 * A small ARGB tile with three parallel 45° strokes (offsets -n/0/+n so the
 * diagonal reconnects across the tile edge instead of leaving a seam where
 * the pattern repeats) — GPU-tiled by [PropertyFactory.fillPattern], not
 * redrawn per pixel.
 */
internal fun diagonalHatchBitmap(colorArgb: Int, tilePx: Int): Bitmap {
    val bmp = Bitmap.createBitmap(tilePx.coerceAtLeast(1), tilePx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val paint = Paint().apply {
        color = colorArgb
        strokeWidth = tilePx * HATCH_STROKE_FRACTION
        isAntiAlias = false
        style = Paint.Style.STROKE
    }
    val n = tilePx.toFloat()
    for (offset in intArrayOf(-tilePx, 0, tilePx)) {
        canvas.drawLine(0f, offset.toFloat(), n, offset + n, paint)
    }
    return bmp
}
