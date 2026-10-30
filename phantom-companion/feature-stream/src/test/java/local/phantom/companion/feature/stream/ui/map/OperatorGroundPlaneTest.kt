package local.phantom.companion.feature.stream.ui.map

import androidx.compose.ui.graphics.toArgb
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пак лежить НА ЗЕМЛІ — і це властивість шару, не побажання.
 *
 * Вирок гонтлету, 5 персон: «пак без стрілки напрямку в спокої; на даху
 * 3D-будинку». Друга половина — про нахил: шари оператора малюються ПОВЕРХ
 * обʼєму будівель (так вирішено навмисно — будівля не кришка над людиною,
 * див. MapTier.BUILDING_EXTRUSION), тож єдине, що відрізняє «лежить на
 * землі» від «наліплений на стіну», — вирівнювання по площині мапи. Забери
 * одне `pitch-alignment`, і знак почне стояти щитом до камери на висоті даху.
 *
 * Перевірка читає ДЖЕРЕЛО, бо в JVM немає ні стилю, ні рушія: MapLibre тут
 * не завантажиться. Той самий спосіб, що в MapMarkerContrastTest.
 */
class OperatorGroundPlaneTest {

    private fun mapSource(name: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        assertTrue("repo root with feature-stream/ not found", dir != null)
        val file = File(
            dir!!,
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/$name",
        )
        assertTrue("$name не знайдено — перевірка вже нічого не боронить", file.isFile)
        return file.readText().lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
    }

