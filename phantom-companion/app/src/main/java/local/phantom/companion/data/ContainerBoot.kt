package local.phantom.companion.data

import android.content.Context
import android.util.Log
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIError
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.AIRouter
import local.phantom.companion.core.ai.Route
import local.phantom.companion.core.ai.RoutingStrategy
import local.phantom.companion.core.ai.route.GeminiCloudRoute
import local.phantom.companion.core.ai.route.RetryingAIRouter
import local.phantom.companion.core.ai.withResumePrefix
import local.phantom.companion.core.resilience.RetryPolicy
import local.phantom.companion.core.data.PhantomDatabase
import local.phantom.companion.core.data.profile.ProfileStore
import local.phantom.companion.core.data.lifelog.LifeLogArchive
import local.phantom.companion.core.net.map.GeoEndpoints
import local.phantom.companion.core.net.map.routingBaseFor
import local.phantom.companion.core.voice.TtsEngine
import local.phantom.companion.core.voice.route.RoutedTts
import local.phantom.companion.feature.godmode.engine.GodModeEventBus
import local.phantom.companion.feature.godmode.engine.NotificationClassifier
import local.phantom.companion.feature.godmode.engine.NotificationImportant
import local.phantom.companion.feature.godmode.engine.TaskerEngine
import local.phantom.companion.R
import local.phantom.companion.data.backup.refreshAutoSnapshot
import local.phantom.companion.data.backup.wireWalletEscrow
import local.phantom.companion.data.erase.eraseEverything
import local.phantom.companion.net.PhantomApi
import local.phantom.companion.net.PhantomLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Job
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/**
 * Everything that happens once, when the process starts: geo endpoints,
 * the brain bootstrap, the sensor buses, the proactive voice seat.
 *
 * It is a sequence with an order that matters, which is why it reads as one
 * long function — but it is not a dependency graph, so it does not belong in
 * the file that builds one.
 */
