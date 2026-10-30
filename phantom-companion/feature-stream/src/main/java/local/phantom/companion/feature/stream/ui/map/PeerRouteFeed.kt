package local.phantom.companion.feature.stream.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import local.phantom.companion.feature.stream.ui.map.peers.PeerRouteFigure
import local.phantom.companion.feature.stream.ui.map.peers.peerRouteGeoJson
import local.phantom.companion.feature.stream.ui.map.peers.peerRouteLabel

/**
 * Межа між листом і малюнком.
 *
 * Живе ТУТ, а не в `peers/`, і це не смак: той каталог не сміє знати `core.net`
 * навіть іменем типу ([PeerLayerFirewallTest] боронить це коренем). Тож дріт
 * перетворюється на фігуру рівно на цьому порозі — далі вглиб іде вже малюнок.
 */
@Composable
internal fun rememberPeerRoutesGeoJson(viewModel: MapViewModel): String {
    val marks by viewModel.peerRoutes.collectAsStateWithLifecycle()
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val res = LocalContext.current.resources

    val figures = remember(marks, res) {
        marks.map { m ->
            PeerRouteFigure(
                peerId = m.peerId,
                label = peerRouteLabel(res, m.name),
                shape = m.route.shape,
                expiresAtMs = m.route.expiresAtMs,
            )
        }
    }

    LaunchedEffect(figures) {
        nowMs = System.currentTimeMillis()
        // Спимо рівно до найближчого строку. На порожньому списку сну немає
        // взагалі, а після останнього спливу цикл закінчується сам.
        var next = figures.filter { it.expiresAtMs > nowMs }.minOfOrNull { it.expiresAtMs }
        while (next != null) {
            delay((next - System.currentTimeMillis()).coerceAtLeast(PEER_ROUTE_MIN_WAIT_MS))
            nowMs = System.currentTimeMillis()
            viewModel.sweepPeerRoutes()
            next = figures.filter { it.expiresAtMs > nowMs }.minOfOrNull { it.expiresAtMs }
        }
    }

    return remember(figures, nowMs) { peerRouteGeoJson(figures, nowMs) }
}

/**
 * Найкоротший сон. Годинник тут прокидається НА СТРОК, а не за розкладом:
 * маршрут не рухається зовсім, тож між листом і його спливом ніякої новини
 * немає, і будильник щочверть хвилини витрачав би батарею на тишу. Підлога
 * потрібна лише щоб строк, який уже минув, не крутив цикл без сну.
 */
private const val PEER_ROUTE_MIN_WAIT_MS = 1_000L
