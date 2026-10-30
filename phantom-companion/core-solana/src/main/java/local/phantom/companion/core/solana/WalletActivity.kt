package local.phantom.companion.core.solana

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import local.phantom.companion.core.design.component.PhantomDialog
import local.phantom.companion.core.design.component.PrimaryButton
import local.phantom.companion.core.design.component.SwitchRow
import local.phantom.companion.core.design.haptics.PhantomHaptics
import local.phantom.companion.core.design.kit.ListRow
import local.phantom.companion.core.design.kit.ListRowHeight
import local.phantom.companion.core.design.permission.PermissionReason
import local.phantom.companion.core.design.permission.rememberPermissionGate
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.theme.PhantomTheme
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.design.R as DesignR
import kotlin.coroutines.resume

class WalletActivity : FragmentActivity() {

    // Реєстрація результату мусить відбутись до STARTED — тому поле, а не lazy.
    private val embed = WalletEmbed(this)
    private val door get() = embed.door

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        paintBars(WalletLook.skin(SolanaLayer.skin.value).spec.isDark)
        val open = Opened.read(intent::getStringExtra, intent.getBooleanExtra(EXTRA_SEAL_PICK, false))
        setContent {
            // Та сама тема й той самий розмір тексту, що застосунок щойно показав; не сказав — ніч.
            val fromApp by SolanaLayer.skin.collectAsState()
            val skin = WalletLook.skin(fromApp)
            val dark = skin.spec.isDark
            LaunchedEffect(dark) { paintBars(dark) }
            PhantomTheme(spec = skin.spec, fontScale = skin.fontScale, accent = skin.accent) {
                if (!SolanaLayer.installed) WalletPreparing() else WalletScreen(door, open, onBack = ::finish)
            }
        }
    }

    /** Значки рядка стану — за темою гаманця, а не за системною: нічний гаманець удень лишається нічним. */
    private fun paintBars(dark: Boolean) {
        val clear = android.graphics.Color.TRANSPARENT
        val bars = if (dark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
    }

    /** `coin`/`amount` — чернетка асистента або те, що людина набрала в розмові; діалог лише заповнюється ними. */
    data class Opened(
        val peer: String?,
        val sealHash: String?,
        val sealLabel: String,
        val coin: Coin? = null,
        val amount: String? = null,
        val typedTo: SolanaKey? = null,
        val sealPick: Boolean = false,
        val cash: Boolean = false,
        val byAssistant: Boolean = false,
        /** Частка рахунку: розмова, куди лягає картка, і id рахунку (збірка 18). */
        val chat: String? = null,
        val ref: String? = null,
        /** [typedTo] — гаманець закладу з рахунку зустрічі, а не вписаний руками. */
        val venue: Boolean = false,
        val scanned: Boolean = false,
        val reference: SolanaKey? = null,
        val memo: String? = null,
    ) {
        /** Чи екран відкрили платити частку рахунку: тоді маршрут «заряджено» до закриття діалогу чи оплати. */
        val billArmed: Boolean get() = chat != null || ref != null || reference != null || memo != null

        /** Діалог, який відкривається сам: людина з чернетки, готівкою чи переказом. */
        fun firstDialog(contacts: List<SolanaContact>): Pair<SolanaContact, Boolean>? {
            if (peer != null) return contacts.firstOrNull { it.id == peer && it.address != null }?.let { it to cash }
            val typed = typedTo ?: return null
            return (contacts.firstOrNull { it.address == typed } ?: SolanaContact("", typed.short(), typed)) to false
        }

        /**
         * Маршрут рахунку — лише переказу тій самій людині (чи адресі), для якої екран
         * відкрили, і лише поки [armed]: скасований відбиток і друга спроба так само йдуть
         * у групу з `ref`, а закритий аркуш чи заплачена частка — ні ([armedAfter]): інакше
         * переказ «за інше» тій самій людині ліг би в групу й зарахувався б як частка.
         * Готівка рахунку не платить.
         */
        fun routeFor(c: SolanaContact, cash: Boolean, armed: Boolean): PayRoute {
            val same = if (peer != null) c.id == peer else typedTo != null && c.address == typedTo
            return if (same && !cash && armed) PayRoute(chat, ref, reference, memo) else PayRoute()
        }

        /** Звідки адреса в діалозі [c] — для сторожа перед відбитком. */
        fun sourceFor(c: SolanaContact): AddressSource = when {
            c.id.isNotBlank() -> AddressSource.CONVERSATION
            venue && typedTo != null && c.address == typedTo -> AddressSource.VENUE
            scanned && typedTo != null && c.address == typedTo -> AddressSource.SCANNED
            else -> AddressSource.TYPED
        }

        /** Монета й сума з чернетки — лише в той самий діалог, що відкрився сам. */
        fun fillFor(c: SolanaContact, cash: Boolean): Pair<Coin?, String>? {
            val same = if (peer != null) c.id == peer else typedTo != null && c.address == typedTo
            return if (same && cash == this.cash) coin to amount.orEmpty() else null
        }

        companion object {
            fun read(extra: (String) -> String?, sealPick: Boolean = false): Opened {
                val rawRef = extra(EXTRA_PAY_REF)?.takeIf { it.isNotBlank() }
                // Кривий ref — не «картка в групу без ref» (а саме так її зарахував би веб
                // як переказ без привʼязки): весь маршрут рахунку геть, як і в `payAsk`.
                val crooked = rawRef != null && !PaymentFrame.isRef(rawRef)
                val chat = extra(EXTRA_PAY_CHAT)?.takeIf { it.isNotBlank() && !crooked }
                // Заклад — лише з групою: без неї його картку нікому показати.
                val venue = extra(EXTRA_PAY_VENUE)?.takeIf { chat != null }?.let(SolanaKey::parse)
                return Opened(
                    peer = extra(EXTRA_PEER),
                    sealHash = extra(EXTRA_SEAL_HASH),
                    sealLabel = extra(EXTRA_SEAL_LABEL).orEmpty(),
                    coin = Coin.of(extra(EXTRA_COIN) ?: extra(EXTRA_PAY_COIN)),
                    amount = extra(EXTRA_AMOUNT) ?: extra(EXTRA_PAY_AMOUNT),
                    typedTo = venue ?: extra(EXTRA_TO)?.let(SolanaKey::parse),
                    sealPick = sealPick,
                    cash = extra(EXTRA_CASH) == "1",
                    byAssistant = extra(EXTRA_COIN) != null,
                    chat = chat,
                    ref = rawRef.takeIf { !crooked },
                    venue = venue != null,
                    scanned = extra(EXTRA_SCANNED) == "1",
                    reference = extra(EXTRA_PAY_REFERENCE)?.takeIf { extra(EXTRA_SCANNED) == "1" }?.let(SolanaKey::parse),
                    memo = extra(EXTRA_PAY_MEMO)?.takeIf { extra(EXTRA_SCANNED) == "1" && it.isNotBlank() },
                )
            }

            /** Маршрут після відповіді гаманця: лише гроші, що пішли, «розряджають» рахунок. */
            fun armedAfter(armed: Boolean, outcome: WalletOutcome<*>): Boolean = armed && outcome !is WalletOutcome.Done
        }
    }

    companion object {
        const val EXTRA_PEER = "peer_id"
        const val EXTRA_SEAL_HASH = "seal_sha256"
        const val EXTRA_SEAL_LABEL = "seal_label"
        const val EXTRA_PAY_COIN = "pay_coin"
        const val EXTRA_PAY_AMOUNT = "pay_amount"
        const val EXTRA_PAY_CHAT = "pay_chat"
        const val EXTRA_PAY_REF = "pay_ref"
        /** Гаманець закладу з рахунку зустрічі — окремо від [EXTRA_TO], бо його не вписували руками. */
        const val EXTRA_PAY_VENUE = "pay_venue"
        const val EXTRA_COIN = "draft_coin"
        const val EXTRA_AMOUNT = "draft_amount"
        const val EXTRA_TO = "draft_to"
        const val EXTRA_SEAL_PICK = "seal_pick_photo"
        const val EXTRA_CASH = "draft_cash"
        const val EXTRA_SCANNED = "pay_scanned"
        const val EXTRA_PAY_REFERENCE = "pay_reference"
        const val EXTRA_PAY_MEMO = "pay_memo"

        fun scannedExtras(link: PayLink): Map<String, String> = buildMap {
            put(EXTRA_TO, link.to.base58)
            put(EXTRA_SCANNED, "1")
            link.coin?.let { put(EXTRA_PAY_COIN, it.wire) }
            link.amount?.let { put(EXTRA_PAY_AMOUNT, it) }
            link.reference?.let { put(EXTRA_PAY_REFERENCE, it.base58) }
            link.memo?.let { put(EXTRA_PAY_MEMO, it) }
        }

        fun scannedIntent(context: Context, link: PayLink): Intent =
            SolanaLayer.walletIntent(context).apply { scannedExtras(link).forEach { (k, v) -> putExtra(k, v) } }

        /** Лише відкриває екран із заповненими полями; переказ чи купюра йдуть після кнопки й відбитка там. */
        fun draftIntent(context: Context, draft: PaymentDraft, cash: Boolean = false): Intent =
            SolanaLayer.walletIntent(context, draft.peerId).apply { draftExtras(draft, cash).forEach { (k, v) -> putExtra(k, v) } }

        fun draftExtras(draft: PaymentDraft, cash: Boolean): Map<String, String> = buildMap {
            draft.peerId?.let { put(EXTRA_PEER, it) }
            put(EXTRA_COIN, draft.coin.wire)
            draft.amount?.let { put(EXTRA_AMOUNT, it) }
            if (draft.source == AddressSource.TYPED) put(EXTRA_TO, draft.to.base58)
            if (cash) put(EXTRA_CASH, "1")
        }

        fun sealPhotoIntent(context: Context): Intent =
            SolanaLayer.walletIntent(context).putExtra(EXTRA_SEAL_PICK, true)
    }
}

