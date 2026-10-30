package local.phantom.companion.core.design.theme

import local.phantom.companion.core.design.tokens.PhantomAccent
import local.phantom.companion.core.design.tokens.PhantomAccents
import local.phantom.companion.core.design.tokens.PhantomColors

/** Профіль › Вигляд › Акцент: вибір із переліку tokens.json; невідоме імʼя — перший (бурштин). */
val ACCENT_DEFAULT: String = PhantomAccents.first().id

fun accentOf(id: String?): PhantomAccent = PhantomAccents.firstOrNull { it.id == id } ?: PhantomAccents.first()

/**
 * Міняє лише ролі акценту; тло, текст і тони людей лишаються палітрою теми.
 * Перший акцент — власний бурштин палітри: точка сонця лишає свої ролі, як у вебі.
 */
fun PhantomColors.withAccent(id: String?): PhantomColors {
    val choice = accentOf(id)
    if (choice == PhantomAccents.first()) return this
    val t = choice.let { if (isDark) it.night else it.day }
    return copy(
        accent = t.accent,
        accentHover = t.accentHover,
        accentPress = t.accentPress,
        accentText = t.accentText,
        accentLine = t.accentLine,
        onAccent = t.onAccent,
    )
}

/** Старі поля спеки, що є акцентом (таблиця мосту в PhantomTheme.kt). */
fun PhantomThemeSpec.withAccent(colors: PhantomColors): PhantomThemeSpec = copy(
    primary = colors.accent,
    primarySoft = colors.accentPress,
    primaryDeep = colors.accentText,
    accentSharp = colors.accentText,
)
