package local.phantom.companion.feature.stream.ui.map.nav

import android.graphics.RectF
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import local.phantom.companion.core.sensor.nav.RoadWay

/**
 * Дороги з того, що вже намальовано на екрані.
 *
 * ПОЛЬОВИЙ ЗВІТ (власник, 2 серпня 2026): «були місця, де мою точку показувало
 * трохи неподалік, а я їхав чи на трасі, чи по дорозі». Обидва снапери, які в
 * нас були, — мережеві: OSRM `/match` і плитки Overpass. У дорозі вони
 * недоступні рівно тоді, коли потрібні, тож точка лишалась сирою.
 *
 * Але геометрія доріг уже лежить у памʼяті: рендерер щойно намалював їх із
 * векторних тайлів, часто офлайнових. [collect] дістає ті самі лінії назад
 * через `queryRenderedFeatures` — без жодного запиту в мережу.
 *
 * Шари не називаємо: id залежать від стилю (Liberty/Positron/Dark), а схема
 * OpenMapTiles — ні. Тому беремо все у вікні і лишаємо те, що має дорожній
 * `class`. Стиль можна міняти скільки завгодно — снап не зламається.
 */
internal object RenderedRoads {

    /** Дорожні значення `class` зі схеми OpenMapTiles (шар `transportation`). */
    private val ROAD_CLASSES = setOf(
        "motorway", "trunk", "primary", "secondary", "tertiary",
        "minor", "service", "residential", "unclassified", "living_street",
        "motorway_link", "trunk_link", "primary_link", "secondary_link",
        "tertiary_link",
    )

    /** Пішохідне і рейкове відкидаємо: авто ними не їде, а снап на них
     *  стягнув би точку з проїжджої частини на тротуар поруч. */
    private val REJECT_CLASSES = setOf(
        "path", "track", "footway", "pedestrian", "steps", "cycleway",
        "rail", "transit", "ferry", "aerialway",
    )

    /**
     * @param boxPx півсторона вікна запиту в екранних пікселях довкола центра.
     *   Півкілометра на типовому масштабі руху — досить, щоб мати і поточну
     *   дорогу, і сусідні смуги розвʼязки, і не тягнути пів міста.
     */
    fun collect(map: MapLibreMap, boxPx: Float = 320f): List<RoadWay> {
        // ЗБІЙ НА ПРИСТРОЇ ВЛАСНИКА, 16.08 19:22:56 — OutOfMemoryError у
        // RoadGraph.<init>, купа впёрлась у стелю 256 МБ, застосунок закрився
        // просто на мапі. Той самий слід о 11:40 і об 11:51: тричі за добу.
        //
        // Вікно тут міряне в ЕКРАННИХ пікселях — 640×640 незалежно від масштабу.
        // На вулиці це пів кілометра дороги; на масштабі області це вся дорожня
        // мережа, яку рендерер намалював, і кожна її вершина лягає ключем у
        // HashMap графа. Межі не було ніякої.
        //
        // Дві ворота, і обоє про суть, а не про памʼять. Перші: приліплювання до
        // намальованої дороги має сенс лише там, де дорога намальована окремою
        // дорогою — на дрібних масштабах тайл віддає узагальнену лінію, до якої
        // приліплюватись нічого. Другі: навіть на вулиці щільне місто віддає
        // більше, ніж варто тримати, тож збір має стелю і КАЖЕ, коли її дістав.
        val zoom = map.cameraPosition.zoom
        if (zoom < MIN_SNAP_ZOOM) return emptyList()

        val centre = map.projection.toScreenLocation(map.cameraPosition.target ?: return emptyList())
        val box = RectF(
            centre.x - boxPx, centre.y - boxPx,
            centre.x + boxPx, centre.y + boxPx,
        )
        val features = runCatching { map.queryRenderedFeatures(box) }.getOrNull().orEmpty()

        var points = 0
        val out = LinkedHashMap<Long, RoadWay>()
        for (f in features) {
            if (out.size >= MAX_WAYS || points >= MAX_POINTS) {
                android.util.Log.i(
                    "PhantomSnap",
                    "road collection capped at ${out.size} ways / $points points " +
                        "(zoom ${"%.1f".format(zoom)}, features ${features.size})",
                )
                break
            }
            val cls = f.getStringProperty("class") ?: continue
            if (cls in REJECT_CLASSES) continue
            if (cls !in ROAD_CLASSES) continue

            val lines: List<List<Pair<Double, Double>>> = when (val g = f.geometry()) {
                is LineString -> listOf(g.coordinates().map { it.latitude() to it.longitude() })
                is MultiLineString ->
                    g.coordinates().map { part -> part.map { it.latitude() to it.longitude() } }
                else -> continue
            }

            // Тайли ріжуть одну дорогу на шматки, і кожен приходить окремою
            // фічею з тим самим id. Ключ складений із id та першої точки, щоб
            // сусідні шматки не затирали один одного в мапі.
            val baseId = f.getNumberProperty("id")?.toLong()
                ?: f.id()?.toLongOrNull()
                ?: cls.hashCode().toLong()
            val name = f.getStringProperty("name")
            val oneway = f.getNumberProperty("oneway")?.toInt() == 1

            for (pts in lines) {
                if (pts.size < 2) continue
                val key = baseId * 31 + pts.first().hashCode()
                out[key] = RoadWay(id = key, points = pts, name = name, oneway = oneway)
                points += pts.size
            }
        }
        return out.values.toList()
    }

    /**
     * Нижче цього наближення приліплюватись нема до чого: тайл віддає
     * узагальнену лінію замість смуг, і «дорога під тобою» на такому масштабі
     * не визначається взагалі. 12 — приблизно рівень міського району.
     */
    const val MIN_SNAP_ZOOM = 12.0

    /** Стеля збору. Щільний центр міста в цьому вікні дає ~400–700 шляхів. */
    private const val MAX_WAYS = 2_000

    /** Друга стеля — по вершинах: сотня довгих шляхів важить більше за тисячу коротких. */
    private const val MAX_POINTS = 60_000
}
