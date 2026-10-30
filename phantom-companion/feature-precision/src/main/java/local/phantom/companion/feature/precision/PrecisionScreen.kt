package local.phantom.companion.feature.precision

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import local.phantom.companion.core.design.theme.LocalBottomChromeInset
import local.phantom.companion.core.design.component.GlassCard
import local.phantom.companion.core.design.component.GlassLevel
import local.phantom.companion.core.design.component.PhantomBackPill
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.PhantomCaption
import local.phantom.companion.core.design.theme.PhantomDataText
import local.phantom.companion.core.design.theme.PhantomKicker
import local.phantom.companion.core.design.theme.PhantomProse
import local.phantom.companion.core.design.theme.PhantomRadii
import local.phantom.companion.core.design.theme.PhantomSpacings
import local.phantom.companion.core.geo.basemap.OfflineBasemapImporter
import local.phantom.companion.core.geo.basemap.OfflineBasemapInfo
import local.phantom.companion.core.geo.basemap.OfflineBasemapResult
import local.phantom.companion.core.geo.basemap.OfflineBasemapStore
import local.phantom.companion.core.gnss.Band
import local.phantom.companion.core.gnss.Constellation
import local.phantom.companion.core.indoor.pdr.ContinuityState
import local.phantom.companion.core.mobility.TransportMode
import local.phantom.companion.core.net.roadpack.RoadPackLink
import local.phantom.companion.core.net.roadpack.RoadPackLinkClient
import local.phantom.companion.core.net.roadpack.RoadPackSession
import java.io.File

/**
 * Екран чесної позиції.
 *
 * Єдине місце застосунку, де стан навігації показано повністю й без прикрас:
 * загальний вердикт, небесна карта супутників, скільки прийнято й скільки
 * виключено та за що, розбіжність із мережею, режим руху. Для продукту, що
 * продається як стійкий до РЕБ, це не «діагностика для розробника» — це видимий
 * доказ стійкості в момент, коли він найпотрібніший.
 */
@Composable
fun PrecisionScreen(
    onBack: () -> Unit,
    state: PrecisionUiState,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
) {
    val theme = LocalPhantomTheme.current

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(theme.surfaceBase)
            .padding(horizontal = PhantomSpacings.Base),
        // Рейка пливе ПОВЕРХ цього екрана, і про це не знав ніхто: список
        // добігав до низу вікна, а острівець сідав на останній рядок. На
        // апараті 19.09 так зникав цілий рядок паку. Резерв публікує оболонка
        // (`LocalBottomChromeInset`), і поза нею він дорівнює 0.dp — превʼю
        // та онбординг від цього не рухаються.
        contentPadding = PaddingValues(
            bottom = LocalBottomChromeInset.current + PhantomSpacings.Base,
        ),
        verticalArrangement = Arrangement.spacedBy(PhantomSpacings.Small),
    ) {
        item { Spacer(Modifier.height(PhantomSpacings.Base)) }
        item {
            // Only exit from this screen before this: every sibling settings
            // screen has one, this one only had the system back gesture.
            Row(Modifier.fillMaxWidth()) {
                PhantomBackPill(
                    label = stringResource(R.string.precision_back),
                    onBack = onBack,
                )
            }
        }
        item { VerdictBanner(state) }
        item { SourceRow(state) }
        item { ModeCard(state) }
        item { AutonomyCard(state) }
        item { RoadPackCard() }
        item { OfflineBasemapCard() }
        item { CloudPacksCard() }
        item {
            GlassCard(level = GlassLevel.Card, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(PhantomSpacings.Base)) {
                    PhantomKicker(stringResource(R.string.precision_sky_title))
                    Spacer(Modifier.height(PhantomSpacings.Small))
                    SkyPlot(state.satellites, reducedMotion = reducedMotion)
                }
            }
        }
        item { ConstellationLegend(state) }

        if (state.satellites.isNotEmpty()) {
            item {
                PhantomKicker(
                    stringResource(R.string.precision_satellites_header, state.usedCount, state.excludedCount),
                    modifier = Modifier.padding(top = PhantomSpacings.Small, start = PhantomSpacings.Micro),
                )
            }
            items(state.satellites, key = { it.key }) { sat -> SatelliteRowView(sat) }
        }

        item { Spacer(Modifier.height(PhantomSpacings.XLarge)) }
    }
}

/**
 * Головний банер вердикту. Тривожні стани (підміна, глушіння, немає позиції)
 * несуть коралову рамку й ореол; спокійні — тихі. Колір тут не декор, а
 * найшвидший канал: за кермом людина встигає прочитати лише його.
 */
