package local.phantom.companion.feature.stream.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.data.poi.PoiHit
import local.phantom.companion.core.data.poi.PoiRepository
import local.phantom.companion.core.data.poi.PoiVocabulary
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyph
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyphIcon
import local.phantom.companion.core.design.R as DesignR

// Рядки аркуша пошуку на v1.2 — та сама анатомія, що в групах Профілю
// (RowGroup/NavRow живуть у :app, звідси їх не видно, тож тут їхній двійник
// на тих самих токенах): піднятий аркуш, групи `overlay` з радіусом картки,
// волосина між рядками, назва body, підпис footnote, значки lc_*.

/** Мірки аркуша пошуку — лише те, чого немає в токенах. */
internal object SearchMetrics {
    /** Якір (дім/робота): назва й підпис у два рядки, значок 24. */
    val AnchorMin: Dp = 72.dp
}

/** Підпис секції: caption text3 над групою, з тим самим відступом, що в Профілі. */
@Composable
internal fun SectionLabel(text: String, tight: Boolean = false) {
    MapSectionCaption(
        text = text.replaceFirstChar { it.titlecase() },
        modifier = Modifier.padding(
            start = PhantomSpace.gutterPhone + PhantomSpace.s4,
            end = PhantomSpace.gutterPhone,
            top = if (tight) PhantomSpace.s3 else PhantomSpace.s5,
            bottom = PhantomSpace.s2,
        ),
    )
}

/**
 * Підпис секції плюс «очистити» — рідкісна руйнівна дія СЛОВОМ. Обвід чипа
 * робив із неї дію першого рівня, рівну «Укриттю»: обвід — це запрошення.
 */
@Composable
internal fun RecentHeader(onClearRecent: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = PhantomSpace.gutterPhone + PhantomSpace.s4,
                end = PhantomSpace.s2,
                top = PhantomSpace.s3,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        MapSectionCaption(stringResource(R.string.map_search_recent).replaceFirstChar { it.titlecase() })
        MapTextAction(
            label = stringResource(R.string.map_search_recent_clear).replaceFirstChar { it.titlecase() },
            onClick = onClearRecent,
            tone = MapTone.Neutral,
        )
    }
}

/**
 * A single horizontally-scrolling shelf — the whole group is reachable with
 * one thumb-drag, no matter how many chips it holds.
 */
@Composable
internal fun ShelfRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = PhantomSpace.gutterPhone),
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
    ) {
        content()
    }
}

/** Ярлик → повний запит до агента. Ярлик короткий, бо він на чипі. */
@Composable
internal fun askPrompts(): List<Pair<String, String>> = listOf(
    stringResource(R.string.map_search_ask_near) to stringResource(R.string.map_search_ask_near_prompt),
    stringResource(R.string.map_search_ask_where) to stringResource(R.string.map_search_ask_where_prompt),
    stringResource(R.string.map_search_ask_risks) to stringResource(R.string.map_search_ask_risks_prompt),
    stringResource(R.string.map_search_ask_food) to stringResource(R.string.map_search_ask_food_prompt),
    stringResource(R.string.map_search_ask_reach) to stringResource(R.string.map_search_ask_reach_prompt),
)

/**
 * Одна пропозиція, одна дія — і жодного вигаданого числа.
 *
 * РОЗМІРУ ТУТ НЕМАЄ НАВМИСНО. Єдине джерело ваги пакета — підписаний маніфест
 * каталогу, а в цій збірці каталог віддає `NotConfigured`: будь-які «221 МБ» у
 * цьому рядку були б вигадані. Тому рядок лише називає країну й веде на екран
 * завантажень, де вага стоїть із маніфесту — і де людина ще нічого не почала.
 */
@Composable
internal fun MissingPackOffer(pack: MissingPack, onOpenPacks: () -> Unit) {
    val c = LocalPhantomColors.current
    val lang = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]?.language
    val countryName = if (lang == "uk") {
        pack.nameUa.ifBlank { pack.nameEn }
    } else {
        pack.nameEn.ifBlank { pack.nameUa }
    }
    val sizeMb = pack.sizeMb
    val title = if (sizeMb != null) {
        stringResource(R.string.map_pack_banner_title, countryName, sizeMb)
    } else {
        stringResource(R.string.map_pack_banner_title_nosize, countryName)
    }
    LineRow(
        title = title,
        subtitle = stringResource(R.string.map_pack_download),
        subtitleColor = c.accentText,
        leading = { MapIcon(DesignR.drawable.lc_download, tint = c.accentText) },
        modifier = Modifier.padding(vertical = PhantomSpace.s1),
        onClick = onOpenPacks,
    )
}

