package local.phantom.companion.ui.home

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import local.phantom.companion.PhantomApp
import local.phantom.companion.core.design.motion.LocalMotionScale
import local.phantom.companion.core.design.motion.MotionAccess
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.navigation.Routes
import local.phantom.companion.self.PhantomVisits
import local.phantom.companion.service.PeerMeshGate
import local.phantom.companion.ui.messenger.AgencySessionManager
import local.phantom.companion.ui.messenger.WebMessengerHost
import local.phantom.companion.ui.messenger.WebNav

/** Розмови · Мапа · Гаманець · Профіль. The messenger WebView stays alive under the other tabs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(navController: NavHostController, initialTab: HomeTab = HomeTab.CHATS) {
    local.phantom.companion.StartMarks.mark("home:compose")
    val container = remember { PhantomApp.container() }
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(initialTab) }

    LaunchedEffect(Unit) {
        container.homeTabRequest.collect { requested ->
            if (requested != null) {
                container.homeTabRequest.value = null
                tab = requested
            }
        }
    }
    LaunchedEffect(Unit) { container.mapOpenRequests.collect { tab = HomeTab.MAP } }
    LaunchedEffect(tab) { container.visibleTab.value = tab }

    // Built off the main thread: the first touch of navEngine can wait on boot's lazy lock.
    val guiding by remember {
        flow { emitAll(container.navEngine.state) }.map { it.guiding }.flowOn(Dispatchers.Default)
    }.collectAsStateWithLifecycle(initialValue = false)
    val fences by remember {
        flow { emitAll(container.sentinelFenceEngine.count) }.flowOn(Dispatchers.Default)
    }.collectAsStateWithLifecycle(initialValue = 0)
    var mapWoken by rememberSaveable { mutableStateOf(false) }
    val mapWanted = MapWake.wanted(mapWoken, tab, guiding, fences)
    if (mapWanted && !mapWoken) SideEffect { mapWoken = true }
    val mapVm = if (mapWanted) rememberMapViewModel() else null
    if (mapVm != null) {
        local.phantom.companion.StartMarks.mark("home:mapVm")
        MapSession(mapVm, mapOnScreen = tab == HomeTab.MAP)
        local.phantom.companion.StartMarks.mark("home:mapSession")
    }

    val inConversation by remember {
        combine(container.events.visiblePeerChat, container.events.visiblePeerGroup) { a, b ->
            a != null || b != null
        }
    }.collectAsStateWithLifecycle(initialValue = false)
    val modalOpen by container.events.webModalOpen.collectAsStateWithLifecycle()
    val roster by container.peers.peers.collectAsStateWithLifecycle()
    val outbox by container.mesh.outbox.collectAsStateWithLifecycle()
    val marked by container.events.webUnreadMarks.collectAsStateWithLifecycle()
    val unread = remember(roster, outbox, marked) {
        local.phantom.companion.core.net.peer.PeerUnread.withMarks(roster, outbox, marked)
    }

    val bar by remember {
        container.profilesMirror.state.map { it.active?.bar }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = container.profilesMirror.state.value.active?.bar)
    LaunchedEffect(bar, tab) { if (tab !in visibleTabs(bar)) tab = HomeTab.CHATS }
    val slots by remember {
        container.profilesMirror.state.map { it.active?.slots }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = container.profilesMirror.state.value.active?.slots)
    var slot by rememberSaveable { mutableStateOf<String?>(null) }
    val closeSlot = {
        if (slot != null) WebNav.request(WebNav.Door.OpenDirection(""))
        slot = null
    }
    LaunchedEffect(slots) { if (slot != null && slots.orEmpty().none { it.id == slot }) slot = null }

    BackHandler(enabled = tab != HomeTab.CHATS || slot != null) {
        if (slot != null) closeSlot() else tab = HomeTab.CHATS
    }
    val callOpen by container.events.phantomCallOpen.collectAsStateWithLifecycle()

    val colors = LocalPhantomColors.current
    val reduced = MotionAccess.reduced(LocalMotionScale.current)
    val barVisible = homeBarVisible(tab, inConversation, modalOpen, WindowInsets.isImeVisible)
    Column(modifier = Modifier.fillMaxSize().background(colors.canvas)) {
        Box(
            modifier = Modifier
                .weight(1f)
                .then(if (barVisible) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier),
        ) {
            ChatsTab(
                active = tab == HomeTab.CHATS,
                takesBack = !callOpen,
                padBottom = !barVisible,
                onExit = { (context as? Activity)?.moveTaskToBack(true) },
            )
            AnimatedContent(
                targetState = tab,
                transitionSpec = { FadeThrough.spec(reduced) },
                label = "tabs",
            ) { shown ->
                when (shown) {
                    HomeTab.MAP -> mapVm?.let {
                        MapTab(
                            vm = it,
                            onOpenPacks = { navController.navigate(Routes.SettingsMap.path) },
                        )
                    }
                    // Гаманець — та сама поверхня, що WalletActivity, з дверима MainActivity
                    // (відбиток, вікно MWA); переходи з розмови й далі відкривають активність.
                    HomeTab.WALLET -> Box(Modifier.fillMaxSize().background(colors.canvas)) {
                        (context as? local.phantom.companion.MainActivity)?.walletEmbed?.Pane()
                    }
                    HomeTab.PROFILE -> Box(Modifier.fillMaxSize().background(colors.canvas).statusBarsPadding()) {
                        ProfileTab(navController)
                    }
                    HomeTab.CHATS -> Unit
                }
            }
        }
        if (barVisible) {
            HomeBottomBar(
                selected = tab,
                unreadChats = unread,
                bar = bar,
                slots = slots,
                selectedSlot = slot,
                onSlot = {
                    slot = it
                    tab = HomeTab.CHATS
                    WebNav.request(WebNav.Door.OpenDirection(it))
                },
                onSelect = {
                    closeSlot()
                    tab = it
                },
                onProfileDoubleTap = { WebNav.request(WebNav.Door.ProfilesBack) },
            )
        }
    }
    // Поверх усього, але в ТІЙ САМІЙ композиції: WebView під ним живе далі,
    // і завершений дзвінок лишає людину в розмові з PHANTOM, а не в списку.
    if (callOpen) {
        PhantomCallScreen(onEnd = { container.events.phantomCallOpen.value = false })
    }
}

@Composable
private fun ChatsTab(active: Boolean, takesBack: Boolean, padBottom: Boolean, onExit: () -> Unit) {
    // The mesh service follows the people screen; see MessengerDestinations' history.
    if (active) {
        LifecycleResumeEffect(Unit) {
            PeerMeshGate.peopleEntered()
            onPauseOrDispose { PeerMeshGate.peopleLeft() }
        }
    }
    WebMessengerHost(onBack = onExit, active = active, takesBack = takesBack, padBottom = padBottom)
}

/** Ask PHANTOM from outside its chat: the question lands in that chat and the chat opens. */
internal fun askPhantom(prompt: String) {
    val container = PhantomApp.container()
    val me = container.mesh.selfIdFlow.value.ifBlank { "operator_me" }
    val asked = PeerMessage(
        id = "msg_" + java.util.UUID.randomUUID().toString().take(12),
        fromId = me,
        toId = AgencySessionManager.PHANTOM_AGENT_ID,
        body = prompt,
        sentAtMs = System.currentTimeMillis(),
        state = PeerMessage.State.DELIVERED,
    )
    container.mesh.ingestDirect(asked)
    container.agencyManager.dispatchPhantomDialogue(prompt, me, questionId = asked.id)
    PhantomVisits.openChat.value = AgencySessionManager.PHANTOM_AGENT_ID
    container.homeTabRequest.value = HomeTab.CHATS
}

internal fun homeBarVisible(tab: HomeTab, inConversation: Boolean, modalOpen: Boolean, ime: Boolean): Boolean =
    !(tab == HomeTab.CHATS && (inConversation || modalOpen)) && !ime

