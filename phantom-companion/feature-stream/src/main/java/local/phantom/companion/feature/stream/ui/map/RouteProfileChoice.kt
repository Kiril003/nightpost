package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.sensor.nav.KinematicsEngine

/**
 * Який режим пропонувати, коли оператор просто сказав «веди сюди».
 *
 * Досі кнопка «Маршрут сюди» жорстко просила `FOOT` — і застосунок пропонував
 * іти пішки на будь-яку відстань, навіть коли людина сиділа за кермом. Це
 * зауваження власника з пристрою: «автоматом показувати не пішки маршрут як
 * завжди, а найкращий».
 *
 * Порядок рішення — від найсильнішого свідчення до найслабшого:
 *
 * 1. **Чим людина ЗАРАЗ їде** (детектор режиму руху, `:core-sensor`). Якщо
 *    оператор у машині, вести його пішки — абсурд: він не покине авто через те,
 *    що так вирішив застосунок. Свідчення руху сильніше за будь-яку відстань.
 *    Довіряємо лише впевненому вердикту ([MIN_MOTION_CONFIDENCE]) — детектор
 *    сам тримає DRIVE/TRAIN невпевненими, доки рух не стане стійким.
 *
 * 2. **Відстань.** Ключовий поріг — [WALK_CEILING_M]. Нижче за нього транзит
 *    програє навіть теоретично: сама лише очікувана пауза на зупинці зʼїдає
 *    виграш у швидкості. Саме це власник побачив на екрані — 1,5 км «транзитом»
 *    за 17 хв, тоді як пішки те саме за схожий час, тільки без чекання.
 *
 * Функція чиста й покрита тестами: рішення, яке щодня бачить користувач, не
 * повинно жити всередині обробника натискання.
 *
 * СИРЕНА-4 — [siren] за живої тривоги над оператором виграє РІВНО той
 * розгалуз, який раніше без свідчення руху обирав транзит на далеку ціль:
 * чекати на зупинці чи гортати незнайому пересадку гірше за ходу, коли
 * над головою тривога, і транзит до того ж перший ламається без мережі
 * («маршрутизатор недоступний» — саме цей екран). Свідчення руху лишається
 * сильнішим за сирену: людину, що вже за кермом, застосунок з машини не жене.
 */
internal object RouteProfileChoice {

    /**
     * Стеля пішої відстані, м. Нижче — веди пішки: ~15 хв ходу, і транзит на
     * такій дистанції виграшу не дає (очікування + підхід до зупинки).
     */
    const val WALK_CEILING_M = 1_200.0

    /**
     * Нижче цієї впевненості вердикт руху ігноруємо. Детектор навмисно тримає
     * DRIVE і TRAIN невпевненими, доки рух не стане стійким, — а помилково
     * запропонувати авто пішоходу гірше, ніж просто порахувати відстань.
     */
    const val MIN_MOTION_CONFIDENCE = 0.7f

    /**
     * @param distanceM відстань по прямій між точками, м.
     * @param motion поточний вердикт руху або `null`, коли детектора нема.
     */
    fun forTrip(
        distanceM: Double,
        motion: KinematicsEngine.MotionProfile?,
        /** СИРЕНА-4 — тривога над самим оператором зараз, не деінде в країні. */
        siren: Boolean = false,
    ): RouteProfile {
        val moving = motion?.takeIf { it.confidence >= MIN_MOTION_CONFIDENCE }
        when (moving?.state) {
            // Людина вже в транспорті — веди для того транспорту, в якому вона є.
            KinematicsEngine.MotionState.DRIVE -> return RouteProfile.CAR
            KinematicsEngine.MotionState.CYCLE -> return RouteProfile.BIKE
            // Потяг — це вже громадський транспорт: доводимо маршрут ним.
            KinematicsEngine.MotionState.TRAIN -> return RouteProfile.TRANSIT
            else -> Unit
        }
        if (siren) return RouteProfile.FOOT
        return when {
            !distanceM.isFinite() || distanceM <= 0.0 -> RouteProfile.FOOT
            distanceM <= WALK_CEILING_M -> RouteProfile.FOOT
            // Без свідчення руху авто НЕ припускаємо: типовий оператор без
            // машини (вирок власника 29.08), тож далека ціль — транзит.
            // Авто лишається за детектором DRIVE вище і за чипом у смузі.
            else -> RouteProfile.TRANSIT
        }
    }

    /** Зручна форма для викликів із аркушів: приймає самі координати. */
    fun forTrip(
        from: Pair<Double, Double>,
        to: Pair<Double, Double>,
        motion: KinematicsEngine.MotionProfile?,
        siren: Boolean = false,
    ): RouteProfile = forTrip(haversineMeters(from, to), motion, siren)
}
