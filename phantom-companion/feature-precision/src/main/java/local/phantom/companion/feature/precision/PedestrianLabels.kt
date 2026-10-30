package local.phantom.companion.feature.precision

import local.phantom.companion.core.indoor.pdr.ContinuityState
import local.phantom.companion.core.indoor.pdr.PedestrianSnapshot
import local.phantom.companion.core.indoor.rtt.RttAvailability
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Підписи автономного ходу — чисті функції від знімка, без Compose і без
 * платформи, тож перевіряються на JVM разом із рештою моделі екрана.
 *
 * Тут тримається головна лінія всього екрана: **жодне число не зʼявляється без
 * підстави**. Там, де оцінка не впевнена, стоїть слово, а не цифра, — так само,
 * як класифікатор режиму руху друкує «Визначаю…» замість вигаданого режиму.
 * Різниця між «12 м на північний схід» і «менше за похибку» — це різниця між
 * даними і брехнею, і вона вирішується саме тут.
 */

/**
 * Заголовок стану — те одне, що людина прочитає, не вчитуючись.
 *
 * `null` (джерело ще не підписане) навмисно НЕ дорівнює
 * [ContinuityState.UNAVAILABLE]: перше триває частку секунди, друге — назавжди,
 * і сказати «немає акселерометра» там, де насправді «ще не почав», означало б
 * збрехати про залізо.
 */
internal fun continuityTitle(s: PedestrianSnapshot?): String = when (s?.state) {
    null -> "Визначаю…"
    ContinuityState.UNAVAILABLE -> "Недоступний"
    ContinuityState.WARMING -> "Визначаю…"
    ContinuityState.STILL -> "Стою"
    ContinuityState.BLIND -> "Кроки без напрямку"
    ContinuityState.HOLDING -> "Тримаю хід"
    ContinuityState.DRIFTED -> "Хід втратив точність"
}

/**
 * Пояснення без термінів — і, головне, з ПРИЧИНОЮ.
 *
 * «Не працює» не є відповіддю: людині в переході потрібна дія. Тому сліпий хід
 * називає своє джерело (немає магнітометра / поле спотворене / курс ще не
 * знайдено), а дрейф прямо каже, що потрібна нова привʼязка.
 */
internal fun continuityExplanation(s: PedestrianSnapshot?): String = when (s?.state) {
    null ->
        "Чекаю на датчики руху."

    ContinuityState.UNAVAILABLE ->
        "У пристрої немає акселерометра — рахувати кроки нічим."

    ContinuityState.WARMING ->
        "Чекаю на перші кроки. До них хід нічого не стверджує."

    ContinuityState.STILL ->
        "Кроків немає — похибка стоїть на місці разом із вами."

    ContinuityState.BLIND -> when {
        !s.sensors.magnetometer ->
            "Кроки рахуються, але напрямку взяти нізвідки: у пристрої немає магнітометра. " +
                "Курс зʼявиться після супутникової привʼязки на ходу."
        s.magneticAnomaly ->
            "Магнітне поле спотворене залізом — курсу з нього не беру. " +
                "Пройдений шлях відомий, напрямок — ні."
        else ->
            "Шукаю опорний курс. Поки його немає, шлях рахується, а напрямок — ні."
    }

    ContinuityState.HOLDING ->
        if (s.anchoredToWorld) {
            "Веду від останньої супутникової привʼязки. Похибка росте з кожним кроком, не з часом."
        } else {
            "Супутникової привʼязки ще не було: зміщення рахується від місця, де відкрито екран."
        }

    ContinuityState.DRIFTED ->
        "Похибка переросла корисну межу. Потрібна нова привʼязка — вийдіть туди, де видно небо."
}

/**
 * Зміщення від привʼязки одним рядком.
 *
 * Коли зміщення менше за власну похибку, воно НЕ стверджує, що людина
 * зрушила, — і тоді показується саме це, а не цифра, яку неминуче прочитають
 * як факт.
 */
