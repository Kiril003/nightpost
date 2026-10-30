package local.phantom.companion.core.solana

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.Solana
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.solana.mobilewalletadapter.common.signin.SignInWithSolana
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentHashMap

data class SolanaContact(val id: String, val name: String, val address: SolanaKey?)

class SolanaConfig(
    /** Лише `field`: у Play цифрові товари продаються тільки через Play Billing. */
    val licencePurchase: Boolean,
    val licenceBaseUrl: String,
    val activateLicence: suspend (String) -> Boolean,
    val contacts: () -> List<SolanaContact>,
    val send: suspend (peerId: String, body: String) -> Boolean,
    /** Картка в розмову за її id (група зустрічі); false — такої розмови в мене вже нема. */
    val sendToChat: suspend (chatId: String, body: String) -> Boolean = { _, _ -> false },
    /** Інші учасники розмови [chatId] (без мене), якщо я в ній є; інакше порожньо. */
    val chatMembers: (chatId: String) -> List<String> = { emptyList() },
    /** Сервер-спонсор комісій і проксі RPC (той самий сайт, що ліцензії). Порожньо — людина платить сама, вузли лише прямі. */
    val sponsorBaseUrl: String = "",
    /** Мої перекази з журналу розмов: (людина, кадр). */
    val sent: () -> List<Pair<String, PaymentFrame>> = { emptyList() },
    /** Своя особа в месенджері — до неї сервер привʼязує знак «Seeker перевірено». */
    val selfId: () -> String? = { null },
    /** Публічний ключ сервера ліцензій (hex) — ним знак Seeker перевіряється без мережі. */
    val licencePublicKey: String = "",
    val nearby: NearbyCash? = null,
    /** Бали з сервера (`/points/me`); null — у цій збірці балів немає. */
    val points: (suspend () -> WalletPoints)? = null,
    /** «Розділити»: аркуш рахунку в розмовах — той самий, що відкриває помічник. */
    val split: (() -> Unit)? = null,
    val scan: (() -> Unit)? = null,
)

enum class WalletKind { BUILT_IN, EXTERNAL }

@Serializable
data class CashNote(val seed: String, val nonce: String, val value: String? = null, val voucherSig: String? = null, val voucherUnits: Long = 0)

@Serializable
data class CashPocket(val coin: String = "", val units: Long = 0, val notes: List<CashNote> = emptyList(), val checkedAtMs: Long = 0, val lamports: Long = -1)

@Serializable
data class SealRecord(val sha256: String, val signature: String, val cluster: String, val label: String, val atMs: Long)

data class WalletState(
    val kind: WalletKind = WalletKind.BUILT_IN,
    val address: SolanaKey? = null,
    val builtIn: SolanaKey? = null,
    val pocket: SolanaKey? = null,
    val backedUp: Boolean = false,
    val backup: BackupStatus = BackupStatus.None,
    val cluster: Cluster = Cluster.DEVNET,
    val balances: Map<Coin, Long>? = null,
    val seekerMint: String? = null,
    /** ISO-дата кінця місяця PRO, виданого сервером за SGT; null — сервер нічого не видавав. */
    val seekerProUntil: String? = null,
    /** Свій знак Seeker (SGT і/або рівень стейку SKR), як його видав сервер; null — знака немає. */
    val seekerBadge: SeekerBadge? = null,
    val pendingPurchase: PendingPurchase? = null,
    val cash: CashPocket = CashPocket(),
    val seals: List<SealRecord> = emptyList(),
    /** Спонсор комісій, якщо сервер його назвав; null — платимо самі. */
    val sponsor: SponsorInfo? = null,
    /** Що сервер сказав про ескроу рахунків; null — не читали або сервер недосяжний. */
    val escrow: EscrowConfig? = null,
    /** Інші токени на рахунку (не відомі монети); null — ще не читали. */
    val tokens: List<HeldToken>? = null,
    val shelf: Shelf = Shelf(),
    /** Баланси адрес «лише дивитись», лампорти; немає ключа — не прочитано. */
    val watchedLamports: Map<String, Long> = emptyMap(),
    val faucet: FaucetStatus = FaucetStatus.READY,
    /** Останнє вдале читання балансів із часом; null — жодного ще не було. */
    val lastBalances: LastBalances? = null,
    /** Скільки SOL повернеться, якщо закрити порожні токен-рахунки; null — нічого або не читали. */
    val reclaim: Reclaim? = null,
)

data class PendingPurchase(val referenceSeed: String, val signature: String, val coin: Coin, val units: Long)

sealed interface WalletOutcome<out T> {
    data class Done<T>(val value: T) : WalletOutcome<T>
    data object NoWallet : WalletOutcome<Nothing>
    data object Declined : WalletOutcome<Nothing>
    data class Failed(val why: String) : WalletOutcome<Nothing>
}

/** Те, що дає екран: вікно для зовнішнього гаманця і відбиток/PIN для вбудованого. */
class WalletHost(
    private val _sender: ActivityResultSender?,
    val confirm: suspend () -> Boolean,
) {
    val sender: ActivityResultSender get() = requireNotNull(_sender) { "no ActivityResultSender" }
}

object SolanaLayer {

    @Volatile var config: SolanaConfig? = null
        private set

    private lateinit var prefs: SharedPreferences
    private lateinit var vault: BuiltInVault
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(WalletState())
    val state: StateFlow<WalletState> = _state.asStateFlow()

    private val _skin = MutableStateFlow<WalletSkin?>(null)
    /** Тема й циферблат тексту, якими застосунок щойно малював себе; null — ще не казав (гаманець тоді нічний). */
    val skin: StateFlow<WalletSkin?> = _skin.asStateFlow()

    /** Застосунок каже, як він зараз виглядає, — гаманець відкривається в тому ж. */
    fun useSkin(skin: WalletSkin) {
        _skin.value = skin
    }

