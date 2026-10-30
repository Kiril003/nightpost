package local.phantom.companion.feature.stream.ui.map.dossier

import androidx.compose.ui.graphics.Color

/**
 * P3 — міст між чистою таблицею категорій і Compose.
 *
 * Значок сюди більше не входить: шапка досьє носить знак родини
 * [local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyph], той
 * самий, що й пін під пальцем. Власна таблиця Material Outlined, яка стояла
 * тут, робила з однієї аптеки дві різні аптеки — на мапі одну, у картці іншу.
 */

/**
 * Акцент категорії. [appAccent] — акцент застосунку; він і є «default=amber»
 * з брифу, тож [DossierCategory.PLACE] свідомо не заводить ще один жовтий у
 * палітру, а бере той, що вже живе в темі.
 */
internal fun DossierCategory.accent(appAccent: Color): Color =
    if (usesAppAccent) appAccent else Color(accentArgb)
