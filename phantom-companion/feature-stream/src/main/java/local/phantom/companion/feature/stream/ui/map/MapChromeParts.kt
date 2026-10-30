package local.phantom.companion.feature.stream.ui.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.motion.MotionTokens
import local.phantom.companion.core.design.motion.PhantomMotion
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.data.transit.TransitVehicle
import local.phantom.companion.core.data.transit.surface.SurfaceFeed
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.atlas.SceneMode
import local.phantom.companion.feature.stream.ui.map.dossier.DossierTransitLine
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyphIcon
import local.phantom.companion.feature.stream.ui.map.icons.glyph
import local.phantom.companion.feature.stream.ui.map.transit.transitVehicleFacts
import local.phantom.companion.core.design.R as DesignR


// NAV-0 — chrome parts: the banners, pills and rails the MapChrome
// scaffold arranges per scene. Вигляд — одна мова v1.2 з MapCardParts.kt:
// піднята пігулка з волосиною, footnote, значки lc_*, ціль 48dp.

/** M-C05/C06 — degraded evacuation routing is live: a warning, said in words. */
@Composable
internal fun FallbackPlanBanner() {
    val c = LocalPhantomColors.current
    MapPill(tone = MapTone.Warning) {
        MapIcon(DesignR.drawable.lc_shelter, tint = c.warning, size = PhantomSize.iconS)
        MapPillText(
            text = stringResource(R.string.map_fallback_plan).replaceFirstChar { it.titlecase() },
            color = c.warning,
            weight = FontWeight.SemiBold,
        )
    }
}

/**
 * Transient one-line notice (route failed / nothing nearby). Takes the text
 * nullable and owns its own arrival: the notice is written from a coroutine
 * that clears it again seconds later, and a pill that blinks in and out of a
 * column drags every sibling with it.
 */
@Composable
internal fun NoticePill(text: String?) {
    var last by remember { mutableStateOf("") }
    if (text != null) last = text
    AnimatedVisibility(
        visible = text != null,
        enter = PhantomMotion.expand,
        exit = PhantomMotion.dismiss,
    ) {
        MapPill {
            MapPillText(text = last, maxLines = 2)
        }
    }
}

/**
 * «Звідси маршрут»: обраний початок ЧЕКАЄ на кінець, і мовчазне очікування —
 * пастка. Пілюля тримає слово на склі, доки кінець не названо; дотик по ній
 * скасовує вибір (сама вона нікуди не веде — вести буде наступна точка).
 */
@Composable
internal fun RouteOriginPill(label: String, onCancel: () -> Unit) {
    CancellablePill(
        text = stringResource(R.string.map_route_origin_pill, label),
        spoken = stringResource(R.string.map_route_origin_cancel),
        onCancel = onCancel,
    )
}

/**
 * Якір ставлять пальцем: режим, у якому мапа чекає довгого дотику.
 *
 * Мовчазний режим — пастка тієї ж породи, що й мовчазний запис: людина не
 * знає ані що від неї хочуть, ані як вийти. Пілюля каже обидва; дотик по ній
 * скасовує вибір. Ціль 48dp живе на зовнішній коробці [MapPill], скло — 40dp.
 */
@Composable
internal fun AnchorPickPill(label: String, onCancel: () -> Unit) {
    CancellablePill(
        text = stringResource(R.string.map_anchor_pill, label),
        spoken = stringResource(R.string.map_anchor_pill_cancel),
        onCancel = onCancel,
    )
}

/** Режим, що чекає пальця: слово і × — дотик по всій пігулці скасовує. */
@Composable
private fun CancellablePill(text: String, spoken: String, onCancel: () -> Unit) {
    val c = LocalPhantomColors.current
    MapPill(onClick = onCancel, spoken = spoken) {
        MapPillText(text = text, modifier = Modifier.weight(1f, fill = false))
        MapIcon(DesignR.drawable.lc_close, tint = c.text2, size = PhantomSize.iconS)
    }
}

/**
 * Режим малювання: мапа збирає вершини й мусить сказати це вголос.
 *
 * Той самий закон, що й у якоря: мовчазний режим не дає ані зрозуміти, чого
 * від тебе хочуть, ані вийти. Тут пілюля каже три речі — що рахується, скільки
 * вже намальовано, і як піти. Кожна дія окрема, бо «прибрати останню» під час
 * малювання тиснуть частіше за все інше разом. «Готово» — головна дія режиму,
 * тож вона одна несе бурштин.
 */