    private val _revision = MutableStateFlow(0)
    /** Росте, коли змінилось щось видиме в розмовах: стан переказу, готівки, відбитка. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val payments = PaymentWatch(scope) { changed() }
    private val signal = PaySignal(scope, http = { http }, base = { config?.sponsorBaseUrl.orEmpty() }) { payments.kick() }
    private val lastCard = ConcurrentHashMap<String, PaymentStatus>()
    private val claims by lazy {
        val saved = prefs.getString(K_CLAIMS, null)?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() }.orEmpty()
        SignatureClaims(saved) { prefs.edit().putString(K_CLAIMS, json.encodeToString(it)).apply() }
    }
    private val seals = ConcurrentHashMap<String, SealVerdict>()
    private val checking = ConcurrentHashMap.newKeySet<String>()
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private val adapter by lazy {
        MobileWalletAdapter(ConnectionIdentity(Uri.parse("https://phantom-os.dev"), Uri.parse("favicon.ico"), "Nightpost"))
    }

    fun install(context: Context, config: SolanaConfig) {
        this.config = config
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("phantom_solana", Context.MODE_PRIVATE)
        vault = BuiltInVault(context.applicationContext)
        _state.value = read()
        // Адреса є в кожного з першого запуску; зерно створюється поза головним потоком.
        scope.launch {
            runCatching { vault.ensure() }
            _state.update { read() }
        }
    }

    val installed: Boolean get() = ::prefs.isInitialized

    internal var http: HttpCall = PlainHttp
    internal var rpcTransport: RpcTransport = HttpRpcTransport
    internal var signForTest: ((ByteArray) -> ByteArray)? = null

    internal fun testReset(state: WalletState, config: SolanaConfig? = null) {
        this.config = config
        this.http = PlainHttp
        this.rpcTransport = HttpRpcTransport
        this.signForTest = null
        _state.value = state
    }

    fun rpc(cluster: Cluster): SolanaRpc = SolanaRpc(rpcUrls(cluster), rpcTransport)

    fun rpcUrls(cluster: Cluster): List<String> = SolanaRpc.ladder(
        config?.sponsorBaseUrl.orEmpty(),
        when (cluster) {
            Cluster.DEVNET -> BuildConfig.RPC_DEVNET
            Cluster.MAINNET -> BuildConfig.RPC_MAINNET
        },
        cluster,
    )

    fun walletIntent(context: Context, peerId: String? = null): Intent =
        Intent(context, WalletActivity::class.java).apply {
            peerId?.let { putExtra(WalletActivity.EXTRA_PEER, it) }
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun seedVaultPresent(context: Context): Boolean = runCatching {
        context.packageManager.resolveContentProvider("com.solanamobile.seedvault.wallet.v1.walletprovider", 0) != null
    }.getOrDefault(false)

    // ── гаманці ───────────────────────────────────────────────────────────

    suspend fun connectExternal(host: WalletHost): WalletOutcome<SolanaKey> {
        adapter.blockchain = chain(_state.value.cluster)
        adapter.authToken = prefs.getString(K_TOKEN, null)
        return when (val r = adapter.connect(host.sender)) {
            is TransactionResult.Success -> {
                val key = r.authResult.accounts.firstOrNull()?.publicKey?.let(SolanaKey::of)
                    ?: return WalletOutcome.Failed("no_account")
                prefs.edit().putString(K_EXTERNAL, key.base58).putString(K_TOKEN, adapter.authToken)
                    .putString(K_KIND, WalletKind.EXTERNAL.name).apply()
                reload()
                WalletOutcome.Done(key)
            }
            is TransactionResult.NoWalletFound -> WalletOutcome.NoWallet
            is TransactionResult.Failure -> failure(r)
        }
    }

    suspend fun useBuiltIn(host: WalletHost?) {
        if (host != null && prefs.getString(K_TOKEN, null) != null) {
            adapter.authToken = prefs.getString(K_TOKEN, null)
            runCatching { adapter.disconnect(host.sender) }
        }
        prefs.edit().remove(K_EXTERNAL).remove(K_TOKEN).putString(K_KIND, WalletKind.BUILT_IN.name).apply()
        reload()
    }

    internal fun backupWords(): List<String> = vault.words()

    /** Кличуть одразу після відбитка/PIN: зерно лягає лише під ключ, що це розблокування прийняв. */
    suspend fun createBuiltIn(): WalletOutcome<SolanaKey> =
        runCatching { withContext(Dispatchers.Default) { vault.ensure() } }
            .also { reload() }
            .fold({ WalletOutcome.Done(it) }, { WalletOutcome.Failed(it.message ?: VaultLocked.WHY) })

    fun markBackedUp() {
        vault.markBackedUp()
        reload()
    }

    fun markCopied(name: String?, place: String?) {
        vault.markCopied(System.currentTimeMillis(), name, place)
        reload()
    }

    /** Кидає [VaultLocked] без свіжого розблокування і [NoScreenLock] без блокування екрана. */
    fun restore(words: List<String>): Boolean {
        vault.restore(words) ?: return false
        prefs.edit().remove(K_SHARED).remove(K_CASH).apply()
        reload()
        return true
    }

    fun setCluster(cluster: Cluster) {
        if (cluster == _state.value.cluster) return
        prefs.edit().putString(K_CLUSTER, cluster.wire).remove(K_TOKEN).apply()
        reload()
    }

    @Volatile private var sponsor: SponsorInfo? = null

    private fun sponsorClient() = SponsorClient(config?.sponsorBaseUrl.orEmpty())

    /** Чи є зараз спонсор комісій. Недосяжний сервер — чесне «немає», а не останнє відоме «так». */
    suspend fun refreshSponsor() {
        sponsor = sponsorClient().info()
        _state.update { it.copy(sponsor = sponsor) }
    }

    suspend fun refreshEscrow() {
        val read = EscrowConfigClient(config?.sponsorBaseUrl.orEmpty(), http).fetch()
        _state.update { it.copy(escrow = read) }
    }

    /** Аркуш рахунку питає раніше, ніж відкрили гаманець: прочитати конфіг у фоні, відповідь — наступного разу. */
    fun escrowSoon() {
        if (_state.value.escrow == null) scope.launch { runCatching { refreshEscrow() } }
    }

    /** Рахунок-ескроу з мережі; null — ескроу вимкнено, адреса чужа або мережа мовчить. */
    suspend fun bill(address: String): BillView? {
        val s = _state.value
        val program = s.escrow?.programFor(s.cluster) ?: return null
        val key = SolanaKey.parse(address) ?: return null
        return runCatching { BillRead.parse(rpc(s.cluster).account(key), program) }.getOrNull()
    }