@Composable
private fun VerdictBanner(state: PrecisionUiState) {
    val theme = LocalPhantomTheme.current
    val v = state.verdict
    val accent = if (v.alarming) theme.coral else theme.primary

    GlassCard(
        level = GlassLevel.Hero,
        accentBorder = true,
        glow = v.alarming,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(PhantomSpacings.Large)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(10.dp)
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(accent),
                )
                Spacer(Modifier.width(PhantomSpacings.Small))
                PhantomKicker(stringResource(if (v.alarming) R.string.precision_verdict_alarm else R.string.precision_verdict_status), color = accent)
            }
            Spacer(Modifier.height(PhantomSpacings.Small))
            PhantomProse(v.title, color = theme.inkStrong)
            Spacer(Modifier.height(PhantomSpacings.Micro))
            PhantomCaption(v.explanation, color = theme.inkSecondary)

            state.networkDivergenceKm?.let { km ->
                if (km >= PrecisionUiState.DIVERGENCE_ALARM_KM) {
                    Spacer(Modifier.height(PhantomSpacings.Small))
                    DivergenceLine(km, theme.coral)
                }
            }
        }
    }
}

/** Розбіжність із мережею — головний доказ підміни, показаний числом. */
@Composable
private fun DivergenceLine(km: Double, color: Color) {
    val theme = LocalPhantomTheme.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        PhantomCaption(stringResource(R.string.precision_divergence), color = theme.inkSecondary)
        Spacer(Modifier.width(PhantomSpacings.Micro))
        PhantomDataText(formatKm(LocalContext.current, km), color = color, fontWeight = FontWeight.Bold)
    }
}

/** Звідки взято поточний розвʼязок і супутня метрика точності. */
@Composable
private fun SourceRow(state: PrecisionUiState) {
    val theme = LocalPhantomTheme.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PhantomSpacings.Small),
    ) {
        MetricTile(stringResource(R.string.precision_tile_source), sourceLabel(LocalContext.current, state.fixSource), Modifier.weight(1f))
        MetricTile(
            stringResource(R.string.precision_tile_accuracy),
            state.accuracyM?.let { stringResource(R.string.precision_accuracy_value, it.toInt()) } ?: "—",
            Modifier.weight(1f),
        )
        MetricTile(
            stringResource(R.string.precision_tile_frequency),
            if (state.dualFrequency) "L1+L5" else "L1",
            Modifier.weight(1f),
            highlight = state.dualFrequency,
        )
    }
}

/**
 * Режим руху, визначений з акселерометра (`:core-mobility`). Не декор: коли
 * супутника немає, саме режим підказує численню шляху, як інтерпретувати рух —
 * а користувачу видно, що система розуміє контекст. До першого повного вікна
 * (≈18 с) чесно показує «Визначаю…».
 */
