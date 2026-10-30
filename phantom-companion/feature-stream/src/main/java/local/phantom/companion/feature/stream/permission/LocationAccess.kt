package local.phantom.companion.feature.stream.permission

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.location.LocationManagerCompat
import local.phantom.companion.core.design.R as DesignR
import local.phantom.companion.core.design.permission.GateCard
import local.phantom.companion.core.design.permission.PermissionGate
import local.phantom.companion.core.design.permission.PermissionReason
import local.phantom.companion.core.design.permission.rememberPermissionGate
import local.phantom.companion.feature.stream.R

val LOCATION_PERMISSIONS = listOf(
    android.Manifest.permission.ACCESS_FINE_LOCATION,
    android.Manifest.permission.ACCESS_COARSE_LOCATION,
)

fun locationServicesOn(context: Context): Boolean = runCatching {
    LocationManagerCompat.isLocationEnabled(context.getSystemService(LocationManager::class.java))
}.getOrDefault(true)

/**
 * Дозвіл на місце є, а перемикач «Місце» в шторці телефона вимкнено — фікса не
 * буде, скільки не чекай. Брама дозволу цього не бачить, тому питання окреме.
 */
class LocationAccess internal constructor(
    private val gate: PermissionGate,
    private val servicesOn: () -> Boolean,
    private val showServicesOff: () -> Unit,
) {
    fun granted(): Boolean = gate.granted()

    fun servicesOn(): Boolean = servicesOn.invoke()

    fun promptServices() = showServicesOff()

    fun request(onAnswer: (Boolean) -> Unit) = gate.request { ok ->
        if (ok && !servicesOn()) showServicesOff()
        onAnswer(ok)
    }

    fun requestThen(action: () -> Unit) = gate.request { ok ->
        if (!ok) return@request
        if (servicesOn()) action() else showServicesOff()
    }
}

@Composable
fun rememberLocationAccess(reason: PermissionReason): LocationAccess {
    val context = LocalContext.current
    val gate = rememberPermissionGate(permissions = LOCATION_PERMISSIONS, reason = reason, anyOf = true)
    var off by remember { mutableStateOf(false) }
    if (off) {
        GateCard(
            icon = DesignR.drawable.lc_locate,
            title = stringResource(R.string.perm_location_off_title),
            body = stringResource(R.string.perm_location_off_body),
            primary = stringResource(DesignR.string.perm_open_settings),
            secondary = stringResource(DesignR.string.perm_not_now),
            onPrimary = {
                off = false
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
            onSecondary = { off = false },
        )
    }
    return remember(gate) { LocationAccess(gate, { locationServicesOn(context) }, { off = true }) }
}