    suspend fun refreshBalances() {
        runCatching { refreshSponsor() }
        runCatching { refreshEscrow() }
        val s = _state.value
        val owner = s.address ?: return
        val rpc = rpc(s.cluster)
        val b = runCatching { Coin.on(s.cluster).associateWith { rpc.units(owner, it, s.cluster) } }.getOrNull()
        val last = LastBalances.next(s.lastBalances, b, System.currentTimeMillis())
        if (b != null && last != null && installed) prefs.edit().putString(LastBalances.key(s.cluster, owner), last.encode()).apply()
        _state.update { it.copy(balances = b, lastBalances = last) }
        val raw = runCatching { listOf(Programs.TOKEN, Programs.TOKEN_2022).map { rpc.tokenAccounts(owner, it) } }.getOrNull()
        val held = raw?.let { r -> runCatching { r.flatMap(TokenHoldings::parse) }.getOrNull() }
        if (raw != null) _state.update { it.copy(reclaim = Reclaim.of(raw)) }
        val watched = s.shelf.watched.mapNotNull { a ->
            SolanaKey.parse(a)?.let { k -> runCatching { a to rpc.lamports(k) }.getOrNull() }
        }.toMap()
        _state.update { it.copy(tokens = held?.let { h -> TokenHoldings.foreign(h, s.cluster) }, watchedLamports = watched) }
    }

    // ── полиця карток: токени за адресою, «лише дивитись», сховані, порядок ──

    fun addMint(mint: SolanaKey) = shelf { it.withMint(mint) }

    fun watchAddress(address: SolanaKey) = shelf { it.withWatched(address) }

    fun hideCard(key: String) = shelf { it.hide(key) }

    fun showCard(key: String) = shelf { it.show(key) }

    fun moveCard(key: String, delta: Int, keys: List<String>) = shelf { it.move(key, delta, keys) }

    private fun shelf(change: (Shelf) -> Shelf) {
        val s = _state.value
        val next = change(s.shelf)
        if (next == s.shelf) return
        prefs.edit().putString(K_SHELF + s.cluster.wire, json.encodeToString(next)).apply()
        _state.update { it.copy(shelf = next) }
    }

    private val feed = ConcurrentHashMap<String, List<ActivityLine>>()
    private val feedLoads = ConcurrentHashMap<String, Deferred<List<ActivityLine>?>>()

    /**
     * Останні рухи [coin] на рахунку з мережі; null — мережа не відповіла. Відповідь живе
     * в памʼяті на адресу й мережу, а читання одне на ключ: свайп туди-назад мережу не смикає.
     * [fresh] — людина потягнула «оновити» або гроші щойно пішли.
     */
    suspend fun activity(coin: Coin, fresh: Boolean = false): List<ActivityLine>? {
        val s = _state.value
        val owner = s.address ?: return null
        val key = listOf(s.cluster.wire, owner.base58, coin.wire).joinToString("|")
        if (!fresh) feed[key]?.let { return it }
        val load = feedLoads.computeIfAbsent(key) {
            scope.async { readActivity(owner, coin, s.cluster)?.also { feed[key] = it } }
                .also { d -> d.invokeOnCompletion { feedLoads.remove(key, d) } }
        }
        return load.await()
    }

    /** Що вже прочитано для [coin] на поточній адресі — щоб картка не мигала «читаємо», коли відповідь є. */
    fun knownActivity(coin: Coin): List<ActivityLine>? {
        val s = _state.value
        val owner = s.address ?: return null
        return feed[listOf(s.cluster.wire, owner.base58, coin.wire).joinToString("|")]
    }

    private suspend fun readActivity(owner: SolanaKey, coin: Coin, cluster: Cluster): List<ActivityLine>? {
        val where = if (coin.native) owner else Programs.associatedTokenAddress(owner, coin.mint(cluster) ?: return emptyList())
        val rpc = rpc(cluster)
        return runCatching {
            coroutineScope {
                rpc.signaturesFor(where, ACTIVITY_LIMIT)
                    .map { sig -> async { WalletFeed.lineOf(rpc.transaction(sig), sig, owner, coin, cluster) } }
                    .mapNotNull { it.await() }
            }
        }.getOrNull()
    }

    private val deviceIdRegex = Regex("^[A-Za-z0-9_-]{8,64}$")

