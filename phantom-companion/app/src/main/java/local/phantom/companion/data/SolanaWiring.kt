package local.phantom.companion.data

import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import local.phantom.companion.BuildConfig
import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.solana.AddressSharing
import local.phantom.companion.core.solana.SolanaConfig
import local.phantom.companion.core.solana.SolanaContact
import local.phantom.companion.core.solana.SolanaKey
import local.phantom.companion.core.solana.SolanaLayer
import local.phantom.companion.core.solana.WalletAddressFrame
import local.phantom.companion.core.solana.PointsLine
import local.phantom.companion.core.solana.WalletPoints
import local.phantom.companion.ui.home.HomeTab
import local.phantom.companion.ui.home.PointsLoad
import local.phantom.companion.ui.home.PointsPanel
import local.phantom.companion.ui.messenger.WebMessengerGroups
import local.phantom.companion.ui.messenger.WebMessengerMeetupDraft

internal data class SolanaFlags(val licencePurchase: Boolean, val licenceBaseUrl: String)

internal fun solanaConfigFlags() = SolanaFlags(
    licencePurchase = SolanaFlavor.LICENCE_PURCHASE,
    licenceBaseUrl = if (SolanaFlavor.LICENCE_PURCHASE) BuildConfig.LICENSE_BASE_URL else "",
)

internal fun AppContainer.installSolana() {
    val flags = solanaConfigFlags()
    SolanaLayer.install(
        applicationContext,
        SolanaConfig(
            licencePurchase = flags.licencePurchase,
            licenceBaseUrl = flags.licenceBaseUrl,
            activateLicence = { key -> license.activate(key).isSuccess },
            contacts = {
                peers.peers.value.filter { it.kind == PeerIdentity.Kind.PERSON }
                    .map { SolanaContact(it.id, it.name, sharedWalletOf(it.id)) }
            },
            send = { peerId, body -> mesh.send(peerId, body) != null },
            // Частка рахунку — у групу зустрічі, і лише в ту, де я ще є.
            sendToChat = { chatId, body ->
                val log = mesh.outbox.value
                val group = WebMessengerGroups.find(log, chatId)
                    ?.takeIf { PeerGroup.standing(log, it, mesh.selfIdFlow.value) == PeerGroup.Standing.IN }
                group != null && mesh.sendToGroup(group, body).isNotEmpty()
            },
            // Адреса гаманця — усім у групі, де я є: інакше мою картку там ніхто не звірить.
            chatMembers = { chatId ->
                val log = mesh.outbox.value
                val me = mesh.selfIdFlow.value
                WebMessengerGroups.find(log, chatId)
                    ?.takeIf { PeerGroup.standing(log, it, me) == PeerGroup.Standing.IN }
                    ?.others(me).orEmpty()
            },
            // Спонсор комісій живе на тому ж сервері, що й ліцензії, але це не продаж — тож і в store.
            sponsorBaseUrl = BuildConfig.LICENSE_BASE_URL,
            sent = { sentPayments() },
            selfId = { mesh.selfIdFlow.value.takeIf { it.isNotBlank() } },
            licencePublicKey = BuildConfig.LICENSE_PUBLIC_KEY,
            nearby = nearbyCourier,
            points = { walletPoints() },
            split = {
                WebMessengerMeetupDraft.split("claimed")?.let { WebMessengerMeetupDraft.open(it) }
                homeTabRequest.value = HomeTab.CHATS
            },
            scan = { events.routeRequest.value = local.phantom.companion.navigation.Routes.Scan.path },
        ),
    )
    AddressSharing.install(applicationContext)
    scope.launch {
        combine(
            peers.peers.map { list -> list.filter { it.kind == PeerIdentity.Kind.PERSON && it.dhPubKey.isNotBlank() }.map { it.id } },
            combine(mesh.outbox, mesh.selfIdFlow) { log, me -> talkedWith(log, me) }.distinctUntilChanged(),
            AddressSharing.withAll,
            SolanaLayer.state.map { it.address },
            mesh.selfIdFlow,
        ) { people, talked, all, address, me ->
            if (address == null || me.isBlank()) emptyList() else AddressSharing.recipients(people, talked, all)
        }
            .distinctUntilChanged()
            .collect { ids -> if (ids.isNotEmpty()) SolanaLayer.syncAddress(ids) }
    }
    scope.launch {
        networkProbe.online.drop(1).filter { it }.collect { SolanaLayer.recheckPending() }
    }
}

/** Адреса, яку людина сама дала в розмові; остання перемагає. */
internal fun AppContainer.sharedWalletOf(peerId: String): SolanaKey? =
    mesh.conversation(peerId).asReversed().firstNotNullOfOrNull { m ->
        m.takeIf { it.fromId == peerId && WalletAddressFrame.isAddress(it.body) }?.let { WalletAddressFrame.decode(it.body) }
    }

/** Кишеня готівки, яку людина назвала сама: нею мають бути підписані її купюри. */
internal fun AppContainer.sharedPocketOf(peerId: String): SolanaKey? = pocketNamedBy(mesh.conversation(peerId), peerId)

internal fun pocketNamedBy(conversation: List<PeerMessage>, peerId: String): SolanaKey? =
    conversation.asReversed().firstNotNullOfOrNull { m ->
        m.takeIf { it.fromId == peerId && WalletAddressFrame.isAddress(it.body) }?.let { WalletAddressFrame.decodePocket(it.body) }
    }

/** Бали для картки гаманця — ті самі, що в Профілі: сервер рахує, гаманець лише показує. */
internal suspend fun AppContainer.walletPoints(): WalletPoints =
    when (val l = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PointsPanel.load(points.me()) }) {
        is PointsLoad.Shown -> WalletPoints.Shown(
            l.view.total,
            l.view.events.map { PointsLine(applicationContext.getString(PointsPanel.kindLabel(it.kind)), it.points, it.atMs) },
        )
        PointsLoad.Offline -> WalletPoints.Unreachable
        PointsLoad.Loading, PointsLoad.Hidden -> WalletPoints.Hidden
    }