/** Шар Solana ще не встав — чесне речення на чорнилі, без вигаданого балансу. */
/**
 * Двері гаманця для будь-якої FragmentActivity — відбиток, вікно гаманця MWA,
 * FLAG_SECURE. Створюється ПОЛЕМ активності: [ActivityResultSender] реєструє
 * результат, а це можна лише до STARTED.
 *
 * Збірка 17: гаманець — окрема вкладка оболонки (Розмови · Мапа · Гаманець ·
 * Профіль), а не рядок у Профілі. Вкладка й [WalletActivity] малюють ту саму
 * поверхню тими самими дверима; окрема активність лишається для переходів із
 * розмови (переказ людині, відбиток файла, чернетка асистента).
 */
class WalletEmbed(private val activity: FragmentActivity) {

    private val sender = ActivityResultSender(activity)

    internal val door: WalletDoor = object : WalletDoor {
        override val hasScreenLock: Boolean
            get() = BiometricManager.from(activity).canAuthenticate(VaultKeys.authenticators()) == BiometricManager.BIOMETRIC_SUCCESS

        override fun host(titleRes: Int, detail: String?) = WalletHost(sender) { confirmUser(activity.getString(titleRes), detail, hasScreenLock) }

        override fun secure(on: Boolean) {
            val window = activity.window
            if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /**
     * Згода відбитком/PIN на підпис ПОЗА екраном гаманця (бали за оплачену частку
     * з Профілю): те саме вікно, ті самі правила — без блокування екрана лише
     * після явного «так» на попередження. Кличуть з головного потоку.
     */
    suspend fun confirm(title: String, detail: String? = null): Boolean = confirmUser(title, detail, door.hasScreenLock)

    /** Гаманець у вкладці: без стрілки «назад» — корінь вкладки, BACK веде оболонка. */
    @Composable
    fun Pane() {
        if (!SolanaLayer.installed) WalletPreparing() else WalletScreen(door, WalletActivity.Opened(null, null, ""), onBack = null)
    }

    private suspend fun confirmUser(title: String, detail: String?, hasScreenLock: Boolean): Boolean =
        SigningConsent.ask(hasScreenLock) { unlock(title, detail) }

    private suspend fun unlock(title: String, detail: String?): Boolean {
        return suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(VaultKeys.authenticators())
            if (detail != null) {
                info.setSubtitle(detail)
                if (SolanaLayer.state.value.cluster == Cluster.DEVNET) info.setDescription(activity.getString(R.string.sol_confirm_test))
            }
            prompt.authenticate(info.build())
        }
    }

}

@Composable
private fun WalletPreparing() {
    val c = LocalPhantomColors.current
    Box(Modifier.fillMaxSize().background(c.canvas).safeDrawingPadding().padding(PhantomSpace.s6)) {
        Text(stringResource(R.string.sol_preparing), style = PhantomText.callout, color = c.text2)
    }
}

@Composable
private fun WalletScreen(door: WalletDoor, open: WalletActivity.Opened, onBack: (() -> Unit)?) {
    val state by SolanaLayer.state.collectAsState()
    val revision by SolanaLayer.revision.collectAsState()
    LaunchedEffect(state.address, state.cluster) { SolanaLayer.refreshBalances() }
    WalletBody(
        door,
        state,
        open,
        onBack,
        pending = remember(revision) { { sig: String -> SolanaLayer.paymentStatus(sig) == PaymentStatus.PENDING } },
    )
}

/**
 * Екран гаманця з готового стану — його ж малюють кадри WalletLookShotsTest.
 * Обличчя екрана — картки активів, їхні дії й історія; решта гаманця — за рядком «Ще».
 * [people] — хто є в месенджері; [points], [activity], [split] — за замовчуванням із шару.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun WalletBody(
    door: WalletDoor,
    state: WalletState,
    open: WalletActivity.Opened,
    onBack: (() -> Unit)?,
    people: suspend () -> List<SolanaContact> = { SolanaLayer.config?.contacts?.invoke().orEmpty() },
    points: suspend () -> WalletPoints? = { SolanaLayer.config?.points?.invoke() },
    activity: suspend (Coin, Boolean) -> List<ActivityLine>? = { coin, fresh -> SolanaLayer.activity(coin, fresh) },
    known: (Coin) -> List<ActivityLine>? = { SolanaLayer.knownActivity(it) },
    split: (() -> Unit)? = SolanaLayer.config?.split,
    refreshBalances: suspend () -> Unit = { SolanaLayer.refreshBalances() },
    connect: suspend () -> WalletOutcome<*> = { SolanaLayer.connectExternal(door.host(R.string.sol_confirm_title)) },
    recoveryWords: suspend () -> List<String>? = {
        if (door.host(R.string.sol_confirm_words).confirm()) withContext(Dispatchers.IO) { SolanaLayer.backupWords() } else null
    },
    markBackedUp: () -> Unit = { SolanaLayer.markBackedUp() },
    requestTestUsdc: suspend () -> Unit = { SolanaLayer.requestTestUsdc() },
    pending: (String) -> Boolean = { false },
) {
    val ctx = LocalContext.current
    val c = LocalPhantomColors.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    var note by remember { mutableStateOf<String?>(null) }
    var handoff by remember { mutableStateOf<Handoff?>(null) }
    var askMainnet by remember { mutableStateOf(false) }
    var more by rememberSaveable { mutableStateOf(WalletShows.moreAtStart(open, state)) }
    var refresh by remember { mutableIntStateOf(0) }
    var pulling by remember { mutableStateOf(false) }
    var contacts by remember { mutableStateOf<List<SolanaContact>?>(null) }
    var pts by remember { mutableStateOf<WalletPoints?>(null) }
    var focus by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var sendCoin by remember { mutableStateOf<Coin?>(null) }
    var requestCoin by remember { mutableStateOf<Coin?>(null) }
    var adding by remember { mutableStateOf(false) }
    var arranging by remember { mutableStateOf<WalletCard?>(null) }
    var restoring by remember { mutableStateOf(false) }
    var restoringFile by remember { mutableStateOf(false) }
    var backupSheet by remember { mutableStateOf(false) }
    var savingCopy by remember { mutableStateOf(false) }
    var checkingCopy by remember { mutableStateOf(false) }
    var words by remember { mutableStateOf<List<String>?>(null) }
    val showWords: () -> Unit = {
        scope.launch { words = runCatching { recoveryWords() }.getOrElse { note = ctx.getString(if (it is NoScreenLock) R.string.sol_lock_required else R.string.sol_vault_locked); null } }
    }
    val pay = rememberPayState(open)

    fun say(outcome: WalletOutcome<*>, done: String? = null) {
        handoff = null
        note = when (outcome) {
            is WalletOutcome.Done -> done
            WalletOutcome.NoWallet -> ctx.getString(R.string.sol_no_wallet)
            WalletOutcome.Declined -> ctx.getString(R.string.sol_declined)
            is WalletOutcome.Failed -> when (outcome.why) {
                "offline" -> ctx.getString(R.string.sol_offline)
                VaultLocked.WHY -> ctx.getString(R.string.sol_vault_locked)
                NoScreenLock.WHY -> ctx.getString(R.string.sol_lock_required)
                "other_account" -> ctx.getString(R.string.sol_other_account)
                "cash_low" -> ctx.getString(R.string.sol_cash_low)
                "cash_other_coin" -> ctx.getString(R.string.sol_cash_other_coin)
                "no_notes" -> ctx.getString(R.string.sol_cash_no_notes)
                "cash_no_sol" -> ctx.getString(R.string.sol_cash_no_sol)
                "builtin_only" -> ctx.getString(R.string.sol_cash_builtin_only)
                "sponsor_sponsor_quota", "sponsor_sponsor_daily_budget" -> ctx.getString(R.string.sol_sponsor_quota)
                "sponsor_ata_already_sponsored" -> ctx.getString(R.string.sol_sponsor_ata)
                else -> if (outcome.why.startsWith("sponsor_")) ctx.getString(R.string.sol_sponsor_refused, outcome.why.removePrefix("sponsor_")) else SolanaTrouble.words(ctx, outcome.why)
            }
        }
    }

    LaunchedEffect(state.address) {
        val list = withContext(Dispatchers.Default) { runCatching { people() }.getOrDefault(emptyList()) }
        contacts = list
        pay.prefillFrom(list)
    }
    LaunchedEffect(refresh) { pts = runCatching { points() }.getOrNull() }
    // Що щойно сталося, живе нагорі — туди й повертаємось, щойно воно зʼявилось.
    LaunchedEffect(handoff, note) { if (handoff != null || note != null) scroll.animateScrollTo(0) }

    val names: (SolanaKey) -> String? = remember(contacts) {
        val known = contacts.orEmpty().mapNotNull { p -> p.address?.let { it.base58 to p.name } }.toMap()
        val name: (SolanaKey) -> String? = { key -> known[key.base58] }
        name
    }
    val primary = WalletLook.primary(state)
    val cards = WalletCards.of(state, pts)
    val hasPointsCard = cards.any { it is WalletCard.Points }
    var dropping by remember { mutableStateOf(false) }
    val airdrop: @Composable () -> Unit = {
        WalletAction(
            label = stringResource(R.string.sol_airdrop),
            enabled = !dropping,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                dropping = true
                scope.launch {
                    val ok = SolanaLayer.airdrop()
                    say(WalletOutcome.Done(Unit), ctx.getString(if (ok) R.string.sol_airdrop_ok else R.string.sol_airdrop_failed))
                    kotlinx.coroutines.delay(4_000L)
                    refreshBalances()
                    refresh++
                    dropping = false
                }
            },
        )
    }
    Column(Modifier.fillMaxSize().background(c.canvas).safeDrawingPadding()) {
        WalletHeader(stringResource(R.string.sol_title), WalletLook.markLit(state), onBack, test = state.cluster == Cluster.DEVNET) {
            if (onBack == null) SolanaLayer.config?.scan?.let { WalletScanButton(it) }
            if (!hasPointsCard) {
                (pts as? WalletPoints.Shown)?.let { p ->
                    PointsPill(p.total) { focus = WalletCard.Points(p.total).key to (focus?.second ?: 0) + 1 }
                }
            }
        }
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = pulling,
            onRefresh = {
                pulling = true
                scope.launch {
                    refreshBalances()
                    refresh++
                    pulling = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = PhantomSpace.gutterPhone),
                verticalArrangement = Arrangement.spacedBy(PhantomSpace.s5),
            ) {
                handoff?.let { HandoffCard(it) } ?: note?.let { WalletNote(it) }
                WalletLockGate(door, state, ::say)
                WalletCarousel(
                    state = state,
                    cards = cards,
                    points = pts,
                    names = names,
                    refresh = refresh,
                    activity = activity,
                    known = known,
                    onSend = { sendCoin = it },
                    onRequest = { requestCoin = it },
                    onSplit = split?.let { go -> { go(); onBack?.invoke() } },
                    focus = focus,
                    onAdd = { adding = true },
                    onArrange = { arranging = it },
                    pending = pending,
                ) {
                    WalletReturnCard(door, state, ::say)
                    state.backupShown.takeIf { state.kind == WalletKind.BUILT_IN && state.builtIn != null }?.let { backup ->
                        BackupRow(backup, onSave = showWords, onStatus = { backupSheet = true })
                    }
                }
                if (state.address != null && state.cluster == Cluster.DEVNET && state.balances?.get(Coin.SOL) == 0L && !more) airdrop()
                UtilityCard(
                    state = state,
                    onFaucet = { scope.launch { requestTestUsdc(); refresh++ } },
                    more = more,
                ) { more = !more }
                if (more) {
                    CashSection(door, state, primary == WalletLook.Primary.FILL_POCKET, ::say)
                    PeopleList(state, contacts.orEmpty(), pay)
                    WalletGroup(title = stringResource(R.string.sol_network), padded = false) {
                        SwitchRow(
                            label = stringResource(NetworkSwitch.label(state.cluster)),
                            body = stringResource(NetworkSwitch.subtitle(state.cluster)),
                            checked = NetworkSwitch.checked(state.cluster),
                            onChange = { want ->
                                when (val step = NetworkSwitch.onToggle(want)) {
                                    NetworkSwitch.Step.ConfirmMainnet -> askMainnet = true
                                    is NetworkSwitch.Step.Set -> SolanaLayer.setCluster(step.cluster)
                                }
                            },
                        )
                        if (state.address != null && state.cluster == Cluster.DEVNET) {
                            WalletDivider()
                            Box(Modifier.padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s3)) { airdrop() }
                        }
                    }
                    AddressSharingSection()
                    SourceSection(door, state, ::say, showWords)
                    SealSection(door, state, open, ::say)
                    SeekerSection(door, state)
                    if (WalletShows.licence(SolanaLayer.config?.licencePurchase == true, state)) LicenceSection(door, state, ::say)
                }
                Spacer(Modifier.height(PhantomSpace.s8))
            }
        }
    }
    if (adding) {
        AddSheet(
            hidden = state.shelf.hidden,
            builtIn = state.kind == WalletKind.BUILT_IN,
            seeker = remember { SolanaLayer.seedVaultPresent(ctx) },
            onAdd = { what, key ->
                if (what == AddWhat.TOKEN) SolanaLayer.addMint(key) else SolanaLayer.watchAddress(key)
                scope.launch { refreshBalances(); refresh++ }
            },
            onConnect = { scope.launch { say(connect(), null) } },
            onRestore = { restoring = true },
            onRestoreFile = { restoringFile = true },
            onShow = SolanaLayer::showCard,
            onDismiss = { adding = false },
        )
    }
    if (restoring) RestoreDialog(door, onDismiss = { restoring = false }, say = ::say)
    words?.let { list ->
        DisposableEffect(Unit) {
            door.secure(true)
            onDispose { door.secure(false) }
        }
        BackupWordsSheet(list, onDone = { markBackedUp(); words = null }, onDismiss = { words = null })
    }
    if (restoringFile) OpenCopyFlow(door, onClose = { restoringFile = false }, say = ::say)
    if (backupSheet) {
        BackupSheet(
            status = state.backupShown,
            onCheck = { backupSheet = false; checkingCopy = true },
            onSave = { backupSheet = false; savingCopy = true },
            onRestore = { backupSheet = false; restoringFile = true },
            onRestoreWords = { backupSheet = false; restoring = true },
            onDismiss = { backupSheet = false },
        )
    }
    if (savingCopy) SaveCopyFlow(door, onClose = { savingCopy = false }, say = ::say)
    if (checkingCopy) CheckCopyFlow(door, onClose = { checkingCopy = false }, say = ::say)
    arranging?.let { card ->
        val keys = WalletCards.of(state, pts).filter { it != WalletCard.Add }.map { it.key }
        val at = keys.indexOf(card.key)
        ArrangeSheet(
            name = CardNames.of(card.key),
            earlier = at > 0,
            later = at in 0 until keys.lastIndex,
            onMove = { SolanaLayer.moveCard(card.key, it, keys) },
            onHide = { SolanaLayer.hideCard(card.key) },
            onDismiss = { arranging = null },
        )
    }
    if (askMainnet) {
        PhantomDialog(
            title = stringResource(R.string.sol_mainnet_ask_title),
            text = stringResource(R.string.sol_mainnet_ask_text),
            confirmLabel = stringResource(R.string.sol_mainnet_ask_yes),
            dismissLabel = stringResource(R.string.sol_cancel),
            // Справжні гроші, які не повернути, — та сама вага, що й «відновити гаманець».
            danger = true,
            onDismiss = { askMainnet = false },
            onConfirm = {
                askMainnet = false
                SolanaLayer.setCluster(NetworkSwitch.confirmed())
            },
        )
    }
    sendCoin?.let { coin ->
        SendPicker(
            coin = coin,
            contacts = contacts.orEmpty(),
            onPick = { p ->
                sendCoin = null
                pay.start(p, coin)
            },
            onDismiss = { sendCoin = null },
            onScan = SolanaLayer.config?.scan?.takeIf { onBack == null }?.let { s ->
                {
                    sendCoin = null
                    s()
                }
            },
        )
    }
    requestCoin?.let { coin -> RequestSheet(state, coin) { requestCoin = null } }
    PaySheet(
        door = door,
        state = state,
        open = open,
        pay = pay,
        say = ::say,
        handed = { h ->
            note = null
            handoff = h
        },
        sent = {
            scope.launch {
                refreshBalances()
                refresh++
            }
        },
    )
}

/** Копія гаманця — окремим рядком під діями (W4): не збережено — щит попереджає й веде зберегти. */
@Composable
private fun BackupRow(backup: BackupStatus, onSave: () -> Unit, onStatus: () -> Unit) {
    val c = LocalPhantomColors.current
    val copy = backup as? BackupStatus.Copy
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val ctx = LocalContext.current
    val sub = when {
        backup == BackupStatus.None -> stringResource(R.string.sol_backup_not_saved)
        copy != null -> stringResource(R.string.sol_backup_status_copy) + " · " + CopyPlace.line(copy.atMs, CopyPlace.label(ctx, copy.place), locale)
        else -> stringResource(R.string.sol_backup_status_words)
    }
    ListRow(
        title = stringResource(R.string.sol_backup_row_title),
        sub = sub,
        padded = false,
        onClick = if (backup == BackupStatus.None) onSave else onStatus,
        modifier = Modifier.testTag("wallet-backup"),
        leading = {
            Box(Modifier.size(PhantomSize.avatarM).clip(CircleShape).background(c.raised), contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.lc_shield),
                    contentDescription = null,
                    tint = if (backup == BackupStatus.None) c.warning else c.text2,
                    modifier = Modifier.size(PhantomSize.iconM),
                )
            }
        },
        trailing = {
            Icon(
                painter = painterResource(DesignR.drawable.lc_chevron_right),
                contentDescription = null,
                tint = c.text3,
                modifier = Modifier.size(PhantomSize.iconM),
            )
        },
    )
}

