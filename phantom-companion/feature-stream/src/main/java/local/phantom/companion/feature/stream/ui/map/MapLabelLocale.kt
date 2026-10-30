package local.phantom.companion.feature.stream.ui.map

import android.content.Context
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer

/**
 * ЛАТИНКА ДЛЯ ТОГО, ХТО НЕ ЧИТАЄ КИРИЛИЦІ.
 *
 * Гонтлет-1, персона «турист без української»: мапа, кожен підпис якої —
 * кирилиця, для нього не мапа, а орнамент. Це був єдиний блокер, через який
 * ціла категорія людей не могла користуватись продуктом узагалі.
 *
 * Три рішення, і кожне навмисне:
 *
 * 1. **Стиль не перепікається.** Обсидіан народжується українським
 *    (`coalesce(name:uk, name)`), і так і лишається: це наш ґрунт, наша країна
 *    і наш замовчуваний читач. Чужа мова — це ПРОХІД поверх готового стилю,
 *    рівно для того випадку, коли телефон каже, що його власник читає інше.
 * 2. **Жодної транслітерації від себе.** Ми не вигадуємо «Khreshchatyk» з
 *    «Хрещатика» — беремо те, що написали мапери: `name:en`, далі `name:latin`
 *    (так це поле зветься у векторних тайлах OpenMapTiles), далі `int_name`.
 *    Якщо латинки немає, лишається кирилиця: чесне «не знаю» замість
 *    машинного припущення, яке не вимовить ні водій, ні перехожий.
 * 3. **Свої шари не чіпаємо.** Ознаки з `phantom-` джерел несуть одне поле
 *    `name`, яке ми ж і зібрали; переписати його на `name:en` означало б
 *    стерти підписи власних міток дочиста.
 */

/** Мови, для яких кирилиця мапи — рідне письмо, а не перешкода. */
internal val CYRILLIC_READERS = setOf("uk", "ru", "be", "bg", "mk", "sr")

/** Чи читає власник телефона кирилицю. Питання до МОВИ, не до країни. */
internal fun readsCyrillic(language: String): Boolean =
    language.lowercase().substringBefore('-').substringBefore('_') in CYRILLIC_READERS

internal fun deviceReadsCyrillic(context: Context): Boolean {
    val locales = context.resources.configuration.locales
    if (locales.isEmpty) return true
    return readsCyrillic(locales[0].language)
}

/**
 * Порядок полів для чужинця — це і є правило: спершу справжня англійська
 * назва, потім латинка векторного тайла, потім міжнародна, і аж тоді рідна.
 * `name` перед `name:uk` навмисне: у світі за межами України саме воно частіше
 * і є латинкою.
 */
internal val LATIN_NAME_FIELDS = listOf("name:en", "name:latin", "int_name", "name", "name:uk")

/** Поле підпису для чужинця, зібране з [LATIN_NAME_FIELDS]. */
internal fun latinTextField(): Expression = Expression.coalesce(
    *(LATIN_NAME_FIELDS.map { Expression.get(it) } + Expression.literal("")).toTypedArray(),
)

/**
 * Прохід мовою підпису. Порожній для кирилічного читача — стиль уже говорить
 * його мовою, і другий запис лише зніс би те, що [applyGroundCartography] і
 * Обсидіан поклали навмисне.
 */
internal fun applyLabelLocale(style: Style, cyrillicReader: Boolean) {
    if (cyrillicReader) return
    val field = latinTextField()
    style.layers.forEach { layer ->
        if (layer !is SymbolLayer) return@forEach
        if (layer.id.startsWith(PHANTOM_LAYER_PREFIX)) return@forEach
        // Один зіпсований шар не має права лишити людину без мапи — той самий
        // захист, що в ґрунтовому проході.
        runCatching {
            // Без цієї перевірки щити доріг (`{ref}`) і номери будинків
            // почали б писати назву вулиці замість числа.
            if (!labelsAName(symbolTextField(layer))) return@runCatching
            layer.setProperties(
                PropertyFactory.textField(field),
                // Разом із полем — і стек шрифту: сервер гліфів не тримає
                // замовчування MapLibre, і підпис просто не намалювався б.
                PropertyFactory.textFont(MAP_TEXT_FONT),
            )
        }
    }
}

private fun symbolTextField(layer: SymbolLayer): String? {
    val field = layer.textField
    return if (field.isExpression) field.expression?.toString() else field.value?.toString()
}

/**
 * Імʼя МІСЦЯ для чужинця — те саме правило, але для наших власних пінів:
 * їхні назви прийшли з `poiDisplayName`, тобто вже українськими, а сирі теги
 * місця ще при них.
 *
 * [fallback] — те, що вирішив репозиторій. Він і лишається, коли латинки немає:
 * порожній підпис гірший за кирилічний.
 */
internal fun latinPlaceName(tags: Map<String, String>, fallback: String): String =
    sequenceOf("name:en", "int_name")
        .mapNotNull { tags[it]?.trim() }
        .firstOrNull { it.isNotBlank() }
        ?: fallback