@Composable
internal fun DrawPill(
    vertexCount: Int,
    lengthLabel: String?,
    heldVertex: Boolean,
    onUndo: () -> Unit,
    onRemoveHeld: () -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s1),
    ) {
        MapPill(modifier = Modifier.weight(1f, fill = false)) {
            MapPillText(
                // Довжина лишається на очах і поки вершину тягнуть: саме тоді
                // на неї й дивляться, і ховати її під словом «взято» означало
                // б забрати єдиний свідок того, що зсув щось змінив.
                text = when {
                    lengthLabel == null -> stringResource(R.string.map_draw_pill_empty)
                    heldVertex -> stringResource(R.string.map_draw_pill_held, lengthLabel)
                    else -> stringResource(R.string.map_draw_pill, lengthLabel)
                },
            )
        }
        // Один слот на «прибрати», не два поруч: обрана вершина й остання
        // вершина — це та сама дія над різними вершинами, а пʼята кнопка в
        // стрічці не вміщується в ширину екрана.
        if (heldVertex) {
            DrawPillAction(
                label = stringResource(R.string.map_draw_remove_vertex_short),
                spoken = stringResource(R.string.map_draw_remove_vertex),
                onClick = onRemoveHeld,
            )
        } else if (vertexCount > 0) {
            DrawPillAction(
                label = stringResource(R.string.map_draw_undo_short),
                spoken = stringResource(R.string.map_draw_undo),
                onClick = onUndo,
            )
        }
        MapRoundAction(
            icon = DesignR.drawable.lc_check,
            contentDescription = stringResource(R.string.map_draw_done),
            onClick = onDone,
            accent = true,
        )
        MapRoundAction(
            icon = DesignR.drawable.lc_close,
            contentDescription = stringResource(R.string.map_draw_cancel),
            onClick = onCancel,
        )
    }
}

/** Дія пігулки малювання словом: та сама пігулка, ціль 48dp. */
@Composable
private fun DrawPillAction(
    label: String,
    spoken: String,
    onClick: () -> Unit,
) {
    MapPill(onClick = onClick, spoken = spoken) {
        MapPillText(text = label.replaceFirstChar { it.titlecase() }, weight = FontWeight.Medium)
    }
}

/**
 * ATLAS-2 — «режим: за кермом» / «режим: загроза», рівно один раз на вхід.
 *
 * Дисципліна взята з чипа довіри й свідомо ті самі числа: вікно [ANNOUNCE_MS],
 * стеля переоголошення [REANNOUNCE_FLOOR_MS], і жодного циклу кадрів — одна
 * затримка на перехід, після чого композиція не прокидається взагалі. Режими,
 * що нічого не забирають з екрана (спокій, огляд, ходьба, транзит, ведення),
 * плашки не отримують: там нема про що попереджати, а мовчазна зміна — це і є
 * та «доречність», заради якої вся хвиля.
 */
@Composable
internal fun rememberModeAnnouncement(mode: SceneMode): String? {
    var state by remember { mutableStateOf(SceneAnnounceState()) }
    var stamp by remember { mutableLongStateOf(0L) }
    LaunchedEffect(mode) {
        val now = android.os.SystemClock.elapsedRealtime()
        state = state.observe(mode, now)
        stamp = now
        if (state.announcing(now)) {
            delay(ANNOUNCE_MS)
            stamp = android.os.SystemClock.elapsedRealtime()
        }
    }
    val res = modeNoticeRes(mode) ?: return null
    return if (state.announcing(stamp)) stringResource(res) else null
}

private fun modeNoticeRes(mode: SceneMode): Int? = when (mode) {
    SceneMode.DRIVE -> R.string.map_mode_drive
    SceneMode.THREAT -> R.string.map_mode_threat
    SceneMode.CALM, SceneMode.EXPLORE, SceneMode.GUIDE,
    SceneMode.WALK, SceneMode.TRANSIT,
    -> null
}

/** MAP-17 — the basemap choices offered by the on-map style switcher.
 *  «Супутник» (Esri World_Imagery, keyless) was cut 2026-07-20: Esri ToS
 *  requires an ArcGIS subscription/token for app use — no free commercial
 *  imagery exists, so the option is removed rather than shipped illegal. */
