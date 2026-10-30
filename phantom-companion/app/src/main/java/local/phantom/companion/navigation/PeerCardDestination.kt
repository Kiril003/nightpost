package local.phantom.companion.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import local.phantom.companion.PhantomApp


/** Та сама межа для полів, у які набирають ІМʼЯ: назва групи, імʼя контакта. */
private fun refusedAsFrame(
    container: local.phantom.companion.data.AppContainer,
    ctx: android.content.Context,
    typed: String,
): Boolean {
    val refused =
        local.phantom.companion.core.net.peer.PeerFrames.refuseTyped(typed) != null
    if (refused) sayRefusal(container, ctx)
    return refused
}

private fun sayRefusal(
    container: local.phantom.companion.data.AppContainer,
    ctx: android.content.Context,
) {
    container.errorBus.emit(
        source = "peer.typed",
        severity = local.phantom.companion.core.data.errors.PhantomError.Severity.Warn,
        message = ctx.getString(local.phantom.companion.R.string.peer_typed_service_prefix),
    )
}

/**
 * One person's own screen. Everything here writes to the encrypted roster, so
 * every action goes to the container's scope rather than blocking the tap.
 */
@Composable
internal fun PeerCardDestination(navController: NavController, peerId: String) {
    PeerCardBehindLock(navController, peerId)
}

