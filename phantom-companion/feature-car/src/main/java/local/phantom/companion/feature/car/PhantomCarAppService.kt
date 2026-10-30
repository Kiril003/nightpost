package local.phantom.companion.feature.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

internal fun <V> carHostValidator(debuggable: Boolean, allowAll: () -> V, allowlist: () -> V): V =
    if (debuggable) allowAll() else allowlist()

/**
 * HIGHWAY — PHANTOM's second head.
 *
 * The Android Auto host starts this process itself, whenever the phone is
 * plugged into a car, with no Activity of ours running and usually with the
 * phone's screen off. Everything it needs therefore arrives through
 * [CarPorts]; see [ports].
 */
class PhantomCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator = carHostValidator(
        debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0,
        allowAll = { HostValidator.ALLOW_ALL_HOSTS_VALIDATOR },
    ) {
        HostValidator.Builder(applicationContext)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session = PhantomCarSession(sessionInfo)

    override fun onCreateSession(): Session = PhantomCarSession(null)

    companion object {
        /**
         * The seam. `:app` sets this once, from its container, before any car
         * host can connect — the container outlives every screen, which is
         * exactly the property the car head needs and an Activity cannot offer.
         *
         * A `lateinit`-style global is not elegant, and it is the honest shape
         * of the problem: the host constructs this Service, so there is no
         * constructor to inject through, and the alternative (a Service that
         * reaches into `:app` directly) would invert the dependency and drag
         * Compose into a module that must not have it.
         */
        @Volatile
        var ports: CarPorts? = null
    }
}

/**
 * One connection to a car (main head unit or digital instrument cluster).
 *
 * The Session is created on connect and destroyed on unplug; it holds no state
 * of its own beyond the screen stack, because the drive it is showing does not
 * belong to it.
 */
class PhantomCarSession(
    private val sessionInfo: SessionInfo? = null,
) : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        val ports = PhantomCarAppService.ports
        val isCluster = sessionInfo?.displayType == SessionInfo.DISPLAY_TYPE_CLUSTER

        // Cluster displays MUST ONLY render NavigationTemplate.
        if (isCluster) {
            return CarNavigationScreen(carContext, ports, isCluster = true)
        }

        if (ports != null && acceptNavigate(intent, ports)) {
            return CarNavigationScreen(carContext, ports, isCluster = false)
        }
        return if (ports == null) {
            // `:app` never wired the seam — a programming error, not an
            // operator one. Say so plainly on the car screen rather than
            // crashing the host's process, which is what an NPE here would do.
            CarMessageScreen(carContext, "Nightpost ще не готовий")
        } else if (
            local.phantom.companion.core.nav.GuidanceHub.state.value
                is local.phantom.companion.core.nav.GuidanceState.Guiding
        ) {
            // Plugged in mid-drive: the head unit opens on the road, not on a
            // browse grid the driver would have to navigate past.
            CarNavigationScreen(carContext, ports, isCluster = false)
        } else {
            CarHomeScreen(carContext, ports)
        }
    }

    /**
     * Dusk, a tunnel, the driver flipping the head unit to night. Templates
     * redraw themselves; the drawn surface is not a template and would keep the
     * old palette until something else happened to move.
     */
    override fun onCarConfigurationChanged(newConfiguration: android.content.res.Configuration) {
        val top = runCatching {
            carContext.getCarService(androidx.car.app.ScreenManager::class.java).top
        }.getOrNull()
        (top as? CarNavigationScreen)?.onCarConfigurationChanged()
    }

    /**
     * A drive already running when the car is plugged in must appear on the
     * head unit without the operator hunting for it.
     */
    override fun onNewIntent(intent: Intent) {
        val ports = PhantomCarAppService.ports
        val asked = ports != null && acceptNavigate(intent, ports)
        if (asked ||
            local.phantom.companion.core.nav.GuidanceHub.state.value
            is local.phantom.companion.core.nav.GuidanceState.Guiding
        ) {
            carContext.getCarService(androidx.car.app.ScreenManager::class.java)
                .push(CarNavigationScreen(carContext, ports))
        }
    }

    /**
     * The host — or the car's assistant — telling us where to go. A certified
     * navigation app is expected to obey this; ignoring it is the difference
     * between «a navigation app» and «an app with a map in it».
     */
    private fun acceptNavigate(intent: Intent, ports: CarPorts): Boolean {
        if (intent.action != CarContext.ACTION_NAVIGATE) return false
        val target = CarNavigateIntent.parse(intent.dataString) ?: return false
        lifecycleScope.launch {
            when (target) {
                is CarNavigateIntent.Target.Point -> ports.navigateTo(
                    CarPlace(
                        id = 0L,
                        name = target.label ?: CarCopy.DESTINATION,
                        lat = target.lat,
                        lon = target.lon,
                        distanceMeters = 0.0,
                    ),
                )
                is CarNavigateIntent.Target.Query -> {
                    // No coordinate came with the words; the offline gazetteer
                    // is the only thing that can turn them into one, and it is
                    // the same one the car's own search uses.
                    val here = ports.currentLocation() ?: return@launch
                    ports.search(
                        query = target.text,
                        lat = here.lat,
                        lon = here.lon,
                        radiusMeters = CarCategory.RADIUS_METERS,
                        subcategories = emptyList(),
                        limit = 1,
                    ).firstOrNull()?.let { ports.navigateTo(it) }
                }
            }
        }
        return true
    }
}

/** A dead end that does not crash the car. */
internal class CarMessageScreen(
    carContext: CarContext,
    private val message: String,
) : Screen(carContext) {
    override fun onGetTemplate(): androidx.car.app.model.Template =
        androidx.car.app.model.MessageTemplate.Builder(message)
            .setTitle("Nightpost")
            .setHeaderAction(androidx.car.app.model.Action.APP_ICON)
            .build()
}
