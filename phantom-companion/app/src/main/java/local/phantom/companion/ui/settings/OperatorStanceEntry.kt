package local.phantom.companion.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import local.phantom.companion.PhantomApp
import local.phantom.companion.R
import local.phantom.companion.core.data.country.CountryPackRegistry
import local.phantom.companion.core.data.country.OperatorRole
import local.phantom.companion.ui.home.ActionRow
import local.phantom.companion.ui.home.RowDivider
import local.phantom.companion.ui.home.RowTone

/**
 * ДЕ ТИ Й ХТО ТИ — рішення, яке доти не мало де показатись.
 *
 * `CountryPackRegistry.setOverride` існував і не мав жодного виклику: країну
 * можна було тільки вгадати з фікса. Роль не існувала взагалі, і тому швидкий
 * доступ мапи пропонував «Укриття» людині в Словаччині.
 *
 * Обидва рядки перемикаються дотиком по колу; праворуч — поточне значення,
 * тому воно тихого кольору, а не бурштину дії.
 */
@Composable
internal fun OperatorStanceEntry() {
    val context = LocalContext.current
    val prefs = remember { (context.applicationContext as PhantomApp).container.mapPrefs }
    val role by prefs.operatorRole.collectAsStateWithLifecycle()
    val override by prefs.countryOverride.collectAsStateWithLifecycle()

    val countryValue = override
        ?: stringResource(R.string.stance_country_auto, CountryPackRegistry.current()?.id.orEmpty())

    ActionRow(
        label = stringResource(R.string.stance_country_title),
        body = stringResource(R.string.stance_country_body),
        action = countryValue,
        tone = RowTone.Quiet,
        onClick = {
            val packs = CountryPackRegistry.packs.map { it.id }
            val next = when (override) {
                null -> packs.firstOrNull()
                else -> packs.getOrNull(packs.indexOf(override) + 1)
            }
            prefs.setCountryOverride(next)
            CountryPackRegistry.setOverride(next)
        },
    )
    RowDivider()
    ActionRow(
        label = stringResource(R.string.stance_role_title),
        body = stringResource(R.string.stance_role_body),
        action = stringResource(roleWord(role)),
        tone = RowTone.Quiet,
        onClick = {
            val all = OperatorRole.entries
            prefs.setOperatorRole(all[(all.indexOf(role) + 1) % all.size])
        },
    )
}

private fun roleWord(role: OperatorRole): Int = when (role) {
    OperatorRole.CIVILIAN -> R.string.stance_role_civilian
    OperatorRole.VOLUNTEER -> R.string.stance_role_volunteer
    OperatorRole.SERVICE -> R.string.stance_role_service
}
