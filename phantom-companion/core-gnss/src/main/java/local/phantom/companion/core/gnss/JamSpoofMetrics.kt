package local.phantom.companion.core.gnss

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Розділені метрики глушіння та підміни супутникового сигналу.
 *
 * Розділення тут не косметичне, а суттєве: глушіння й підміна — протилежні
 * атаки, і плутати їх означає діяти неправильно в обох випадках.
 *
 *  • **Глушіння** ВІДБИРАЄ сигнал. Його видно як узгоджений обвал C/N0 по всій
 *    констеляції, втрату супутників, зростання невизначеностей. Реакція —
 *    перейти на числення шляху. Детектор «слабкого сигналу» його ловить.
 *
 *  • **Підміна** навпаки ДАЄ сильний, «здоровий» сигнал, який бреше. C/N0
 *    висока й неприродно однорідна, зсув годинника стрибає без відповідного
 *    руху, доплер розходиться з фазою, супутники зʼявляються групою з однаковими
 *    характеристиками. Реакція — відкинути супутники, що виглядають найкраще.
 *    Детектор «слабкого сигналу» тут СЛІПИЙ: сигнал сильний.
 *
 * 21 липня 2026 в Києві сталося саме друге: супутники показали Ліму, мережа
 * лишилась у Києві (розбіжність 12 270 км), C/N0 при цьому була високою. Тому
 * бал підміни рахується з ознак, які підробити ззовні важко: раптова
 * однорідність, стрибок годинника, поява групи, і — як зовнішнє свідчення —
 * розбіжність із незалежним мережевим позиціонуванням.
 *
 * Клас із памʼяттю: обидві атаки видно лише в динаміці (обвал ВІДНОСНО норми,
 * поява ВІДНОСНО попереднього неба). База оновлюється лише «чистими» епохами,
 * щоб атака не змогла поступово стати новою нормою.
 */