/** Порожньо ≠ зламано: два стани — два тексти, і вихід із глухого кута. */
@Composable
internal fun NothingFound(query: String, gap: SearchGap?, onAskAi: () -> Unit) {
    val c = LocalPhantomColors.current
    Column(
        modifier = Modifier.padding(
            horizontal = PhantomSpace.gutterPhone,
            vertical = PhantomSpace.s5,
        ),
        verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
    ) {
        Text(
            text = stringResource(R.string.map_search_empty_title, query),
            style = PhantomText.headline,
            color = c.text,
        )
        // Порада мусить відповідати ПРИЧИНІ. «Спробуй категорію» на пристрої
        // без пакета країни — це відправити людину робити марну роботу: там
        // порожньо не тому, що вона не так спитала.
        Text(
            text = when (gap) {
                SearchGap.NO_PACK -> stringResource(R.string.map_search_empty_no_pack)
                SearchGap.NOT_IN_CORPUS -> stringResource(R.string.map_search_empty_not_in_corpus)
                null -> stringResource(R.string.map_search_empty_hint)
            },
            style = PhantomText.footnote,
            color = c.text3,
        )
        MapChip(
            label = stringResource(R.string.map_search_ask_about, query),
            onClick = onAskAi,
        )
    }
}

/** Пропозиція «показати всі на мапі»: картка групи, не бурштин — головна дія не вона. */
@Composable
internal fun FloodByNameCard(query: String, onClick: () -> Unit) {
    val name = stringResource(R.string.map_search_flood_title, query)
    GroupSlot(index = 0, count = 1, modifier = Modifier.padding(vertical = PhantomSpace.s1)) {
        MapListRow(
            title = name,
            subtitle = stringResource(R.string.map_search_flood_sub),
            leading = { PlaceLead() },
            onClick = onClick,
            spoken = name,
            titleMaxLines = 2,
        )
    }
}

@Composable
internal fun IntentBanner(
    cat: DiscoveryCat,
    alongRoute: Boolean,
    onFlood: () -> Unit,
    onHere: () -> Unit,
) {
    val c = LocalPhantomColors.current
    val verb = stringResource(
        if (alongRoute) R.string.map_search_along_route else R.string.map_search_nearby_verb,
    )
    val floodName = stringResource(R.string.map_search_intent_flood_desc, cat.label, verb)
    val hereName = stringResource(R.string.map_search_intent_here_desc, cat.label)
    val rows = if (alongRoute) 1 else 2
    GroupSlot(index = 0, count = rows, modifier = Modifier.padding(top = PhantomSpace.s1)) {
        MapListRow(
            title = "${cat.label} $verb",
            // Тактична примітка — застереження, не дія: warning, не бурштин.
            subtitle = cat.tacticalNote?.let { stringResource(it) }
                ?: stringResource(R.string.map_search_intent_flood_sub),
            subtitleColor = if (cat.tacticalNote != null) c.warning else c.text3,
            leading = {
                PhantomGlyphIcon(glyph = cat.glyph, tint = c.text2, modifier = Modifier.size(PhantomSize.iconM))
            },
            onClick = onFlood,
            spoken = floodName,
            titleMaxLines = 2,
        )
    }
    if (!alongRoute) {
        GroupSlot(index = 1, count = rows, modifier = Modifier.padding(bottom = PhantomSpace.s1)) {
            MapListRow(
                title = stringResource(R.string.map_search_intent_here).replaceFirstChar { it.titlecase() },
                leading = { MapIcon(DesignR.drawable.lc_search, tint = c.accentText) },
                onClick = onHere,
                spoken = hereName,
                titleColor = c.accentText,
            )
        }
    }
}

/**
 * Комірка групи рядків у ледачому списку.
 *
 * Група — один `overlay`-прямокутник із радіусом картки, але рядки в ній —
 * окремі `item` LazyColumn (мітки, місця), тож форму несе кожна комірка сама:
 * перша закруглена згори, остання знизу, між ними — волосина від тексту.
 */
