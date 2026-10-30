package local.phantom.companion.feature.stream.ui.map

/** Тони мапи стилю D. Джерело — showcase-out/map-x/kit.css: m-route, m-halo, m-label-min, m-roof. */
internal object MapTokens {
    const val ROUTE_INK_DAY = "#1E1A15"
    const val ROUTE_INK_NIGHT = "#ECEAE5"
    const val ROUTE_HALO_DAY = "#F6F1E7"
    const val ROUTE_HALO_NIGHT = "#121316"
    const val ROUTE_ALT_DAY = "#776D60"
    const val ROUTE_ALT_NIGHT = "#8E8B85"
    const val ROOF_DAY = "#D4C9B8"
    const val ROOF_NIGHT = "#3A3E47"

    fun routeInk(isDark: Boolean): String = if (isDark) ROUTE_INK_NIGHT else ROUTE_INK_DAY
    fun routeHalo(isDark: Boolean): String = if (isDark) ROUTE_HALO_NIGHT else ROUTE_HALO_DAY
    fun roof(isDark: Boolean): String = if (isDark) ROOF_NIGHT else ROOF_DAY
}

/** Роль лінії маршруту. Координатор теми не знає — колір вирішує шар у [routeColor]. */
internal enum class RouteRole(val id: String) {
    ROUTE("route"),
    ALT("alt"),
    ACCESS("access"),
    INDOOR("indoor"),
    ;

    companion object {
        fun of(id: String?): RouteRole? = entries.firstOrNull { it.id == id }
    }
}

/** Пішохідні й внутрішні відрізки відрізняє штрих, не барва. */
internal fun routeColor(role: RouteRole, isDark: Boolean): String = when (role) {
    RouteRole.ROUTE, RouteRole.ACCESS, RouteRole.INDOOR -> MapTokens.routeInk(isDark)
    RouteRole.ALT -> if (isDark) MapTokens.ROUTE_ALT_NIGHT else MapTokens.ROUTE_ALT_DAY
}
