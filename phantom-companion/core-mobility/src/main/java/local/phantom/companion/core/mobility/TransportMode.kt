package local.phantom.companion.core.mobility

/**
 * Режим пересування користувача та все, що потрібно, аби йому довіряти.
 *
 * Тип свідомо несе не лише клас, а й впевненість і латентність: споживач
 * (голос, маршрутизація, енергополітика) має право поводитись інакше, коли
 * система «майже певна вже хвилину» і коли вона «щойно припустила».
 */
enum class TransportMode {
    /** Нерухомо. Не те саме, що зупинка на світлофорі — див. сегментацію. */
    STILL,
    WALKING,
    RUNNING,
    CYCLING,
    /** За кермом або пасажиром авто — з акселерометра ці випадки нерозрізненні. */
    CAR,
    /** Наземний громадський: автобус, тролейбус, трамвай. */
    TRANSIT,
    /** Метро. Окремо від [RAIL] через відсутність супутника і власну геометрію. */
    METRO,
    RAIL,
    /** Судно. */
    MARINE,
    /** Літак. Визначається без GPS і звʼязку — акселерометр плюс барометр. */
    FLIGHT,
    UNKNOWN,
}

/**
 * Класифікація одного вікна спостережень.
 *
 * @param confidence 0..1
 * @param windowEndElapsedMs кінець вікна за монотонним годинником
 */
data class ModeVerdict(
    val mode: TransportMode,
    val confidence: Double,
    val windowEndElapsedMs: Long,
    val features: Map<String, Double> = emptyMap(),
)

/**
 * Сегмент поїздки — результат часової сегментації поверх послідовності вердиктів.
 *
 * Саме сегмент, а не окреме вікно, є одиницею, придатною для продукту: «ви їхали
 * автомобілем 18 хвилин» — це сегмент; окреме вікно на світлофорі, що виглядає
 * як [TransportMode.STILL], не має породжувати ні запис у щоденнику, ні репліку.
 */
data class ModeSegment(
    val mode: TransportMode,
    val startElapsedMs: Long,
    val endElapsedMs: Long,
    val confidence: Double,
) {
    val durationMs: Long get() = endElapsedMs - startElapsedMs
}

/**
 * Контракт класифікатора. Реалізація сьогодні — детермінована евристика над
 * ознаками вікна; завтра на це саме місце стає нейромодель, і жоден споживач
 * про це не дізнається. Тому інтерфейс не знає ні про TensorFlow, ні про вікна:
 * лише ознаки на вході, вердикт на виході.
 */
interface ModeClassifier {
    fun classify(features: Map<String, Double>, windowEndElapsedMs: Long): ModeVerdict
}