    /** Сервер фаусета бачить лише згортку особи, не саму особу в месенджері. */
    internal fun faucetDeviceCode(selfId: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest("faucet:v1:$selfId".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)

    suspend fun requestTestUsdc() {
        val s = _state.value
        if (s.cluster != Cluster.DEVNET || s.kind != WalletKind.BUILT_IN) return
        val owner = s.address ?: return
        val device = config?.selfId?.invoke()?.trim().orEmpty()
        if (!deviceIdRegex.matches(device)) {
            _state.update { it.copy(faucet = FaucetStatus.READY) }
            return
        }
        _state.update { it.copy(faucet = FaucetStatus.SENDING) }
        val result = withContext(Dispatchers.IO) {
            FaucetClient(config?.sponsorBaseUrl.orEmpty(), http).drip(owner, faucetDeviceCode(device))
        }
        when (result) {
            is FaucetResult.Sent -> {
                _state.update { it.copy(faucet = FaucetStatus.READY) }
                refreshBalances()
            }
            FaucetResult.Off -> _state.update { it.copy(faucet = FaucetStatus.OFF) }
            FaucetResult.Limited -> _state.update { it.copy(faucet = FaucetStatus.LIMITED) }
            FaucetResult.Unavailable -> _state.update { it.copy(faucet = FaucetStatus.RETRY) }
        }
    }

    suspend fun airdrop(): Boolean {
        val s = _state.value
        if (s.cluster != Cluster.DEVNET) return false
        val owner = s.address ?: return false
        return runCatching { rpc(Cluster.DEVNET).requestAirdrop(owner, 1_000_000_000L) }.isSuccess
    }

    /** Підписати й надіслати транзакцію активним гаманцем. Повертає підпис. */
    suspend fun signAndSend(host: WalletHost, build: suspend (SolanaKey) -> ByteArray): WalletOutcome<String> {
        val s = _state.value
        val owner = s.address ?: return WalletOutcome.Failed("not_connected")
        val message = runCatching { build(owner) }.getOrElse { return WalletOutcome.Failed("offline") }
        if (s.kind == WalletKind.BUILT_IN) {
            if (!host.confirm()) return WalletOutcome.Declined
            return withContext(Dispatchers.IO) {
                runCatching {
                    val tx = signForTest?.invoke(message) ?: SolanaTx.sign(message, listOf(vault.keypair()))
                    rpc(s.cluster).sendRaw(tx)
                }.fold({ WalletOutcome.Done(it) }, { WalletOutcome.Failed(it.message ?: "rpc") })
            }
        }
        adapter.blockchain = chain(s.cluster)
        adapter.authToken = prefs.getString(K_TOKEN, null)
        val r = adapter.transact(host.sender) { auth ->
            val signer = auth.accounts.firstOrNull()?.publicKey?.let(SolanaKey::of)
            if (signer != owner) return@transact null
            signAndSendTransactions(arrayOf(SolanaTx.unsigned(message))).signatures.firstOrNull()
        }
        return when (r) {
            is TransactionResult.Success -> {
                prefs.edit().putString(K_TOKEN, adapter.authToken).apply()
                r.payload?.let { WalletOutcome.Done(Base58.encode(it)) } ?: WalletOutcome.Failed("other_account")
            }
            is TransactionResult.NoWalletFound -> WalletOutcome.NoWallet
            is TransactionResult.Failure -> failure(r)
        }
    }

    /**
     * Переказ токена: якщо є спонсор — людина підписує свій слот, комісію й ренту ATA платить
     * спонсор, і сервер сам відправляє. Спонсора немає/недосяжний — звичайний шлях, людина платить сама.
     * Відмова спонсора (правило, квота) — чесна помилка, а не тихе списання з власного SOL.
     */
    suspend fun sendTransfer(host: WalletHost, coin: Coin, build: suspend (owner: SolanaKey, feePayer: SolanaKey) -> ByteArray): WalletOutcome<String> {
        val s = _state.value
        val route = FeeRoute.choose(s.sponsor, s.kind, s.cluster, coin)
        if (route is FeeRoute.Route.Sponsored) {
            val owner = s.address ?: return WalletOutcome.Failed("not_connected")
            val message = runCatching { build(owner, route.feePayer) }.getOrElse { return WalletOutcome.Failed("offline") }
            if (!host.confirm()) return WalletOutcome.Declined
            val kp = runCatching { withContext(Dispatchers.Default) { vault.keypair() } }.getOrElse { return WalletOutcome.Failed(it.message ?: VaultLocked.WHY) }
            val r = withContext(Dispatchers.IO) {
                runCatching { sponsorClient().submit(SolanaTx.partiallySigned(message, listOf(kp)), kp) }.getOrDefault(SponsorResult.Unavailable)
            }
            when (r) {
                is SponsorResult.Sent -> return WalletOutcome.Done(r.signature)
                is SponsorResult.Refused -> return WalletOutcome.Failed("sponsor_" + r.reason)
                SponsorResult.Unavailable -> {
                    sponsor = null
                    _state.update { it.copy(sponsor = null) }
                }
            }
        }
        return signAndSend(host) { owner -> build(owner, owner) }
    }

    // ── Seeker ────────────────────────────────────────────────────────────

    /**
     * Місяць PRO за Seeker Genesis Token — рішення СЕРВЕРА, не позначка на телефоні:
     * позначка входу від сервера → підпис Sign-In-With-Solana активним гаманцем (вбудований —
     * відбитком, зовнішній — `signIn` через MWA/Seed Vault) → сервер читає mainnet і закріплює mint.
     * Без SGT у гаманці (видно з mainnet ще до підпису) людину не просять нічого підписувати.
     */
    suspend fun claimSeeker(host: WalletHost): WalletOutcome<SeekerClaimResult> {
        val s = _state.value
        val owner = s.address ?: return WalletOutcome.Failed("not_connected")
        // Без адреси сервера (збірка Play) видати місяць нікому — і це кажемо, а не «немає звʼязку».
        if (config?.licenceBaseUrl.isNullOrBlank()) return WalletOutcome.Done(SeekerClaimResult.Refused("not_here"))
        val local = runCatching { SeekerGenesis.check(owner, rpc(Cluster.MAINNET)) }.getOrNull()
        if (local == SeekerGenesis.Verdict.NotHolder) {
            prefs.edit().remove(K_SEEKER).apply()
            reload()
            return WalletOutcome.Done(SeekerClaimResult.NotSeeker)
        }
        val client = SeekerClaimClient(config?.licenceBaseUrl.orEmpty())
        val challenge = client.challenge() ?: return WalletOutcome.Done(SeekerClaimResult.Unavailable)
        val signed = signIn(host, challenge, owner)
        val (message, signature) = (signed as? WalletOutcome.Done)?.value ?: return signed.fail()
        val result = client.claim(owner, message, signature, config?.selfId?.invoke())
        val badge = (result as? SeekerClaimResult.Granted)?.badge ?: (result as? SeekerClaimResult.AlreadyUsed)?.badge
        if (badge != null) prefs.edit().putString(K_BADGE, badge).apply()
        when (result) {
            is SeekerClaimResult.Granted -> {
                val activated = config?.activateLicence?.invoke(result.licenceKey) == true
                if (!activated) return WalletOutcome.Done(SeekerClaimResult.Refused("not_activated"))
                prefs.edit().putString(K_SEEKER, result.mint).putString(K_PRO_UNTIL, result.proUntil).apply()
            }
            is SeekerClaimResult.AlreadyUsed -> prefs.edit().putString(K_SEEKER, (local as? SeekerGenesis.Verdict.Holder)?.mint).apply()
            SeekerClaimResult.NotSeeker -> prefs.edit().remove(K_SEEKER).apply {
                // Знак «Seeker» застарів; знак лише стейкера (sgt=false) — ні.
                if (SeekerBadge.parse(prefs.getString(K_BADGE, null) ?: "")?.sgt == true) remove(K_BADGE)
            }.apply()
            else -> Unit
        }
        reload()
        return WalletOutcome.Done(result)
    }

    /**
     * «Оновити знак Seeker»: той самий підпис входу, а сервер заново читає SGT і стейк SKR.
     * Місяця PRO не чіпає; працює й без SGT — тоді знак каже лише «стейкає SKR».
     */
    suspend fun refreshSeekerBadge(host: WalletHost): WalletOutcome<BadgeRefresh> {
        val owner = _state.value.address ?: return WalletOutcome.Failed("not_connected")
        if (config?.licenceBaseUrl.isNullOrBlank()) return WalletOutcome.Done(BadgeRefresh.Refused("not_here"))
        val identity = config?.selfId?.invoke() ?: return WalletOutcome.Failed("no_identity")
        val client = SeekerClaimClient(config?.licenceBaseUrl.orEmpty())
        val challenge = client.challenge() ?: return WalletOutcome.Done(BadgeRefresh.Unavailable)
        val signed = signIn(host, challenge, owner)
        val (message, signature) = (signed as? WalletOutcome.Done)?.value ?: return signed.fail()
        val result = client.refreshBadge(owner, message, signature, identity)
        when (result) {
            is BadgeRefresh.Fresh -> prefs.edit().putString(K_BADGE, result.badge).apply()
            BadgeRefresh.Nothing -> prefs.edit().remove(K_BADGE).apply()
            else -> Unit
        }
        reload()
        return WalletOutcome.Done(result)
    }

    /** Свій чинний знак Seeker — його прикладаємо до офлайн-купюри. */
    private fun ownBadge(): String? = prefs.getString(K_BADGE, null)?.takeIf { SeekerBadge.fresh(it, System.currentTimeMillis() / 1000) }

    /**
     * Чи несе купюра чесний знак «Seeker перевірено» саме від [senderId]. Без мережі:
     * підпис сервера ліцензій, строк і особа відправника.
     */
    fun seekerVerified(voucher: CashVoucher, senderId: String?): Boolean = seekerBadgeOf(voucher, senderId) != null

    /** Перевірений офлайн знак відправника купюри (SGT і/або рівень стейку SKR) або null. */
    fun seekerBadgeOf(voucher: CashVoucher, senderId: String?): SeekerBadge? {
        val badge = voucher.badge ?: return null
        val key = config?.licencePublicKey.orEmpty()
        return (SeekerBadge.verify(badge, key, senderId, System.currentTimeMillis() / 1000) as? SeekerBadge.Verdict.Valid)?.badge
    }

    /** (точний текст, підпис) Sign-In-With-Solana від активного гаманця. */
    private suspend fun signIn(host: WalletHost, challenge: SiwsChallenge, owner: SolanaKey): WalletOutcome<Pair<String, ByteArray>> {
        val s = _state.value
        if (s.kind == WalletKind.BUILT_IN) {
            if (!host.confirm()) return WalletOutcome.Declined
            val message = Siws.message(challenge, owner, Siws.now())
            val sig = runCatching { withContext(Dispatchers.Default) { vault.keypair().sign(message.toByteArray(Charsets.UTF_8)) } }
                .getOrElse { return WalletOutcome.Failed(it.message ?: VaultLocked.WHY) }
            return WalletOutcome.Done(message to sig)
        }
        // SGT живе лише в mainnet — і вхід підписується для mainnet, хоч би яку мережу обрано для переказів.
        adapter.blockchain = Solana.Mainnet
        val payload = SignInWithSolana.Payload(
            challenge.domain, null as String?, challenge.statement, Uri.parse(challenge.uri), "1", challenge.chainId,
            challenge.nonce, Siws.now(), null, null, null, null,
        )
        val r = adapter.signIn(host.sender, payload)
        adapter.blockchain = chain(s.cluster)
        return when (r) {
            is TransactionResult.Success -> {
                val res = r.payload
                if (SolanaKey.of(res.publicKey) != owner) return WalletOutcome.Failed("other_account")
                WalletOutcome.Done(String(res.signedMessage, Charsets.UTF_8) to res.signature)
            }
            is TransactionResult.NoWalletFound -> WalletOutcome.NoWallet
            is TransactionResult.Failure -> failure(r)
        }
    }

    /**
     * Підпис закритого речення ключем вбудованого гаманця — для заяв, де гаманець
     * сам свідчить про свою адресу (бали за оплачену частку). Спершу згода
     * (відбиток/PIN), потім ключ; [use] отримує підписувач, який живе лише на час
     * виклику. Зовнішній гаманець так не підпише — [StatementOutcome.Unsupported].
     */
    suspend fun <T> signStatement(
        confirm: suspend () -> Boolean,
        use: suspend ((ByteArray) -> ByteArray) -> T,
    ): StatementOutcome<T> {
        val s = _state.value
        return WalletStatement.run(
            kind = s.kind.takeIf { s.address != null },
            confirm = confirm,
            keypair = { withContext(Dispatchers.Default) { vault.keypair() } },
            use = use,
        )
    }

    // ── адреси в розмовах ─────────────────────────────────────────────────

    /** Кожен з [peerIds] отримує нашу активну адресу (і кишеню) один раз — усередині зашифрованої розмови. */
    suspend fun syncAddress(peerIds: List<String>) {
        if (!installed) return
        val me = _state.value.address ?: return
        val pocket = _state.value.pocket?.takeIf { _state.value.kind == WalletKind.BUILT_IN }
        val mark = me.base58 + (pocket?.let { ";" + it.base58 } ?: "")
        val shared = sharedMap()
        var changed = false
        for (id in peerIds) {
            if (shared[id] == mark) continue
            val send = config?.send ?: return
            if (pocket != null && !send(id, WalletAddressFrame.encodePocket(pocket))) continue
            if (send(id, WalletAddressFrame.encode(me))) {
                shared[id] = mark
                changed = true
            }
        }
        if (changed) prefs.edit().putString(K_SHARED, json.encodeToString(shared.toMap())).apply()
    }

    // ── переказ у розмові ─────────────────────────────────────────────────

    /**
     * [peerId] null — адреса вписана вручну (чи це гаманець закладу): без [route] кадр нема
     * кому показати в розмові. [route] — частка рахунку: картка з `ref` іде в групу зустрічі.
     */
    suspend fun payPeer(host: WalletHost, peerId: String?, to: SolanaKey, coin: Coin, units: Long, route: PayRoute = PayRoute(), note: String? = null): WalletOutcome<Paid> {
        val cluster = _state.value.cluster
        val rpc = rpc(cluster)
        val validNote = note?.takeIf(FrameNote::isValid)
        val ref = route.ref ?: validNote?.takeIf { route.reference == null && route.memo == null }?.let { PaymentFrame.newRef() }
        val frameNote = validNote?.takeIf { ref != null }
        // Частка рахунку пишеться на мережу Memo-інструкцією (як у купівлі ліцензії): id видно в провіднику.
        val r = sendTransfer(host, coin) { owner, payer -> PaymentPost.transfer(owner, payer, to, coin, cluster, units, rpc.latestBlockhash(), reference = route.reference, memo = route.memo, ref = ref) }
        return PaymentPost.after(r, { sig -> PaymentFrame(cluster, coin, units, to, sig, ref, frameNote) }) { frame ->
            payments.mark(frame.signature, PaymentStatus.PENDING)
            val c = config
            syncAddress(PaymentPost.addressTo(peerId, route, route.chatId?.let { c?.chatMembers?.invoke(it) }.orEmpty()))
            val posted = PaymentPost.deliver(
                frame,
                peerId,
                route,
                toPeer = { id, body -> c?.send?.invoke(id, body) == true },
                toChat = { id, body -> c?.sendToChat?.invoke(id, body) == true },
            )
            watch(frame)
            posted
        }
    }

    fun sentBefore(peerId: String?, to: SolanaKey): List<SentBefore> =
        runCatching { config?.sent?.invoke().orEmpty() }.getOrDefault(emptyList())
            .filter { (id, f) -> if (peerId != null) id == peerId else f.to == to }
            .map { SentBefore.of(it.second) }

    /** Останній відомий стан переказу; невідомий запускає перевірку в мережі. */
    fun statusOf(frame: PaymentFrame): PaymentStatus {
        if (payments.status(frame.signature)?.settled != true) watch(frame)
        return lastCard[frame.signature] ?: PaymentStatus.PENDING
    }

    fun paymentStatus(signature: String): PaymentStatus? = payments.statusBySignature(signature)

    /** Купюру подає в мережу кожен, хто її бачить і має звʼязок, — хто перший, той і погасив. */
    fun cashStatusOf(voucher: CashVoucher): PaymentStatus {
        if (payments.status(voucher.payment.signature)?.settled != true) watch(voucher.payment, voucher.tx)
        return lastCard[voucher.payment.signature] ?: PaymentStatus.PENDING
    }

    fun watch(frame: PaymentFrame, rawToSubmit: ByteArray? = null) = watchKey(frame.signature, frame, rawToSubmit, null)

    private fun watchKey(key: String, frame: PaymentFrame, rawToSubmit: ByteArray?, payer: SolanaKey?) {
        frame.ref?.let { signal.watch(frame.cluster, PaymentFrame.refKey(it)) }
        payments.watch(key, if (rawToSubmit != null) PaymentWatch.CASH_MAX_AGE_MS else PaymentWatch.PAY_MAX_AGE_MS) {
            val rpc = rpc(frame.cluster)
            if (rawToSubmit != null) runCatching { rpc.sendRaw(rawToSubmit) }
            PaymentCheck.check(frame, rpc, payer)
        }
    }

    /**
     * Стан картки в розмові. [card] — хто й яким повідомленням її показав; [payer] — гаманець
     * (для купюри — кишеня), який відправник назвав у PH-SOLKEY; [mine] — картка моя власна.
     */
    fun cardStatus(frame: PaymentFrame, raw: ByteArray?, card: String, mine: Boolean, payer: SolanaKey?, signer: SolanaKey? = null): CardVerdict {
        if (!installed) return CardVerdict(PaymentStatus.PENDING)
        val verdict = CardVerdict.before(claims.claim(frame.signature, card), mine, payer, signer) ?: run {
            val by = if (mine) null else payer
            val key = listOf(frame.signature, frame.to.base58, frame.coin.wire, frame.units, by?.base58 ?: "-").joinToString("|")
            val known = payments.status(key)
            if (known == null || !known.settled) watchKey(key, frame, raw, by)
            CardVerdict(known ?: PaymentStatus.PENDING)
        }
        lastCard[frame.signature] = verdict.status
        return verdict
    }

    /** Мережа повернулась: усе, що чекало, перевірити зараз, а не за розкладом. */
    fun recheckPending() = payments.kick()

    // ── готівка без інтернету ────────────────────────────────────────────

    /** Онлайн, один раз: перекласти суму в кишеню й завести три «купюри» (nonce-рахунки). */
    suspend fun prepareCash(host: WalletHost, coin: Coin, units: Long): WalletOutcome<String> {
        val s = _state.value
        if (s.kind != WalletKind.BUILT_IN) return WalletOutcome.Failed("builtin_only")
        val pocket = s.pocket ?: return WalletOutcome.Failed("not_connected")
        val rpc = rpc(s.cluster)
        val notes = CashPlan.notes(pocket)
        val merged = CashPlan.merge(s.cash, coin, notes) ?: return WalletOutcome.Failed("cash_other_coin")
        val r = run {
            val owner = s.address ?: return WalletOutcome.Failed("not_connected")
            val message = runCatching {
                val rent = rpc.rentExempt(Programs.NONCE_SPACE)
                val missing = notes.filter { rpc.nonceValue(SolanaKey.of(it.nonce)) == null }
                CashPlan.fund(owner, pocket, coin, s.cluster, units, rent, missing, rpc.latestBlockhash())
            }.getOrElse { return WalletOutcome.Failed("offline") }
            if (!host.confirm()) return WalletOutcome.Declined
            runCatching {
                val tx = withContext(Dispatchers.Default) { SolanaTx.sign(message, listOf(vault.keypair(), vault.keypair(Slip10.SOLANA_CASH))) }
                rpc.sendRaw(tx)
            }.fold({ WalletOutcome.Done(it) }, { WalletOutcome.Failed(it.message ?: "rpc") })
        }
        if (r !is WalletOutcome.Done) return r
        saveCash(CashPlan.merge(_state.value.cash, coin, notes) ?: merged)
        for (i in 0 until 15) {
            delay(2_000L)
            if (refreshCash()) break
        }
        return r
    }

    /** Онлайн: що лежить у кишені, які купюри вже погашено, нові значення nonce. */
    suspend fun refreshCash(): Boolean {
        val s = _state.value
        val pocket = s.pocket ?: return false
        val coin = Coin.of(s.cash.coin) ?: return false
        val rpc = rpc(s.cluster)
        return runCatching {
            val sol = rpc.lamports(pocket)
            val units = if (coin.native) (sol - CashPlan.FEES).coerceAtLeast(0) else rpc.units(pocket, coin, s.cluster)
            val notes = s.cash.notes.map { n ->
                val value = rpc.nonceValue(SolanaKey.of(n.nonce))?.let(Base58::encode)
                val spent = n.voucherSig != null && (value != null && value != n.value || payments.statusBySignature(n.voucherSig) == PaymentStatus.FAILED)
                if (spent || n.voucherSig == null) n.copy(value = value, voucherSig = null, voucherUnits = 0) else n
            }
            saveCash(s.cash.copy(units = units, notes = notes, checkedAtMs = System.currentTimeMillis(), lamports = sol))
            notes.all { it.value != null }
        }.getOrDefault(false)
    }

    fun cashFreeUnits(): Long = _state.value.cash.let { c -> c.units - c.notes.sumOf { it.voucherUnits } }

    fun cashOnHand(): CashOnHand {
        val s = _state.value
        val notes = s.cash.notes.any { it.value != null && it.voucherSig == null }
        val coin = Coin.of(s.cash.coin)
        val sol = coin == null || CashPlan.solCovers(coin, s.cash.lamports, s.cash.notes.count { it.voucherSig != null })
        return CashOnHand(coin, if (notes) cashFreeUnits().coerceAtLeast(0) else 0, s.kind == WalletKind.BUILT_IN, sol)
    }

    /** Без мережі: підписати купюру кишенею й передати її людині через сітку. */
    suspend fun issueCash(host: WalletHost, peerId: String, to: SolanaKey, units: Long): WalletOutcome<CashVoucher> {
        val s = _state.value
        val pocket = s.pocket ?: return WalletOutcome.Failed("not_connected")
        val coin = Coin.of(s.cash.coin) ?: return WalletOutcome.Failed("no_cash")
        if (units > cashFreeUnits()) return WalletOutcome.Failed("cash_low")
        val note = s.cash.notes.firstOrNull { it.value != null && it.voucherSig == null } ?: return WalletOutcome.Failed("no_notes")
        if (!CashPlan.solCovers(coin, s.cash.lamports, s.cash.notes.count { it.voucherSig != null })) return WalletOutcome.Failed("cash_no_sol")
        val message = CashVoucher.message(pocket, SolanaKey.of(note.nonce), Base58.decode(note.value!!)!!, to, coin, s.cluster, units)
        if (!host.confirm()) return WalletOutcome.Declined
        val signed = runCatching { withContext(Dispatchers.Default) { SolanaTx.sign(message, listOf(vault.keypair(Slip10.SOLANA_CASH))) } }
            .getOrElse { return WalletOutcome.Failed(it.message ?: VaultLocked.WHY) }
        val voucher = CashVoucher.of(s.cluster, coin, units, to, signed).copy(badge = ownBadge())
        saveCash(s.cash.copy(notes = s.cash.notes.map { if (it == note) it.copy(voucherSig = voucher.payment.signature, voucherUnits = units) else it }))
        payments.mark(voucher.payment.signature, PaymentStatus.PENDING)
        syncAddress(listOf(peerId))
        config?.send?.invoke(peerId, voucher.encode())
        watch(voucher.payment, voucher.tx)
        return WalletOutcome.Done(voucher)
    }

    // ── відбиток у мережі ─────────────────────────────────────────────────

    suspend fun seal(host: WalletHost, sha256Hex: String, label: String): WalletOutcome<SealRecord> {
        val cluster = _state.value.cluster
        val rpc = rpc(cluster)
        val r = signAndSend(host) { owner -> SealFrame.message(owner, rpc.latestBlockhash(), sha256Hex) }
        val sig = (r as? WalletOutcome.Done)?.value ?: return r.fail()
        val rec = SealRecord(sha256Hex, sig, cluster.wire, label, System.currentTimeMillis())
        val list = listOf(rec) + _state.value.seals
        prefs.edit().putString(K_SEALS, json.encodeToString(list.take(50))).apply()
        reload()
        watchSeal(SealFrame(cluster, sha256Hex, sig))
        return WalletOutcome.Done(rec)
    }

    fun sealOf(frame: SealFrame): SealVerdict {
        val known = seals[frame.signature]
        if (known == null || known == SealVerdict.Pending) watchSeal(frame)
        return known ?: SealVerdict.Pending
    }

    private fun watchSeal(frame: SealFrame) {
        if (!checking.add(frame.signature)) return
        scope.launch {
            try {
                for (attempt in 0 until 10) {
                    val v = runCatching { SealCheck.judge(frame.sha256Hex, rpc(frame.cluster).transaction(frame.signature)) }.getOrNull()
                    if (v != null && seals.put(frame.signature, v) != v) changed()
                    if (v != null && v != SealVerdict.Pending) return@launch
                    delay(if (attempt < 4) 3_000L else 15_000L)
                }
            } finally {
                checking.remove(frame.signature)
            }
        }
    }

    // ── ліцензія ──────────────────────────────────────────────────────────

    fun shop(): LicenceShop? = config?.takeIf { it.licencePurchase }?.let { LicenceShop(it.licenceBaseUrl) }

    suspend fun buyLicence(host: WalletHost, offer: LicenceOffer, coin: Coin): WalletOutcome<ClaimResult> {
        val units = offer.prices[coin] ?: return WalletOutcome.Failed("no_price")
        if (offer.cluster != _state.value.cluster) setCluster(offer.cluster)
        val reference = PurchaseReference.fresh()
        val rpc = rpc(offer.cluster)
        val r = sendTransfer(host, coin) { owner, payer ->
            Transfers.build(owner, offer.recipient, coin, offer.cluster, units, rpc.latestBlockhash(), reference = reference.key, memo = "Nightpost licence", feePayer = payer)
        }
        val sig = (r as? WalletOutcome.Done)?.value ?: return r.fail()
        val pending = PendingPurchase(reference.seedBase58, sig, coin, units)
        savePending(pending)
        return WalletOutcome.Done(claimPending(pending))
    }

    suspend fun claimPending(pending: PendingPurchase? = _state.value.pendingPurchase): ClaimResult {
        val p = pending ?: return ClaimResult.Refused("nothing")
        val shop = shop() ?: return ClaimResult.Refused("not_here")
        val ref = PurchaseReference.restore(p.referenceSeed) ?: return ClaimResult.Refused("lost")
        var result: ClaimResult = ClaimResult.NotYet
        for (attempt in 0 until 6) {
            result = shop.claim(ref, p.signature)
            if (result !is ClaimResult.NotYet) break
            delay(5_000L)
        }
        if (result is ClaimResult.Key && config?.activateLicence?.invoke(result.licenceKey) == true) savePending(null)
        return result
    }

    // ── службове ──────────────────────────────────────────────────────────

    private fun <T> WalletOutcome<*>.fail(): WalletOutcome<T> = when (this) {
        WalletOutcome.Declined -> WalletOutcome.Declined
        WalletOutcome.NoWallet -> WalletOutcome.NoWallet
        is WalletOutcome.Failed -> this
        is WalletOutcome.Done -> WalletOutcome.Failed("unexpected")
    }

    private fun sharedMap(): MutableMap<String, String> =
        prefs.getString(K_SHARED, null)?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() }
            .orEmpty().toMutableMap()

