package local.phantom.companion.feature.stream.ui.map.dossier

import local.phantom.companion.feature.stream.ui.map.mapRaised
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import local.phantom.companion.core.design.layout.readingGutter
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.theme.PhantomTouchFloor
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.net.map.OpeningHours
import local.phantom.companion.core.net.map.PlaceDossierClient
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.MapActionTile
import local.phantom.companion.feature.stream.ui.map.MapPrimaryAction
import local.phantom.companion.feature.stream.ui.map.MapSecondaryAction
import local.phantom.companion.feature.stream.ui.map.MapTileRow
import local.phantom.companion.feature.stream.ui.map.PhantomMarkGlyph
import local.phantom.companion.feature.stream.ui.map.SheetBody
import local.phantom.companion.feature.stream.ui.map.SheetNote
import local.phantom.companion.feature.stream.ui.map.SheetSmall
import local.phantom.companion.feature.stream.ui.map.SheetText
import local.phantom.companion.feature.stream.ui.map.SheetTitle
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyph
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyphIcon
import local.phantom.companion.feature.stream.ui.map.icons.asImageVector
import local.phantom.companion.feature.stream.ui.map.icons.glyph
import local.phantom.companion.feature.stream.ui.map.venue.PlaceVenueSection
import local.phantom.companion.core.design.R as DesignR
import local.phantom.companion.feature.stream.ui.map.MapText

