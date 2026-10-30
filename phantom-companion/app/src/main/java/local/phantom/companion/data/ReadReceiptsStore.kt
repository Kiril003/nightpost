package local.phantom.companion.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** «Надсилати звіти про прочитання» — взаємно: вимкнене ховає й чужі. Типово увімкнено. */
class ReadReceiptsStore(context: Context) {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun set(on: Boolean) {
        _enabled.value = on
        prefs.edit().putBoolean(KEY, on).apply()
    }

    private companion object {
        const val FILE_NAME = "phantom_read_receipts_v1"
        const val KEY = "send"
    }
}
