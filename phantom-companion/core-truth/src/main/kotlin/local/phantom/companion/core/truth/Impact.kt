package local.phantom.companion.core.truth

/**
 * «Твоє підтвердження побачили N людей». Лічиться, скільки РІЗНИХ людей
 * дивились на факт, поки свідчення людини ще входило в його довіру (до 2
 * півжиттів або до її наступного свідчення). Хто саме — не віддається ніколи.
 *
 * [viewerToken] — непрозорий знак дня (сіль доби на сервері), не ключ людини:
 * по ньому можна не рахувати одного глядача двічі, але не можна склеїти його дні.
 */
class ImpactCounter {
    private val views = HashMap<FactKey, MutableMap<String, MutableList<Long>>>()

    fun view(key: FactKey, viewerToken: String, atMs: Long) {
        views.getOrPut(key) { HashMap() }.getOrPut(viewerToken) { ArrayList() } += atMs
    }

    fun reach(contribution: Observation, selfToken: String?, supersededAtMs: Long?, nowMs: Long): Int {
        val horizon = contribution.atMs + (Truth.STALE_AFTER_HALF_LIVES * contribution.key.kind.halfLifeMs).toLong()
        val until = minOf(nowMs, horizon, supersededAtMs ?: Long.MAX_VALUE)
        return views[contribution.key].orEmpty().count { (token, times) ->
            token != selfToken && times.any { it >= contribution.atMs && it <= until }
        }
    }

    companion object {
        /**
         * Точне мале число видає людей: «тебе побачила 1 людина» в селі на 40
         * дворів — це імʼя. Тому 1–4 — «кілька» (повертається 1), далі вниз до
         * кратного 5, від сотні — до кратного 10.
         */
        fun band(n: Int): Int = when {
            n <= 0 -> 0
            n < 5 -> 1
            n < 100 -> n / 5 * 5
            else -> n / 10 * 10
        }
    }
}