/**
 * NAV-4 / PRECISION — картка досьє, три рівні зверху вниз.
 *
 *  • ПІК — бейдж, назва, ОДИН інфо-рядок («вид · поверх · за розкладом стан ·
 *    відстань») і ОДИН ряд дій. 119dp до першого факту замість 305: у пік
 *    (≈⅓ екрана) тепер влазять і перші факти, а не половина ряду кнопок.
 *  • ТІЛО — факти, вміст ТРЦ / маршрути зупинки, і лише ПІСЛЯ них огляд від
 *    PHANTOM: людина тапнула МІСЦЕ, а не асистента.
 *  • ГЛИБИНА — переліки і подробиці, розкриття в місці.
 *
 * Чого тут БІЛЬШЕ НЕМА:
 *  • градієнтної коробки шапки (32dp повітря навколо 48dp змісту);
 *  • окремого ряду «відстань/статус» і статус-чіпа — вони сегменти інфо-рядка;
 *  • підписів під іконними діями (ряд 48dp замість смуги 57dp);
 *  • плити-тизера ШІ над фактами місця;
 *  • FlowRow «пігулок» маршрутів — зупинка отримала список із частотами;
 *  • голого «Відчинено»: `opening_hours` знає розклад, а не блекаут.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlaceDossierContent(
    dossier: PlaceDossier?,
    accentColor: Color,
    /** Огляд від PHANTOM — стан потоку токенів ПРЯМО в цю картку. */
    briefing: DossierBriefing?,
    onBrief: () -> Unit,
    onBriefRetry: () -> Unit,
    onRoute: () -> Unit,
    // NAV-Ω1 — «заїхати по дорозі»: протягнути АКТИВНИЙ маршрут через це
    // місце (супровід не переривається). Пропонується лише поки маршрут живий.
    onVia: (() -> Unit)? = null,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onOpenExternal: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onDial: (String) -> Unit,
    onShowLine: (PlaceDossierClient.RouteLineRef) -> Unit,
    /** Лінія з активу пристрою — геометрія є, релації Overpass нема. */
    onShowLocalLine: ((DossierTransitLine) -> Unit)? = null,
    onRetarget: (DossierTarget) -> Unit,
    /**
     * P1 — ціль тапу. Дозволяє намалювати пік ще до першого знімка досьє:
     * назва, категорія і «Маршрут» є в руках одразу.
     */
    target: DossierTarget? = null,
    /** Позиція оператора для «відстань · ETA»; null — сегмента нема. */
    origin: Pair<Double, Double>? = null,
    /** Перезапуск збірки, коли слот здався. Null — просто чесний прочерк. */
    onRetry: (() -> Unit)? = null,
    /** «Що тут зараз» — свідчення людей про це місце; null — блоку нема. */
    hereNow: local.phantom.companion.feature.stream.ui.map.here.HereNowState? = null,
    onHereReport: (kindWire: String, readingWire: String) -> Unit = { _, _ -> },
    /**
     * Табло відправлень для зупинки. Типово — живе ([DepartureBoardSection],
     * Transitous); кадр підставляє готові рядки, щоб не ходити в мережу.
     */
    departureBoard: @Composable (lat: Double, lon: Double, airport: Boolean) -> Unit = { lat, lon, airport ->
        DepartureBoardSection(lat = lat, lon = lon, airport = airport, onShowTrip = { onShowLocalLine?.invoke(it) })
    },
    /** «Зустрітися тут» — чернетка зустрічі в месенджері з цим місцем. */
    onMeetHere: (() -> Unit)? = null,
    /** «У чат» — місце карткою в розмову з людиною. */
    onSendToChat: (() -> Unit)? = null,
) {
    val c = LocalPhantomColors.current

    // Поки досьє не приїхало, картку тримає миттєвий знімок із цілі тапу.
    val model = dossier ?: target?.let { instantDossier(it) }
    // Відтінок категорії — лише на значку місця; решта картки говорить бурштином.
    val categoryTint = model?.category?.accent(accentColor) ?: accentColor
    // Бурштин як СЛОВО (посилання, телефон, «Повторити») — `accentText`:
    // заливний `accent` на світлому аркуші вдень не читається як текст.
    val accent = c.accentText

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = readingGutter(PhantomSpace.gutterPhone))
            .padding(bottom = PhantomSpace.s6)
            .navigationBarsPadding(),
    ) {
        if (model == null) {
            Spacer(Modifier.height(PhantomSpace.s4))
            SheetNote(stringResource(R.string.dossier_nothing_yet))
            return@Column
        }

        // ── ПІК ──────────────────────────────────────────────────────────
        placeHeroUrl(model)?.let { PlaceHero(it) }
        DossierPeak(model, categoryTint, origin)
        Spacer(Modifier.height(PhantomSpace.s4))
        val social = onMeetHere != null || onSendToChat != null
        DossierActionRow(onRoute, onVia, onSave, onShare, onOpenExternal.takeUnless { social }, onMeetHere, onSendToChat)
        Spacer(Modifier.height(PhantomSpace.s4))
        if (hereNow != null && local.phantom.companion.feature.stream.ui.map.here.hereNowShows(hereNow)) {
            local.phantom.companion.feature.stream.ui.map.here.HereNowBlock(
                state = hereNow,
                nowMs = remember(hereNow) { System.currentTimeMillis() },
                onReport = onHereReport,
            )
            Spacer(Modifier.height(PhantomSpace.s4))
        }

        // ── ТІЛО ─────────────────────────────────────────────────────────
        // Спеціалізовані доменні картки під кожен тип обʼєкта
        target?.venueId?.let { PlaceVenueSection(it) }
        val airport = model.tags["aeroway"] in AIRPORT_TAGS
        if (airport || looksLikeTransitStop(model.tags, model.target)) {
            departureBoard(model.target.lat, model.target.lon, airport)
        }
        DossierTransitSection(model, accent, onShowLine, onShowLocalLine)
        DossierFuelAndEvCard(model, accent)
        DossierParkingCard(model, accent)
        DossierShelterCard(model, accent)
        DossierDiningCard(model, accent)
        DossierMallCard(model, accent)

        // Години, адреса, телефон — одна піднята картка фактів, рівним кроком рядка.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = PhantomSpace.s1)
                .mapRaised()
                .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s2),
        ) {
            DossierDetailSection(model, accent, onRetry)
            DossierCoreFacts(model, accent, onDial)
            DossierFacts(model, accent, onOpenUrl)
        }

        DossierChips(model, c.text2)
        DossierInsideSection(model, accent, onRetarget)
        DossierBriefingSection(briefing, accent, onBrief, onBriefRetry)

        // ── ГЛИБИНА ──────────────────────────────────────────────────────
        DossierReferenceSection(model, accent, onOpenUrl)
        DossierSimilarSection(model, onRetarget)
        if (hereNow != null) {
            local.phantom.companion.feature.stream.ui.map.here.HereReportRow(hereNow, onHereReport)
        }
        DossierDetailsDisclosure(model, accent, displacedExternal = onVia != null || social, onOpenExternal)
    }
}