internal enum class MapBasemap { DARK, LIGHT, MINIMAL }

/** MAP-17 — the basemap options behind the style switcher, as data tokens. */
internal val BASEMAP_CHOICES: List<Pair<Int, MapBasemap?>> = listOf(
    R.string.map_basemap_auto to null,
    R.string.map_basemap_dark to MapBasemap.DARK,
    R.string.map_basemap_light to MapBasemap.LIGHT,
    R.string.map_basemap_minimal to MapBasemap.MINIMAL,
)

/**
 * R4.x / СИРЕНА — the living air-raid pill. Red + breathing dot when the
 * operator's own oblast is under alert (typed threat + elapsed time); a calm
 * line otherwise, and a country-wide count so the map always tells the truth
 * about the sky. The pill is now a doorway: tapping it opens the «Небо»
 * alarm command sheet — the old inline oblast popup moved there, where it
 * has room to be a real board (types, durations, journal).
 */
/**
 * «Ця ділянка ще не збережена офлайн» — підпис до штрихування.
 *
 * Рядок `map_prov_line_not_downloaded` жив у ресурсах із НУЛЕМ викликів:
 * написаний, перекладений, перевірений тестом парності — і не показаний
 * ніде. Разом із ним мовчав `map_prov_sheet_body_not_downloaded`, у якому
 * сказано найважливіше: «мовчання карти означає саме це, а не „тут нічого
 * немає"».
 *
 * Ціна мовчання виміряна не на нас: власник завтра за кордоном. Без мережі
 * й без збереженого краю він побачить суцільне тло в діагональну смужку —
 * і прочитає це як поламаний застосунок. Штрихування показує ФАКТ; лише
 * слово каже, що з ним робити.
 *
 * Мовчить, коли землю видно: підпис над завантаженою мапою був би шумом.
 */
@Composable
internal fun GroundNotSavedBanner(gap: BasemapGap, onSave: () -> Unit) {
    if (gap == BasemapGap.NONE) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(
                if (gap == BasemapGap.MAP) R.string.map_r0_map_not_saved else R.string.map_prov_line_not_downloaded,
            ),
            style = PhantomText.footnote,
            color = LocalPhantomColors.current.text2,
            modifier = Modifier.weight(1f, fill = false).padding(horizontal = PhantomSpace.s2),
        )
        Text(
            text = stringResource(R.string.map_r0_save_area),
            style = PhantomText.footnote,
            fontWeight = FontWeight.SemiBold,
            color = LocalPhantomColors.current.accentText,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clickable(role = Role.Button, onClick = onSave)
                .padding(horizontal = PhantomSpace.s2),
        )
    }
}

/**
 * «За межами внесеної офлайн-основи» — та сама чесність, що й
 * [GroundNotSavedBanner], для іншого джерела: тут порожнє тло означає, що
 * файл mbtiles не сягає сюди, а не що застосунок зламався.
 */
@Composable
internal fun OfflineBasemapCoverageBanner(visible: Boolean) {
    if (!visible) return
    MapFootnoteLine(stringResource(R.string.map_offline_basemap_out_of_bounds))
}

/** Рядок-примітка під пігулками: footnote text2, без скла — це підпис, не кнопка. */
@Composable
private fun MapFootnoteLine(text: String) {
    Text(
        text = text,
        style = PhantomText.footnote,
        color = LocalPhantomColors.current.text2,
        modifier = Modifier.padding(horizontal = PhantomSpace.s2),
    )
}

