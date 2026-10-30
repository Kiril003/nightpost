package local.phantom.companion.feature.stream.ui.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.HeatmapLayer
import org.maplibre.android.style.layers.HillshadeLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer

/**
 * **Підлога підписів: жодна наша площина й жодна лінія не лягає поверх назв.**
 *
 * Кадр власника 01.09 02:18: «лінії сині спавняться просто ніби поверх де
 * хочуть» — наші лінії світились поверх вулиць, будівель і ПІДПИСІВ, мапу не
 * було як читати. Причина не в жодному окремому шарі: реєстр ставить кожен
 * внесок через `addLayer`, тобто в самий кінець стилю — над підписами, які
 * стиль поклав останніми. Драбина [local.phantom.companion.feature.stream.ui.map.atlas.MapTier]
 * вирішує, хто з наших вище кого, і мовчить про єдине чуже, що важить оку:
 * назви місць і вулиць.
 *
 * Правило картографії, не смаку: **площини й лінії — під назвами, знаки — над.**
 * Маршрут, транзит, зона тривоги, пакети, кільце сумніву — це ґрунт, крізь
 * який людина читає; вулиця мусить лишитись підписаною поверх нього. Пін,
 * підпис місця, стрілка маневру, оператор — знаки, і вони над усім.
 *
 * Тому підлога одна на реєстр і кладеться в ОДНОМУ місці (обгортка `add` у
 * MapGroundContributions), а не у двадцяти встановлювачах: двадцять місць —
 * двадцять нагод забути двадцять перше. Ідемпотентно: що вже під підлогою,
 * не рухається; що додано лениво з проходу оновлення — опускається так само.
 */

/** Що движок уміє намалювати. Підлога дивиться лише на це, ніколи на id. */
internal enum class LayerKind { AREA_OR_LINE, MARK, OTHER }

/** Один шар стилю очима підлоги: id, рід, і чи несе він текст. */
internal data class LayerSlot(val id: String, val kind: LayerKind, val labelled: Boolean = false)

/**
 * Якір підлоги — перший шар ґрунту, що пише текст. Не «перший символ»:
 * у чужому стилі символом буває стрілка одностороннього руху посеред доріг,
 * і підлога під нею втопила б маршрут під мостами.
 */
internal fun labelFloorAnchor(basemap: List<LayerSlot>): String? =
    basemap.firstOrNull { it.kind == LayerKind.MARK && it.labelled }?.id

/**
 * Кого з наших переносити під якір, у порядку перенесення. Порядок = порядок
 * у стилі: послідовний `addLayerBelow(anchor)` його зберігає, бо кожен
 * наступний стає між попереднім і якорем.
 */
internal fun planLabelFloor(stack: List<LayerSlot>, anchorId: String, basemapIds: Set<String>): List<String> {
    val anchorIdx = stack.indexOfFirst { it.id == anchorId }
    if (anchorIdx < 0) return emptyList()
    return stack.withIndex()
        .filter { (i, slot) -> i > anchorIdx && slot.id !in basemapIds && slot.kind == LayerKind.AREA_OR_LINE }
        .map { it.value.id }
}

/** Одна підлога на один завантажений стиль — реєстр і живе рівно стільки. */
internal class LabelFloor {
    private var captured = false
    private var anchorId: String? = null
    private var basemapIds: Set<String> = emptySet()

    /**
     * Викликати ДО першого встановлювача: у ту мить у стилі лежать ЛИШЕ чужі
     * шари, тож усе, що є, — ґрунт, а перший підпис серед них — якір.
     */
    fun capture(style: Style) {
        if (captured) return
        captured = true
        val basemap = style.layers.map { it.slot() }
        basemapIds = basemap.mapTo(HashSet()) { it.id }
        anchorId = labelFloorAnchor(basemap)
    }

    /** Після кожного встановлювача: усе наше площинне й лінійне над якорем — під нього. */
    fun sink(style: Style) {
        val anchor = anchorId ?: return
        val stack = style.layers
        val plan = planLabelFloor(stack.map { it.slot() }, anchor, basemapIds)
        if (plan.isEmpty()) return
        val byId = stack.associateBy { it.id }
        for (id in plan) {
            val layer = byId[id] ?: continue
            // Одна невдача не має права лишити мапу без шару: якщо зняли, а
            // покласти під якір не вдалось — повертаємо туди, де він і був
            // без підлоги (зверху). Гірше за «поверх підписів» лише «зник».
            runCatching {
                style.removeLayer(layer)
                style.addLayerBelow(layer, anchor)
            }.onFailure {
                if (style.getLayer(id) == null) runCatching { style.addLayer(layer) }
            }
        }
    }
}

private fun Layer.slot(): LayerSlot = when (this) {
    is SymbolLayer -> LayerSlot(id, LayerKind.MARK, labelled = textField.let { it.expression != null || it.value != null })
    is CircleLayer -> LayerSlot(id, LayerKind.MARK)
    is FillLayer, is LineLayer, is FillExtrusionLayer, is RasterLayer, is HeatmapLayer, is HillshadeLayer ->
        LayerSlot(id, LayerKind.AREA_OR_LINE)
    // Тло й невідомі майбутні роди не чіпаємо: не знаємо — не рухаємо.
    else -> LayerSlot(id, LayerKind.OTHER)
}