    /** Тіло інсталятора, зіставлене по дужках. */
    private fun installer(name: String, fileName: String): String {
        val source = mapSource(fileName)
        val at = source.indexOf("fun $name(")
        assertTrue("$name зник із $fileName", at >= 0)
        var depth = 0
        var i = source.indexOf('{', at)
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            i++
        }
        return source.substring(at, minOf(i + 1, source.length))
    }

    /** Блок одного шару: від його `getLayer(...)` до наступного такого ж. */
    private fun layerBlock(body: String, layerConst: String): String {
        val at = body.indexOf("getLayer($layerConst)")
        assertTrue("шар $layerConst зник з installOperatorPin", at >= 0)
        val next = body.indexOf("getLayer(", at + 1).takeIf { it > at } ?: body.length
        return body.substring(at, next)
    }

    @Test
    fun `every operator layer lies in the map plane`() {
        val body = installer("installOperatorPin", "MapStyleLayers.kt")
        mapOf(
            "OPERATOR_CONE_LAYER" to "iconPitchAlignment",
            "OPERATOR_HALO_LAYER" to "circlePitchAlignment",
            "OPERATOR_RING_LAYER" to "circlePitchAlignment",
            "OPERATOR_NOTCH_LAYER" to "iconPitchAlignment",
            "OPERATOR_DOT_LAYER" to "circlePitchAlignment",
            "OPERATOR_ARROW_LAYER" to "iconPitchAlignment",
        ).forEach { (layer, property) ->
            val block = layerBlock(body, layer)
            assertTrue(
                "$layer втратив $property(..._MAP) — при нахилі він стане щитом до камери " +
                    "на висоті даху, а не диском на землі",
                block.contains("$property(") && block.contains("PITCH_ALIGNMENT_MAP"),
            )
        }
    }

    /**
     * «Я тут» — чорнильний диск, бурштинове кільце і суцільна точка: бурштин
     * торкається лише чорнила, а сам бурштин — акцент дизайн-системи, не
     * окремий жовтий. Ні пульсу, ні сяйва — жодного blur на шарах оператора.
     */
    @Test
    fun `the puck is an amber ring and dot on an ink disc, with no glow`() {
        val body = installer("installOperatorPin", "MapStyleLayers.kt")
        val halo = layerBlock(body, "OPERATOR_HALO_LAYER")
        val ring = layerBlock(body, "OPERATOR_RING_LAYER")
        val dot = layerBlock(body, "OPERATOR_DOT_LAYER")
        assertTrue("диск пака не чорнильний", halo.contains("circleColor(MapSymbolPalette.PUCK_RIM)"))
        assertTrue("у диска немає світлого канту", halo.contains("circleStrokeColor(MapSymbolPalette.PUCK_HALO)"))
        assertTrue("кільце не бурштинове", ring.contains("circleStrokeColor(MapSymbolPalette.PUCK_CORE)"))
        assertTrue("кільце залите — це вже не кільце", ring.contains("circleOpacity(0f)"))
        assertTrue("точка не бурштинова", dot.contains("circleColor(MapSymbolPalette.PUCK_CORE)"))
        assertTrue("сяйво на пакові", !body.contains("circleBlur"))
        assertEquals(
            "бурштин пака розійшовся з акцентом дизайн-системи",
            local.phantom.companion.core.design.tokens.NightColors.accent.toArgb(),
            MapSymbolPalette.PUCK_CORE,
        )
    }

    /** Курс повертає знак разом із мапою, а не разом із екраном. */
    @Test
    fun `the direction marks rotate with the ground, not with the screen`() {
        val body = installer("installOperatorPin", "MapStyleLayers.kt")
        listOf("OPERATOR_CONE_LAYER", "OPERATOR_NOTCH_LAYER", "OPERATOR_ARROW_LAYER").forEach { layer ->
            assertTrue(
                "$layer крутиться по екрану — при повороті мапи він показуватиме не туди",
                layerBlock(body, layer).contains("ICON_ROTATION_ALIGNMENT_MAP"),
            )
        }
    }

    /**
     * Обʼєм ставить себе ПІД перший символьний шар стилю, а шари оператора
     * дописуються в кінець списку — тобто вище. Це і є причина, чому пак
     * видно з-за будинку; ціна — «на даху» при нахилі, яку гасить площина.
     * Зникне `addLayerBelow` — і будинки зʼїдять оператора цілком.
     */
    @Test
    fun `buildings go under the style's own labels, never over the operator`() {
        val body = installer("installBuildingExtrusions", "MapTerrainLayers.kt")
        assertTrue(
            "обʼєм більше не вставляється під перший символьний шар — контракт Ids.kt зламано",
            body.contains("addLayerBelow(layer, firstSymbol)"),
        )
        assertTrue(
            "обʼєм шукає перший символьний шар не як SymbolLayer",
            body.contains("is org.maplibre.android.style.layers.SymbolLayer"),
        )
    }

    /** Конус говорить рівно настільки голосно, наскільки пристрій упевнений. */
    @Test
    fun `a sure compass draws a louder and longer cone than an unsure one`() {
        val ladder = listOf(CompassFidelity.HIGH, CompassFidelity.MEDIUM, CompassFidelity.LOW)
        ladder.zipWithNext().forEach { (surer, vaguer) ->
            assertTrue(
                "$surer шепоче не тихіше за $vaguer — саме це й прочитали як «нема напрямку»",
                surer.restAlpha > vaguer.restAlpha,
            )
            assertTrue("$surer тягнеться не далі за $vaguer", surer.reachDp > vaguer.reachDp)
        }
        assertTrue(
            "напрям, якого пристрій не знає, усе одно щось малює",
            CompassFidelity.UNRELIABLE.restAlpha == 0f,
        )
        assertTrue(
            "найгірший із намальованих конусів тихіший за той, що був до вироку (0.55)",
            CompassFidelity.LOW.restAlpha >= 0.55f,
        )
    }

    /** Засічка мусить виходити за світлий обід — інакше її не видно збоку. */
    @Test
    fun `the notch reaches past the puck's own rim`() {
        val body = mapSource("MapStyleLayers.kt")
        assertTrue(
            "засічка малюється від краю ядра назовні — інакше вона під ободом",
            body.contains("val rIn = (PUCK_CORE_R_DP + PUCK_RIM_W_DP) * density") &&
                body.contains("val rOut = (PUCK_HALO_R_DP + NOTCH_OVERHANG_DP) * density"),
        )
    }
}