@Composable
private fun UtilityCard(
    state: WalletState,
    onFaucet: () -> Unit,
    more: Boolean,
    onMore: () -> Unit,
) {
    val c = LocalPhantomColors.current
    val showFaucet = state.address != null && state.cluster == Cluster.DEVNET && state.kind == WalletKind.BUILT_IN
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("wallet-utility")
            .clip(RoundedCornerShape(PhantomRadius.card))
            .background(c.surface),
    ) {
        if (showFaucet) {
            val status = state.faucet
            val enabled = status == FaucetStatus.READY || status == FaucetStatus.RETRY
            val secondary = when (status) {
                FaucetStatus.OFF -> stringResource(R.string.sol_faucet_off)
                FaucetStatus.LIMITED -> stringResource(R.string.sol_faucet_wait)
                FaucetStatus.RETRY -> stringResource(R.string.sol_faucet_retry)
                FaucetStatus.SENDING -> stringResource(R.string.sol_faucet_sending)
                FaucetStatus.READY -> null
            }
            ListRow(
                title = stringResource(R.string.sol_faucet_get),
                height = ListRowHeight.One,
                divider = true,
                enabled = enabled,
                onClick = onFaucet,
                trailing = secondary?.let { text ->
                    { Text(text = text, style = PhantomText.footnote, color = c.text3) }
                },
            )
        }
        ListRow(
            title = stringResource(R.string.sol_more),
            height = ListRowHeight.One,
            divider = false,
            onClick = onMore,
            trailing = {
                Icon(
                    painter = painterResource(DesignR.drawable.lc_chevron_right),
                    contentDescription = null,
                    tint = c.text3,
                    modifier = Modifier.size(PhantomSize.iconM).graphicsLayer { rotationZ = if (more) 90f else 0f },
                )
            },
        )
    }
}

