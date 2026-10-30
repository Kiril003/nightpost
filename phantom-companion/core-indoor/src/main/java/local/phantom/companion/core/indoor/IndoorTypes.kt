package local.phantom.companion.core.indoor

/**
 * Спільний словник позиціонування без супутника.
 *
 * Уся геометрія тут ведеться в локальних метрах (схід/північ від якоря), а не в
 * градусах: під землею немає сенсу тягнути широту-довготу крізь кожен крок, а
 * похибки в метрах додаються чесно. Перетворення в WGS84 — рівно одне, на виході.
 */

/** Джерело, яке дало цю оцінку. Показується користувачу без прикрас. */
enum class IndoorSource { PDR, WIFI_RTT, FUSED, BAROMETER_ONLY, NONE }

/**
 * Крок пішохода, виявлений з акселерометра.
 *
 * @param lengthM оцінена довжина кроку — не константа: залежить від частоти
 *   кроку і вертикальної амплітуди, і саме її похибка домінує на довгих ділянках
 * @param headingRad курс за гіроскопом та магнітометром
 */
data class Step(
    val elapsedMs: Long,
    val lengthM: Double,
    val headingRad: Double,
    val headingUncertaintyRad: Double = 0.0,
)

/** Точка локальної траєкторії PDR у метрах від якоря. */
data class LocalPoint(val eastM: Double, val northM: Double)

/**
 * Одне вимірювання Wi-Fi RTT до точки доступу.
 *
 * На відміну від RSSI, це фізична відстань за часом проходження пакета, тож
 * перевідбиття псують її на десятки сантиметрів, а не на десятки метрів.
 *
 * @param distanceM виміряна відстань
 * @param stdDevM повідомлена приймачем невизначеність — вага в подальшому зведенні
 * @param anchor координата точки доступу в тій самій локальній системі, якщо відома
 */
data class RttRange(
    val bssid: String,
    val elapsedMs: Long,
    val distanceM: Double,
    val stdDevM: Double,
    val rssiDbm: Int? = null,
    val anchor: LocalPoint? = null,
)

/**
 * Підсумкова оцінка позиції в приміщенні.
 *
 * @param level рівень/поверх за барометром і планом: −1 для вестибюля, −2 для
 *   платформи глибокого закладення. Дробові значення допустимі для проміжних
 *   сходових майданчиків.
 * @param accuracyM чесна оцінка похибки, а не оптимістична: споживач вирішує
 *   за нею, чи взагалі показувати точку
 */
data class IndoorFix(
    val point: LocalPoint,
    val accuracyM: Double,
    val source: IndoorSource,
    val elapsedMs: Long,
    val level: Double? = null,
    val headingRad: Double? = null,
)

/**
 * Перетворення подібності, яким траєкторія PDR садиться на якорі RTT.
 *
 * Саме тому суміщення шукається як єдине перетворення цілої кривої, а не
 * покроково: дрейф PDR системний (накопичена похибка курсу повертає всю криву),
 * тож він виправляється поворотом і зсувом усієї траєкторії, а не боротьбою з
 * кожним кроком окремо.
 */
data class Similarity(
    val dxM: Double,
    val dyM: Double,
    val rotationRad: Double,
    val scale: Double = 1.0,
) {
    fun apply(p: LocalPoint): LocalPoint {
        val c = kotlin.math.cos(rotationRad)
        val s = kotlin.math.sin(rotationRad)
        return LocalPoint(
            eastM = scale * (p.eastM * c - p.northM * s) + dxM,
            northM = scale * (p.eastM * s + p.northM * c) + dyM,
        )
    }
}
