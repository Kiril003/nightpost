package local.phantom.companion.feature.precision

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import local.phantom.companion.core.design.component.GlassCard
import local.phantom.companion.core.design.component.GlassLevel
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.PhantomCaption
import local.phantom.companion.core.design.theme.PhantomDataText
import local.phantom.companion.core.design.theme.PhantomKicker
import local.phantom.companion.core.design.theme.PhantomSpacings
import local.phantom.companion.core.geo.basemap.BasemapPackGate
import local.phantom.companion.core.net.geosrc.SourceUnavailable
import local.phantom.companion.core.data.geocoder.GeocoderPackGate
import local.phantom.companion.core.geo.terrain.TerrainPackGate
import local.phantom.companion.core.net.pack.CountryPackLink
import local.phantom.companion.core.net.pack.ForgeCatalog
import local.phantom.companion.core.net.pack.ForgePackCatalogEntry
import local.phantom.companion.core.net.pack.ForgePackKind
import local.phantom.companion.core.net.pack.PackDownload
import local.phantom.companion.core.net.pack.PackFetcher
import local.phantom.companion.core.net.pack.WorldCatalog
import local.phantom.companion.core.net.pack.WorldPackCatalogClient
import local.phantom.companion.core.net.pack.WorldPackCatalogEntry
import local.phantom.companion.core.net.pack.WorldPackKind
import local.phantom.companion.core.net.pack.WorldPackLink
import local.phantom.companion.feature.precision.worldpack.RoadMeshPackGate

/**
 * ХМАРНІ ПАКЕТИ — the internet-catalog sibling of the two manual/PC cards
 * above: same destination (offline basemap, offline roads), a different
 * source of bytes. [WorldPackLink.baseUrl] is empty until the owner
 * decides where the bucket lives (see the pricing note in the wave report,
 * not in code) — this card then honestly says "not configured" instead of
 * pretending a catalog exists.
 *
 * Every state this card can show is downstream of [PackFetcher] (which
 * already resumes, verifies sha256, and never invents an unbacked percent —
 * `WorldPackGateTest` pins that), so the only new risk here is display: a
 * row must never show a percent for a state that isn't [PackDownload.Downloading].
 *
 * Підпис звіряється до всього іншого (`WorldPackTrust`), тож картка має ДВА
 * різновиди «не вийшло»: мережевий, з посиланням на повтор, і недоведений, де
 * повтор нічого не змінить.
 */