@Composable
private fun PeerCardBehindLock(navController: NavController, peerId: String) {
    val ctx = LocalContext.current
    val container = remember { (ctx.applicationContext as PhantomApp).container }
    val roster by container.peers.peers.collectAsStateWithLifecycle()
    val outbox by container.mesh.outbox.collectAsStateWithLifecycle()
    val myInvite by container.mesh.myInvite.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (container.mesh.myInvite.value == null) container.mesh.refreshInvite() }
    val peer = roster.firstOrNull { it.id == peerId }
    if (peer == null) {
        LaunchedEffect(peerId) { navController.popBackStack() }
        return
    }
    val nowMs = rememberNowMs()
    val shareSessions by container.liveShare.sessions.collectAsStateWithLifecycle()
    val shareSession = shareSessions.firstOrNull { it.peerId == peerId }
    val sharePosition by container.liveShare.position.collectAsStateWithLifecycle()
    val handles = remember(peerId) { local.phantom.companion.data.PeerHandleBook.open(ctx) }
    val handle = handles.shown(peer)
    local.phantom.companion.feature.stream.ui.people.PeerCardScreen(
        peer = peer,
        handle = handle,
        myInvite = myInvite,
        nowMs = nowMs,
        // Вирок «чи дійде» перечитується на такті годинника: телефон, який
        // щойно зʼявився поруч, мусить змінити напис ДО того, як людина тисне.
        shareReach = remember(peerId, peer.dhPubKey, nowMs) { container.liveShare.reach(peerId) },
        shareSession = shareSession,
        shareRemainingMs = shareSession?.let {
            local.phantom.companion.core.net.peer.PeerLiveShare.remainingMs(it, nowMs)
        },
        sharePosition = sharePosition,
        onShareStart = { span -> container.liveShare.start(peerId, span) },
        onShareStop = { container.liveShare.stop(peerId) },
        theirPositionAtMs = remember(peerId, nowMs) {
            container.liveShare.standingFor(peerId)?.atMs
        },
        onBack = { navController.popBackStack() },
        onRename = { name ->
            if (!refusedAsFrame(container, ctx, name)) {
                container.scope.launch(Dispatchers.IO) { container.peers.rename(peerId, name) }
            }
        },
        onSetVerified = { yes ->
            container.scope.launch(Dispatchers.IO) { container.peers.setVerified(peerId, yes) }
        },
        // Скільки саме зникне — питання, на яке треба відповісти ДО дотику, і
        // відповідь читається зі сховища, а не вгадується.
        history = remember(outbox, peerId) {
            local.phantom.companion.core.net.peer.PeerPurge.conversation(outbox, peerId)
        },
        vaultCounts = remember(outbox, peerId) {
            local.phantom.companion.feature.stream.ui.people.SharedVaultCounts.of(outbox, peerId)
        },
        onClearHistory = { container.mesh.clearConversation(peerId) },
        sharesGroup = remember(outbox, peerId) {
            local.phantom.companion.core.net.peer.PeerPurge.sharesGroup(outbox, peerId)
        },
        onRemove = {
            // Листи першими, реєстр другим — інакше історія лишається сиротою.
            container.scope.launch(Dispatchers.IO) {
                container.mesh.clearConversation(peerId)
                container.peers.remove(peerId)
            }
            // Back past the conversation too: a chat with somebody who is no
            // longer in the roster is a screen that cannot send anything.
            navController.popBackStack(Routes.Home.path, inclusive = false)
        },
        onOpenChat = {
            local.phantom.companion.self.PhantomVisits.openChat.value = peerId
            container.homeTabRequest.value = local.phantom.companion.ui.home.HomeTab.CHATS
        },
        // Той самий ключ, знятий камерою з її екрана, підіймає запис до
        // доведеного — без нового знайомства. Єдине місце, крім «Людей»,
        // де камера має право на сильну позначку.
        onScanInvite = { code ->
            val decoded = local.phantom.companion.core.net.peer.PeerInvite.decode(code)
            if (decoded is local.phantom.companion.core.net.peer.PeerInvite.Decoded.Ph2) {
                container.scope.launch {
                    container.mesh.accept(
                        decoded.invite,
                        local.phantom.companion.core.net.peer.PeerIdentity.KeySource.CAMERA,
                    )
                }
            }
            decoded
        },
        // Число з її екрана, що збіглось з нашим, доводить обидва ключі тим
        // самим шляхом, що й сканування запрошення: фотони з екрана в обʼєктив.
        // Тому збіг ставить і звірку, і камеру — і тільки звідси.
        onScanVerify = { text ->
            val me = container.mesh.myInvite.value
            val row = container.peers.peers.value.firstOrNull { it.id == peerId }
            val outcome = if (me == null || row == null || row.dhPubKey.isBlank()) {
                local.phantom.companion.core.net.peer.PeerSafety.Outcome.NOT_A_NUMBER
            } else {
                local.phantom.companion.core.net.peer.PeerSafety.compare(
                    text,
                    local.phantom.companion.core.net.peer.PeerInvite.fingerprint(me.pubKey, me.dhPubKey),
                    local.phantom.companion.core.net.peer.PeerInvite.fingerprint(row.pubKey, row.dhPubKey),
                )
            }
            if (outcome == local.phantom.companion.core.net.peer.PeerSafety.Outcome.MATCH) {
                container.scope.launch(Dispatchers.IO) {
                    container.peers.setVerified(
                        peerId,
                        true,
                        source = local.phantom.companion.core.net.peer.PeerIdentity.KeySource.CAMERA,
                    )
                }
            }
            outcome
        },
    )
}


/**
 * Годинник, що цокає, поки екран справді перед людиною: «поруч» згасає за 90 с
 * саме, а не випадковою рекомпозицією.
 *
 * Delay-цикл, а не Compose-анімація — жодного кадру на секунду, тож без
 * теплового сліду. Привʼязаний до RESUMED, бо композиція розмови не
 * розбирається, коли застосунок згорнули: без цього цикл будив би процесор
 * кожні пʼять секунд у кишені, щоб оновити число, якого ніхто не бачить.
 */
@Composable
private fun rememberNowMs(
    periodMs: Long = local.phantom.companion.core.net.peer.PeerPresence.TICK_MS,
): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(owner, periodMs) {
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            local.phantom.companion.core.net.peer.PeerPresence.tick(periodMs) { now = it }
        }
    }
    return now
}