@Composable
internal fun AlertBanner(
    ownOblast: String?,
    ownState: AlertState?,
    activeCount: Int,
    /** СИРЕНА-3 — вирок про небо, спільний зі шторкою. Слова, не площа. */
    verdict: SkyVerdict,
    onOpen: (() -> Unit)? = null,
) {
    // Країна без стрічки тривог: питання не має джерела, тож його й не ставимо.
    // Це РАНІШЕ за все решту — «не знаю» тут було б твердженням про канал,
    // якого немає (`SkyVerdict.NO_COUNTRY_FEED`).
    if (verdict == SkyVerdict.NO_COUNTRY_FEED) return
    val feedSilent = verdict.feedSilent
    // Nothing meaningful to say → stay silent. Але «мовчання мережі ніколи не
    // читається як безпека» (AirAlertWatch.kt) — стрічку, яку ще НІ РАЗУ не
    // прочитали (NOFEED на холодному старті), не можна плутати з «тихо всюди»:
    // це «не знаю», а рівно тоді, коли стрічка ще не встигла, і GPS-фіксу
    // здебільшого теж ще нема. Мовчати тут — сказати «все чисто» саме там, де
    // це неправда за власним законом файлу.
    if (ownOblast == null && activeCount == 0 && !feedSilent) return
    val c = LocalPhantomColors.current
    val underAlert = ownState?.active == true
    val shortName = ownOblast?.let { oblastShortUk(it) }
    // Своя область відома, десь іде тривога, АБО стрічка ще не озвалась —
    // в усіх трьох випадках аркуш «Небо» відкривається: у нього є спокійне
    // обличчя (журнал і маршрут до укриття), і саме тиша чи мовчання стрічки
    // на холодному старті — момент, коли людині це найпотрібніше.
    val canOpen = onOpen != null && (ownOblast != null || activeCount > 0 || feedSilent)

    // Зелена крапка над мовчазною стрічкою — та сама брехня, що й слово «тиша»,
    // тільки швидша: колір читається раніше за текст.
    val dotColor = when {
        underAlert -> c.danger
        feedSilent -> c.warning
        else -> c.success
    }
    val title = stringResource(
        when {
            underAlert -> R.string.map_alert_loud
            feedSilent -> R.string.map_alert_unknown
            ownOblast == null -> R.string.map_alert_country
            else -> R.string.map_alert_quiet
        },
    )
    val feedSilentWord = stringResource(R.string.map_alert_feed_silent)
    val sinceMask = stringResource(R.string.map_alert_since)
    // Видиме і сказане розходяться саме тут, і навмисно.
    //
    // На екрані власника цей рядок читався «тиша · Житомирська · по країні 10
    // обл. пі…» — обрізаний посеред слова. Місця в пігулці рівно на один
    // короткий рядок, а фактів у ній три, і найдовший з них найменш терміновий:
    // скільки областей ГДЕСЬ під тривогою. Тож він стискається до двох токенів
    // («10 обл.»), а не втрачає хвіст: слово лишається при числі, і різати
    // нічого. Повне речення нікуди не зникло — воно йде в озвучення нижче,
    // тобто саме туди, де довжина нічого не коштує.
    val countryShort = stringResource(R.string.map_alert_country_count, activeCount)
    val countrySpoken = stringResource(R.string.map_alert_country_count_spoken, activeCount)
    val countryOnly = stringResource(R.string.map_alert_country_only, activeCount)

    // «почалась 7 год тому» над мовчазною стрічкою читається як тривога, що
    // ТРИВАЄ сім годин. Час початку — правда; його теперішність — ні, і саме її
    // стрічка вже не підтверджує. Тож замість віку говориться мовчання: рядок
    // лишається того самого калібру, а обіцянки в ньому більше немає. Повний
    // розбір (тип, початок, журнал) чекає в аркуші «Небо», де є місце.
    fun ownFacts() = buildString {
        if (underAlert) {
            if (shortName != null) append(shortName)
            if (feedSilent) {
                append(if (isEmpty()) feedSilentWord else " · $feedSilentWord")
            } else {
                ownState?.sinceText?.let { append(if (isEmpty()) sinceMask.format(it) else " · $it") }
            }
        } else if (ownOblast != null) {
            append(shortName ?: "")
            if (feedSilent) append(if (isEmpty()) feedSilentWord else " · $feedSilentWord")
        }
    }

    // Лічильник по країні падає першим, щойно стрічка змовкла: він порахований
    // з того самого мовчазного знімка, тобто вже не свідчить, а місця в пігулці
    // рівно на один короткий рядок — і мовчання терміновіше за число.
    val subtitle = when {
        feedSilent -> ownFacts().ifBlank { feedSilentWord }
        ownOblast == null -> countryOnly
        activeCount > 0 && !underAlert -> listOf(ownFacts(), countryShort)
            .filter { it.isNotBlank() }.joinToString(" · ")
        else -> ownFacts()
    }
    val subtitleSpoken = when {
        feedSilent -> ownFacts().ifBlank { feedSilentWord }
        ownOblast == null -> countryOnly
        activeCount > 0 && !underAlert -> listOf(ownFacts(), countrySpoken)
            .filter { it.isNotBlank() }.joinToString(" · ")
        else -> ownFacts()
    }

    // Single-line, wrap-content pill — one short status sentence, never a
    // whole map row. Під тривогою — волосина danger, за мовчазної стрічки —
    // warning: колір краю каже те саме, що й крапка, тільки раніше.
    val tone = when {
        underAlert -> MapTone.Danger
        feedSilent -> MapTone.Warning
        else -> MapTone.Neutral
    }
    val spokenName = listOf(title, subtitleSpoken).filter { it.isNotBlank() }.joinToString(" · ")
    MapPill(
        tone = tone,
        onClick = if (canOpen) ({ onOpen?.invoke() }) else null,
        spoken = spokenName,
    ) {
        // The dot breathes while the operator's own sky is loud — the one
        // permitted motion in CALM chrome, because it is the one fact that
        // must never be missable.
        val dotAlpha = if (underAlert) {
            val pulse = rememberInfiniteTransition(label = "alert-pill-pulse")
            pulse.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(MotionTokens.pulseUrgent, RepeatMode.Reverse),
                label = "alert-pill-dot",
            ).value
        } else 1f
        MapDot(color = dotColor, modifier = Modifier.alpha(dotAlpha))
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MapPillText(
                text = title,
                color = if (underAlert) c.danger else c.text,
                weight = FontWeight.SemiBold,
            )
            if (subtitle.isNotBlank()) {
                MapPillText(
                    text = " · $subtitle",
                    color = c.text2,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        if (canOpen) {
            MapIcon(DesignR.drawable.lc_chevron_right, tint = c.text3, size = PhantomSize.iconS)
        }
    }
}

/**
 * Картка машини, якої можна дочекатись.
 *
 * Була тактичним HUD: швидкість, курс у градусах, наповненість, ідентифікатор
 * рейсу — сира телеметрія, яку власник у полі назвав «даними, що людям бачити
 * не захочеться». Лишилось те, заради чого пігулку взагалі торкають: який це
 * маршрут, звідки взялась позиція і як побачити всю лінію. Напрямок і кнопка
 * лінії зʼявляються лише тоді, коли за ними стоїть мережа, що справді описує
 * місце під цією машиною ([transitVehicleFacts]).
 */
@Composable
internal fun TransitVehicleCard(
    vehicle: TransitVehicle,
    surfaceFeed: () -> SurfaceFeed?,
    onShowLine: (DossierTransitLine) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Скло показує лише живі позиції, тож картка теж: селектор може принести
    // модельну машину хіба що з чужого шляху — і тоді краще нічого.
    if (!vehicle.isLiveGps) return

    val c = LocalPhantomColors.current
    val prefix = stringResource(R.string.map_transit_route_prefix)
    val facts = remember(vehicle.vehicleId, vehicle.routeId, vehicle.routeLabel, prefix) {
        transitVehicleFacts(vehicle, prefix, surfaceFeed())
    }

    MapCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = PhantomSpace.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhantomGlyphIcon(
                glyph = vehicle.mode.glyph(),
                tint = c.text2,
                modifier = Modifier.size(PhantomSize.iconM),
            )
            Spacer(Modifier.width(PhantomSpace.s3))
            Text(
                text = facts.routeLabel.ifEmpty { stringResource(R.string.map_transit_unnumbered) },
                style = PhantomText.headline,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            MapRowAction(
                icon = DesignR.drawable.lc_close,
                contentDescription = stringResource(R.string.map_transit_close),
                onClick = onDismiss,
            )
        }
        Column(
            modifier = Modifier.padding(start = PhantomSpace.s4, end = PhantomSpace.s4, bottom = PhantomSpace.s3),
            verticalArrangement = Arrangement.spacedBy(PhantomSpace.s1),
        ) {
            facts.direction?.let { direction ->
                Text(
                    text = direction,
                    style = PhantomText.body,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
            ) {
                MapDot(c.success, Modifier.size(DOT))
                Text(
                    text = stringResource(R.string.map_transit_live_gps),
                    style = PhantomText.footnote,
                    color = c.text2,
                )
            }
        }
        facts.line?.let { line ->
            MapRowDivider(inset = 0.dp)
            MapTextAction(
                label = stringResource(R.string.map_transit_show_line),
                onClick = { onShowLine(line) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private val DOT = PhantomSpace.s2