@Composable
internal fun CloudPacksCard() {
    val theme = LocalPhantomTheme.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val catalogClient = remember { WorldPackCatalogClient(baseUrl = { WorldPackLink.baseUrl() }) }
    val basemapFetcher = remember {
        PackFetcher(baseUrl = { WorldPackLink.baseUrl() }, gate = BasemapPackGate(context))
    }
    val roadMeshFetcher = remember {
        PackFetcher(baseUrl = { WorldPackLink.baseUrl() }, gate = RoadMeshPackGate(context))
    }

    var catalogState by remember { mutableStateOf<CloudCatalogUi>(CloudCatalogUi.Loading) }
    var downloads by remember { mutableStateOf<Map<String, PackDownload>>(emptyMap()) }
    // A pack whose row was tapped on a metered connection and is waiting on a
    // second, explicit tap. PackFetcher itself never asks about network state
    // (see its "ЗАКОН ЛІЧИЛЬНИКА" note) — refusing metered data is the
    // caller's job, and this card used to skip that job entirely and hand it
    // a hardcoded `allowMetered = false` that nothing downstream ever reads.
    var pendingMetered by remember { mutableStateOf<WorldPackCatalogEntry?>(null) }

    // Пакети кузні — окремий підписаний файл, той самий ключ і та сама брама.
    val geocoderFetcher = remember {
        PackFetcher(baseUrl = { WorldPackLink.baseUrl() }, gate = GeocoderPackGate(context))
    }
    val terrainFetcher = remember {
        PackFetcher(baseUrl = { WorldPackLink.baseUrl() }, gate = TerrainPackGate(context))
    }
    var forgePacks by remember { mutableStateOf<List<ForgePackCatalogEntry>>(emptyList()) }
    var forgeDownloads by remember { mutableStateOf<Map<String, PackDownload>>(emptyMap()) }

    suspend fun refresh() {
        catalogState = CloudCatalogUi.Loading
        catalogState = when (val c = catalogClient.catalog()) {
            WorldCatalog.NotConfigured -> CloudCatalogUi.NotConfigured
            is WorldCatalog.Ok -> CloudCatalogUi.Available(c.packs)
            is WorldCatalog.Unavailable -> when (c.reason) {
                // «Спробувати ще раз» тут було б знущанням: підпис не
                // зʼявиться від повторного запиту, і ключ у збірці теж.
                is SourceUnavailable.Unproven -> CloudCatalogUi.Refused(c.reason.detail)
                else -> CloudCatalogUi.Failed(c.reason.detail)
            }
        }
    }

    suspend fun refreshForge() {
        forgePacks = (catalogClient.forgeCatalog() as? ForgeCatalog.Ok)?.packs.orEmpty()
    }

    LaunchedEffect(Unit) {
        refresh()
        refreshForge()
    }

    // Рід, якого ця збірка не вміє внести, НЕ зʼїжджає мовчки в чужу браму:
    // саме так один невідомий рядок став би чужим пакетом на диску.
    fun forgeFetcherFor(kind: ForgePackKind) = when (kind) {
        ForgePackKind.GEOCODER -> geocoderFetcher
        ForgePackKind.TERRAIN_DEM -> terrainFetcher
        ForgePackKind.BASEMAP_PMTILES -> null
        ForgePackKind.TRANSIT_GTFS -> null
        // Пак країни ставить ВЛАСНИЙ фетчер `:app`: `CountryPackStore` володіє
        // каталогом на диску, і другий писар туди не потрібен.
        ForgePackKind.COUNTRY_PLACES -> CountryPackLink.fetcher()
    }

    fun startForge(entry: ForgePackCatalogEntry) {
        val fetcher = forgeFetcherFor(entry.kind) ?: return
        scope.launch {
            fetcher.install(entry.id, allowMetered = false) { state ->
                forgeDownloads = forgeDownloads + (entry.id to state)
            }
        }
    }

    fun fetcherFor(kind: WorldPackKind) =
        if (kind == WorldPackKind.BASEMAP) basemapFetcher else roadMeshFetcher

    fun start(entry: WorldPackCatalogEntry, allowMetered: Boolean) {
        pendingMetered = null
        scope.launch {
            fetcherFor(entry.kind).install(entry.id, allowMetered = allowMetered) { state ->
                downloads = downloads + (entry.id to state)
            }
        }
    }

    fun requestStart(entry: WorldPackCatalogEntry) {
        if (isActiveNetworkMetered(context)) {
            pendingMetered = entry
        } else {
            start(entry, allowMetered = false)
        }
    }

    GlassCard(level = GlassLevel.Card, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(PhantomSpacings.Base)) {
            PhantomKicker(stringResource(R.string.cloud_packs_title))
            Spacer(Modifier.height(PhantomSpacings.Micro))
            PhantomCaption(stringResource(R.string.cloud_packs_explainer), color = theme.inkSecondary)
            Spacer(Modifier.height(PhantomSpacings.Small))

            when (val cs = catalogState) {
                CloudCatalogUi.Loading -> PhantomCaption(
                    stringResource(R.string.cloud_packs_loading),
                    color = theme.inkSecondary,
                )
                CloudCatalogUi.NotConfigured -> PhantomCaption(
                    stringResource(R.string.cloud_packs_not_configured),
                    color = theme.inkSecondary,
                )
                is CloudCatalogUi.Refused -> PhantomCaption(cs.message, color = theme.coral)
                is CloudCatalogUi.Failed -> Column {
                    PhantomCaption(cs.message, color = theme.coral)
                    Spacer(Modifier.height(PhantomSpacings.Micro))
                    PhantomDataText(
                        stringResource(R.string.cloud_packs_action_retry),
                        color = theme.primary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { scope.launch { refresh() } },
                    )
                }
                is CloudCatalogUi.Available -> {
                    if (cs.packs.isEmpty()) {
                        PhantomCaption(stringResource(R.string.cloud_packs_empty), color = theme.inkSecondary)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            for (entry in cs.packs) {
                                CloudPackRow(entry, downloads[entry.id], onTap = { requestStart(entry) })
                                if (pendingMetered?.id == entry.id) {
                                    MeteredConfirmRow(
                                        onConfirm = { start(entry, allowMetered = true) },
                                        onCancel = { pendingMetered = null },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (forgePacks.isNotEmpty()) {
                Spacer(Modifier.height(PhantomSpacings.Small))
                PhantomKicker(stringResource(R.string.forge_packs_title))
                Spacer(Modifier.height(PhantomSpacings.Micro))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (entry in forgePacks) {
                        ForgePackRow(
                            entry = entry,
                            download = forgeDownloads[entry.id],
                            supported = forgeFetcherFor(entry.kind) != null,
                            onTap = { startForge(entry) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ForgePackRow(
    entry: ForgePackCatalogEntry,
    download: PackDownload?,
    supported: Boolean,
    onTap: () -> Unit,
) {
    val theme = LocalPhantomTheme.current
    val busy = download is PackDownload.Checking ||
        download is PackDownload.Downloading ||
        download is PackDownload.Verifying
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(theme.primary.copy(alpha = if (busy || !supported) 0.06f else 0.14f))
            .clickable(enabled = !busy && supported) { onTap() }
            .padding(horizontal = PhantomSpacings.Small, vertical = PhantomSpacings.Small),
    ) {
        Column(Modifier.fillMaxWidth()) {
            // Рід + країна в один рядок не влазить: «МІСЦЯ Й ВУЛИЦІ КРАЇНИ ·
            // Австрія — місця й». Стандарт ролі тепер ставить трикрапку, тож
            // зріз принаймні видно; але тут читають САМЕ назву, і два рядки
            // показують її цілою. Стеля лишається — інакше довга назва
            // розсуває рядок списку.
            PhantomDataText(
                forgePackKindLabel(entry.kind) + " · " + entry.labelUa,
                color = if (busy || !supported) theme.inkSecondary else theme.primary,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
            )
            PhantomCaption(
                if (!supported) {
                    stringResource(R.string.forge_packs_kind_unsupported)
                } else {
                    forgePackStateLabel(entry, download)
                },
                color = cloudPackStateColor(download, theme),
            )
        }
    }
}

@Composable
private fun forgePackKindLabel(kind: ForgePackKind): String = stringResource(
    when (kind) {
        ForgePackKind.GEOCODER -> R.string.forge_packs_kind_geocoder
        ForgePackKind.TERRAIN_DEM -> R.string.forge_packs_kind_terrain
        ForgePackKind.BASEMAP_PMTILES -> R.string.forge_packs_kind_basemap
        ForgePackKind.TRANSIT_GTFS -> R.string.forge_packs_kind_transit
        ForgePackKind.COUNTRY_PLACES -> R.string.forge_packs_kind_places
    },
)

@Composable
private fun forgePackStateLabel(entry: ForgePackCatalogEntry, download: PackDownload?): String = when (download) {
    is PackDownload.Checking -> stringResource(R.string.cloud_packs_state_checking)
    is PackDownload.Verifying -> stringResource(R.string.cloud_packs_state_verifying)
    is PackDownload.Downloading -> stringResource(
        R.string.cloud_packs_state_downloading,
        download.percent,
        byteSizeLabel(LocalContext.current, download.doneBytes),
        byteSizeLabel(LocalContext.current, download.totalBytes),
    )
    is PackDownload.Installed -> stringResource(R.string.cloud_packs_state_installed)
    is PackDownload.Failed -> download.reason.detail
    else -> {
        val size = byteSizeLabel(LocalContext.current, entry.bytes)
        val date = roadPackBakedDateLabel(entry.bakedAt)
        if (date != null) {
            stringResource(R.string.cloud_packs_size_baked, size, date)
        } else {
            stringResource(R.string.cloud_packs_size_only, size)
        }
    }
}

private sealed interface CloudCatalogUi {
    data object Loading : CloudCatalogUi
    data object NotConfigured : CloudCatalogUi
    data class Available(val packs: List<WorldPackCatalogEntry>) : CloudCatalogUi

    /** Мережевий збій — повтор має сенс, тож поруч стоїть посилання. */
    data class Failed(val message: String) : CloudCatalogUi

    /** Каталог не довів, що він наш. Повтор сенсу не має, і посилання немає. */
    data class Refused(val message: String) : CloudCatalogUi
}

@Composable
private fun CloudPackRow(
    entry: WorldPackCatalogEntry,
    download: PackDownload?,
    onTap: () -> Unit,
) {
    val theme = LocalPhantomTheme.current
    val busy = download is PackDownload.Checking ||
        download is PackDownload.Downloading ||
        download is PackDownload.Verifying
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(theme.primary.copy(alpha = if (busy) 0.06f else 0.14f))
            .clickable(enabled = !busy) { onTap() }
            .padding(horizontal = PhantomSpacings.Small, vertical = PhantomSpacings.Small),
    ) {
        Column(Modifier.fillMaxWidth()) {
            PhantomDataText(
                cloudPackKindLabel(entry.kind) + " · " + entry.labelUa,
                color = if (busy) theme.inkSecondary else theme.primary,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
            )
            PhantomCaption(cloudPackStateLabel(entry, download), color = cloudPackStateColor(download, theme))
        }
    }
}

@Composable
private fun MeteredConfirmRow(onConfirm: () -> Unit, onCancel: () -> Unit) {
    val theme = LocalPhantomTheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = PhantomSpacings.Small, top = 2.dp, bottom = PhantomSpacings.Micro),
        horizontalArrangement = Arrangement.spacedBy(PhantomSpacings.Small),
    ) {
        PhantomCaption(stringResource(R.string.cloud_packs_metered_warning), color = theme.coral)
        PhantomDataText(
            stringResource(R.string.cloud_packs_action_metered_confirm),
            color = theme.primary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable { onConfirm() },
        )
        PhantomDataText(
            stringResource(R.string.cloud_packs_action_cancel),
            color = theme.inkSecondary,
            modifier = Modifier.clickable { onCancel() },
        )
    }
}

/** [PackFetcher] refuses to ask this by design (see its "ЗАКОН ЛІЧИЛЬНИКА" note) —
 *  the caller owns network policy, and this card is the caller. */
private fun isActiveNetworkMetered(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
    return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}

@Composable
private fun cloudPackKindLabel(kind: WorldPackKind): String = stringResource(
    if (kind == WorldPackKind.BASEMAP) R.string.cloud_packs_kind_basemap else R.string.cloud_packs_kind_road_mesh,
)

@Composable
private fun cloudPackStateColor(
    download: PackDownload?,
    theme: local.phantom.companion.core.design.theme.PhantomThemeSpec,
) =
    when (download) {
        is PackDownload.Failed -> theme.coral
        is PackDownload.Installed -> theme.primary
        else -> theme.inkSecondary
    }

/**
 * The one line a row shows below its label — offer (size + baked date)
 * before a tap, live byte counts during, verdict after. [PackDownload.Downloading]
 * is the only branch that prints a percent, and it is only ever reached with
 * a positive [PackDownload.Downloading.totalBytes] ([WorldPackGate] refuses
 * anything else before [PackFetcher] gets this far) — there is no "unknown
 * size" branch here because there is no code path that can produce one.
 */
@Composable
private fun cloudPackStateLabel(entry: WorldPackCatalogEntry, download: PackDownload?): String = when (download) {
    null -> cloudPackOfferLabel(entry)
    is PackDownload.NotConfigured -> cloudPackOfferLabel(entry)
    is PackDownload.Checking -> stringResource(R.string.cloud_packs_state_checking)
    is PackDownload.Verifying -> stringResource(R.string.cloud_packs_state_verifying)
    is PackDownload.Available -> cloudPackOfferLabel(entry)
    is PackDownload.Downloading -> stringResource(
        R.string.cloud_packs_state_downloading,
        download.percent,
        byteSizeLabel(LocalContext.current, download.doneBytes),
        byteSizeLabel(LocalContext.current, download.totalBytes),
    )
    is PackDownload.Installed -> stringResource(R.string.cloud_packs_state_installed)
    is PackDownload.Failed -> download.reason.detail
}

@Composable
private fun cloudPackOfferLabel(entry: WorldPackCatalogEntry): String {
    val size = byteSizeLabel(LocalContext.current, entry.bytes)
    val date = roadPackBakedDateLabel(entry.bakedAt)
    return if (date != null) {
        stringResource(R.string.cloud_packs_size_baked, size, date)
    } else {
        stringResource(R.string.cloud_packs_size_only, size)
    }
}
