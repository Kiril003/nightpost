package local.phantom.companion.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import local.phantom.companion.PhantomApp
import local.phantom.companion.R
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerInvite
import local.phantom.companion.core.solana.SolanaLayer
import local.phantom.companion.core.solana.WalletActivity
import local.phantom.companion.ui.home.HomeTab
import local.phantom.companion.ui.scan.CodeEntryScreen
import local.phantom.companion.ui.scan.CodeRoute
import local.phantom.companion.ui.scan.PersonScanOutcome
import local.phantom.companion.ui.scan.ScanCard
import local.phantom.companion.ui.scan.ScanScreen
import local.phantom.companion.ui.scan.matchSafety
import local.phantom.companion.ui.screens.WebSignIn

/** Маршрут універсального сканера QR-кодів. */
@Composable
internal fun ScanDestination(navController: NavController) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as PhantomApp).container }
    LaunchedEffect(Unit) { if (container.mesh.myInvite.value == null) runCatching { container.mesh.refreshInvite() } }

    fun leave() {
        navController.popBackStack()
    }

    fun replaceWith(route: String) {
        navController.navigate(route) {
            popUpTo(Routes.Scan.path) { inclusive = true }
        }
    }

    var entering by remember { mutableStateOf(false) }

    // typed = код вставили чи набрали: це не камера, тож і не звірка наживо.
    fun land(r: CodeRoute, typed: Boolean): ScanCard? =
        when (r) {
            is CodeRoute.Person -> if (r.invite.peerId == container.mesh.myInvite.value?.peerId) {
                ScanCard(R.string.scan_card_self)
            } else {
                leave()
                container.scope.launch(Dispatchers.IO) {
                    PersonScanOutcome.land(
                        source = if (typed) PeerIdentity.KeySource.SHARED else PeerIdentity.KeySource.CAMERA,
                        accept = { src -> container.mesh.accept(r.invite, src) },
                        markVerified = { id -> container.peers.setVerified(id, true, source = PeerIdentity.KeySource.CAMERA) },
                        openChat = { id -> container.peerChatRequest.value = id },
                        openCard = { id -> container.peerCardRequest.value = id },
                    )
                }
                null
            }
            is CodeRoute.SafetyNumber -> if (typed) ScanCard(R.string.scan_card_not_ours) else {
                val me = container.mesh.myInvite.value
                val hit = me?.let {
                    matchSafety(
                        r.text,
                        PeerInvite.fingerprint(it.pubKey, it.dhPubKey),
                        container.peers.peers.value
                            .filter { p -> p.kind == PeerIdentity.Kind.PERSON && p.dhPubKey.isNotBlank() }
                            .map { p -> p.id to PeerInvite.fingerprint(p.pubKey, p.dhPubKey) },
                    )
                }
                if (hit == null) {
                    ScanCard(R.string.scan_card_no_match)
                } else {
                    leave()
                    container.scope.launch(Dispatchers.IO) {
                        container.peers.setVerified(hit, true, source = PeerIdentity.KeySource.CAMERA)
                    }
                    container.peerCardRequest.value = hit
                    null
                }
            }
            is CodeRoute.Computer -> {
                container.events.pairPayload.value = r.json
                replaceWith(Routes.Pair.path)
                null
            }
            CodeRoute.OwnDevice -> ScanCard(R.string.scan_card_own_device)
            is CodeRoute.SiteSignIn -> {
                if (r.viaLink) {
                    container.events.webSignInPayload.value = r.payload
                } else {
                    container.events.webSignInScan.value = r.payload
                }
                replaceWith(WebSignIn.ROUTE)
                null
            }
            is CodeRoute.Pay -> {
                leave()
                context.startActivity(WalletActivity.scannedIntent(context, r.link))
                null
            }
            is CodeRoute.Place -> {
                leave()
                container.events.mapPointRequest.value = r.point
                container.homeTabRequest.value = HomeTab.MAP
                null
            }
            is CodeRoute.OldInvite -> ScanCard(R.string.scan_card_old_invite)
        }

    if (entering) {
        BackHandler { entering = false }
        CodeEntryScreen(
            onBack = { entering = false },
            read = { CodeRoute.classify(it, SolanaLayer.state.value.cluster) },
            onCode = { land(it, typed = true) },
        )
        return
    }
    ScanScreen(
        onBack = { leave() },
        read = { CodeRoute.classify(it, SolanaLayer.state.value.cluster) },
        onEnterCode = { entering = true },
        onCode = { land(it, typed = false) },
    )
}