/**
 * Без блокування екрана вбудований гаманець не створюється й не підписує; без зерна — «Створити»
 * за відбитком/PIN. Стан блокування перечитується щоразу, як людина повертається з Налаштувань.
 */
@Composable
private fun WalletLockGate(door: WalletDoor, state: WalletState, say: (WalletOutcome<*>, String?) -> Unit) {
    if (state.kind != WalletKind.BUILT_IN) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var locked by remember { mutableStateOf(door.hasScreenLock) }
    var busy by remember { mutableStateOf(false) }
    LifecycleResumeEffect(door) {
        locked = door.hasScreenLock
        onPauseOrDispose {}
    }
    if (!locked) {
        Column(verticalArrangement = Arrangement.spacedBy(PhantomSpace.s3)) {
            WalletWarning(stringResource(R.string.sol_lock_required))
            WalletAction(
                label = stringResource(R.string.sol_lock_settings),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)) }
                        .onFailure { runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } }
                },
            )
        }
        return
    }
    if (state.address != null) return
    WalletAction(
        label = stringResource(R.string.sol_create),
        enabled = !busy,
        accent = true,
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            busy = true
            scope.launch {
                if (door.host(R.string.sol_confirm_title).confirm()) say(SolanaLayer.createBuiltIn(), null)
                busy = false
            }
        },
    )
}

/** Що екран гаманця показує в клині «гроші без інтернету»; решта коду живе, лише не на виду. */
internal object WalletShows {
    /** Купівля ліцензії — не на шляху; незавершену покупку все одно видно, щоб її можна було довести. */
    fun licence(purchaseOn: Boolean, state: WalletState): Boolean = purchaseOn && state.pendingPurchase != null

    /** Прийшли з розмови печаткою чи з незавершеною покупкою — «Ще» вже розкрите, бо саме там ці розділи. */
    fun moreAtStart(open: WalletActivity.Opened, state: WalletState): Boolean =
        open.sealHash != null || open.sealPick || state.pendingPurchase != null
}

private fun clusterName(c: Cluster) = if (c == Cluster.MAINNET) R.string.sol_network_mainnet else R.string.sol_network_devnet

/**
 * Перемикач мережі на екрані гаманця.
 *
 * Збірка 16: підписом рядка стояла ПОТОЧНА мережа («Тестова (devnet) — гроші
 * несправжні»), а перемикач означав «основна увімкнена» — на devnet це
 * читалось як «тестову вимкнено». Тепер підпис — одне незмінне питання
 * «Справжні гроші (mainnet)», поточна мережа — підрядком, а до справжніх
 * грошей веде лише підтвердження. Назад, у тестову, — одразу.
 */
internal object NetworkSwitch {
    sealed interface Step {
        data object ConfirmMainnet : Step
        data class Set(val cluster: Cluster) : Step
    }

    @Suppress("UNUSED_PARAMETER")
    fun label(c: Cluster): Int = R.string.sol_network_real
    fun subtitle(c: Cluster): Int = clusterName(c)
    fun checked(c: Cluster): Boolean = c == Cluster.MAINNET
    fun onToggle(wantMainnet: Boolean): Step = if (wantMainnet) Step.ConfirmMainnet else Step.Set(Cluster.DEVNET)
    fun confirmed(): Cluster = Cluster.MAINNET
}

