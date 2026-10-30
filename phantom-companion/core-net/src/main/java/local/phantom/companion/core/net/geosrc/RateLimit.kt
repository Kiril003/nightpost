package local.phantom.companion.core.net.geosrc

import kotlin.math.max
import kotlin.math.min

/**
 * Обмежувач частоти «не частіше ніж раз на [minIntervalMs], з дозволеним
 * сплеском у [burst] запитів».
 *
 * Чому не проста перевірка «минуло N мс від останнього запиту»: карта
 * робить кілька запитів поспіль при відкритті екрана (bbox + деталі
 * станції), і жорсткий інтервал відрізав би другий із них назавжди.
 * Відро жетонів пропускає короткий сплеск і все одно тримає середню
 * частоту в межах домовленості з джерелом.
 *
 * Потокобезпечний.
 */
class MinIntervalLimiter(
    private val minIntervalMs: Long,
    private val burst: Int = 1,
    private val clock: GeoClock = SystemGeoClock,
) {
    private var tokens: Double = burst.toDouble()
    private var lastRefillMs: Long = Long.MIN_VALUE

    private fun refill(now: Long) {
        if (lastRefillMs == Long.MIN_VALUE) {
            lastRefillMs = now
            return
        }
        if (now <= lastRefillMs) return
        if (minIntervalMs <= 0L) {
            tokens = burst.toDouble()
            lastRefillMs = now
            return
        }
        val gained = (now - lastRefillMs).toDouble() / minIntervalMs.toDouble()
        tokens = min(burst.toDouble(), tokens + gained)
        lastRefillMs = now
    }

    /** Забирає один жетон. `false` означає «зараз не можна». */
    @Synchronized
    fun tryAcquire(): Boolean {
        refill(clock.nowMs())
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }

    /**
     * Скільки мілісекунд лишилося до появи наступного жетона.
     * Нуль означає, що [tryAcquire] пройде просто зараз.
     */
    @Synchronized
    fun retryAfterMs(): Long {
        refill(clock.nowMs())
        if (tokens >= 1.0) return 0L
        val missing = 1.0 - tokens
        return max(1L, (missing * minIntervalMs).toLong())
    }

    /** Мінімальний інтервал між запитами — реєстр показує його користувачеві. */
    val intervalMillis: Long get() = minIntervalMs
}

/**
 * Добовий лічильник кредитів.
 *
 * OpenSky тарифікує не запити, а кредити, і вартість запиту залежить від
 * площі bbox. Дізнатися залишок з відповіді не можна (заголовок є не
 * завжди), тож рахувати доводиться локально — інакше «квота скінчилася»
 * приходить як 429 посеред маршруту без попередження.
 *
 * Доба — UTC-доба, як її рахує саме джерело; локальна північ дала б зсув
 * на кілька годин і передчасне «квота вичерпана».
 */
class DailyQuota(
    val limitPerDay: Int,
    private val clock: GeoClock = SystemGeoClock,
) {
    private var dayIndex: Long = Long.MIN_VALUE
    private var spent: Int = 0

    private fun roll(now: Long) {
        val today = Math.floorDiv(now, DAY_MS)
        if (today != dayIndex) {
            dayIndex = today
            spent = 0
        }
    }

    /** Скільки кредитів іще можна витратити сьогодні. */
    @Synchronized
    fun remaining(): Int {
        roll(clock.nowMs())
        return max(0, limitPerDay - spent)
    }

    /** Чи вистачить квоти на [credits] кредитів — без списання. */
    @Synchronized
    fun canSpend(credits: Int): Boolean {
        roll(clock.nowMs())
        return credits >= 0 && spent + credits <= limitPerDay
    }

    /** Списує [credits] кредитів. `false` — квоти не вистачило, нічого не списано. */
    @Synchronized
    fun spend(credits: Int): Boolean {
        roll(clock.nowMs())
        if (credits < 0) return false
        if (spent + credits > limitPerDay) return false
        spent += credits
        return true
    }

    /** Момент наступного обнулення лічильника (UTC-північ) у мілісекундах. */
    @Synchronized
    fun resetAtMs(): Long {
        val now = clock.nowMs()
        roll(now)
        return (Math.floorDiv(now, DAY_MS) + 1) * DAY_MS
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