internal fun AppContainer.boot() {
    if (!booted.compareAndSet(false, true)) return
    BootProbe.mark("boot:enter")
    BootProbe.startSampling(scope)
    installGeoEndpoints()
    installRoadPackLink()
    installPcLayerLink()
    installWorldPackLink()
    BootProbe.mark("boot:packLinks")
    // PhantomViewModel reads the pair store during composition; warm the
    // Keystore unwrap here so the main thread finds it already open.
    runCatching { store.load() }
    runCatching { previewStore.sweep() }
    BootProbe.mark("boot:storeLoad")
    aiBootstrapper.ensureBrain()
    BootProbe.mark("boot:ensureBrain")
    // ПК і ключ приходять своїми дверима; стан «є чим думати» мусить їх бачити.
    aiBootstrapper.observe(link.connected)
    aiBootstrapper.observe(brainPrefs.routing)
    // Hearing self-provisions the same way, but only after voice is touched:
    // the watcher stays inert (no cold-start fetch) until onCaptureStart has
    // armed it, then resumes an interrupted pull on the next connected network.
    // 12.09 Moonshine і Supertonic у пакунку більше не їдуть — їх качає
    // ModelManager на дотик; цей сторож тримає VOSK, якого в пакунку теж
    // немає. До зведення тут стояло «спечені в APK» — правда хвилі чесності,
    // яку хвиля релізу скасувала тим самим днем.
    hearingProvisioner.observeNetwork(networkProbe.online)
    // 05.09 privacy audit - the live voice is free, but it no longer fetches
    // itself. It used to arm unconditionally at boot, so ~400 MB left for
    // huggingface.co on the first Wi-Fi connection with no tap and no key -
    // see phantom-phone-downloads-without-a-key. voicePrefs.liveVoiceRequested
    // is now the first gate, off on every fresh install; Wi-Fi/disk/mobile-data
    // are the gates AFTER that word, exactly as before. Until it lands the
    // system voice speaks - and says so.
    LiveVoiceProvisioner(
        models = modelManager,
        requested = voicePrefs.liveVoiceRequested,
        online = networkProbe.online,
        metered = meteredNetworkFlow(applicationContext),
        allowMobile = voicePrefs.liveVoiceOnMobile,
        freeBytes = { applicationContext.filesDir.usableSpace },
        scope = scope,
        model = liveVoiceModel,
    ).also {
        LiveVoice.provisioner = it
        it.arm()
    }
    BootProbe.mark("boot:liveVoiceArm")
    aiBootstrapper.observe(aiKeyStore.geminiKey)

    // Tier-2 WRITE-path — the slow idle loop that turns settled conversations
    // into durable memory even if the app never gets backgrounded. Requires the
    // last turn to be 5 min cold so a live chat is never grabbed mid-flight;
    // consolidateMemory itself is fail-safe and de-duplicated across runs.
    scope.launch {
        while (true) {
            kotlinx.coroutines.delay(MEMORY_CONSOLIDATE_INTERVAL_MS)
            runCatching { consolidateMemory(requireIdleMs = 5 * 60_000L) }
        }
    }

    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
        while (true) {
            runCatching { directory.autoPublish(profileStore.getActive()?.displayName) }
            kotlinx.coroutines.delay(PeerDirectoryStore.AUTO_EVERY_MS)
        }
    }

    // TLS до хмари платимо наперед, поки людина ще пише.
    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
        // «Лише на пристрої» — це і про рукостискання: прогрів дивився тільки
        // на ключ, тож з вимкненою хмарою кожен старт однаково відкривав TLS.
        val cloudAllowed =
            brainPrefs.routing.value != local.phantom.companion.core.ai.RoutingStrategy.LocalOnly
        if (cloudAllowed && aiKeyStore.geminiKey.value != null) {
            runCatching { geminiRoute.warmUp() }
        }
    }
    local.phantom.companion.feature.godmode.service
        .AccessibilityServiceActionRegistry.authorizer = appActionAuthorizer({ vaultStore }, applicationContext.packageName)

    // W4-5 — arm the NotificationReceiverService's process-global
    // config so it can enqueue routine notifications into the
    // digest repository as soon as the operator grants
    // Notification Listener access. Per-app rules start empty
    // (operator-overridden via Settings → Notifications when the
    // surface ships); quiet hours default to 22:00–08:00.
    local.phantom.companion.feature.godmode.service
        .NotificationReceiverConfig.repository = notificationDigestRepository

    // DA-5 — both periodic schedulers now ride WorkManager
    // (StreamCleanupWorker + NotificationDigestWorker) instead
    // of a coroutine loop. KEEP policy means the same
    // `enqueueAll` call works on first install (creates the
    // schedule) and every cold launch after (no-op). The
    // schedules survive process kill on Samsung Sleeping Apps
    // / Xiaomi MIUI / Huawei — which is the entire reason the
    // «no new dependencies» rule has its one documented
    // exception.
    runCatching {
        WorkScheduling.enqueueAll(applicationContext)
    }.onFailure { Log.w("AppContainer", "WorkManager enqueue failed", it) }
    // Нічний повний рюкзак: лише Wi-Fi без ліміту і зарядка (розд. 07 §7).
    runCatching {
        local.phantom.companion.data.backpack.BackpackWorker.schedule(applicationContext)
    }.onFailure { Log.w("AppContainer", "backpack schedule failed", it) }
    // Резервна копія: тижневе оновлення за наявності закріпленого файлу копії (H6).
    runCatching {
        local.phantom.companion.data.backup.BackupResealWorker.schedule(applicationContext)
    }.onFailure { Log.w("AppContainer", "backup reseal schedule failed", it) }
    runCatching {
        local.phantom.companion.data.backup.AutoSnapshotWorker.schedule(applicationContext)
        wireWalletEscrow()
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { refreshAutoSnapshot() }.onFailure { Log.w("AppContainer", "autobackup snapshot failed", it) }
        }
    }.onFailure { Log.w("AppContainer", "autobackup schedule failed", it) }


    // Що подія GodMode лишає по собі — вирішує
    // [LifeLogEventArchiver], не цей цикл. Тут лише прибирання того,
    // що старі версії вже записали на диск:
    //  - ScreenSnapshot — фаєрхоуз дампів акселебіліті (QA-fix
    //    2026-07-17), який топив повнотабличний косинус queryMemory;
    //  - Notification / NotificationBurst — сирий заголовок і текст
    //    чужого повідомлення, вкладені у вектор і назавжди доступні
    //    пошуку. Разові коди й банківські SMS теж. Прибирання
    //    ідемпотентне: нові конверти лежать під іншим типом.
    scope.launch {
        val archiver = local.phantom.companion.feature.godmode.engine
            .LifeLogEventArchiver(lifeLog)
        runCatching {
            val purged = lifeLog.purgeType("ScreenSnapshot")
            if (purged > 0) Log.i("AppContainer", "LifeLog: purged $purged ScreenSnapshot rows")
        }
        runCatching {
            val purged = archiver.purgeRawNotificationRows()
            if (purged > 0) Log.i("AppContainer", "LifeLog: purged $purged raw notification rows")
        }
        runCatching {
            val purged = streamRepository.deleteByIdPrefix(
                local.phantom.companion.feature.stream.domain.NOTIFICATION_ITEM_ID_PREFIX,
            )
            if (purged > 0) Log.i("AppContainer", "Stream: purged $purged notification rows")
        }
        local.phantom.companion.feature.godmode.engine.GodModeEventBus.events.collect { event ->
            archiver.onEvent(event)
            // Phase 3-E4 — feed ScreenChanged into the foreground-app
            // monitor so the Whisper Line overlay can hide on the
            // SensitiveAppDenylist set (banks, password managers,
            // OTP apps). NotificationListenerService events do NOT
            // count as foreground; only AccessibilityService
            // ScreenChanged tracks what the operator is actually on.
            if (event is local.phantom.companion.feature.godmode.engine.GodModeEvent.ScreenChanged) {
                local.phantom.companion.feature.ambient.whisper.ForegroundAppMonitor.update(event.pkg)
            }
        }
    }
    // QH3 — model download lifecycle → refresh consuming engines.
    // Subscribing to the explicit DownloadEvent stream (not the
    // ModelState StateFlow) ensures we only fire on the
    // edge-triggered Completed transition; subscribing to states
    // would re-fire on every progress tick.
    scope.launch {
        modelManager.downloadEvents.collect { ev ->
            when (ev) {
                is local.phantom.companion.core.ai.DownloadEvent.Failed -> {
                    // QH4 — surface download failures to operator
                    // instead of letting them sit silently in the
                    // ModelState.error field that only the Settings
                    // dialog sees when it happens to be open.
                    // The build name and the Java exception go to Діагностика
                    // through `cause`; the operator is told which faculty
                    // failed, in his own language.
                    errorBus.emit(
                        source = "ModelManager",
                        severity = local.phantom.companion.core.data.errors.PhantomError.Severity.Error,
                        message = applicationContext.getString(
                            R.string.model_fetch_failed,
                            applicationContext.getString(modelRoleRes(ev.type)),
                        ),
                        cause = IllegalStateException("${ev.type.displayName}: ${ev.message}"),
                    )
                }
                is local.phantom.companion.core.ai.DownloadEvent.Completed -> {
                    when (ev.type) {
                        local.phantom.companion.core.ai.ModelType.SUPERTONIC,
                        local.phantom.companion.core.ai.ModelType.SUPERTONIC_LITE -> {
                            runCatching {
                                (ttsEngine as? local.phantom.companion.core.voice.route.RoutedTts)
                                    ?.refreshPremiumStatus()
                            }.onFailure { Log.w("AppContainer", "TTS refresh failed", it) }
                        }
                        local.phantom.companion.core.ai.ModelType.EMBEDDING -> {
                            // OnnxEmbeddingHelper will load the model dynamically when it becomes available
                        }
                        local.phantom.companion.core.ai.ModelType.EMBEDDING_MULTILINGUAL -> {
                            // НЕ ВИКЛИКАЄТЬСЯ НІЧИМ станом на 07.09: NeedsSearch не має
                            // жодного споживача в main, тож завантажений файл лежить без
                            // читача. Гілка навмисно порожня і навмисно названа — щоб
                            // наступний бачив борг, а не думав, що все зведено.
                        }
                        local.phantom.companion.core.ai.ModelType.VOSK,
                        local.phantom.companion.core.ai.ModelType.MOONSHINE_UK,
                        local.phantom.companion.core.ai.ModelType.YAMNET -> {
                            // BW-2 — same resolution as the lazy init
                            // path above so a freshly unzipped Vosk
                            // model lands at the right inner dir.
                            val voskDir = modelManager.resolveVoskModelDir()?.absolutePath
                            val yamnetFile = modelManager.getModelFile(local.phantom.companion.core.ai.ModelType.YAMNET)
                            val yamnetPath = yamnetFile.takeIf { it.exists() && it.length() > 0 }?.absolutePath
                            val moonshineDir = modelManager.getModelFile(local.phantom.companion.core.ai.ModelType.MOONSHINE_UK)
                            runCatching {
                                voiceManager.refreshModels(
                                    voskDir,
                                    yamnetPath,
                                    moonshineDir.takeIf { local.phantom.companion.core.voice.moonshine.MoonshineSttEngine.allPresent(it) },
                                )
                            }.onFailure { Log.w("AppContainer", "STT/Yamnet refresh failed", it) }
                            
                            // Verbal feedback for model landing
                            scope.launch {
                                val text = when (ev.type) {
                                    local.phantom.companion.core.ai.ModelType.MOONSHINE_UK -> "Модель українського слуху завантажена. Точність розпізнавання зросла."
                                    local.phantom.companion.core.ai.ModelType.VOSK -> "Українська модель завантажена. Тепер я вас чую."
                                    local.phantom.companion.core.ai.ModelType.YAMNET -> "Слух оточення активовано."
                                    else -> null
                                }
                                text?.let { t -> 
                                    val norm = local.phantom.companion.core.voice.UaTtsNormalizer.normalize(t)
                                    ttsEngine.speak(norm) 
                                }
                            }
                        }
                    }
                }
                else -> Unit  // Started/Progress are status-bar concerns
            }
        }
    }
    // QH3 — bootstrap the TTS route indicator at first link of
    // [ttsEngine] so Settings shows the correct «голос: …» state
    // even before the first speak() call.
    //
    // 12.09: до цього дня обидві моделі лежали в APK і тут РОЗПАКОВУВАЛИСЬ у
    // filesDir — 442 МБ у пакунку плюс ті самі 442 МБ на диску. Тепер джерело
    // одне (ModelManager), і старт лише питає диск: що завантажено, те й
    // вмикається; чого немає — те чесно «не встановлено» в Налаштуваннях.
    scope.launch {
        runCatching {
            modelManager.recheck(liveVoiceModel)
            (ttsEngine as? local.phantom.companion.core.voice.route.RoutedTts)
                ?.refreshPremiumStatus()
        }
    }
    scope.launch {
        runCatching {
            val moonshineDir = modelManager.getModelFile(local.phantom.companion.core.ai.ModelType.MOONSHINE_UK)
            modelManager.recheck(local.phantom.companion.core.ai.ModelType.MOONSHINE_UK)
            if (local.phantom.companion.core.voice.moonshine.MoonshineSttEngine.allPresent(moonshineDir)) {
                val voskDir = modelManager.resolveVoskModelDir()?.absolutePath
                val yamnetFile = modelManager.getModelFile(local.phantom.companion.core.ai.ModelType.YAMNET)
                val yamnetPath = yamnetFile.takeIf { it.exists() && it.length() > 0 }?.absolutePath
                voiceManager.refreshModels(voskDir, yamnetPath, moonshineDir)
            }
        }.onFailure { Log.w("AppContainer", "Moonshine refresh failed", it) }
    }
    // Phase 3-D3 — drain DiaryStore.publish queue on every reconnect.
    // The link's `connected` StateFlow flips Disconnected→Connecting→
    // Connected; we only act on Connected transitions (drop is fine —
    // the queue persists in pending_publish until the next Connected).
    // QH5 — also surface link-state transitions to the error bus so
    // the operator gets «зʼєднання втрачено · перепідключаюсь» as a
    // Stream Notice when the WS drops mid-session, instead of
    // silently going stale.
    scope.launch {
        var previous = local.phantom.companion.net.LinkStatus.Disconnected
        link.connected.collect { status ->
            if (status == local.phantom.companion.net.LinkStatus.Connected) {
                runCatching { diaryStore.drainPending(diaryTransport) }
                    .onFailure { Log.w("AppContainer", "diary drain failed", it) }
                if (previous != local.phantom.companion.net.LinkStatus.Connected &&
                    previous != local.phantom.companion.net.LinkStatus.Disconnected) {
                    // We were reconnecting and now we're back.
                    // Info severity — operator-visible only in
                    // Diagnostics history, not as a Stream Notice.
                    errorBus.emit(
                        source = "PhantomLink",
                        severity = local.phantom.companion.core.data.errors.PhantomError.Severity.Info,
                        message = "зʼєднання відновлено",
                    )
                }
            } else if (previous == local.phantom.companion.net.LinkStatus.Connected) {
                // We had a live link and just lost it. Warn level
                // — sits quietly in Diagnostics so we don't crowd
                // Stream every time the operator's wifi blips.
                errorBus.emit(
                    source = "PhantomLink",
                    severity = local.phantom.companion.core.data.errors.PhantomError.Severity.Warn,
                    message = "зʼєднання втрачено · перепідключаюсь",
                )
            }
            previous = status
        }
    }
    // SV-2 — observe Live Dialog preference and master mute, drive
    // the foreground service lifecycle off the combined state. The
    // service refuses to start while muted; if mute flips on while
    // running, the service self-stops via its own collectLatest on
    // `voicePrefs.micMuted`. Belt-and-braces: we also stop here so
    // a paused state never leaves a stale notification visible.
    scope.launch {
        kotlinx.coroutines.flow.combine(
            voicePrefs.liveDialogEnabled,
            voicePrefs.micMuted,
        ) { enabled, muted -> enabled && !muted }
            .collect { syncLiveDialogService() }
    }

    // NAV-FGS (brief 03 §4.1) — drive the turn-by-turn foreground service
    // off the engine's own guiding flag. Started when a drive goes live,
    // stopped when it ends — so a backgrounded drive keeps GPS at
    // navigation grade and the process alive, and a finished one leaves no
    // stale banner. The engine owns the drive; this service only keeps the
    // OS from killing it. distinctUntilChanged so we hop the service once
    // per drive, not once per fix.
    scope.launch {
        navEngine.state
            .map { it.guiding }
            .distinctUntilChanged()
            .collect { guiding ->
                val intent = android.content.Intent(
                    applicationContext,
                    local.phantom.companion.service.NavigationService::class.java,
                )
                runCatching {
                    if (guiding) {
                        androidx.core.content.ContextCompat
                            .startForegroundService(applicationContext, intent)
                    } else {
                        applicationContext.stopService(intent)
                    }
                }.onFailure { Log.w("AppContainer", "NavigationService lifecycle hop failed", it) }
            }
    }

    // DA-3 / B-1 — Phase 3-D2 wire-up.
    scope.launch {
        link.events.collect { event ->
            if (event.channel == local.phantom.companion.feature.stream.diary
                    .DiaryWireParser.CHANNEL
            ) {
                val msg = local.phantom.companion.feature.stream.diary
                    .DiaryWireParser.tryParse(event.type, event.raw)
                if (msg != null) {
                    runCatching { diarySubscriber.handle(msg) }
                        .onFailure {
                            Log.w("AppContainer", "diary handle failed", it)
                        }
                }
            }
        }
    }
    // DA-3 / B-2 — Global lock_vault signal wiring
    scope.launch {
        link.events.collect { event ->
            if (event.channel == "companion_control") {
                val data = runCatching { event.raw["data"] as? kotlinx.serialization.json.JsonObject }.getOrNull()
                val verb = (data?.get("verb") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull()
                if (verb == "lock_vault") {
                    Log.i("AppContainer", "B-2: lock_vault signal received, clamping local vault")
                    vaultStore.lockNow()
                }
            }
        }
    }
    // Сховище налаштувань шифроване і живе тут, у :app; служба стрічки живе в
    // :feature-ambient і не дістане його сама. Дзеркалимо в той самий шов, що
    // й події: без цього «частота» і «звук» писались нікуди.
    scope.launch {
        voicePrefs.whisperFreqOrdinal.collect {
            local.phantom.companion.feature.ambient.whisper.WhisperTuning.setFreqOrdinal(it)
        }
    }
    scope.launch {
        voicePrefs.whisperSoundEnabled.collect {
            local.phantom.companion.feature.ambient.whisper.WhisperTuning.setSoundEnabled(it)
        }
    }
    // Phase 3-E3 — bridge `agent.stream` wire frames into the
    // WhisperLineService event bus. Runs even when the service
    // isn't attached so messages don't get lost in the gap
    // between operator enabling the overlay and the first event;
    // WhisperEventBus replay=0 means events that arrive before
    // a subscriber drop on the floor (which is correct: a stale
    // proactive cue has no value once it's a few seconds old).
    scope.launch {
        link.events.collect { event ->
            if (event.channel == "agent.stream") {
                val data = runCatching {
                    event.raw["data"] as? kotlinx.serialization.json.JsonObject
                }.getOrNull()
                val text = data
                    ?.get("text")
                    ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                    ?.contentOrNull()
                val emotion = data
                    ?.get("emotion")
                    ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                    ?.contentOrNull()
                local.phantom.companion.feature.ambient.whisper.WhisperEventBus.push(
                    local.phantom.companion.feature.ambient.whisper.WhisperEvent(
                        kind = event.type,
                        text = text,
                        emotion = emotion,
                    ),
                )
            }
        }
    }
    pipeBusesToNervousBus()
    BootProbe.mark("boot:nervousBus")
    startProactiveVoiceSeat()
    BootProbe.mark("boot:proactiveVoiceSeat")
    installSolana()
    startPeerMesh()
    startMapPeople()
    BootProbe.mark("boot:peerMeshStarted")
    scope.launch { eraseEverything(applicationContext).resume() }
}

