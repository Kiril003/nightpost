package local.phantom.companion.feature.stream.ui.map

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.pluralStringResource
import local.phantom.companion.feature.stream.ui.map.around.AroundPlace
import local.phantom.companion.feature.stream.ui.map.around.PeekPlaces
import local.phantom.companion.feature.stream.ui.map.around.PeekPlacesStrip
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.component.SheetWindowBars
import local.phantom.companion.core.design.theme.PhantomPalette
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.peers.PeerAvatar
import local.phantom.companion.core.design.R as DesignR

internal fun selfInitialOf(displayName: String?): String? =
    displayName?.trim()?.firstOrNull()?.uppercase()

@Composable
internal fun MapSearchField(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    initials: String? = null,
    avatar: (@Composable () -> Unit)? = null,
    inCard: Boolean = false,
) {
    val c = LocalPhantomColors.current
    val shape = RoundedCornerShape(PhantomRadius.control)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PhantomSize.input)
            .clip(shape)
            .then(if (inCard) Modifier else Modifier.background(c.raised).border(1.dp, c.line, shape))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = PhantomSpace.s4, end = PhantomSpace.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(DesignR.drawable.lc_search),
            contentDescription = null,
            tint = c.text3,
            modifier = Modifier.size(PhantomSize.iconM),
        )
        Spacer(Modifier.width(PhantomSpace.s3))
        Text(
            text = stringResource(R.string.map_home_search_hint),
            style = PhantomText.body,
            color = c.text3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(PhantomSpace.s2))
        if (avatar != null) {
            avatar()
        } else {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(c.overlay),
                contentAlignment = Alignment.Center,
            ) {
                if (initials != null) {
                    Text(initials, style = PhantomText.caption, color = c.text)
                } else {
                    Icon(painterResource(DesignR.drawable.lc_profile), null, tint = c.text2, modifier = Modifier.size(PhantomSize.iconM))
                }
            }
        }
    }
}

/**
 * Пошук і чипи над мапою. Колонка без бічних полів: пошук бере поле сам, а
 * рядок чипів іде на всю ширину й заїжджає під край екрана (поле — всередині
 * прокрутки). Вставку статус-бару вже спожив корінь хрому; тут — повітря під ним.
 */
@Composable
internal fun MapHomeTopBand(
    showSearch: Boolean,
    onSearch: () -> Unit,
    initials: String?,
    modifier: Modifier = Modifier,
    chips: @Composable () -> Unit,
) {
    val c = LocalPhantomColors.current
    val shape = RoundedCornerShape(PhantomRadius.card)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = PhantomSpace.s3, end = PhantomSpace.s3, top = PhantomSpace.s3, bottom = PhantomSpace.s2)
            .clip(shape)
            .background(c.raised)
            .border(1.dp, c.line, shape),
    ) {
        if (showSearch) {
            MapSearchField(onClick = onSearch, initials = initials, inCard = true)
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        }
        chips()
    }
}

@Composable
internal fun MapHomeCategoryBar(
    chips: List<MapHomeCategoryChip>,
    activeLabel: String?,
    searchAreaVisible: Boolean,
    onSelectChip: (MapHomeCategoryChip, String) -> Unit,
    onSearchArea: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPhantomColors.current
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .testTag(TAG_MAP_HOME_CHIPS)
                .padding(horizontal = PhantomSpace.s1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            chips.forEach { chip ->
                val label = stringResource(chip.labelRes)
                val selected = activeLabel == label
                Column(
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .clickable(role = Role.Button) { onSelectChip(chip, label) }
                        .padding(horizontal = PhantomSpace.s3),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Spacer(Modifier.height(PhantomSpace.s1))
                    Text(
                        text = label,
                        style = PhantomText.subhead,
                        color = if (selected) c.text else c.text2,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(PhantomSpace.s1))
                    Box(Modifier.width(20.dp).height(2.dp).background(if (selected) c.text else Color.Transparent))
                }
            }
        }
        if (searchAreaVisible) {
            Spacer(Modifier.height(PhantomSpace.s1))
            Box(
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .clip(RoundedCornerShape(PhantomRadius.control))
                    .border(1.dp, c.lineStrong, RoundedCornerShape(PhantomRadius.control))
                    .clickable(role = Role.Button, onClick = onSearchArea)
                    .testTag(TAG_MAP_SEARCH_AREA_CHIP)
                    .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s1),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.map_search_this_area),
                    style = PhantomText.caption,
                    color = c.text,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun MapLocateFab(
    permissionGranted: Boolean,
    followActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPhantomColors.current
    val desc = stringResource(
        if (permissionGranted) R.string.map_me else R.string.map_state_allow_location,
    )
    val fill = c.raised
    val ink = if (followActive && permissionGranted) c.accent else c.text
    Box(
        modifier = modifier
            .size(MapChromeMetrics.Control)
            .clip(RoundedCornerShape(PhantomRadius.control))
            .background(fill)
            .border(1.dp, c.lineStrong, RoundedCornerShape(PhantomRadius.control))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = desc }
            .testTag(TAG_MAP_LOCATE_FAB),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(DesignR.drawable.lc_locate),
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(PhantomSize.iconM),
        )
        if (!permissionGranted) {
            Box(
                modifier = Modifier
                    .width(PhantomSize.iconM)
                    .height(2.dp)
                    .rotate(-45f)
                    .background(c.danger),
            )
        }
    }
}

