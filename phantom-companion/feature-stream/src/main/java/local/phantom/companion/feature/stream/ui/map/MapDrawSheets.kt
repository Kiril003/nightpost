package local.phantom.companion.feature.stream.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import local.phantom.companion.core.data.places.DrawnLine
import local.phantom.companion.core.data.places.MarkName
import local.phantom.companion.core.data.spatial.GeoPoint
import local.phantom.companion.core.design.layout.readingGutter
import local.phantom.companion.core.design.theme.PhantomTouchFloor
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.geo.GeoMath
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyph
import local.phantom.companion.feature.stream.ui.map.icons.asImageVector

/**
 * Ламану домалювали — лишилось імʼя.
 *
 * Імʼя питають ТУТ, а не після збереження: сховище безіменну лінію не візьме
 * ([MarkName]), тож аркуш без поля імені показував би кнопку, яка мовчки не
 * працює. Довжина стоїть поруч із полем, бо саме вона відрізняє дві схожі
 * лінії, коли їх стане десяток.
 */
@Composable
internal fun DrawNamingSheet(
    points: List<GeoPoint>,
    accentColor: Color,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val c = LocalPhantomColors.current
    val res = LocalContext.current.resources
    var draft by remember { mutableStateOf("") }
    val nameFocus = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { nameFocus.requestFocus() }
    val named = MarkName.isNamed(draft)
    val fieldLabel = stringResource(R.string.map_draw_name_hint)

    Column(
        modifier = Modifier.drawSheetBody(),
        verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
    ) {
        SheetTitle(stringResource(R.string.map_draw_name_title))
        SheetNote(
            stringResource(R.string.map_line_length, fmtDistance(res, GeoMath.pathLengthM(points))),
            tnum = true,
        )
        SheetField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = fieldLabel,
            modifier = Modifier.padding(top = PhantomSpace.s2),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { if (named) onSave(MarkName.clean(draft)) },
            ),
            fieldModifier = Modifier
                .focusRequester(nameFocus)
                .semantics { contentDescription = fieldLabel },
        )
        if (named) {
            DrawSheetRow(
                label = stringResource(R.string.map_draw_save),
                ink = c.accentText,
                onClick = { onSave(MarkName.clean(draft)) },
            )
        } else {
            // Кнопки нема, і причина сказана словом — той самий закон, що й у мітки.
            SheetNote(stringResource(R.string.map_draw_unnamed))
        }
        DrawSheetRow(
            label = stringResource(R.string.map_draw_cancel),
            ink = c.text2,
            onClick = onCancel,
        )
    }
}

/**
 * Картка збереженої лінії: що це, яка завдовжки, і що з нею можна зробити.
 *
 * Нечитабельна лінія не вдає порожню: вона каже про себе словом і не пропонує
 * ані маршруту, ані правки — вести нікуди по вершинах, яких не прочитали.
 * Головна дія — «маршрут уздовж», єдина залита; решта — рядки словом.
 */
@Composable
internal fun DrawnLineCard(
    line: DrawnLine,
    accentColor: Color,
    onRouteAlong: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = LocalPhantomColors.current
    val res = LocalContext.current.resources

    Column(
        modifier = Modifier.drawSheetBody(),
        verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
    ) {
        SheetTitle(line.label)
        if (!line.readable) {
            SheetNote(stringResource(R.string.map_line_unreadable))
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2)) {
                SheetNote(
                    stringResource(R.string.map_line_length, fmtDistance(res, GeoMath.pathLengthM(line.points))),
                    tnum = true,
                )
                SheetNote("·", color = c.text3)
                SheetNote(stringResource(R.string.map_line_vertices, line.points.size), tnum = true)
            }
            MapPrimaryAction(
                PhantomGlyph.ROUTE_TO.asImageVector(),
                stringResource(R.string.map_line_route_along),
                onRouteAlong,
                modifier = Modifier.padding(top = PhantomSpace.s2),
            )
            DrawSheetRow(
                label = stringResource(R.string.map_line_edit),
                ink = c.text,
                onClick = onEdit,
            )
        }
        DrawSheetRow(
            label = stringResource(R.string.map_line_delete),
            ink = c.danger,
            onClick = onDelete,
        )
    }
}

@Composable
private fun Modifier.drawSheetBody(): Modifier = this
    .fillMaxWidth()
    .padding(horizontal = readingGutter(PhantomSpace.gutterPhone))
    .padding(top = PhantomSpace.s1, bottom = PhantomSpace.s6)

/** Рядок аркуша словом, без медальйона — та сама мова, що й у решті точок. */
@Composable
private fun DrawSheetRow(label: String, ink: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PhantomTouchFloor)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.CenterStart,
    ) {
        SheetBody(label, color = ink, maxLines = 1)
    }
}
