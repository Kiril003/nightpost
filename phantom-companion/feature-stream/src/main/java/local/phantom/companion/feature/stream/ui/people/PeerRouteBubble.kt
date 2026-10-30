package local.phantom.companion.feature.stream.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.LocalStateAccent
import local.phantom.companion.core.design.theme.PhantomCaption
import local.phantom.companion.core.design.theme.PhantomDataText
import local.phantom.companion.core.design.theme.PhantomProse
import local.phantom.companion.core.design.theme.PhantomRadii
import local.phantom.companion.core.design.theme.PhantomSpacings
import local.phantom.companion.core.design.theme.PhantomTouchFloor
import local.phantom.companion.core.net.peer.PeerLive
import local.phantom.companion.feature.stream.R

/**
 * Чужий маршрут у розмові — і одна дія над ним.
 *
 * «Їхати цим маршрутом» НЕ бере чужу геометрію за істину: вона планує від
 * СВОГО положення до тих самих точок наявним планувальником. Різниця не
 * формальна — чужа ламана намальована з чужого початку, чужим профілем і в
 * чужу хвилину; поїхати нею означало б їхати з місця, де тебе немає.
 *
 * Числа — те, що сказав відправник, і подані як його слова: свого ETA тут
 * порахувати нема з чого, а видати чуже за своє гірше, ніж не сказати.
 *
 * Після [PeerLive.Route.expiresAtMs] рядок каже про це ВГОЛОС. Дію лишено
 * навмисно: строк вийшов у ПЛАНУ, а не в МІСЦЬ. «Їхати цим маршрутом» ніколи
 * не бере чужу геометрію — вона віддає точки відправника власному
 * планувальнику, і той рахує зараз, по сьогоднішніх дорогах, від того місця,
 * де людина стоїть. Прибрати дію означало б змусити переписувати координати
 * рукою з картки, яка їх показує, — і сховати те, що лишається правдою.
 * Тому змінюються слова й вага, а не наявність.
 */
@Composable
fun PeerRouteBubble(
    route: PeerLive.Route,
    mine: Boolean,
    onTake: ((PeerLive.Route) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val theme = LocalPhantomTheme.current
    val live = LocalStateAccent.current.color

    var age by remember(route.expiresAtMs) {
        mutableStateOf(peerRouteAge(route.expiresAtMs, System.currentTimeMillis()))
    }
    // Одне пробудження на весь строк: рядок мусить перестати брехати й тоді,
    // коли розмову тримають відкритою через ту саму мить.
    LaunchedEffect(route.expiresAtMs) {
        val left = route.expiresAtMs - System.currentTimeMillis()
        if (left > 0L) delay(left)
        age = peerRouteAge(route.expiresAtMs, System.currentTimeMillis())
    }
    val expired = age == PeerRouteAge.EXPIRED
    // Протухлий план не має тягнути око так само, як свіжий: колір стану
    // належить тому, що ще діє.
    val accent = if (expired) theme.inkSecondary else live

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(PhantomRadii.MediumShape)
            .background(if (mine) live.copy(alpha = 0.15f) else theme.glassPanel.copy(alpha = 0.65f))
            .border(
                1.dp,
                if (mine) live.copy(alpha = 0.4f) else theme.glassBorder,
                PhantomRadii.MediumShape,
            )
            .padding(PhantomSpacings.Base),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.20f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.AltRoute,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(PhantomSpacings.Small))
            Column(modifier = Modifier.weight(1f)) {
                PhantomProse(
                    text = stringResource(
                        if (expired) R.string.peer_route_title_expired else R.string.peer_route_title,
                    ),
                    color = theme.inkStrong,
                )
                PhantomCaption(
                    text = stringResource(
                        R.string.peer_route_facts,
                        distanceLabel(route.distanceM),
                        (route.durationS / 60L).coerceAtLeast(1L).toInt(),
                    ),
                    color = theme.inkSecondary,
                )
            }
        }

        Spacer(Modifier.height(PhantomSpacings.Small))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(PhantomRadii.SmallShape)
                .background(theme.surfaceDeep.copy(alpha = 0.45f))
                .padding(horizontal = PhantomSpacings.Small, vertical = 6.dp),
        ) {
            PhantomDataText(
                text = "%.5f, %.5f → %.5f, %.5f".format(
                    java.util.Locale.US,
                    route.waypoints.first().first,
                    route.waypoints.first().second,
                    route.waypoints.last().first,
                    route.waypoints.last().second,
                ),
            )
        }

        if (onTake == null || mine) return@Column
        Spacer(Modifier.height(PhantomSpacings.Small))
        Box(
            modifier = Modifier
                .heightIn(min = PhantomTouchFloor)
                .fillMaxWidth()
                .clip(PhantomRadii.Pill)
                .background(accent.copy(alpha = 0.08f))
                .border(1.dp, accent.copy(alpha = 0.22f), PhantomRadii.Pill)
                .clickable { onTake(route) }
                .padding(horizontal = PhantomSpacings.Base, vertical = PhantomSpacings.Small),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PhantomProse(
                    text = stringResource(
                        if (expired) R.string.peer_route_take_expired else R.string.peer_route_take,
                    ),
                    color = accent,
                )
                PhantomCaption(
                    text = stringResource(R.string.peer_route_taken),
                    color = theme.inkSecondary,
                )
            }
        }
    }
}

/** Метри до кілометра — метрами: «0,3 км» на 340 м нічого не додає. */
@Composable
private fun distanceLabel(distanceM: Double): String =
    if (distanceM < 1_000.0) {
        stringResource(R.string.peer_route_dist_m, distanceM.toInt())
    } else {
        stringResource(
            R.string.peer_route_dist_km,
            "%.1f".format(java.util.Locale("uk", "UA"), distanceM / 1_000.0),
        )
    }

/** Чи вийшов строк надісланого маршруту. Порівняння одне на всі поверхні. */
internal enum class PeerRouteAge { LIVE, EXPIRED }

/**
 * Строк — МЕЖА листа, тож рівно на [expiresAtMs] маршрут уже протух: та сама
 * межа, за якою його викидає приймач ([local.phantom.companion.core.net.peer
 * .PeerLiveInbox.absorbRoute]) і за якою його перестає малювати мапа. Три
 * поверхні з трьома різними «майже» — це три різні правди про один лист.
 */
internal fun peerRouteAge(expiresAtMs: Long, nowMs: Long): PeerRouteAge =
    if (expiresAtMs <= nowMs) PeerRouteAge.EXPIRED else PeerRouteAge.LIVE