@Composable
internal fun MapHomePeekBar(
    sharing: Int,
    activeCategory: String?,
    people: List<MapTrayPerson>,
    onOpenAround: () -> Unit,
    onPersonClick: (MapTrayPerson) -> Unit,
    onPlansClick: () -> Unit,
    onMeetClick: () -> Unit,
    places: PeekPlaces,
    onPlace: (AroundPlace) -> Unit,
    collapsed: Boolean = false,
    onCollapsedChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val c = LocalPhantomColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = PhantomRadius.sheet, topEnd = PhantomRadius.sheet))
            .background(c.raised)
            .testTag("map-around-peek")
            .animateContentSize(spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
            .padding(bottom = PhantomSpace.s3),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val handleLabel = stringResource(if (collapsed) R.string.map_peek_expand else R.string.map_peek_collapse)
        Box(
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                .pointerInput(collapsed) {
                    var dragged = 0f
                    detectVerticalDragGestures(
                        onDragStart = { dragged = 0f },
                        onDragEnd = { onCollapsedChange(NearbyPeek.afterDrag(collapsed, dragged / density)) },
                    ) { change, dy ->
                        change.consume()
                        dragged += dy
                    }
                }
                .clickable(role = Role.Button, onClickLabel = handleLabel) { onCollapsedChange(!collapsed) }
                .testTag("map-around-peek-handle"),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(c.lineStrong),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onOpenAround)
                .padding(horizontal = PhantomSpace.gutterPhone),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = activeCategory ?: stringResource(R.string.map_around_title),
                style = PhantomText.headline,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            val shown = places as? PeekPlaces.Shown
            if (shown != null && activeCategory != null) {
                Spacer(Modifier.width(PhantomSpace.s2))
                Text(
                    text = pluralStringResource(R.plurals.map_peek_count, shown.total, shown.total),
                    style = PhantomText.callout,
                    color = c.text2,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            if (sharing > 0) {
                Text(
                    text = stringResource(R.string.map_around_sharing, sharing),
                    style = PhantomText.callout,
                    color = c.text2,
                    maxLines = 1,
                )
                Spacer(Modifier.width(PhantomSpace.s2))
            }
            if (shown != null) {
                Text(
                    text = stringResource(R.string.map_peek_all) + " ›",
                    style = PhantomText.callout,
                    color = c.text,
                    maxLines = 1,
                )
            }
        }
        if (!collapsed) {
            Spacer(Modifier.height(PhantomSpace.s2))
            PeekPlacesStrip(places = places, onPlace = onPlace)
            Spacer(Modifier.height(PhantomSpace.s3))
            MapInviteStrip(
                people = people,
                onPersonClick = onPersonClick,
                onPlansClick = onPlansClick,
                onMeetClick = onMeetClick,
            )
        }
    }
}

