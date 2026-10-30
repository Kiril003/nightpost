package local.phantom.companion.core.solana

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import local.phantom.companion.core.design.component.SwitchRow

/**
 * Кому адреса гаманця їде сама. Адреса відкриває баланс і всю історію рахунку, тож не
 * кожному, хто є в контактах, а лише тим, з ким є розмова 1:1, — або всім, якщо людина
 * так вирішила. Той, кому платять або хто платить, отримує її при першій такій дії.
 */
object AddressSharing {
    private var prefs: SharedPreferences? = null
    private val _withAll = MutableStateFlow(false)
    val withAll: StateFlow<Boolean> = _withAll.asStateFlow()

    fun install(context: Context) {
        val p = context.applicationContext.getSharedPreferences("phantom_solana", Context.MODE_PRIVATE)
        prefs = p
        _withAll.value = p.getBoolean(K_ALL, false)
    }

    fun setWithAll(on: Boolean) {
        prefs?.edit()?.putBoolean(K_ALL, on)?.apply()
        _withAll.value = on
    }

    fun recipients(people: List<String>, talked: Set<String>, withAll: Boolean): List<String> =
        if (withAll) people else people.filter { it in talked }

    private const val K_ALL = "share_address_with_all"
}

/** Перемикач у плиті гаманця; пояснення — реченням під нею, як у налаштуваннях. */
@Composable
internal fun AddressSharingSection() {
    val all by AddressSharing.withAll.collectAsState()
    WalletGroup(title = null, explainer = stringResource(R.string.sol_share_all_hint), padded = false) {
        SwitchRow(label = stringResource(R.string.sol_share_all), checked = all, onChange = AddressSharing::setWithAll)
    }
}