internal fun appActionAuthorizer(
    vault: () -> local.phantom.companion.core.data.vault.VaultStore,
    ownPackage: String,
) = local.phantom.companion.feature.godmode.engine.AppActionAuthorizer(
    isSensitiveApp = { local.phantom.companion.feature.ambient.denylist.SensitiveAppDenylist.isSensitive(it) },
    isVaultLocked = { runCatching { vault().locked.value }.getOrDefault(true) },
    ownPackage = ownPackage,
)

/**
 * The mesh needs no pairing — this phone has a key of its own, so two people
 * can talk with no server in the world. It runs in a service rather than this
 * scope, because a message at 03:00 must land while the app sleeps.
 *
 * But it runs only once there is somebody to talk to. Unconditionally, on a
 * fresh install, it was a multicast lock and a 5 s pump held open for an empty
 * roster — see [PeerMeshGate].
 */
private fun AppContainer.startPeerMesh() {
    scope.launch {
        local.phantom.companion.service.PeerMeshGate.keepInStep(
            roster = peers.peers,
            running = { mesh.running },
            start = {
                // До сервісу: той першим будить хаб, і перший же кадр має знати «вдома» й обличчя.
                if (peopleWired.compareAndSet(false, true)) peopleWhere.wire(peerWhereDoing(), ::peerFace)
                local.phantom.companion.service.PeerMeshService.ensureRunning(applicationContext)
            },
            stop = {
                local.phantom.companion.service.PeerMeshService.stop(applicationContext)
            },
        )
    }
}