/** Чий гаманець: підключити свій (MWA, Seed Vault на Seeker), відновити зі слів або повернутися до вбудованого. */
@Composable
private fun SourceSection(door: WalletDoor, state: WalletState, say: (WalletOutcome<*>, String?) -> Unit, onWords: () -> Unit) {
    if (state.address == null) return
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    if (state.kind == WalletKind.BUILT_IN) {
        val seeker = remember { SolanaLayer.seedVaultPresent(ctx) }
        WalletGroup(title = stringResource(R.string.sol_external)) {
            WalletAction(
                label = stringResource(if (busy) R.string.sol_connecting else if (seeker) R.string.sol_seed_vault else R.string.sol_connect_external),
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    busy = true
                    scope.launch { say(SolanaLayer.connectExternal(door.host(R.string.sol_confirm_title)), null); busy = false }
                },
            )
            WalletAction(stringResource(R.string.sol_restore), modifier = Modifier.fillMaxWidth(), onClick = { restoring = true })
        }
        WalletGroup(title = stringResource(R.string.sol_copy_title)) {
            WalletAction(stringResource(R.string.sol_recovery_words), modifier = Modifier.fillMaxWidth(), onClick = onWords)
            WalletAction(stringResource(R.string.sol_copy_save), modifier = Modifier.fillMaxWidth(), onClick = { saving = true })
            WalletAction(stringResource(R.string.sol_copy_restore), modifier = Modifier.fillMaxWidth(), onClick = { opening = true })
        }
    } else {
        WalletGroup(title = null) {
            WalletAction(
                label = stringResource(R.string.sol_back_to_builtin),
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    busy = true
                    scope.launch { SolanaLayer.useBuiltIn(door.host(R.string.sol_confirm_title)); busy = false }
                },
            )
        }
    }
    if (restoring) RestoreDialog(door, onDismiss = { restoring = false }, say = say)
    if (saving) SaveCopyFlow(door, onClose = { saving = false }, say = say)
    if (opening) OpenCopyFlow(door, onClose = { opening = false }, say = say)
}

/**
 * Нове зерно лягає лише під ключ, що щойно прийняв відбиток/PIN. null — не дійшло до слів:
 * немає блокування, людина скасувала або ключ не відчинився (причину вже сказано).
 */
internal suspend fun restoreSeed(door: WalletDoor, say: (WalletOutcome<*>, String?) -> Unit, restore: () -> Boolean): Boolean? {
    if (!door.hasScreenLock) return null.also { say(WalletOutcome.Failed(NoScreenLock.WHY), null) }
    if (!door.host(R.string.sol_confirm_title).confirm()) return null.also { say(WalletOutcome.Declined, null) }
    return runCatching { withContext(Dispatchers.Default) { restore() } }
        .getOrElse { say(WalletOutcome.Failed(it.message ?: VaultLocked.WHY), null); null }
}

@Composable
private fun WalletReturnCard(door: WalletDoor, state: WalletState, say: (WalletOutcome<*>, String?) -> Unit) {
    val ctx = LocalContext.current
    var was by remember { mutableStateOf(WalletReturn.expected(ctx)) }
    val now = state.builtIn?.base58
    LaunchedEffect(was, now) {
        if (was != null && was == now) {
            WalletReturn.clear(ctx)
            was = null
        }
    }
    val old = was
    val returned = WalletReturn.state(old, now)
    if (old == null || returned == WalletReturn.State.NONE) return
    var restoring by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var inCopy by remember(old) { mutableStateOf(WalletEscrow.held() != null) }
    val scope = rememberCoroutineScope()
    val c = LocalPhantomColors.current
    ListRow(
        title = stringResource(R.string.sol_return_title),
        sub = stringResource(
            when {
                inCopy -> R.string.sol_return_in_copy
                returned == WalletReturn.State.REPLACED -> R.string.sol_return_other_now
                else -> R.string.sol_return_needs_words
            },
        ),
        padded = false,
        onClick = { choosing = true },
        modifier = Modifier.testTag("wallet-return"),
        leading = {
            Box(Modifier.size(PhantomSize.avatarM).clip(CircleShape).background(c.raised), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.lc_rotate_ccw), null, tint = c.text2, modifier = Modifier.size(PhantomSize.iconM))
            }
        },
        trailing = {
            Icon(painterResource(DesignR.drawable.lc_chevron_right), null, tint = c.text3, modifier = Modifier.size(PhantomSize.iconM))
        },
    )
    if (choosing) {
        WalletSheet(title = stringResource(R.string.sol_return_title), onDismiss = { choosing = false }) {
            if (inCopy) {
                WalletAction(
                    stringResource(R.string.sol_return_from_copy),
                    accent = true,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        choosing = false
                        scope.launch {
                            val words = WalletEscrow.held() ?: return@launch run { inCopy = false }
                            if (restoreSeed(door, say) { SolanaLayer.restore(words) } == true) {
                                WalletEscrow.settled()
                                inCopy = false
                                say(WalletOutcome.Done(Unit), ctx.getString(R.string.sol_restore_ok))
                            }
                        }
                    },
                )
            }
            WalletAction(stringResource(R.string.sol_restore), accent = !inCopy, modifier = Modifier.fillMaxWidth(), onClick = { choosing = false; restoring = true })
            if (returned == WalletReturn.State.REPLACED) {
                WalletAction(
                    stringResource(R.string.sol_return_keep_new),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        choosing = false
                        WalletReturn.clear(ctx)
                        WalletEscrow.settled()
                        was = null
                    },
                )
            }
        }
    }
    if (restoring) RestoreDialog(door, onDismiss = { restoring = false }, say = say)
}

