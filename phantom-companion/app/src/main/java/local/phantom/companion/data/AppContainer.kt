package local.phantom.companion.data

import android.content.Context
import android.util.Log
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.license.LicenseFeature
import local.phantom.companion.core.ai.AIError
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.AIRouter
import local.phantom.companion.core.ai.Route
import local.phantom.companion.core.ai.RoutingStrategy
import local.phantom.companion.core.ai.route.GeminiCloudRoute
import local.phantom.companion.core.ai.route.RetryingAIRouter
import local.phantom.companion.core.ai.withResumePrefix
import local.phantom.companion.core.data.PhantomDatabase
import local.phantom.companion.core.data.country.readAsset
import local.phantom.companion.core.data.country.readAssetStream
import local.phantom.companion.core.data.profile.ProfileStore
import local.phantom.companion.core.data.lifelog.LifeLogArchive
import local.phantom.companion.core.net.map.GeoEndpoints
import local.phantom.companion.core.net.map.routingBaseFor
import local.phantom.companion.core.sensor.nav.ConsistencyGate
import local.phantom.companion.core.sensor.nav.LocalRouter.Refusal as LegRefusal
import local.phantom.companion.core.voice.TtsEngine
import local.phantom.companion.core.voice.route.AndroidSystemTts
import local.phantom.companion.core.voice.route.RoutedTts
import local.phantom.companion.feature.godmode.engine.GodModeEventBus
import local.phantom.companion.feature.godmode.engine.TaskerEngine
import local.phantom.companion.net.PhantomApi
import local.phantom.companion.net.SymbioteExecutor
import local.phantom.companion.net.PhantomLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.emptyFlow
import local.phantom.companion.core.bus.PhantomEvent
import local.phantom.companion.core.bus.PhantomNervousBus
import local.phantom.companion.core.predict.proactive.ProactiveEngine
import local.phantom.companion.core.predict.proactive.ProactiveFrameSource
import local.phantom.companion.core.predict.proactive.ProactiveLlmReasoner
import local.phantom.companion.core.predict.proactive.ProactiveUtteranceSink
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
import local.phantom.companion.core.sensor.nav.railsIn
import local.phantom.companion.core.sensor.nav.stopsIn
import local.phantom.companion.service.NotificationLook

/**
 * ARCH: PRECISION PIVOT (2026-07-21) — a live GNSS/fused fix is navigation-grade
 * only while the chip keeps refreshing it. Past this age the position is stale:
 * still fine to tell the AI roughly WHERE the operator is, never good enough to
 * STEER on — so it drops to COARSE and guidance fails closed instead of
 * rerouting off a fix the operator already drove past. 10 s comfortably spans
 * the 5 s ambient cadence; under guidance the chip runs at 1 Hz.
 */
private const val RAW_FIX_NAV_FRESH_MS = 10_000L

// Tier-2 write-path — how often the idle loop tries to consolidate the recent
// conversation into durable facts. Cheap when there's nothing new (early-returns
// before touching the model), so a slow cadence is plenty.
internal const val MEMORY_CONSOLIDATE_INTERVAL_MS = 20 * 60_000L

/** Cloud distillation spends quota; once every few minutes is plenty. */
internal const val CLOUD_CONSOLIDATE_MIN_GAP_MS = 4 * 60_000L

// Below this the kinematic verdict must not re-pace a live route. Pinned to the
// engine's weakest WALK: at 0.55 a walk without a step detector never reached
// guidance, and the route stayed paced for the car it was planned as.
internal val GAIT_MIN_CONFIDENCE =
    local.phantom.companion.core.sensor.nav.KinematicsEngine.WALK_SPEED_ONLY_CONFIDENCE

/**
 * Hand-rolled DI graph. Keeps the dependency wiring obvious in one place
 * without dragging Hilt's annotation-processor into the build.
 */
