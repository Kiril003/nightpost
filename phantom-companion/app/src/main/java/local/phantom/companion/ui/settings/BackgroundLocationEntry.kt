package local.phantom.companion.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import local.phantom.companion.R
import local.phantom.companion.core.design.R as DesignR
import local.phantom.companion.core.design.permission.PermissionReason
import local.phantom.companion.core.design.permission.rememberPermissionGate
import local.phantom.companion.feature.stream.R as StreamR
import local.phantom.companion.ui.home.GrantRow

/**
 * СЛІД ПОЗА ЕКРАНОМ — «дозволити завжди».
 *
 * Власник питав прямо, чи збереться слід у поїздці, і чи не «стоїть точка на
 * місці». Без цього дозволу Android віддає позицію лише доки екран увімкнений
 * або доки крутиться служба переднього плану. Тобто телефон у кишені під час
 * переїзду пише **діри**, і жодне повідомлення про це не зʼявляється.
 *
 * Сам рядок у store не показується: там немає ACCESS_BACKGROUND_LOCATION, і
 * викликач ставить його під `Distribution.backgroundLocation`.
 */
@Composable
internal fun BackgroundLocationEntry() {
    val context = LocalContext.current
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

    val fineHeld = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }
    val bgHeld = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    var fine by remember { mutableStateOf(fineHeld()) }
    var background by remember { mutableStateOf(bgHeld()) }

    val askFine = rememberPermissionGate(
        permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION),
        reason = PermissionReason(
            DesignR.drawable.lc_locate,
            StreamR.string.perm_location_map_title,
            StreamR.string.perm_location_map_body,
        ),
    )
    val askBackground = rememberPermissionGate(
        permissions = listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        reason = PermissionReason(DesignR.drawable.lc_locate, R.string.perm_bg_loc_title, R.string.perm_bg_loc_body),
    )

    GrantRow(
        label = stringResource(R.string.bg_loc_title),
        body = stringResource(
            when {
                background -> R.string.bg_loc_on
                !fine -> R.string.bg_loc_needs_fine
                else -> R.string.bg_loc_off
            },
        ),
        granted = background,
        grantedWord = stringResource(R.string.look_granted),
        grantWord = stringResource(R.string.look_grant),
        onGrant = {
            when {
                !fine -> askFine.request {
                    fine = it
                    background = bgHeld()
                }
                // З API 30 системний діалог «дозволити завжди» більше не
                // зʼявляється: єдина дорога — сторінка дозволів застосунку.
                // Кидати сюди запит було б тихою відмовою.
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                else -> askBackground.request { background = it }
            }
        },
    )
}
