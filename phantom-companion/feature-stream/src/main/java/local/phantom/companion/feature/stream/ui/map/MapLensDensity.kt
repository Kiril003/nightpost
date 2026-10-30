package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.feature.stream.ui.map.atlas.DensityBudget
import local.phantom.companion.feature.stream.ui.map.atlas.LensFrame
import local.phantom.companion.feature.stream.ui.map.atlas.SceneMode
import local.phantom.companion.feature.stream.ui.map.icons.PinTier
import local.phantom.companion.feature.stream.ui.map.icons.PoiGlyphTable
import local.phantom.companion.feature.stream.ui.map.icons.marks.MarkKind
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory

/**
 * ATLAS-2 — що саме з тисячі місць має право на скло в кожному режимі.
 *
 * Один рядок на режим, жодної гілки. Рядок каже дві речі й ніколи третьої:
 * кого В ЗАГАЛІ пускають ([PlaceLensRow.admits]/[PlaceLensRow.admitKeys]) і
 * кого підіймають наперед черги на проріджування ([PlaceLensRow.promotes]).
 * Скільки саме лишиться — це вже бюджет Лінзи, і він приходить кадром.
 *
 * Ярус піна ([PinTier]) виводиться з [PoiGlyphTable], а не переписується тут:
 * другий список «що важливе» неминуче розійшовся б із першим, і розійшовся б
 * тихо — саме на аптеці, яку хтось додав в одну множину й забув у другій.
 */
internal data class PlaceLensRow(
    val scene: SceneMode,
    /** Яруси, яким режим відчиняє двері. */
    val admits: Set<PinTier> = ALL_TIERS,
    /** Іменні винятки понад [admits] — «крім усього критичного, ще й пальне». */
    val admitKeys: Set<String> = emptySet(),
    /** Кого цей режим ставить у голову черги, коли місця на всіх нема. */
    val promotes: Set<String> = emptySet(),
    /** Нижче цього зуму підняття не діє: воно про масштаб, а не про клас. */
    val promoteMinZoom: Double = 0.0,
) {
    companion object {
        val ALL_TIERS: Set<PinTier> = PinTier.entries.toSet()
    }
}

internal object PlaceLens {

    /** Що потрібно за кермом і чого там не потрібно нічого іншого. */
    private val DRIVING = setOf(
        "fuel", "charging_station", "car_repair", "tyres", "parking", "toll_booth",
        "checkpoint", "speed_camera", "restaurant", "fast_food", "cafe", "toilets",
    )

    /** Меблі пішохода: ними користуються з відстані витягнутої руки. */
    private val ON_FOOT = setOf(
        "entrance", "elevator", "escalator", "steps", "stairs", "subway_entrance",
        "drinking_water", "toilets",
    )

    private val RIDING = setOf(
        "bus_stop", "bus_station", "tram_stop", "station", "halt", "platform",
        "subway", "subway_entrance", "ferry_terminal", "taxi",
    )

    /**
     * Той самий поріг, з якого движок показує підпис місця: підняти сходи вище
     * за аптеку варто лише там, де їх видно як сходи, а не як крапку.
     */
    const val ON_FOOT_MIN_ZOOM = 16.0

    /**
     * Таблиця. Порожній рядок означає «як завжди», і саме тому CALM з EXPLORE
     * тут немає: вони і є «як завжди», а рядок-заглушка лише брехав би, що
     * хтось про них ухвалив окреме рішення.
     */
    private val ROWS: Map<SceneMode, PlaceLensRow> = listOf(
        // Критичний ярус пускають усюди, включно з кермом: закон «у тісноті
        // аптека витісняє крамницю» старший за цю хвилю (POIOverlay.sortKey), і
        // режим, який єдиний зі всіх ВИДАЛЯЄ лікарню, суперечив би йому мовчки.
        PlaceLensRow(
            scene = SceneMode.DRIVE,
            admits = setOf(PinTier.CRITICAL),
            admitKeys = DRIVING,
            promotes = setOf("fuel", "charging_station"),
        ),
        PlaceLensRow(
            scene = SceneMode.WALK,
            promotes = ON_FOOT,
            promoteMinZoom = ON_FOOT_MIN_ZOOM,
        ),
        PlaceLensRow(scene = SceneMode.TRANSIT, promotes = RIDING),
        // Стійкість: вода, ліки, укриття, лікарня — і це рівно критичний ярус.
        PlaceLensRow(scene = SceneMode.THREAT, admits = setOf(PinTier.CRITICAL)),
    ).associateBy { it.scene }

    private fun keysOf(poi: PoiEntity): List<String> = listOfNotNull(
        PoiGlyphTable.pinSubcategory(poi)?.takeIf { it.isNotBlank() },
        poi.category.takeIf { it.isNotBlank() },
    )

    fun admits(scene: SceneMode, poi: PoiEntity): Boolean {
        val row = ROWS[scene] ?: return true
        val tier = PoiGlyphTable.resolve(poi).tier
        return tier in row.admits || keysOf(poi).any { it in row.admitKeys }
    }

