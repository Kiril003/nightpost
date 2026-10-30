package local.phantom.companion.feature.stream.ui.report

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.serialization.json.Json

/**
 * Вхідна точка типу віджета `report` — єдине, що знає про звіт решта
 * Stream.
 *
 * ## Дві поверхні, один віджет
 *
 * У потоці чату віджет — це [ReportHandoffCard]. Повноекранний
 * [ReportSurface] відкривається **звідси ж**, локальним станом, а не
 * маршрутом навігації. Причина в [ReportSurface]: `Dialog` накривається
 * поверх живої композиції чату, тому закриття повертає оператора в ту
 * саму позицію стрічки — не «приблизно ту», а буквально ту, бо список
 * чату за цей час не переміряли.
 *
 * Побічний наслідок, який варто назвати вголос: віджет не потребує жодних
 * правок у `PhantomNavGraph` чи `AppContainer`. Звіт вмикається однією
 * гілкою в `WidgetRenderer` і одним рядком у каталозі системного промпту.
 *
 * ## Розбір — один раз на payload
 *
 * `remember(payload)` тримає розібраний документ, доки не зміниться сам
 * payload. Розбір восьмирозділового звіту на кожній рекомпозиції стрічки
 * чату (а вона перескладається на кожен токен потокової відповіді) зʼїв
 * би кадр там, де його найменше можна віддати.
 *
 * @param payload JSON звіту (див. [ReportPayload])
 * @param json спільний інстанс Stream — навмисно **не** використовується:
 *   блоки звіту розрізняються полем `block`, а не `type`, тож розбір
 *   веде власний інстанс. Параметр лишається в сигнатурі заради
 *   однорідності з рештою 42 віджетів.
 */
@Composable
fun ReportWidget(
    payload: String,
    @Suppress("UNUSED_PARAMETER") json: Json,
    modifier: Modifier = Modifier,
) {
    val doc = remember(payload) { ReportPayload.parse(payload) }
    var open by remember(payload) { mutableStateOf(false) }

    // Порожній титул І порожній зміст = розібрати не вдалося взагалі.
    // Документ із титулом, але без даних — інша річ: він відкривається і
    // чесно каже, що записів немає.
    if (doc.title.isBlank() && doc.sections.isEmpty() && doc.headline.isEmpty()) {
        ReportBrokenCard(modifier = modifier)
        return
    }

    ReportHandoffCard(doc = doc, modifier = modifier, onOpen = { open = true })

    if (open) {
        ReportSurface(doc = doc, onClose = { open = false })
    }
}