@Composable
private fun ModeCard(state: PrecisionUiState) {
    val theme = LocalPhantomTheme.current
    val known = state.mode != TransportMode.UNKNOWN
    GlassCard(level = GlassLevel.Subtle, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(PhantomSpacings.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhantomKicker(stringResource(R.string.precision_mode_title))
            Spacer(Modifier.weight(1f))
            PhantomDataText(
                modeLabel(LocalContext.current, state.mode),
                color = if (known) theme.ink else theme.inkSecondary,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * АВТОНОМНИЙ ХІД — числення шляху пішохода з `:core-indoor`.
 *
 * **Чому це на цьому екрані, а не десь у діагностиці.** Весь екран — про те, що
 * буде, коли супутника відберуть. Вердикт уже обіцяє «рахую від датчиків руху»;
 * ця картка показує, СКІЛЬКИ саме автономії реально лишилось, і показує це
 * ЗАЗДАЛЕГІДЬ — поки небо ще є. Людина, яка бачить «Тримаю хід · ±11 м» до
 * входу в перехід, розуміє ціну наступних двохсот метрів; людина, якій це
 * покажуть уперше в мить глушіння, вже нічого не встигне.
 *
 * **Чому тут немає координати.** Числення дає ЗМІЩЕННЯ від привʼязки, а не
 * позицію. Показати з нього широту-довготу означало б перетворити «десь за
 * сімдесят метрів звідти» на точку на карті з підписом — тобто вигадати позицію
 * рівно в той момент, коли перевірити її нічим. Тому тут зміщення, напрямок і
 * похибка; і там, де зміщення менше за власну похибку, стоїть слово, а не цифра.
 */
@Composable
private fun AutonomyCard(state: PrecisionUiState) {
    val theme = LocalPhantomTheme.current
    val walk = state.pedestrian
    val s = walk?.state
    val alarming = s == ContinuityState.DRIFTED || s == ContinuityState.UNAVAILABLE
    val confident = s == ContinuityState.HOLDING
    val titleColor = when {
        alarming -> theme.coral
        confident -> theme.primary
        else -> theme.inkSecondary
    }

    GlassCard(level = GlassLevel.Card, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(PhantomSpacings.Base)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PhantomKicker(stringResource(R.string.precision_walk_title))
                Spacer(Modifier.weight(1f))
                PhantomDataText(continuityTitle(walk), color = titleColor, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(PhantomSpacings.Micro))
            PhantomCaption(continuityExplanation(walk), color = theme.inkSecondary)

            if (walk != null && s != ContinuityState.UNAVAILABLE) {
                Spacer(Modifier.height(PhantomSpacings.Small))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PhantomSpacings.Small),
                ) {
                    MetricTile(stringResource(R.string.precision_tile_steps), walk.stepCount.toString(), Modifier.weight(1f))
                    MetricTile(stringResource(R.string.precision_tile_walked), metersLabel(walk.pathLengthM), Modifier.weight(1f))
                    MetricTile(stringResource(R.string.precision_tile_error), accuracyLabel(walk), Modifier.weight(1f), highlight = alarming)
                }

                Spacer(Modifier.height(PhantomSpacings.Small))
                DetailLine(
                    label = stringResource(if (walk.anchoredToWorld) R.string.precision_from_anchor else R.string.precision_from_start),
                    value = displacementLabel(walk),
                    color = if (walk.informative) theme.ink else theme.inkSecondary,
                )
                DetailLine(
                    label = stringResource(R.string.precision_heading),
                    value = headingLabel(walk),
                    color = if (walk.headingRad != null) theme.ink else theme.inkSecondary,
                )
                if (walk.magneticAnomaly) {
                    DetailLine(
                        label = stringResource(R.string.precision_magnetic),
                        value = stringResource(R.string.precision_magnetic_distorted),
                        color = theme.coral,
                    )
                }
                DetailLine(
                    label = stringResource(R.string.precision_level),
                    value = levelLabel(walk) + (verticalTransitLabel(walk)?.let { " · $it" } ?: ""),
                    color = if (walk.sensors.barometer) theme.ink else theme.inkSecondary,
                )
            }

            Spacer(Modifier.height(PhantomSpacings.Micro))
            DetailLine(
                label = "Wi-Fi RTT:",
                value = rttCapabilityLabel(state.rttAvailability),
                color = theme.inkSecondary,
            )
        }
    }
}

/** Рядок «підпис — значення» для карток стану. */
@Composable
private fun DetailLine(label: String, value: String, color: Color) {
    val theme = LocalPhantomTheme.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhantomCaption(label, color = theme.inkSecondary)
        Spacer(Modifier.weight(1f))
        PhantomDataText(value, color = color, fontSize = 11.sp)
    }
}

/**
 * ОФЛАЙН-ДОРОГИ — дорожній пакет (області/країни), спечений поза пристроєм.
 * Після імпорту `LocalRouter` має повний граф і прокладає маршрут без сервера
 * й без інтернету — саме те, що потрібно під РЕБ. Дія, а не статус: тому
 * єдина інтерактивна картка на екрані.
 *
 * Phase 2 (map-plan) — раніше єдиний шлях був ручний файловий провідник; тепер
 * картка спершу питає спарований ПК, що він реально спік (`RoadPackLink`,
 * маніфест-кероване «Оновити дорожній пакет: …», ніколи жорстко вписаний
 * рядок), і streaming-завантажує обраний пакет у той самий `bulkImport` шов.
 * Ручний провідник ЛИШАЄТЬСЯ як резерв — коли ПК не спарований, вимкнений,
 * поза Wi-Fi, або нічого ще не спік.
 */
