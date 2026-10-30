package local.phantom.companion.feature.stream.domain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Distance and clock — the facts that make an overview about this person now. */
object BriefingFacts {

    /** «350 м» / «2.4 км» — the precision worth saying out loud, no more. */
    fun distance(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): String {
        val metres = metresBetween(fromLat, fromLon, toLat, toLon)
        return if (metres < 1_000) "${(metres / 10).toInt() * 10} м"
        else String.format(Locale("uk"), "%.1f км", metres / 1_000)
    }

    fun metresBetween(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(toLat - fromLat)
        val dLon = Math.toRadians(toLon - fromLon)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(fromLat)) * cos(Math.toRadians(toLat)) *
            sin(dLon / 2).let { it * it }
        return 2 * r * asin(sqrt(a).coerceIn(0.0, 1.0))
    }

    /** «14:05, середа» — so opening hours and «зараз» mean something. */
    fun clock(nowMs: Long): String =
        SimpleDateFormat("HH:mm, EEEE", Locale("uk")).format(Date(nowMs))

    fun minuteClock(minuteOfDay: Int): String {
        val m = ((minuteOfDay % 1440) + 1440) % 1440
        return String.format(Locale("uk"), "%02d:%02d", m / 60, m % 60)
    }

    fun openLine(open: Boolean, boundaryMinuteOfDay: Int?): String = when {
        open && boundaryMinuteOfDay != null -> "відчинено, зачиняється о ${minuteClock(boundaryMinuteOfDay)}"
        open -> "відчинено"
        boundaryMinuteOfDay != null -> "зачинено, відчиняється о ${minuteClock(boundaryMinuteOfDay)}"
        else -> "зачинено"
    }

    /** null для будь-якого значення поза yes/no — модель не отримує здогад. */
    fun feeLine(fee: String?): String? = when (fee) {
        "yes" -> "платно"
        "no" -> "безкоштовно"
        else -> null
    }

    fun similarLine(count: Int, nearestName: String?, nearestMetres: Double?): String? {
        if (count <= 0) return null
        val head = "$count поруч"
        if (nearestName.isNullOrBlank() || nearestMetres == null) return head
        val d = if (nearestMetres < 1_000) "${(nearestMetres / 10).toInt() * 10} м"
        else String.format(Locale("uk"), "%.1f км", nearestMetres / 1_000)
        return "$head, найближче — $nearestName за $d"
    }

    const val LABEL_FEE = "Оплата: "
    const val LABEL_FROM_OPERATOR = "Від оператора: "
    const val LABEL_NOW = "Зараз: "
    const val LABEL_STATE = "Стан: "
    const val LABEL_SIMILAR = "Схоже поруч: "
    const val QUESTION = "Що це і чим корисне саме зараз?"

    const val NOTE_IDENTITY_GUESSED =
        "Назву місця вгадано за дорожньою сіткою, а не з довідника — не будуй на ній тверджень."
}