// Гейт кличе start щоразу, як ростер оживає; слухач своїх місць має бути один.
private val peopleWired = java.util.concurrent.atomic.AtomicBoolean(false)

/** «Мій світ» і «я на місці» ідуть на тих самих вимірах, що вже приходять; свого слухача GPS немає. */
private fun AppContainer.startMapPeople() {
    // Роди мапи — до першого листа собі, інакше кодек мовчки пропустить їх як незнані.
    local.phantom.companion.core.net.peer.SelfMapWire.register()
    scope.launch { sensorAdapter?.gpsFeed?.collect { mapPeople.onFix() } }
}

private fun AppContainer.startProactiveVoiceSeat() {
    // The operator's switch, not a compile-time constant. Built, wired and
    // unreachable is the same as absent — and it was absent on every device.
    scope.launch {
        var job: Job? = null
        voicePrefs.proactiveEnabled.collect { on ->
            if (on) {
                if (job?.isActive != true) job = buildProactiveEngine().start(scope)
            } else {
                job?.cancel()
                job = null
            }
        }
    }
}

private fun AppContainer.buildProactiveEngine(): ProactiveEngine {

    // 1. Signal sources → named String? flows. gnss_trust (spoof/EW trust)
    //    is critical so a jam/spoof flip drives high salience.
    val signalStreams: Map<String, Flow<String?>> = mapOf(
        "gnss_trust" to (locator?.trust?.map { it.name } ?: emptyFlow<String?>()),
        "screen" to local.phantom.companion.feature.ambient.whisper.ForegroundAppMonitor.currentPackage,
        "sound" to voiceManager.classificationResults.map { it.label },
    )

    // 2. Міркує той самий розум, що й чат (ПК або хмара); без розуму — мовчить.
    val generate: suspend (String) -> String = { prompt ->
        val sb = StringBuilder()
        var finalText: String? = null
        aiRouter.complete(
            AIIntent.Text(prompt = prompt, history = emptyList(), systemPrompt = null, context = null),
        ).collect { chunk ->
            when (chunk) {
                is AIChunk.Token -> sb.append(chunk.text)
                is AIChunk.Final -> finalText = chunk.text
                else -> Unit
            }
        }
        finalText?.takeIf { it.isNotBlank() } ?: sb.toString()
    }

    // 3. Sink → the SAME channels the app already uses: proactive_card on the
    //    nervous bus (StreamViewModel → Insight), the app's gated TTS entry,
    //    and the whisper ribbon.
    val sink = ProactiveUtteranceSink(
        speak = { text ->
            if (voicePrefs.ttsEnabled.value &&
                local.phantom.companion.feature.stream.tts.SmartTtsGate.shouldSpeak(applicationContext, text)
            ) {
                ttsEngine.speak(text)
            }
        },
        card = { utterance, _ ->
            nervousBus.publish(
                PhantomEvent(
                    id = UUID.randomUUID().toString(),
                    timestampMs = System.currentTimeMillis(),
                    sourceOrgan = "ProactiveEngine",
                    type = local.phantom.companion.core.predict.ForesightEngine.TYPE_PROACTIVE_CARD,
                    payloadJson = buildJsonObject {
                        put("id", UUID.randomUUID().toString())
                        put("title", "")
                        put("body", utterance.say)
                        put("kind", "proactive_voice")
                    }.toString(),
                    deviceOrigin = applicationContext.packageName,
                    privacy = "trusted",
                ),
            )
        },
        whisper = { text ->
            local.phantom.companion.feature.ambient.whisper.WhisperEventBus.push(
                local.phantom.companion.feature.ambient.whisper.WhisperEvent(kind = "proactive", text = text),
            )
        },
    )

    return ProactiveEngine(
        frames = ProactiveFrameSource(
            signalStreams = signalStreams,
            criticalKeys = setOf("gnss_trust"),
        ).frames(),
        reasoner = ProactiveLlmReasoner(generate = generate),
        sink = sink,
    )
}