/**
 * Пік: бейдж, назва і ОДИН інфо-рядок.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DossierPeak(
    dossier: PlaceDossier,
    accent: Color,
    origin: Pair<Double, Double>?,
) {
    val c = LocalPhantomColors.current
    val context = LocalContext.current
    val words = PeakWords(
        openUntil = { context.getString(R.string.dossier_open_until, it) },
        open = stringResource(R.string.dossier_open),
        closedUntil = { context.getString(R.string.dossier_closed_until, it) },
        closed = stringResource(R.string.dossier_closed),
        floor = { context.getString(R.string.dossier_floor, it) },
        inContainer = { context.getString(R.string.dossier_in_container, it) },
        walk = WalkWords(
            minutes = { context.getString(R.string.dossier_walk_minutes, it) },
            hours = { context.getString(R.string.dossier_walk_hours, it) },
            hoursMinutes = { h, m -> context.getString(R.string.dossier_walk_hours_minutes, h, m) },
        ),
        feePaid = stringResource(R.string.dossier_fee_paid),
        feeFree = stringResource(R.string.dossier_fee_free),
    )
    val segments = peakSegments(dossier, origin, words)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
    ) {
        // Той самий знак родини, що й пін під пальцем, у тоні категорії.
        Box(
            modifier = Modifier
                .size(BADGE_SIZE)
                .clip(CircleShape)
                .background(accent.copy(alpha = BADGE_WASH)),
            contentAlignment = Alignment.Center,
        ) {
            PhantomGlyphIcon(
                glyph = dossier.category.glyph(),
                tint = accent,
                modifier = Modifier
                    .size(BADGE_GLYPH)
                    .semantics { contentDescription = dossier.category.labelUk },
            )
        }
        Column(Modifier.weight(1f)) {
            SheetTitle(dossier.title)
            if (segments.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s1),
                    modifier = Modifier
                        .padding(top = PhantomSpace.s0_5)
                        // Один вузол для TalkBack: інакше він читає «магазин»,
                        // паузу, «·», паузу — по слову на сегмент.
                        .semantics(mergeDescendants = true) {
                            contentDescription = segments.joinToString(", ") { it.text }
                        },
                ) {
                    segments.forEachIndexed { index, segment -> DossierPeakItem(segment, leading = index > 0) }
                }
            }
        }
    }
}

/**
 * Рядки дій: ОДНА залита дія — «Маршрут»; «Заїхати» поруч піднятою, не
 * залитою; під ними рівні плитки «Зберегти · Поділитись · Відкрити».
 *
 * До 03.10 «Заїхати» була другою залитою кнопкою тієї ж ваги, і на картці
 * стояло два бурштинові прямокутники — ранг перестав читатись формою.
 * Знаки — родини мапи: «Маршрут» = ROUTE_TO, «Заїхати» = VIA, «Поділитись» =
 * HANDOUT, «Відкрити» = HANDOFF — ті самі, що на аркуші точки.
 */