class JamSpoofMetrics(
    /** Скільки останніх епох формують «норму» C/N0 та кількості супутників. */
    private val baselineWindow: Int = BASELINE_WINDOW,
    /** Обвал середньої C/N0 (dB-Hz) відносно бази, що дає повний бал глушіння. */
    private val jamCn0Drop: Double = JAM_CN0_DROP,
    /** Нижче цієї σ (dB-Hz) розкид C/N0 «занадто чистий», щоб бути природним. */
    private val spoofUniformityCn0: Double = SPOOF_UNIFORMITY_CN0,
) {

    private val cn0History = ArrayDeque<Double>()
    private val countHistory = ArrayDeque<Double>()
    private var lastKeys: Set<String> = emptySet()
    private var lastClockBias: Double? = null

    fun reset() {
        cn0History.clear()
        countHistory.clear()
        lastKeys = emptySet()
        lastClockBias = null
    }

    /**
     * Оцінити цілісність епохи.
     *
     * @param epoch поточні спостереження
     * @param excludedByFde супутники, які FDE вже виключив як суперечливі —
     *   велика їх частка сама по собі є сильним свідченням підміни
     * @param networkDivergenceKm розбіжність із мережевим позиціонуванням, км;
     *   `null` — мережевого якоря немає (наприклад у літаку)
     * @param fixSource звідки взято поточний розвʼязок — для звіту
     */
    fun evaluate(
        epoch: GnssEpoch,
        excludedByFde: Map<String, String> = emptyMap(),
        networkDivergenceKm: Double? = null,
        fixSource: FixSource = FixSource.GNSS_FUSED,
    ): GnssIntegrity {
        val obs = epoch.observations
        val notes = mutableListOf<String>()

        val meanCn0 = if (obs.isEmpty()) 0.0 else obs.map { it.cn0DbHz }.average()
        val baselineCn0 = if (cn0History.isEmpty()) meanCn0 else cn0History.average()
        val baselineCount = if (countHistory.isEmpty()) obs.size.toDouble() else countHistory.average()

        val jam = jammingScore(obs.size, meanCn0, baselineCount, baselineCn0, notes)
        val spoof = spoofingScore(epoch, meanCn0, excludedByFde, networkDivergenceKm, notes)

        // Базу оновлюємо ЛИШЕ чистими епохами: інакше повільна підміна або
        // наростаюче глушіння стануть новою «нормою», і детектор осліпне саме
        // тоді, коли він найпотрібніший.
        val clean = jam < 0.3 && spoof < 0.3
        if (clean && obs.isNotEmpty()) {
            push(cn0History, meanCn0)
            push(countHistory, obs.size.toDouble())
        }
        lastKeys = obs.map { it.key }.toSet()
        lastClockBias = epoch.clockBiasNanos

        return GnssIntegrity(
            jammingScore = jam,
            spoofingScore = spoof,
            usableSats = obs.size - excludedByFde.size,
            excluded = excludedByFde,
            dualFrequency = epoch.dualFrequency,
            source = fixSource,
            notes = notes,
        )
    }

    /**
     * Глушіння: сигнал слабшає узгоджено.
     *
     * Порогів два, бо атака має два різні почерки: різкий обвал C/N0 нижче норми
     * і різка втрата видимих супутників. Береться сильніший з двох — досить
     * одного виразного симптому.
     */
    private fun jammingScore(
        count: Int,
        meanCn0: Double,
        baselineCount: Double,
        baselineCn0: Double,
        notes: MutableList<String>,
    ): Double {
        if (cn0History.size < 2) return 0.0 // ще нема з чим порівнювати

        val cn0Drop = (baselineCn0 - meanCn0).coerceAtLeast(0.0)
        val cn0Score = (cn0Drop / jamCn0Drop).coerceIn(0.0, 1.0)

        val lostFraction = if (baselineCount <= 0.0) 0.0
        else ((baselineCount - count) / baselineCount).coerceIn(0.0, 1.0)

        val score = maxOf(cn0Score, lostFraction)

        if (score >= 0.6) {
            notes += "Сигнал слабшає: C/N0 впала на ${fmt(cn0Drop)} dB-Hz, " +
                "супутників ${count} проти звичних ${baselineCount.toInt()}."
        }
        return score
    }

    /**
     * Підміна: сигнал сильний, але бреше. Бали з кількох незалежних свідчень
     * беруться «мʼяким або» — кожне окремо переконливе, разом певність росте.
     */
    private fun spoofingScore(
        epoch: GnssEpoch,
        meanCn0: Double,
        excludedByFde: Map<String, String>,
        networkDivergenceKm: Double?,
        notes: MutableList<String>,
    ): Double {
        val obs = epoch.observations
        var score = 0.0

        // 1) Розбіжність із мережею — найсильніше свідчення (наприклад стрибок на сотні км).
        networkDivergenceKm?.let { km ->
            if (km >= SPOOF_DIVERGENCE_KM) {
                score = maxOf(score, 1.0)
                notes += "Супутникова позиція розходиться з мережею на ${fmt(km)} км — це підміна."
            } else if (km >= SPOOF_DIVERGENCE_KM / 5) {
                score = maxOf(score, 0.5)
            }
        }

        // 2) FDE виключив велику частку неба як суперечливу руху пристрою.
        if (obs.isNotEmpty()) {
            val excludedFraction = excludedByFde.size.toDouble() / obs.size
            if (excludedFraction >= 0.4) {
                score = maxOf(score, 0.6 + 0.4 * (excludedFraction - 0.4) / 0.6)
                notes += "Виключено ${excludedByFde.size} з ${obs.size} супутників як суперечливі рухові."
            }
        }

        // 3) Неприродна однорідність сильного сигналу.
        if (obs.size >= 4 && meanCn0 >= SPOOF_STRONG_CN0) {
            val sd = stdDev(obs.map { it.cn0DbHz })
            if (sd < spoofUniformityCn0) {
                score = maxOf(score, 0.55)
                notes += "Сигнал підозріло однорідний (σ=${fmt(sd)} dB-Hz при середній ${fmt(meanCn0)}) — можлива підміна."
            }
        }

        // 4) Стрибок зсуву годинника без руху (понад 100 км еквівалентної відстані).
        val prevBias = lastClockBias
        val bias = epoch.clockBiasNanos
        if (prevBias != null && bias != null) {
            val jumpM = abs(bias - prevBias) * LIGHT_M_PER_NANOS
            if (jumpM >= SPOOF_CLOCK_JUMP_M * 10.0) {
                score = maxOf(score, 0.4)
                notes += "Різкий стрибок годинника приймача (${fmt(jumpM)} м) без руху."
            }
        }

        return score.coerceIn(0.0, 1.0)
    }

    private fun push(q: ArrayDeque<Double>, v: Double) {
        q.addLast(v)
        while (q.size > baselineWindow) q.removeFirst()
    }

    private fun stdDev(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val m = xs.average()
        return sqrt(xs.sumOf { (it - m) * (it - m) } / xs.size)
    }

    private fun fmt(v: Double): String = if (v >= 100) "${v.toInt()}" else "%.1f".format(v)

    companion object {
        const val BASELINE_WINDOW = 30
        const val JAM_CN0_DROP = 12.0
        const val SPOOF_UNIFORMITY_CN0 = 1.2
        const val SPOOF_STRONG_CN0 = 40.0

        /**
         * Розбіжність із мережею, що сама по собі доводить підміну, км.
         * 21.07 було 12 270 км; 50 не дає ні стільникова похибка, ні
         * багатошляховість.
         */
        const val SPOOF_DIVERGENCE_KM = 50.0

        /** Стрибок зсуву годинника (метри), підозрілий без руху. */
        const val SPOOF_CLOCK_JUMP_M = 1_000.0

        /** Швидкість світла, м/нс — для переведення зсуву годинника у метри. */
        const val LIGHT_M_PER_NANOS = 0.299_792_458
    }
}
