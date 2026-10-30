package local.phantom.companion.license

import local.phantom.companion.core.license.LicenseModel

/**
 * Наскільки далеко назад PHANTOM має право згадувати.
 *
 * Вільний рівень памʼятає останній місяць: розмова від цього не ламається,
 * зникає лише «ти казав це навесні». Факти при цьому НЕ стираються і
 * продовжують накопичуватися — щойно ключ активують, повертається все, зокрема
 * прожите без ключа. Замок відсуває горизонт, а не палить архів.
 */
object MemoryHorizon {

    const val FREE_DAYS: Int = 30

    /** Найдавніша мить, яку дозволено згадати. */
    fun cutoffMs(nowMs: Long, entitled: Boolean): Long =
        if (entitled) Long.MIN_VALUE else nowMs - FREE_DAYS * LicenseModel.DAY_MS

    fun <T> withinHorizon(
        items: List<T>,
        nowMs: Long,
        entitled: Boolean,
        at: (T) -> Long,
    ): List<T> {
        val cutoff = cutoffMs(nowMs, entitled)
        return items.filter { at(it) >= cutoff }
    }
}
