package local.phantom.companion.data

import local.phantom.companion.core.mobility.TransportMode
import local.phantom.companion.core.sensor.nav.KinematicsEngine

/**
 * Переклад вироку `:core-mobility` на мову класифікатора руху.
 *
 * Живе в застосунку, бо тільки він бачить обидва модулі: `:core-sensor` про
 * `:core-mobility` не знає й не має знати.
 *
 * Переклад свідомо ЧАСТКОВИЙ. Пішки, бігом, велосипедом і «стою» — це не
 * транспортний засіб, і вигадувати для них вид означало б наповнити поле
 * `MotionProfile.vehicle` шумом. `UNKNOWN` теж лишається null: «не знаю» —
 * це відповідь, а не привід вгадати.
 *
 * `CAR` і `TRANSIT` обидва дають [KinematicsEngine.VehicleKind.PUBLIC_ROAD] —
 * і це не недогляд, а межа фізики: авто й тролейбус їдуть тією самою дорогою
 * на тій самій підвісці. Що їх розрізняє — профіль зупинок, і саме його вже
 * зважив `:core-mobility`, перш ніж сказати `TRANSIT`.
 */
internal fun vehicleKindOf(mode: TransportMode): KinematicsEngine.VehicleKind? = when (mode) {
    TransportMode.CAR, TransportMode.TRANSIT -> KinematicsEngine.VehicleKind.PUBLIC_ROAD
    TransportMode.METRO -> KinematicsEngine.VehicleKind.METRO
    TransportMode.RAIL -> KinematicsEngine.VehicleKind.RAIL
    TransportMode.MARINE -> KinematicsEngine.VehicleKind.MARINE
    // Політ — стан руху, а не транспортний засіб у сенсі цього поля: його
    // несе сам MotionState.FLIGHT, і дублювати його видом нема потреби.
    TransportMode.FLIGHT -> null
    TransportMode.STILL,
    TransportMode.WALKING,
    TransportMode.RUNNING,
    TransportMode.CYCLING,
    TransportMode.UNKNOWN,
    -> null
}

/**
 * Нижче цієї певності вирок не варто передавати далі: класифікатор мапи має
 * власну витримку, і годувати його здогадами означає міняти вигляд екрана на
 * шум. 0.6 — не смак: `:core-mobility` віддає 0.5 як «рівно не знаю».
 */
internal const val VEHICLE_OPINION_FLOOR = 0.6
