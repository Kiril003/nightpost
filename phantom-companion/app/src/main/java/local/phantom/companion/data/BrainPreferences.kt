package local.phantom.companion.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import local.phantom.companion.core.ai.RoutingStrategy

/**
 * Чий мозок відповідає — вибір людини, а не константа в лямбді.
 *
 * До 04.09 [AppContainer] передавав роутерові `{ RoutingStrategy.CloudFirst }`
 * літералом: `LocalFirst` і `LocalOnly` існували в коді й були недосяжні з
 * жодного екрана. Поки ключа немає, це нічого не міняло — без ключа хмара
 * взагалі не кандидат. Але щойно людина вводить ключ, продукт мовчки починає
 * питати хмару першою, не спитавши її. Вибір, зроблений за людину і схований
 * у коді, — це те саме мовчазне рішення, що й обрізане імʼя чи вигаданий стан.
 *
 * Тут він стає явним і збереженим. Типове значення не змінене (`CloudFirst`):
 * зміна замовчування — окреме рішення власника, а не побічний наслідок того,
 * що вибір нарешті видно.
 *
 * Сховище звичайне, не шифроване: це не секрет, а налаштування. Секрети
 * лежать в [AiKeyStore] за `EncryptedSharedPreferences`.
 */
class BrainPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _routing = MutableStateFlow(read())

    /** Порядок, у якому роутер питатиме мозки. Читається на кожен запит. */
    val routing: StateFlow<RoutingStrategy> = _routing.asStateFlow()

    /** Зберігає вибір і віддає його негайно — роутер бачить наступний запит. */
    fun setRouting(value: RoutingStrategy) {
        prefs.edit().putString(KEY_ROUTING, tag(value)).apply()
        _routing.value = value
    }

    private fun read(): RoutingStrategy = when (prefs.getString(KEY_ROUTING, null)) {
        TAG_LOCAL_FIRST -> RoutingStrategy.LocalFirst
        TAG_LOCAL_ONLY -> RoutingStrategy.LocalOnly
        TAG_CLOUD_ONLY -> RoutingStrategy.CloudOnly
        else -> DEFAULT
    }

    private companion object {
        const val PREFS_NAME = "phantom_brain_prefs"
        const val KEY_ROUTING = "routing_strategy"

        // Теги, а не порядкові номери: `RoutingStrategy` — sealed interface,
        // і додавання стану посередині не має мовчки перемкнути чийсь вибір.
        const val TAG_CLOUD_FIRST = "cloud_first"
        const val TAG_LOCAL_FIRST = "local_first"
        const val TAG_LOCAL_ONLY = "local_only"
        const val TAG_CLOUD_ONLY = "cloud_only"

        val DEFAULT: RoutingStrategy = RoutingStrategy.CloudFirst

        fun tag(value: RoutingStrategy): String = when (value) {
            RoutingStrategy.CloudFirst -> TAG_CLOUD_FIRST
            RoutingStrategy.LocalFirst -> TAG_LOCAL_FIRST
            RoutingStrategy.LocalOnly -> TAG_LOCAL_ONLY
            RoutingStrategy.CloudOnly -> TAG_CLOUD_ONLY
        }
    }
}
