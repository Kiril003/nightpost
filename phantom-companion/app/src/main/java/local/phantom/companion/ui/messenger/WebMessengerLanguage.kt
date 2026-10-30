package local.phantom.companion.ui.messenger

import android.content.Context
import android.content.res.Resources
import java.util.Locale
import local.phantom.companion.core.data.locale.AppLanguage

/**
 * Мова месенджера — та, що обрана в Nightpost (перший екран, Профіль › Мова).
 *
 * Веб має власний каталог рядків (`phantom-os/src/frontend/src/i18n`) і
 * читає мову з налаштування `ui_language`. До 09.10 на телефоні його не ставив
 * ніхто: власник обирав English, а розмови лишались українською.
 *
 * Два шляхи, як у теми: [WebMessengerBridge.getLanguage] — на завантаженні
 * сторінки, [webLanguagePushJs] — на зміну (хост штовхає, щойно вибір інший).
 * Бандл оголошує приймача сам: `services/nativeLanguage.ts`.
 */
internal const val WEB_LANGUAGE_SINK = "__phantomLanguage"

/** Мови каталогу вебу: uk/en/cs як у рідного боку, далі хвиля 1 (лінивий JSON, решта ключів англійською). */
internal val WEB_LOCALES: List<String> = listOf("uk", "en", "cs", "es", "pt-BR", "de", "fr", "pl", "tr", "it", "id")

/** Слово вебу для мови застосунку: теги збігаються з локалями каталогу (`uk`, `en`). */
internal fun webLanguageOf(language: AppLanguage): String = language.tag

/** Мова телефона → локаль вебу, або null, коли такої в каталозі немає. */
internal fun webTagOf(locale: Locale): String? = when (val lang = locale.language) {
    "pt" -> "pt-BR"
    "in", "id" -> "id"
    else -> lang.takeIf { it in WEB_LOCALES }
}

/**
 * Мова месенджера. Обрана в Nightpost — вона. Без вибору (або «Як у телефоні»)
 * — перша мова телефона, яку має веб: німецький телефон бачить месенджер
 * німецькою, хоч рідні екрани англійські (`values/`). Жодної — англійська, не українська.
 */
internal fun webLanguageFor(picked: AppLanguage?, phone: List<Locale>): String {
    if (picked != null) return webLanguageOf(picked)
    for (l in phone) webTagOf(l)?.let { return it }
    return "en"
}

internal fun webLanguage(context: Context): String {
    val picked = AppLanguage.chosen(context).takeUnless { AppLanguage.followsPhone(context) }
    val phone = Resources.getSystem().configuration.locales
    return webLanguageFor(picked, (0 until phone.size()).map { phone.get(it) })
}

/** Поштовх у вже відкриту сторінку; тег — лише з [WEB_LOCALES], тож лапки безпечні. */
internal fun webLanguagePushJs(tag: String): String {
    require(tag in WEB_LOCALES) { tag }
    return "window.$WEB_LANGUAGE_SINK && window.$WEB_LANGUAGE_SINK('$tag');"
}
