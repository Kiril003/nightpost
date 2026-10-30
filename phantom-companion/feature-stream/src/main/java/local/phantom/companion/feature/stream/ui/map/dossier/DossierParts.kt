package local.phantom.companion.feature.stream.ui.map.dossier

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.theme.PhantomTouchFloor
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.SheetBody
import local.phantom.companion.feature.stream.ui.map.SheetChevron
import local.phantom.companion.feature.stream.ui.map.SheetNote
import local.phantom.companion.feature.stream.ui.map.SheetPill
import local.phantom.companion.feature.stream.ui.map.SheetSection
import local.phantom.companion.feature.stream.ui.map.SheetSmall

/**
 * Дрібні деталі досьє — стан слоту, пігулки, рядки фактів.
 *
 * Винесені з листа не заради краси файлу: кожна з них має відповідати на
 * питання «дані ще їдуть чи їх нема?», і поки вони жили всередині одного
 * 560-рядкового композабла, відповідь була одна на всіх — спінер.
 *
 * 03.10: медальйонна дія (`DossierIconAction`), пігулка стану
 * (`DossierStatusPill`) і власна залита кнопка досьє пішли — перших двох ніхто
 * не кликав, а головна дія має один рецепт на всі аркуші мапи
 * ([local.phantom.companion.feature.stream.ui.map.MapPrimaryAction]).
 */

/**
 * Чесний провал слоту. Не «щось пішло не так», а що саме не приїхало — і,
 * коли лист уміє перезапуск, кнопка. Решта картки лишається робочою.
 */
@Composable
internal fun DossierSlotFailure(text: String, accent: Color, onRetry: (() -> Unit)?) {
    val retryLabel = stringResource(R.string.dossier_retry)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
        modifier = Modifier.padding(vertical = PhantomSpace.s1),
    ) {
        SheetNote(text)
        if (onRetry != null) {
            Box(
                modifier = Modifier
                    .heightIn(min = PhantomTouchFloor)
                    .widthIn(min = PhantomTouchFloor)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onRetry)
                    .semantics(mergeDescendants = true) { contentDescription = retryLabel }
                    .padding(horizontal = PhantomSpace.s2),
                contentAlignment = Alignment.Center,
            ) {
                SheetBody(retryLabel, color = accent, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** Заголовок секції — один голос на всі групи досьє. */
@Composable
internal fun DossierSectionTitle(text: String, color: Color = Color.Unspecified) {
    if (color == Color.Unspecified) {
        SheetSection(text, Modifier.padding(top = PhantomSpace.s3))
    } else {
        SheetNote(text, Modifier.padding(top = PhantomSpace.s3), color = color, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Шеврон розкриття — вниз, коли згорнуто; вгору, коли відкрито. */
@Composable
internal fun DossierDisclosureChevron(expanded: Boolean, tint: Color) {
    SheetChevron(expanded, tint)
}

/**
 * Факт-пігулка («Wi-Fi», «24/7») — те, що вирішує «зайти чи ні».
 *
 * Факт не належить категорії, тож і не бере її відтінок: аптечний червоний на
 * слові «Wi-Fi» читався як попередження.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun DossierFactPill(text: String, tint: Color) {
    SheetPill(text)
}

/** Рядок «мітка — значення». Клікабельний лише тоді, коли клік щось робить. */
@Composable
internal fun DossierFactRow(
    label: String,
    value: String,
    valueColor: Color,
    onClick: (() -> Unit)? = null,
    /** Дрібний хвіст під значенням — те, що правдиве, але не головне. */
    detail: String? = null,
) {
    val c = LocalPhantomColors.current
    val spoken = "$label: " + listOfNotNull(value, detail).joinToString(", ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let {
                // Однаковий зріст для всіх рядків: клікабельний телефон не має
                // стояти вищим за адресу, інакше картка йде нерівним кроком.
                if (onClick != null) {
                    it.heightIn(min = PhantomTouchFloor).clickable(role = Role.Button, onClick = onClick)
                } else {
                    it.heightIn(min = PhantomTouchFloor)
                }
            }
            // Мітка й значення озвучуються одним рядком і в нечіпному випадку:
            // інакше TalkBack читає «Адреса», паузу, і адресу як окремий вузол.
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .padding(vertical = PhantomSpace.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SheetSmall(label, modifier = Modifier.width(FACT_LABEL_WIDTH))
        Column(Modifier.weight(1f)) {
            SheetBody(value, color = valueColor)
            detail?.let { SheetSmall(it, color = c.text3) }
        }
    }
}

/**
 * Геокодер віддає адресу ланцюгом з девʼяти ланок до країни включно; на картці
 * це було чотири рядки тіла (кадр 06.09). Перші три ланки — адреса, решта —
 * дрібним під нею.
 */
internal const val ADDRESS_HEAD_PARTS = 3

internal fun splitAddress(display: String): Pair<String, String?> {
    val parts = display.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.size <= ADDRESS_HEAD_PARTS) return display.trim() to null
    return parts.take(ADDRESS_HEAD_PARTS).joinToString(", ") to parts.drop(ADDRESS_HEAD_PARTS).joinToString(", ")
}

/** «Години роботи» в один рядок: вузька колонка ламала мітку на два. */
private val FACT_LABEL_WIDTH: Dp = 96.dp