/** Labeled map actions: never an unlabeled icon column. */
@Composable
internal fun MapActionChip(
    label: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    active: Boolean = false,
    loud: Boolean = false,
) {
    val c = LocalPhantomColors.current
    val fill = when {
        loud -> c.danger
        active -> c.accent
        else -> c.raised
    }
    val ink = when {
        loud -> PhantomPalette.LoudInk
        active -> c.onAccent
        else -> c.text
    }
    Row(
        modifier = Modifier
            .heightIn(min = PhantomSize.buttonL)
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, if (loud || active) fill else c.line, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = PhantomSpace.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, tint = ink, modifier = Modifier.size(PhantomSize.iconM))
        Spacer(Modifier.width(PhantomSpace.s2))
        Text(label, style = PhantomText.subhead, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MapLayersSheet(
    onDismiss: () -> Unit,
    styles: @Composable () -> Unit,
    tools: @Composable () -> Unit,
    onOffline: (() -> Unit)?,
    offlineCount: Int,
    onCar: (() -> Unit)?,
) {
    val c = LocalPhantomColors.current
    // FR-8 (ніч 09.10, A52): аркуш відкривався наполовину — нижній ряд «Хто
    // мене бачить · ніхто» лягав під кнопки навігації, а відступ під ними
    // стоїть у кінці прокрутки, тож у пів-стані його не видно. Аркуш шарів
    // короткий: одразу повний, і останній ряд стоїть над смугою навігації.
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        // Розгорнутий аркуш доходив до самого верху вікна, і ручка лягала під
        // годинник і значки рядка стану (A52, 02.10). Аркуш зупиняється під ним.
        modifier = Modifier.statusBarsPadding(),
        containerColor = c.raised,
        scrimColor = c.scrim,
    ) {
        SheetWindowBars()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PhantomSpace.gutterPhone)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
        ) {
            Text(stringResource(R.string.map_layers), style = PhantomText.title2, color = c.text)
            SheetCaption(stringResource(R.string.map_sheet_style))
            styles()
            if (onOffline != null) {
                SheetRow(
                    label = if (offlineCount > 0) stringResource(R.string.map_sheet_saved) else stringResource(R.string.map_sheet_offline),
                    value = offlineCount.takeIf { it > 0 }?.toString(),
                    icon = DesignR.drawable.lc_download,
                    onClick = onOffline,
                )
            }
            if (onCar != null) {
                SheetRow(label = stringResource(R.string.map_sheet_car), icon = DesignR.drawable.lc_devices, onClick = onCar)
            }
            tools()
            Spacer(Modifier.height(PhantomSpace.s6))
        }
    }
}

@Composable
private fun SheetCaption(text: String) {
    Text(text, style = PhantomText.caption, color = LocalPhantomColors.current.text3)
}

@Composable
private fun SheetRow(label: String, @DrawableRes icon: Int, onClick: () -> Unit, value: String? = null) {
    val c = LocalPhantomColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(PhantomRadius.card))
            .background(c.overlay)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = PhantomSpace.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, tint = c.text2, modifier = Modifier.size(PhantomSize.iconM))
        Spacer(Modifier.width(PhantomSpace.s3))
        Text(label, style = PhantomText.body, color = c.text, modifier = Modifier.weight(1f))
        if (value != null) Text(value, style = PhantomText.callout, color = c.text3)
    }
}

/** The look of the ground as four labelled swatches, not a dropdown. */
@Composable
internal fun BasemapTiles(selected: MapBasemap?, onSelect: (MapBasemap?) -> Unit) {
    val c = LocalPhantomColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2)) {
        BASEMAP_CHOICES.forEach { (label, value) ->
            val on = selected == value
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(PhantomRadius.card))
                    .background(if (on) c.accentSoft else c.overlay)
                    .clickable(role = Role.RadioButton, onClick = { onSelect(value) })
                    .padding(PhantomSpace.s2),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val (ground, road) = swatch(value)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(PhantomRadius.control))
                        .background(ground)
                        .then(
                            if (on) Modifier.border(2.dp, c.accentLine, RoundedCornerShape(PhantomRadius.control))
                            else Modifier,
                        ),
                ) {
                    Box(Modifier.align(Alignment.Center).fillMaxWidth().height(4.dp).background(road))
                    Box(Modifier.align(Alignment.Center).width(4.dp).height(44.dp).background(road))
                }
                Spacer(Modifier.height(PhantomSpace.s1))
                Text(
                    stringResource(label),
                    style = PhantomText.caption,
                    color = if (on) c.text else c.text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun swatch(value: MapBasemap?): Pair<Color, Color> = when (value) {
    MapBasemap.DARK -> PhantomPalette.MapSwatchDarkGround to PhantomPalette.MapSwatchDarkLine
    MapBasemap.LIGHT -> PhantomPalette.MapSwatchLightGround to PhantomPalette.MapSwatchLightLine
    MapBasemap.MINIMAL -> PhantomPalette.MapSwatchLightGround to PhantomPalette.MapSwatchMinimalLine
    null -> if (LocalPhantomColors.current.isDark) PhantomPalette.MapSwatchDarkGround to PhantomPalette.MapSwatchPin
    else PhantomPalette.MapSwatchLightGround to PhantomPalette.MapSwatchPin
}
