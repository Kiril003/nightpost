package local.phantom.companion.data

import local.phantom.companion.core.mobility.TransportMode
import local.phantom.companion.core.sensor.nav.KinematicsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Скарга власника: «визначення транспортного засобу, на якому я рухаюсь, —
 * зараз тупорилі прорахунки, не справжні».
 *
 * Причина була не в алгоритмі: `:core-mobility` рахує режим руху цілком
 * серйозно, а от його вирок не доходив до класифікатора мапи ЖОДНОГО разу —
 * `onModeOpinion` не кликав ніхто в дереві. Ці тести стережуть переклад, що
 * зʼєднав два боки, і — головне — його МЕЖІ: де ми мовчимо.
 */
class VehicleOpinionTest {

    @Test
    fun `дорожній транспорт — один вид на авто й на маршрутку`() {
        assertEquals(
            KinematicsEngine.VehicleKind.PUBLIC_ROAD,
            vehicleKindOf(TransportMode.CAR),
        )
        assertEquals(
            KinematicsEngine.VehicleKind.PUBLIC_ROAD,
            vehicleKindOf(TransportMode.TRANSIT),
        )
    }

    @Test
    fun `рейки, метро і вода мають власні види`() {
        assertEquals(KinematicsEngine.VehicleKind.RAIL, vehicleKindOf(TransportMode.RAIL))
        assertEquals(KinematicsEngine.VehicleKind.METRO, vehicleKindOf(TransportMode.METRO))
        assertEquals(KinematicsEngine.VehicleKind.MARINE, vehicleKindOf(TransportMode.MARINE))
    }

    @Test
    fun `власне тіло транспортом не є`() {
        for (m in listOf(
            TransportMode.STILL,
            TransportMode.WALKING,
            TransportMode.RUNNING,
            TransportMode.CYCLING,
        )) {
            assertNull("«$m» — це не транспортний засіб", vehicleKindOf(m))
        }
    }

    @Test
    fun `«не знаю» лишається «не знаю», а не здогадом`() {
        assertNull(vehicleKindOf(TransportMode.UNKNOWN))
        // Політ несе сам стан руху; дублювати його видом — вигадувати сутність.
        assertNull(vehicleKindOf(TransportMode.FLIGHT))
    }

    @Test
    fun `поріг певності не пускає вгадування на екран`() {
        assertTrue("0.5 — це рівно «не знаю» у core-mobility", VEHICLE_OPINION_FLOOR > 0.5)
        assertTrue("поріг вище 0.9 замовчував би правдиві вироки", VEHICLE_OPINION_FLOOR <= 0.8)
    }
}