internal fun displacementLabel(s: PedestrianSnapshot?): String = when {
    s == null || s.state == ContinuityState.UNAVAILABLE -> "—"
    s.state == ContinuityState.WARMING -> "—"
    s.state == ContinuityState.BLIND -> "напрямок невідомий"
    s.informative -> {
        val where = s.displacementBearingRad?.let { " · ${cardinalPhrase(it)}" } ?: ""
        "${metersLabel(s.displacementM)}$where"
    }
    else -> "менше за похибку"
}

/** Вісім румбів словами — «на північний схід» читається швидше за «47°». */
internal fun cardinalPhrase(bearingRad: Double): String {
    val octant = ((bearingRad / (PI / 4.0)).roundToInt() % 8 + 8) % 8
    return when (octant) {
        0 -> "на північ"
        1 -> "на північний схід"
        2 -> "на схід"
        3 -> "на південний схід"
        4 -> "на південь"
        5 -> "на південний захід"
        6 -> "на захід"
        else -> "на північний захід"
    }
}

/**
 * Курс тіла з його чесною невизначеністю.
 *
 * Невизначеність друкується завжди й поруч: курс «47°» і курс «47° ±25°» — це
 * різні твердження, і друге з них у кишені трапляється частіше.
 */
internal fun headingLabel(s: PedestrianSnapshot?): String {
    val h = s?.headingRad ?: return "невідомий"
    val deg = Math.toDegrees(h).roundToInt() % 360
    val sigma = Math.toDegrees(s.headingUncertaintyRad).roundToInt()
    return "$deg° ±$sigma°"
}

/** Метри до кілометра, далі — кілометри: «1240 м» ніхто не читає. */
internal fun metersLabel(m: Double): String = when {
    !m.isFinite() -> "—"
    m >= 1000.0 -> "%.1f км".format(m / 1000.0)
    else -> "${m.roundToInt()} м"
}

/** Похибка як «±N м»; до метра не округлюємо в менший бік — це обіцянка. */
internal fun accuracyLabel(s: PedestrianSnapshot?): String {
    val a = s?.accuracyM ?: return "—"
    if (!a.isFinite()) return "—"
    return "±${metersLabel(a)}"
}

/**
 * Рівень і сира висота поруч.
 *
 * Рівень — це ІНТЕРПРЕТАЦІЯ (без плану станції — просто висота, поділена на три
 * метри), а відносна висота — сам вимір. Показувати лише інтерпретацію означало
 * б видати «−2» за номер платформи, яким воно не є.
 */
internal fun levelLabel(s: PedestrianSnapshot?): String {
    if (s == null || !s.sensors.barometer) return "барометра немає"
    val level = s.level ?: return "—"
    val rounded = if (abs(level - level.roundToInt()) < 1e-6) {
        level.roundToInt().toString()
    } else {
        "%.1f".format(level)
    }
    return "$rounded · Δ ${metersLabel(abs(s.relativeAltitudeM))} ${if (s.relativeAltitudeM < 0) "вниз" else "вгору"}"
}

/** Вертикальний рух прямо зараз — ескалатор, ліфт, сходи. */
internal fun verticalTransitLabel(s: PedestrianSnapshot?): String? {
    if (s == null || !s.inVerticalTransit) return null
    return if (s.verticalRateMps < 0) "спуск" else "підйом"
}

/**
 * Чому Wi-Fi RTT (не)використовується — чесно, включно з випадком «залізо є».
 *
 * Апаратура сама по собі позиції не дає: RTT міряє ВІДСТАНЬ до точки доступу, а
 * щоб перетворити відстані на координату, потрібні координати самих точок. Їх у
 * застосунку немає й офлайн узятись їм нізвідки, тож «підтримується» тут не
 * означає «працює» — і написано саме так.
 */
internal fun rttCapabilityLabel(availability: RttAvailability?): String = when (availability) {
    null, RttAvailability.NO_HARDWARE -> "пристрій не підтримує"
    RttAvailability.DISABLED -> "Wi-Fi вимкнено"
    RttAvailability.PERMISSION_MISSING -> "потрібен дозвіл на пристрої поруч"
    RttAvailability.SUPPORTED -> "апаратура є, але немає координат точок доступу"
}