@Composable
private fun RoadPackCard() {
    val theme = LocalPhantomTheme.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ui by remember { mutableStateOf<RoadPackUi>(RoadPackUi.Idle) }
    var linkState by remember { mutableStateOf<RoadPackLinkUi>(RoadPackLinkUi.Disconnected) }
    var transfer by remember { mutableStateOf<RoadPackTransfer>(RoadPackTransfer.Idle) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            ui = RoadPackUi.Working
            scope.launch {
                ui = when (val r = RoadPackImporter.importFrom(context, uri)) {
                    is RoadPackImporter.Result.Success ->
                        RoadPackUi.Done(
                            r.wayCount, r.hasTags, r.formatVersion,
                            r.stopCount, r.railCount, r.routeCount, r.missingKeys,
                        )
                    is RoadPackImporter.Result.Failure -> RoadPackUi.Failed(r.message)
                }
            }
        }
    }

    // Дешева перевірка «ПК на звʼязку» (пряме читання StateFlow-значення через
    // лямбду — без мережі) кожні кілька секунд, щоб «ПК не на звʼязку» саме
    // вимикалось при перепідключенні (mDNS/WS-реконект), без дії користувача.
    // Маніфест перезапитується лише на переході disconnected → connected, а
    // не на кожному тику — це не мережевий опитувач.
    LaunchedEffect(Unit) {
        var wasConnected = false
        while (isActive) {
            val session = RoadPackLink.session
            val isConnected = session != null && session.connected()
            if (isConnected != wasConnected) {
                wasConnected = isConnected
                linkState = if (session != null && isConnected) {
                    fetchRoadPackManifest(context, session)
                } else {
                    RoadPackLinkUi.Disconnected
                }
            }
            delay(ROAD_PACK_LINK_POLL_MS)
        }
    }

    GlassCard(level = GlassLevel.Card, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(PhantomSpacings.Base)) {
            PhantomKicker(stringResource(R.string.precision_roads_title))
            Spacer(Modifier.height(PhantomSpacings.Micro))
            PhantomCaption(
                stringResource(R.string.precision_roads_explainer),
                color = theme.inkSecondary,
            )

            Spacer(Modifier.height(PhantomSpacings.Small))
            RoadPackLinkSection(
                linkState = linkState,
                transfer = transfer,
                onRetry = {
                    val session = RoadPackLink.session
                    if (session != null) {
                        scope.launch { linkState = fetchRoadPackManifest(context, session) }
                    }
                },
                onPick = { pack ->
                    val session = RoadPackLink.session ?: return@RoadPackLinkSection
                    scope.launch {
                        downloadAndImportRoadPack(
                            context = context,
                            session = session,
                            pack = pack,
                            setTransfer = { transfer = it },
                            setUi = { ui = it },
                        )
                    }
                },
            )

            when (val s = ui) {
                RoadPackUi.Working -> {
                    Spacer(Modifier.height(PhantomSpacings.Small))
                    PhantomCaption(stringResource(R.string.precision_roads_working), color = theme.primary)
                }
                is RoadPackUi.Done -> {
                    Spacer(Modifier.height(PhantomSpacings.Small))
                    PhantomDataText(
                        LocalContext.current.resources.getQuantityString(R.plurals.precision_roads_imported, s.ways, s.ways),
                        color = theme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                    // Скільки доріг внесено — половина правди. Друга половина:
                    // чим саме тепер прокладається маршрут і звідки береться
                    // час. Обидва рядки — inkSecondary, а не coral: це опис
                    // внесеного, не відмова.
                    Spacer(Modifier.height(PhantomSpacings.Micro))
                    // Старий рецепт — єдиний випадок, що говорить coral: він
                    // називає ПРИЧИНУ і має дію («перепекти»). Решта два —
                    // опис внесеного, не відмова, тому inkSecondary.
                    val verdict = packVerdict(
                        s.formatVersion,
                        s.hasTags,
                        local.phantom.companion.core.sensor.nav.RoadMeshStore.DB_VERSION,
                    )
                    PhantomCaption(
                        stringResource(recipeSentence(verdict, s.formatVersion)),
                        color = if (verdict == PackVerdict.STALE_RECIPE) theme.coral
                        else theme.inkSecondary,
                    )
                    // ТРАНСПОРТ. Кажемо ЧИСЛА, і жодного слова про час чи
                    // ціну: OSM їх не несе, а ця сама поверхня колись
                    // друкувала «Проїзд: 8.00 ₴» літералом на кожній зупинці.
                    if (s.stops > 0) {
                        Spacer(Modifier.height(PhantomSpacings.Micro))
                        PhantomCaption(
                            stringResource(R.string.roadpack_done_transit, s.stops, s.rails, s.routes),
                            color = theme.inkSecondary,
                        )
                    }
                    if (s.missingKeys.isNotEmpty()) {
                        Spacer(Modifier.height(PhantomSpacings.Micro))
                        PhantomCaption(
                            stringResource(
                                R.string.roadpack_missing_keys,
                                s.missingKeys.joinToString(", "),
                            ),
                            color = theme.coral,
                        )
                    }
                }
                is RoadPackUi.Failed -> {
                    Spacer(Modifier.height(PhantomSpacings.Small))
                    PhantomCaption(s.msg, color = theme.coral)
                }
                RoadPackUi.Idle -> Unit
            }

            if (ui != RoadPackUi.Working && transfer !is RoadPackTransfer.Downloading) {
                Spacer(Modifier.height(PhantomSpacings.Small))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(theme.primary.copy(alpha = 0.14f))
                        .clickable { picker.launch(arrayOf("*/*")) }
                        .padding(vertical = PhantomSpacings.Small),
                    contentAlignment = Alignment.Center,
                ) {
                    PhantomDataText(
                        stringResource(if (ui is RoadPackUi.Done) R.string.precision_manual_other else R.string.precision_manual_file),
                        color = theme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/** Перелік пакетів ПК, стан звʼязку й перебігу передачі — окремим блоком, щоб
 *  [RoadPackCard] лишався читабельним стейт-машиною екрана, а не мережею. */
@Composable
private fun RoadPackLinkSection(
    linkState: RoadPackLinkUi,
    transfer: RoadPackTransfer,
    onRetry: () -> Unit,
    onPick: (RoadPackLinkClient.RoadPackManifest) -> Unit,
) {
    val theme = LocalPhantomTheme.current
    when (linkState) {
        RoadPackLinkUi.Disconnected -> {
            PhantomCaption(stringResource(R.string.precision_link_off), color = theme.inkSecondary)
        }
        RoadPackLinkUi.Loading -> {
            PhantomCaption(stringResource(R.string.precision_link_loading), color = theme.inkSecondary)
        }
        is RoadPackLinkUi.LinkFailed -> {
            Column {
                PhantomCaption(linkState.message, color = theme.coral)
                Spacer(Modifier.height(PhantomSpacings.Micro))
                PhantomDataText(
                    stringResource(R.string.precision_retry),
                    color = theme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onRetry() },
                )
            }
        }
        is RoadPackLinkUi.Available -> {
            if (linkState.packs.isEmpty()) {
                PhantomCaption(stringResource(R.string.precision_link_empty), color = theme.inkSecondary)
                return
            }
            Column {
                for (pack in linkState.packs) {
                    val downloading = (transfer as? RoadPackTransfer.Downloading)
                        ?.takeIf { it.packId == pack.pack_id }
                    RoadPackRow(pack, downloading?.progress, onPick = { onPick(pack) })
                    Spacer(Modifier.height(PhantomSpacings.Micro))
                }
                (transfer as? RoadPackTransfer.Failed)?.let { f ->
                    PhantomCaption(f.message, color = theme.coral)
                }
            }
        }
    }
}

@Composable
private fun RoadPackRow(
    pack: RoadPackLinkClient.RoadPackManifest,
    downloadProgress: Float?,
    onPick: () -> Unit,
) {
    val theme = LocalPhantomTheme.current
    val label = roadPackOfferLabel(LocalContext.current, pack)
    val enabled = downloadProgress == null && pack.format_supported
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(theme.primary.copy(alpha = if (enabled) 0.14f else 0.06f))
            .clickable(enabled = enabled) { onPick() }
            .padding(horizontal = PhantomSpacings.Small, vertical = PhantomSpacings.Small),
    ) {
        Column {
            PhantomDataText(
                label,
                color = if (enabled) theme.primary else theme.inkSecondary,
                fontWeight = FontWeight.Medium,
            )
            if (!pack.format_supported) {
                PhantomCaption(
                    stringResource(R.string.precision_pack_unsupported),
                    color = theme.coral,
                )
            } else if (downloadProgress != null) {
                PhantomCaption(
                    stringResource(R.string.precision_downloading, (downloadProgress * 100).toInt()),
                    color = theme.inkSecondary,
                )
            }
        }
    }
}

private sealed interface RoadPackUi {
    data object Idle : RoadPackUi
    data object Working : RoadPackUi
    /** [hasTags] — чи ніс внесений пакет теги доріг. Не помилка й не попередження:
     *  національне випікання (`~/phantom_ai/road-mesh-bake`) наразі тегів не
     *  кладе взагалі, тож «без тегів» — звичайний випадок, а не рідкісний. Але
     *  наслідок для оператора реальний, і мовчати про нього не можна: без тегів
     *  маршрут іде лише по довжині, а час — плаский здогад. */
    data class Done(
        val ways: Int,
        val hasTags: Boolean,
        val formatVersion: Int = 0,
        /** Транспортний скелет пакета. Нулі — «пакет цього не ніс». */
        val stops: Int = 0,
        val rails: Int = 0,
        val routes: Int = 0,
        /** Ключі, на які пристрій уміє діяти, а пакет їх не несе. */
        val missingKeys: List<String> = emptyList(),
    ) : RoadPackUi
    data class Failed(val msg: String) : RoadPackUi
}

/** Стан списку пакетів ПК — окремий від [RoadPackUi], яке описує наслідок
 *  імпорту незалежно від джерела байтів (ручний файл чи мережа). */
private sealed interface RoadPackLinkUi {
    data object Disconnected : RoadPackLinkUi
    data object Loading : RoadPackLinkUi
    data class Available(val packs: List<RoadPackLinkClient.RoadPackManifest>) : RoadPackLinkUi
    data class LinkFailed(val message: String) : RoadPackLinkUi
}

private sealed interface RoadPackTransfer {
    data object Idle : RoadPackTransfer
    data class Downloading(val packId: String, val progress: Float) : RoadPackTransfer
    data class Failed(val packId: String, val message: String) : RoadPackTransfer
}

private const val ROAD_PACK_LINK_POLL_MS = 4_000L

private suspend fun fetchRoadPackManifest(context: android.content.Context, session: RoadPackSession): RoadPackLinkUi {
    val baseUrl = session.baseUrl()
    val jwt = session.deviceJwt()
    if (baseUrl == null || jwt == null) return RoadPackLinkUi.Disconnected
    return runCatching { session.client.listPacks(baseUrl, jwt) }.fold(
        onSuccess = { RoadPackLinkUi.Available(it) },
        onFailure = { RoadPackLinkUi.LinkFailed(it.message ?: context.getString(R.string.precision_link_failed)) },
    )
}

/**
 * Завантажити пакет із ПК у тимчасовий файл, перевірити хеш (усередині
 * [RoadPackLinkClient.downloadPack]) і лише тоді влити в [RoadMeshStore] тим
 * самим [RoadPackImporter.importFromFile], яким уже йде ручний шлях —
 * атомарна установка: половина пакета або пошкоджений пакет не має шляху
 * дістатись до `bulkImport`.
 */
private suspend fun downloadAndImportRoadPack(
    context: android.content.Context,
    session: RoadPackSession,
    pack: RoadPackLinkClient.RoadPackManifest,
    setTransfer: (RoadPackTransfer) -> Unit,
    setUi: (RoadPackUi) -> Unit,
) {
    val baseUrl = session.baseUrl()
    val jwt = session.deviceJwt()
    if (baseUrl == null || jwt == null) {
        setTransfer(RoadPackTransfer.Failed(pack.pack_id, context.getString(R.string.precision_link_off_dot)))
        return
    }
    setTransfer(RoadPackTransfer.Downloading(pack.pack_id, 0f))
    val dest = File(context.cacheDir, "roadpack_link_${pack.pack_id}.db")
    val result = session.client.downloadPack(baseUrl, jwt, pack, dest) { progress ->
        setTransfer(RoadPackTransfer.Downloading(pack.pack_id, progress))
    }
    when (result) {
        is RoadPackLinkClient.DownloadResult.Success -> {
            setTransfer(RoadPackTransfer.Idle)
            setUi(RoadPackUi.Working)
            setUi(
                when (val imported = RoadPackImporter.importFromFile(context, result.file)) {
                    is RoadPackImporter.Result.Success -> RoadPackUi.Done(
                        imported.wayCount, imported.hasTags, imported.formatVersion,
                        imported.stopCount, imported.railCount, imported.routeCount,
                        imported.missingKeys,
                    )
                    is RoadPackImporter.Result.Failure -> RoadPackUi.Failed(imported.message)
                },
            )
        }
        is RoadPackLinkClient.DownloadResult.Failure -> {
            setTransfer(RoadPackTransfer.Failed(pack.pack_id, result.message))
        }
    }
}

/** «Оновити дорожній пакет: Україна, 1,5 ГБ, спечено 24.07» — з полів
 *  маніфесту, ніколи жорстко вписаний рядок. `internal`, як і решту чистих
 *  підписів нижче — тестується окремо від Compose. */
internal fun roadPackOfferLabel(context: android.content.Context, pack: RoadPackLinkClient.RoadPackManifest): String {
    val size = byteSizeLabel(context, pack.bytes_total)
    val date = roadPackBakedDateLabel(pack.baked_at)
    return if (date != null) {
        context.getString(R.string.precision_offer_dated, pack.label, size, date)
    } else {
        context.getString(R.string.precision_offer, pack.label, size)
    }
}

/** "2026-07-24T10:41:16Z" → "24.07" — без парсера дат заради однієї підстрічки. */
internal fun roadPackBakedDateLabel(isoUtc: String): String? {
    if (isoUtc.length < 10 || isoUtc[4] != '-' || isoUtc[7] != '-') return null
    val month = isoUtc.substring(5, 7)
    val day = isoUtc.substring(8, 10)
    return "$day.$month"
}

/**
 * ОФЛАЙН-ОСНОВА — файл тайлів (`.mbtiles`), спечений поза пристроєм
 * (`planetiler` на phantom-oracle, `styles/OFFLINE.md` у `:feature-stream`).
 *
 * Брат-близнюк [RoadPackCard]: та сама дія («внести файл вручну»), інша доля
 * байтів. Дорожній пакет ВЛИВАЄТЬСЯ в граф і сам файл більше не потрібен;
 * mbtiles має ЛИШИТИСЬ собою назавжди — `MapCanvas` (`:feature-stream`)
 * відкриває його напряму під час малювання (`mbtiles:///абсолютний/шлях`).
 *
 * Показує ПРАВДУ про внесене: територію й масштаби з `metadata` пакета, не
 * «офлайн увімкнено» без змісту. Мовчить лише про мережевий доступ до ПК —
 * на відміну від дорожніх пакетів, файл основи важить гігабайти й приходить
 * лише вручну, тож [RoadPackLinkSection]-аналога тут немає.
 */
@Composable
private fun OfflineBasemapCard() {
    val theme = LocalPhantomTheme.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { OfflineBasemapStore(context) }
    var current by remember { mutableStateOf(store.info.value) }
    var working by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(store) { store.info.collect { current = it } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            working = true
            failure = null
            scope.launch {
                when (val r = OfflineBasemapImporter.importFrom(context, uri)) {
                    is OfflineBasemapResult.Success -> store.save(r.info)
                    is OfflineBasemapResult.Failure -> failure = r.message
                }
                working = false
            }
        }
    }

    GlassCard(level = GlassLevel.Card, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(PhantomSpacings.Base)) {
            PhantomKicker(stringResource(R.string.offline_basemap_title))
            Spacer(Modifier.height(PhantomSpacings.Micro))
            PhantomCaption(stringResource(R.string.offline_basemap_explainer), color = theme.inkSecondary)

            val snapshot = current
            Spacer(Modifier.height(PhantomSpacings.Small))
            when {
                working -> PhantomCaption(stringResource(R.string.offline_basemap_working), color = theme.primary)
                snapshot != null -> PhantomDataText(
                    offlineBasemapSummary(context, snapshot),
                    color = theme.primary,
                    fontWeight = FontWeight.Medium,
                )
                else -> PhantomCaption(stringResource(R.string.offline_basemap_absent), color = theme.inkSecondary)
            }
            failure?.let {
                Spacer(Modifier.height(PhantomSpacings.Micro))
                PhantomCaption(it, color = theme.coral)
            }

            if (!working) {
                Spacer(Modifier.height(PhantomSpacings.Small))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(theme.primary.copy(alpha = 0.14f))
                        .clickable { picker.launch(arrayOf("*/*")) }
                        .padding(vertical = PhantomSpacings.Small),
                    contentAlignment = Alignment.Center,
                ) {
                    PhantomDataText(
                        stringResource(
                            if (snapshot != null) {
                                R.string.offline_basemap_replace_button
                            } else {
                                R.string.offline_basemap_add_button
                            },
                        ),
                        color = theme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/** «Внесена територія · z0-14 · 1,3 ГБ» — з полів пакета, ніколи жорстко вписаний рядок. */
internal fun offlineBasemapSummary(context: android.content.Context, info: OfflineBasemapInfo): String {
    val size = byteSizeLabel(context, info.bytes)
    val name = info.name?.takeIf { it.isNotBlank() } ?: context.getString(R.string.precision_basemap_default_name)
    return "$name · z${info.minZoom}-${info.maxZoom} · $size"
}

/** GB from 1 GB up, else whole MB; separators follow the app language. */
internal fun byteSizeLabel(context: android.content.Context, bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    return if (gb >= 1.0) {
        context.getString(R.string.precision_size_gb, gb)
    } else {
        context.getString(R.string.precision_size_mb, bytes / 1_000_000.0)
    }
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    val theme = LocalPhantomTheme.current
    GlassCard(level = GlassLevel.Subtle, modifier = modifier) {
        Column(Modifier.padding(PhantomSpacings.Small)) {
            PhantomKicker(label)
            Spacer(Modifier.height(PhantomSpacings.Micro))
            PhantomDataText(
                value,
                color = if (highlight) theme.primary else theme.ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** Скільки супутників з кожної системи реально в розвʼязку. */
@Composable
private fun ConstellationLegend(state: PrecisionUiState) {
    if (state.satellites.isEmpty()) return
    val theme = LocalPhantomTheme.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = PhantomSpacings.Micro),
        horizontalArrangement = Arrangement.spacedBy(PhantomSpacings.Base),
    ) {
        for ((c, rows) in state.byConstellation) {
            Column {
                PhantomKicker(constellationLabel(LocalContext.current, c))
                PhantomDataText(
                    "${rows.count { it.used }}/${rows.size}",
                    color = theme.ink,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/** Один рядок таблиці супутників: система, смуга, сила, стан. */
@Composable
private fun SatelliteRowView(sat: SatelliteRow) {
    val theme = LocalPhantomTheme.current
    val stateColor = when {
        sat.exclusionReason != null -> theme.coral
        sat.used -> theme.primary
        else -> theme.inkSecondary
    }
    GlassCard(level = GlassLevel.Subtle, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(PhantomSpacings.Small)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhantomDataText(
                    "${constellationLabel(LocalContext.current, sat.constellation)} ${sat.svid}",
                    color = theme.ink,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.width(72.dp),
                )
                PhantomCaption(bandLabel(sat.band), color = theme.inkSecondary, modifier = Modifier.width(44.dp))

                // Стовпчик сили сигналу.
                Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(theme.glassBorder)) {
                    Box(
                        Modifier
                            .fillMaxWidth(sat.strength)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(stateColor),
                    )
                }
                Spacer(Modifier.width(PhantomSpacings.Small))
                PhantomDataText("${sat.cn0DbHz.toInt()}", color = theme.inkSecondary, modifier = Modifier.width(28.dp))
            }
            // Причина виключення — під рядком, кораловим: видно не лише «кого», а «за що».
            sat.exclusionReason?.let { reason ->
                Spacer(Modifier.height(2.dp))
                PhantomCaption(reason, color = theme.coral)
            }
        }
    }
}

// ————————————————————————————————————————————————————————————————
// Підписи — чиста функція, без залежностей від Compose (тестується окремо).
// ————————————————————————————————————————————————————————————————

internal fun sourceLabel(context: android.content.Context, source: local.phantom.companion.core.gnss.FixSource): String = when (source) {
    local.phantom.companion.core.gnss.FixSource.GNSS_RAW -> context.getString(R.string.precision_src_gnss)
    local.phantom.companion.core.gnss.FixSource.GNSS_FUSED -> context.getString(R.string.precision_src_gnss_fused)
    local.phantom.companion.core.gnss.FixSource.DEAD_RECKONING -> context.getString(R.string.precision_src_dead)
    local.phantom.companion.core.gnss.FixSource.WIFI_RTT -> "Wi-Fi RTT"
    local.phantom.companion.core.gnss.FixSource.ROAD_MATCH -> context.getString(R.string.precision_src_road)
    local.phantom.companion.core.gnss.FixSource.NETWORK -> context.getString(R.string.precision_src_network)
    local.phantom.companion.core.gnss.FixSource.NONE -> "—"
}

internal fun constellationLabel(context: android.content.Context, c: Constellation): String = when (c) {
    Constellation.GPS -> "GPS"
    Constellation.GLONASS -> context.getString(R.string.precision_const_glonass)
    Constellation.GALILEO -> "GAL"
    Constellation.BEIDOU -> "BDS"
    Constellation.QZSS -> "QZS"
    Constellation.SBAS -> "SBAS"
    Constellation.IRNSS -> "IRN"
    Constellation.UNKNOWN -> "?"
}

internal fun modeLabel(context: android.content.Context, m: TransportMode): String = when (m) {
    TransportMode.STILL -> context.getString(R.string.precision_mode_still)
    TransportMode.WALKING -> context.getString(R.string.precision_mode_walking)
    TransportMode.RUNNING -> context.getString(R.string.precision_mode_running)
    TransportMode.CYCLING -> context.getString(R.string.precision_mode_cycling)
    TransportMode.CAR -> context.getString(R.string.precision_mode_car)
    TransportMode.TRANSIT -> context.getString(R.string.precision_mode_transit)
    TransportMode.METRO -> context.getString(R.string.precision_mode_metro)
    TransportMode.RAIL -> context.getString(R.string.precision_mode_rail)
    TransportMode.MARINE -> context.getString(R.string.precision_mode_marine)
    TransportMode.FLIGHT -> context.getString(R.string.precision_mode_flight)
    TransportMode.UNKNOWN -> context.getString(R.string.precision_mode_unknown)
}

internal fun bandLabel(b: Band): String = when (b) {
    Band.L1 -> "L1"
    Band.L2 -> "L2"
    Band.L5 -> "L5"
    Band.E5B -> "E5b"
    Band.B2 -> "B2"
    Band.UNKNOWN -> "—"
}

/** Формат відстані: метри, кілометри або тисячі км — без «12270.0 км». */
internal fun formatKm(context: android.content.Context, km: Double): String = when {
    km >= 1000 -> context.getString(R.string.precision_km_thousand, (km / 1000).toInt())
    km >= 10 -> context.getString(R.string.precision_km_whole, km.toInt())
    else -> context.getString(R.string.precision_km_one, km)
}