    private fun saveCash(c: CashPocket) {
        prefs.edit().putString(K_CASH, json.encodeToString(c)).apply()
        _state.update { it.copy(cash = c) }
    }

    private fun savePending(p: PendingPurchase?) {
        prefs.edit().apply {
            if (p == null) remove(K_PENDING) else putString(K_PENDING, listOf(p.referenceSeed, p.signature, p.coin.wire, p.units).joinToString(";"))
        }.apply()
        _state.update { it.copy(pendingPurchase = p) }
    }

    private fun reload() {
        val before = _state.value.address
        _state.value = read()
        if (before != _state.value.address) changed()
    }

    private fun read(): WalletState {
        val kind = runCatching { WalletKind.valueOf(prefs.getString(K_KIND, null) ?: "") }.getOrDefault(WalletKind.BUILT_IN)
        val external = prefs.getString(K_EXTERNAL, null)?.let(SolanaKey::parse)
        val builtIn = vault.address
        val active = if (kind == WalletKind.EXTERNAL && external != null) WalletKind.EXTERNAL else WalletKind.BUILT_IN
        val activeAddress = if (active == WalletKind.EXTERNAL) external else builtIn
        return WalletState(
            kind = active,
            address = activeAddress,
            builtIn = builtIn,
            pocket = vault.pocket,
            backedUp = vault.backedUp,
            backup = vault.backup,
            cluster = Cluster.of(prefs.getString(K_CLUSTER, null)) ?: Cluster.DEVNET,
            balances = null,
            seekerMint = prefs.getString(K_SEEKER, null),
            seekerProUntil = prefs.getString(K_PRO_UNTIL, null),
            seekerBadge = prefs.getString(K_BADGE, null)?.let(SeekerBadge::parse),
            pendingPurchase = prefs.getString(K_PENDING, null)?.split(';')?.takeIf { it.size == 4 }?.let { f ->
                val coin = Coin.of(f[2]) ?: return@let null
                PendingPurchase(f[0], f[1], coin, f[3].toLongOrNull() ?: return@let null)
            },
            cash = prefs.getString(K_CASH, null)?.let { runCatching { json.decodeFromString<CashPocket>(it) }.getOrNull() } ?: CashPocket(),
            seals = prefs.getString(K_SEALS, null)?.let { runCatching { json.decodeFromString<List<SealRecord>>(it) }.getOrNull() }.orEmpty(),
            sponsor = sponsor,
            shelf = (Cluster.of(prefs.getString(K_CLUSTER, null)) ?: Cluster.DEVNET).let { c ->
                prefs.getString(K_SHELF + c.wire, null)?.let { runCatching { json.decodeFromString<Shelf>(it) }.getOrNull() }
            } ?: Shelf(),
            lastBalances = activeAddress?.let { a ->
                LastBalances.decode(prefs.getString(LastBalances.key(Cluster.of(prefs.getString(K_CLUSTER, null)) ?: Cluster.DEVNET, a), null))
            },
        )
    }

