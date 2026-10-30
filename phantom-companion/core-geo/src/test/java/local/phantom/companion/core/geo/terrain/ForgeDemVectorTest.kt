package local.phantom.companion.core.geo.terrain

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.InflaterInputStream
import kotlin.math.abs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.geo.pmtiles.FileRangeSource
import local.phantom.companion.core.geo.pmtiles.PmTilesCompression
import local.phantom.companion.core.geo.pmtiles.PmTilesPyramid
import local.phantom.companion.core.geo.pmtiles.PmTilesReader
import local.phantom.companion.core.geo.pmtiles.PmTilesTileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Висота, яку телефон дістає з пакета кузні, проти висоти, яку кузня туди
 * поклала.
 *
 * Проби — `forge/vectors/dem-moldova.json`: вокзал Кишинева 40.6 м, пагорб
 * біля Бєлєнешть ~326 м, Дністер ~24 м. Зріз архіву несе z6–z10, тож проби
 * z12 повного пакета сюди не входять і чесно пропускаються.
 */
class ForgeDemVectorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val vectors by lazy {
        Json.parseToJsonElement(
            javaClass.classLoader!!.getResourceAsStream("forge/dem-moldova-vectors.json")!!
                .bufferedReader().use { it.readText() },
        ).jsonObject
    }

    private fun archive(): File {
        val dest = File(temp.newFolder("forge"), "dem-md-slice.pmtiles")
        javaClass.classLoader!!.getResourceAsStream("forge/dem-md-slice.pmtiles")!!.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
        return dest
    }

    @Test
    fun `the archive is the terrarium png the hillshade layer expects`() = runTest {
        val reader = PmTilesReader.open(FileRangeSource(archive()))
        assertEquals(PmTilesTileType.PNG, reader.header.tileType)
        assertEquals(PmTilesCompression.NONE, reader.header.tileCompression)
        assertEquals(6, reader.header.minZoom)
        assertEquals(10, reader.header.maxZoom)
        assertTrue(reader.header.coversPoint(47.0105, 28.8638))
        reader.close()
    }

    @Test
    fun `every sampled pixel carries the elevation the forge measured`() = runTest {
        val reader = PmTilesReader.open(FileRangeSource(archive()))
        var checked = 0
        for (element in vectors["samples"]!!.jsonArray) {
            val sample = element.jsonObject
            val z = sample["z"]!!.jsonPrimitive.int
            if (z !in reader.header.minZoom..reader.header.maxZoom) continue
            val x = sample["x"]!!.jsonPrimitive.int
            val y = sample["y"]!!.jsonPrimitive.int
            val bytes = reader.tile(z, x, y)
            assertNotNull("тайла z$z/$x/$y немає в архіві", bytes)
            val image = decodePng(bytes!!)
            assertEquals(256, image.width)

            val px = sample["px"]!!.jsonPrimitive.int
            val py = sample["py"]!!.jsonPrimitive.int
            val at = py * image.width * 3 + px * 3
            val r = image.pixels[at].toInt() and 0xFF
            val g = image.pixels[at + 1].toInt() and 0xFF
            val b = image.pixels[at + 2].toInt() and 0xFF
            val want = sample["rgb"]!!.jsonArray.map { it.jsonPrimitive.int }
            assertEquals("колір z$z/$x/$y", want, listOf(r, g, b))
            // terrarium: висота — це сам піксель, а не таблиця поруч.
            val metres = r * 256.0 + g + b / 256.0 - 32768.0
            assertEquals(sample["elevation_m"]!!.jsonPrimitive.double, metres, 0.01)
            checked++
        }
        assertEquals("проби кузні z6–z10", 8, checked)
        reader.close()
    }

    private class Png(val width: Int, val height: Int, val pixels: ByteArray)

    /**
     * PNG розбирається тут вручну, бо юніт-тести Android-модуля компілюються
     * проти `android.jar`, і `javax.imageio` там немає зовсім.
     */
    private fun decodePng(bytes: ByteArray): Png {
        var i = 8
        var width = 0
        var height = 0
        var bitDepth = 0
        var colorType = 0
        val idat = java.io.ByteArrayOutputStream()
        while (i + 8 <= bytes.size) {
            val length = readInt(bytes, i)
            val type = String(bytes, i + 4, 4, Charsets.US_ASCII)
            val data = i + 8
            when (type) {
                "IHDR" -> {
                    width = readInt(bytes, data)
                    height = readInt(bytes, data + 4)
                    bitDepth = bytes[data + 8].toInt() and 0xFF
                    colorType = bytes[data + 9].toInt() and 0xFF
                }
                "IDAT" -> idat.write(bytes, data, length)
            }
            i = data + length + 4
            if (type == "IEND") break
        }
        assertEquals("terrarium — це 8 біт на канал", 8, bitDepth)
        assertEquals("terrarium — це RGB без палітри й без альфи", 2, colorType)

        val raw = InflaterInputStream(ByteArrayInputStream(idat.toByteArray())).use { it.readBytes() }
        val channels = 3
        val stride = width * channels
        val out = ByteArray(height * stride)
        var pos = 0
        for (y in 0 until height) {
            val filter = raw[pos].toInt() and 0xFF
            pos++
            for (x in 0 until stride) {
                val cur = raw[pos + x].toInt() and 0xFF
                val a = if (x >= channels) out[y * stride + x - channels].toInt() and 0xFF else 0
                val b = if (y > 0) out[(y - 1) * stride + x].toInt() and 0xFF else 0
                val c = if (x >= channels && y > 0) out[(y - 1) * stride + x - channels].toInt() and 0xFF else 0
                val value = when (filter) {
                    0 -> cur
                    1 -> cur + a
                    2 -> cur + b
                    3 -> cur + (a + b) / 2
                    4 -> cur + paeth(a, b, c)
                    else -> error("невідомий фільтр рядка: $filter")
                }
                out[y * stride + x] = (value and 0xFF).toByte()
            }
            pos += stride
        }
        return Png(width, height, out)
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = abs(p - a)
        val pb = abs(p - b)
        val pc = abs(p - c)
        return when {
            pa <= pb && pa <= pc -> a
            pb <= pc -> b
            else -> c
        }
    }

    private fun readInt(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)

    @Test
    fun `the pyramid walk covers every tile the forge baked`() = runTest {
        val reader = PmTilesReader.open(FileRangeSource(archive()))
        val z8 = PmTilesPyramid.tileRect(reader.header, 8)
        assertTrue("z8 мусить накривати вокзал", 148 in z8.x0..z8.x1 && 90 in z8.y0..z8.y1)
        val z10 = PmTilesPyramid.tileRect(reader.header, 10)
        assertTrue(594 in z10.x0..z10.x1 && 360 in z10.y0..z10.y1)
        assertTrue("рамка не може бути порожньою", z8.count >= 9)
        reader.close()
    }

    @Test
    fun `rows are flipped for mbtiles and only there`() {
        assertEquals(165, PmTilesPyramid.tmsRow(8, 90))
        assertEquals(663, PmTilesPyramid.tmsRow(10, 360))
        assertEquals(0, PmTilesPyramid.tmsRow(0, 0))
    }

    @Test
    fun `the converted metadata tells maplibre it is raster relief`() = runTest {
        val reader = PmTilesReader.open(FileRangeSource(archive()))
        val meta = PmTilesPyramid.mbTilesMetadata(reader.header, "phantom-terrain", TerrainPackImporter.ATTRIBUTION)
        assertEquals("png", meta["format"])
        assertEquals("baselayer", meta["type"])
        assertEquals("6", meta["minzoom"])
        assertEquals("10", meta["maxzoom"])
        assertEquals("© ESA / Copernicus DEM GLO-30", meta["attribution"])
        assertEquals(4, meta["bounds"]!!.split(",").size)
        reader.close()
    }
}