private fun AppContainer.pipeBusesToNervousBus() {
    val deviceId = applicationContext.packageName

    // 1. GodModeEventBus
    scope.launch {
        local.phantom.companion.feature.godmode.engine.GodModeEventBus.events.collect { event ->
            val payload = godModeNervousPayload(event)
            nervousBus.publish(
                PhantomEvent(
                    id = UUID.randomUUID().toString(),
                    timestampMs = System.currentTimeMillis(),
                    sourceOrgan = "GodModeEventBus",
                    type = event::class.simpleName ?: "Unknown",
                    payloadJson = payload.toString(),
                    deviceOrigin = deviceId,
                    privacy = "trusted"
                )
            )
        }
    }

    // 2. AppActionTraceBus
    scope.launch {
        local.phantom.companion.feature.godmode.engine.AppActionTraceBus.events.collect { event ->
            val payload = buildJsonObject {
                put("widgetId", JsonPrimitive(event.widgetId))
                put("progress", JsonPrimitive(event.progress.toString()))
                put("emittedAtMs", JsonPrimitive(event.emittedAtMs))
            }
            nervousBus.publish(
                PhantomEvent(
                    id = UUID.randomUUID().toString(),
                    timestampMs = event.emittedAtMs,
                    sourceOrgan = "AppActionTraceBus",
                    type = "AppActionTrace",
                    payloadJson = payload.toString(),
                    deviceOrigin = deviceId,
                    privacy = "trusted"
                )
            )
        }
    }

    // 3. PhantomErrorBus (via errorBus)
    scope.launch {
        errorBus.events.collect { error ->
            val payload = buildJsonObject {
                put("source", JsonPrimitive(error.source))
                put("message", JsonPrimitive(error.message))
                put("cause", JsonPrimitive(error.cause?.message ?: ""))
            }
            nervousBus.publish(
                PhantomEvent(
                    id = error.id,
                    timestampMs = error.ts,
                    sourceOrgan = "PhantomErrorBus",
                    type = error.severity.name,
                    payloadJson = payload.toString(),
                    deviceOrigin = deviceId,
                    privacy = "trusted"
                )
            )
        }
    }

    // 5. NotificationImportantBus
    scope.launch {
        local.phantom.companion.feature.godmode.engine.NotificationImportantBus.events.collect { notif ->
            nervousBus.publish(notificationImportantEvent(notif, deviceId))
        }
    }
}