    /**
     * Черга на виживання: менше число лишається. База — ярус піна, тобто те
     * саме число, яким уже сортує сам шар; режим має право підняти свій клас
     * до самого верху, але ніколи опустити чужий.
     */
    fun rank(scene: SceneMode, poi: PoiEntity, zoom: Double): Int {
        val base = when (PoiGlyphTable.resolve(poi).tier) {
            PinTier.CRITICAL -> 0
            PinTier.SUSTAIN -> 1
            PinTier.ORDINARY -> 2
        }
        val row = ROWS[scene] ?: return base
        if (zoom < row.promoteMinZoom) return base
        return if (keysOf(poi).any { it in row.promotes }) 0 else base
    }

    /**
     * Місця, які цей момент справді пускає на скло. Порядок джерела зберігається
     * — він уже проранжований `PoiDensity`, і перетасувати його означало б
     * зіпсувати вибір носія підпису серед однойменних близнюків.
     */
    fun select(
        pois: List<PoiEntity>,
        scene: SceneMode,
        zoom: Double,
        budget: Int = LensFrame.UNLIMITED,
    ): List<PoiEntity> {
        val admitted = pois.filter { POIOverlay.drawableOnGround(it) && admits(scene, it) }
        return DensityBudget.trim(admitted, budget) { rank(scene, it, zoom) }
    }
}

/**
 * Те саме питання до знаків подій. Бюджету в них немає свідомо (`DensityClass.EVENT`:
 * воротар доречності вже проріджує їх ДО цього місця), тож режим тут вирішує
 * лише одне — доречність КЛАСУ.
 */
internal object EventLens {

    /** Дворові ворота — не факт про дорогу; пункт пропуску і камера — факт. */
    private val ROAD_FACTS = setOf(
        MarkKind.CHECKPOINT, MarkKind.CRASH,
        MarkKind.CAMERA, MarkKind.DANGER, MarkKind.SHELTER,
    )

    fun admits(scene: SceneMode, kind: MarkKind): Boolean = when (scene) {
        SceneMode.DRIVE -> kind in ROAD_FACTS
        else -> true
    }

    /** За кермом зі світу лишаються лише ті місця, що важать із дороги. */
    fun admitsWorld(scene: SceneMode, kind: local.phantom.companion.feature.stream.ui.map.world.WorldKind): Boolean =
        scene != SceneMode.DRIVE || kind in DRIVE_WORLD

    private val DRIVE_WORLD = setOf(
        local.phantom.companion.feature.stream.ui.map.world.WorldKind.FUEL,
        local.phantom.companion.feature.stream.ui.map.world.WorldKind.HOSPITAL,
        local.phantom.companion.feature.stream.ui.map.world.WorldKind.SHELTER,
    )

    /** Під сиреною укриття стоїть попереду всього, чим би екран не був зайнятий. */
    fun rank(scene: SceneMode, kind: MarkKind): Int = when {
        scene == SceneMode.THREAT && kind == MarkKind.SHELTER -> 0
        else -> 1
    }
}

/** Стежки Обсидіану. Чужий стиль такого шару не має — і тоді нижче no-op. */
internal const val OBSIDIAN_PATH_LAYER = "obsidian-road-path"

/**
 * Голос стежок, і чому це властивість, а не перепікання стилю.
 *
 * Гонтлет §2 демотував стежки ГЛОБАЛЬНО (0.9× рубця, альфа 0.40@z14→0.82@z18.5):
 * на z15 вони конкурували з вулицями й ламали ієрархію. Пішоходу ж стежка — це
 * і є маршрут. Тому режим піднімає рівно один запис на одному шарі; сам асет
 * лишається байт-у-байт тим самим, а вихід із режиму повертає ту саму рампу.
 * Перепікання стилю тут коштувало б перезавантаження всієї мапи на кожен крок
 * із тротуару на дорогу.
 */
internal fun applyFootpathVoice(style: Style, scene: SceneMode) {
    val layer = style.getLayer(OBSIDIAN_PATH_LAYER) as? LineLayer ?: return
    layer.setProperties(PropertyFactory.lineOpacity(pathAlphaRamp(scene)))
}

/** Окремо від запису — саме цю форму читає граматичний сторож виразів. */
internal fun pathAlphaRamp(scene: SceneMode): Expression {
    val stops = if (scene == SceneMode.WALK) PATH_ALPHA_WALK else PATH_ALPHA_BASE
    return Expression.interpolate(
        Expression.linear(),
        Expression.zoom(),
        *stops.map { (z, a) -> Expression.stop(z, a) }.toTypedArray(),
    )
}

/** Байт-у-байт `PATH_ALPHA` компілятора стилю: розбіжність тут = стрибок альфи. */
internal val PATH_ALPHA_BASE = listOf(14.0 to 0.40f, 16.0 to 0.58f, 18.5 to 0.82f)

/** Пішки стежка веде, а не супроводжує — тож вона голосніша за рубець поруч. */
internal val PATH_ALPHA_WALK = listOf(14.0 to 0.62f, 16.0 to 0.80f, 18.5 to 1.0f)
