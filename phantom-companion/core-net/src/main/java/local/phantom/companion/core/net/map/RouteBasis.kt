package local.phantom.companion.core.net.map

import kotlin.math.roundToInt

/**
 * Три речі, які роутер знає про маршрут і які досі не доходили до жодного
 * екрана: звідки взялися хвилини, скільки шляху мало справжній знак, і чи веде
 * пішохідний варіант узбіччям магістралі.
 *
 * OSRM цих полів не дає взагалі — тоді [lines] порожній і не показується
 * НІЧОГО: мовчання чесніше за вигадану впевненість.
 */
object RouteBasis {

    /** Нижче цього магістраль у шляху — перехрестя, яке перетинають, а не хода
     *  узбіччям; дзеркалить LocalRouter.TRUNK_SHOULDER_NOTICE_M. */
    const val TRUNK_SHOULDER_NOTICE_M: Double = 150.0

    /**
     * `sidewalk` протеговано на 216 із 20 907 магістральних ways, тож «є
     * узбіччя» — твердження, якого офлайн-витяг підтвердити не може. Ніколи не
     * замінювати це формулювання на впевнене.
     */
    const val TRUNK_SHOULDER: String =
        "частина шляху — узбіччям магістралі, чи є узбіччя — невідомо"

    /**
     * БРІД. Формулювання тримає рівно ту саму межу, що й рядок про узбіччя
     * вище, і з тієї самої причини: сказано, ЩО ПОЗНАЧЕНО, а не що буде.
     *
     * «Проїзний», «прохідний», «глибина», «обережно» — усе це твердження про
     * майбутнє, яких дані не несуть: рівень води в OSM не записаний і не може
     * бути, бо змінюється за годину. Тому кінець рядка — «проїзність невідома»,
     * і замінювати його на впевнене формулювання не можна ніколи.
     */
    fun fordLine(count: Int): String? = when {
        count <= 0 -> null
        count == 1 -> "на шляху позначено брід — проїзність невідома"
        else -> "на шляху позначено броди ($count) — проїзність невідома"
    }

    fun lines(variant: RouteVariant): List<String> = buildList {
        speedLine(variant.speedBasis, variant.postedShare)?.let { add(it) }
        if (variant.trunkShoulderMeters >= TRUNK_SHOULDER_NOTICE_M) add(TRUNK_SHOULDER)
        fordLine(variant.fordCrossings)?.let { add(it) }
    }

    fun summary(variant: RouteVariant): String? =
        lines(variant).takeIf { it.isNotEmpty() }?.joinToString(" · ")

    private fun speedLine(basis: String, postedShare: Double): String? {
        val share = postedShare.coerceIn(0.0, 1.0)
        // 0 % на екрані, коли знак таки був, — брехня в інший бік.
        val pct = (share * 100).roundToInt().coerceAtLeast(if (share > 0.0) 1 else 0)
        val signs = if (pct > 0) ", знаки на $pct% шляху" else ", знаків нема"
        return when (basis) {
            "POSTED" -> "час — за знаками на дорозі"
            "CLASS" -> "час — за класом дороги$signs"
            "ASSUMED" -> "час — припущення$signs"
            else -> null
        }
    }
}
