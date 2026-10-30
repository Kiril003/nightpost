package local.phantom.companion.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import local.phantom.companion.R
import local.phantom.companion.core.ai.RoutingStrategy
import local.phantom.companion.ui.home.ChoiceRow
import local.phantom.companion.ui.home.RowDivider
import local.phantom.companion.ui.home.Explainer

/**
 * Кого питати першим — вибір людини, не константа.
 *
 * До 04.09 порядок стояв літералом у контейнері, і `LocalFirst`/`LocalOnly`
 * існували в коді, недосяжні з жодного екрана. Потім вибір жив на старому
 * екрані налаштувань, куди вели лише двері «Для розробника». Тепер це рядки
 * розділу PHANTOM, поруч із ключем, від якого залежить половина варіантів.
 *
 * Наслідок показано лише під обраним варіантом: три речення поспіль читались
 * би як інструкція, а не як відповідь «що зараз буде з моїм питанням».
 */
@Composable
internal fun BrainOrderRows(
    routing: RoutingStrategy,
    onRoutingChange: (RoutingStrategy) -> Unit,
    keyStored: Boolean,
) {
    brainOrderChoices(routing).forEachIndexed { i, order ->
        if (i > 0) RowDivider()
        val chosen = order == routing
        ChoiceRow(
            label = stringResource(brainOrderLabel(order)),
            selected = chosen,
            body = if (chosen) stringResource(brainOrderBody(order)) else null,
            onSelect = { onRoutingChange(order) },
        )
    }
    if (!keyStored) {
        // Факт, а не здогад: без ключа хмара недоступна, хоч би що обрано.
        Explainer(stringResource(R.string.look_brain_no_key))
    }
}

/**
 * Що пропонується. «Лише хмара» не пропонується (так було й до переїзду), але
 * якщо вона вже збережена — показується, інакше жоден рядок не був би
 * позначений, і екран брехав би про поточний вибір мовчанням.
 */
internal fun brainOrderChoices(current: RoutingStrategy): List<RoutingStrategy> =
    listOf(RoutingStrategy.CloudFirst, RoutingStrategy.LocalFirst, RoutingStrategy.LocalOnly) +
        listOfNotNull(RoutingStrategy.CloudOnly.takeIf { current == it })

/** Слово для порядку, а не назва перелічення. */
@androidx.annotation.StringRes
internal fun brainOrderLabel(order: RoutingStrategy): Int = when (order) {
    RoutingStrategy.CloudFirst -> R.string.look_brain_cloud_first
    RoutingStrategy.LocalFirst -> R.string.look_brain_local_first
    RoutingStrategy.LocalOnly -> R.string.look_brain_local_only
    RoutingStrategy.CloudOnly -> R.string.look_brain_cloud_only
}

/** Одне речення правди про наслідок вибору. */
@androidx.annotation.StringRes
internal fun brainOrderBody(order: RoutingStrategy): Int = when (order) {
    RoutingStrategy.CloudFirst -> R.string.settings_brain_order_cloud_first_body
    RoutingStrategy.LocalFirst -> R.string.settings_brain_order_local_first_body
    RoutingStrategy.LocalOnly -> R.string.settings_brain_order_local_only_body
    RoutingStrategy.CloudOnly -> R.string.settings_brain_order_cloud_only_body
}