@Composable
private fun DossierActionRow(
    onRoute: () -> Unit,
    onVia: (() -> Unit)?,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onOpenExternal: (() -> Unit)?,
    onMeetHere: (() -> Unit)? = null,
    onSendToChat: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MapPrimaryAction(
                icon = PhantomGlyph.ROUTE_TO.asImageVector(),
                label = stringResource(R.string.dossier_route),
                onClick = onRoute,
                modifier = Modifier.weight(1f),
            )
            if (onVia != null) {
                MapSecondaryAction(
                    icon = PhantomGlyph.VIA.asImageVector(),
                    label = stringResource(R.string.dossier_via),
                    onClick = onVia,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        MapTileRow {
            if (onMeetHere != null) {
                MapActionTile(PhantomGlyph.PERSON.asImageVector(), stringResource(R.string.place_meet_here), onMeetHere)
            }
            if (onSendToChat != null) {
                MapActionTile(ImageVector.vectorResource(DesignR.drawable.lc_chats), stringResource(R.string.place_send_chat), onSendToChat)
            }
            MapActionTile(
                ImageVector.vectorResource(DesignR.drawable.lc_bookmark),
                stringResource(R.string.dossier_save),
                onSave,
            )
            MapActionTile(
                PhantomGlyph.HANDOUT.asImageVector(),
                stringResource(R.string.dossier_share),
                onShare,
            )
            if (onOpenExternal != null) {
                MapActionTile(
                    PhantomGlyph.HANDOFF.asImageVector(),
                    stringResource(R.string.dossier_external),
                    onOpenExternal,
                )
            }
        }
    }
}

/**
 * «Огляд від PHANTOM» — рядок 48dp ПІСЛЯ фактів, без плити.
 *
 * Тизер ШІ стояв над годинами й адресою і коштував 56dp піка. Відповідь тече
 * сюди так само, як раніше; у чат іде лише копія як історія.
 */
@Composable
private fun DossierBriefingSection(
    briefing: DossierBriefing?,
    accent: Color,
    onBrief: () -> Unit,
    onRetry: () -> Unit,
) {
    val c = LocalPhantomColors.current
    val phase = briefing?.phase ?: DossierBriefing.Phase.IDLE
    val label = when (phase) {
        DossierBriefing.Phase.THINKING -> stringResource(R.string.dossier_overview_thinking)
        DossierBriefing.Phase.STREAMING -> stringResource(R.string.dossier_overview_streaming)
        else -> stringResource(R.string.dossier_overview)
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = PhantomTouchFloor)
                .let {
                    if (phase == DossierBriefing.Phase.IDLE) {
                        it.clickable(role = Role.Button, onClick = onBrief)
                            .semantics(mergeDescendants = true) { contentDescription = label }
                    } else {
                        it
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
        ) {
            // Огляд пише PHANTOM — тож і знак його, а не чужа «іскра».
            PhantomMarkGlyph(tint = accent, modifier = Modifier.size(ICON))
            SheetBody(label, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (phase == DossierBriefing.Phase.DONE) {
                SheetSmall(stringResource(R.string.dossier_overview_echoed))
            }
        }
        if (briefing != null && briefing.hasText) {
            Spacer(Modifier.height(PhantomSpace.s2))
            // Відповідь PHANTOM — аркуш паперу, як у розмові: `surface`,
            // волосина `line`, усі кути 18.
            val paper = RoundedCornerShape(PhantomRadius.bubble)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(paper)
                    .background(c.surface)
                    .border(1.dp, c.line, paper)
                    .padding(horizontal = 14.dp, vertical = PhantomSpace.s3),
            ) {
                SheetText(briefing.text, PhantomText.body, color = c.text)
            }
        }
        if (phase == DossierBriefing.Phase.FAILED) {
            DossierSlotFailure(
                briefing?.error ?: stringResource(R.string.dossier_overview_failed),
                accent,
                onRetry,
            )
        }
    }
}

/**
 * Деталі місця: «читаю деталі» поки шукаємо, «шукаю глибше» поки мережа
 * добирає, і ДВА різні тексти в кінці — «даних про цей обʼєкт нема» проти
 * «джерело не відповіло».
 */
@Composable
private fun DossierDetailSection(dossier: PlaceDossier, accent: Color, onRetry: (() -> Unit)?) {
    when (detailVerdict(dossier)) {
        DossierDetailVerdict.PENDING -> SheetNote(stringResource(R.string.dossier_detail_pending))
        DossierDetailVerdict.DEEPENING -> SheetNote(stringResource(R.string.dossier_detail_deepening))
        DossierDetailVerdict.LOOKUP_FAILED ->
            DossierSlotFailure(stringResource(R.string.dossier_detail_failed), accent, onRetry)
        DossierDetailVerdict.NO_DATA ->
            if (coreFacts(dossier).isEmpty()) SheetNote(stringResource(R.string.dossier_detail_no_data))
        DossierDetailVerdict.PRESENT -> Unit
    }
}

@Composable
private fun DossierCoreFacts(dossier: PlaceDossier, accent: Color, onDial: (String) -> Unit) {
    val c = LocalPhantomColors.current
    val words = HoursWords(
        days = stringResource(R.string.dossier_hours_days).split(','),
        daily = stringResource(R.string.dossier_hours_daily),
        allDay = stringResource(R.string.dossier_hours_all_day),
        off = stringResource(R.string.dossier_hours_off),
    )
    for (fact in coreFacts(dossier)) {
        val value = fact.value ?: continue
        when (fact.field) {
            CoreField.HOURS -> DossierFactRow(stringResource(R.string.dossier_hours_label), humanHours(value, words), c.text)
            CoreField.ADDRESS -> {
                val (head, tail) = splitAddress(value)
                DossierFactRow(stringResource(R.string.dossier_address_label), head, c.text, detail = tail)
            }
            CoreField.PHONE ->
                DossierFactRow(stringResource(R.string.dossier_phone_label), value, accent, onClick = { onDial(value) })
        }
    }
    if (dossier.address == null && dossier.isPending(DossierSlot.ADDRESS)) {
        SheetNote(stringResource(R.string.dossier_address_pending))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DossierChips(dossier: PlaceDossier, tint: Color) {
    val chips = dossier.quickChips()
    if (chips.isEmpty()) return
    Spacer(Modifier.height(PhantomSpace.s2))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
        verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
    ) {
        chips.forEach { DossierFactPill(it, tint) }
    }
}

@Composable
private fun DossierFacts(
    dossier: PlaceDossier,
    accent: Color,
    onOpenUrl: (String) -> Unit,
) {
    val c = LocalPhantomColors.current
    dossier.website?.let { url ->
        DossierFactRow(
            stringResource(R.string.dossier_site_label),
            url.removePrefix("https://").removePrefix("http://").trimEnd('/'),
            accent,
            onClick = { onOpenUrl(url) },
        )
    }
    // Послуги закладу — рядком фактів, не окремою карткою з назвою, що повторює заголовок.
    DossierObjectFacts.medical(dossier.tags)
        .filterNot { it == "пандус" && dossier.wheelchair != null }
        .takeIf { it.isNotEmpty() && dossier.tags["amenity"] in MEDICAL_AMENITIES }
        ?.let { DossierFactRow(stringResource(R.string.dossier_services_label), it.joinToString(", "), c.text) }
    dossier.cuisine?.let {
        DossierFactRow(stringResource(R.string.dossier_cuisine_label), it, c.text)
    }
    // Закритий словник: невідоме значення wheelchair мовчить, а не друкує
    // себе як є («else -> it» ганяло raw «designated» на екран до 15.09).
    dossier.wheelchair?.let { w ->
        val access = when (w) {
            "yes" -> stringResource(R.string.dossier_access_yes)
            "designated" -> stringResource(R.string.dossier_access_designated)
            "limited" -> stringResource(R.string.dossier_access_limited)
            "no" -> stringResource(R.string.dossier_access_no)
            else -> null
        }
        access?.let { DossierFactRow(stringResource(R.string.dossier_access_label), it, c.text) }
    }
    dossier.building?.let { b ->
        val levelsWord = stringResource(R.string.dossier_levels_short)
        val parts = buildList {
            b.levels?.let { add("$it $levelsWord") }
            b.heightM?.let { add("${it.toInt()} ${stringResource(R.string.dossier_metres_short)}") }
            b.buildingType?.let { add(buildingTypeLabel(it)) }
            if (b.street != null && b.houseNumber != null) add("${b.street}, ${b.houseNumber}")
            else b.name?.takeIf { it != dossier.title }?.let { add(it) }
        }
        if (parts.isNotEmpty()) {
            DossierFactRow(
                stringResource(R.string.dossier_building_label),
                parts.joinToString(" · "),
                c.text,
            )
        }
    }
    dossier.extraFacts().forEach { (label, value) -> DossierFactRow(label, value, c.text) }

    // Координати живуть у «Деталях» — двічі на картці вони були механікою.
}

/**
 * `opening_hours` людською мовою для простих розкладів: «Mo-Su 08:00-22:00» →
 * «щодня 08:00–22:00». Складний вираз лишається як є — вигадувати гірше.
 */
internal data class HoursWords(val days: List<String>, val daily: String, val allDay: String, val off: String)

internal fun humanHours(raw: String, w: HoursWords): String {
    if (raw.trim() == "24/7") return w.allDay
    if (Regex("""[A-Za-z]{3,}|PH|SH|\[""").containsMatchIn(raw.replace("off", ""))) return raw
    val codes = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
    return raw.split(';').joinToString(", ") { part ->
        var p = part.trim().replace(Regex("""^Mo-Su\b"""), w.daily)
        codes.forEachIndexed { i, code -> p = p.replace(code, w.days.getOrElse(i) { code }) }
        p.replace(Regex("""(\d{2}:\d{2})-(\d{2}:\d{2})"""), "$1–$2").replace("off", w.off)
    }
}

/**
 * P0 — ДОВІДКА проти ПОРУЧ: заголовок «ДОВІДКА» отримує лише стаття з
 * вердиктом [WikiVerdict.ENTITY]; усе інше — контекст із прямою обмовкою.
 */
@Composable
private fun DossierReferenceSection(
    dossier: PlaceDossier,
    accent: Color,
    onOpenUrl: (String) -> Unit,
) {
    val c = LocalPhantomColors.current
    if (dossier.isPending(DossierSlot.REFERENCE)) {
        DossierSectionTitle(stringResource(R.string.dossier_reference_title))
        SheetNote(stringResource(R.string.dossier_reference_pending))
        return
    }
    val reference = dossier.reference ?: return
    val article = reference.article

    if (reference.isAboutEntity) {
        DossierSectionTitle(stringResource(R.string.dossier_reference_title))
        Spacer(Modifier.height(PhantomSpace.s1))
        SheetBody(article.extract, color = c.text2, maxLines = 4)
        val wikiName = "${stringResource(R.string.dossier_wiki_prefix)} ${article.title}"
        Box(
            modifier = Modifier
                .heightIn(min = PhantomTouchFloor)
                .clip(CircleShape)
                .clickable(role = Role.Button) { onOpenUrl(article.pageUrl) }
                .semantics(mergeDescendants = true) { contentDescription = wikiName },
            contentAlignment = Alignment.CenterStart,
        ) {
            SheetBody("$wikiName →", color = accent, fontWeight = FontWeight.Medium)
        }
    } else {
        DossierSectionTitle(stringResource(R.string.dossier_nearby_title))
        SheetNote(stringResource(R.string.dossier_nearby_disclaimer))
        val nearbyName = article.title
        Box(
            modifier = Modifier
                .heightIn(min = PhantomTouchFloor)
                .clip(CircleShape)
                .clickable(role = Role.Button) { onOpenUrl(article.pageUrl) }
                .semantics(mergeDescendants = true) { contentDescription = nearbyName },
            contentAlignment = Alignment.CenterStart,
        ) {
            SheetBody("$nearbyName →", color = accent, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun DossierSimilarSection(dossier: PlaceDossier, onRetarget: (DossierTarget) -> Unit) {
    val c = LocalPhantomColors.current
    if (dossier.similar.isEmpty()) return
    DossierSectionTitle(dossier.similarTitle)
    dossier.similar.forEach { poi ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = PhantomTouchFloor)
                .clickable(role = Role.Button) {
                    onRetarget(
                        DossierTarget(
                            lat = poi.lat,
                            lon = poi.lon,
                            nameHint = poi.name,
                            categoryHint = poi.category,
                            subcategoryHint = poi.subcategory,
                        ),
                    )
                }
                .semantics(mergeDescendants = true) { contentDescription = poi.name },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SheetBody(poi.name, modifier = Modifier.weight(1f), maxLines = 1)
            SheetNote(
                formatDistance(
                    haversineMeters(dossier.target.lat, dossier.target.lon, poi.lat, poi.lon),
                ),
                tnum = true,
            )
        }
    }
}

/**
 * P0 — координати ЖИВУТЬ ТУТ: згорнуто, з копіюванням. І тут же — межа знання
 * про будівлю: внутрішніх проходів у джерелі просто нема, і тиша про це гірша
 * за одне чесне речення.
 */
@Composable
private fun DossierDetailsDisclosure(
    dossier: PlaceDossier,
    accent: Color,
    displacedExternal: Boolean,
    onOpenExternal: () -> Unit,
) {
    val c = LocalPhantomColors.current
    val clipboard = LocalClipboardManager.current
    var expanded by remember { mutableStateOf(false) }
    val coords = "${"%.5f".format(dossier.target.lat)}, ${"%.5f".format(dossier.target.lon)}"
    val detailsLabel = stringResource(R.string.dossier_details_label)
    val copyLabel = stringResource(R.string.dossier_copy_label)
    val externalLabel = stringResource(R.string.dossier_external)

    Spacer(Modifier.height(PhantomSpace.s2))
    HorizontalDivider(color = c.line)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PhantomTouchFloor)
            .clickable(role = Role.Button) { expanded = !expanded }
            .semantics(mergeDescendants = true) { contentDescription = detailsLabel },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SheetBody(detailsLabel, color = c.text2, modifier = Modifier.weight(1f))
        DossierDisclosureChevron(expanded, c.text2)
    }
    AnimatedVisibility(visible = expanded) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SheetNote(coords, modifier = Modifier.weight(1f), tnum = true)
                // Гліф лишається дрібним; хіт-бокс навколо нього тримає підлогу
                // дотику, щоб рукавиця не мусила влучати в саму іконку.
                Box(
                    modifier = Modifier
                        .size(PhantomTouchFloor)
                        .clickable(role = Role.Button) { clipboard.setText(AnnotatedString(coords)) }
                        .semantics(mergeDescendants = true) { contentDescription = copyLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ms_copy),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(ICON),
                    )
                }
            }
            if (displacedExternal) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = PhantomTouchFloor)
                        .clickable(role = Role.Button, onClick = onOpenExternal)
                        .semantics(mergeDescendants = true) { contentDescription = externalLabel },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SheetBody("$externalLabel →", color = accent, fontWeight = FontWeight.Medium)
                }
            }
            if (dossier.isContainer) {
                SheetSmall(stringResource(R.string.dossier_indoor_limit))
            }
            if (dossier.routePoint != null) {
                SheetSmall(stringResource(R.string.dossier_route_to_entrance))
            }
            // Вік даних, чесно: походження невідоме — рядка просто немає.
            sourceLine(dossier.identitySource)?.let { SheetSmall(it) }
        }
    }
}