@Composable
internal fun GroupSlot(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    dividerInset: Dp = MapRowTextInset,
    content: @Composable () -> Unit,
) {
    val c = LocalPhantomColors.current
    val r = PhantomRadius.card
    val first = index == 0
    val last = index >= count - 1
    val shape = RoundedCornerShape(
        topStart = if (first) r else 0.dp,
        topEnd = if (first) r else 0.dp,
        bottomStart = if (last) r else 0.dp,
        bottomEnd = if (last) r else 0.dp,
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PhantomSpace.gutterPhone)
            .clip(shape)
            .background(c.overlay),
    ) {
        if (!first) MapRowDivider(inset = dividerInset)
        content()
    }
}

/** Знак місця — той самий ромб, що на мапі, у кольорі значків рядка. */
@Composable
internal fun PlaceLead() {
    PhantomGlyphIcon(
        glyph = PhantomGlyph.PLACE,
        tint = LocalPhantomColors.current.text2,
        modifier = Modifier.size(PhantomSize.iconM),
    )
}

/**
 * Анатомія рядка аркуша, одна на всі секції — рядок групи Профілю:
 * `[16][значок 20][16][назва body / підпис footnote][дія 48]`, мінімум 56dp.
 *
 * Диска під значком немає: значок, що повторюється в кожному рядку секції,
 * нічого не розрізняє — він лише додає маси зліва. [leading] `null` —
 * секція, де й самого значка не треба (нещодавнє).
 */
@Composable
internal fun LineRow(
    title: String,
    subtitle: String?,
    leading: (@Composable () -> Unit)? = null,
    index: Int = 0,
    count: Int = 1,
    subtitleColor: Color? = null,
    modifier: Modifier = Modifier,
    onRoute: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = LocalPhantomColors.current
    val spoken = listOfNotNull(title, subtitle?.takeIf { it.isNotBlank() }).joinToString(", ")
    GroupSlot(
        index = index,
        count = count,
        modifier = modifier,
        dividerInset = if (leading != null) MapRowTextInset else PhantomSpace.s4,
    ) {
        MapListRow(
            title = title,
            subtitle = subtitle,
            subtitleColor = subtitleColor ?: c.text3,
            leading = leading,
            onClick = onClick,
            spoken = spoken,
            titleMaxLines = 2,
            trailing = onRoute?.let { route ->
                {
                    // Стрілка в кожному рядку — не головна дія списку; бурштин лишається «Маршруту».
                    MapRowAction(
                        icon = DesignR.drawable.lc_locate,
                        contentDescription = stringResource(R.string.map_sheet_route_here),
                        onClick = route,
                    )
                }
            },
        )
    }
}

/**
 * What the row says under the name: the kind, the street, the distance — the
 * three things that decide whether this is the «Сільпо» he meant. A bare
 * «340 м» told him nothing about which of nine identical names it was.
 */
internal fun offlineSubtitle(h: PoiHit, here: String? = null): String {
    val kind = PoiVocabulary.label(h.subcategory, h.tags)
    val street = h.tags["addr:street"]?.let { s ->
        h.tags["addr:housenumber"]?.let { "$s, $it" } ?: s
    }
    // Країна — лише коли вона НЕ та, у якій людина стоїть. Список із двох
    // «Нових», у Словаччині й в Україні, без цього неможливо читати; а «UA»
    // на кожному рядку в Україні — шум, що нічого не розрізняє.
    val abroad = h.tags[PoiRepository.COUNTRY_TAG]
        ?.takeIf { it.isNotBlank() && !it.equals(here, ignoreCase = true) }
        ?.uppercase()
    return listOfNotNull(abroad, kind, street, metresLabel(h.distanceMeters)).joinToString(" · ")
}

internal fun distanceLabel(here: Pair<Double, Double>?, lat: Double, lon: Double): String? {
    val (hLat, hLon) = here ?: return null
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat - hLat)
    val dLon = Math.toRadians(lon - hLon)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(Math.toRadians(hLat)) * Math.cos(Math.toRadians(lat)) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
    return metresLabel(2 * r * Math.asin(Math.sqrt(a.coerceIn(0.0, 1.0))))
}

private fun metresLabel(m: Double): String =
    if (m < 1000) {
        MapText.s(R.string.mapl_i_metres, m.toInt()).ifEmpty { "${m.toInt()} m" }
    } else {
        val km = String.format(java.util.Locale.US, "%.1f", m / 1000.0)
        MapText.s(R.string.mapl_i_kilometres, km).ifEmpty { "$km km" }
    }
