package local.phantom.companion.core.data.spatial

import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.net.live.LiveMarksConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Межа безпеки: позначки з цього шару бачать інші люди, тож їхні види — закритий
 * словник. Блокпост, позиції й влучання туди не входять жодною рукою.
 */
class MarkKindBoundaryTest {

    private class Sink : SpatialLayerSink {
        val layers = mutableListOf<SpatialLayer>()
        override suspend fun push(spec: SpatialLayerSpec): String {
            layers += SpatialLayer("l${layers.size}", spec, 0L, true, null)
            return "l${layers.size - 1}"
        }
        override suspend fun setVisible(layerId: String, visible: Boolean) = Unit
        override suspend fun clear(layerId: String): Boolean = layers.removeAll { it.id == layerId }
        override suspend fun clearThread(threadId: String?, origin: LayerOrigin?): Int = 0
        override suspend fun list(threadId: String?): List<SpatialLayer> = layers.toList()
        override suspend fun requestFocus(target: FocusTarget) = Unit
    }

    private val refused = listOf(
        "блокпост", "Блокпост на виїзді", "БЛОКПІСТ", "КПП", "checkpoint", "позиції", "військові",
        "окоп", "ППО", "приліт", "обстріл", "military", "troops", "патруль", "Патруль", "patrol", "поліція",
    )

    @Test
    fun `the shared vocabulary is exactly crash`() {
        assertEquals(setOf("ДТП"), LiveMarksConfig.ALLOWED_KINDS)
    }

    @Test
    fun `no refused kind reaches the layer by the single door`() = runTest {
        val sink = Sink()
        refused.forEach { kind ->
            val thrown = runCatching { sink.pushHazardMark(kind, 50.45, 30.52) }.exceptionOrNull()
            assertTrue("«$kind» пройшов у шар", thrown is RefusedMarkKind)
        }
        assertTrue(sink.layers.isEmpty())
    }

    @Test
    fun `the batch door drops them too`() = runTest {
        val sink = Sink()
        assertNull(sink.pushHazardMarks(refused.map { HazardMarkInput(it, 50.45, 30.52) }))
        sink.pushHazardMarks(listOf(HazardMarkInput("блокпост", 50.45, 30.52), HazardMarkInput("ДТП", 50.46, 30.53)))
        assertEquals(listOf("ДТП"), sink.layers.single().spec.markers.map { it.label })
    }

    @Test
    fun `ordinary road kinds are not refused`() {
        listOf("ДТП", "ремонт", "затор", "камера", "небезпека", "інше", "лежачий поліцейський", "патрон").forEach {
            assertFalse("«$it» відмовлено", isRefusedMarkKind(it))
        }
    }

    @Test
    fun `police and patrol in any word form stay refused`() {
        listOf("поліція", "поліцейський", "патруль", "патрулі", "Patrol car").forEach {
            assertTrue("«$it» пройшло", isRefusedMarkKind(it))
        }
    }
}
