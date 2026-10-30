package local.phantom.companion.core.sensor.nav

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The road mesh decodes thousands of points per fix while the map is open. */
class RoadWayDecodeChurnTest {

    @Test
    fun `points decode without kotlin's regex-checked toDouble`() {
        val src = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "core-sensor/src/main/java/local/phantom/companion/core/sensor/nav/RoadMeshStore.kt") }
            .first { it.isFile }
            .readText()
        val body = src.substringAfter("fun decodePoints").substringBefore("\n    }\n")
        assertFalse("toDoubleOrNull runs an ICU regex per number: GBs of native churn", "toDoubleOrNull" in body)
    }

    @Test
    fun `decoding still means the same`() {
        assertEquals(listOf(50.45 to 30.52, 49.83 to 18.16), RoadWayCodec.decodePoints("50.45,30.52;49.83,18.16"))
        assertNull(RoadWayCodec.decodePoints("50.45,x;1,2"))
        assertNull(RoadWayCodec.decodePoints("NaN,1;1,2"))
        assertNull(RoadWayCodec.decodePoints("50.45,30.52"))
    }
}