// Шина тримає останні 64 події в памʼяті, тож усе, що сюди покласти, переживе
// саму подію. Заголовок уже редагований на своїй шині; тут те саме правило
// вдруге — щоб чужий видавець не проніс сирий рядок повз нього.
internal fun notificationImportantEvent(
    notif: NotificationImportant,
    deviceId: String,
): PhantomEvent {
    val payload = buildJsonObject {
        put("packageName", JsonPrimitive(notif.packageName))
        put("redactedTitle", JsonPrimitive(NotificationClassifier.redactSensitive(notif.redactedTitle)))
        put("redactedText", JsonPrimitive(notif.redactedText))
        put("deliveredAtMs", JsonPrimitive(notif.deliveredAtMs))
    }
    return PhantomEvent(
        id = UUID.randomUUID().toString(),
        timestampMs = notif.deliveredAtMs,
        sourceOrgan = "NotificationImportantBus",
        type = "NotificationImportant",
        payloadJson = payload.toString(),
        deviceOrigin = deviceId,
        privacy = "trusted"
    )
}

// На цю шину їде факт події, а не її слова. Дамп екрана — до 5000 символів
// того, що оператор зараз читає, — і тіло чужого сповіщення тут ніхто не
// споживає, а замовкли б вони аж зі смертю процесу. Ті, кому вміст справді
// потрібен, беруть його на GodModeEventBus, до цієї межі.
internal fun godModeNervousPayload(
    event: local.phantom.companion.feature.godmode.engine.GodModeEvent,
): JsonObject = when (event) {
    is local.phantom.companion.feature.godmode.engine.GodModeEvent.Notification -> buildJsonObject {
        put("pkg", JsonPrimitive(event.pkg))
        put("redactedTitle", JsonPrimitive(NotificationClassifier.redactSensitive(event.title)))
        put("textChars", JsonPrimitive(event.text.length))
    }
    is local.phantom.companion.feature.godmode.engine.GodModeEvent.NotificationBurst -> buildJsonObject {
        put("pkg", JsonPrimitive(event.pkg))
        put("textChars", JsonPrimitive(event.combinedText.length))
    }
    is local.phantom.companion.feature.godmode.engine.GodModeEvent.ScreenChanged -> buildJsonObject {
        put("pkg", JsonPrimitive(event.pkg))
        put("className", JsonPrimitive(event.className))
    }
    is local.phantom.companion.feature.godmode.engine.GodModeEvent.ScreenSnapshot -> buildJsonObject {
        put("pkg", JsonPrimitive(event.pkg))
        put("textChars", JsonPrimitive(event.textContent.length))
    }
}

