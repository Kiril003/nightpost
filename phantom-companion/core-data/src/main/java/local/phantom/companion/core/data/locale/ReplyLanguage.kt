package local.phantom.companion.core.data.locale

import android.content.Context
import java.util.Locale

/**
 * Якою мовою говорить помічник — і все, що лягає в розмову від його імені.
 *
 * Збірка 16, домівка українською, а під нею: «I couldn't find “olena” among
 * the people in your chats.» Рядок брався з ресурсів за мовою СИСТЕМИ, а
 * відповідь лягає в месенджер, який говорить мовою застосунку. Половина
 * екрана однією мовою, речення помічника — іншою.
 *
 * Правило: мова, яку людина обрала саме для Nightpost ([AppLanguage]: перший
 * екран або Профіль › Мова). Збірка 18 (власник, 08.10): не обирала —
 * англійська, та сама мова, що вже обрана на першому екрані. До Android 13
 * вибір лежить у налаштуваннях застосунку, а не в мові системи.
 */
object ReplyLanguage {

    /** Вибір → мова відповіді. Мова без перекладу — англійська за замовчуванням. */
    fun locale(picked: Locale?): Locale = (AppLanguage.of(picked) ?: AppLanguage.DEFAULT).locale

    /** Що людина обрала для застосунку; null — не обирала. */
    fun picked(context: Context): Locale? = AppLanguage.chosen(context)?.locale

    /** Контекст, з якого беруться рядки відповіді. Мова вже та — той самий контекст. */
    fun context(base: Context): Context = AppLanguage.localized(base, AppLanguage.current(base))
}