@Composable
private fun RestoreDialog(door: WalletDoor, onDismiss: () -> Unit, say: (WalletOutcome<*>, String?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    val badWords = stringResource(R.string.sol_restore_bad)
    PhantomDialog(
        title = stringResource(R.string.sol_restore),
        confirmLabel = stringResource(R.string.sol_restore),
        dismissLabel = stringResource(R.string.sol_cancel),
        // Поточний гаманець буде замінено — дія, яку не забрати назад.
        danger = true,
        onDismiss = onDismiss,
        onConfirm = {
            scope.launch {
                when (restoreSeed(door, say) { SolanaLayer.restore(text.split(Regex("\\s+"))) }) {
                    true -> {
                        say(WalletOutcome.Done(Unit), ctx.getString(R.string.sol_restore_ok))
                        onDismiss()
                    }
                    false -> bad = true
                    null -> onDismiss()
                }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(PhantomSpace.s3)) {
            WalletWarning(stringResource(R.string.sol_restore_warning))
            WordsField(text, { text = it; bad = false }, stringResource(R.string.sol_restore_hint), if (bad) badWords else null)
            if (bad) Text(badWords, style = PhantomText.footnote, color = LocalPhantomColors.current.danger)
        }
    }
}

/** Вікно зі словами саме несе FLAG_SECURE: прапорець екрана на окреме вікно діалогу не поширюється. */
@Composable
internal fun BackupWordsSheet(words: List<String>, onDone: () -> Unit, onDismiss: () -> Unit) {
    WalletSheet(title = stringResource(R.string.sol_backup_title), onDismiss = onDismiss, secure = true) {
        WalletWarning(stringResource(R.string.sol_backup_warning))
        WordsGrid(words)
        SheetActions(
            confirm = stringResource(R.string.sol_backup_done),
            onConfirm = onDone,
            dismiss = stringResource(R.string.sol_cancel),
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun SeekerSection(door: WalletDoor, state: WalletState) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var verdict by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    fun day(iso: String?): String = iso?.let {
        runCatching { java.time.LocalDate.parse(it).format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)) }.getOrNull() ?: it
    } ?: "—"
    WalletGroup(title = stringResource(R.string.sol_seeker_title)) {
        state.seekerProUntil?.let { WalletLine(stringResource(R.string.sol_seeker_granted, day(it)), quiet = false) }
        state.seekerBadge?.let { b ->
            SolanaNotice.badgeLine(ctx, b)?.let { line ->
                val until = java.time.Instant.ofEpochSecond(b.expires).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
                WalletLine(stringResource(R.string.sol_badge_own, line, day(until)))
            }
        }
        verdict?.let { WalletLine(it, quiet = false, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (state.seekerProUntil == null) {
            WalletAction(
                label = stringResource(if (checking) R.string.sol_seeker_checking else R.string.sol_seeker_check),
                enabled = !checking && state.address != null,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    checking = true
                    scope.launch {
                        val r = SolanaLayer.claimSeeker(door.host(R.string.sol_confirm_seeker))
                        verdict = when (r) {
                            is WalletOutcome.Done -> when (val v = r.value) {
                                is SeekerClaimResult.Granted -> null
                                is SeekerClaimResult.AlreadyUsed ->
                                    if (v.otherWallet) ctx.getString(R.string.sol_seeker_used_elsewhere)
                                    else ctx.getString(R.string.sol_seeker_used, day(v.proUntil))
                                SeekerClaimResult.NotSeeker -> ctx.getString(R.string.sol_seeker_no)
                                SeekerClaimResult.Unavailable -> ctx.getString(R.string.sol_seeker_unknown)
                                is SeekerClaimResult.Refused ->
                                    when (v.reason) {
                                        "not_activated" -> ctx.getString(R.string.sol_seeker_not_activated)
                                        "not_here" -> ctx.getString(R.string.sol_seeker_not_here)
                                        else -> ctx.getString(R.string.sol_failed, v.reason)
                                    }
                            }
                            WalletOutcome.Declined -> ctx.getString(R.string.sol_declined)
                            WalletOutcome.NoWallet -> ctx.getString(R.string.sol_no_wallet)
                            is WalletOutcome.Failed ->
                                if (r.why == "other_account") ctx.getString(R.string.sol_other_account) else ctx.getString(R.string.sol_failed, r.why)
                        }
                        checking = false
                    }
                },
            )
        }
        // Знак для купюр — і після місяця PRO, і без SGT (тоді він каже лише «стейкає SKR»).
        WalletAction(
            label = stringResource(R.string.sol_badge_refresh),
            enabled = !checking && state.address != null,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                checking = true
                scope.launch {
                    val r = SolanaLayer.refreshSeekerBadge(door.host(R.string.sol_confirm_seeker))
                    verdict = when (r) {
                        is WalletOutcome.Done -> when (val v = r.value) {
                            is BadgeRefresh.Fresh -> ctx.getString(R.string.sol_badge_fresh)
                            BadgeRefresh.Nothing -> ctx.getString(R.string.sol_badge_nothing)
                            BadgeRefresh.Unavailable -> ctx.getString(R.string.sol_seeker_unknown)
                            is BadgeRefresh.Refused ->
                                if (v.reason == "not_here") ctx.getString(R.string.sol_seeker_not_here) else ctx.getString(R.string.sol_failed, v.reason)
                        }
                        WalletOutcome.Declined -> ctx.getString(R.string.sol_declined)
                        WalletOutcome.NoWallet -> ctx.getString(R.string.sol_no_wallet)
                        is WalletOutcome.Failed -> when (r.why) {
                            "other_account" -> ctx.getString(R.string.sol_other_account)
                            "no_identity" -> ctx.getString(R.string.sol_badge_no_identity)
                            else -> ctx.getString(R.string.sol_failed, r.why)
                        }
                    }
                    checking = false
                }
            },
        )
    }
}

/**
 * Хто й чим зараз платиться: аркуш суми відкривається з рядка людини, з «Надіслати» на
 * картці або сам — з чернетки розмови. Закритий аркуш «розряджає» рахунок, скасований відбиток — ні.
 */
internal class PayState(private val open: WalletActivity.Opened, prefill: MutableState<Boolean>, billArmed: MutableState<Boolean>) {
    var paying by mutableStateOf<Pair<SolanaContact, Boolean>?>(null)
    var coin by mutableStateOf<Coin?>(null)
    var prefill by prefill
    var billArmed by billArmed

    fun prefillFrom(contacts: List<SolanaContact>) {
        if (paying == null && prefill) open.firstDialog(contacts)?.let { paying = it }
    }

    fun start(p: SolanaContact, coin: Coin? = null, cash: Boolean = false) {
        this.coin = coin
        paying = p to cash
    }
}

@Composable
private fun rememberPayState(open: WalletActivity.Opened): PayState {
    val prefill = rememberSaveable { mutableStateOf(open.peer != null || open.typedTo != null) }
    val armed = rememberSaveable { mutableStateOf(open.billArmed) }
    return remember(open) { PayState(open, prefill, armed) }
}

@Composable
private fun PeopleList(state: WalletState, contacts: List<SolanaContact>, pay: PayState) {
    val cashReady = WalletLook.cashReady(state)
    WalletGroup(title = stringResource(R.string.sol_people_title), padded = false) {
        val inset = Modifier.padding(PhantomSpace.s4)
        if (contacts.isEmpty()) WalletLine(stringResource(R.string.sol_people_empty), modifier = inset, quiet = false)
        val waiting = contacts.count { it.address == null }
        if (waiting > 0) WalletLine(stringResource(R.string.sol_people_waiting, waiting), modifier = inset)
        contacts.filter { it.address != null }.forEachIndexed { i, c ->
            if (i > 0 || waiting > 0) WalletDivider(Modifier.padding(start = PERSON_TEXT_INSET))
            PersonRow(
                contact = c,
                canPay = state.address != null,
                cashReady = cashReady,
                onCash = { pay.start(c, cash = true) },
                onPay = { pay.start(c) },
            )
        }
    }
}

@Composable
private fun PaySheet(
    door: WalletDoor,
    state: WalletState,
    open: WalletActivity.Opened,
    pay: PayState,
    say: (WalletOutcome<*>, String?) -> Unit,
    handed: (Handoff) -> Unit,
    sent: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var history by remember { mutableStateOf<List<SentBefore>?>(null) }
    val nearbyAsk = rememberNearbyAsk()
    LaunchedEffect(pay.paying) {
        history = null
        val c = pay.paying?.first ?: return@LaunchedEffect
        history = withContext(Dispatchers.Default) { SolanaLayer.sentBefore(c.id.ifBlank { null }, c.address!!) }
    }
    pay.paying?.let { (c, cash) ->
        val cashCoin = Coin.of(state.cash.coin)
        val filled = if (pay.prefill) open.fillFor(c, cash) else null
        val route = open.routeFor(c, cash, pay.billArmed)
        AmountSheet(
            title = stringResource(if (cash) R.string.sol_cash_to else R.string.sol_pay_to, c.name),
            kicker = stringResource(
                when {
                    route.chatId != null -> R.string.sol_bill_share_kind
                    filled != null && open.byAssistant -> R.string.sol_draft_by_phantom
                    cash -> R.string.sol_cash_kind
                    else -> R.string.sol_pay_kind
                },
            ),
            cash = cash,
            coins = if (cash) listOfNotNull(cashCoin) else FeeRoute.payableCoins(state),
            cluster = state.cluster,
            initialCoin = filled?.first ?: pay.coin,
            initialText = filled?.second.orEmpty(),
            sponsored = { coin -> !cash && FeeRoute.choose(state.sponsor, state.kind, state.cluster, coin) is FeeRoute.Route.Sponsored },
            guard = { coin, units ->
                TransferGuard.view(c.address!!, open.sourceFor(c), coin, state.cluster, units, history)
            },
            available = { coin -> if (cash) state.cash.units.takeIf { coin == cashCoin && state.cash.checkedAtMs > 0 } else state.balances?.get(coin) },
            recipient = c,
            onDismiss = { pay.paying = null; pay.prefill = false; pay.billArmed = false },
            onPayWithNote = { coin, units, note ->
                pay.paying = null
                pay.prefill = false
                val sum = WalletLook.sum(units, coin)
                val go = {
                    scope.launch {
                        val host = door.host(if (cash) R.string.sol_confirm_cash else R.string.sol_confirm_title, "${c.name} · $sum")
                        if (cash) {
                            when (val r = SolanaLayer.issueCash(host, c.id, c.address!!, units)) {
                                is WalletOutcome.Done -> handed(Handoff(c.name, sum, r.value.payment.signature))
                                else -> say(r, null)
                            }
                        } else {
                            val r = SolanaLayer.payPeer(host, c.id.ifBlank { null }, c.address!!, coin, units, route, note)
                            pay.billArmed = WalletActivity.Opened.armedAfter(pay.billArmed, r)
                            say(r, ctx.getString(PaymentPost.paidLine(r)))
                            if (r is WalletOutcome.Done) sent()
                        }
                    }
                    Unit
                }
                if (cash) nearbyAsk(false, go) else go()
            },
        )
    }
}

/** Людина: пляма тону, імʼя й коротка адреса; під ними — що з нею можна зробити. */
@Composable
private fun PersonRow(contact: SolanaContact, canPay: Boolean, cashReady: Boolean, onCash: () -> Unit, onPay: () -> Unit) {
    val c = LocalPhantomColors.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s3),
        verticalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
    ) {
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
        ) {
            PersonDot(contact.id.ifBlank { contact.name }, contact.name)
            Column(Modifier.weight(1f)) {
                Text(contact.name, style = PhantomText.headline, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(contact.address!!.short(), style = PhantomText.footnote.tnum(), color = c.text3)
            }
        }
        if (canPay) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = PhantomSize.avatarM + PhantomSpace.s3),
                horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
            ) {
                if (cashReady) WalletAction(stringResource(R.string.sol_give_cash), onClick = onCash, accent = true, modifier = Modifier.weight(1f))
                WalletAction(stringResource(R.string.sol_pay), onClick = onPay, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * Аркуш суми: монета, велике поле, мережа словом, сторож — і одна бурштинова дія
 * «Дати купюру · 5 USDC» / «Переказати 5 USDC» на всю ширину.
 */
@Composable
internal fun AmountSheet(
    title: String,
    coins: List<Coin>,
    cluster: Cluster,
    sponsored: (Coin) -> Boolean = { false },
    onDismiss: () -> Unit,
    onPay: (Coin, Long) -> Unit = { _, _ -> },
    onPayWithNote: ((Coin, Long, String?) -> Unit)? = null,
    initialCoin: Coin? = null,
    initialText: String = "",
    kicker: String? = null,
    cash: Boolean = false,
    showNote: Boolean = !cash,
    guard: (Coin, Long?) -> TransferGuard.View? = { _, _ -> null },
    available: (Coin) -> Long? = { null },
    recipient: SolanaContact? = null,
) {
    if (coins.isEmpty()) return
    val ctx = LocalContext.current
    val pc = LocalPhantomColors.current
    var coin by remember { mutableStateOf(initialCoin?.takeIf { it in coins } ?: if (Coin.USDC in coins) Coin.USDC else coins.first()) }
    var text by remember { mutableStateOf(initialText) }
    var noteText by remember { mutableStateOf("") }
    val units = Amounts.toUnits(text, coin)
    val seen = guard(coin, units)
    val bad = if (text.isNotBlank() && units == null) stringResource(R.string.sol_amount_bad) else null
    val have = available(coin)
    val over = units != null && have != null && units > have
    val confirm = when {
        units == null -> stringResource(if (cash) R.string.sol_confirm_cash else R.string.sol_pay)
        cash -> stringResource(R.string.sol_cash_confirm, WalletLook.shown(units, coin), coin.symbol)
        else -> stringResource(R.string.sol_pay_confirm, WalletLook.shown(units, coin), coin.symbol)
    }
    WalletSheet(
        title = if (recipient != null) kicker ?: title else title,
        onDismiss = onDismiss,
        kicker = if (recipient != null) null else kicker,
        test = cluster == Cluster.DEVNET,
        footer = {
            PrimaryButton(
                label = confirm,
                onClick = {
                    units?.let { u ->
                        val cleanNote = noteText.trim().ifEmpty { null }?.takeIf { showNote && FrameNote.isValid(it) }
                        if (onPayWithNote != null) onPayWithNote(coin, u, cleanNote) else onPay(coin, u)
                    }
                },
                enabled = units != null && !over,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        if (recipient != null) {
            ListRow(
                title = recipient.name,
                sub = recipient.address?.short()?.takeIf { it != recipient.name },
                height = ListRowHeight.Two,
                leading = { PersonDot(recipient.id.ifBlank { recipient.name }, recipient.name) },
                modifier = Modifier.testTag("pay-recipient"),
                padded = false,
            )
        }
        if (seen?.fromChat == true) OriginLine(seen.paidBefore)
        if (coins.size > 1) CoinPicker(coins, coin) { coin = it }
        AmountField(value = text, onValueChange = { text = it }, symbol = coin.symbol, label = stringResource(R.string.sol_amount), error = bad)
        if (bad != null) Text(bad, style = PhantomText.footnote, color = pc.danger)
        if (over && have != null) {
            Text(
                stringResource(R.string.sol_amount_over, WalletLook.shown(have, coin), coin.symbol),
                style = PhantomText.footnote,
                color = pc.danger,
                modifier = Modifier.testTag("pay-over-balance"),
            )
        }
        if (showNote) {
            NoteField(
                value = noteText,
                onValueChange = { next ->
                    val single = next.replace("\n", "").replace("\r", "")
                    if (single.length <= FrameNote.MAX) noteText = single
                },
                label = stringResource(R.string.sol_note_label),
            )
        }
        if (cluster != Cluster.DEVNET) NetworkLine(cluster)
        if (sponsored(coin)) WalletLine(stringResource(R.string.sol_fee_sponsored))
        seen?.notes?.takeIf { it.isNotEmpty() }?.let { notes -> GuardNotes(notes.map { it.sentence(ctx) }) }
    }
}

/** Кишеня готівки — серце демо: скільки в ній лежить і скільки купюр ще можна дати. */
@Composable
private fun CashSection(door: WalletDoor, state: WalletState, primary: Boolean, say: (WalletOutcome<*>, String?) -> Unit) {
    val scope = rememberCoroutineScope()
    val c = LocalPhantomColors.current
    var filling by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val nearbyAsk = rememberNearbyAsk()
    val coin = Coin.of(state.cash.coin)
    val builtIn = state.kind == WalletKind.BUILT_IN
    WalletGroup(
        title = stringResource(R.string.sol_cash_title),
    ) {
        if (!builtIn) {
            NearbyRow(nearbyAsk)
            WalletLine(stringResource(R.string.sol_cash_builtin_only), quiet = false)
            return@WalletGroup
        }
        if (coin != null) {
            if (state.cash.checkedAtMs == 0L) {
                WalletLine(stringResource(R.string.sol_cash_not_checked), quiet = false)
            } else {
                Column(Modifier.semantics(mergeDescendants = true) {}) {
                    Text(stringResource(R.string.sol_cash_in_pocket), style = PhantomText.footnote, color = c.text3)
                    MoneyFigure(WalletLook.shown(state.cash.units, coin), coin.symbol, big = false)
                    Text(
                        text = stringResource(
                            R.string.sol_cash_free,
                            WalletLook.shown(WalletLook.freeUnits(state.cash), coin),
                            coin.symbol,
                            WalletLook.freeNotes(state.cash),
                        ),
                        style = PhantomText.footnote.tnum(),
                        color = c.text2,
                    )
                }
            }
        }
        NearbyRow(nearbyAsk)
        Row(horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                WalletMainAction(stringResource(R.string.sol_cash_prepare), primary = primary, enabled = !busy) { filling = true }
            }
            if (coin != null) {
                WalletAction(stringResource(R.string.sol_refresh), enabled = !busy, onClick = {
                    busy = true
                    scope.launch { SolanaLayer.refreshCash(); busy = false }
                })
            }
        }
        if (busy) WalletLine(stringResource(R.string.sol_cash_preparing), quiet = false, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    if (filling) {
        AmountSheet(
            title = stringResource(R.string.sol_cash_prepare),
            coins = Coin.on(state.cluster),
            cluster = state.cluster,
            showNote = false,
            available = { coin -> state.balances?.get(coin) },
            onDismiss = { filling = false },
            onPay = { coin, units ->
                filling = false
                busy = true
                scope.launch { say(SolanaLayer.prepareCash(door.host(R.string.sol_confirm_title), coin, units), null); busy = false }
            },
        )
    }
}

/**
 * Поруч просить дозволи через спільну браму: причина, вікно ОС, після відмови —
 * картка, а коли ОС вікна не дасть — налаштування. [always] — людина сама тиснула
 * «Прийняти»: продовження лише з дозволом. Без [always] розрахунок іде далі в будь-якому разі.
 */
@Composable
private fun rememberNearbyAsk(): (always: Boolean, then: () -> Unit) -> Unit {
    val nearby = SolanaLayer.config?.nearby
    val gate = rememberPermissionGate(
        permissions = nearby?.missingPermissions().orEmpty(),
        reason = PermissionReason(DesignR.drawable.lc_people, R.string.sol_nearby_why_title, R.string.sol_nearby_why),
    )
    return { always, then ->
        if (nearby == null || nearby.missingPermissions().isEmpty() || !(always || nearby.wantedNow())) {
            then()
        } else {
            gate.request { ok -> if (ok || !always) then() }
        }
    }
}

@Composable
private fun NearbyRow(ask: (always: Boolean, then: () -> Unit) -> Unit) {
    val nearby = SolanaLayer.config?.nearby ?: return
    val state by nearby.state.collectAsState()
    when (state) {
        NearbyState.OFF -> Unit
        NearbyState.NO_SERVICES -> WalletLine(stringResource(R.string.sol_nearby_no_services))
        NearbyState.LISTENING -> WalletLine(
            stringResource(R.string.sol_nearby_listening),
            quiet = false,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        NearbyState.NEEDS_PERMISSION, NearbyState.IDLE, NearbyState.HANDED, NearbyState.SEARCHING -> {
            if (state == NearbyState.NEEDS_PERMISSION) WalletLine(stringResource(R.string.sol_nearby_needs))
            WalletAction(stringResource(R.string.sol_nearby_receive), modifier = Modifier.fillMaxWidth(), onClick = { ask(true) { nearby.listen() } })
        }
    }
}

@Composable
private fun SealSection(door: WalletDoor, state: WalletState, open: WalletActivity.Opened, say: (WalletOutcome<*>, String?) -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val pc = LocalPhantomColors.current
    var asking by remember { mutableStateOf(open.sealHash?.let { it to open.sealLabel.ifBlank { it.take(12) } }) }
    var pickPhoto by rememberSaveable { mutableStateOf(open.sealPick) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val hex = withContext(Dispatchers.IO) {
                runCatching {
                    val md = java.security.MessageDigest.getInstance("SHA-256")
                    ctx.contentResolver.openInputStream(uri)!!.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            md.update(buf, 0, n)
                        }
                    }
                    md.digest().joinToString("") { "%02x".format(it) }
                }.getOrNull()
            } ?: return@launch
            asking = hex to (uri.lastPathSegment?.substringAfterLast('/') ?: hex.take(12))
        }
    }
    LaunchedEffect(pickPhoto) {
        if (!pickPhoto) return@LaunchedEffect
        pickPhoto = false
        picker.launch(arrayOf("image/*"))
    }
    WalletGroup(title = stringResource(R.string.sol_seal_title)) {
        WalletAction(
            label = stringResource(R.string.sol_seal_pick),
            enabled = state.address != null,
            modifier = Modifier.fillMaxWidth(),
            onClick = { picker.launch(arrayOf("*/*")) },
        )
        state.seals.take(5).forEach { s ->
            val cluster = Cluster.of(s.cluster) ?: return@forEach
            val word = SolanaNotice.sealWord(ctx, SolanaLayer.sealOf(SealFrame(cluster, s.sha256, s.signature)))
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                Text(s.label, style = PhantomText.subhead, color = pc.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${s.sha256.take(12)}… · $word", style = PhantomText.footnote.tnum(), color = pc.text3)
            }
        }
    }
    asking?.let { (hex, label) ->
        PhantomDialog(
            title = stringResource(R.string.sol_seal_ask, label),
            confirmLabel = stringResource(R.string.sol_seal_title),
            dismissLabel = stringResource(R.string.sol_cancel),
            onDismiss = { asking = null },
            onConfirm = {
                asking = null
                scope.launch {
                    val r = SolanaLayer.seal(door.host(R.string.sol_confirm_seal), hex, label)
                    if (r is WalletOutcome.Done && open.peer != null && open.sealHash == hex) {
                        SolanaLayer.config?.send?.invoke(open.peer, SealFrame(Cluster.of(r.value.cluster)!!, hex, r.value.signature).encode())
                    }
                    say(r, ctx.getString(R.string.sol_seal_done))
                }
            },
        ) {
            Text(hex, style = PhantomText.footnote.tnum(), color = pc.text2)
        }
    }
}

@Composable
private fun LicenceSection(door: WalletDoor, state: WalletState, say: (WalletOutcome<*>, String?) -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var offer by remember { mutableStateOf<LicenceOffer?>(null) }
    var line by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun tell(r: ClaimResult) {
        line = when (r) {
            is ClaimResult.Key -> ctx.getString(if (SolanaLayer.state.value.pendingPurchase == null) R.string.sol_licence_done else R.string.sol_licence_not_activated)
            ClaimResult.NotYet -> ctx.getString(R.string.sol_licence_pending)
            is ClaimResult.Refused -> ctx.getString(R.string.sol_licence_refused, r.reason)
        }
    }

    WalletGroup(title = stringResource(R.string.sol_licence_title)) {
        if (state.pendingPurchase != null) {
            WalletLine(stringResource(R.string.sol_licence_pending), quiet = false)
            WalletAction(stringResource(R.string.sol_licence_retry), enabled = !busy, accent = true, modifier = Modifier.fillMaxWidth(), onClick = {
                busy = true
                scope.launch { tell(SolanaLayer.claimPending()); busy = false }
            })
        } else if (offer == null) {
            WalletAction(stringResource(R.string.sol_licence_load), enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                busy = true
                scope.launch {
                    offer = SolanaLayer.shop()?.offer()
                    if (offer == null) line = ctx.getString(R.string.sol_licence_unavailable)
                    busy = false
                }
            })
        } else {
            val o = offer!!
            WalletLine(stringResource(R.string.sol_licence_network, ctx.getString(clusterName(o.cluster))))
            o.prices.forEach { (coin, units) ->
                WalletAction(
                    label = stringResource(R.string.sol_licence_buy, WalletLook.shown(units, coin), coin.symbol),
                    enabled = !busy && state.address != null,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        busy = true
                        line = ctx.getString(R.string.sol_licence_waiting)
                        scope.launch {
                            when (val r = SolanaLayer.buyLicence(door.host(R.string.sol_confirm_title), o, coin)) {
                                is WalletOutcome.Done -> tell(r.value)
                                else -> { line = null; say(r, null) }
                            }
                            busy = false
                        }
                    },
                )
            }
        }
        line?.let { WalletLine(it, quiet = false, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    }
}

/** Попередження сторожа: знак і слово, без плашки; TalkBack читає блок одним шматком. */
@Composable
private fun GuardNotes(lines: List<String>) {
    Column(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
    ) {
        WalletDivider(Modifier.padding(bottom = PhantomSpace.s1))
        lines.forEach { WalletWarning(it) }
    }
}

@Composable
private fun OriginLine(paidBefore: Int) {
    val pc = LocalPhantomColors.current
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
    ) {
        Icon(painterResource(DesignR.drawable.lc_check), contentDescription = null, tint = pc.text2, modifier = Modifier.size(PhantomSize.iconS))
        val more = if (paidBefore > 0) " " + pluralStringResource(R.plurals.sol_guard_paid_before, paidBefore, paidBefore) else ""
        Text(stringResource(R.string.sol_guard_from_chat) + more, style = PhantomText.footnote, color = pc.text2)
    }
}

/** Стан купюри нагорі гаманця: її ж видно одразу після відбитка, а не за три екрани в «Готівці». */
@Composable
private fun HandoffCard(h: Handoff) {
    val nearby = SolanaLayer.config?.nearby
    val radio = nearby?.state?.collectAsState()?.value
    var track by remember(h.signature) { mutableStateOf(HandoffTrack.start(radio, nearby?.wantedNow() == true)) }
    LaunchedEffect(h.signature, radio) { if (radio != null) track = track.next(radio) }
    val view = LocalView.current
    var buzzed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(track.phase, h.signature) {
        if (track.phase == HandoffPhase.HANDED && buzzed != h.signature) {
            buzzed = h.signature
            PhantomHaptics.confirm(view)
        }
    }
    HandoffView(h, track.phase)
}

/** Текст рядка людини починається за плямою тону: тут же й волосина між рядками. */
private val PERSON_TEXT_INSET = PhantomSpace.s4 + PhantomSize.avatarM + PhantomSpace.s3