/**
 * Приводить службу живого діалогу до стану, який обіцяють налаштування.
 *
 * Викликається і з [boot], і з `MainActivity.onResume`, і саме друге тут
 * головне. `boot()` виконується на КОЖНОМУ старті процесу, зокрема тоді, коли
 * процес підняв не оператор, а система — приймач BOOT_COMPLETED, вартовий
 * тривог, робітник. Android 12+ не дає стартувати службу переднього плану з
 * фону, а тип `microphone` (Android 14+) не дозволений із фону взагалі. Раніше
 * цей виняток ковтав `runCatching`, і виходило найгірше з можливого: перемикач
 * світиться «увімкнено», острів світиться, а мікрофон закритий і в шторці
 * порожньо. Тепер відмова їде в Діагностику, а наступний вихід оператора в
 * застосунок піднімає службу вже з переднього плану.
 */
internal fun AppContainer.syncLiveDialogService() {
    // Без RECORD_AUDIO служба типу microphone на Android 14 падає разом із процесом:
    // дозвіл «лише цього разу» спливає, його відкликають у налаштуваннях.
    val micGranted = androidx.core.content.ContextCompat.checkSelfPermission(
        applicationContext,
        android.Manifest.permission.RECORD_AUDIO,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val shouldRun = voicePrefs.liveDialogEnabled.value && !voicePrefs.micMuted.value && micGranted
    val intent = android.content.Intent(
        applicationContext,
        local.phantom.companion.service.LiveDialogService::class.java,
    )
    runCatching {
        if (shouldRun) {
            androidx.core.content.ContextCompat.startForegroundService(applicationContext, intent)
        } else {
            applicationContext.stopService(intent)
        }
    }.onFailure { t ->
        Log.w("AppContainer", "LiveDialog lifecycle hop failed", t)
        if (shouldRun) {
            errorBus.emit(
                source = "LiveDialog",
                severity = local.phantom.companion.core.data.errors.PhantomError.Severity.Warn,
                message = "живий діалог не піднявся у фоні — відкрий застосунок, і мікрофон увімкнеться",
                cause = t,
            )
        }
    }
}

/** Which faculty a model file gives PHANTOM — the only part of it the operator cares about. */
private fun modelRoleRes(type: local.phantom.companion.core.ai.ModelType): Int = when (type) {
    local.phantom.companion.core.ai.ModelType.VOSK,
    local.phantom.companion.core.ai.ModelType.MOONSHINE_UK -> R.string.model_role_hearing
    local.phantom.companion.core.ai.ModelType.SUPERTONIC,
    local.phantom.companion.core.ai.ModelType.SUPERTONIC_LITE -> R.string.model_role_voice
    local.phantom.companion.core.ai.ModelType.YAMNET -> R.string.model_role_sounds
    local.phantom.companion.core.ai.ModelType.EMBEDDING -> R.string.model_role_memory
    local.phantom.companion.core.ai.ModelType.EMBEDDING_MULTILINGUAL -> R.string.model_role_meaning
}


private val selfMapLock = Any()
@Volatile private var selfMapOne: SelfMapSync? = null

/**
 * Мапа однієї особи між моїми пристроями: місця, лінії, показ, привид.
 * Кодек PH-SLF реєструє сесія месенджера й віддає сюди операції place/route/mapself/world.
 */
internal val AppContainer.selfMap: SelfMapSync
    get() = selfMapOne ?: synchronized(selfMapLock) {
        selfMapOne ?: SelfMapSync(
            ledger = SelfMapFileLedger(applicationContext),
            target = SelfMapStores(
                placeDao = database.savedPlaceDao(),
                lineDao = database.drawnLineDao(),
                mapSelf = { peopleWhere.selfSettings() },
                applySetting = { peopleWhere.applySetting(it.key, it.value) },
                applyCell = { mapPeople.recorder.absorb(it) },
            ),
            origin = { selfMapOrigin(applicationContext) },
        ).also { selfMapOne = it }
    }

// Особа на моїх пристроях одна, тож id особи тут не годиться: потрібен id саме цієї копії.
private fun selfMapOrigin(context: android.content.Context): String {
    val prefs = context.getSharedPreferences("self_map", android.content.Context.MODE_PRIVATE)
    return prefs.getString("origin", null) ?: UUID.randomUUID().toString().replace("-", "").take(16)
        .also { prefs.edit().putString("origin", it).apply() }
}