    private fun changed() {
        _revision.update { it + 1 }
    }

    private fun chain(cluster: Cluster) = when (cluster) {
        Cluster.DEVNET -> Solana.Devnet
        Cluster.MAINNET -> Solana.Mainnet
    }

    private fun <T> failure(r: TransactionResult.Failure<*>): WalletOutcome<T> {
        val text = (r.message + " " + (r.e.message ?: "")).lowercase()
        return if ("not authorize" in text || "declin" in text || "interrupted" in text || "cancel" in text) {
            WalletOutcome.Declined
        } else {
            WalletOutcome.Failed(r.message)
        }
    }

    private const val ACTIVITY_LIMIT = 8
    private const val K_KIND = "kind"
    private const val K_EXTERNAL = "external"
    private const val K_TOKEN = "auth_token"
    private const val K_CLUSTER = "cluster"
    private const val K_SEEKER = "seeker_mint"
    private const val K_PRO_UNTIL = "seeker_pro_until"
    private const val K_BADGE = "seeker_badge"
    private const val K_PENDING = "pending_purchase"
    private const val K_SHARED = "shared_with"
    private const val K_CASH = "cash"
    private const val K_SEALS = "seals"
    private const val K_SHELF = "shelf_"
    private const val K_CLAIMS = "claimed_signatures"
}
