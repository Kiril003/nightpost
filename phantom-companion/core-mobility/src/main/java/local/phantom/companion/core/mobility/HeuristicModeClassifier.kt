package local.phantom.companion.core.mobility

import kotlin.math.min

/**
 * Детерміноване дерево правил над ознаками вікна — робочий класифікатор режиму.
 *
 * Чому взагалі евристика, а не модель. ТЗ передбачає нейромережу (InceptionTime
 * для класифікації вікна + U-Time для сегментації, заявлені ~87 % точності), і це
 * правильна ціль. Але ваг цих моделей у проєкті немає, а тягнути TensorFlow Lite
 * заборонено — тож єдина чесна відповідь: евристика працює *насправді*, а місце
 * моделі тримає окрема реалізація того самого інтерфейсу ([NeuralModeClassifier]),
 * яка прямо каже, що не завантажена. Евристика не видає себе за нейромережу.
 *
 * Правила впорядковані від найнадійніших до найслабших, і кожен поріг — іменована
 * стала з походженням у KDoc. Числа взяті з фізіології ходьби (каденс, вертикальна
 * амплітуда), з характерних частот підвіски й рами та з діапазонів швидкості —
 * не з підбору під конкретний набір даних, якого в нас немає.
 *
 * Свідома межа: пара «авто проти наземного громадського» і пара «велосипед проти
 * авто в заторі» на одному 18-секундному вікні розрізняються погано. Класифікатор
 * не приховує цього завищеною впевненістю — він повертає низьку, а розвʼязання
 * лишає сегментації, у якої є хвилини контексту.
 */