private fun buildingTypeLabel(type: String): String = when (type) {
    "residential", "apartments" -> MapText.s(R.string.kt_bld_residential)
    "house", "detached" -> MapText.s(R.string.kt_bld_house)
    "commercial", "retail" -> MapText.s(R.string.kt_bld_commercial)
    "industrial" -> MapText.s(R.string.kt_bld_industrial)
    "office" -> MapText.s(R.string.kt_bld_office)
    "school" -> MapText.s(R.string.kt_bld_school)
    "hospital" -> MapText.s(R.string.kt_bld_hospital)
    "church", "cathedral" -> MapText.s(R.string.kt_bld_church)
    "garage", "garages" -> MapText.s(R.string.kt_bld_garages)
    else -> type.replace('_', ' ')
}

/**
 * Звідки прийшла особистість цього місця. Рівно за фактом: мережа цього
 * сеансу — чи те, що вже лежало на пристрої. Невідоме походження не малює
 * нічого.
 */
@Composable
private fun sourceLine(source: DossierSourceKind?): String? = when (source) {
    DossierSourceKind.OVERPASS -> stringResource(R.string.dossier_source_live)
    DossierSourceKind.VIEWPORT,
    DossierSourceKind.GAZETTEER,
    DossierSourceKind.ROAD_MESH,
    -> stringResource(R.string.dossier_source_device)
    null -> null
}

private val BADGE_SIZE = PhantomSize.avatarM
private val BADGE_GLYPH = 22.dp

/** Тон категорії за значком — тло, а не заливка: знак лишається головним. */
private const val BADGE_WASH = 0.18f
private val ICON = 20.dp

private val AIRPORT_TAGS = setOf("aerodrome", "terminal")

@Composable
private fun DossierPeakItem(segment: PeakSegment, leading: Boolean) {
    val c = LocalPhantomColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s1)) {
        if (leading) SheetNote("·", color = c.text3)
        SheetNote(
            segment.text,
            color = when (segment.tone) {
                PeakTone.POSITIVE -> c.success
                PeakTone.DANGER -> c.danger
                PeakTone.NEUTRAL -> c.text2
            },
            tnum = true,
        )
    }
}