class AppContainer(val applicationContext: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    internal val booted = java.util.concurrent.atomic.AtomicBoolean(false)

    // QH4 — central operator-visible error bus. Fed by every subsystem
    // failure that previously got swallowed via Log.e + null. The bus
    // routes Error/Critical entries into Stream as Notice items and
    // keeps the last 50 in history for Settings → Діагностика.
    val errorBus: local.phantom.companion.core.data.errors.PhantomErrorBus by lazy {
        local.phantom.companion.core.data.errors.PhantomErrorBus()
    }

    val nervousBus: PhantomNervousBus by lazy {
        PhantomNervousBus()
    }

    // Lazy, not eager: opening this store unwraps the Keystore master key
    // (EncryptedSharedPreferences), which on a cold main thread costs more
    // than the whole rest of the wiring put together.
    val store: PairedDeviceStore by lazy { PairedDeviceStore(applicationContext) }

    /** Everyone a key was proved with — devices and people alike. */
    val peers: PeerRosterStore by lazy { PeerRosterStore(applicationContext) }
    val staffInbox: StaffInbox by lazy { StaffInbox { id -> peers.peers.value.any { it.id == id } } }

    /** Кого цей телефон не слухає. Один на процес: сітка й міст читають той самий список. */
    val peerBlocks: PeerBlockStore by lazy { PeerBlockStore(applicationContext) }

    val readReceipts: ReadReceiptsStore by lazy { ReadReceiptsStore(applicationContext) }

    /** Картки посилань, які складає цей телефон до своїх листів (PeerPreview). */
    val linkPreviewSetting by lazy { local.phantom.companion.data.linkpreview.LinkPreviewStore(applicationContext) }
    val linkPreviews by lazy {
        local.phantom.companion.data.linkpreview.LinkPreviews(
            enabled = { linkPreviewSetting.enabled.value },
            narrow = { mesh.linkNarrow.value },
            fetch = { local.phantom.companion.data.linkpreview.LinkPreviewFetcher.fetch(it) },
            send = { to, body -> mesh.send(to, body) },
        )
    }
    val quietHours: QuietHoursStore by lazy { QuietHoursStore(applicationContext) }
    val chatHush: ChatHushStore by lazy { ChatHushStore(applicationContext) }

    /** This phone's own key — minted on first use, owing nothing to pairing. */
    val identity: DeviceIdentityStore by lazy { DeviceIdentityStore(applicationContext) }

    /** How far the operator read in each group — groups have no roster row. */
    val groupReads: PeerGroupReadStore by lazy { PeerGroupReadStore(applicationContext) }

    /** Обличчя групи — теж місцеве: у заголовок групи четверте поле не влазить. */
    val groupAvatars: PeerGroupAvatarStore by lazy { PeerGroupAvatarStore(applicationContext) }

    val todayTabs: TodayTabsStore by lazy { TodayTabsStore(applicationContext) }

    /** Профіль › Вигляд: тло розмов, акцент, щільність, обкладинка — окремо для кожної своєї особи. */
    val appearance: local.phantom.companion.data.appearance.AppearanceStore by lazy {
        local.phantom.companion.data.appearance.AppearanceStore(java.io.File(applicationContext.filesDir, "appearance")) {
            runCatching { identity.get().id }.getOrDefault("")
        }.also { store -> scope.launch(Dispatchers.IO) { store.bind() } }
    }

    val peerPhotos: PeerPhotoStore by lazy { PeerPhotoStore(java.io.File(applicationContext.filesDir, "peer_photos")) }
    val peerBanners: PeerBannerStore by lazy { PeerBannerStore(java.io.File(applicationContext.filesDir, "peer_banners")) }

    val profilesMirror: ProfilesMirror by lazy {
        ProfilesMirror(
            dir = java.io.File(applicationContext.filesDir, "profiles"),
            onChanged = { change ->
                profileHerald.onMirror(change)?.let {
                    android.util.Log.i("ProfileHerald", "mirror legacy=${change.legacy} faces=${change.faces.size} owners=${change.owners} frames=${it.sent.size}")
                }
            },
        )
    }

    val previewStore: local.phantom.companion.ui.messenger.preview.PreviewStore by lazy {
        local.phantom.companion.ui.messenger.preview.PreviewStore(
            dir = java.io.File(applicationContext.cacheDir, "preview"),
            openViewer = { entry ->
                local.phantom.companion.ui.messenger.preview.PreviewViewerActivity.launch(
                    applicationContext,
                    entry.id,
                    entry.kind,
                    entry.name,
                )
            },
        )
    }

    val visibility: VisibilityStore by lazy { VisibilityStore(applicationContext) }

    val profileHerald: ProfileHerald by lazy {
        ProfileHerald(
            dir = java.io.File(applicationContext.filesDir, "profile_self"),
            legacyRev = {
                applicationContext.getSharedPreferences("phantom_profile_wire", Context.MODE_PRIVATE).getLong("rev", 0L)
            },
            people = { peers.peers.value },
            blocked = { peerBlocks.blocked.value },
            name = { profileId ->
                profilesMirror.profile(profileId)?.ownName?.trim()?.takeIf { it.isNotEmpty() }
                    ?: runCatching { kotlinx.coroutines.runBlocking { profileStore.getActive()?.displayName } }.getOrNull()
            },
            handle = { profileId ->
                // Кадр повний, тож «ніка немає» — це Cleared: Absent лишив би в
                // людей знятий нік навіки.
                directory.handle.value?.takeUnless { profilesMirror.profile(profileId)?.hidesHandle == true }
                    ?.let { local.phantom.companion.core.net.peer.PeerProfile.Field.Set(it) }
                    ?: local.phantom.companion.core.net.peer.PeerProfile.Field.Cleared
            },
            send = { id, frame -> mesh.send(id, frame) != null },
            faceFor = { peerId -> profilesMirror.ownerOf(peerId)?.faceFor(peers.circlesOf(peerId)) },
            ownerOf = { peerId -> profilesMirror.ownerOf(peerId)?.id },
            hides = { peerId -> visibility.choice.value.hides(peers.circlesOf(peerId).isNotEmpty()) },
            activeProfileId = {
                profilesMirror.liveActive()?.id
                    ?: runCatching { kotlinx.coroutines.runBlocking { profileStore.getActive()?.id } }.getOrNull()
            },
            faceProfileId = { profilesMirror.liveActive()?.id },
            legacyProfileId = { key ->
                val main = profilesMirror.state.value.main
                if (main != null && ProfileHerald.dirKey(main.id) == key) {
                    runCatching { kotlinx.coroutines.runBlocking { profileStore.getActive()?.id } }.getOrNull()
                } else {
                    null
                }
            },
        ).also { h ->
            h.watch(
                scope,
                peers.peers,
                peerBlocks.blocked,
                mesh.selfIdFlow.map { it.isNotBlank() },
                kotlinx.coroutines.flow.combine(visibility.choice, peers.memberships) { v, m -> v to m },
            )
        }
    }

    val selfFace: SelfFace by lazy {
        SelfFace(
            dir = java.io.File(applicationContext.filesDir, "profile_self"),
            activeId = { profileStore.getActive()?.id },
            rename = { name ->
                val active = profileStore.getActive()
                if (active != null && active.displayName != name) profileStore.update(active.copy(displayName = name))
                mesh.refreshInvite()
                active != null && active.displayName != name
            },
            herald = { profileHerald },
        )
    }

    /**
     * Пошук людини за іменем. Адреса та сама, що й у ліцензій та сховка —
     * власник не вводить її ніде й ніколи; порожня означає, що назовні не
     * піде жодного запиту.
     */
    val directory: PeerDirectoryStore by lazy {
        PeerDirectoryStore(
            context = applicationContext,
            baseUrl = local.phantom.companion.BuildConfig.LICENSE_BASE_URL,
            myInvite = { mesh.myInvite.value ?: run { mesh.refreshInvite(); mesh.myInvite.value } },
            signingKey = { runCatching { identity.get().privateKey }.getOrNull() },
        )
    }

    /**
     * Ключ заяв (рішення власника №6.4, 10.10): окремий від ключа месенджера,
     * тож сервер закладів і балів не звʼяже оцінки, «Я на місці» й гаманець із
     * ключем, який знають контакти.
     */
    val claimKeys: ClaimKeyStore by lazy { ClaimKeyStore(applicationContext) }

    val backupKeys: local.phantom.companion.data.backup.BackupKeyStore by lazy {
        local.phantom.companion.data.backup.BackupKeyStore(applicationContext)
    }

    val webBackupStash: local.phantom.companion.data.backup.WebBackupStash by lazy {
        local.phantom.companion.data.backup.WebBackupStash(java.io.File(applicationContext.filesDir, "backup"))
    }

    val claims: ClaimSigner by lazy { ClaimSigner(key = { runCatching { claimKeys.get() }.getOrNull() }) }

    /**
     * Заклади (`platform-site/server/venues.py`): сторінка, «поруч», оцінка.
     * Адреса та сама, що в довідника й ліцензій, коли ввімкнено
     * `BuildConfig.SCENE_SERVER`. Оцінку підписує ключ заяв ([claims]), не
     * месенджера (`docs/scene/MEETUP_CONTRACT.md` §7). Речення складає заява,
     * а не веб, тож це не оракул підпису. Немає ключа — `null`.
     */
    val venues: local.phantom.companion.data.VenueClient by lazy {
        local.phantom.companion.data.VenueClient(
            baseUrl = local.phantom.companion.data.VenueClient.sceneBase(
                local.phantom.companion.BuildConfig.SCENE_SERVER,
                local.phantom.companion.BuildConfig.LICENSE_BASE_URL,
            ),
            signer = local.phantom.companion.data.VenueClient.deviceSigner(claims::prove),
            http = local.phantom.companion.data.VenueClient.urlConnection(),
            owner = local.phantom.companion.data.VenueClient.ownerSigner { runCatching { identity.get() }.getOrNull() },
        )
    }

    /**
     * Бали (`platform-site/server/points.py`): «Я на місці», перша оплачена
     * частка, власні бали. Той самий сервер, той самий прапорець і той самий
     * ключ заяв, що в [venues]; бали рахує лише сервер.
     */
    val points: local.phantom.companion.data.PointsClient by lazy {
        local.phantom.companion.data.PointsClient(
            baseUrl = local.phantom.companion.data.VenueClient.sceneBase(
                local.phantom.companion.BuildConfig.SCENE_SERVER,
                local.phantom.companion.BuildConfig.LICENSE_BASE_URL,
            ),
            signer = local.phantom.companion.data.VenueClient.deviceSigner(claims::prove),
            http = local.phantom.companion.data.VenueClient.urlConnection(),
        )
    }

    /**
     * Заявки на «першу оплачену частку»: веб лише лишає їх у мосту, а відбиток під
     * підписом гаманця просить Профіль, коли людина сама натисне «Зарахувати».
     * Один екземпляр на міст і Профіль — вони бачать одну заявку.
     */
    val pointsClaims: local.phantom.companion.data.PointsClaims by lazy {
        local.phantom.companion.data.PointsClaims(
            api = points,
            memory = local.phantom.companion.data.PointsClaimsMemory(applicationContext),
        )
    }

    /**
     * СИМБІОЗ, хвиля 1 — спільна поверхня: месенджер пише точки людей, мапа їх
     * читає. Памʼять, не диск: точка живе рівно стільки, скільки триває сеанс.
     */
    val peerPoints: local.phantom.companion.core.data.peers.PeerPointsRepository by lazy {
        local.phantom.companion.core.data.peers.PeerPointsRepository()
    }

    /** Що ця копія має право робити. Мапа й тривоги не питають — вони завжди є. */
    val license: local.phantom.companion.license.LicenseService by lazy {
        local.phantom.companion.license.LicenseService(applicationContext, scope)
    }

    /**
     * Купівля ліцензії в застосунку. Є лише в store-збірці (Google Play
     * Billing); у польовій — null, ключ купують на сайті.
     */
    val checkout: local.phantom.companion.license.Checkout? by lazy {
        local.phantom.companion.license.CheckoutFactory.create(applicationContext, scope, license)
    }

    val outboxStore: PeerOutboxStore by lazy { PeerOutboxStore(applicationContext) }

    // Один на процес: два екземпляри з власною памʼяттю перетирали б один одного на диску.
    val savedNotes: SavedNotes by lazy { SavedNotes(applicationContext) }

    // Пряма секретність для листів через сховок (docs/crypto/RELAY_FS.md), за прапорцем.
    val relayFs: local.phantom.companion.data.relayfs.RelayFsWiring by lazy {
        local.phantom.companion.data.relayfs.RelayFsWiring(this)
    }

    // Зникаючі повідомлення: строк розмови, мітки прочитання, щохвилинне стирання.
    val moments: MomentsWiring by lazy { MomentsWiring(this) }

    val contactRequests: ContactRequestsWiring by lazy { ContactRequestsWiring(this) }

    /** Посилання-запрошення в гілку: маршрут веба й відповідь на прохання вступити. */
    internal val groupLinks: local.phantom.companion.ui.messenger.WebMessengerGroupLinks by lazy {
        local.phantom.companion.ui.messenger.WebMessengerGroupLinks(object : local.phantom.companion.ui.messenger.WebMessengerGroupLinks.Host {
            override fun me() = mesh.selfIdFlow.value
            override fun log() = mesh.outbox.value
            override fun myInvite() = mesh.myInvite.value
            override fun knows(peerId: String) = peers.peers.value.any { it.id == peerId && it.dhPubKey.isNotBlank() }
            override fun nameOf(peerId: String) = peers.peers.value.firstOrNull { it.id == peerId }?.name.orEmpty()
            override suspend fun accept(invite: local.phantom.companion.core.net.peer.PeerInvite.Invite) {
                mesh.accept(invite)
            }
            override fun send(toId: String, body: String) = mesh.send(toId, body) != null
            override fun sendAct(group: local.phantom.companion.core.net.peer.PeerGroup.Group, act: local.phantom.companion.core.net.peer.PeerGroup.Act) =
                mesh.sendGroupAct(group, act).isNotEmpty()
        })
    }

    val disappear: local.phantom.companion.data.disappear.DisappearWiring by lazy {
        local.phantom.companion.data.disappear.DisappearWiring(this)
    }

    // Відкладені листи телефона: шле будильник, переживають перезавантаження.
    val scheduledLetters: local.phantom.companion.data.scheduled.ScheduledLetters by lazy {
        val slot = EncryptedSlot(applicationContext, "phantom_scheduled", durable = true)
        local.phantom.companion.data.scheduled.ScheduledLetters(read = { slot.read("letters") }, write = { slot.write("letters", it) })
    }

    // Розд. 07 §7 «Рюкзак перед підвалом» і §10 «Сирена-маяк».
    val backpack: local.phantom.companion.data.backpack.BackpackRunner by lazy {
        local.phantom.companion.data.backpack.BackpackRunner(this)
    }

    // Розд. 07 «Обхідний інтернет»: прохання до мого ПК сховком або SMS.
    val intents: local.phantom.companion.data.intent.IntentWiring by lazy {
        local.phantom.companion.data.intent.IntentWiring(this)
    }

    /**
     * Байти фото й файлів. Опис їх лежить у журналі листів, тож сховище про
     * них не памʼятає нічого власного — питає журнал.
     */
    val peerFiles: PeerFileStore by lazy {
        PeerFileStore(
            filesDir = applicationContext.filesDir,
            manifestOf = { blobId ->
                PeerFileStore.manifestIn(mesh.outbox.value, blobId) ?: moments.manifest(blobId) ?: bannerPull.manifest(blobId)
            },
        )
    }

    /** Петля банера на прохання глядача: див. [BannerVideoPull]. */
    val bannerPull: BannerVideoPull by lazy {
        BannerVideoPull(
            files = peerFiles,
            own = { peerId -> profileHerald.sharedBannerVideo(peerId) },
            specOf = { id -> peers.peers.value.firstOrNull { it.id == id }?.bannerSpec },
            specs = { peers.peers.value.map { it.bannerSpec } },
            mayServe = { id -> id !in peerBlocks.blocked.value && peers.peers.value.any { it.id == id } },
            send = { id, body -> mesh.send(id, body) != null },
            sendMany = { id, bodies -> mesh.sendMany(id, bodies) },
        )
    }

    /** Phone-to-phone, with no server between: listener, beacon and outbox. */
    val mesh: PeerMesh by lazy {
        PeerMesh(
            roster = peers,
            log = outboxStore,
            selfId = { runCatching { identity.get().id }.getOrNull() },
            privateKey = { runCatching { identity.get().privateKey }.getOrNull() },
            publicKey = { runCatching { identity.get().publicKeyB64 }.getOrNull() },
            dhPrivateKey = { runCatching { identity.get().dhPrivateKey }.getOrNull() },
            dhPublicKey = { runCatching { identity.get().dhPublicKeyB64 }.getOrNull() },
            myName = { profilesMirror.inviteName(runCatching { profileStore.getActive()?.displayName }.getOrNull()) },
            beacon =local.phantom.companion.core.net.peer.PeerBeacon(applicationContext),
            relayOpen = ::openRelay,
            online = { networkProbe.online.value },
            // Байти шматків живуть на диску, а в журналі — посилання. Обидві
            // лямбди ведуть у те саме сховище, яке ці байти й так тримає:
            // другий їх примірник у журналі був чистою платою, і саме він
            // ставив стелю на розмір файла.
            vaultChunk = { conversationId, body -> peerFiles.vault(conversationId, body) },
            openChunk = { body -> peerFiles.rehydrate(body) },
            onArrived = ::onPeerMessage,
            onCall = { callHub.absorb(it) },
            onWhere = { peopleWhere.absorb(it) },
            onTyping = { typingIn.tryEmit(it) },
            blocked = { peerBlocks.blocked.value },
            onUnsent = { conversationId, line ->
                local.phantom.companion.core.net.peer.PeerFile.describe(line.body)
                    ?.let { peerFiles.drop(conversationId, it.blobId) }
            },
            nearby = nearbyCourier,
        )
    }

    // Один інстанс на весь застосунок: тримає кеш TURN-пари й один OkHttp
    // клієнт. WebMessengerBridge створюється заново на кожен WebView — якби
    // він будував свій WebMessengerIce, кеш не влучав би ніколи й кожен
    // виклик лишав по собі незакритий HttpClient.
    val webMessengerIce: local.phantom.companion.ui.messenger.WebMessengerIce by lazy {
        local.phantom.companion.ui.messenger.WebMessengerIce.of(this)
    }

    val nearbyCourier: local.phantom.companion.data.nearby.NearbyCourier by lazy {
        local.phantom.companion.data.nearby.NearbyFlavor.courier(
            context = applicationContext,
            scope = scope,
            selfId = { mesh.selfIdFlow.value },
            contacts = {
                val blocked = peerBlocks.blocked.value
                peers.peers.value
                    .filter { it.kind == local.phantom.companion.core.net.peer.PeerIdentity.Kind.PERSON && it.dhPubKey.isNotBlank() && it.id !in blocked }
                    .map { it.id }
            },
            online = { networkProbe.online.value },
            inbound = { raw -> mesh.acceptNearby(raw) },
        )
    }

    /** Чужий кадр «друкую»; хто його покаже — вирішує месенджер. */
    val typingIn = kotlinx.coroutines.flow.MutableSharedFlow<local.phantom.companion.core.net.peer.PeerMessage>(
        extraBufferCapacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /**
     * Дзвінок 1:1 — один на застосунок.
     *
     * Живе тут, а не в месенджері й не в екрані: запрошення приходить, коли
     * жодного з них на екрані немає, і апарат мусить піднятись однаково.
     * Дорога назад — [PeerMesh.sendCallFrame]: той самий запечатаний конверт,
     * що й слово, і ЛИШЕ пряма дорога (див. [PeerCall.SIGNAL]).
     */
    val callHub: PeerCallHub by lazy {
        PeerCallHub(
            send = { peerId, frame -> mesh.sendCallFrame(peerId, frame) },
            reachable = { peerId -> mesh.canCall(peerId) },
            onLine = ::onCallLine,
            onVideo = { local.phantom.companion.service.PeerCallNotifications.videoChanged(applicationContext, it) },
            selfId = { mesh.selfIdFlow.value },
            nameOf = { id -> peers.peers.value.firstOrNull { it.id == id }?.name },
            accepts = { id -> visibility.choice.value.calls.allows(peers.circlesOf(id).isNotEmpty()) },
            groupMembers = { id ->
                local.phantom.companion.core.net.peer.PeerGroup.known(mesh.outbox.value)
                    .firstOrNull { it.id == id }?.memberIds
            },
        ).also { it.attach(scope) }
    }

    /**
     * Лінія змінилась. Шторка — єдина поверхня, яка є завжди: застосунок може
     * бути закритий, і саме тому повноекранне сповіщення, а не рядок у стрічці.
     *
     * [id] у лінії — не завжди людина: дзвінок у групу веде облік за
     * `group.id` ([PeerCallHub.postToGroup]), і без цієї гілки плашка
     * показувала б обрізаний хеш замість назви розмови.
     */
    private fun onCallLine(line: local.phantom.companion.core.net.peer.PeerCallLine.Line) {
        val who = local.phantom.companion.core.net.peer.PeerCallLine.peerOf(line)?.let { id ->
            peers.peers.value.firstOrNull { it.id == id }?.name
                ?: local.phantom.companion.core.net.peer.PeerGroup.known(mesh.outbox.value)
                    .firstOrNull { it.id == id }?.name
                ?: local.phantom.companion.core.net.peer.PeerRoster.shortId(id)
        }
        local.phantom.companion.service.PeerCallNotifications.show(
            context = applicationContext,
            line = line,
            callerName = who.orEmpty(),
            quiet = local.phantom.companion.core.net.peer.PeerCallLine.peerOf(line)
                ?.let(quietHours::hushesNow) == true,
        )
    }

    /** Мультиагентний менеджер та інтеграція ШІ-агента для Месенджера */
    val agencyManager: local.phantom.companion.ui.messenger.AgencySessionManager by lazy {
        local.phantom.companion.ui.messenger.AgencySessionManager(this)
    }

    /**
     * Один вхід на все, що приїхало. Байти лягають на диск ПЕРШИМИ: шторка
     * може промовчати (розмова відкрита), і збирання файла, підвішене за
     * сповіщення, не відбувалось би саме тоді, коли людина дивиться на екран.
     */
    private fun onPeerMessage(message: local.phantom.companion.core.net.peer.PeerMessage) {
        runCatching { peerFiles.absorb(message, mesh.selfIdFlow.value) }
        runCatching { bannerPull.serve(message) }
        runCatching { liveShare.absorb(message) }
        runCatching { moments.absorb(message) }
        runCatching { contactRequests.absorb(message) }
        runCatching { groupLinks.absorb(message) }
        runCatching { if (staffInbox.absorb(message.fromId, message.body)) mesh.forgetLive(setOf(message.id)) }
        // Профіль лягає в реєстр, а не в стрічку: він оновлює картку людини.
        // [PeerRoster.absorbProfile] сам відсіює не-кадри, чужих і повтори
        // переказу, тож перевірка роду тут була б другою правдою про дріт.
        val applied = runCatching { peers.absorbProfile(message.fromId, message.body) }.getOrDefault(false)
        if (applied) runCatching { peerPhotos.absorb(message.fromId, message.body) }
        runCatching {
            peerBanners.take(message.fromId, message.body, applied, specOf(message.fromId)) { peers.peers.value.map { it.bannerSpec } }
        }
        announceMessage(message)
    }

    private fun specOf(id: String): String? = peers.peers.value.firstOrNull { it.id == id }?.bannerSpec

    /**
     * «Ділюсь положенням з обраним» — сеанси в обидва боки.
     *
     * Живе тут, а не в мапі й не в людях: обидва екрани мусять бачити ОДИН
     * сеанс, а не кожен свій. Годинник заводиться лише коли сеанс є.
     */
    val liveShare: PeerLiveHub by lazy {
        PeerLiveHub(
            points = peerPoints,
            roster = { peers.peers.value },
            fix = { locationProvider.current() },
            fixAllowed = { local.phantom.companion.privacy.LocationGrants.held(applicationContext) },
            selfId = { mesh.selfIdFlow.value },
            addressed = { mesh.addressed.value },
            relayOpen = { mesh.relayReady() },
            outbox = { mesh.outbox.value },
            send = { peerId, body -> mesh.send(peerId, body) },
            forget = { ids -> mesh.forgetLive(ids) },
        ).also {
            it.attach(scope)
            local.phantom.companion.feature.stream.ui.map.live.LiveShareForeground.bind(
                applicationContext, scope, it.banners.map { b -> b.map { x -> x.name } }, onStop = it::stopAll,
            )
        }
    }

    val peopleWhere: PeerWhereHub by lazy {
        PeerWhereHub(
            points = peerPoints,
            roster = { peers.peers.value },
            fix = { locationProvider.current() },
            send = { peerId, body -> mesh.send(peerId, body) },
            grantStore = PeerWhereGrantStore(applicationContext),
            fixAllowed = { local.phantom.companion.privacy.LocationGrants.held(applicationContext) },
            outbox = { mesh.outbox.value },
            forget = { ids -> mesh.forgetLive(ids) },
        ).also { hub ->
            hub.attach(scope)
            peers.bindWhere(HubWhere(hub))
            scope.launch { peers.peers.collect { hub.onRoster(it) } }
            scope.launch { sensorAdapter?.gpsFeed?.collect { hub.onFix() } }
        }
    }

    /** Рядки «Хто мене бачить»: усі люди, дозвіл кожного і чи його взагалі можна дати. */
    val whoSeesMe: kotlinx.coroutines.flow.StateFlow<List<local.phantom.companion.core.data.peers.PeerSeeRow>> by lazy {
        kotlinx.coroutines.flow.combine(peers.peers, peopleWhere.grants, peers.memberships) { list, grants, circles ->
            list.filter { it.kind == local.phantom.companion.core.net.peer.PeerIdentity.Kind.PERSON }
                .map {
                    local.phantom.companion.core.data.peers.PeerSeeRow(
                        peerId = it.id,
                        name = it.name.ifBlank { local.phantom.companion.core.net.peer.PeerRoster.shortId(it.id) },
                        precision = grants[it.id]?.let { p ->
                            local.phantom.companion.core.data.peers.PeerPrecision.valueOf(p.name)
                        },
                        eligible = local.phantom.companion.core.net.peer.PeerWhere.mayReceive(it),
                        inClose = "close" in circles[it.id].orEmpty(),
                    )
                }
                .sortedWith(compareBy({ it.precision == null }, { !it.eligible }, { it.name.lowercase() }))
        }.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())
    }

    fun setWhoSeesMe(peerId: String, precision: local.phantom.companion.core.data.peers.PeerPrecision?): Boolean =
        peopleWhere.setPrecision(
            peerId,
            precision?.let { local.phantom.companion.core.net.peer.PeerWhere.Precision.valueOf(it.name) },
        )

    /**
     * Кому можна віддати маршрут: лише ті, чий ключ у нас є. Перечитується,
     * коли міняється реєстр АБО коли хтось зʼявився поруч — вирок «чи дійде»
     * мусить бути свіжим у мить, коли на нього дивляться.
     */
    val routeShareTargets: kotlinx.coroutines.flow.StateFlow<
        List<local.phantom.companion.core.net.peer.PeerLiveShare.Target>,
        > by lazy {
        kotlinx.coroutines.flow.combine(peers.peers, mesh.addressed) { list, _ ->
            list.filter { it.dhPubKey.isNotBlank() }
                .map {
                    local.phantom.companion.core.net.peer.PeerLiveShare.Target(
                        peerId = it.id,
                        name = it.name,
                        reach = liveShare.reach(it.id),
                    )
                }
        }.stateIn(
            scope,
            kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
            emptyList(),
        )
    }

    /**
     * Сховок, куди лягає слово, поки другий телефон вимкнений.
     *
     * Адреса та сама, що й у ліцензій: сховок — це ті самі три ручки на тому
     * самому сервері, і власник не вводить її ніде й ніколи. Порожня (складання
     * без `phantom.licenseHost`) — ворота кажуть NO_ADDRESS і телефон не робить
     * назовні жодного запиту.
     */
    private fun openRelay(
        contacts: Int,
    ): local.phantom.companion.core.net.peer.PeerRelayTransport? =
        local.phantom.companion.core.net.peer.PeerRelayGate.open(
            settings = local.phantom.companion.core.net.peer.PeerRelayGate.Settings(
                enabled = true,
                baseUrl = local.phantom.companion.BuildConfig.LICENSE_BASE_URL,
                legacyUrl = local.phantom.companion.BuildConfig.LEGACY_RELAY_HOST,
            ),
            contacts = contacts,
            legacyQueue = java.io.File(applicationContext.filesDir, "relay_legacy_queue"),
        )

    /**
     * A believed message reaches the operator even with the app asleep — that
     * is the whole point of a listener that runs in a service. Silent while the
     * conversation is already on screen.
     */
    private fun chatsOnScreen(): Boolean =
        events.visibleTab.value == local.phantom.companion.ui.home.HomeTab.CHATS

    private fun announceMessage(message: local.phantom.companion.core.net.peer.PeerMessage) =
        local.phantom.companion.service.ShadeDoor.arrived(applicationContext, mesh.selfIdFlow.value, message.fromId, message.id) {
            announceFresh(message)
        }

    private fun announceFresh(message: local.phantom.companion.core.net.peer.PeerMessage) {
        local.phantom.companion.core.net.peer.PeerAnnounce.reaction(
            message = message,
            log = mesh.outbox.value,
            visibleChat = events.visiblePeerChat.value.takeIf { chatsOnScreen() },
            visibleGroup = events.visiblePeerGroup.value.takeIf { chatsOnScreen() },
            selfId = mesh.selfIdFlow.value,
            isHuman = { local.phantom.companion.ui.messenger.LocalAuthors.isHuman(it) },
        )?.let {
            announceReaction(message, it)
            return
        }
        // A group line arrives as an ordinary private copy, so the group path
        // needs its own silencer. The rule itself lives in core-net where it can
        // be proved without a phone; here it only gets a name and a string.
        val notice = local.phantom.companion.core.net.peer.PeerAnnounce.notice(
            message = message,
            visibleChat = events.visiblePeerChat.value.takeIf { chatsOnScreen() },
            visibleGroup = events.visiblePeerGroup.value.takeIf { chatsOnScreen() },
            selfId = mesh.selfIdFlow.value,
            isHuman = { local.phantom.companion.ui.messenger.LocalAuthors.isHuman(it) },
        ) ?: return
        if (local.phantom.companion.core.solana.SolanaNotice.isSilent(notice.text)) return
        if (!InboxReadable.shows(mesh.outbox.value, peers.peers.value, notice, message.id)) return
        val who = local.phantom.companion.service.NoticeLabels.senderName(
            peers.peers.value.firstOrNull { it.id == notice.fromId }?.name,
            applicationContext.getString(local.phantom.companion.R.string.peer_unknown_sender),
        )
        // Never the wire form. «PH-LOC:50.44020,30.51950;25» in the shade is
        // the protocol shouting at a human, and the one line they see before
        // deciding whether to open the app at all.
        val attachment = local.phantom.companion.core.net.peer.PeerFile.describe(notice.text)
        val body = when {
            local.phantom.companion.core.net.peer.PeerPayload.isPlace(notice.text) ->
                applicationContext.getString(
                    local.phantom.companion.feature.stream.R.string.peer_place_notification,
                )
            // Картка доходить до РОЗМОВИ, отже й до шторки — на відміну від
            // решти службового вантажу, який глушить PeerAnnounce.silent(). Без
            // цієї гілки в сповіщенні стояло б «PH-CRD:kb;eyJjb2x1bW5z…»: той
            // самий дріт в очі, тільки в рядку, який людина бачить ПЕРШИМ і за
            // яким вирішує, чи відкривати застосунок узагалі.
            local.phantom.companion.ui.messenger.WebMessengerCardLine.isGift(notice.text) ->
                applicationContext.getString(local.phantom.companion.R.string.gift_notification)
            local.phantom.companion.core.net.peer.PeerCard.isCard(notice.text) ->
                applicationContext.getString(
                    local.phantom.companion.R.string.peer_card_notification,
                )
            local.phantom.companion.core.solana.SolanaNotice.isOurs(notice.text) ->
                local.phantom.companion.core.solana.SolanaNotice.line(applicationContext, notice.text)
            // Той самий закон, що й для місця: у шторці не буває дроту.
            local.phantom.companion.core.net.peer.PeerLive.isRoute(notice.text) ->
                applicationContext.getString(
                    local.phantom.companion.feature.stream.R.string.peer_route_notification,
                )
            local.phantom.companion.core.net.peer.PeerMoment.isMoment(notice.text) ->
                applicationContext.getString(local.phantom.companion.R.string.moment_notification)
            // Опис вкладення — це JSON з ключем усередині. У шторці він був би
            // не просто негарним: це рядок, який людина бачить ПЕРШИМ.
            attachment != null -> attachment.caption.ifBlank {
                applicationContext.getString(
                    when (local.phantom.companion.service.NoticeLabels.attachKind(attachment.kind, attachment.mime)) {
                        local.phantom.companion.service.NoticeLabels.Attach.PHOTO ->
                            local.phantom.companion.feature.stream.R.string.peer_photo_notification
                        local.phantom.companion.service.NoticeLabels.Attach.VOICE ->
                            local.phantom.companion.feature.stream.R.string.peer_voice_notification
                        local.phantom.companion.service.NoticeLabels.Attach.VIDEO ->
                            local.phantom.companion.feature.stream.R.string.peer_video_notification
                        local.phantom.companion.service.NoticeLabels.Attach.FILE ->
                            local.phantom.companion.feature.stream.R.string.peer_file_notification
                    },
                )
            }
            local.phantom.companion.service.MarkerLines.markerOnlyKind(notice.text) != null ->
                applicationContext.getString(
                    local.phantom.companion.service.MarkerLines.markerOnlyKind(notice.text)!!.res,
                )
            else -> notice.text
        }
        // Імʼя людини окремо від назви групи: MessagingStyle сам малює автора
        // під заголовком розмови, а склеєне «Хто · Група» ставало іменем
        // неіснуючої людини і ламало і аватар, і ярлик розмови.
        local.phantom.companion.service.PeerNotifications.notifyMessage(
            context = applicationContext,
            notice = notice,
            senderName = who,
            body = body,
            atMs = message.sentAtMs,
            quiet = notice.quiet || quietHours.hushesNow(notice.fromId) &&
                !local.phantom.companion.service.PeerNotifications.mentionBreaks(applicationContext, notice),
        )
    }
    private fun announceReaction(
        message: local.phantom.companion.core.net.peer.PeerMessage,
        r: local.phantom.companion.core.net.peer.PeerAnnounce.Reacted,
    ) {
        if (!InboxReadable.shows(mesh.outbox.value, peers.peers.value, r.notice, message.id)) return
        val who = local.phantom.companion.service.NoticeLabels.senderName(
            peers.peers.value.firstOrNull { it.id == r.notice.fromId }?.name,
            applicationContext.getString(local.phantom.companion.R.string.peer_unknown_sender),
        )
        local.phantom.companion.service.PeerNotifications.notifyMessage(
            context = applicationContext,
            notice = r.notice,
            senderName = who,
            body = local.phantom.companion.service.NoticeLabels.reaction(applicationContext, r.emoji, r.quoted),
            atMs = message.sentAtMs,
            quiet = r.notice.quiet || quietHours.hushesNow(r.notice.fromId),
        )
    }

    val pairClient: local.phantom.companion.core.net.pair.PairFlowClient by lazy {
        local.phantom.companion.core.net.pair.PairFlowClient()
    }
    val pairDiscovery: local.phantom.companion.core.net.pair.PairDiscoveryClient by lazy {
        local.phantom.companion.core.net.pair.PairDiscoveryClient(
            mdnsDiscovery = local.phantom.companion.core.net.discovery.MdnsDiscovery(applicationContext),
        )
    }
    // PhantomApi builds a ktor/OkHttp engine in its constructor — class
    // loading nobody needs before the first request or the first pair check.
    val api: PhantomApi by lazy { PhantomApi(store) }
    val link: PhantomLink by lazy { PhantomLink(api, store) }

    val symbiote: SymbioteExecutor by lazy {
        SymbioteExecutor(
            applicationContext,
            api,
            link,
            scope,
            awaitVerdict = { awaitGuardedVerdict() },
        ) { text ->
            ttsEngine.speak(text)
        }
    }

    // Phase 1-C-3 (companion-v2) — multi-profile store backed by the
    // SQLCipher-encrypted Room database. Opened lazily on first access
    // so cold app launch isn't blocked by the EncryptedSharedPreferences
    // → Keystore unwrap of the per-install DB key.
    val database: PhantomDatabase by lazy {
        // SQLCipher 4.6.x wants its native lib loaded before the first DAO
        // query on SDK 34+. Doing it here — inside the same synchronized lazy
        // that opens the DB — guarantees the ordering without paying for it on
        // the main thread the way PhantomApp.onCreate used to.
        // Відповідь тримає VaultCrypto, бо її читає ще й пігулка сховища в
        // профілі: тиха невдача тут малювалась там як «SQLCipher» назавжди.
        if (!local.phantom.companion.core.data.VaultCrypto.nativeLoaded) {
            Log.e("AppContainer", "SQLCipher loadLibrary failed")
        }
        PhantomDatabase.open(applicationContext)
    }
    val profileStore: ProfileStore by lazy { ProfileStore(database.profileDao()) }
    val vaultStore: local.phantom.companion.core.data.vault.VaultStore by lazy {
        local.phantom.companion.core.data.vault.VaultStore(database.vaultDao())
    }

    // Phase 1.5-D — Decision Diary local-only store. Backend sync (when
    // PHANTOM OS publishes a `decision_diary/*` channel) will hook into
    // the same Flow surface in a follow-up phase.
    val diaryStore: local.phantom.companion.core.data.diary.DiaryStore by lazy {
        local.phantom.companion.core.data.diary.DiaryStore(
            dao = database.decisionEntryDao(),
            pendingDao = database.pendingPublishDao(),
        )
    }

    /**
     * DA-3 / B-1 — Process-global subscriber for `decision_diary`
     * frames. Writes through to DiaryStore and surfaces follow-ups
     * for the UI.
     */
    val diarySubscriber: local.phantom.companion.feature.stream.diary.DiaryWebSocketSubscriber by lazy {
        local.phantom.companion.feature.stream.diary.DiaryWebSocketSubscriber(diaryStore)
    }

    /**
     * Phase 3-D3 — concrete transport that bridges DiaryStore.publish
     * into PhantomLink. Reads `link.connected` for the cheap
     * isConnected predicate; encodes the §10.15 envelope and calls
     * `link.send(text)`. Returns false on any failure path so
     * DiaryStore queues the envelope for the reconnect drain.
     */
    val diaryTransport: local.phantom.companion.core.data.diary.DiaryPublishTransport by lazy {
        object : local.phantom.companion.core.data.diary.DiaryPublishTransport {
            override val isConnected: Boolean
                get() = link.connected.value == local.phantom.companion.net.LinkStatus.Connected

            override suspend fun send(verb: String, payload: String): Boolean {
                if (!isConnected) return false
                val envelope = local.phantom.companion.core.data.diary.DiaryPublishCodec
                    .encodeEnvelope(verb, payload, System.currentTimeMillis())
                return runCatching { link.send(envelope) }.getOrDefault(false)
            }
        }
    }

    // Phase 1-E-3 — AI key storage + router wired in :app DI.
    // AiKeyStore wraps EncryptedSharedPreferences; opened lazily so it
    // does not block cold launch alongside PairedDeviceStore.
    val aiKeyStore: AiKeyStore by lazy { AiKeyStore(applicationContext) }

    val modelManager: local.phantom.companion.core.ai.ModelManager by lazy {
        local.phantom.companion.core.ai.ModelManager(
            client = io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
                install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                    json(kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
                }
                // Many CDNs (alphacephei, HF) reject empty/default UA.
                install(io.ktor.client.plugins.UserAgent) {
                    agent = "PhantomCompanion/0.1 (Android)"
                }
                // Follow HF resolve → CDN redirects.
                followRedirects = true
            },
            rootDir = applicationContext.filesDir,
            hfTokenProvider = { aiKeyStore.hfToken.value },
            // Єдина адреса на весь каталог. Порожня — «завантажити нема звідки»,
            // і це кажеться словом; чужого хоста замість неї не підставляють.
            modelsBaseUrl = { local.phantom.companion.BuildConfig.MODELS_BASE_URL },
            // The container's scope, not a screen's: model transfers are
            // application-lifetime work. Supertonic alone is ~400 MB — it
            // must survive the operator leaving Settings mid-download.
            scope = scope,
        )
    }

    val onnxEmbeddingHelper: local.phantom.companion.core.ai.OnnxEmbeddingHelper by lazy {
        // Real WordPiece vocab from the bundled library asset (merged into the APK).
        val tokenizer = runCatching {
            applicationContext.assets.open("minilm_vocab.txt").bufferedReader().use { reader ->
                local.phantom.companion.core.ai.WordPieceTokenizer.fromVocabLines(reader.lineSequence())
            }
        }.getOrNull()
        local.phantom.companion.core.ai.OnnxEmbeddingHelper(
            modelManager.getModelFile(local.phantom.companion.core.ai.ModelType.EMBEDDING),
            tokenizer,
        )
    }


    /**
     * Composite AI router: cloud Gemini (key from [AiKeyStore]) with
     * GemmaLocalRoute as fallback.
     *
     * Strategy defaults to [RoutingStrategy.CloudFirst] for Tier 1
     * standalone mode — on-device Gemma lands in Phase 1-D-2.
     *
     * The [apiKeyProvider] lambda captures the live [StateFlow] value so
     * key changes (after the operator saves a new key in the settings
     * dialog) are picked up on the next [complete] call without
     * recreating the router.
     *
     * Wrapped in [runCatching] so a Keystore/BouncyCastle init failure on
     * Samsung One UI 7 / MIUI does not crash the process — the app boots
     * in a degraded-AI mode and surfaces an error token instead of dying.
     */
    val pcBrainRoute: local.phantom.companion.core.ai.route.PcBrainRoute by lazy {
        local.phantom.companion.core.ai.route.PcBrainRoute(
            // «Нічого не виходить з пристрою» стосується й власного ПК: через реле байти йдуть мережею.
            reachable = {
                brainPrefs.routing.value != RoutingStrategy.LocalOnly &&
                    link.connected.value == local.phantom.companion.net.LinkStatus.Connected
            },
            ask = { content, session ->
                val (status, body) = api.askBrain(content, session)
                local.phantom.companion.core.ai.route.PcBrainRoute.Reply(status, body)
            },
            yieldToolTurns = {
                brainPrefs.routing.value != RoutingStrategy.LocalOnly &&
                    geminiRoute.availableRoutes().isNotEmpty() &&
                    // Без дороги в інтернет ключ не відповість — хід лишається ПК.
                    local.phantom.companion.data.intent.InternetPath.has(applicationContext)
            },
        )
    }

    val geminiRoute: GeminiCloudRoute by lazy {
        GeminiCloudRoute(apiKeyProvider = { aiKeyStore.geminiKey.value })
    }

    private val geminiLite: GeminiCloudRoute by lazy {
        GeminiCloudRoute(apiKeyProvider = { aiKeyStore.geminiKey.value }, fixedProfile = local.phantom.companion.core.ai.BrainProfile.CHAT)
    }

    /** Власний ключ Gemini щойно відмовив (ключ/квота) — голос і чат ідуть сервером. */
    fun ownKeyDead(): Boolean = cloudUnlessLocalOnly.dead()

    fun noteOwnKeyRefusal(httpCode: Int, body: String) =
        cloudUnlessLocalOnly.note(local.phantom.companion.core.ai.AIError.CloudRejected(httpCode, body))

    val nightpostBrain by lazy {
        nightpostBrainRoute(applicationContext, brainPrefs, identity, ownKeyDead = { cloudUnlessLocalOnly.dead() }) { aiKeyStore.geminiKey.value }
    }

    /** Власний ключ у ланцюгу: обірваний потік продовжується, 5xx чи мовчання — одна легша модель, «лише на пристрої» — тиша, мертвий ключ — сервер. */
    private val cloudUnlessLocalOnly: local.phantom.companion.core.ai.route.OwnKeyGuard by lazy {
        val cloudRetrying = RetryingAIRouter(
            delegate = local.phantom.companion.core.resilience.ResumableCloud(geminiRoute),
            fallback = local.phantom.companion.core.resilience.ResumableCloud(geminiLite),
        )
        local.phantom.companion.core.ai.route.OwnKeyGuard(
            delegate = object : AIRouter {
                override fun complete(intent: AIIntent) = cloudRetrying.complete(intent)
                override suspend fun availableRoutes() =
                    if (brainPrefs.routing.value == RoutingStrategy.LocalOnly) emptySet()
                    else cloudRetrying.availableRoutes()
            },
            key = { aiKeyStore.geminiKey.value },
        )
    }

    /** ПК і власний ключ, без сервера Nightpost: фонова робота не має права на денну квоту людини. */
    private val ownBrains: AIRouter by lazy {
        local.phantom.companion.core.ai.route.BrainChain(listOf(pcBrainRoute, cloudUnlessLocalOnly))
    }

    val aiRouter: AIRouter by lazy {
        runCatching {
            // Розмова власника (`grounded`) спирається на памʼять: без опори — «не знаю»
            // без моделі, з опорою — блок памʼяті з мітками [m:id] і рядок «З памʼяті: …».
            local.phantom.companion.core.ai.route.BrainChain(
                listOf(pcBrainRoute, cloudUnlessLocalOnly, nightpostBrain),
                memory = memoryRuntime.ground { phantomSelf.memory },
            )
        }.getOrElse { t ->
            Log.e("AppContainer", "aiRouter init failed — running in no-AI mode", t)
            errorBus.emit(
                source = "AIRouter",
                severity = local.phantom.companion.core.data.errors.PhantomError.Severity.Critical,
                // A key fixes nothing here: this branch is the router's own
                // construction throwing, and the fallback is null for the rest
                // of the process. Say what is true and what actually helps.
                message = applicationContext.getString(local.phantom.companion.R.string.ai_router_boot_failed),
                cause = t,
            )
            nullRouter()
        }
    }

    /**
     * Connectivity sensor. Owned by the container (application lifetime)
     * because [aiBootstrapper] re-arms its wake on every reconnect — a probe
     * bound to a screen would stop listening the moment the operator left it.
     */
    val networkProbe: local.phantom.companion.core.resilience.NetworkProbe by lazy {
        local.phantom.companion.core.resilience.NetworkProbe(
            connectivityManager = applicationContext.getSystemService(
                android.net.ConnectivityManager::class.java,
            ),
            scope = scope,
        )
    }

    val aiBootstrapper: local.phantom.companion.core.ai.AiBootstrapper by lazy {
        local.phantom.companion.core.ai.AiBootstrapper(
            router = aiRouter,
            scope = scope,
        )
    }

    /**
     * Zero-click hearing. The twin of [aiBootstrapper]: PHANTOM fetches his own
     * STT model the first time voice is actually used, so the operator is never
     * sent to Settings to «завантажити» before the app can hear him. Reuses the
     * same [modelManager] transfer machinery; armed lazily via VoiceManager's
     * onCaptureStart hook, never at cold start.
     */
    val hearingProvisioner: local.phantom.companion.core.ai.HearingProvisioner by lazy {
        local.phantom.companion.core.ai.HearingProvisioner(
            models = modelManager,
            scope = scope,
            hearingModel = local.phantom.companion.core.ai.hearingModelForRam(
                local.phantom.companion.ui.settings.deviceTotalRamBytes(applicationContext),
                local.phantom.companion.core.voice.VoskRuntime.shipped,
            ),
            isOnline = { networkProbe.online.value },
            isMetered = {
                runCatching {
                    val cm = applicationContext.getSystemService(
                        android.net.ConnectivityManager::class.java,
                    )
                    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                    caps?.hasCapability(
                        android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
                    )?.not() ?: true
                }.getOrDefault(true)
            },
        )
    }

    /** The voice self-provisions, like the brain and the ear. */
    val voiceProvisioner: local.phantom.companion.core.ai.HearingProvisioner by lazy {
        local.phantom.companion.core.ai.HearingProvisioner(
            models = modelManager,
            scope = scope,
            isOnline = { networkProbe.online.value },
            isMetered = {
                runCatching {
                    val cm = applicationContext.getSystemService(
                        android.net.ConnectivityManager::class.java,
                    )
                    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                    caps?.hasCapability(
                        android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
                    )?.not() ?: true
                }.getOrDefault(true)
            },
            hearingModel = liveVoiceModel,
        )
    }

    val liveVoiceModel: local.phantom.companion.core.ai.ModelType by lazy {
        val total = runCatching {
            val info = android.app.ActivityManager.MemoryInfo()
            applicationContext.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(info)
            info.totalMem
        }.getOrDefault(0L)
        liveVoiceModelFor(total) {
            local.phantom.companion.core.voice.supertonic.SupertonicAssets.allPresent(modelManager.getModelFile(it))
        }
    }

    // Phase 2-B — TTS layer: RoutedTts tries Supertonic 3 (streaming ONNX,
    // fetched by [voiceProvisioner] the moment the operator turns the voice
    // on) then falls back to AndroidSystemTts (uk-UA, ready immediately).
    // Wrapped in [runCatching] so a TTS framework failure does not crash cold launch.
    val ttsEngine: TtsEngine by lazy {
        runCatching {
            // Лямбда, а не знімок: [voicePrefs] нижче в цьому ж класі і теж
            // lazy, а голос мусить брати щойно зрушений повзунок уже з
            // наступної фрази — прочитане тут значення протрималось би до
            // перезапуску застосунку.
            //
            // Подається `prosody`, а НЕ пара pitch/rate: доки оператор не
            // торкався повзунка, воно `null`, і рушій не кличе setPitch/
            // setSpeechRate узагалі. Так лишається чинним темп, який людина
            // виставила в системних налаштуваннях доступності: «ми не маємо
            // думки» тут мусить лишатись мовчанням, а не нейтральною 1.0.
            val systemTts = AndroidSystemTts(
                applicationContext,
                tuning = { voicePrefs.prosody.value },
            )
            val supertonicDir = modelManager.getModelFile(liveVoiceModel)
            modelManager.recheck(liveVoiceModel)
            val supertonic = local.phantom.companion.core.voice.route.SupertonicRoute(
                synth = local.phantom.companion.core.voice.supertonic.OrtSupertonicSynth(
                    modelDir = supertonicDir,
                ),
                player = local.phantom.companion.core.voice.audio.PcmStreamPlayer { isNavigation ->
                    local.phantom.companion.core.voice.audio.AudioTrackPcmSink(isNavigation)
                },
            )
            RoutedTts(
                premium = supertonic,
                systemFallback = systemTts,
                baked = local.phantom.companion.core.voice.route.BakedNavVoice(
                    dir = File(
                        applicationContext.filesDir,
                        "models/${local.phantom.companion.core.voice.route.BakedNavVoice.DIR}",
                    ),
                    assets = applicationContext.assets,
                ),
            )
        }.getOrElse { t ->
            Log.e("AppContainer", "ttsEngine init failed — TTS unavailable", t)
            errorBus.emit(
                source = "TtsEngine",
                severity = local.phantom.companion.core.data.errors.PhantomError.Severity.Error,
                message = applicationContext.getString(local.phantom.companion.R.string.tts_engine_boot_failed),
                cause = t,
            )
            nullTts()
        }
    }

    // Phase 2-B-1 — voice preferences (TTS on/off toggle, Keystore-backed).
    val voicePrefs: VoicePreferences by lazy { VoicePreferences(applicationContext) }

    /** Чий мозок відповідає першим — збережений вибір людини. */
    val brainPrefs: BrainPreferences by lazy { BrainPreferences(applicationContext) }

    val symbiotePrefs: SymbiotePreferences by lazy { SymbiotePreferences(applicationContext) }

    // MAP-1 — tile provider + API key + custom-URL store. Keystore-backed
    // (mirror of VoicePreferences) so MapTiler / Stadia keys live in the
    // same encrypted preferences pile as the Gemini key. Default
    // OpenFreeMap so first-run map works without any operator setup.
    val mapPrefs: MapPreferences by lazy { MapPreferences(applicationContext) }

    /**
     * ATLAS-3 — паки країн: те, що приїхало на диск, плюс памʼять про країну.
     *
     * Викликається ПЕРШИМ на прогрівальній нитці: усе, що вона грітиме далі
     * (полігони областей, індекс регіонів, витяг POI), читається з активного
     * пака — без цього виклику вона грілася б із порожнечі. Вбудована Україна
     * стоїть у реєстрі від конструювання, тож вікна «жодного пака» не буває.
     */
    @Synchronized
    fun installCountryPacks() {
        if (countryPacksInstalled) return
        countryPacksInstalled = true
        val registry = local.phantom.companion.core.data.country.CountryPackRegistry
        runCatching { registry.install(countryPackStore.packs()) }
            .onFailure { t -> Log.w("AppContainer", "country pack store unreadable", t) }
        // Порядок: install() скидає активний пак, restore() його повертає.
        registry.restore(mapPrefs.rememberedCountry.value, mapPrefs.countryOverride.value)
        registry.setRole(mapPrefs.operatorRole.value)
        registry.rememberVia { id -> mapPrefs.rememberCountry(id) }
    }

    /**
     * Пак щойно приїхав дротом — підхопити його БЕЗ перезапуску.
     *
     * Та сама пара `install` + `restore`, що й на старті, і в тому ж порядку:
     * `install()` скидає активний пак і ручний вибір оператора, `restore()`
     * повертає обидва з налаштувань. Викликати тут самий `install()` означало
     * б, що завантаження Польщі мовчки знімає вибір країни, зроблений рукою.
     *
     * Засів іде ОДРАЗУ за реєстром, бо пак без засіву — це мапа, яка малює
     * піни й не вміє їх знайти: рівно та вада, з якої почалась хвиля
     * «знайдене, але не намальоване», лише дзеркально.
     */
    fun countryPackArrived(pack: local.phantom.companion.core.data.country.InstalledPack) {
        val registry = local.phantom.companion.core.data.country.CountryPackRegistry
        runCatching { registry.install(countryPackStore.packs()) }
            .onFailure { t -> Log.w("AppContainer", "country pack store unreadable", t) }
        registry.restore(mapPrefs.rememberedCountry.value, mapPrefs.countryOverride.value)
        Log.i("AppContainer", "country pack ${pack.id} v${pack.version} installed")
        seedPlaceGazetteerIfNeeded()
        seedStreetGazetteerIfNeeded()
        seedSettlementGazetteerIfNeeded()
    }

    private var countryPacksInstalled = false

    /**
     * Порожній `PACK_PUBLIC_KEY` означає «не приймається жоден завантажений
     * пак» — та сама постава, що й у ліцензійних воротах, а не «приймаються
     * всі». Збірка без якоря довіри живе на вбудованій Україні.
     */
    val countryPackStore: local.phantom.companion.core.data.country.CountryPackStore by lazy {
        local.phantom.companion.core.data.country.CountryPackStore(
            root = java.io.File(applicationContext.filesDir, "country"),
            publicKeyHex = local.phantom.companion.BuildConfig.PACK_PUBLIC_KEY,
            appBuild = local.phantom.companion.BuildConfig.PACK_SCHEMA_BUILD,
            audit = local.phantom.companion.core.geo.license.PackLicenseAudit::violations,
        )
    }

    /**
     * ATLAS-3 — клієнт завантаження пакiв країн.
     *
     * База читається ЖИВОЮ лямбдою, а не знімком: порожній
     * `PACKS_BASE_URL` (типовий стан збірки) має лишатись чесним
     * `PackDownload.NotConfigured`, а не мовчазним походом кудись.
     *
     * Ворота знають про [countryPackArrived]: пак, який щойно доїхав, стає
     * активним і засіяним без перезапуску — і памʼять про країну при цьому
     * вціліла, бо `restore()` йде одразу за `install()`.
     */
    val packFetcher: local.phantom.companion.core.net.pack.PackFetcher by lazy {
        local.phantom.companion.core.net.pack.PackFetcher(
            baseUrl = { local.phantom.companion.BuildConfig.PACKS_BASE_URL },
            gate = local.phantom.companion.core.data.country.CountryPackGate(
                store = countryPackStore,
                onInstalled = ::countryPackArrived,
            ),
        )
    }

    // H-02 — the last satellite check of the device clock, kept across restarts
    // so a phone that came back up with a wrong clock and no sky cannot present
    // the light countdown's clean face. See [ClockSkewPreferences].
    val clockSkewStore: local.phantom.companion.core.geo.sky.ClockSkewStore by lazy {
        ClockSkewPreferences(applicationContext)
    }

    // GEO-LAW (2026-07-20, licensing sweep, brief 05 B5) — install the
    // online geo-service config ONCE, at boot, before any tool or
    // client gets a chance to resolve a routing/geocoding base URL. A
    // debug build with nothing stored in Settings → Map → Геосервіси
    // falls back to the community demo servers (MapPreferences.
    // geoEndpointsConfig() gates that on BuildConfig.DEBUG); a release
    // build with nothing stored stays DISABLED — honest failure, never a
    // silent hardwired public server.
    internal fun installGeoEndpoints() {
        GeoEndpoints.config = mapPrefs.geoEndpointsConfig()
    }

    /**
     * Wave 05.09 — install where the cloud basemap/road-mesh catalog lives,
     * same doctrine as [installGeoEndpoints]. Deliberately the SAME
     * `PACKS_BASE_URL` [packFetcher] above already reads for country packs
     * (bucket `phantom-maps` on R2) rather than a second address: one more
     * config knob is one more way to leave a build pointed nowhere by
     * accident. Empty stays "not configured", honestly, until a build sets
     * `phantom.packsBaseUrl`.
     */
    internal fun installWorldPackLink() {
        local.phantom.companion.core.net.pack.WorldPackLink.baseUrl = {
            local.phantom.companion.BuildConfig.PACKS_BASE_URL
        }
        // Той самий якір, що й у пакiв країн вище: порожній = «не приймається
        // жоден завантажений пакет», а не «приймаються всі».
        local.phantom.companion.core.net.pack.WorldPackLink.publicKeyHex = {
            local.phantom.companion.BuildConfig.PACK_PUBLIC_KEY
        }
        // Проти minAppBuild світового пака — той самий номер, що appBuild у
        // CountryPackStore вище: пак, перепечений під новішу схему, не має
        // ставати на збірку, яка його не читає.
        //
        // САМЕ PACK_SCHEMA_BUILD, а не VERSION_CODE: другий у debug навмисно
        // лишається 1, і цей заслін відкидав там кожен пак із каталогу.
        local.phantom.companion.core.net.pack.WorldPackLink.appBuild = {
            local.phantom.companion.BuildConfig.PACK_SCHEMA_BUILD
        }
        // Пакети країн (рід COUNTRY_PLACES у каталозі кузні) ставляться ТИМ
        // САМИМ фетчером, що й досі: один писар на каталог `files/country`.
        local.phantom.companion.core.net.pack.CountryPackLink.fetcher = { packFetcher }
    }

    /**
     * Phase 2 (map-plan) — install the road-pack link session once, at boot,
     * the same doctrine as [installGeoEndpoints] right above: `:core-net`
     * cannot see `PairedDeviceStore`/`PhantomApi`/`PhantomLink` (those live
     * in `:app`), so the container hands `:core-net` live lambdas over its
     * own already-constructed [store]/[api]/[link] rather than snapshots —
     * a re-pair, a LAN-address change, or a WS reconnect is picked up on the
     * next call with no second install site to remember.
     */
    internal fun installRoadPackLink() {
        local.phantom.companion.core.net.roadpack.RoadPackLink.session =
            local.phantom.companion.core.net.roadpack.RoadPackSession(
                client = local.phantom.companion.core.net.roadpack.RoadPackLinkClient(
                    pinnedCertSha256 = { store.load()?.serverCertSha256 ?: "" },
                ),
                baseUrl = { api.address.current() ?: store.load()?.lanUrl },
                deviceJwt = { store.load()?.deviceJwt },
                connected = { link.connected.value == local.phantom.companion.net.LinkStatus.Connected },
            )
    }

    /**
     * Map-symbiosis — install the PC national-layer link session, same
     * doctrine as [installRoadPackLink] right above (`:core-net` cannot see
     * `PairedDeviceStore`/`PhantomApi`/`PhantomLink`, so the container hands
     * over live lambdas rather than snapshots).
     */
    internal fun installPcLayerLink() {
        local.phantom.companion.core.net.maplayer.PcLayerLink.session =
            local.phantom.companion.core.net.maplayer.PcLayerSession(
                client = local.phantom.companion.core.net.maplayer.PcLayerCatalogClient(
                    pinnedCertSha256 = { store.load()?.serverCertSha256 ?: "" },
                ),
                baseUrl = { api.address.current() ?: store.load()?.lanUrl },
                deviceJwt = { store.load()?.deviceJwt },
                connected = { link.connected.value == local.phantom.companion.net.LinkStatus.Connected },
            )
    }

    val overpassClient: local.phantom.companion.core.net.map.OverpassClient by lazy {
        local.phantom.companion.core.net.map.OverpassClient()
    }

    /**
     * IRONCLAD-2 — the permanent road mesh: every tile ever fetched, kept
     * forever, bulk-import seam for country-scale packs. The routing world
     * stops evaporating with the process.
     */
    val roadMeshStore: local.phantom.companion.core.sensor.nav.RoadMeshStore by lazy {
        local.phantom.companion.core.sensor.nav.RoadMeshStore(applicationContext)
    }

    /**
     * ATLAS-2 — one road tile for the map-matcher, as the pure [RoadWay]
     * shape core-sensor's engine understands. Null = honest fetch failure
     * (RoadTruth keeps whatever tiles it already holds).
     *
     * IRONCLAD-2 — Overpass no longer stands alone: a successful fetch
     * thickens the permanent mesh as a by-product (same doctrine as the POI
     * gazetteer), and a failed one — the blackout, the rate limit, the dead
     * network under an air raid — is answered by everything this device has
     * ever earned. Asphalt does not move; a stale road beats no road.
     */
    /**
     * Транспорт із дорожнього пакета навколо прямокутника — зупинки й колії.
     *
     * ОФЛАЙН І ЛИШЕ ОФЛАЙН, на відміну від [fetchRoadWays], який спершу
     * пробує Overpass. Мережі тут немає в принципі: під РЕБ саме це і є
     * єдина причина, чому шар існує. Порожній результат означає «пакет цього
     * не несе» (усе, що спечено до v3), а не «тут транспорту немає».
     */
    suspend fun fetchPackTransit(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
    ): local.phantom.companion.feature.stream.ui.map.PackTransitSlice =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val localStops = runCatching {
                roadMeshStore.stopsIn(minLat, minLon, maxLat, maxLon)
            }.getOrDefault(emptyList())

            val localRails = runCatching {
                roadMeshStore.railsIn(minLat, minLon, maxLat, maxLon)
            }.getOrDefault(emptyList())

            val stops = if (localStops.isNotEmpty()) {
                localStops
            } else {
                runCatching {
                    overpassClient.fetchPois(minLat, minLon, maxLat, maxLon)
                        .filter { el ->
                            val t = el.tags ?: return@filter false
                            t["highway"] == "bus_stop" ||
                                t["highway"] == "platform" ||
                                t["public_transport"] in setOf("platform", "stop_position", "station") ||
                                t["railway"] in setOf("tram_stop", "station", "halt") ||
                                t["amenity"] == "bus_station"
                        }
                        .mapNotNull { el ->
                            val lat = el.effectiveLat ?: return@mapNotNull null
                            val lon = el.effectiveLon ?: return@mapNotNull null
                            val tags = el.tags ?: emptyMap()
                            val name = local.phantom.companion.core.data.poi.poiDisplayName(tags)
                            val routes = tags["route_ref"] ?: tags["ref"] ?: ""
                            local.phantom.companion.core.sensor.nav.TransitStop(
                                id = el.id,
                                lat = lat,
                                lon = lon,
                                name = name,
                                tags = tags,
                                routes = routes,
                            )
                        }
                }.getOrDefault(emptyList())
            }

            local.phantom.companion.feature.stream.ui.map.PackTransitSlice(
                stops = stops,
                rails = localRails,
            )
        }

    suspend fun fetchRoadWays(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
    ): List<local.phantom.companion.core.sensor.nav.RoadWay>? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val elements = overpassClient.fetchRoads(minLat, minLon, maxLat, maxLon)
            if (elements.isEmpty()) {
                val stored = runCatching {
                    roadMeshStore.waysIn(minLat, minLon, maxLat, maxLon)
                }.getOrDefault(emptyList())
                return@withContext stored.ifEmpty { null }
            }
            val ways = elements.mapNotNull { el ->
                val pts = el.geometry?.map { it.lat to it.lon } ?: return@mapNotNull null
                if (pts.size < 2) return@mapNotNull null
                local.phantom.companion.core.sensor.nav.RoadWay(
                    id = el.id,
                    points = pts,
                    name = el.tags?.get("name"),
                    oneway = el.tags?.get("oneway") == "yes" || el.tags?.get("oneway") == "1",
                    // Overpass has been sending these all along; dropping them
                    // here is what left the offline router unable to tell a
                    // courtyard driveway from a street.
                    tags = el.tags.orEmpty(),
                )
            }
            // Never let persistence failure cost the live fetch its result.
            runCatching { roadMeshStore.put(ways) }
            ways
        }

    /**
     * ATLAS-2b — the PRIMARY road-snap: the recent fix trace, map-matched onto
     * the local OSRM network. This runs where Overpass cannot — the owner's
     * field report is that Overpass is unreachable exactly while driving, yet
     * the self-hosted OSRM holds the whole road graph and answers in
     * milliseconds. Null = honest failure (a hung/absent stack, a trace OSRM
     * cannot match): [RoadTruth] then falls back to its tile matcher, and
     * failing that leaves the dot on the raw fused fix.
     */
    suspend fun snapToRoad(
        trace: List<local.phantom.companion.core.ai.context.LocationFix>,
    ): local.phantom.companion.feature.stream.ui.map.nav.RoadSnapFix? {
        if (trace.size < 2) return null
        val points = trace.map { fix ->
            // 25 m stands in for an unknown radius — OSRM coerces it into its
            // own [8, 50] m search band anyway.
            Triple(fix.lat, fix.lon, fix.accuracyM ?: 25f)
        }
        val hit = runCatching {
            osrmClient.match(local.phantom.companion.core.net.map.RouteProfile.CAR, points)
        }.getOrNull() ?: return null
        return local.phantom.companion.feature.stream.ui.map.nav.RoadSnapFix(
            lat = hit.lat,
            lon = hit.lon,
            wayName = hit.wayName,
            confidence = hit.confidence,
        )
    }

    /** LEVIATHAN — the on-device A* router over the permanent road mesh, the
     *  offline half of [routedVariants]. Stateless; one instance is plenty. */
    private val localRouter = local.phantom.companion.core.sensor.nav.LocalRouter()

    /** Коридор, уже піднятий у памʼять: перебудова не платить за нього вдруге. */
    private val corridorGraphs = local.phantom.companion.core.sensor.nav.CorridorGraphCache()

    /**
     * HORIZON II.1 — памʼять про те, ДЕ НЕБО ВОРОЖЕ, на весь процес.
     *
     * Досі ця мапа народжувалась усередині [MapViewModel] — тобто вмирала
     * разом з екраном мапи, і читав її рівно один споживач: евакуаційний
     * маршрут у [SpatialRoutingService]. Виходило, що застосунок УЖЕ вміє
     * обходити тінь глушилки, але лише коли людина тікає в укриття; звичайне
     * «проведи мене туди» через [offlineMeshRoute] про неї не знало.
     *
     * Тепер екземпляр один і живе тут: [MapViewModel] годує його вироками
     * довіри з кожного фікса (див. `gnssIntegrity.observe`), а обидва
     * маршрутизатори — евакуаційний і головний — читають ТОЙ САМИЙ прошарок.
     *
     * Чесно про межу: мапа тримається лише в памʼяті процесу. [GnssIntegrityMap]
     * уміє `snapshot()`/`restore()`, але сховища під них у дереві немає —
     * вивчене за поїздку зникає з процесом. Це названа прогалина, не обіцянка.
     */
    val gnssIntegrity = local.phantom.companion.core.sensor.nav.GnssIntegrityMap()

    /**
     * УКРИТТЯ — остання сітка забудови, яку вже привезли з Overpass, разом зі
     * своїм прямокутником. Пишеться в [fetchCoverGrid], читається
     * [coverForCorridor] і БІЛЬШЕ НІЯК: головний маршрут ніколи не ходить по
     * неї в мережу сам.
     */
    @Volatile
    private var cachedCoverGrid:
        Pair<DoubleArray, local.phantom.companion.core.sensor.nav.CoverGrid>? = null

    /**
     * Чому [routedVariants] щойно відмовив — записується на кожному з чотирьох
     * місць відмови всередині [offlineMeshRoute] і мережевого плеча, а
     * [RoutePlanner] читає це рівно один раз одразу після відмови. Смуга
     * відмови на мапі досі казала одне й те саме «маршрутизатор недоступний»
     * і для «немає доріг на пристрої», і для «сервер не налаштований» —
     * різні світи під одним реченням.
     */
    @Volatile
    private var lastRouteRefusal: local.phantom.companion.core.nav.RoadRefusal? = null

    /**
     * FIELD ROUTING — джерело маршруту головного навігатора: **граф на
     * пристрої перший**, мережа — лише коли свій граф каже «не знаю». Порядок
     * тримає [routeGraphFirst], і саме там стоїть сторож проти перевороту.
     *
     * Було навпаки, і це коштувало водієві до 12 секунд очікування на кожній
     * перебудові: мережа перша, готовий рушій у кишені — другий. The stack lives on the laptop LAN, so the moment the phone leaves
     * that Wi-Fi — the whole point of a field companion — [OsrmClient.routeDetailed]
     * returns null and every `nav.start` / map «маршрут» would otherwise fail. The
     * mesh is every tile the app has ever fetched: wherever the operator has been
     * online once he can still route offline, the same doctrine the evacuation
     * engine already runs on, now on the primary path. Wired into [navEngine]'s
     * [RoutePlanner]; the map reads that same planner, so both heads inherit it.
     */
    private suspend fun routedVariants(
        profile: local.phantom.companion.core.net.map.RouteProfile,
        waypoints: List<Pair<Double, Double>>,
    ): List<local.phantom.companion.core.net.map.RouteVariant>? =
        run {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            var source = "NONE"
            // Скидається на КОЖНУ спробу: причина належить ЦЬОМУ виклику,
            // інакше стара відмова пережила б успіх сусіднього профілю.
            lastRouteRefusal = null
            val variants = local.phantom.companion.core.nav.routeGraphFirst(
                onDevice = {
                    // Розрізняємо «граф підняли з бази» і «граф уже був у
                    // памʼяті»: без цього мілісекунди в записі не мають
                    // механізму, і 40 мс від 400 не відрізнити причиною.
                    val hitsBefore = corridorGraphs.hits
                    offlineMeshRoute(profile, waypoints)?.also {
                        source = if (corridorGraphs.hits > hitsBefore) "GRAPH_CACHED" else "GRAPH"
                    }
                },
                network = {
                    osrmClient.routeDetailed(profile, waypoints)?.also { source = "NETWORK" }
                        ?: run {
                            // Мережа щось відповіла, а не мовчала: сервер
                            // налаштований, і саме тому «не налаштовано» тут
                            // було б брехнею — граф на пристрої вже сказав
                            // своє слово, це останнє.
                            if (local.phantom.companion.core.net.map.GeoEndpoints.config.routingTemplate != null) {
                                lastRouteRefusal = local.phantom.companion.core.nav.RoadRefusal.NETWORK_NO_ROUTE
                            }
                            null
                        }
                },
            )
            // Відмовили обидва плеча — тоді й тільки тоді має значення, що
            // мережевого плеча взагалі немає. Порядок навмисний: поки граф
            // назвав OFF_ROAD чи SEARCH_CEILING, сервер тут ні до чого.
            if (variants.isNullOrEmpty()) {
                lastRouteRefusal = local.phantom.companion.core.nav.refusalWithoutRouter(
                    lastRouteRefusal,
                    local.phantom.companion.core.net.map.GeoEndpoints.config.routingTemplate != null,
                )
            }
            // Без цього рядка «швидко» лишається відчуттям. Пишемо КОЖНУ
            // відповідь, включно з відмовою обох джерел.
            blackBox.recordRoute(
                source = source,
                ms = android.os.SystemClock.elapsedRealtime() - startedAt,
                distanceM = variants?.firstOrNull()?.distanceMeters,
                points = variants?.firstOrNull()?.points?.size ?: 0,
                basis = variants?.firstOrNull()?.speedBasis.orEmpty(),
            )
            variants
        }

    /**
     * The offline leg of [routedVariants]: stitch an on-device route over the
     * cached mesh, one A* per waypoint pair. A corridor the mesh does not hold
     * fails honestly (null) — never a straight line across unknown ground (Law 3).
     * Bounded to field scale: the mesh is a neighbourhood cache, not a country
     * extract, so a cross-country bbox is refused rather than loaded whole.
     */
    internal suspend fun offlineMeshRoute(
        profile: local.phantom.companion.core.net.map.RouteProfile,
        waypoints: List<Pair<Double, Double>>,
    ): List<local.phantom.companion.core.net.map.RouteVariant>? {
        if (waypoints.size < 2 ||
            profile == local.phantom.companion.core.net.map.RouteProfile.TRANSIT
        ) return null
        // ТРИ ПРОФІЛІ, А НЕ ДВА. Тут стояло `profile == FOOT`, тобто велосипед
        // мовчки їхав автомобільними правилами: обʼїжджав велодоріжки (для
        // авто це заборонений клас, 500×), не читав `bicycle=no` і рахував час
        // автомобільним темпом, оголошуючи його «за знаками». `when` над
        // переліком навмисно: новий профіль не проскочить у гілку «інакше».
        val mode = when (profile) {
            local.phantom.companion.core.net.map.RouteProfile.FOOT ->
                local.phantom.companion.core.sensor.nav.RoadProfile.TravelMode.FOOT
            local.phantom.companion.core.net.map.RouteProfile.BIKE ->
                local.phantom.companion.core.sensor.nav.RoadProfile.TravelMode.BIKE
            local.phantom.companion.core.net.map.RouteProfile.CAR,
            // TRANSIT відсіяно вище — сюди не доходить.
            local.phantom.companion.core.net.map.RouteProfile.TRANSIT ->
                local.phantom.companion.core.sensor.nav.RoadProfile.TravelMode.CAR
        }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val minLat = waypoints.minOf { it.first }
            val maxLat = waypoints.maxOf { it.first }
            val minLon = waypoints.minOf { it.second }
            val maxLon = waypoints.maxOf { it.second }
            // ~0.75° ≈ 80 km: past this the neighbourhood mesh cannot honestly
            // hold the corridor, so refuse rather than load the whole store.
            if (maxLat - minLat > 0.75 || maxLon - minLon > 0.75) {
                lastRouteRefusal = local.phantom.companion.core.nav.RoadRefusal.TOO_LONG
                return@withContext null
            }
            val pad = 0.02 // ~2 km skirt so the graph holds the whole corridor
            // Перебудова при сході з маршруту питає майже той самий коридор, а
            // платила за нього повну ціну: заміряно 77 мс підготовки (розбір
            // рядків + побудова графа) проти 24 мс самого пошуку, плюс запит до
            // SQLite. Кеш звіряє вміщення прямокутника і ревізію мережі, тож
            // свіжі тайли його скасовують — див. [CorridorGraphCache].
            val graph = corridorGraphs.graphFor(
                minLat = minLat - pad,
                minLon = minLon - pad,
                maxLat = maxLat + pad,
                maxLon = maxLon + pad,
                revision = roadMeshStore.revision,
            ) {
                // Мовчазне ковтання тут коштувало б найдорожче: людина побачила
                // б «маршруту немає» і в разі порожньої мережі, і в разі
                // ЗЛАМАНОЇ бази — два різні світи під одним мовчанням. Тому
                // причина йде в чорну скриню словом, як і решта відмов.
                runCatching {
                    // Дві стадії міряються ОКРЕМО, бо лікуються протилежним:
                    // повільне читання — це SQL і розбір рядків, повільна
                    // побудова — це індекси в памʼяті. Одне число на обидві
                    // сказало б лише «довго», не сказавши де.
                    val readAt = android.os.SystemClock.elapsedRealtime()
                    val ways = roadMeshStore.waysIn(
                        minLat - pad, minLon - pad, maxLat + pad, maxLon + pad,
                    )
                    val readMs = android.os.SystemClock.elapsedRealtime() - readAt
                    val buildAt = android.os.SystemClock.elapsedRealtime()
                    val g = local.phantom.companion.core.sensor.nav.RoadGraph(ways)
                    blackBox.recordPrep(
                        ways = ways.size,
                        readMs = readMs,
                        buildMs = android.os.SystemClock.elapsedRealtime() - buildAt,
                    )
                    g
                }.onFailure { e ->
                    blackBox.note(
                        "route",
                        "граф не зібрався: ${e::class.simpleName} ${e.message ?: "без пояснення"}",
                    )
                    lastRouteRefusal = local.phantom.companion.core.nav.RoadRefusal.GRAPH_UNREADABLE
                }.getOrNull()?.takeIf { it.wayCount > 0 }
                    ?: run {
                        // `onFailure` above already named a broken DB; only a
                        // clean read that came back empty is "no data here".
                        if (lastRouteRefusal != local.phantom.companion.core.nav.RoadRefusal.GRAPH_UNREADABLE) {
                            blackBox.note("route", "мережа в цьому коридорі порожня")
                            lastRouteRefusal = local.phantom.companion.core.nav.RoadRefusal.NO_DATA
                        }
                        null
                    }
            } ?: return@withContext null
            // ДИНАМІЧНІ ПОПРАВКИ — те, чого не можна запекти в жоден серверний
            // профіль, і саме тому цей роутер лишається на пристрої.
            //
            // Обидві вже існували, обидві вже працювали — і жодна не вмикалась
            // тут. `SpatialRoutingService.routeTo` подавав їх у той самий
            // [LocalRouter] і той самий [RoadGraph], тож захист від глушилки й
            // від відкритої землі був у застосунку рівно доти, доки людина
            // тікала в укриття. Звичайна поїздка їхала найкоротшим.
            //
            // Ціна нуль: обидві поправки — це читання однієї клітини з мапи в
            // памʼяті, без мережі й без SQL, і множник ≥ 1 лише КЕРМУЄ —
            // маршрут через них не зникає (див. [RouteCaution]).
            val caution = local.phantom.companion.core.sensor.nav.RouteCaution.EVERYDAY
            // За ПОВНИМ прямокутником графа, а не за точками: А* вільний вести
            // будь-якою дорогою, що потрапила в граф, тож накривати треба саме
            // той прямокутник, з якого дороги читались.
            val cover = coverForCorridor(minLat - pad, minLon - pad, maxLat + pad, maxLon + pad)
            val openGroundCost: ((Double, Double) -> Double)? = cover?.let { g ->
                { lat, lon -> g.costFactorAt(lat, lon, caution.coverAvoidance) }
            }
            val pts = mutableListOf<Pair<Double, Double>>()
            var dist = 0.0
            var dur = 0.0
            var postedMeters = 0.0
            var shoulderMeters = 0.0
            var fordCrossings = 0
            // Weakest leg decides: one guessed stretch makes the whole estimate
            // a guess, the same strictness LocalRouter applies within a leg.
            var basis = local.phantom.companion.core.sensor.nav.RoadProfile.SpeedBasis.POSTED
            val allRoadNames = mutableListOf<String?>()
            for ((a, b) in waypoints.zipWithNext()) {
                // ЧОМУ саме — від маршрутизатора, а не здогадом тут. Досі цей
                // блок оголошував [RoadRefusal.NO_DATA] на БУДЬ-ЯКУ відмову,
                // тобто «доріг цієї ділянки на пристрої немає — потрібен
                // дорожній пакет» казалось і тому, хто стоїть серед поля за
                // 200 м від дороги, і тому, чий пошук просто впрів у стелю з
                // уже встановленим пакетом.
                var why: LegRefusal? = null
                val leg = localRouter.route(
                    graph, a.first, a.second, b.first, b.second, mode,
                    integrity = gnssIntegrity,
                    jammingAvoidance = caution.jammingAvoidance,
                    extraCostFactor = openGroundCost,
                    onRefusal = { why = it },
                ) ?: run {
                    lastRouteRefusal = when (why) {
                        LegRefusal.EXHAUSTED ->
                            local.phantom.companion.core.nav.RoadRefusal.SEARCH_CEILING
                        LegRefusal.SNAP_FROM, LegRefusal.SNAP_TO ->
                            local.phantom.companion.core.nav.RoadRefusal.OFF_ROAD
                        // EMPTY_GRAPH / NO_PATH / DEGENERATE / null — дороги
                        // сюди справді не завантажені або звʼязку між точками
                        // в окрузі немає. Це і є той єдиний випадок, на який
                        // порада «потрібен дорожній пакет» відповідає правду.
                        else -> local.phantom.companion.core.nav.RoadRefusal.NO_DATA
                    }
                    blackBox.note("route", "маршрут відмовлено: ${why ?: "без причини"}")
                    return@withContext null
                }
                if (pts.isEmpty()) {
                    pts.addAll(leg.points)
                    allRoadNames.addAll(leg.roadNames)
                } else {
                    pts.addAll(leg.points.drop(1))
                    allRoadNames.addAll(leg.roadNames.drop(1))
                }
                dist += leg.distanceMeters
                dur += leg.durationSeconds
                postedMeters += leg.postedShare * leg.distanceMeters
                shoulderMeters += leg.trunkShoulderMeters
                // Ноги маршруту не перетинаються дорогами (кожна веде від точки
                // до точки), тож сума ніг — це й є число переправ на шляху.
                fordCrossings += leg.fordCrossings
                if (leg.speedBasis.ordinal > basis.ordinal) basis = leg.speedBasis
            }
            val synthesizedSteps = local.phantom.companion.core.nav.OfflineManeuverSynthesizer.synthesize(
                points = pts,
                roadNames = allRoadNames,
                profile = profile,
            )
            listOf(
                local.phantom.companion.core.net.map.RouteVariant(
                    points = pts,
                    distanceMeters = dist,
                    durationSeconds = dur,
                    steps = synthesizedSteps,
                    speedsMps = emptyList(),
                    profile = profile,
                    speedBasis = basis.name,
                    postedShare = if (dist > 0) postedMeters / dist else 0.0,
                    trunkShoulderMeters = shoulderMeters,
                    fordCrossings = fordCrossings,
                    origin = local.phantom.companion.core.net.map.RouteOrigin.OFFLINE_MESH,
                ),
            )
        }
    }

    /**
     * УКРИТТЯ — kinetic-cover grid for one bbox: real OSM building
     * footprints → [CoverGrid]. Null = no buildings / dead network (the
     * exposure index then stays honestly time-only).
     */
    suspend fun fetchCoverGrid(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
    ): local.phantom.companion.core.sensor.nav.CoverGrid? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val els = overpassClient.fetchBuildings(minLat, minLon, maxLat, maxLon)
            val footprints = els.mapNotNull { el ->
                val ring = el.geometry?.map { it.lat to it.lon } ?: return@mapNotNull null
                if (ring.size < 3) return@mapNotNull null
                val tags = el.tags
                val height = tags?.get("height")?.replace(',', '.')
                    ?.takeWhile { it.isDigit() || it == '.' }?.toFloatOrNull()
                    ?: tags?.get("building:levels")?.replace(',', '.')?.toFloatOrNull()?.times(3.2f)
                    ?: 8f
                local.phantom.companion.core.sensor.nav.CoverGrid.Companion.Footprint(ring, height)
            }
            if (footprints.isEmpty()) {
                null
            } else {
                local.phantom.companion.core.sensor.nav.CoverGrid.build(footprints).also { g ->
                    // Єдине місце, де сітка забудови взагалі зʼявляється в
                    // цьому процесі. Запамʼятовуємо разом із прямокутником,
                    // щоб головний маршрут міг нею скористатися, НЕ ходячи в
                    // мережу — див. [coverForCorridor].
                    cachedCoverGrid = doubleArrayOf(minLat, minLon, maxLat, maxLon) to g
                }
            }
        }

    /**
     * УКРИТТЯ на ЗВИЧАЙНОМУ маршруті — тільки з того, що вже привезли.
     *
     * Чому не «дофетчити, як робить евакуація»: головний маршрут навмисно
     * ходить [routeGraphFirst] — граф на пристрої першим, — і причина тому
     * заміряна: мережеве плече коштувало водієві до 12 секунд на КОЖНІЙ
     * перебудові. Повісити сюди синхронний запит в Overpass означало б
     * повернути рівно ту затримку, від якої цей порядок і рятує, і зробити
     * головний маршрут залежним від мережі — тобто зламати офлайн.
     * Забудови на пристрої немає в принципі: [RoadMeshStore] несе дороги,
     * зупинки й колії, а стіни живуть лише в Overpass.
     *
     * Тому сітка береться ЛИШЕ якщо вона вже в руках — і лише якщо її
     * прямокутник ПОВНІСТЮ накриває коридор. Часткове накриття довелося б
     * заборонити навіть якби хотілося: [CoverGrid.coverAt] за межами свого
     * прямокутника віддає 0, а 0 у цієї сітки означає «відкрите поле», не
     * «не знаю». Половина коридору тоді мовчки оголошувалась би голою землею
     * і маршрут кермував би від вигаданої небезпеки.
     */
    private fun coverForCorridor(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
    ): local.phantom.companion.core.sensor.nav.CoverGrid? {
        val (box, grid) = cachedCoverGrid ?: return null
        val holds = box[0] <= minLat && box[1] <= minLon && box[2] >= maxLat && box[3] >= maxLon
        return if (holds && grid.cellCount > 0) grid else null
    }

    /** ПРОВИДЕЦЬ — the corridor prefetch orchestrator (tiles + permanent mesh). */
    val corridorPrefetcher: local.phantom.companion.feature.stream.ui.map.prefetch.CorridorPrefetcher by lazy {
        local.phantom.companion.feature.stream.ui.map.prefetch.CorridorPrefetcher(
            scope = scope,
            fetchRoadWays = { s, w, n, e -> fetchRoadWays(s, w, n, e) },
            heldCells = { cells ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { roadMeshStore.heldCells(cells) }.getOrDefault(LongArray(0))
                }
            },
            offlineRegions = { offlineRegions },
            metered = {
                val cm = applicationContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                    as? android.net.ConnectivityManager
                val caps = cm?.let { it.getNetworkCapabilities(it.activeNetwork) }
                // Немає мережі або не знаємо її — вважаємо лімітованою.
                caps?.hasCapability(
                    android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
                )?.not() ?: true
            },
            styleUrl = {
                local.phantom.companion.feature.stream.ui.map.offlineStyleUrlFor(
                    mapPrefs.providerId.value, mapPrefs.apiKey.value, mapPrefs.customUrl.value,
                )
            },
        )
    }

    /** ПРОВИДЕЦЬ — corridor to [dest] (null = the active route), then launch. */
    internal suspend fun corridorLaunch(
        dest: Pair<Double, Double>?,
        label: String?,
        tiles: Boolean,
    ): String {
        if (dest == null) {
            val route = activeRoutePoints.value
                ?: return "Немає ні цілі, ні активного маршруту — дай place або lat/lon."
            return corridorPrefetcher.start(label ?: "активний маршрут", route, tiles)
        }
        val fix = locationProvider.current()
            ?.takeIf { it.isNavigationGrade && !it.dead }
            ?: return "Не знаю, де ти зараз (немає чесного фіксу) — коридор нема звідки будувати."
        val res = runCatching {
            osrmClient.route(
                local.phantom.companion.core.net.map.RouteProfile.CAR,
                fix.lat, fix.lon, dest.first, dest.second,
            )
        }.getOrNull()
        val polyline = res?.points ?: listOf(fix.lat to fix.lon, dest)
        val name = (label ?: "ціль") +
            if (res == null) " (по прямій — маршрут не збудувався)" else ""
        return corridorPrefetcher.start(name, polyline, tiles)
    }

    val poiRepository: local.phantom.companion.core.data.poi.PoiRepository by lazy {
        local.phantom.companion.core.data.poi.PoiRepository(
            poiDao = database.poiDao(),
            overpassClient = overpassClient,
            // HIGHWAY — the persistent corpus behind offline search. Fed as a
            // by-product of every Overpass fetch the app has ever made.
            gazetteer = database.poiGazetteerDao(),
            // The offline-first icon floor: when live Overpass is jammed away,
            // the map draws pins from this bundled OSM extract instead of blank.
            // A lambda, not the pack: touching `offlinePoiPack` here would
            // parse 4 MB of JSON on whatever thread assembled the graph, and
            // that thread is composition.
            //
            // Three separately baked packs, one merged floor: the general OSM
            // extract, the NSZU pharmacy/clinic registry and the invincibility
            // points all answer `getNearby`/`searchOffline` through the exact
            // same PoiRepository path — no second repository, no second query.
            offlinePack = { mergedOfflinePoiPack() },
            // Геокодер кузні — країнний пакет місць, вулиць і адрес. Лямбда, а
            // не читач: відкриття SQLite має статися на першому ЗАПИТІ, і не
            // на тому потоці, що збирає граф.
            forgeGeocoders = { forgeGeocoders() },
            homeCountry = ::countryAt,
        )
    }

    private val openedForgeGeocoders =
        java.util.concurrent.ConcurrentHashMap<String, local.phantom.companion.core.data.geocoder.ForgeGeocoder>()

    /**
     * УСІ поставлені пакети кузні, відкриті один раз і залишені відкритими.
     *
     * Було рівно ОДИН, і це не дрібниця обсягу: людина в Кошицях, у якої стоїть
     * український пакет, мовчки отримувала українські відповіді за сотні
     * кілометрів і мала всі підстави вирішити, що пошук зламано. Порожній
     * екран чесніший за це.
     */
    @Synchronized
    private fun forgeGeocoders(): List<local.phantom.companion.core.data.geocoder.ForgeGeocoder> {
        val paths = local.phantom.companion.core.data.geocoder.ForgeGeocoderStore(applicationContext)
            .installedPaths()
        for (gone in openedForgeGeocoders.keys - paths.toSet()) {
            openedForgeGeocoders.remove(gone)?.let { runCatching { it.close() } }
        }
        return paths.mapNotNull { path ->
            openedForgeGeocoders[path] ?: local.phantom.companion.core.data.geocoder.ForgeGeocoder
                .open(path)
                ?.also { openedForgeGeocoders[path] = it }
        }
    }

    /**
     * У якій країні людина стоїть — рамки країн із [PlaceGazetteer], без
     * жодного запиту до пакетів.
     *
     * Питати про це самі пакети коштує дорого й було зміряно: зворотний запит
     * «чи є тут щось моє» у пакеті чужої країни — 103–121 мс, бо звужує лише
     * широту. Рамка країни коштує нуль.
     *
     * ТОЧНІСТЬ ТУТ РАМКОВА, не полігональна, і цього досить: відповідь керує
     * лише тим, у ЧИЄМУ пакеті дозволено нечіткий прохід. Помилитись на сусіда
     * означає витратити зайві мілісекунди, а не показати чуже місце. Саме тому
     * тут НЕМАЄ полігонів областей, якими користується екран пошуку: там ціна
     * помилки — назвати людині у Львові «Польщу», тобто видима брехня.
     */
    private fun countryAt(lat: Double, lon: Double): String? {
        val gazetteer = local.phantom.companion.core.geo.place.PlaceGazetteer
        // Найменша площа й без «материкових» рамок. Перший-ліпший збіг давав у
        // Кошицях «Росію»: її материкова рамка через Калінінград починається з
        // ~19,6° сх. д. і накриває Словаччину.
        return gazetteer.countries(lat, lon)
            .filter { it.contains(lat, lon) && !gazetteer.isMainlandFramed(it) }
            .minByOrNull { it.landAreaKm2 ?: Double.MAX_VALUE }
            ?.id
            ?.removePrefix(gazetteer.WORLD_ID_PREFIX)
            ?.uppercase()
            ?.takeIf { it.length == 2 }
    }

    private fun mergedOfflinePoiPack(): local.phantom.companion.core.data.poi.OfflinePoiPack =
        local.phantom.companion.core.data.poi.OfflinePoiPack.merge(
            listOf(
                offlinePoiPack.current(),
                officialFacilitiesPack.current(),
                invincibilityPack.current(),
            ).filterNotNull(),
        )

    /**
     * ATLAS-3 — офлайнова підлога пінів належить АКТИВНОМУ паку, не збірці.
     * Пак не оголосив витягу (або пака нема взагалі) — підлоги теж нема:
     * порожній пакет замість київського, а не київський над Варшавою.
     */
    private val offlinePoiPack = local.phantom.companion.core.data.country.PackAsset(
        path = { it.poiAsset },
        read = ::readPackAsset,
        parse = { local.phantom.companion.core.data.poi.OfflinePoiPack.parse(it) },
    )

    /** Same seam as [offlinePoiPack] — NSZU pharmacies + primary-care providers. */
    private val officialFacilitiesPack = local.phantom.companion.core.data.country.PackAsset(
        path = { it.officialFacilitiesAsset },
        read = ::readPackAsset,
        parse = { local.phantom.companion.core.data.poi.OfflinePoiPack.parse(it) },
    )

    /** Same seam as [offlinePoiPack] — the three-oblast invincibility-point registry. */
    private val invincibilityPack = local.phantom.companion.core.data.country.PackAsset(
        path = { it.invincibilityAsset },
        read = ::readPackAsset,
        parse = { local.phantom.companion.core.data.poi.OfflinePoiPack.parse(it) },
    )

    private fun readPackAsset(
        pack: local.phantom.companion.core.data.country.CountryPack,
        name: String,
    ): String? = pack.readAsset(applicationContext, name)

    /** Boot warm-up seam (PhantomApp.warmGeoAssets, background thread): the
     *  4 MB pack parse costs 300–700 ms + ~20 MB transient allocations on a
     *  2 GB phone — paid at boot on a MIN_PRIORITY thread, not on whichever
     *  thread first touches [poiRepository] (brief 03 defect #2). Racing a
     *  first-touch is benign: [PackAsset] parses under its own lock.
     *  No pack yet (first launch, no fix) — nothing to warm, and nothing
     *  parsed for a country the operator may not be in. */
    fun warmOfflinePoiPack() {
        offlinePoiPack.current()
        officialFacilitiesPack.current()
        invincibilityPack.current()
    }

    /**
     * Кордон перетнуто — асети нового пака розбираються ТУТ, а не на першому
     * кадрі, що їх торкнеться. Прогрів на старті гріє пак, активний на старті;
     * після зміни країни без цього виклику 1,2 МБ наземної мережі розбиралися
     * б у композиції першої відкритої панелі транспорту. Пак без асетів
     * (Польща) віддає null миттєво — гріти нічого.
     */
    private fun warmPackAssets() {
        scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                offlinePoiPack.current()
                officialFacilitiesPack.current()
                invincibilityPack.current()
                metroPackAsset.current()
                surfacePackAsset.current()
            }
        }
    }

    /**
     * Make the bundled pack findable, not merely drawable.
     *
     * The pack fed the map's pins from the day it landed and fed offline search
     * never: search reads the gazetteer, and the gazetteer only ever filled
     * from live Overpass answers. So the operator's «шукає фігово» was literal
     * — on a phone that had not panned that exact viewport while online, there
     * was nothing to find.
     *
     * Keyed on the pack's bake epoch, so this costs one pass per re-bake and
     * nothing on every other launch. Runs on the container scope (IO), never on
     * the boot thread: twenty thousand encrypted inserts is seconds, and boot
     * does not get to spend them.
     */
    fun seedPoiGazetteerIfNeeded() {
        scope.launch(Dispatchers.IO) {
            // The MERGED identity, not just poi.json's: an install that
            // already seeded the old single pack must still notice the two
            // new ones landing, or their places stay drawable but unsearchable
            // forever on every phone that updated instead of freshly installed.
            val pack = mergedOfflinePoiPack()
            if (pack.size == 0) return@launch
            val prefs = applicationContext.getSharedPreferences(POI_SEED_PREFS, Context.MODE_PRIVATE)
            if (prefs.getLong(POI_SEED_KEY, 0L) == pack.generated) return@launch
            val written = runCatching { poiRepository.seedGazetteerFromPack() }.getOrDefault(0)
            if (written > 0) {
                prefs.edit().putLong(POI_SEED_KEY, pack.generated).apply()
                Log.i("AppContainer", "poi gazetteer seeded: $written places from bundled pack")
            }
        }
    }

    /**
     * The street-name half of [seedPoiGazetteerIfNeeded], same shape, same
     * table — a street lands in `poi_gazetteer` as a
     * [local.phantom.companion.core.data.poi.PoiGazetteerEntity] with
     * `category = "street"`, so [local.phantom.companion.core.data.poi
     * .PoiRepository.searchOfflineVerdict] answers «хрещатик» with no code of
     * its own to write.
     *
     * Streamed rather than parsed whole ([OfflineStreetPack]): the corpus is
     * ~270 000 lines, an order of magnitude past the 21 000 Kyiv POIs FTS4
     * was proven at on this device (11.08) — the whole reason this exists is
     * to measure that jump, not assume it is free. The re-seed check reads
     * only the header line, so a launch after the first costs one line, not
     * the whole file.
     */
    fun seedStreetGazetteerIfNeeded() = seedStreamedCorpus(
        label = "streets",
        keyPrefix = STREET_SEED_KEY,
        asset = { it.streetsAsset },
        generatedOf = local.phantom.companion.core.data.poi.OfflineStreetPack::parseGenerated,
        chunks = local.phantom.companion.core.data.poi.OfflineStreetPack::chunkLines,
    )

    /**
     * ВОДА — національний довідник місць у той самий газетир, тим самим
     * потоком, що й вулиці.
     *
     * Це та половина, якої бракувало людині поза Києвом. Пак `poi.json` носив
     * 21 335 місць у рамці [50.21, 30.24, 50.59, 30.83] — це місто, і 21 333 з
     * них усередині нього. Тобто на запит «де вода» у Харкові пристрій
     * відповідав порожнечею, маючи в тому самому витягу OSM 13 461 точку води
     * по країні.
     *
     * Потоком, а не другим `poi.json`, і причина зміряна: [OfflinePoiPack]
     * тримає кожен рядок живим обʼєктом до кінця життя застосунку — вірний
     * розмін на 21 тисячі рядків міста і хибний на 108 тисячах країни, де сам
     * лише розбір коштував би секунди й понад сотню мегабайтів тимчасових
     * виділень на телефоні з 2 ГБ. [OfflinePlacePack] має форму, вже доведену
     * на 270 000 вулиць: рядок на місце, фіксовані партії, у памʼяті нічого
     * понад партію в леті.
     */
    fun seedPlaceGazetteerIfNeeded() = seedStreamedCorpus(
        label = "places",
        keyPrefix = PLACE_SEED_KEY,
        asset = { it.placesAsset },
        generatedOf = local.phantom.companion.core.data.poi.OfflinePlacePack::parseGenerated,
        chunks = local.phantom.companion.core.data.poi.OfflinePlacePack::chunkLines,
    )

    /**
     * НАСЕЛЕНІ ПУНКТИ — тим самим потоком, у той самий газетир.
     *
     * Виміряно 12.09: `places.tsv` попри назву не несе жодного поселення
     * (`category='place'` у корпусі телефона — нуль рядків), а міста знав лише
     * `PlaceTable.kt`, 314 рядків, вписаних рукою. Тому «Моршин» із села і
     * назва будь-якого села звідусіль давали порожній екран при 496 806
     * рядках у кишені. Асет кузні додає 68 010.
     *
     * Формат [OfflinePlacePack] лишається без змін — саме тому тут немає ані
     * нового розбирача, ані нової таблиці.
     */
    fun seedSettlementGazetteerIfNeeded() = seedStreamedCorpus(
        label = "settlements",
        keyPrefix = SETTLEMENT_SEED_KEY,
        asset = { it.settlementsAsset },
        generatedOf = local.phantom.companion.core.data.poi.OfflinePlacePack::parseGenerated,
        chunks = local.phantom.companion.core.data.poi.OfflinePlacePack::chunkLines,
    )

    /**
     * ОДИН потік для всіх трьох корпусів — і чому він мусив стати одним.
     *
     * До 16.09 це були три майже однакові тіла, що відрізнялись лише полем
     * пака, розбирачем і ключем. Поки корпуси були ВБУДОВАНІ, різниця між
     * ними й справді була лише в цьому. Щойно їх повіз завантажуваний пак,
     * додалась четверта відмінність, яку три копії не мали де тримати:
     *
     * КЛЮЧ ЗАСІВУ НАЛЕЖИТЬ ПАКУ, А НЕ ЗАСТОСУНКУ. Спільний ключ означав би,
     * що телефон, який засіяв Україну й полетів у Польщу, порівнює епоху
     * випікання ПОЛЬЩІ з епохою УКРАЇНИ. Числа різні — засів пройде; але
     * назад в Україну він пройде теж, і так щоразу: два корпуси по чверті
     * мільйона рядків перезаписували б одне одного на кожному перетині
     * кордону. Тепер ключ — `<корпус>:<пак>`, тож кожна країна памʼятає свій
     * засів окремо.
     *
     * Україна лишається на СТАРОМУ ключі без суфікса навмисно: телефон, який
     * уже має її 265 447 вулиць, не має перекладати їх удруге лише тому, що
     * ми перейменували ключ.
     *
     * Памʼять тут не залежить від розміру країни: з файлу живе рівно партія в
     * леті ([OfflinePlacePack.chunkLines] — 500 рядків), і Німеччина на 1,37
     * млн вулиць коштує стільки ж живої памʼяті, скільки Словаччина на 34 тис.
     * Що росте — час і диск, не купа.
     */
    private fun seedStreamedCorpus(
        label: String,
        keyPrefix: String,
        asset: (local.phantom.companion.core.data.country.CountryPack) -> String?,
        generatedOf: (String) -> Long,
        chunks: (java.io.BufferedReader) -> Sequence<List<local.phantom.companion.core.data.poi.PoiGazetteerEntity>>,
    ) {
        scope.launch(Dispatchers.IO) {
            val pack = local.phantom.companion.core.data.country.CountryPackRegistry.current() ?: return@launch
            val name = asset(pack) ?: return@launch
            val key = seedKey(keyPrefix, pack.id)
            val prefs = applicationContext.getSharedPreferences(POI_SEED_PREFS, Context.MODE_PRIVATE)
            var written = 0
            val started = android.os.SystemClock.elapsedRealtime()
            runCatching {
                pack.readAssetStream(applicationContext, name)
                    ?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                        val generated = reader.readLine()?.let(generatedOf) ?: 0L
                        if (prefs.getLong(key, 0L) == generated) return@use
                        for (chunk in chunks(reader)) {
                            database.poiGazetteerDao().upsert(chunk)
                            written += chunk.size
                        }
                        if (written > 0) prefs.edit().putLong(key, generated).apply()
                    }
            }.onFailure { t -> Log.w("AppContainer", "$label seed failed for ${pack.id}", t) }
            if (written > 0) {
                val spent = android.os.SystemClock.elapsedRealtime() - started
                Log.i("AppContainer", "$label gazetteer seeded: $written rows from ${pack.id} in $spent ms")
            }
        }
    }

    private fun seedKey(prefix: String, packId: String): String =
        if (packId == local.phantom.companion.core.data.country.UkrainePack.id) prefix
        else "$prefix:$packId"

    // M-C05/C06 — offline-first shelter registry backing the Tactical
    // Evacuation Engine (Law 5: cached shelters when Overpass is down).
    val shelterRepository: local.phantom.companion.core.data.evac.ShelterRepository by lazy {
        local.phantom.companion.core.data.evac.ShelterRepository(
            poiDao = database.poiDao(),
            overpassClient = overpassClient,
        )
    }

    /**
     * R4.1 — the spatial spine. One Room-backed store of named layers the
     * phantom pushes onto the map (`map.show`), the operator saves by hand,
     * and later phases (R7 Atlas, R8 M-cards) write into as a SECOND writer,
     * never a parallel store.
     */
    val spatialLayerRepository: local.phantom.companion.core.data.spatial.SpatialLayerRepository by lazy {
        local.phantom.companion.core.data.spatial.SpatialLayerRepository(
            dao = database.spatialLayerDao(),
        ).also { armLiveMarksPlane(it) }
    }

    /**
     * ATLAS-4 «Живий шар» v0 — спільні TTL-мітки між операторами.
     *
     * Жодного UI: мапа вже малює шар «позначки на мапі», тож синхронізована
     * мітка зʼявляється тим самим шляхом, що й власна. Провенанс «спільнота»
     * лишається в даних — синхронізована мітка не стає підтвердженим фактом.
     *
     * Площина зводиться разом зі сховищем міток, а не в PhantomApp: доки шару
     * ніхто не торкнувся, синхронізувати нічого. Ключів немає → площини немає,
     * і застосунок цього навіть не помічає (local-first).
     */
    @Volatile
    private var liveMarksPlaneRef: local.phantom.companion.core.data.live.LiveMarksRepository? = null

    val liveMarksPlane: local.phantom.companion.core.data.live.LiveMarksRepository?
        get() = liveMarksPlaneRef

    private fun armLiveMarksPlane(
        store: local.phantom.companion.core.data.spatial.SpatialLayerRepository,
    ) {
        val config = local.phantom.companion.core.net.live.LiveMarksConfig(
            baseUrl = local.phantom.companion.BuildConfig.SUPABASE_URL,
            anonKey = local.phantom.companion.BuildConfig.SUPABASE_ANON_KEY,
        )
        if (!config.isConfigured) return
        val plane = local.phantom.companion.core.data.live.LiveMarksRepository(
            port = local.phantom.companion.core.net.live.LiveMarksClient(
                config = config,
                deviceTag = liveMarksDeviceTag(),
            ),
            sink = store,
            layers = store.layers,
            // Прискорювач, не джерело: опитування лишається звіряльником і
            // саме пришвидшується, коли сокет відпадає.
            stream = local.phantom.companion.core.net.live.LiveMarksRealtime(config),
            region = {
                val view = events.mapViewport.value
                // Регіон — це вʼюпорт, НЕ позиція оператора, і лише на міському
                // зумі: далі мітки не читаються оком, а запит роздувся б на
                // пів області.
                if (view == null || view.zoom < LIVE_PLANE_MIN_ZOOM) {
                    null
                } else {
                    local.phantom.companion.core.data.live.LiveRegion(
                        lat = view.centerLat,
                        lon = view.centerLon,
                        radiusM = LIVE_PLANE_RADIUS_M,
                    )
                }
            },
        )
        liveMarksPlaneRef = plane
        plane.start(scope)
    }

    /** Анонімний токен встановлення. Не ідентифікує людину і не покидає пристрій,
     *  окрім як разом із власною міткою; читати його назад ніхто не вміє. */
    private fun liveMarksDeviceTag(): String {
        val prefs = applicationContext.getSharedPreferences(LIVE_PLANE_PREFS, Context.MODE_PRIVATE)
        prefs.getString(LIVE_PLANE_TAG_KEY, null)?.let { return it }
        val tag = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(LIVE_PLANE_TAG_KEY, tag).apply()
        return tag
    }

    /**
     * The cross-surface channels. They hold no logic and depend on nothing,
     * which is why they were the most-edited region of this file — every new
     * surface added one. The properties below stay as delegates because ~90
     * call sites across two modules reach through `container.<channel>`.
     */
    val events: AppEventBus = AppEventBus()

    val activeThreadRef get() = events.activeThreadRef

    val mapViewport get() = events.mapViewport

    /**
     * M-I01 fix — the operator asked PHANTOM to drop a pin/route/nearby from
     * chat and it silently wrote to [spatialLayerRepository] with the map
     * closed: the layer was there, but nobody saw it. `map.point/route/nearby`
     * now emit here after a successful push, and the nav layer (below)
     * navigates to the Map destination so the result is actually seen — Law 2
     * (dual control) means PHANTOM's hand must be as visible as the
     * operator's own gesture, not just persisted for later.
     */
    val mapOpenRequests get() = events.mapOpenRequests

    val homeTabRequest get() = events.homeTabRequest

    val visibleTab get() = events.visibleTab

    /** A peer conversation requested from outside the app (link, notification). */
    val peerChatRequest get() = events.peerChatRequest

    val peerGroupRequest get() = events.peerGroupRequest

    val peerCardRequest get() = events.peerCardRequest

    /**
     * СИРЕНА-3 — небо процесу, а не екрана.
     *
     * До цього опитування тривог жило в `MapViewModel`: згорнутий застосунок
     * не знав про тривогу над оператором взагалі. Тепер один тримач на процес,
     * а мапа й `AirAlertService` — його споживачі. Темп адаптивний: 30 с під
     * тривогою у своїй області, 60 с при увімкненому екрані, 180 с у кишені,
     * з подвоєнням на кожну невдачу мережі.
     */
    val countryAlarms: local.phantom.companion.feature.stream.ui.map.country.CountryAlarms by lazy {
        local.phantom.companion.feature.stream.ui.map.country.CountryAlarms(
            context = applicationContext,
            secret = { key ->
                // A vault row whose value will not open now comes back empty rather
                // than throwing, so blank has to be treated as absent — otherwise
                // this sends "" as a credential and reads the 401 as a dead feed.
                runCatching { vaultStore.getEntry(key)?.value?.takeIf { it.isNotBlank() } }.getOrNull()
            },
            // Українець за кордоном, що ввімкнув тривоги сам: пак під ним неба не має.
            fallbackPack = {
                local.phantom.companion.core.data.country.UkrainePack.takeIf { mapPrefs.ukraineAlerts.value == true }
            },
        )
    }

    val airAlertWatch: local.phantom.companion.feature.stream.ui.map.AirAlertWatch by lazy {
        local.phantom.companion.feature.stream.ui.map.AirAlertWatch(
            scope = scope,
            fetchBase = { countryAlarms.regionState() },
            fetchSiren = { countryAlarms.typed() },
            fetchDetail = { countryAlarms.detail() },
            // Без runCatching: збій читання полігонів має ДОЛЕТІТИ до watch і
            // втримати попередню область; null тут — «поза областями», і watch
            // на нього область чистить.
            oblastAt = { lat, lon ->
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    countryAlarms.regionShapes().oblastAt(lat, lon)
                }
            },
            // Активація пака живе тут, бо це єдиний фікс, що йде і в кишені:
            // мапа може бути закрита, а кордон — вже перетнутий.
            skyDeclared = { fix ->
                fix?.let {
                    val before = local.phantom.companion.core.data.country.CountryPackRegistry.current()?.id
                    local.phantom.companion.core.data.country.CountryPackRegistry.locate(it.lat, it.lon)
                    val now = local.phantom.companion.core.data.country.CountryPackRegistry.current()?.id
                    if (now != before) warmPackAssets()
                }
                local.phantom.companion.service.AirAlertGate.skyDeclared(applicationContext, mapPrefs) { countryAlarms.skyAvailable() }
            },
            locationSource = { locationProvider.current() },
            online = { networkProbe.online.value },
            screenOn = {
                runCatching {
                    applicationContext.getSystemService(android.os.PowerManager::class.java)
                        ?.isInteractive ?: true
                }.getOrDefault(true)
            },
        )
    }

    /**
     * PROTEUS P0 — a prompt for the Dialogue stratum fired from a chamber
     * (Lens «спитати ШІ» today). Replaces the old `stream?prompt=` nav
     * argument, whose popUpTo-inclusive delivery rebuilt the Stream entry
     * and killed the shared StreamViewModel. replay=1 + reset-after-handle,
     * same contract as [navCommands]: the emitter may fire before the
     * Continuum recomposes.
     */
    val streamPromptRequests get() = events.streamPromptRequests

    /**
     * M-C01 — Phantom requested to show the threat brief for an oblast.
     * The MapScreen observes this and opens the ThreatBriefSheet.
     */
    val threatBriefRequests get() = events.threatBriefRequests

    /**
     * NAV-2 — `nav.start`/`nav.stop` from PHANTOM's hand to the map surface.
     * replay=1 on purpose: the tool usually fires while the map is still
     * navigating on screen, so the command must survive until MapScreen's
     * collector composes; MapScreen resets the replay cache after handling
     * so a stale command never re-fires on a later open.
     */
    val navCommands get() = events.navCommands

    /**
     * NAV-Ω1 — the selected route's polyline while one is live on the map
     * (published by MapScreen, read by `route.along`). Null = no route.
     */
    val activeRoutePoints get() = events.activeRoutePoints

    val osrmClient: local.phantom.companion.core.net.map.OsrmClient by lazy {
        local.phantom.companion.core.net.map.OsrmClient()
    }

    val routeIntel: local.phantom.companion.core.net.map.RouteIntel by lazy {
        local.phantom.companion.core.net.map.RouteIntel()
    }

    /**
     * MULTIMODAL CONTINUUM — the fused routing mesh: metro (synthesized,
     * OSM topology + headway model) + the full surface network (bus /
     * trolleybus / tram, same doctrine), one [Timetable]. Street-level and
     * street↔metro transfers come from the builder's proximity footpaths;
     * the pedestrian first/last mile and park-and-ride legs come from the
     * road router inside the planner — that is the whole fusion, in one place.
     *
     * ATLAS-3 — обидві мережі належать паку. Пак їх не оголосив (або пака
     * нема) — мережі не існує тут узагалі: `TransitRepository` бачить
     * `simulatedBounds == null`, ярус моделі мовчить, і цикл флоту не крутить
     * київську модель над чужим містом.
     */
    val metroFeed: local.phantom.companion.core.data.transit.metro.MetroFeed?
        get() = metroPackAsset.current()

    val surfaceFeed: local.phantom.companion.core.data.transit.surface.SurfaceFeed?
        get() = surfacePackAsset.current()

    private val metroPackAsset = local.phantom.companion.core.data.country.PackAsset(
        path = { it.metroAsset },
        read = ::readPackAsset,
        parse = { local.phantom.companion.core.data.transit.metro.MetroFeed.parse(it) },
    )

    private val surfacePackAsset = local.phantom.companion.core.data.country.PackAsset(
        path = { it.surfaceAsset },
        read = ::readPackAsset,
        parse = { local.phantom.companion.core.data.transit.surface.SurfaceFeed.parse(it) },
    )

    /**
     * ATLAS-3 — глобальний ярус розкладів. Ключ приходить лише зі збірки
     * (`phantom.transitlandKey` у local.properties); порожній означає чесну
     * відсутність джерела, а не порожній транспорт. Розклади, і ніколи
     * позиції: рухома мітка зі статичного GTFS була б фантомом.
     */
    val transitStatic: local.phantom.companion.core.data.transit.TransitlandSource by lazy {
        local.phantom.companion.core.data.transit.TransitlandSource(
            apiKey = local.phantom.companion.BuildConfig.TRANSITLAND_KEY,
        )
    }

    /**
     * ATLAS-3 — ОПУБЛІКОВАНИЙ розклад міста, доставлений і розібраний.
     *
     * До 13.09.2026 `GtfsStaticParser` не мав ЖОДНОГО виклику в бойовому
     * коді, а `TimetableBuilder` нижче будувався з самої лише інтервальної
     * моделі й чекав на дані, яких ніхто не приносив. Це — та ланка.
     *
     * Онлайн, не в APK: київський архів перегенеровується щодоби, і
     * запечений знімок був би тихо неправдивим уже назавтра. Диск —
     * подушка, не джерело істини; [ensureSchedule] ходить у мережу рівно
     * раз на день служби.
     */
    val gtfsScheduleStore: local.phantom.companion.core.data.transit.gtfs.GtfsScheduleStore by lazy {
        local.phantom.companion.core.data.transit.gtfs.GtfsScheduleStore(
            client = local.phantom.companion.core.net.transit.GtfsStaticClient(),
            cacheDir = java.io.File(applicationContext.cacheDir, "transit"),
        )
    }

    /**
     * Розклад, придатний ПРЯМО ЗАРАЗ: є, розібраний, і саме на сьогодні.
     * Протухлий знімок сюди не проходить — картка радше змовчить, ніж
     * покаже вчорашні хвилини як сьогоднішні.
     */
    fun freshSchedule(): local.phantom.companion.core.data.transit.gtfs.GtfsSchedule? =
        gtfsScheduleStore.freshSchedule()

    /**
     * Тягне розклад міста, що покриває точку. Тихо нічого не робить там,
     * де розкладу не оголошено — це не помилка, це чесна відсутність.
     */
    suspend fun ensureSchedule(lat: Double, lon: Double) {
        val feed = local.phantom.companion.core.data.transit.GtfsScheduleRegistry
            .feedFor(lat, lon) ?: return
        gtfsScheduleStore.ensure(feed)
    }

    /**
     * Ліниво, але не назавжди. `lazy {}` запікав би розклад активного пака в
     * процес: збудований у Києві планувальник возив би варшавʼянина
     * київськими рейсами до перезапуску. Ключ — пак і його транзитні асети;
     * зміна країни будує новий розклад, а пак без мереж будує порожній
     * миттєво і чесно нікуди не везе.
     */
    private val multimodalPlannerLazy: Lazy<local.phantom.companion.core.data.transit.MultimodalPlanner?> =
        object : Lazy<local.phantom.companion.core.data.transit.MultimodalPlanner?> {
            private var key: String? = null
            private var built: local.phantom.companion.core.data.transit.MultimodalPlanner? = null

            override val value: local.phantom.companion.core.data.transit.MultimodalPlanner?
                @Synchronized get() {
                    val pack = local.phantom.companion.core.data.country.CountryPackRegistry.current()
                    val wanted = "${pack?.id}:${pack?.metroAsset}:${pack?.surfaceAsset}"
                    built?.let { if (key == wanted) return it }
                    return buildMultimodalPlanner().also { key = wanted; built = it }
                }

            override fun isInitialized(): Boolean = built != null
        }

    private fun buildMultimodalPlanner(): local.phantom.companion.core.data.transit.MultimodalPlanner? {
        val metro = metroFeed
        val surface = surfaceFeed
        val stops = (metro?.stops ?: emptyList()) + (surface?.stops ?: emptyList())
        if (stops.isEmpty()) return null
        val timetable = runCatching {
            local.phantom.companion.core.data.transit.raptor.TimetableBuilder.build(
                stops = stops,
                trips = (metro?.trips() ?: emptyList()) + (surface?.trips() ?: emptyList()),
                extraTransfers = metro?.transfers ?: emptyList(),
            )
        }.getOrNull() ?: return null
        val roadRouter = local.phantom.companion.data.OsrmRoadRouter(
            osrmClient,
            // The earned mesh backs walking-leg geometry when OSRM is dead —
            // the same doctrine as evacuation's offline fallback.
            meshGraph = { s, w, n, e ->
                runCatching {
                    local.phantom.companion.core.sensor.nav.RoadGraph(roadMeshStore.waysIn(s, w, n, e))
                }.getOrNull()?.takeIf { it.wayCount > 0 }
            },
            // …і той самий прошарок неба, що й у головного маршруту.
            integrity = gnssIntegrity,
        )
        return local.phantom.companion.core.data.transit.MultimodalPlanner(
            timetable = timetable,
            road = roadRouter,
            // Park-and-ride drops stay metro-only by doctrine: driving to a
            // bus stop to sit in the same traffic is not a journey.
            metroStopIds = metro?.stationIds ?: emptyList(),
            parkRideLots = metro?.doc?.parkRideLots ?: emptyList(),
        )
    }

    val multimodalPlanner: local.phantom.companion.core.data.transit.MultimodalPlanner?
        get() = multimodalPlannerLazy.value

    val globalTransitRouter: local.phantom.companion.core.data.transit.GlobalTransitRouter by lazy {
        local.phantom.companion.core.data.transit.GlobalTransitRouter()
    }

    /**
     * HIGHWAY DOMINANCE — THE navigator. One per process, and it belongs to
     * nobody.
     *
     * Both heads observe this: the Continuum's `MapViewModel` and the car's
     * `NavigationTemplate`. Neither owns it, so neither can end a drive by being
     * destroyed — which is the whole point, because on Android Auto the phone's
     * Activity is usually not running, and a route on `viewModelScope` would be
     * a route the car cannot see.
     *
     * It is started here, on [scope], and it runs its own fix pump for as long
     * as the process lives. The pump is cheap when idle (a slow pulse) and only
     * goes to 1 Hz once a drive is actually live.
     */
    val navEngine: local.phantom.companion.core.nav.NavigationEngine by lazy {
        local.phantom.companion.core.nav.NavigationEngine(
            scope = scope,
            locationSource = {
                locationProvider.current()?.let { fix ->
                    local.phantom.companion.core.nav.NavFix(
                        lat = fix.lat,
                        lon = fix.lon,
                        speedMps = fix.speedMps,
                        bearingDeg = fix.bearing,
                        // ZERO-FAIL — the engine refuses to steer on anything the
                        // location chain has not judged. A 30-minute-old IP-geo
                        // guess is good enough to tell the AI what city he is in,
                        // and nowhere near good enough to turn a wheel on.
                        navigationGrade = fix.isNavigationGrade,
                        // KYIV FIELD FAILURE — judged is not the same as believed.
                        // A dead-reckoned fix is honestly navigation-grade, but
                        // after hours of EW its doubt was 346 km and guidance
                        // rerouted off it all day. Reroutes/arrival need the sky.
                        trusted = fix.isNavigationGrade && !fix.dead &&
                            (fix.accuracyM ?: 0f) <= 150f,
                        // QA FIELD FAILURE — off-route verdicts inflate their
                        // corridor by the fix's own admitted doubt; without it
                        // indoor scatter reads as leaving the route.
                        accuracyM = fix.accuracyM,
                        gait = navGait(),
                    )
                }
            },
            planner = local.phantom.companion.core.nav.RoutePlanner(
                scope = scope,
                osrm = osrmClient,
                intel = routeIntel,
                multimodalPlanner = multimodalPlannerLazy,
                globalTransitRouter = globalTransitRouter,
                // FIELD ROUTING — OSRM-first with the permanent road mesh as the
                // offline/off-LAN fallback (see [routedVariants]). Without it the
                // navigator dies the moment the phone leaves the laptop's Wi-Fi.
                routeSource = { profile, waypoints -> routedVariants(profile, waypoints) },
                routeRefusal = { lastRouteRefusal },
            ),
            landmarks = RouteLandmarkSource(gazetteer = { database.poiGazetteerDao() }, mesh = { roadMeshStore }),
            darkAt = RouteLandmarkSource::darkAt,
            // ЗІР — шар маршруту в тому самому реєстраторі, що вже пише небо й
            // фільтр. Реєстратор працює в БУДЬ-ЯКІЙ збірці, переживає поїздку
            // файлом і знімається однією командою; журнал мапи, який писав
            // тільки вигін, живе лише при відкритому екрані й лише в debug.
            trace = local.phantom.companion.core.nav.GuidanceTrace { t ->
                blackBox.recordNav(
                    lat = t.lat,
                    lon = t.lon,
                    snapLat = t.snapLat,
                    snapLon = t.snapLon,
                    offMeters = t.offMeters,
                    offRoute = t.offRoute,
                    offStreak = t.offStreak,
                    requestReroute = t.requestReroute,
                    routeBearingDeg = t.routeBearingDeg,
                    motionBearingDeg = t.motionBearingDeg,
                    speedMps = t.speedMps,
                    trusted = t.trusted,
                    accuracyM = t.accuracyM,
                    gait = t.gait,
                    stepIndex = t.stepIndex,
                    distanceToManeuverM = t.distanceToManeuverM,
                    maneuver = t.maneuver,
                    roadName = t.roadName,
                )
            },
        ).also { engine ->
            engine.start()

            // Pre-warm the transit mesh off the main thread: the fused
            // timetable build (~18k trip expansion) belongs to this
            // coroutine, not to the first «транзит» tap and never to a
            // frame. Lazy is synchronized — a plan racing this simply waits.
            scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                runCatching { multimodalPlannerLazy.value }
            }

            // PHANTOM's hand reaches the engine directly, not through a screen:
            // `nav.start` must work on a drive whose only display is the car's.
            // The map still collects this flow for the arrival deadline, whose
            // watcher is genuinely a map-surface concern.
            scope.launch {
                navCommands.collect { cmd ->
                    // KYIV FIELD FAILURE — a route planned FROM a dead-reckoned
                    // estimate kilometres adrift is a route to nowhere. An
                    // honest «не знаю, де ви» beats confident nonsense.
                    val from = locationProvider.current()
                        ?.takeIf {
                            it.isNavigationGrade && !it.dead &&
                                (it.accuracyM ?: 0f) <= 150f
                        }
                        ?.let { it.lat to it.lon }
                    engine.execute(cmd, from)
                }
            }

            // W-3 / NAV-Ω1 — `route.along` asks "що по дорозі" from the CHAT, so
            // the corridor it searches must be published by the engine, not by a
            // map composition that may never have existed on this drive.
            scope.launch {
                engine.state.collect { st ->
                    activeRoutePoints.value = st.routePoints.takeIf { it.isNotEmpty() }
                }
            }

            // ПРОВИДЕЦЬ — a fresh route corridor thickens the permanent mesh
            // by itself, silently: if the connection dies mid-drive, the
            // routing world along this exact corridor is already earned.
            scope.launch {
                activeRoutePoints
                    .filterNotNull()
                    .distinctUntilChanged { a, b ->
                        a.size == b.size &&
                            a.firstOrNull() == b.firstOrNull() &&
                            a.lastOrNull() == b.lastOrNull()
                    }
                    .collect { corridorPrefetcher.meshOnly(it) }
            }
        }
    }

    /**
     * R4.x — the direct "світ" menu on the map. The user taps транспорт /
     * камери / літаки and the matching world tool runs at the map centre with
     * no LLM turn — the tap itself is the permission grant (NETWORK+LOCATION).
     * The tool pushes its own WORLD layer (and requests focus), so the map
     * comes alive immediately. Keeps feature-stream free of core-symbiote via
     * the [WorldLayerSummoner] port.
     */
    val worldSummoner: local.phantom.companion.feature.stream.ui.map.WorldLayerSummoner by lazy {
        val summonHttp = local.phantom.companion.core.tool.impl.GeoHttp(
            io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp),
        )
        val thread = { activeThreadRef.get() }
        local.phantom.companion.feature.stream.ui.map.WorldLayerSummoner { kind, lat, lon ->
            val tool = when (kind) {
                "transit" -> local.phantom.companion.core.tool.impl.TransitNearbyTool(
                    http = summonHttp, sink = spatialLayerRepository,
                    locationProvider = locationProvider, currentThreadId = thread,
                )
                "routes" -> local.phantom.companion.core.tool.impl.TransitRoutesTool(
                    http = summonHttp, sink = spatialLayerRepository,
                    locationProvider = locationProvider, currentThreadId = thread,
                )
                "cameras" -> local.phantom.companion.core.tool.impl.WorldCamerasTool(
                    http = summonHttp, sink = spatialLayerRepository,
                    locationProvider = locationProvider, currentThreadId = thread,
                )
                "aircraft" -> local.phantom.companion.core.tool.impl.WorldAircraftTool(
                    http = summonHttp, sink = spatialLayerRepository,
                    locationProvider = locationProvider, currentThreadId = thread,
                )
                else -> null
            } ?: return@WorldLayerSummoner false
            val ctx = local.phantom.companion.core.tool.ToolContext(
                appContext = applicationContext,
                operatorId = "operator",
                requestId = "world-menu-" + System.currentTimeMillis(),
                approvedPermissions = setOf(
                    local.phantom.companion.core.tool.ToolPermission.NETWORK,
                    local.phantom.companion.core.tool.ToolPermission.LOCATION,
                ),
            )
            val args = kotlinx.serialization.json.buildJsonObject {
                put("lat", kotlinx.serialization.json.JsonPrimitive(lat))
                put("lon", kotlinx.serialization.json.JsonPrimitive(lon))
            }
            val res = runCatching { tool.execute(args, ctx) {} }.getOrNull()
            res is local.phantom.companion.core.tool.ToolResult.Ok
        }
    }

    // MAP-4 — offline region pack manager. Wraps MapLibre's
    // OfflineManager + OfflineRegion callback API in coroutines and a
    // RegionInfo Flow that the Settings → Кешовані регіони list
    // subscribes to. Lazy so MapLibre.getInstance() doesn't fire until
    // the operator opens the Map surface or Settings → Map.
    val offlineRegions: local.phantom.companion.feature.stream.ui.map.OfflineRegionsManager by lazy {
        local.phantom.companion.feature.stream.ui.map.OfflineRegionsManager(applicationContext)
    }

    // Phase 2-A — STT layer: Hybrid STT manager (Vosk + Whisper).
    val voiceManager: local.phantom.companion.core.voice.VoiceManager by lazy {
        local.phantom.companion.core.voice.VoiceManager(
            applicationContext,
            onCaptureStart = { hearingProvisioner.ensureHearing() },
        ).apply {
            // BW-2 — resolveVoskModelDir() walks the unzip output to find
            // the dir that actually contains am/conf/graph (the alphacephei
            // zip wraps everything in a top-level VOSK_MODEL_DIR subdir,
            // which Model() can't load).
            val voskDir = modelManager.resolveVoskModelDir()?.absolutePath
            val yamnetFile = modelManager.getModelFile(local.phantom.companion.core.ai.ModelType.YAMNET)
            val yamnetPath = yamnetFile.takeIf { it.exists() && it.length() > 0 }?.absolutePath
            val moonshineDir = modelManager.getModelFile(local.phantom.companion.core.ai.ModelType.MOONSHINE_UK)

            runCatching {
                refreshModels(voskDir, yamnetPath, moonshineDir.takeIf { it.exists() })
            }.onFailure { t ->
                Log.w("AppContainer", "Initial STT/Yamnet init failed", t)
                errorBus.emit(
                    local.phantom.companion.core.data.errors.PhantomError.create(
                        source = "VoiceManager",
                        severity = local.phantom.companion.core.data.errors.PhantomError.Severity.Error,
                        // onCaptureStart above provisions hearing itself, so
                        // there is nothing in Settings for him to check.
                        message = "слух не запустився — спробую ще, коли візьмешся за мікрофон",
                        cause = t,
                    )
                )
            }
        }
    }

    /**
     * OMNIPRESENT VOICE — the full-duplex acoustic channel, at application
     * scope.
     *
     * It lives *here*, and not in a ViewModel or a Service, because that is
     * what makes the voice omnipresent: the operator racks the camera from
     * Presence to Dialogue to the World, and the conversation does not so much
     * as stutter, because nothing it depends on is mounted in a stratum. The
     * [LiveDialogService] merely keeps the process alive around it; killing
     * that service does not kill the channel.
     *
     * It intercepts rather than wraps — every `ttsEngine.speak()` in the
     * process, wherever it was launched from, already funnels through
     * SpeechActivityHub, which the engine watches. So the map's voice cue and
     * the chat's reply both get barge-in without either of them knowing this
     * class exists.
     */
    val duplexVoice: local.phantom.companion.core.voice.duplex.DuplexVoiceEngine by lazy {
        local.phantom.companion.core.voice.duplex.DuplexVoiceEngine(
            voice = voiceManager,
            tts = ttsEngine,
            scope = scope,
            muted = { voicePrefs.micMuted.value },
        )
    }

    // Phase B — MobileSensorAdapter (GPS+IMU+MicRms). Gated until verified.
    //
    // MIC-1 — the mic-RMS source no longer opens its own AudioRecord; it
    // samples the process-global AudioLevelHub instead. The lambda reads
    // the hub directly so accessing the level does NOT force VoiceManager
    // lazy init (which would load Vosk/Whisper on every cold launch and
    // blow the perf budget). The hub returns 0f when no recorder is
    // active; the .takeIf clamp converts that to null so `micRmsDb` in
    // SensorBatch carries null and the SystemPulse VOICE dot drops to OFF.
    val sensorAdapter: local.phantom.companion.core.sensor.MobileSensorAdapter? by lazy {
        if (local.phantom.companion.BuildConfig.ENABLE_SENSOR) {
            local.phantom.companion.core.sensor.MobileSensorAdapter(
                context = applicationContext,
                audioLevelProvider = {
                    local.phantom.companion.core.voice.AudioLevelHub.level.value
                        .takeIf { it > 0f }
                },
            ).also { it.start(scope) }
        } else {
            null
        }
    }

    /**
     * W1-4 — operator location with the full five-step chain:
     *   1. live MobileSensorAdapter GPS
     *   2. LocationManager PASSIVE_PROVIDER last-known
     *   3. LocationManager NETWORK_PROVIDER last-known
     *   4. cached IP-geo (30 min TTL)
     *   5. fresh ipapi.co lookup via [ipApiClient]
     *
     * Cloud fallback is hard-gated by GHOST mode — a vault-of-traces
     * session must not silently leak the operator's IP to the cloud
     * geo service. On-device sources keep working under GHOST.
     */

    /**
     * W2-1/W2-2 — Stream history repository. The StreamViewModel takes
     * this to persist every Text/Widget/Notice/Command emit; the
     * cold-launch path (W2-3) reads from it on init so chat history
     * survives app kill. Lazy on PhantomDatabase so we don't pay the
     * Room init cost until the operator actually hits the Stream.
     */
    val streamRepository: local.phantom.companion.core.data.stream.StreamRepository by lazy {
        local.phantom.companion.core.data.stream.StreamRepository(database.streamItemDao())
    }

    /**
     * DA-5 — exposed as a lazy so [StreamCleanupWorker] can call
     * `runOnceIfDue` through the canonical AppContainer accessor.
     * The Worker grabs `PhantomApp.container().streamCleanupScheduler`
     * — same entry point a Settings «очистити зараз» button uses.
     */
    val streamCleanupScheduler: StreamCleanupScheduler by lazy {
        StreamCleanupScheduler(streamRepository, voicePrefs)
    }

    /**
     * W2-4 — ThreadRail's persistence layer. Bootstraps the default
     * «Зараз» thread on first launch so the rail is never empty;
     * the repository's observeAll() Flow drives ThreadRail's pill
     * order (most-recent-first via last_active_at).
     */
    val threadMetaRepository: local.phantom.companion.core.data.stream.ThreadMetaRepository by lazy {
        local.phantom.companion.core.data.stream.ThreadMetaRepository(database.threadMetaDao()).also { repo ->
            scope.launch {
                runCatching {
                    // Підпис бере ТОЙ, ХТО МАЄ МОВУ. Репозиторій її не має.
                    repo.ensureDefault(
                        applicationContext.getString(
                            local.phantom.companion.feature.stream.R.string.thread_now,
                        ),
                    )
                }
            }
        }
    }

    /**
     * W1.5-4 — central SoundEngine (canonical sound catalog from
     * DESIGN_BRIEF §V/§XV). GHOST-gated so a vault-of-traces session
     * stays silent. Composable callers read it via LocalSoundEngine;
     * non-Composable callers (ViewModels, services) take it as a
     * constructor parameter.
     */
    val soundEngine: local.phantom.companion.core.design.sound.SoundEngine by lazy {
        local.phantom.companion.core.design.sound.SoundEngine.gated(
            inner = local.phantom.companion.core.design.sound.AndroidToneGeneratorSoundEngine(applicationContext),
            isGhost = {
                contextEngine?.state?.value == local.phantom.companion.core.context.SystemState.GHOST
            },
        )
    }

    /**
     * PHANTOM-EW — the one place a position may enter PHANTOM.
     *
     * Every consumer downstream of here (the map's operator dot, the guidance
     * corridor, the AI's `[LOCATION:…]` header) reads the *fused* fix: judged by
     * the spoof gate, weighted by a Kalman filter, dead-reckoned through a
     * blackout, and carrying its own honest radius of doubt. Nobody reads the
     * chip directly any more — under electronic warfare the chip is a hostile
     * witness, and a navigator that believes it walks its operator into the
     * position an adversary chose.
     */
    val locator: local.phantom.companion.core.sensor.nav.PhantomLocator? by lazy {
        sensorAdapter?.let { adapter ->
            local.phantom.companion.core.sensor.nav.PhantomLocator(
                context = applicationContext,
                gpsFeed = adapter.gpsFeed,
                imuFeed = adapter.imuEvents,
                blackBox = if (local.phantom.companion.Distribution.blackBox) blackBox else null,
                // ЛІЧИЛЬНИК ШЛЯХУ НА ГРАФІ — без цього рядка шов існує, а графа
                // йому ніхто не подає, і він мовчить назавжди: рівно та хвороба,
                // яку ми ловимо в чужому коді.
                //
                // Через кеш коридору, а не прямим читанням: лічильник питає граф
                // на КОЖНОМУ такті сліпоти, а підготовка коштує 77 мс (розбір
                // 24 + побудова 53, заміряно). Кеш віддає вміщений прямокутник
                // із памʼяті; свіжі тайли скасовують його ревізією.
                graphNear = ::roadGraphNear,
            ).also { loc ->
                loc.start(scope)
                // ЯКИЙ САМЕ ТРАНСПОРТ. `KinematicsEngine` чесно каже, що не
                // може цього знати: авто й тролейбус на дванадцяти секундах
                // акселерометра — той самий сигнал. `:core-mobility` уміє
                // (профіль ЗУПИНОК на хвилинах), його двері `onModeOpinion`
                // існували — і в усьому дереві їх не кликав НІХТО, тож
                // `MotionProfile.vehicle` завжди був null. Ось шов.
                //
                // Ціна названа: це другий слухач акселерометра поруч із тим,
                // що вже веде локатор. Вікно ~9 с, вирок рідкий; годувати його
                // ще й швидкістю зшитого фікса треба, бо без неї «стою в заторі»
                // не відрізнити від «стою на місці».
                val modeSource = local.phantom.companion.feature.precision.LiveMotionSource(
                    applicationContext,
                )
                scope.launch {
                    loc.fix.collect { fix ->
                        if (fix != null && !fix.dead) {
                            modeSource.offerSpeed(fix.speedMps.toDouble())
                        }
                    }
                }
                scope.launch {
                    modeSource.verdicts().collect { verdict ->
                        // Нижче порогу — мовчимо: здогад, переданий далі, стає
                        // виглядом екрана, і це вже не здогад для користувача.
                        if (verdict.confidence >= VEHICLE_OPINION_FLOOR) {
                            loc.onVehicleOpinion(
                                kind = vehicleKindOf(verdict.mode),
                                confidence = verdict.confidence,
                                nowMs = android.os.SystemClock.elapsedRealtime(),
                            )
                        }
                    }
                }
                // WAVE 5 — Continuous pedestrian dead-reckoning during GPS silence/metro
                val pdr = local.phantom.companion.feature.precision.LivePedestrianSource(applicationContext)
                scope.launch {
                    loc.fix.collect { fix ->
                        if (fix != null && !fix.dead) {
                            pdr.offerTrustedFix(
                                accuracyM = fix.uncertaintyM.toDouble(),
                                bearingRad = Math.toRadians(fix.bearingDeg.toDouble()),
                                speedMps = fix.speedMps.toDouble(),
                            )
                        }
                    }
                }
                scope.launch {
                    pdr.snapshots().collect { snap ->
                        val bearingRad = snap.displacementBearingRad
                        if (snap.informative && bearingRad != null && snap.displacementM > 0.0) {
                            val eastM = snap.displacementM * kotlin.math.sin(bearingRad)
                            val northM = snap.displacementM * kotlin.math.cos(bearingRad)
                            loc.onPedestrianDisplacement(
                                local.phantom.companion.core.sensor.nav.PedestrianDisplacement(
                                    eastM = eastM,
                                    northM = northM,
                                    accuracyM = snap.accuracyM,
                                    informative = snap.informative,
                                    atMs = android.os.SystemClock.elapsedRealtime(),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun roadGraphNear(lat: Double, lon: Double): local.phantom.companion.core.sensor.nav.RoadGraph? {
        val pad = 0.01 // ~1,1 км навколо людини — далі вона за такт не піде
        return corridorGraphs.graphFor(
            minLat = lat - pad,
            minLon = lon - pad,
            maxLat = lat + pad,
            maxLon = lon + pad,
            revision = roadMeshStore.revision,
        ) {
            runCatching {
                local.phantom.companion.core.sensor.nav.RoadGraph(
                    roadMeshStore.waysIn(lat - pad, lon - pad, lat + pad, lon + pad),
                )
            }.getOrNull()?.takeIf { it.wayCount > 0 }
        }
    }

    /** Carries the dot through a tunnel, a jammer or a caught spoof; see [locationProvider]. */
    private val blindNav = local.phantom.companion.core.sensor.nav.BlindNavigator(graphNear = ::roadGraphNear)

    private fun navGait(): local.phantom.companion.core.nav.NavGait {
        val m = locator?.motion?.value ?: return local.phantom.companion.core.nav.NavGait.UNKNOWN
        if (m.confidence < GAIT_MIN_CONFIDENCE) return local.phantom.companion.core.nav.NavGait.UNKNOWN
        return when (m.state) {
            local.phantom.companion.core.sensor.nav.KinematicsEngine.MotionState.WALK,
            local.phantom.companion.core.sensor.nav.KinematicsEngine.MotionState.RUN,
            -> local.phantom.companion.core.nav.NavGait.ON_FOOT
            local.phantom.companion.core.sensor.nav.KinematicsEngine.MotionState.CYCLE ->
                local.phantom.companion.core.nav.NavGait.CYCLING
            local.phantom.companion.core.sensor.nav.KinematicsEngine.MotionState.DRIVE,
            local.phantom.companion.core.sensor.nav.KinematicsEngine.MotionState.TRAIN,
            local.phantom.companion.core.sensor.nav.KinematicsEngine.MotionState.FLIGHT,
            -> local.phantom.companion.core.nav.NavGait.DRIVING
            // STILL keeps the route's own profile: a body at rest is not evidence.
            else -> local.phantom.companion.core.nav.NavGait.UNKNOWN
        }
    }

    /**
     * ZERO-FAIL — the flight recorder for the field test.
     *
     * Lives under `getExternalFilesDir` (still app-private, still wiped on
     * uninstall) rather than `filesDir` for one reason: after the drive the
     * operator can pull it with a plain
     * `adb pull /sdcard/Android/data/<pkg>/files/blackbox/blackbox.log`,
     * with no root and no debug bridge into the app's internal storage. An
     * autopsy you cannot get off the device is not an autopsy.
     *
     * Falls back to internal storage when external is unavailable (no SD
     * emulation, storage ejected) — a recorder that refuses to record because
     * its preferred directory is missing is exactly the failure it exists to
     * prevent.
     */
    val blackBox: local.phantom.companion.core.sensor.nav.BlackBox by lazy {
        val dir = java.io.File(
            applicationContext.getExternalFilesDir(null) ?: applicationContext.filesDir,
            "blackbox",
        )
        local.phantom.companion.core.sensor.nav.BlackBox(
            dir = dir,
            thermalStatus = {
                runCatching {
                    (applicationContext.getSystemService(Context.POWER_SERVICE)
                        as? android.os.PowerManager)?.currentThermalStatus
                }.getOrNull()
            },
            batteryTempC = {
                runCatching {
                    // The sticky broadcast: no receiver stays registered, and the
                    // read happens on the recorder's IO thread, never on a hot path.
                    applicationContext.registerReceiver(
                        null,
                        android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED),
                    )?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                        ?.takeIf { it != Int.MIN_VALUE }
                        // Reported in tenths of a degree.
                        ?.let { it / 10f }
                }.getOrNull()
            },
        )
    }

    /**
     * Raise the GNSS chip to navigation grade (1 Hz, high accuracy) for the
     * duration of a drive, and let it fall back to the ambient rate after. Held
     * by the guidance state, not by any screen — the operator racking the camera
     * up to the chat does not end his journey.
     */
    fun setNavigationGrade(enabled: Boolean) {
        sensorAdapter?.setNavigationGrade(enabled)
    }

    /**
     * Труба для трьох щаблів темпу. Без неї планувальник щаблів існує, має
     * тести — і НЕ ДОХОДИТЬ до чипа: рівно та найтихіша форма мертвого коду,
     * якою сьогодні виявився й лічильник шляху з порожнім типовим параметром.
     */
    fun setCadence(cadence: local.phantom.companion.core.sensor.sources.NavCadence) {
        sensorAdapter?.setCadence(cadence)
    }

    /**
     * LEVIATHAN-5 — one instant of tactical truth for `map.analyze`. Every
     * field read from the process-scoped singletons; an instrument that is
     * dark stays null (the tool words that honestly). The shelter lookup is
     * offline-first ([ShelterRepository]'s cache) and skipped entirely
     * without a fix.
     */
    suspend fun assembleTacticalSnapshot(): local.phantom.companion.core.tool.impl.MapAnalyzeTool.Snapshot {
        val fix = locationProvider.current()
        val trust = locator?.trust?.value
        val motion = locator?.motion?.value
        val g = navEngine.guidance.state.value
        val progress = (g as? local.phantom.companion.core.nav.GuidanceState.Guiding)?.progress
        val route = activeRoutePoints.value
        val layers = runCatching {
            spatialLayerRepository.list(null)
                .filter { it.visible && !it.isExpired(System.currentTimeMillis()) }
                .take(6)
                .map { it.spec.title }
        }.getOrDefault(emptyList())
        val shelter = if (fix != null) {
            runCatching {
                (shelterRepository.findNearest(fix.lat, fix.lon)
                    as? local.phantom.companion.core.data.evac.ShelterScanResult.Found)
                    ?.tokens?.firstOrNull()
            }.getOrNull()
        } else null
        return local.phantom.companion.core.tool.impl.MapAnalyzeTool.Snapshot(
            lat = fix?.lat,
            lon = fix?.lon,
            accuracyM = fix?.accuracyM,
            navigationGrade = fix?.isNavigationGrade == true,
            dead = fix?.dead == true,
            trust = trust?.name,
            motionState = motion?.state?.name,
            motionConfidence = motion?.confidence ?: 0f,
            guiding = progress != null,
            remainingDistanceM = progress?.remainingDistanceM,
            remainingDurationS = progress?.remainingDurationS,
            routeDrawn = route != null,
            layers = layers,
            shelterName = shelter?.name,
            shelterDistanceM = shelter?.distanceMeters,
            baroAvailable = locator?.telemetry?.value?.baroAvailable,
        )
    }

    /**
     * ПОЛЬОВА ПОМИЛКА, 23.08 — the physical witnesses behind the gate's per-fix
     * refusals: air pressure and vibration. Process-scoped, and read by both the
     * gate and the map's stationary lock.
     */
    private val bodyWitness = local.phantom.companion.core.sensor.nav.BodyWitness()

    /**
     * true = the body is really moving, false = really still, null = no opinion.
     * The map's [StationaryLock] uses it to veto a release that rests on nothing
     * but the fix's own Doppler speed — which a spoofer supplies for free.
     */
    val bodyMoving: () -> Boolean? = {
        bodyWitness.evidence(android.os.SystemClock.elapsedRealtime()).movingVerdict()
    }

    /**
     * PRECISION ENGINE, Section 4 — the consistency gate on the live position
     * path. It holds every fused fix up against the independent network anchor
     * (and the hAcc-explosion / kinematic / staleness checks) before the display
     * trusts it, and since 23.08 against the two witnesses a transmitter cannot
     * reach: the air in the room and the operator's own vibration. In the normal
     * case it is a pass-through and the pivot is untouched; under a caught spoof
     * it holds the anchored position and the display never renders the teleport.
     * Passive badge ([locator]) and this gate are distinct: the badge never moves
     * the puck, this gate only ever REFUSES a poisoned fix — it never fabricates
     * motion.
     */
    val guardedLocation: local.phantom.companion.core.sensor.nav.GuardedLocationSource? by lazy {
        sensorAdapter?.let { adapter ->
            // A barometer listener of the gate's OWN, deliberately not the badge
            // engine's ([locator]): the display path must not inherit that
            // engine's lifecycle, and a second TYPE_PRESSURE listener at 5 Hz is
            // multiplexed by the sensor hub — it costs a float store per event.
            val baro = local.phantom.companion.core.sensor.sources.BarometerSource(applicationContext)
            baro.start()
            scope.launch {
                baro.hpa.filterNotNull().collect {
                    val now = android.os.SystemClock.elapsedRealtime()
                    bodyWitness.onPressure(it, now)
                    blindNav.onPressure(it, now)
                }
            }
            scope.launch {
                adapter.imuEvents.collect {
                    val now = android.os.SystemClock.elapsedRealtime()
                    bodyWitness.onImu(it, now)
                    blindNav.onImu(it, now)
                }
            }
            local.phantom.companion.core.sensor.nav.GuardedLocationSource(
                fusedFeed = adapter.gpsFeed,
                networkFeed = adapter.networkFeed,
                blackBox = if (local.phantom.companion.Distribution.blackBox) blackBox else null,
                body = { bodyWitness.evidence(it) },
                onJudged = { fused, verdict, now ->
                    if (fused != null) blindNav.onFix(fused, now, distrusted = verdict.spoofed || verdict.refusedFix)
                },
            ).also {
                it.start(scope)
            }
        }
    }

    /**
     * The gate's answer for callers that cannot poll — anything that puts a
     * position on the wire. Touching [guardedLocation] here is what STARTS the
     * gate for a process that never opened the map, so this is never a silent
     * null on a paired-but-unopened phone. Null only when there is no sensor
     * adapter at all; every caller must treat null as a refusal, not as
     * permission to read a provider directly.
     */
    private suspend fun awaitGuardedVerdict(): ConsistencyGate.Verdict? {
        val source = guardedLocation ?: return null
        source.guarded.value?.let { return it }
        return kotlinx.coroutines.withTimeoutOrNull(GATE_WARMUP_WAIT_MS) {
            source.guarded.filterNotNull().first()
        }
    }

    /**
     * The reader for [GuardedLocationSource.banner], which nothing in the tree
     * had. Its sentence goes to the shade, so the operator learns the position
     * is uncertain with the phone in a pocket — the case the map banner cannot
     * cover. Silent channel for «немає сигналу»/«приблизно», alerting channel
     * only for a caught spoof.
     */

    /**
     * Turn voice, process-scoped. It used to live in the Compose host, so
     * guidance fell silent the moment the phone was pocketed or Android Auto
     * took the display — exactly when a driver needs it and cannot look.
     */
    val navSpeaker: local.phantom.companion.core.nav.NavCueSpeaker by lazy {
        val routed = ttsEngine as? local.phantom.companion.core.voice.route.RoutedTts
        // HIGHWAY / voice — the only choke point every GuidanceHub cue
        // passes through, urgent or not, so this is the one place that can
        // hold nav audio focus without touching ordinary chat speech at
        // all: chat calls ttsEngine.speak() directly, never through here.
        val navFocus = local.phantom.companion.core.voice.audio.NavAudioFocus(applicationContext)
        local.phantom.companion.core.nav.NavCueSpeaker(
            scope = scope,
            speak = { text, urgent ->
                runCatching {
                    val norm = local.phantom.companion.core.voice.UaTtsNormalizer.normalize(text)
                    navFocus.withFocus {
                        when {
                            urgent && routed != null -> routed.speakUrgent(norm)
                            routed != null -> routed.speakNavigation(norm)
                            else -> ttsEngine.speak(norm)
                        }
                    }
                }
            },
            stopSpeaking = { runCatching { ttsEngine.stop() } },
        ).also { sp ->
            sp.start()
            scope.launch {
                local.phantom.companion.core.nav.GuidanceHub.cues.collect { sp.submit(it) }
            }
        }
    }

    /** Renders the trusted fused fix without its jitter. See [FixSmoother]. */
    private val displaySmoother = local.phantom.companion.core.sensor.nav.FixSmoother()

    val locationProvider: local.phantom.companion.core.ai.context.LocationContextProvider by lazy {
        val provider = local.phantom.companion.core.ai.context.DefaultLocationContextProvider(
            sensorProvider = {
                // ARCH: PRECISION PIVOT + PRECISION ENGINE §4 — the one place a
                // position enters PHANTOM reads the SYSTEM fused fix, but now
                // GATED by [guardedLocation]: fused is displayed while it agrees
                // with the network anchor, and REFUSED (held on the network) when
                // it does not. The EW `locator` remains a passive badge only.
                // Without a believable sky the dot is carried by [blindNav], with doubt that grows.
                val gv = guardedLocation?.guarded?.value
                val raw = sensorAdapter?.gpsFeed?.value
                // H-02 — the satellites' own clock, if one has been overheard
                // recently. Rides on every rung below because it is a property
                // of the SKY, not of the fix it is stamped on: the chip can be
                // jammed into silence and the last minute's satellite time is
                // still the truest thing the device knows about «now».
                val satNow = sensorAdapter?.satelliteNowMs()
                val blind = blindNav.estimate(android.os.SystemClock.elapsedRealtime())
                when {
                    // No believable sky for a few seconds (tunnel, underground,
                    // jammer, caught spoof): the dot rides the body's own motion
                    // instead of freezing where the sky was last heard.
                    blind != null && local.phantom.companion.core.sensor.nav.BlindNavigator.shows(blind) ->
                        local.phantom.companion.core.ai.context.LocationFix(
                            lat = blind.lat,
                            lon = blind.lon,
                            bearing = blind.bearingDeg,
                            accuracyM = blind.uncertaintyM,
                            speedMps = blind.speedMps,
                            grade = local.phantom.companion.core.ai.context.FixGrade.COARSE,
                            dead = true,
                            gpsTimeMs = satNow,
                        )
                    // Caught spoof: show the gate's network-anchored hold, honest
                    // and coarse, never the fused teleport. dead=true + COARSE make
                    // a drive fail closed on it (guidance refuses > 150 m).
                    gv != null && (gv.spoofed || gv.refusedFix) && gv.hasPosition -> local.phantom.companion.core.ai.context.LocationFix(
                        lat = gv.displayLat,
                        lon = gv.displayLon,
                        bearing = null,
                        accuracyM = gv.displayHAccM,
                        speedMps = null,
                        grade = local.phantom.companion.core.ai.context.FixGrade.COARSE,
                        dead = true,
                        gpsTimeMs = satNow,
                    )
                    // Trusted (or the gate not warmed yet): the fused fix, exactly
                    // as the pivot left it — position, heading and doubt from the
                    // chip, uninflated.
                    raw != null -> {
                        val s = raw
                        val ageMs = android.os.SystemClock.elapsedRealtime() - s.elapsedMs
                        val fresh = s.elapsedMs > 0L && ageMs in 0..RAW_FIX_NAV_FRESH_MS
                        // Trust is the gate's business; RENDERING is this one's.
                        // The chip's raw answer wanders tens of metres a second
                        // under a degraded sky and drawing it verbatim gave the
                        // 2026-08-04 sawtooth. The smoother declines to follow the
                        // unexplained part of a step; it never leads, so a parked
                        // phone stays parked (the pivot's law, intact).
                        val shown = displaySmoother.smooth(s.lat, s.lon, s.speedMps, s.elapsedMs)
                        local.phantom.companion.core.ai.context.LocationFix(
                            lat = shown.lat,
                            lon = shown.lon,
                            bearing = s.bearingDeg,
                            accuracyM = s.accuracyM,
                            speedMps = s.speedMps,
                            // A live fused fix IS navigation-grade; its quality
                            // rides in accuracyM. A fix the chip has not refreshed
                            // within RAW_FIX_NAV_FRESH_MS is stale and drops to
                            // COARSE so a drive fails closed on it.
                            grade = if (fresh) {
                                local.phantom.companion.core.ai.context.FixGrade.NAVIGATION
                            } else {
                                local.phantom.companion.core.ai.context.FixGrade.COARSE
                            },
                            dead = false,
                            gpsTimeMs = satNow,
                        )
                    }
                    // The chip is silent (indoors, jammed, cold) but the gate
                    // still holds a network anchor. Returning null here made
                    // "route from here" impossible without GNSS — the exact
                    // case PHANTOM exists for. Coarse and honest beats absent.
                    gv != null && gv.displayHAccM > 0f -> local.phantom.companion.core.ai.context.LocationFix(
                        lat = gv.displayLat,
                        lon = gv.displayLon,
                        bearing = null,
                        accuracyM = gv.displayHAccM,
                        speedMps = null,
                        grade = local.phantom.companion.core.ai.context.FixGrade.COARSE,
                        dead = true,
                        gpsTimeMs = satNow,
                    )
                    else -> null
                }
            },
            passiveProvider = { lastKnownByProvider(android.location.LocationManager.PASSIVE_PROVIDER) },
            networkProvider = { lastKnownByProvider(android.location.LocationManager.NETWORK_PROVIDER) },
            isGhostMode = {
                contextEngine?.state?.value == local.phantom.companion.core.context.SystemState.GHOST
            },
        )
        // Warm the cache in the background so the first AI prompt on
        // a no-GPS device sees a populated `[LOCATION:...]` instead
        // of `[LOCATION:unknown]`. Touch `guardedLocation` here so the
        // consistency gate runs continuously (banner + per-fix log) from
        // startup, not only when the display lambda is next pulled.
        scope.launch {
            guardedLocation
            // Arm the turn voice too: a lazy nobody touches never speaks, and
            // the car head has no composition to touch it from.
            runCatching { navSpeaker }
        }
        provider
    }

    val breadcrumbTracker: local.phantom.companion.core.nav.BreadcrumbTracker by lazy {
        local.phantom.companion.core.nav.BreadcrumbTracker()
    }

    val sentinelFenceEngine: local.phantom.companion.core.nav.SentinelFenceEngine by lazy {
        local.phantom.companion.core.nav.SentinelFenceEngine()
    }

    val shelterVectorEngine: local.phantom.companion.core.nav.ShelterVectorEngine by lazy {
        local.phantom.companion.core.nav.ShelterVectorEngine()
    }

    val atmosphericRadarEngine: local.phantom.companion.core.nav.AtmosphericRadarEngine by lazy {
        local.phantom.companion.core.nav.AtmosphericRadarEngine()
    }

    val placeContextEngine: local.phantom.companion.core.nav.PlaceContextEngine by lazy {
        local.phantom.companion.core.nav.PlaceContextEngine()
    }

    val spatialSearchEngine: local.phantom.companion.core.nav.SpatialSearchEngine by lazy {
        local.phantom.companion.core.nav.SpatialSearchEngine()
    }

    /**
     * W4-2 — read-side accessor for the latest captured `ScreenContext`
     * gated by [VoicePreferences.screenAwarenessEnabled]. The
     * AccessibilityService writes into the same global
     * `ScreenContextStore`; if the operator turns the in-app toggle
     * off, the AI prompt enrichment path returns null even when the
     * OS-level Accessibility grant is still present.
     */
    /**
     * Phase 4 adaptive surface — operator's installed launchable apps,
     * cached for 60 s so the PackageManager scan doesn't run per
     * prompt. AI receives this list inside the `[APPS:]` prompt block
     * and emits exact package names; no static alias map needed.
     */
    private var cachedInstalledApps: List<local.phantom.companion.core.ai.context.InstalledApp> = emptyList()
    private var cachedInstalledAppsAt: Long = 0L
    val installedAppsProvider: local.phantom.companion.core.ai.context.InstalledAppsProvider =
        local.phantom.companion.core.ai.context.InstalledAppsProvider {
            val nowMs = System.currentTimeMillis()
            if (cachedInstalledApps.isNotEmpty() && (nowMs - cachedInstalledAppsAt) < 60_000L) {
                return@InstalledAppsProvider cachedInstalledApps
            }
            val pm = applicationContext.packageManager
            val launcherIntent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            val resolved = runCatching {
                pm.queryIntentActivities(launcherIntent, 0)
            }.getOrDefault(emptyList())
            val list = resolved
                .mapNotNull { info ->
                    val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                    val label = runCatching { info.loadLabel(pm).toString() }.getOrNull()
                        ?: pkg.substringAfterLast('.')
                    local.phantom.companion.core.ai.context.InstalledApp(label = label, packageName = pkg)
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
            cachedInstalledApps = list
            cachedInstalledAppsAt = nowMs
            list
        }

    /** R5.1 — the same catalogue as label→package pairs for the tool layer
     *  (`launch` name resolution + `apps.list`), which stays independent of
     *  `:core-ai`'s InstalledApp type. */
    val installedAppPairs: () -> List<Pair<String, String>> = {
        installedAppsProvider.current().map { it.label to it.packageName }
    }

    val screenContextProvider: () -> local.phantom.companion.feature.godmode.engine.ScreenContext? = {
        if (voicePrefs.screenAwarenessEnabled.value) {
            local.phantom.companion.feature.godmode.engine.ScreenContextStore.current()
        } else null
    }

    /**
     * W4-3 — read-side accessor for the latest selected-text capture
     * gated by the same `screenAwarenessEnabled` toggle that gates
     * `[SCREEN:…]`. Both belong to the AccessibilityService capture
     * surface; both must be opt-in or opt-out together.
     */
    val selectedTextProvider: () -> local.phantom.companion.feature.godmode.engine.SelectedText? = {
        if (voicePrefs.screenAwarenessEnabled.value) {
            local.phantom.companion.feature.godmode.engine.SelectedTextRouter.current()
        } else null
    }

    /**
     * W4-3 — single-shot consume signal. StreamViewModel calls this
     * right after the `[ОПЕРАТОР ВИБРАВ:"…"]` line is appended to
     * the next AI prompt's enrichedContext, so subsequent unrelated
     * turns don't keep re-injecting the same selection.
     */
    val onSelectionConsumed: () -> Unit = {
        local.phantom.companion.feature.godmode.engine.SelectedTextRouter.clear()
    }

    /**
     * W4-4 — sink for `app-action` widget dispatches. StreamViewModel
     * calls this with a fresh widgetId + parsed action sequence; the
     * registry runs the dispatcher backed by the live
     * AccessibilityService and emits trace events that the
     * AppActionWidget Composable subscribes to via [AppActionTraceBus].
     *
     * The authorizer is wired once in [init] so the safety policy is
     * already armed by the time the operator's first prompt lands.
     * Sensitive-app lookup reuses the canonical
     * [SensitiveAppDenylist] (Phase 1.5-C) so banking / password /
     * OTP / wallet packages stay denied across all gesture surfaces.
     * Vault-locked is a stub returning false — Phase 4 wires the
     * real vault clamp signal.
     */
    val appActionDispatcher: (
        String,
        List<local.phantom.companion.feature.godmode.engine.AppAction>,
    ) -> Unit = { widgetId, actions ->
        local.phantom.companion.feature.godmode.service
            .AccessibilityServiceActionRegistry
            .requestDispatch(widgetId, actions, scope)
    }

    /**
     * W4-5 — Notification triage data layer. The
     * NotificationListenerService writes routine notifications into
     * this repository for the daily 9am digest; the scheduler reads
     * + flushes; the StreamViewModel surfaces both Important pushes
     * (immediate Notice) and digest text (one Notice per day).
     */
    val notificationDigestRepository:
        local.phantom.companion.core.data.notifications.NotificationDigestRepository by lazy {
            local.phantom.companion.core.data.notifications.NotificationDigestRepository(
                database.notificationDigestDao(),
            )
        }

    /**
     * W4-5 — hot stream of formatted digest lines fired by the
     * scheduler at the operator's 9am local time. StreamViewModel
     * consumes this directly; the digest scheduler is the only
     * producer.
     */
    val notificationDigestFlow get() = events.notificationDigestFlow

    val notificationDigestScheduler: NotificationDigestScheduler by lazy {
        NotificationDigestScheduler(
            repository = notificationDigestRepository,
            emitter = { digestText -> notificationDigestFlow.tryEmit(digestText); Unit },
            lastEmitMsGetter = { voicePrefs.lastDigestEmitAtMs() },
            lastEmitMsSetter = { ts -> voicePrefs.markDigestEmitted(ts) },
            appLabel = { pkg ->
                runCatching {
                    val pm = applicationContext.packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrNull()
            },
        )
    }

    @Suppress("MissingPermission")
    private fun lastKnownByProvider(providerName: String): local.phantom.companion.core.ai.context.LocationFix? = runCatching {
        val mgr = applicationContext.getSystemService(Context.LOCATION_SERVICE)
            as? android.location.LocationManager ?: return@runCatching null
        mgr.getLastKnownLocation(providerName)?.let {
            local.phantom.companion.core.ai.context.LocationFix(
                lat = it.latitude,
                lon = it.longitude,
                bearing = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && it.hasBearing()) it.bearing else null,
                accuracyM = if (it.hasAccuracy()) it.accuracy else null,
                speedMps = if (it.hasSpeed()) it.speed else null,
            )
        }
    }.onFailure {
        Log.w("AppContainer", "lastKnownLocation($providerName) failed: ${it.message}")
    }.getOrNull()

    /** R1.1 — raw last-known [android.location.Location] (carries fix time for age). */
    @Suppress("MissingPermission")
    private fun rawLastKnown(providerName: String): android.location.Location? = runCatching {
        val mgr = applicationContext.getSystemService(Context.LOCATION_SERVICE)
            as? android.location.LocationManager ?: return@runCatching null
        mgr.getLastKnownLocation(providerName)
    }.getOrNull()

    /** R1.1 — on-device reverse geocoding shared by `location.current` and the ambient header. */
    val reverseGeocoder: ReverseGeocoder by lazy { ReverseGeocoder(applicationContext) }

    /**
     * R1.1 — rich snapshot for the `location.current` tool. Rungs:
     * the consistency gate's verdict → NETWORK last-known → the W1-4 chain
     * (IP-geo/cached, coords only). Null when every rung is silent — the tool
     * answers with an honest `no_fix` error instead of a fabricated position,
     * and with `position_refused` when the gate caught a spoof.
     */
    val locationSnapshotProvider: () -> local.phantom.companion.core.tool.impl.LocationSnapshot? = provider@{
        // PRECISION ENGINE §4 — this tool used to read `sensorAdapter.gpsFeed`
        // RAW, with the EW verdict riding along as a text annotation in `source`.
        // Under a caught spoof that made the assistant state the spoofed
        // coordinates as fact and mention the doubt as a footnote. The gate now
        // decides: it hands over a position, or it hands over a refusal.
        // Touching `guardedLocation` also STARTS the gate on a process that
        // never opened the map.
        guardedLocation?.guarded?.value?.let { v ->
            if (v.status == ConsistencyGate.Status.SPOOFED || !v.hasPosition) {
                return@provider local.phantom.companion.core.tool.impl.LocationSnapshot(
                    lat = null,
                    lon = null,
                    source = "gate/${v.status.name.lowercase()}",
                    refusedUa = v.message ?: ConsistencyGate.NO_FIX_UNKNOWN_UA,
                    refusedEn = v.messageEn ?: ConsistencyGate.NO_FIX_UNKNOWN_EN,
                )
            }
            // Speed and bearing belong to the fused sample and mean nothing on a
            // network hold — they are carried only when the gate is showing it.
            val live = sensorAdapter?.gpsFeed?.value
                ?.takeIf { v.source == ConsistencyGate.Source.FUSED }
            val ageMs = live
                ?.takeIf { it.elapsedMs > 0L }
                ?.let { (android.os.SystemClock.elapsedRealtime() - it.elapsedMs).coerceAtLeast(0L) }
            return@provider local.phantom.companion.core.tool.impl.LocationSnapshot(
                lat = v.displayLat,
                lon = v.displayLon,
                accuracyM = v.displayHAccM,
                speedMps = live?.speedMps,
                bearingDeg = live?.bearingDeg,
                ageMs = ageMs,
                source = "gate/${v.source.name.lowercase()}",
                advisoryUa = v.message,
            )
        }
        // No gate at all (sensors off in this build). PASSIVE_PROVIDER is gone
        // from this chain on purpose: it hands back whatever GPS last said,
        // which is the exact fix the gate exists to refuse. NETWORK last-known
        // is the gate's own independent witness, so it stays.
        val network = android.location.LocationManager.NETWORK_PROVIDER
        rawLastKnown(network)?.let { loc ->
            return@provider local.phantom.companion.core.tool.impl.LocationSnapshot(
                lat = loc.latitude,
                lon = loc.longitude,
                accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
                speedMps = if (loc.hasSpeed()) loc.speed else null,
                bearingDeg = if (loc.hasBearing()) loc.bearing else null,
                ageMs = (System.currentTimeMillis() - loc.time).coerceAtLeast(0L),
                source = "last-known/$network",
            )
        }
        locationProvider.current()?.let { fix ->
            local.phantom.companion.core.tool.impl.LocationSnapshot(
                lat = fix.lat,
                lon = fix.lon,
                accuracyM = fix.accuracyM,
                speedMps = fix.speedMps,
                bearingDeg = fix.bearing,
                ageMs = null,
                source = "ip-geo",
            )
        }
    }

    // Phase C — ContextEngine (autonomous state inference). Gated until verified.
    val contextEngine: local.phantom.companion.core.context.ContextEngine? by lazy {
        if (local.phantom.companion.BuildConfig.ENABLE_CONTEXT) {
            val detectedSoundsBuffer = mutableListOf<String>()

            // Collect sounds in background as they arrive
            scope.launch {
                voiceManager.classificationResults.collect { result ->
                    synchronized(detectedSoundsBuffer) {
                        detectedSoundsBuffer.add(result.label)
                        // Keep only last 20 to avoid memory bloat
                        if (detectedSoundsBuffer.size > 20) detectedSoundsBuffer.removeAt(0)
                    }
                }
            }

            local.phantom.companion.core.context.ContextEngine(
                tree = local.phantom.companion.core.context.decision.DecisionTree(
                    listOf(
                        local.phantom.companion.core.context.nodes.TimeOfDayNode(),
                        local.phantom.companion.core.context.nodes.MotionNode(),
                        local.phantom.companion.core.context.nodes.AmbientAudioNode(),
                        local.phantom.companion.core.context.nodes.ActiveAppNode(),
                    )
                ),
                frameBuilder = local.phantom.companion.core.context.ContextFrameBuilder(
                    bus = nervousBus,
                    deviceId = applicationContext.packageName,
                    profileIdProvider = { null },
                ),
            ).also { engine ->
                engine.start(scope) {
                    val now = System.currentTimeMillis()
                    val cal = java.util.Calendar.getInstance()
                    val sensorBatch = sensorAdapter?.batches?.replayCache?.lastOrNull()
                    val accelMag = sensorBatch?.imu?.let { imu ->
                        kotlin.math.sqrt(imu.accelX * imu.accelX + imu.accelY * imu.accelY + imu.accelZ * imu.accelZ) - 9.81f
                    }?.let { kotlin.math.abs(it) }

                    // Battery status
                    val ifilter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
                    val batteryStatus = applicationContext.registerReceiver(null, ifilter)
                    val level = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                    val batteryPct = if (level != -1 && scale != -1) (level * 100 / scale.toFloat()).toInt() else null
                    val status = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
                    val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                                     status == android.os.BatteryManager.BATTERY_STATUS_FULL

                    val sounds = synchronized(detectedSoundsBuffer) {
                        val list = detectedSoundsBuffer.toList()
                        detectedSoundsBuffer.clear()
                        list
                    }

                    local.phantom.companion.core.context.ContextInput(
                        nowMs = now,
                        localHour = cal.get(java.util.Calendar.HOUR_OF_DAY),
                        localDayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK).let { dow ->
                            ((dow + 5) % 7) + 1
                        },
                        accelMagnitude = accelMag,
                        micRmsDb = sensorBatch?.micRmsDb,
                        batteryLevel = batteryPct,
                        isCharging = isCharging,
                        // QA-fix 2026-07-17 — the REAL package, not the first
                        // line of the raw dump; and PHANTOM watching his own
                        // screen is a mirror, not «занятість» — own package
                        // reads as no foreign app active.
                        activeAppPackage = taskerEngine.currentScreenPackage
                            ?.takeUnless { it.startsWith("local.phantom.companion") },
                        detectedSounds = sounds,
                    )
                }
                scope.launch {
                    engine.latestFrame?.collect { frame ->
                        if (frame != null) {
                            runCatching {
                                foresightEngine.evaluate(frame)
                            }
                        }
                    }
                }
            }
        } else {
            null
        }
    }

    // companion-v2-phase-0 — System Pulse aggregator. Single source of truth
    // for the SystemPulseHeader: subscribes to ContextEngine.snapshots and
    // MobileSensorAdapter.batches when those modules are enabled, plus tracks
    // AI/link availability from cheap providers.
    val systemPulse: SystemPulseRepository by lazy {
        SystemPulseRepository(
            contextEngine = contextEngine,
            sensorAdapter = sensorAdapter,
            aiAvailable = { aiKeyStore.geminiKey.value?.isNotBlank() == true },
            linkConnected = { link.connected.value == local.phantom.companion.net.LinkStatus.Connected },
        ).also { it.start(scope) }
    }

    // Tier 4 — God-Tier SDK & Ambient
    val lifeLog: LifeLogArchive by lazy { LifeLogArchive(database.lifeLogDao(), onnxEmbeddingHelper) }
    // Tier-2 long-term memory — durable operator facts, int8 vectors, dedup+decay.
    val memoryFactStore: local.phantom.companion.core.data.memory.MemoryFactStore by lazy {
        local.phantom.companion.core.data.memory.MemoryFactStore(database.memoryFactDao())
    }

    val phantomVoice: local.phantom.companion.self.PhantomVoice by lazy {
        local.phantom.companion.self.PhantomVoice(this)
    }

    /** «Подзвонити PHANTOM»: живий голос (Gemini Live), інакше покроково. */
    val phantomCall: local.phantom.companion.self.PhantomCall by lazy {
        local.phantom.companion.self.PhantomCall(this)
    }

    val commitmentStore: local.phantom.companion.self.CommitmentStore by lazy {
        local.phantom.companion.self.SqliteCommitmentStore(applicationContext)
    }

    val commitments: local.phantom.companion.self.CommitmentService by lazy {
        local.phantom.companion.self.PhantomSelfAndroid.buildCommitments(
            context = applicationContext,
            store = commitmentStore,
            peers = peers,
        )
    }

    val phantomSelf: local.phantom.companion.self.PhantomSelf by lazy {
        local.phantom.companion.self.PhantomSelfAndroid.build(
            applicationContext,
            memoryFactStore,
            memoryRuntime.ledger,
            onChange = { memoryRuntime.changed() },
            commitments = commitments,
            reminders = local.phantom.companion.self.ReminderSink { text, atMs ->
                reminderStore.add(text = text, triggerAtMs = atMs)
            },
        ) { aiRouter }
    }

    /**
     * Памʼять PHANTOM на телефоні — зашифрована репліка ledger-а ПК (`core-memory`)
     * і її синк зі спареним ПК (docs/memory/SYNC.md) тим самим каналом, що й решта
     * розмов із ПК: мережа поруч, а без неї — реле; TLS приколото до ПК.
     */
    val memoryRuntime: local.phantom.companion.self.MemoryRuntime by lazy {
        local.phantom.companion.self.MemoryRuntime.build(
            applicationContext,
            device = store.load()?.deviceId?.let { "phone-$it" } ?: "phone",
            transport = object : local.phantom.companion.core.memory.MemorySync.Transport {
                override suspend fun pull(since: Long, limit: Int) = api.memoryChanges(since, limit)
                override suspend fun push(body: String) = api.pushMemoryChanges(body)
            },
            paired = { store.load() != null },
        ).also { rt ->
            val connected = kotlinx.coroutines.flow.MutableStateFlow(false)
            scope.launch {
                link.connected.collect { connected.value = it == local.phantom.companion.net.LinkStatus.Connected }
            }
            rt.start(scope, connected)
        }
    }

    // Люди й речі, про яких ці факти: сюди складається «Марта», щоб факт про
    // сестру і факт про Марту стояли на одній людині, а не поруч.
    val memoryEntityStore: local.phantom.companion.core.data.memory.MemoryEntityStore by lazy {
        local.phantom.companion.core.data.memory.MemoryEntityStore(database.memoryEntityDao())
    }

    // Tier-2 WRITE-path: fold the recent conversation into durable facts. Generate
    // is LOCAL-ONLY (never burns cloud quota / works offline); vectors are the same
    // int8 contract recall uses. Consolidation runs off the hot path — see
    // [consolidateMemory], fired on app-background + a slow idle loop.
    /**
     * Distils facts. Prefers the LOCAL brain — learning about a person should
     * not leave the device, and should not spend cloud quota. But local-only
     * meant a phone without Gemma learned nothing at all, even with a working
     * cloud route, so the router is the fallback rather than a refusal.
     * Only the person's own brains (paired PC, own key): the Nightpost server's
     * small daily quota is for their questions, not for background work.
     */
    private val localGenerate: suspend (String) -> String = { prompt ->
        val sb = StringBuilder()
        var finalText: String? = null
        ownBrains.complete(
            AIIntent.Text(prompt = prompt, systemPrompt = null, temperature = 0.2f, maxTokens = 512),
        ).collect { chunk ->
            when (chunk) {
                is local.phantom.companion.core.ai.AIChunk.Token -> sb.append(chunk.text)
                is local.phantom.companion.core.ai.AIChunk.Final -> finalText = chunk.text
                else -> Unit
            }
        }
        finalText?.takeIf { it.isNotBlank() } ?: sb.toString()
    }

    val memoryConsolidator: local.phantom.companion.core.data.memory.MemoryConsolidator by lazy {
        local.phantom.companion.core.data.memory.MemoryConsolidator(
            store = memoryFactStore,
            generate = localGenerate,
            entities = memoryEntityStore,
            // Null when there is no model: the fact is still stored, just
            // without a vector, and stays findable by recency.
            embed = { text ->
                if (onnxEmbeddingHelper.isModelReady()) {
                    runCatching { onnxEmbeddingHelper.getInt8Embedding(text) }.getOrNull()
                } else {
                    null
                }
            },
        )
    }

    /**
     * Горизонт згадування — єдине місце, де платність торкається памʼяті.
     * Складання фактів [consolidateMemory] під замком триває як завжди: інакше
     * купівля ключа не повернула б прожитого, а лише почала б рахунок з нуля.
     */
    fun memoryRecallCutoffMs(nowMs: Long = System.currentTimeMillis()): Long =
        local.phantom.companion.license.MemoryHorizon.cutoffMs(
            nowMs = nowMs,
            entitled = license.has(LicenseFeature.MemoryLongterm),
        )

    private val consolidationRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val lastConsolidatedCreatedAt = java.util.concurrent.atomic.AtomicLong(0L)
    private val lastConsolidatedAtMs = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * Fold the recent conversation into Tier-2 facts + run storage hygiene. Fully
     * fail-safe — every failure is swallowed, so a turn is never crashed by memory
     * work. Gated on a real embedding model (a hashed vector would poison the int8
     * space) and de-duplicated by the newest turn already consolidated. When
     * [requireIdleMs] is set, a still-live conversation (newest turn younger than
     * that) is skipped so the idle loop never grabs a mid-sentence exchange.
     */
    suspend fun consolidateMemory(
        requireIdleMs: Long? = null,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (!consolidationRunning.compareAndSet(false, true)) return
        try {
            // Distilling on the CLOUD costs a request and a slice of the
            // per-minute quota. On the local brain it costs nothing, so only the
            // cloud path is throttled — learning after every turn is right when
            // it is free, and wasteful when it competes with the operator's own
            // questions.
            val since = nowMs - lastConsolidatedAtMs.get()
            if (since < CLOUD_CONSOLIDATE_MIN_GAP_MS) return
            // NOT gated on the embedding model any more. `remember` accepts a
            // null vector and stores the fact anyway; recall then ranks it by
            // recency and reinforcement. Refusing to learn at all because the
            // 90 MB MiniLM is absent is how PHANTOM ended up remembering
            // nothing on a device that had everything else.
            val recent = runCatching { streamRepository.lastN(40) }.getOrNull().orEmpty()
            val turns = recent
                .filter {
                    it.kind == local.phantom.companion.core.data.stream.StreamItemEntity.Kind.TEXT &&
                        !it.streaming && !it.textContent.isNullOrBlank() &&
                        (it.role == local.phantom.companion.core.data.stream.StreamItemEntity.Role.USER ||
                            it.role == local.phantom.companion.core.data.stream.StreamItemEntity.Role.ASSISTANT)
                }
                .sortedBy { it.createdAt }
            if (turns.isEmpty()) return
            val newest = turns.last().createdAt
            if (newest <= lastConsolidatedCreatedAt.get()) return
            if (requireIdleMs != null && nowMs - newest < requireIdleMs) return
            val dialogue = turns.joinToString("\n") { t ->
                val who = if (t.role == local.phantom.companion.core.data.stream.StreamItemEntity.Role.USER) "Оператор" else "Nightpost"
                "$who: ${t.textContent!!.trim()}"
            }
            runCatching { memoryConsolidator.consolidate(dialogue, nowMs) }
            lastConsolidatedCreatedAt.set(newest)
            lastConsolidatedAtMs.set(nowMs)
            runCatching { memoryFactStore.decayAndPrune(nowMs) }
        } catch (t: Throwable) {
            Log.w("AppContainer", "memory consolidation failed", t)
        } finally {
            consolidationRunning.set(false)
        }
    }

    // ── DATA-SYSTEMS — the typed stores behind the chat widgets ──────────────
    val calendarStore: local.phantom.companion.core.data.calendar.CalendarStore by lazy {
        local.phantom.companion.core.data.calendar.CalendarStore(database.calendarEventDao())
    }
    val taskStore: local.phantom.companion.core.data.tasks.TaskStore by lazy {
        local.phantom.companion.core.data.tasks.TaskStore(database.taskDao())
    }
    val reminderStore: local.phantom.companion.core.data.reminders.ReminderStore by lazy {
        local.phantom.companion.core.data.reminders.ReminderStore(database.reminderDao())
    }
    val drawnLineStore: local.phantom.companion.core.data.places.DrawnLineStore by lazy {
        local.phantom.companion.core.data.places.DrawnLineStore(database.drawnLineDao())
    }
    val savedPlaceStore: local.phantom.companion.core.data.places.SavedPlaceStore by lazy {
        local.phantom.companion.core.data.places.SavedPlaceStore(database.savedPlaceDao()).also { store ->
            scope.launch {
                runCatching {
                    local.phantom.companion.core.data.places.SavedPlaceMigration(vaultStore, store).run()
                }
            }
        }
    }


    // Tier 2 — GodMode Engines
    /**
     * W4-7 — pattern repository + detector backing TaskerEngine's
     * promotion from in-memory hard-coded triggers to a Room-persisted
     * observation pipeline. Surfaces insights once a (kind, key)
     * bucket crosses 3 occurrences within the rolling 7-day window.
     */
    val patternRepository:
        local.phantom.companion.core.data.patterns.PatternRepository by lazy {
            local.phantom.companion.core.data.patterns.PatternRepository(database.patternDao())
        }

    val patternDetector:
        local.phantom.companion.feature.godmode.engine.PatternDetector by lazy {
            local.phantom.companion.feature.godmode.engine.PatternDetector(
                repository = patternRepository,
            )
        }

    val taskerEngine: TaskerEngine by lazy {
        TaskerEngine(
            aiRouter = aiRouter,
            scope = scope,
            patternDetector = patternDetector,
            isScreenAwarenessEnabled = { voicePrefs.screenAwarenessEnabled.value },
        )
    }

    /**
     * Everything the graph must DO at startup, as opposed to everything it
     * must BE. This used to be an `init` block, which meant every subsystem it
     * touches (Keystore prefs, ktor engines, Room, the AI routes, the sensor
     * and voice stacks) was resolved on whatever thread constructed the
     * container — always the main thread, inside Application.onCreate.
     * PhantomApp now calls this off the main thread; nothing here needs to
     * finish before the first frame.
     */

    // Default-off proactive seat: assembles the unprompted-voice engine from
    // existing app-scope singletons and launches it — but only when the flag is
    // on. With PROACTIVE_VOICE_ENABLED=false this returns before touching a
    // single dependency, so nothing here runs today.


    internal fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        runCatching { content }.getOrNull()

    // -------------------------------------------------------------------------
    // Symbiote foundation — Tier 14+ per docs/SYMBIOTE_VISION.md
    // -------------------------------------------------------------------------
    //
    // Constitutional core is *not* lazy: every Tier 14+ feature audits its
    // manifest at registration time, so the registry must already exist by
    // the time the first symbiote sub-module is touched. Sub-modules below
    // are still lazy so their hot-path objects do not pay cold-launch cost
    // when the operator never turns them on.
    val constitutionRegistry: local.phantom.companion.core.constitution.ConstitutionRegistry =
        local.phantom.companion.core.constitution.ConstitutionRegistry()





    val hapticEmitter: local.phantom.companion.core.embodiment.HapticEmitter by lazy {
        runCatching {
            local.phantom.companion.core.embodiment.AndroidHapticEmitter(applicationContext)
        }.getOrElse { t ->
            Log.e("AppContainer", "hapticEmitter init failed — falling back to null", t)
            errorBus.emit(
                source = "HapticEmitter",
                severity = local.phantom.companion.core.data.errors.PhantomError.Severity.Warn,
                message = "haptic недоступний — вібрація вимкнена",
                cause = t,
            )
            local.phantom.companion.core.embodiment.NullHapticEmitter()
        }
    }




    val cognitiveLoad: local.phantom.companion.core.bioloop.CognitiveLoadEstimator by lazy {
        local.phantom.companion.core.bioloop.CognitiveLoadEstimator()
    }

    /** Tier 23 — Mental Park + working memory + anchor router. */
    val mentalPark: local.phantom.companion.core.offload.MentalPark by lazy {
        local.phantom.companion.core.offload.MentalPark()
    }





    /** Tier 15 — predictive symbiosis. */
    val doubtAmplifier: local.phantom.companion.core.predict.DoubtAmplifier by lazy {
        local.phantom.companion.core.predict.DoubtAmplifier()
    }

    val convictionScheduler: local.phantom.companion.core.predict.ConvictionScheduler by lazy {
        local.phantom.companion.core.predict.ConvictionScheduler()
    }


    val shimmerEngine: local.phantom.companion.core.predict.ShimmerEngine by lazy {
        local.phantom.companion.core.predict.ShimmerEngine()
    }

    val symbioteSelfAudit: local.phantom.companion.core.constitution.SelfAuditScheduler by lazy {
        local.phantom.companion.core.constitution.SelfAuditScheduler(
            manifestsProvider = { constitutionRegistry.snapshot() },
            auditor = local.phantom.companion.core.constitution.AxiomAuditor(),
            sunset = local.phantom.companion.core.constitution.SunsetScheduler(
                lastActivity = { _ ->
                    // Wire the real activity log in once Tier 23 lands
                    // its persistence layer. For now everything is
                    // considered "freshly active" so nothing sunsets.
                    System.currentTimeMillis()
                },
            ),
        )
    }















    val symbioteRuntime: SymbioteRuntime by lazy {
        SymbioteRuntime(
            prefs = symbiotePrefs,
            systemState = contextEngine?.state
                ?: kotlinx.coroutines.flow.MutableStateFlow(
                    local.phantom.companion.core.context.SystemState.GHOST,
                ),
            hapticEmitter = hapticEmitter,
        )
    }

    /**
     * Тема так, як її бачить веб-месенджер. Пише лише WebMessengerHost (у нього
     * і тільки в нього є розвʼязана палітра з композиції), читають двоє:
     * `PhantomBridge.getTheme()` на запит і поштовх `window.__phantomTheme`.
     */
    val webTheme = kotlinx.coroutines.flow.MutableStateFlow("night")

    /** Фаза доби для нового бандла (`night|dawn|morning|day|golden|dusk`); пише той самий
     *  WebMessengerHost, читає `PhantomBridge.getThemePhase()`. */
    val webThemePhase = kotlinx.coroutines.flow.MutableStateFlow("night")

    /** Кадр сонця `від:до:частка` (порожньо — ручна тема чи полюс); читає `PhantomBridge.getSunLook()`. */
    val webSunLook = kotlinx.coroutines.flow.MutableStateFlow("")

    /** Вибір теми, зроблений у веб-месенджері (`PhantomBridge.setThemeMode`):
     *  [local.phantom.companion.core.design.theme.ThemeMode]. Забирає
     *  MainActivity і віддає PhantomViewModel.setThemeMode — тій самій дорозі,
     *  що й рідний Профіль. */
    val themeModeRequest = kotlinx.coroutines.flow.MutableStateFlow<local.phantom.companion.core.design.theme.ThemeMode?>(null)

    // ── Phase 1.E — Tool Registry ─────────────────────────────────────────────

    val toolPermissionStore: local.phantom.companion.core.tool.permission.EncryptedToolPermissionStore by lazy {
        local.phantom.companion.core.tool.permission.EncryptedToolPermissionStore(applicationContext)
    }

    val toolConsentPrompter: local.phantom.companion.core.tool.permission.ToolConsentPrompter by lazy {
        local.phantom.companion.core.tool.permission.ToolConsentPrompter()
    }

    val toolPermissionGate: local.phantom.companion.core.tool.permission.ToolPermissionGate by lazy {
        local.phantom.companion.core.tool.permission.ToolPermissionGate(
            store = toolPermissionStore,
            ask = { toolConsentPrompter.request(it) },
        )
    }

    val toolDecisionDiary: local.phantom.companion.core.tool.diary.DecisionDiary by lazy {
        local.phantom.companion.core.tool.diary.RoomDecisionDiary(database.toolAuditDao())
    }

    val toolHostAllowlist: local.phantom.companion.core.tool.impl.HostAllowlist by lazy {
        local.phantom.companion.core.tool.impl.HostAllowlist().also {
            // R1.3 — default senses of the web (plan §R1.3). Apex entries
            // cover subdomains (HostAllowlist subdomain matching); every
            // call still passes ToolPermissionGate, and the operator can
            // revoke hosts in Settings.
            it.approve("open-meteo.com")
            it.approve("wikipedia.org")
            it.approve("api.duckduckgo.com")
            it.approve("overpass-api.de")
            // GEO-LAW — geocoding/routing hosts come from the configured
            // endpoint, not a hardcoded demo server; a null config (or a
            // release build with nothing entered in Settings) simply
            // approves nothing, which matches the clients' own honest
            // null-URL failure.
            urlHost(GeoEndpoints.config.geocodeBaseUrl)?.let(it::approve)
            urlHost(GeoEndpoints.config.routingBaseFor("routed-car"))?.let(it::approve)
        }
    }

    /** Bare hostname of a config URL, or null if unset/unparseable. */
    private fun urlHost(url: String?): String? =
        url?.let { runCatching { java.net.URI(it).host }.getOrNull() }

    val termuxInstalled: Boolean by lazy {
        runCatching {
            applicationContext.packageManager.getPackageInfo("com.termux", 0)
            true
        }.getOrDefault(false)
    }

    /** Tool-emitted widget bus — WidgetTool posts here; StreamViewModel subscribes. */
    val toolWidgetEmissions get() = events.toolWidgetEmissions

    /** R6.1 — one non-streaming model turn (system, user) → final text, on
     *  the SAME router the chat uses. Backs the canvas builder passes (and
     *  any future tool that needs a private model turn). */
    val singleTurnComplete: suspend (String, String) -> String =
        { system: String, user: String ->
            val sb = StringBuilder()
            var finalText: String? = null
            aiRouter.complete(
                AIIntent.Text(
                    prompt = user,
                    history = emptyList(),
                    systemPrompt = system,
                    context = null,
                ),
            ).collect { chunk ->
                when (chunk) {
                    is local.phantom.companion.core.ai.AIChunk.Token -> sb.append(chunk.text)
                    is local.phantom.companion.core.ai.AIChunk.Final -> finalText = chunk.text
                    else -> Unit
                }
            }
            finalText?.takeIf { it.isNotBlank() } ?: sb.toString()
        }

    val toolRegistry: local.phantom.companion.core.tool.ToolRegistry by lazy { buildToolRegistry() }

    /** Hands Deck — which one-tap acts this device can actually perform. */
    val directHands: DirectHandsProbe by lazy {
        DirectHandsProbe(appContext = applicationContext, registry = toolRegistry)
    }

    val foresightEngine: local.phantom.companion.core.predict.ForesightEngine by lazy {
        local.phantom.companion.core.predict.ForesightEngine(
            bus = nervousBus,
            deviceId = android.provider.Settings.Secure.getString(
                applicationContext.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "companion_android"
        )
    }

    /** R1.2 — one Open-Meteo client (shared cache) for the widget-hydration
     *  path AND the agentic weather.now / weather.forecast tools. */
    val openMeteoClient: local.phantom.companion.core.ai.external.OpenMeteoClient by lazy {
        local.phantom.companion.core.ai.external.OpenMeteoClient()
    }

    val weatherTool: local.phantom.companion.core.ai.tool.WeatherTool by lazy {
        local.phantom.companion.core.ai.tool.WeatherTool(
            openMeteoClient = openMeteoClient,
            locationProvider = locationProvider
        )
    }


    fun bootSymbioteManifests() {
        val all = local.phantom.companion.core.embodiment.EmbodimentManifests.all +
            local.phantom.companion.core.bioloop.BioloopManifests.all +
            local.phantom.companion.core.offload.OffloadManifests.all +
            local.phantom.companion.core.predict.PredictManifests.all +
            // Phase 0 — feature-stream capabilities self-declare here so
            // the AxiomAuditor sees them at boot. Once any of these grows
            // beyond a one-liner, lift to a feature-stream ManifestList.
            listOf(
                local.phantom.companion.core.constitution.FeatureManifest(
                    id = "widget.interactivity",
                    tier = 0,
                    displayName = "Меню довгого натиску на картці",
                    surfaces = setOf(
                        local.phantom.companion.core.constitution.FeatureManifest.Surface.STREAM,
                    ),
                    sunsetAfterDaysIdle = -1,    // foundational, never sunsets
                ),
                local.phantom.companion.core.constitution.FeatureManifest(
                    id = "reasoning.hidden",
                    tier = 0,
                    displayName = "Прихований хід думки",
                    surfaces = setOf(
                        local.phantom.companion.core.constitution.FeatureManifest.Surface.STREAM,
                    ),
                    sunsetAfterDaysIdle = -1,
                ),
                local.phantom.companion.core.constitution.FeatureManifest(
                    id = "tts.smart",
                    tier = 0,
                    displayName = "Розумний фільтр озвучення",
                    surfaces = setOf(
                        local.phantom.companion.core.constitution.FeatureManifest.Surface.VOICE,
                    ),
                    sunsetAfterDaysIdle = -1,
                ),
                // Phase 1.E — Tool Registry capabilities
                local.phantom.companion.core.constitution.FeatureManifest(
                    id = "tool.registry.dispatch",
                    tier = 1,
                    displayName = "Диспетчер інструментів",
                    erodesOperatorAgency = false,
                    exfiltratesPrivate = false,
                    actsWithoutConsent = false,
                    abusableAgainstWeak = false,
                    sunsetAfterDaysIdle = 90,
                    surfaces = setOf(
                        local.phantom.companion.core.constitution.FeatureManifest.Surface.STREAM,
                    ),
                ),
                local.phantom.companion.core.constitution.FeatureManifest(
                    id = "decision.diary.audit",
                    tier = 1,
                    displayName = "Журнал рішень",
                    erodesOperatorAgency = false,
                    exfiltratesPrivate = false,
                    actsWithoutConsent = false,
                    abusableAgainstWeak = false,
                    sunsetAfterDaysIdle = -1,
                    surfaces = setOf(
                        local.phantom.companion.core.constitution.FeatureManifest.Surface.STREAM,
                    ),
                ),
                // Phase 3.B — offline AI intent queue (Room + WorkManager drain)
                local.phantom.companion.core.constitution.FeatureManifest(
                    id = "resilience.offline.queue",
                    tier = 1,
                    displayName = "Черга намірів офлайн",
                    surfaces = setOf(
                        local.phantom.companion.core.constitution.FeatureManifest.Surface.STREAM,
                    ),
                    sunsetAfterDaysIdle = -1,
                ),
                // Phase 2.C — Agent dispatcher capabilities
                local.phantom.companion.core.constitution.FeatureManifest(
                    id = "agent.dispatch",
                    tier = 2,
                    displayName = "Диспетчер підагентів",
                    surfaces = setOf(
                        local.phantom.companion.core.constitution.FeatureManifest.Surface.STREAM,
                    ),
                    sunsetAfterDaysIdle = -1,
                ),
                local.phantom.companion.core.constitution.FeatureManifest(
                    id = "agent.parallel.dispatch",
                    tier = 2,
                    displayName = "Паралельний диспетчер агентів (до 3)",
                    surfaces = setOf(
                        local.phantom.companion.core.constitution.FeatureManifest.Surface.STREAM,
                    ),
                    sunsetAfterDaysIdle = -1,
                ),
            )
        for (m in all) constitutionRegistry.register(m)
    }

    // -------------------------------------------------------------------------
    // Null-object fallbacks — keep the app alive when a subsystem fails to init
    // -------------------------------------------------------------------------

    private fun nullRouter(): AIRouter = object : AIRouter {
        override fun complete(intent: AIIntent): Flow<AIChunk> = flow {
            emit(
                AIChunk.ErrorChunk(
                    cause = AIError.CloudRejected(0, "AI router init failed — check logcat for AppContainer"),
                    route = Route.CLOUD_GEMINI,
                ),
            )
        }
        override suspend fun availableRoutes(): Set<Route> = emptySet()
    }

    private fun nullTts(): TtsEngine = object : TtsEngine {
        override val displayName: String = "TTS unavailable"
        override suspend fun isReady(): Boolean = false
        override suspend fun speak(text: String, locale: String): Result<Unit> =
            Result.failure(IllegalStateException("TTS init failed — check logcat for AppContainer"))
        override fun stop() = Unit
    }
}

private const val POI_SEED_PREFS = "phantom_poi_seed"
// Суфікс міняється, коли міняється СЛОВНИК, а не пакет: слово «укриття»
// стояло в колонці ключових слів кожного навісу, і на вже засіяному телефоні
// воно лишилось би там назавжди — пак той самий, отже засів не повторився б.
private const val POI_SEED_KEY = "gazetteer_seeded_pack_generation_v2"
private const val STREET_SEED_KEY = "gazetteer_seeded_streets_generation"
private const val PLACE_SEED_KEY = "gazetteer_seeded_places_generation"
private const val SETTLEMENT_SEED_KEY = "gazetteer_seeded_settlements_generation"

private const val LIVE_PLANE_PREFS = "phantom_live_plane"
private const val LIVE_PLANE_TAG_KEY = "device_tag"

/** Нижче цього зуму мітки не читаються оком — і регіон запиту не має сенсу. */
private const val LIVE_PLANE_MIN_ZOOM = 12.0

/** Радіус регіону: ≈9 клітинок запиту, тобто один-два райони міста. */
private const val LIVE_PLANE_RADIUS_M = 3_000.0

/** The gate ticks at 1 Hz; a caller that cannot wait this long gets a refusal. */
private const val GATE_WARMUP_WAIT_MS = 2_500L