class HeuristicModeClassifier(
    /** Верхня межа впевненості для свідомо неоднозначних вердиктів. */
    private val ambiguousConfidenceCap: Double = AMBIGUOUS_CONFIDENCE_CAP,
) : ModeClassifier {

    override fun classify(features: Map<String, Double>, windowEndElapsedMs: Long): ModeVerdict {
        val k = MotionFeatureKeys
        val samples = features[k.WINDOW_SAMPLES] ?: 0.0
        val gap = features[k.WINDOW_GAP_FRACTION] ?: 0.0
        // Немає ознак або вікно наполовину добудоване інтерполяцією — це не
        // «нерухомо», це «не знаю». Мовчазне STILL тут коштувало б пропущеної поїздки.
        if (samples < MotionFeatures.MIN_SAMPLES || gap > MAX_GAP_FRACTION) {
            return ModeVerdict(TransportMode.UNKNOWN, 0.0, windowEndElapsedMs, features)
        }

        val std = features[k.ACC_STD] ?: return unknown(windowEndElapsedMs, features)
        val cadenceHz = features[k.CADENCE_HZ] ?: 0.0
        val cadence = features[k.CADENCE_STRENGTH] ?: 0.0
        val periodicity = features[k.ACORR_PEAK] ?: 0.0
        val vertFraction = features[k.VERT_FRACTION] ?: 0.0
        val lowBand = (features[k.BAND_P0] ?: 0.0) + (features[k.BAND_P1] ?: 0.0)
        val vibrationBand = (features[k.BAND_P3] ?: 0.0) + (features[k.BAND_P4] ?: 0.0)

        val speedKnown = (features[k.SPEED_AVAILABLE] ?: 0.0) > 0.5
        val speedMean = features[k.SPEED_MEAN] ?: 0.0
        val speedCv = features[k.SPEED_CV] ?: 0.0
        val stopFraction = features[k.SPEED_STOP_FRACTION] ?: 0.0
        val jerk = features[k.ACC_JERK_MEAN] ?: 0.0

        // Хода — це збіг трьох незалежних свідчень: циклічність, її частота і те,
        // що коливання переважно вертикальні. Двох мало: вібрація трамвая теж
        // циклічна, а розмахування рукою теж дає потрібну частоту.
        val gaitLike = cadence >= GAIT_CADENCE_STRENGTH_MIN &&
            periodicity >= GAIT_PERIODICITY_MIN &&
            vertFraction >= GAIT_VERT_FRACTION_MIN

        val verdict = when {
            // 1. Нерухомо.
            std < STILL_STD_MAX_MPS2 && cadence < STILL_CADENCE_STRENGTH_MAX &&
                (!speedKnown || speedMean < STILL_SPEED_MAX_MPS) -> {
                // Без швидкості нерухомий телефон у плавно їдучому вагоні
                // виглядає так само — впевненість це визнає.
                TransportMode.STILL to if (speedKnown) STILL_CONFIDENCE else STILL_CONFIDENCE_NO_SPEED
            }

            // 2. Біг: каденс вище бігового порога і вертикальна амплітуда,
            //    якої ходьба не дає в принципі.
            gaitLike && cadenceHz >= RUN_CADENCE_MIN_HZ && std >= RUN_STD_MIN_MPS2 ->
                TransportMode.RUNNING to gaitConfidence(RUN_CONFIDENCE, cadence)

            // 3. Ходьба.
            gaitLike && cadenceHz >= WALK_CADENCE_MIN_HZ && cadenceHz <= WALK_CADENCE_MAX_HZ &&
                std >= WALK_STD_MIN_MPS2 && std <= WALK_STD_MAX_MPS2 ->
                TransportMode.WALKING to gaitConfidence(WALK_CONFIDENCE, cadence)

            // 4. Велосипед: помірна дисперсія без кроку, вібрація рами у верхніх
            //    смугах і — коли швидкість відома — стабільні 9–35 км/год.
            std >= CYCLE_STD_MIN_MPS2 && std <= CYCLE_STD_MAX_MPS2 && !gaitLike &&
                (vibrationBand >= CYCLE_VIBRATION_FRACTION_MIN || cyclingSpeed(speedKnown, speedMean, speedCv)) -> {
                val conf = if (cyclingSpeed(speedKnown, speedMean, speedCv)) CYCLE_CONFIDENCE
                else min(CYCLE_CONFIDENCE_NO_SPEED, ambiguousConfidenceCap)
                TransportMode.CYCLING to conf
            }

            // 5–6. Наземний транспорт: енергія зібрана в низьких смугах (кузов і
            //      підвіска), кроку немає. Авто чи громадський — вирішує профіль
            //      зупинок, і це найслабше місце всього дерева.
            lowBand >= VEHICLE_LOW_BAND_FRACTION_MIN && !gaitLike && std <= VEHICLE_STD_MAX_MPS2 &&
                (!speedKnown || speedMean >= VEHICLE_SPEED_MIN_MPS) -> {
                val transit = speedKnown &&
                    stopFraction >= TRANSIT_STOP_FRACTION_MIN &&
                    speedMean <= TRANSIT_SPEED_MAX_MPS &&
                    jerk <= TRANSIT_JERK_MAX_MPS3
                if (transit) {
                    TransportMode.TRANSIT to min(TRANSIT_CONFIDENCE, ambiguousConfidenceCap)
                } else {
                    TransportMode.CAR to if (speedKnown) CAR_CONFIDENCE else CAR_CONFIDENCE_NO_SPEED
                }
            }

            else -> TransportMode.UNKNOWN to 0.0
        }

        return ModeVerdict(verdict.first, verdict.second.coerceIn(0.0, 1.0), windowEndElapsedMs, features)
    }

    private fun unknown(endMs: Long, features: Map<String, Double>) =
        ModeVerdict(TransportMode.UNKNOWN, 0.0, endMs, features)

    /** Впевненість ходи росте з чистотою піку: розмита циклічність — розмитий вердикт. */
    private fun gaitConfidence(base: Double, cadenceStrength: Double): Double {
        val purity = min(1.0, cadenceStrength / GAIT_CADENCE_STRENGTH_FULL)
        return base * (0.65 + 0.35 * purity)
    }

    private fun cyclingSpeed(known: Boolean, meanMps: Double, cv: Double): Boolean =
        known && meanMps in CYCLE_SPEED_MIN_MPS..CYCLE_SPEED_MAX_MPS && cv <= CYCLE_SPEED_CV_MAX

    companion object {
        /**
         * Частка добудованих вузлів, вище якої вікно вважається непридатним.
         * Чверть вигаданих даних — уже не спостереження.
         */
        const val MAX_GAP_FRACTION = 0.25

        /** Стеля впевненості там, де фізика справді не розрізняє режими. */
        const val AMBIGUOUS_CONFIDENCE_CAP = 0.55

        // ── Нерухомо ──────────────────────────────────────────────────────
        /**
         * СКВ модуля прискорення в спокої, м/с². Телефон у кишені нерухомої
         * людини дає 0.05–0.15 (дихання, мікрорухи); будь-який транспорт із
         * працюючим двигуном — понад 0.2.
         */
        const val STILL_STD_MAX_MPS2 = 0.20

        /** У спокої циклічності немає взагалі; поріг лишає запас на вібрацію поруч. */
        const val STILL_CADENCE_STRENGTH_MAX = 0.35

        /** Швидкість, вище якої «нерухомо» неможливе, м/с (≈ 3 км/год). */
        const val STILL_SPEED_MAX_MPS = 0.8

        const val STILL_CONFIDENCE = 0.9
        const val STILL_CONFIDENCE_NO_SPEED = 0.7

        // ── Хода і біг ────────────────────────────────────────────────────
        /** Мінімальна частка енергії в піку, щоб рух вважався циклічним. */
        const val GAIT_CADENCE_STRENGTH_MIN = 0.12

        /** Частка енергії в піку, вище якої циклічність уже бездоганна. */
        const val GAIT_CADENCE_STRENGTH_FULL = 0.35

        /** Мінімальний нормований автокореляційний пік: форма циклу повторюється. */
        const val GAIT_PERIODICITY_MIN = 0.25

        /** Крок — насамперед вертикаль; нижче цієї частки це вже коливання кузова. */
        const val GAIT_VERT_FRACTION_MIN = 0.35

        /** Каденс ходьби дорослої людини: 84–138 кроків/хв. */
        const val WALK_CADENCE_MIN_HZ = 1.35
        const val WALK_CADENCE_MAX_HZ = 2.45

        /** Нижче цього СКВ «крок» насправді є коливанням транспорту, м/с². */
        const val WALK_STD_MIN_MPS2 = 0.45

        /** Вище — це вже біг, а не швидка хода, м/с². */
        const val WALK_STD_MAX_MPS2 = 6.0

        /** Біг починається з каденсу ≈ 150 кроків/хв. */
        const val RUN_CADENCE_MIN_HZ = 2.5

        /** Вертикальна амплітуда бігу принципово більша за ходьбу, м/с². */
        const val RUN_STD_MIN_MPS2 = 2.2

        const val WALK_CONFIDENCE = 0.9
        const val RUN_CONFIDENCE = 0.9

        // ── Велосипед ─────────────────────────────────────────────────────
        /**
         * Велосипед: рама передає дрібну вібрацію покриття, але вертикального
         * кроку немає. Смуга СКВ ширша за авто і вужча за ходьбу, м/с².
         */
        const val CYCLE_STD_MIN_MPS2 = 0.25
        const val CYCLE_STD_MAX_MPS2 = 2.0

        /** Частка енергії у смугах 2.6–4.8 Гц, характерна для вібрації рами. */
        const val CYCLE_VIBRATION_FRACTION_MIN = 0.28

        /** 9–36 км/год — робочий діапазон міського велосипедиста, м/с. */
        const val CYCLE_SPEED_MIN_MPS = 2.5
        const val CYCLE_SPEED_MAX_MPS = 10.0

        /** Велосипедист тримає швидкість рівніше за авто в міському потоці. */
        const val CYCLE_SPEED_CV_MAX = 0.40

        const val CYCLE_CONFIDENCE = 0.7
        const val CYCLE_CONFIDENCE_NO_SPEED = 0.5

        // ── Наземний транспорт ────────────────────────────────────────────
        /**
         * Частка енергії нижче 1.6 Гц. Підвіска авто і кузов вагона працюють
         * саме там; у ходьби основна смуга вища.
         */
        const val VEHICLE_LOW_BAND_FRACTION_MIN = 0.45

        /** Вище цього СКВ це вже не їзда, а щось у руках пасажира, м/с². */
        const val VEHICLE_STD_MAX_MPS2 = 2.5

        /** Нижче 2 м/с (7 км/год) «авто» без інших свідчень не оголошується. */
        const val VEHICLE_SPEED_MIN_MPS = 2.0

        /**
         * Частка вимірів із повною зупинкою у вікні. Автобус між зупинками
         * стоїть помітну частину часу; авто на трасі — ніколи.
         */
        const val TRANSIT_STOP_FRACTION_MIN = 0.20

        /** Наземний громадський у місті рідко тримає понад 50 км/год, м/с. */
        const val TRANSIT_SPEED_MAX_MPS = 14.0

        /** Розгін автобуса плавніший за автомобільний — обмеження на ривок, м/с³. */
        const val TRANSIT_JERK_MAX_MPS3 = 6.0

        const val CAR_CONFIDENCE = 0.8
        const val CAR_CONFIDENCE_NO_SPEED = 0.55
        const val TRANSIT_CONFIDENCE = 0.55
    }
}
