package local.phantom.companion.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import local.phantom.companion.R
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.sensor.nav.MockPosition
import local.phantom.companion.service.FalsePositionService
import local.phantom.companion.ui.home.ActionRow
import local.phantom.companion.ui.home.ProfileDialog
import local.phantom.companion.ui.home.ProfileField
import local.phantom.companion.ui.home.RowTone

/**
 * ХИБНА ПОЗИЦІЯ для чужих застосунків.
 *
 * Це не налаштування навігації, а межа приватності — що саме цей телефон
 * розповідає про людину чужим застосункам. Живе в «Для розробника», бо сама
 * підміна вимагає розробницького перемикача «застосунок фіктивної локації».
 *
 * **Три стани, і жоден не мовчить.** Вимкнено — сказано, що світ бачить
 * правду. Ввімкнено — показано САМІ координати, які віддаються, бо «увімкнено»
 * без числа не дає перевірити себе. Відмовлено — названо причину й дано
 * дорогу до перемикача, а не глухе «не вдалося».
 *
 * Координати вводить людина. Готових точок тут навмисно немає: список країв,
 * куди зручно «переїхати», перетворив би інструмент приватності на інструмент
 * обходу — а ми домовились підміняти СВОЮ позицію, не ламати чужі перевірки.
 *
 * У store служби підміни немає, тож викликач ставить рядок під
 * `Distribution.falsePosition`.
 */
@Composable
internal fun FalsePositionEntry() {
    val context = LocalContext.current
    val state by MockPosition.state.collectAsStateWithLifecycle()
    var asking by remember { mutableStateOf(false) }
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }

    when (val s = state) {
        is MockPosition.State.Showing -> ActionRow(
            label = stringResource(R.string.false_position_row_title),
            body = stringResource(R.string.false_position_row_on, s.lat, s.lon),
            action = stringResource(R.string.false_position_row_stop),
            tone = RowTone.Danger,
            onClick = { FalsePositionService.stop(context) },
        )

        is MockPosition.State.Refused -> ActionRow(
            label = stringResource(R.string.false_position_row_title),
            body = s.reason,
            action = stringResource(R.string.false_position_row_open_dev),
            onClick = {
                // Ведемо саме в розробницькі: там живе «застосунок фіктивної
                // локації». Загальний екран застосунку тут не показав би
                // нічого й читався б як глухий кут.
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        )

        MockPosition.State.Off -> ActionRow(
            label = stringResource(R.string.false_position_row_title),
            body = stringResource(R.string.false_position_row_off),
            action = stringResource(R.string.false_position_row_set),
            onClick = { asking = true },
        )
    }

    if (asking) {
        ProfileDialog(
            title = stringResource(R.string.false_position_dialog_title),
            body = stringResource(R.string.false_position_dialog_body),
            confirm = stringResource(R.string.false_position_row_set),
            // Кнопка гасне, поки числа непридатні: краще недоступна дія, ніж
            // дія, що мовчки нічого не робить.
            confirmEnabled = parse(lat) != null && parse(lon) != null,
            onConfirm = {
                val la = parse(lat)
                val lo = parse(lon)
                if (la != null && lo != null) {
                    FalsePositionService.show(context, la, lo)
                    asking = false
                }
            },
            onDismiss = { asking = false },
        ) {
            ProfileField(
                value = lat,
                onValueChange = { lat = it },
                hint = stringResource(R.string.false_position_lat),
                keyboardType = KeyboardType.Decimal,
            )
            Spacer(Modifier.height(PhantomSpace.s2))
            ProfileField(
                value = lon,
                onValueChange = { lon = it },
                hint = stringResource(R.string.false_position_lon),
                keyboardType = KeyboardType.Decimal,
            )
        }
    }
}

/** Кома як десятковий роздільник — те, що людина набере на українській клавіатурі. */
private fun parse(raw: String): Double? =
    raw.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
