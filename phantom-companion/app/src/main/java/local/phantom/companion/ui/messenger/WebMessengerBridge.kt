package local.phantom.companion.ui.messenger

import local.phantom.companion.privacy.NotificationAsk
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.webkit.JavascriptInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.phantom.companion.R
import local.phantom.companion.data.AppContainer
import local.phantom.companion.data.PeerFileStore
import local.phantom.companion.data.QuietHours
import local.phantom.companion.data.SavedNotes
import local.phantom.companion.data.TodayTabsStore
import local.phantom.companion.core.embodiment.HapticPattern
import local.phantom.companion.core.net.peer.PeerAnnounce
import local.phantom.companion.core.net.peer.PeerCall
import local.phantom.companion.core.net.peer.PeerCallLine
import local.phantom.companion.core.net.peer.PeerCard
import local.phantom.companion.core.net.peer.PeerDisappear
import local.phantom.companion.core.net.peer.PeerFile
import local.phantom.companion.service.PeerNotifications
import local.phantom.companion.core.net.peer.PeerFrames
import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerGroupRoles
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerMark
import local.phantom.companion.core.net.peer.PeerInvite
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerOutbox
import local.phantom.companion.core.net.peer.PeerPayload
import local.phantom.companion.core.net.peer.PeerPreview
import local.phantom.companion.core.net.peer.PeerProfile
import local.phantom.companion.core.net.peer.PeerPurge
import local.phantom.companion.core.net.peer.PeerRead
import local.phantom.companion.core.net.peer.PeerRelay
import local.phantom.companion.core.net.peer.PeerRevise
import local.phantom.companion.core.net.peer.PeerRoster
import local.phantom.companion.data.PeerPhotoStore
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import local.phantom.companion.core.net.peer.PeerSafety
import local.phantom.companion.data.intent.InternetPath

/**
 * Міст веб-месенджера в піринговий меш телефона.
 *
 * Веб кличе шляхи `/messenger/…` так, ніби перед ним вузол. Тут ті самі шляхи
 * відповідає телефон: контакти — це ростер, розмова — це співрозмовник,
 * лист — `PeerMesh.send`. Сервера між людьми немає — і тут його теж немає.
 */
class WebMessengerBridge(private val container: AppContainer) {

    private val devices by lazy { WebMessengerMine(container) }
    private val ownDevices by lazy { WebMessengerDevices.of(container) }
    private val intentsRoute by lazy { WebMessengerIntents(container) }
    private val backpackRoute by lazy { WebMessengerBackpack(container) }
    private val shareInRoute by lazy { WebMessengerShareIn(container.applicationContext) }
    private val relayFsRoute by lazy { WebMessengerRelayFs(container.relayFs) }
    private val supportRoute by lazy {
        WebMessengerSupport(
            baseUrl = local.phantom.companion.BuildConfig.LICENSE_BASE_URL,
            signer = WebMessengerSupport.deviceSigner({ runCatching { container.identity.get() }.getOrNull() }),
            http = local.phantom.companion.data.VenueClient.urlConnection(20_000),
        )
    }
    private val directory by lazy {
        WebMessengerDirectory(container) { container.profileHerald.bump() }
    }
    private val pcPairing by lazy { local.phantom.companion.data.PcPairing(container) }
    private val solana by lazy { WebMessengerSolana(container) }
    private val spacesRoute by lazy {
        WebMessengerSpaces(
            log = { container.mesh.outbox.value },
            me = { container.mesh.selfIdFlow.value },
            sendAct = { group, act -> container.mesh.sendGroupAct(group, act) },
        )
    }
    private val staffRoute by lazy {
        WebMessengerStaff(
            rows = container.staffInbox::rows,
            knows = { id -> container.peers.peers.value.any { it.id == id } },
            send = { to, frame -> container.mesh.send(to, frame, quiet = true) != null },
        )
    }
    private val privacyRoute by lazy {
        WebMessengerPrivacy(
            receipts = { container.readReceipts.enabled.value },
            setReceipts = container.readReceipts::set,
            visibility = { container.visibility.choice.value },
            setVisibility = container.visibility::set,
        )
    }
    private val circlesRoute by lazy { WebMessengerCircles(container.peers) { id -> container.peers.peers.value.any { it.id == id } || WebMessengerGroups.find(container.mesh.outbox.value, id) != null } }

    /**
     * Заклади й бали: сервер їх бачить лише через телефон (CSP веба —
     * `connect-src 'self'`), а місце для «поруч» і «Я на місці» бере телефон зі
     * своїх воріт місця, не з веба. Мова відмов — мова месенджера.
     */
    private val venues by lazy {
        WebMessengerVenues(
            api = container.venues,
            here = { hereNow(WebMessengerVenues.Use.NEAR) },
            words = { say(it.res) },
            english = ::messengerEnglish,
        )
    }
    private val points by lazy {
        WebMessengerPoints(
            api = container.points,
            claims = container.pointsClaims,
            here = { hereNow(WebMessengerVenues.Use.CHECK_IN) },
            wallet = ::ownWallet,
            words = { say(it.res) },
            english = ::messengerEnglish,
        )
    }

    /**
     * Дотик по картці зустрічі: кадр лише тим, хто в розмові, має ключ і сам
     * сказав у профілі, що вміє позначки сцени (`PeerIdentity.sceneLevel`).
     */
    private val cardMarks by lazy {
        WebMessengerCardMarkDoor(
            log = { container.mesh.outbox.value },
            me = { container.mesh.selfIdFlow.value },
            keyed = { container.peers.peers.value.filter { it.dhPubKey.isNotBlank() }.mapTo(HashSet()) { it.id } },
            scene = {
                container.peers.peers.value
                    .filter { it.sceneLevel >= PeerProfile.SCENE_V1 }
                    .mapTo(HashSet()) { it.id }
            },
            send = { to, frame -> container.mesh.send(to, frame) },
            words = { say(it) },
        )
    }

    private val scene by lazy { WebMessengerScene(venues, points, cardMarks) }
    private val places by lazy { WebMessengerPlaces.of(container) }
    private val day by lazy { WebMessengerDay.of(container) }

    /**
     * Одна відповідь на один запит веба — для порту ([WebMessengerPort]).
     * Мережа всередині йде через [offLane], тож чекає не потік моста, а корутина.
     */
    suspend fun dispatch(method: String, path: String, body: String?): String = try {
        route(method.uppercase(), path.substringBefore('?'), path.substringAfter('?', ""), body)
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        fail(500, "bridge_error", t.message ?: t.javaClass.simpleName)
    }

    /**
     * Старий синхронний вхід — лише для бандла без порту (WebView без
     * `WEB_MESSAGE_LISTENER`). Він чекає за контрактом: JS жде рядок.
     */
    @JavascriptInterface
    fun call(method: String, path: String, body: String?): String =
        WebMessengerPort.await(container.scope) { dispatch(method, path, body) }

    @JavascriptInterface
    fun backupStashPut(key: String, raw: String) {
        runCatching { container.webBackupStash.put(key, raw) }
    }

    @JavascriptInterface
    fun backupStashReplace(json: String) {
        runCatching { container.webBackupStash.replace(json) }
    }

    @JavascriptInterface
    fun backupStashTake(): String = runCatching { container.webBackupStash.take() }.getOrDefault("")

    @JavascriptInterface
    fun previewBegin(kind: String, name: String, mime: String, size: Long): String =
        runCatching { container.previewStore.begin(kind, name, mime, size) }.getOrDefault("")

    @JavascriptInterface
    fun previewChunk(id: String, seq: Int, b64: String): Boolean =
        runCatching { container.previewStore.chunk(id, seq, b64) }.getOrDefault(false)

    @JavascriptInterface
    fun previewOpen(id: String): Boolean =
        runCatching { container.previewStore.open(id) }.getOrDefault(false)

    @JavascriptInterface
    fun previewImageUrl(id: String): String =
        runCatching { container.previewStore.imageUrl(id) }.getOrDefault("")

    @JavascriptInterface
    fun haptic(type: String) {
        if (!WebHaptics.perform(type)) triggerHaptic(type)
    }

    @JavascriptInterface
    fun copyToClipboard(text: String): Boolean = runCatching {
        val clipboard = container.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("phantom", text)
        clipboard?.setPrimaryClip(clip)
        triggerHaptic("tap")
        true
    }.getOrDefault(false)

    @JavascriptInterface
    fun getFromClipboard(): String = runCatching {
        val clipboard = container.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0)?.text?.toString().orEmpty()
        } else ""
    }.getOrDefault("")

    @JavascriptInterface
    fun shareText(text: String, title: String?): Boolean = runCatching {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, text)
            type = "text/plain"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val chooser = Intent.createChooser(sendIntent, title ?: "Nightpost").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        container.applicationContext.startActivity(chooser)
        triggerHaptic("tap")
        true
    }.getOrDefault(false)

    @JavascriptInterface
    fun openMap(lat: Double, lon: Double, label: String?): Boolean = runCatching {
        container.scope.launch(Dispatchers.Main) {
            // Три аргументи раніше не читались: мапа відкривалась там, де стояла
            // камера, тож тап по надісланій точці нічим не відрізнявся від
            // «відкрити мапу». Мітка й наведення — те, що обіцяє підпис.
            val title = label?.trim()?.takeIf { it.isNotEmpty() }
                ?: String.format(java.util.Locale.US, "%.5f, %.5f", lat, lon)
            val layerId = runCatching {
                container.spatialLayerRepository.push(
                    local.phantom.companion.core.data.spatial.SpatialLayerSpec(
                        title = title,
                        origin = local.phantom.companion.core.data.spatial.LayerOrigin.PHANTOM,
                        kind = "point",
                        markers = listOf(
                            local.phantom.companion.core.data.spatial.SpatialMarker(
                                lat = lat,
                                lon = lon,
                                label = title,
                            )
                        ),
                    )
                )
            }.getOrNull()
            runCatching {
                container.spatialLayerRepository.requestFocus(
                    local.phantom.companion.core.data.spatial.FocusTarget(
                        lat = lat,
                        lon = lon,
                        zoom = 15.0,
                        layerId = layerId,
                    )
                )
            }
            container.homeTabRequest.value = local.phantom.companion.ui.home.HomeTab.MAP
        }
        triggerHaptic("chime")
        true
    }.getOrDefault(false)

    @JavascriptInterface
    fun openMapHere(): Boolean = navigate("map", null)

    @JavascriptInterface
    fun openWebSignIn(): Boolean = runCatching {
        container.scope.launch(Dispatchers.Main) {
            container.events.routeRequest.value = local.phantom.companion.ui.screens.WebSignIn.ROUTE
        }
        triggerHaptic("tap")
        true
    }.getOrDefault(false)

    @JavascriptInterface
    fun navigate(to: String?, argsJson: String?): Boolean {
        val act = WebNav.parse(to, argsJson) ?: return false
        container.scope.launch(Dispatchers.Main) {
            when (act) {
                is WebNav.Act.Tab -> {
                    if (act.pickMeetup) container.events.meetupPinPick.value = true
                    act.peerId?.let { container.peopleWhere.focus(it) }?.let { f ->
                        runCatching {
                            container.spatialLayerRepository.requestFocus(
                                local.phantom.companion.core.data.spatial.FocusTarget(f.lat, f.lon, f.zoom),
                            )
                        }
                    }
                    container.homeTabRequest.value = act.tab
                }
                // Лише прапорець. Запит вкладки тут перебудовував домівку через
                // `home()` графа — WebView народжувався наново, і розмова з
                // PHANTOM під дзвінком мінялась на список (A52, 02.10). Кнопка
                // дзвінка й так живе в розмові на вкладці «Чати».
                WebNav.Act.CallPhantom -> container.events.phantomCallOpen.value = true
                WebNav.Act.Scan -> container.events.routeRequest.value = local.phantom.companion.navigation.Routes.Scan.path
            }
        }
        triggerHaptic("tap")
        return true
    }

    @JavascriptInterface
    fun startVoiceInput(): Boolean = runCatching {
        container.scope.launch(Dispatchers.Main) {
            container.voiceManager.startCapturing()
        }
        triggerHaptic("tap")
        true
    }.getOrDefault(false)

    @JavascriptInterface
    fun stopVoiceInput(): Boolean = runCatching {
        container.scope.launch(Dispatchers.Main) {
            container.voiceManager.stopCapturing()
        }
        triggerHaptic("tap")
        true
    }.getOrDefault(false)

    @JavascriptInterface
    fun isVoiceAvailable(): Boolean =
        container.voiceManager.activeEngine.value != local.phantom.companion.core.voice.SttRouteKind.None

    /**
     * Яку палітру носить рідний бік ЗАРАЗ: рівно `day` або `night`.
     *
     * «тему лучше одну на все приложение» — 11.09. Форму диктує бандл, а не ця
     * сторона: він робить `getTheme()==="day"?"day":"night"` і те саме з
     * поштовхом. Будь-що інше — зокрема JSON із кольорами — він зведе до
     * `night`, і денна половина не засвітиться ніколи.
     *
     * Джерело одне: пише `WebMessengerHost` (лише в нього є розвʼязана палітра
     * композиції), читають двоє — цей метод на запит і `window.__phantomTheme`
     * на зміну.
     */
    @JavascriptInterface
    fun getTheme(): String = container.webTheme.value

    /**
     * Фаза доби — `night|dawn|day|dusk`, окремим методом, а не новим значенням
     * [getTheme]: старий бандл звів би «dawn» до ночі. Новий бандл питає це
     * першим і падає на [getTheme], якщо методу немає (старий APK).
     */
    @JavascriptInterface
    fun getThemePhase(): String = container.webThemePhase.value

    /** Кадр сонця `від:до:частка` — веб малює ту саму точку, що й оболонка навколо. */
    @JavascriptInterface
    fun getSunLook(): String = container.webSunLook.value

    /**
     * Мова застосунку тегом каталогу вебу (`en`, `uk`) — веб ставить її в
     * `ui_language` на завантаженні сторінки. Зміну приносить поштовх хоста
     * ([webLanguagePushJs]).
     */
    @JavascriptInterface
    fun getLanguage(): String = webLanguage(container.applicationContext)

    /**
     * Вибір теми людини — `auto|night|day`, той самий, що в рідному Профілі.
     *
     * До збірки 16 веб-екран «Я › Тема» писав вибір лише у свій localStorage:
     * обрала там «День» — месенджер кремовий, а рідна оболонка навколо нього
     * (смуга вкладок, ескіз старту, заставка) і далі в Ночі. Тепер на
     * телефоні вибір один і живе на рідному боці.
     */
    @JavascriptInterface
    fun getThemeMode(): String = webThemeModeOf(
        local.phantom.companion.core.design.theme.startupThemeMode(
            container.applicationContext
                .getSharedPreferences(local.phantom.companion.core.design.theme.THEME_PREFS_FILE, Context.MODE_PRIVATE)
                .getString(local.phantom.companion.core.design.theme.THEME_MODE_PREF_KEY, null),
            null,
        ),
    )

    /** Профіль › Вигляд до першого кадру веба (акцент, щільність); зміну приносить `__phantomAppearance`. */
    @JavascriptInterface
    fun getAppearance(): String = WebMessengerLook.webLook(container.appearance.state.value).toString()

    @JavascriptInterface
    fun setThemeMode(mode: String) {
        nativeThemeModeOf(mode)?.let { container.themeModeRequest.value = it }
    }

    @JavascriptInterface
    fun setProfiles(json: String): Boolean = container.profilesMirror.apply(json)

    @JavascriptInterface
    fun profileAlerts(): Boolean = true

    @JavascriptInterface
    fun getDeviceVitals(): String = runCatching {
        val batteryIntent = container.applicationContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        // null, а не правдоподібне число: нечитаний лічильник, показаний як
        // «100%» чи «25 °C», не відрізнити від виміряного.
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else null
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val tempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempC = if (tempTenths > 0) tempTenths / 10.0 else null

        val actMan = container.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { actMan?.getMemoryInfo(it) }
        val totalMemMb = (memInfo?.totalMem ?: 0L) / (1024 * 1024)
        val availMemMb = (memInfo?.availMem ?: 0L) / (1024 * 1024)
        val usedMemMb = if (totalMemMb > availMemMb) totalMemMb - availMemMb else 0L

        val state = container.contextEngine?.state?.value?.name ?: "FOCUS"
        val sttEngine = container.voiceManager.activeEngine.value.name
        val peerCount = container.peers.peers.value.size

        JSONObject().apply {
            put("battery", batteryPct ?: JSONObject.NULL)
            put("charging", isCharging)
            put("tempC", tempC ?: JSONObject.NULL)
            put("state", state)
            put("sttEngine", sttEngine)
            put("ramUsedMb", usedMemMb)
            put("ramTotalMb", totalMemMb)
            put("peerCount", peerCount)
            put("platform", "Android Companion")
            put("sdk", Build.VERSION.SDK_INT)
            put("model", Build.MODEL)
        }.toString()
    }.getOrDefault("{}")

    @JavascriptInterface
    fun speakText(text: String): Boolean = runCatching {
        container.scope.launch(Dispatchers.Main) {
            container.ttsEngine.speak(text)
        }
        triggerHaptic("tap")
        true
    }.getOrDefault(false)

    @JavascriptInterface
    fun stopSpeaking(): Boolean = runCatching {
        container.scope.launch(Dispatchers.Main) {
            container.ttsEngine.stop()
        }
        triggerHaptic("tap")
        true
    }.getOrDefault(false)

    @JavascriptInterface
    fun notifyMessage(fromName: String, body: String, conversationId: String?): Boolean = runCatching {
        val convId = conversationId ?: "peer_incoming"
        val notice = PeerAnnounce.Notice(
            key = convId,
            fromId = convId,
            groupId = null,
            groupName = null,
            text = body,
        )
        val quiet = container.quietHours.hushesNow(convId)
        PeerNotifications.notifyMessage(
            context = container.applicationContext,
            notice = notice,
            senderName = fromName,
            body = body,
            atMs = System.currentTimeMillis(),
            quiet = quiet,
        )
        if (!quiet) triggerHaptic("receive")
        true
    }.getOrDefault(false)

    private fun quietJson(q: QuietHours = container.quietHours.state.value): JSONObject = JSONObject()
        .put("enabled", q.enabled)
        .put("from", QuietHours.format(q.from))
        .put("to", QuietHours.format(q.to))
        .put("except", JSONArray(q.except.sorted()))

    private fun patchQuiet(body: String?): String {
        val o = body?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return fail(400, "bad_body", say(R.string.msgr_bridge_bad_body))
        val was = container.quietHours.state.value
        fun time(key: String, fallback: java.time.LocalTime) =
            if (o.has(key)) QuietHours.parse(o.optString(key)) else fallback
        val from = time("from", was.from)
        val to = time("to", was.to)
        if (from == null || to == null) return fail(400, "bad_body", say(R.string.msgr_bridge_bad_body))
        val except = o.optJSONArray("except")?.let { a ->
            (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }.toSet()
        } ?: was.except
        val next = QuietHours(o.optBoolean("enabled", was.enabled), from, to, except)
        container.quietHours.set(next)
        return ok(quietJson(next))
    }

    private fun triggerHaptic(type: String) {
        val pattern = when (type.lowercase()) {
            "send" -> HapticPattern(longArrayOf(0L, 20L, 35L, 25L), intArrayOf(0, 50, 0, 75))
            "receive" -> HapticPattern(longArrayOf(0L, 25L, 40L, 30L), intArrayOf(0, 60, 0, 60))
            "success", "done" -> HapticPattern(longArrayOf(0L, 20L, 30L, 30L), intArrayOf(0, 60, 0, 90))
            "error", "warning" -> HapticPattern(longArrayOf(0L, 30L, 35L, 30L, 35L, 30L), intArrayOf(0, 80, 0, 80, 0, 80))
            "chime" -> HapticPattern(longArrayOf(0L, 40L, 60L, 40L), intArrayOf(0, 50, 0, 70))
            else -> HapticPattern(longArrayOf(0L, 18L), intArrayOf(0, 50))
        }
        runCatching { container.hapticEmitter.emit(pattern) }
    }

    private val look = WebMessengerLook(
        object : WebMessengerLook.Port {
            override fun look() = container.appearance.state.value
            override fun setChat(chatId: String, wallpaper: local.phantom.companion.data.appearance.Wallpaper?) =
                container.appearance.setChat(chatId, wallpaper)
            override fun putChatPhoto(chatId: String, bytes: ByteArray) =
                local.phantom.companion.data.appearance.AppearancePhotos.jpeg(bytes)?.let {
                    container.appearance.putPhoto(local.phantom.companion.data.appearance.AppearanceStore.Target.Chat(chatId), it)
                }
        },
    )

    private val hush = WebMessengerHush(
        object : WebMessengerHush.Port {
            override fun live(nowMs: Long) = container.chatHush.live(nowMs)
            override fun set(chatId: String, untilMs: Long?, title: String?) = container.chatHush.set(chatId, untilMs, title)
            override fun clear(chatId: String) = container.chatHush.clear(chatId)
            override fun setRules(rules: Map<String, local.phantom.companion.data.HushRule>) = container.chatHush.setRules(rules)
        },
    )

    private val reminders = WebMessengerReminders(
        object : WebMessengerReminders.Store {
            override suspend fun add(text: String, atMs: Long) = container.reminderStore.add(text = text, triggerAtMs = atMs)
            override suspend fun pending() =
                container.reminderStore.pending(50).map { WebMessengerReminders.Row(it.id, it.text, it.triggerAtMs) }
            override suspend fun remove(id: String) = container.reminderStore.remove(id)
        },
    )
    private val selfRoute = local.phantom.companion.self.PhantomSelfAndroid.route(container.applicationContext, { container.phantomSelf }) { container.aiRouter }
    private val transcribeRoute by lazy { WebMessengerTranscribe.forApp(container) }

    private fun pairStateJson(): JSONObject = when (val st = pcPairing.state) {
        local.phantom.companion.data.PcPairing.State.Idle -> JSONObject().put("state", "idle")
        local.phantom.companion.data.PcPairing.State.Working -> JSONObject().put("state", "working")
        local.phantom.companion.data.PcPairing.State.Done -> JSONObject().put("state", "done")
        is local.phantom.companion.data.PcPairing.State.Failed ->
            JSONObject().put("state", "failed").put("reason", st.why.name.lowercase())
    }

    private suspend fun route(method: String, path: String, query: String, body: String?): String {
        val mesh = container.mesh
        val roster = container.peers
        val rawSeg = path.trim('/').split('/')
        val seg = if (rawSeg.isNotEmpty() && (rawSeg[0] == "chat" || rawSeg[0] == "agency")) {
            listOf("messenger") + rawSeg
        } else if (rawSeg.size >= 2 && rawSeg[0] == "api" && rawSeg[1] == "v1") {
            listOf("messenger") + rawSeg.drop(2)
        } else {
            rawSeg
        }
        if (supportRoute.claims(seg)) return supportRoute.handle(method, seg, body)
        if (seg.size < 2 || seg[0] != "messenger") return fail(404, "not_found", say(R.string.msgr_bridge_no_path, path))
        // Першою, до груп і людей: будь-яка гілка нижче може дійти до сітки.
        if (WebMessengerSaved.claims(seg)) return savedRoute(method, seg, query, body)
        // Сцена зустрічі (заклади, бали — на сервер; дотик по картці — у дріт): до груп і людей їй діла немає.
        if (scene.claims(method, seg)) return scene.handle(method, seg, body)
        if (places.claims(method, seg)) return places.handle(method, seg, query)
        if (selfRoute.claims(seg)) return selfRoute.handle(method, seg, body)
        if (transcribeRoute.claims(seg)) return transcribeRoute.handle(method, seg, body)
        if (reminders.claims(seg)) return reminders.handle(method, seg, body)
        if (hush.claims(seg)) return hush.handle(method, seg, body)
        if (day.claims(seg)) return day.handle(method, seg, body)
        if (look.claims(seg)) return look.handle(method, seg, body)
        if (WebMessengerRegion.claims(method, seg)) return ok(WebMessengerRegion.body(local.phantom.companion.service.AirAlertGate.profile(container.applicationContext)))
        if (container.moments.route.claims(seg)) return container.moments.route.handle(method, seg, body)
        if (ownDevices.claims(seg)) return ownDevices.handle(method, seg, body)
        if (circlesRoute.claims(seg)) return circlesRoute.handle(method, seg, body)
        if (container.contactRequests.route.claims(seg)) return container.contactRequests.route.handle(method, seg, body)
        if (container.groupLinks.claims(seg)) return container.groupLinks.handle(method, seg, body)
        if (staffRoute.claims(seg)) return staffRoute.handle(method, seg, body)
        if (spacesRoute.claims(seg)) return spacesRoute.handle(method, seg, body)
        if (WebMessengerSolana.claims(seg)) return solana.handle(method, seg, body)
        // Розд. 07: «Мої прохання» і екран «Без інтернету» (WebMessengerIntents).
        if (intentsRoute.claims(seg)) return intentsRoute.handle(method, seg, body)
        // Розд. 07 §7: «Профіль › Офлайн-мапи» — рюкзак перед підвалом.
        if (backpackRoute.claims(seg)) return backpackRoute.handle(method, seg)
        if (shareInRoute.claims(seg)) return shareInRoute.handle(method, seg, body)
        // RELAY_FS.md §10: «Профіль › Для розробника» — перемикач і стан конверта v5.
        if (relayFsRoute.claims(seg)) return relayFsRoute.handle(method, seg)

        // Розмова з групою й розмова з людиною ходять ОДНИМИ шляхами: веб не
        // мусить знати, що перед ним. Розводить їх сам журнал — id групи в
        // ньому або є, або немає, і здогад по довжині рядка тут не потрібен.
        val group = if (seg[1] == "conversations" && seg.size >= 3) {
            WebMessengerGroups.find(mesh.outbox.value, seg[2])
        } else {
            null
        }

        return when {
            group != null -> groupRoute(method, seg, query, body, group)
            seg[1] == "agency" && seg.size == 2 && method == "GET" -> {
                val arr = JSONArray()
                container.agencyManager.allSessions().forEach { s ->
                    arr.put(JSONObject().put("id", s.id).put("title", s.title).put("topic", s.topic))
                }
                ok(arr)
            }
            seg[1] == "agency" && seg.size == 2 && method == "POST" -> {
                val o = body?.let { JSONObject(it) } ?: return fail(400, "bad_body", say(R.string.msgr_bridge_bad_body))
                val title = o.optString("title").trim().ifBlank { say(R.string.msgr_bridge_new_project) }
                val topic = o.optString("topic").trim().ifBlank { say(R.string.msgr_bridge_new_topic) }
                val s = container.agencyManager.createSession(title, topic)
                ok(JSONObject().put("id", s.id).put("title", s.title).put("topic", s.topic))
            }
            seg[1] == "chat" && seg.getOrNull(2) == "sessions" && method == "GET" -> {
                val arr = JSONArray()
                container.agencyManager.allSessions().forEach { s ->
                    arr.put(JSONObject().put("id", s.id).put("title", s.title).put("created_at", iso(s.createdAtMs)))
                }
                arr.put(JSONObject().put("id", AgencySessionManager.PHANTOM_AGENT_ID).put("title", AgencySessionManager.PHANTOM_AGENT_NAME).put("created_at", JSONObject.NULL))
                ok(arr)
            }
            seg[1] == "chat" && seg.getOrNull(2) == "message" && method == "POST" -> askAgent(body)
            seg[1] == "identity" && method == "GET" -> ok(identity())
            seg[1] == "identity" && method == "PATCH" -> updateIdentity(body)
            seg[1] == "identity" && method == "POST" -> updateIdentity(body)
            seg[1] == "profile" && method == "PATCH" -> updateIdentity(body)
            seg[1] == "profile" && method == "POST" -> updateIdentity(body)
            seg[1] == "profile" && method == "GET" -> ok(identity())
            seg[1] == WebMessengerWhere.SEGMENT && seg.size >= 3 -> whereRoute(method, seg[2], seg.getOrNull(3), body)
            seg[1] == "live" && seg.getOrNull(2) == "start" && method == "POST" -> {
                val o = body?.let { JSONObject(it) }
                val targetPeerId = o?.optString("peer_id").orEmpty()
                val minutes = o?.optInt("minutes", 60) ?: 60
                val span = local.phantom.companion.core.net.peer.PeerLiveShare.spanFor(minutes)
                val res = container.liveShare.start(targetPeerId, span)
                ok(JSONObject().put("started", true).put("reach", res.name))
            }
            seg[1] == "live" && seg.getOrNull(2) == "stop" && method == "POST" -> {
                val o = body?.let { JSONObject(it) }
                val targetPeerId = o?.optString("peer_id").orEmpty()
                container.liveShare.stop(targetPeerId)
                ok(JSONObject().put("stopped", true))
            }
            seg[1] == "live" && seg.getOrNull(2) == "status" && method == "GET" -> {
                val targetPeerId = query.split('&').firstOrNull { it.startsWith("peer_id=") }?.substringAfter('=') ?: ""
                val standing = container.liveShare.standingFor(targetPeerId)
                val session = container.liveShare.sessionFor(targetPeerId)
                ok(JSONObject().apply {
                    put("sharing_active", session != null)
                    put("peer_sharing", standing != null)
                    if (standing != null) {
                        put("peer_lat", standing.lat)
                        put("peer_lon", standing.lon)
                        put("peer_updated_at", iso(standing.atMs))
                    }
                })
            }
            seg[1] == "roads" && method == "GET" -> ok(roads())
            seg[1] == "queue" && seg.getOrNull(2) == "status" -> ok(JSONObject().put(
                "queued", WebMessengerKinds.queuedLetters(mesh.outbox.value),
            ))
            seg[1] == "queue" && seg.getOrNull(2) == "flush" -> {
                if (!mesh.pollRelayNow()) local.phantom.companion.service.PeerMeshGate.foregrounded()
                // Нічого не підтверджено синхронно — нуль, а не здогадка.
                ok(JSONObject().put("delivered", 0))
            }
            seg[1] == "contacts" && seg.size == 2 && method == "GET" ->
                ok(JSONArray().also { arr -> people(roster.peers.value).forEach { arr.put(contact(it)) } })
            seg[1] == "contacts" && seg.size == 2 && method == "POST" -> addContact(body)
            seg[1] == "contacts" && seg.size == 4 && seg[3] == "verify" -> {
                roster.setVerified(seg[2], true)
                roster.peers.value.firstOrNull { it.id == seg[2] }?.let { ok(contact(it)) }
                    ?: fail(404, "no_contact", say(R.string.msgr_bridge_no_contact))
            }
            seg[1] == "contacts" && seg.size == 4 && seg[3] == "continue" && method == "POST" -> {
                roster.continueAfterKeyChange(seg[2])
                roster.peers.value.firstOrNull { it.id == seg[2] }?.let { ok(contact(it)) }
                    ?: fail(404, "no_contact", say(R.string.msgr_bridge_no_contact))
            }
            seg[1] == "blocked" && seg.size == 2 && method == "GET" ->
                ok(JSONObject().put("blocked", JSONArray(container.peerBlocks.blocked.value.sorted())))
            seg[1] == "contacts" && seg.size == 4 && seg[3] == "block" && method == "POST" -> {
                container.peerBlocks.block(seg[2])
                // Стан, а не луна: у повний список (PeerBlock.MAX_KEPT) нове імʼя не лягає.
                ok(JSONObject().put("blocked", container.peerBlocks.blocks(seg[2])))
            }
            seg[1] == "contacts" && seg.size == 4 && seg[3] == "report" && method == "POST" -> report(seg[2], body)
            seg[1] == "contacts" && seg.size == 4 && seg[3] == "banner-video" && method == "POST" ->
                ok(JSONObject().put("state", container.bannerPull.ask(seg[2]).name.lowercase()))
            seg[1] == "contacts" && seg.size == 4 && seg[3] == "block" && method == "DELETE" -> {
                container.peerBlocks.unblock(seg[2])
                ok(JSONObject().put("blocked", container.peerBlocks.blocks(seg[2])))
            }
            seg[1] == "conversations" && seg.size == 2 && method == "GET" ->
                ok(idsOf(query)?.let { conversationsOf(it) } ?: conversations())
            // Веб шле сівбу вітрини сюди; телефон її не садить — віддає лише живе.
            seg[1] == "bootstrap" -> ok(conversations())
            seg[1] == "conversations" && seg.getOrNull(2) == "bootstrap" -> ok(conversations())
            // Стан сховка для дзвінків веб читає як «невідомо» саме через
            // відмову — це чесніше за вигадане "offline".
            seg[1] == "relay" ->
                fail(404, "not_bridged", say(R.string.msgr_bridge_not_bridged, seg[1]))
            seg[1] == "ice" && method == "GET" -> container.webMessengerIce.answer()
            // `leave`/`drop` — не роди сигналу, а дії над кімнатою; вони живуть
            // у гілках у кінці цього `when`, і сюди заходити не мають.
            seg[1] == "call" && method == "POST" && seg.getOrNull(2) !in CALL_ROOM_VERBS -> {
                NotificationAsk.onEngagement(container.applicationContext)
                handleCallSignal(seg.getOrNull(2) ?: "", body)
            }
            seg[1] == "haptic" && method == "POST" -> {
                val type = body?.let { JSONObject(it).optString("type") } ?: "tap"
                triggerHaptic(type)
                ok(JSONObject().put("haptic", type))
            }
            seg[1] == "clipboard" && seg.getOrNull(2) == "copy" && method == "POST" -> {
                val text = body?.let { JSONObject(it).optString("text") }.orEmpty()
                val copied = copyToClipboard(text)
                ok(JSONObject().put("copied", copied))
            }
            seg[1] == "clipboard" && seg.getOrNull(2) == "paste" && method == "GET" ->
                ok(JSONObject().put("text", getFromClipboard()))
            seg[1] == "save" && method == "POST" -> saveToPhone(body)
            seg[1] == "share" && method == "POST" -> {
                val o = body?.let { JSONObject(it) }
                val text = o?.optString("text").orEmpty()
                val title = o?.optString("title")
                val shared = shareText(text, title)
                ok(JSONObject().put("shared", shared))
            }
            seg[1] == "map" && seg.getOrNull(2) == "open" -> {
                val lat = query.split('&').firstOrNull { it.startsWith("lat=") }?.substringAfter('=')?.toDoubleOrNull() ?: 0.0
                val lon = query.split('&').firstOrNull { it.startsWith("lon=") }?.substringAfter('=')?.toDoubleOrNull() ?: 0.0
                val label = query.split('&').firstOrNull { it.startsWith("label=") }?.substringAfter('=')
                openMap(lat, lon, label)
                ok(JSONObject().put("opened", true))
            }
            seg[1] == "stt" && seg.getOrNull(2) == "start" && method == "POST" -> {
                startVoiceInput()
                ok(JSONObject().put("listening", true))
            }
            seg[1] == "stt" && seg.getOrNull(2) == "stop" && method == "POST" -> {
                stopVoiceInput()
                ok(JSONObject().put("listening", false))
            }
            seg[1] == "stt" && seg.getOrNull(2) == "status" && method == "GET" ->
                ok(JSONObject().put("available", isVoiceAvailable()).put("engine", container.voiceManager.activeEngine.value.name))
            seg[1] == "vitals" || seg[1] == "telemetry" -> ok(JSONObject(getDeviceVitals()))
            seg[1] == "tts" && seg.getOrNull(2) == "speak" && method == "POST" -> {
                val text = JSONObject(body ?: "{}").optString("text", "")
                if (text.isNotBlank()) speakText(text)
                ok(JSONObject().put("speaking", true))
            }
            seg[1] == "tts" && seg.getOrNull(2) == "stop" && method == "POST" -> {
                stopSpeaking()
                ok(JSONObject().put("speaking", false))
            }
            seg[1] == "notify" && method == "POST" -> {
                val json = JSONObject(body ?: "{}")
                notifyMessage(
                    fromName = json.optString("sender", "Nightpost"),
                    body = json.optString("text", ""),
                    conversationId = if (json.has("chatId")) json.getString("chatId") else null,
                )
                ok(JSONObject().put("notified", true))
            }
            seg[1] == "files" && seg.size == 2 && method == "POST" -> takeBlob(body)
            seg[1] == "files" && seg.size == 4 && seg[3] == "status" && method == "GET" ->
                blobStatus(seg[2])
            // Перепитати нема кого: у меші немає ані сервера, що тримає копію,
            // ані слова «надішли ще раз». Черга шле сама, доки не дошле.
            seg[1] == "files" && seg.size == 4 && seg[3] == "request" -> blobStatus(seg[2])
            seg[1] == "conversations" && seg.size == 2 && method == "POST" -> createConversation(body)
            seg[1] == "conversations" && seg.size == 3 && method == "PATCH" -> {
                val title = body?.let { JSONObject(it).optString("title") }?.trim().orEmpty()
                if (title.isEmpty()) return fail(400, "bad_title", say(R.string.msgr_bridge_bad_title))
                // Та сама межа, що й на тілі листа: назва — теж набране людиною.
                if (PeerFrames.refuseTyped(title) != null) {
                    return fail(422, "service_prefix", say(R.string.peer_typed_service_prefix))
                }
                roster.rename(seg[2], title)
                conversationOf(seg[2])?.let { ok(it) } ?: fail(404, "no_conversation", say(R.string.msgr_bridge_no_conversation))
            }
            seg[1] == "conversations" && seg.size == 4 && seg[3] == "circle" && method == "PATCH" -> setCircle(seg[2], body)
            seg[1] == "today" && seg.getOrNull(2) == "tabs" && method == "GET" -> ok(todayTabs())
            seg[1] == "today" && seg.getOrNull(2) == "tabs" && method == "PUT" -> putTodayTabs(body)
            seg[1] == "conversations" && seg.size == 3 && method == "DELETE" -> {
                container.agencyManager.deleteSession(seg[2])
                mesh.clearConversation(seg[2])
                container.peerFiles.forget(seg[2])
                ok(JSONObject().put("deleted", true))
            }
            seg[1] == "conversations" && seg.size == 4 && seg[3] == "clear" -> {
                val n = mesh.clearConversation(seg[2])
                // Байти йдуть разом з рядками. Стерта розмова, що лишила по
                // собі фотографії на диску, — це не стерта розмова.
                val blobs = if (container.peerFiles.forget(seg[2])) 1 else 0
                ok(JSONObject().put("cleared", n).put("blobs", blobs))
            }
            seg[1] == "conversations" && seg.size == 4 && seg[3] == "read" -> {
                roster.markRead(seg[2])
                local.phantom.companion.service.ShadeDoor.seen(container.applicationContext, seg[2])
                reads.readConversation(
                    seg[2],
                    reads.uptoOf(WebMessengerKinds.dmFeed(mesh.outbox.value, seg[2]), seg[2], seqOf(body)),
                )
                ok(JSONObject().put("unread_count", 0))
            }
            seg[1] == "conversations" && seg.size == 4 && seg[3] == "timer" && method == "POST" -> setTimer(seg[2], body)
            seg[1] == "scheduled" && seg.size == 2 && method == "GET" -> ok(JSONArray(scheduled.list().map(::letterJson)))
            seg[1] == "scheduled" && seg.size == 2 && method == "POST" -> scheduledAdd(body)
            seg[1] == "scheduled" && seg.size == 3 && method == "DELETE" -> scheduledOut(scheduled.cancel(seg[2]))
            seg[1] == "scheduled" && seg.size == 3 && method == "PATCH" -> scheduledEdit(seg[2], body)
            seg[1] == "scheduled" && seg.size == 4 && seg[3] == "send" && method == "POST" -> scheduledOut(scheduled.sendNow(seg[2]))
            seg[1] == "conversations" && seg.size == 4 && seg[3] == "typing" && method == "POST" ->
                ok(JSONObject().put("sent", typing.signal(seg[2], body)))
            privacyRoute.claims(seg) -> privacyRoute.handle(method, body)
            seg[1] == "quiet" && seg.size == 2 && method == "GET" -> ok(quietJson())
            seg[1] == "quiet" && seg.size == 2 && method == "PATCH" -> patchQuiet(body)
            seg[1] == "conversations" && seg.size == 4 && seg[3] == "messages" && method == "GET" ->
                served(seg[2], query, messages(seg[2], afterSeq(query)))
            seg[1] == "conversations" && seg.size == 4 && seg[3] == "messages" && method == "POST" -> {
                NotificationAsk.onEngagement(container.applicationContext)
                append(seg[2], body)
            }
            seg[1] == "conversations" && seg.size == 6 && seg[3] == "messages" &&
                seg[5] == "reactions" && method == "POST" -> react(seg[2], seg[4], body)
            seg[1] == "conversations" && seg.size == 6 && seg[3] == "messages" &&
                seg[5] == "pin" && method == "POST" -> pinLine(seg[2], seg[4], body)
            seg[1] == "conversations" && seg.size == 5 && seg[3] == "messages" && method == "PATCH" ->
                editLine(seg[2], seg[4], body)
            seg[1] == "conversations" && seg.size == 5 && seg[3] == "messages" && method == "DELETE" -> {
                val line = mesh.outbox.value.firstOrNull { it.id == seg[4] }
                    ?: return fail(404, "no_message", say(R.string.msgr_bridge_no_message))
                deleted(revisions.delete(listOf(line), WebMessengerRevise.forEveryone(query)))
            }
            seg[1] == "conversations" && seg.size == 6 && seg[3] == "messages" && seg[5] == "history" && method == "GET" -> {
                val line = mesh.outbox.value.firstOrNull { it.id == seg[4] && (it.toId == seg[2] || it.fromId == seg[2]) }
                    ?: return fail(404, "no_message", say(R.string.msgr_bridge_no_message))
                ok(versionsOf(revisions.history(line)))
            }
            seg[1] == "directory" -> directory.route(method, seg, query, body, ::conversationOf)
            seg[1] == "mine" && seg.size == 2 && method == "POST" -> devices.mark(body, ::conversationOf)
            // «Додати свій пристрій»: скан QR з екрана ПК — і все. Спарювання
            // йде у фоні, веб питає GET, доки не побачить кінця.
            seg[1] == "pair" && seg.size == 2 && method == "POST" -> {
                val input = body?.let { runCatching { JSONObject(it).optString("qr") }.getOrNull() }.orEmpty()
                if (local.phantom.companion.data.PcPairing.parse(input) ==
                    local.phantom.companion.data.PcPairing.Input.Bad
                ) {
                    fail(400, "bad_input", say(R.string.msgr_pair_bad_input))
                } else {
                    pcPairing.start(input)
                    ok(pairStateJson())
                }
            }
            seg[1] == "pair" && seg.size == 2 && method == "GET" -> ok(pairStateJson())
            seg[1] == "web-signin" && seg.size == 2 && method == "POST" -> {
                openWebSignIn()
                ok(JSONObject().put("opened", true))
            }
            // ── профіль на дроті ────────────────────────────────────────
            //
            // «profiles», а не «profile», і це не примха: `seg[1] == "profile"`
            // з POST вище вже зайнято [updateIdentity] (імʼя й аватар ЦЬОГО
            // пристрою на диску), а гілки тут дописуються в кінець. Два
            // різні вчинки — «запамʼятай мене» і «розкажи про мене всім» —
            // мусять мати два різні шляхи в будь-якому разі.
            seg[1] == "profiles" && seg.size == 3 && seg[2] == "publish" && method == "POST" ->
                publishProfile(body)
            seg[1] == "profiles" && seg.size == 2 && method == "GET" -> ok(peerProfiles())
            // Кімната дзвінка: хто в ній, у якому стані кожна нога, скільки
            // місця лишилось. Рушій у вебі знає СВОЇ зʼєднання, але не знає
            // ніг, які телефон завів, поки веба не було на екрані, — і без
            // цієї ручки він малював би порожню кімнату посеред розмови.
            seg[1] == "call" && seg.getOrNull(2) == "room" && method == "GET" -> callRoom()
            // Вийти з кімнати цілком: кожній живій нозі — окремий «поклав».
            seg[1] == "call" && seg.getOrNull(2) == "leave" && method == "POST" ->
                ok(JSONObject().put("left", offLane { container.callHub.leaveNow() }))
            // Веб підняв приймач сигналів: віддати кадри, що чекали на нього (холодний старт із дзвінка).
            seg[1] == "call" && seg.getOrNull(2) == "ready" && method == "POST" -> {
                container.callHub.webReady()
                val hub = container.callHub
                val resume = local.phantom.companion.service.CallKeep.resumeOf(hub.line.value, hub.video.value, ::nameOfPeer)
                ok(JSONObject().put("ready", true).put("resume", resume ?: JSONObject.NULL))
            }
            // Куди йде звук: читає й перемикає рідний [CallAudioRoute], не веб.
            seg[1] == "call" && seg.getOrNull(2) == "audio-route" && method == "GET" ->
                ok(CallAudioRoute.shared(container.applicationContext).describe())
            seg[1] == "call" && seg.getOrNull(2) == "audio-route" && method == "POST" -> {
                val key = body?.let { runCatching { JSONObject(it).optString("route") }.getOrNull() }.orEmpty()
                ok(CallAudioRoute.shared(container.applicationContext).choose(key))
            }
            // Забрати ОДНУ ногу, не роняючи решти розмови.
            seg[1] == "call" && seg.getOrNull(2) == "drop" && method == "POST" -> {
                val who = body?.let { runCatching { JSONObject(it).optString("peer_id") }.getOrNull() }
                if (who.isNullOrBlank()) {
                    fail(400, "no_peer_id", say(R.string.msgr_bridge_call_no_leg))
                } else {
                    ok(JSONObject().put("dropped", offLane { container.callHub.dropLeg(who) }))
                }
            }
            // Підписати заяву на посилання для вкладення. В КІНЕЦЬ навмисно:
            // дерево зливають із шістьма іншими, і нова гілка посередині
            // зробила б конфлікт там, де його могло не бути.
            seg[1] == "attachments" && seg.size == 3 && seg[2] == "claim" && method == "POST" ->
                attachmentClaim(body)
            // Пошук по всіх листах апарата — на вузлі, щоб журнал не їхав у веб.
            seg[1] == "search" && seg.size == 2 && method == "GET" -> searchAll(query)
            // Жива смуга над списком: небо, люди, що показують місце, звʼязок.
            // В КІНЕЦЬ з тієї самої причини, що й гілка вище.
            seg[1] == "pulse" && seg.size == 2 && method == "GET" -> ok(pulse())
            seg[1] == "pulse" && seg.size == 4 && seg[2] == "where" && method == "POST" -> focusPeer(seg[3])
            // Яка розмова зараз на екрані — глушник шторки. Прапорці в
            // AppEventBus існували, а ставив їх ніхто: сповіщення приходило
            // на лист, який людина вже читала (перевірка доставки 24.09).
            seg[1] == "visible" && seg.size == 2 && method == "POST" -> markVisible(body)
            seg[1] == "chrome" && seg.size == 2 && method == "POST" -> markChrome(body)
            // Позначки «непрочитане» з веба — для плашки вкладки «Чати».
            seg[1] == "unread-marks" && seg.size == 2 && method == "PUT" -> unreadMarks(body)
            else -> fail(404, "not_found", say(R.string.msgr_bridge_no_route, method, path))
        }
    }

    // ── пошук ────────────────────────────────────────────────────────────

    /**
     * Ті самі розмови, що в [conversations], і ті самі стрічки, що в
     * [messages]/[groupMessages]/«Збереженому» — інакше пошук знайшов би лист,
     * якого розмова не покаже, або не знайшов би того, що в ній видно.
     */
    private fun searchAll(query: String): String {
        val (q, limit) = WebMessengerSearch.parse(query)
        val log = container.mesh.outbox.value
        val me = container.mesh.selfIdFlow.value
        val sources = ArrayList<WebMessengerSearch.Source>()
        sources += WebMessengerSearch.Source(SavedNotes.ID, saved.feed().map { it.second })
        sources += WebMessengerSearch.Source(
            AgencySessionManager.PHANTOM_AGENT_ID,
            container.mesh.conversation(AgencySessionManager.PHANTOM_AGENT_ID).map { SpokenFold.forPage(it) },
        )
        container.agencyManager.allSessions().forEach { s ->
            sources += WebMessengerSearch.Source(s.id, log.filter { it.toId == s.id || it.fromId == s.id })
        }
        people(container.peers.peers.value).forEach { p ->
            sources += WebMessengerSearch.Source(p.id, container.mesh.conversation(p.id))
        }
        WebMessengerGroups.all(log).forEach { g ->
            sources += WebMessengerSearch.Source(g.id, WebMessengerGroups.feed(log, g.id)) { body ->
                WebMessengerGroups.said(body)?.takeIf { PeerGroup.decodeAct(it) == null }
            }
        }
        val report = WebMessengerSearch.search(q, sources, limit)
        val hits = JSONArray()
        report.hits.forEach { h ->
            hits.put(
                JSONObject()
                    .put("conversation_id", h.conversationId)
                    .put("message_id", h.messageId)
                    .put("author_id", h.authorId)
                    .put(
                        "author_name",
                        when (h.authorId) {
                            AgencySessionManager.PHANTOM_AGENT_ID -> AgencySessionManager.PHANTOM_AGENT_NAME
                            else -> authorName(h.authorId, me)
                        },
                    )
                    .put("field", h.field.wire)
                    .put("text", h.text)
                    .put("sent_at", iso(h.sentAtMs)),
            )
        }
        return ok(
            JSONObject()
                .put("hits", hits)
                .put("scanned", report.scanned)
                .put("conversations", report.conversations)
                .put("truncated", report.truncated),
        )
    }

    // ── видима розмова ───────────────────────────────────────────────────

    /**
     * `{"conversation_id": "…"}` — ця розмова на екрані; `null` — жодна.
     * Група чи людина — вирішує журнал, як і для решти маршрутів.
     */
    private fun report(peerNodeId: String, body: String?): String {
        val messageId = runCatching { JSONObject(body ?: "{}").optString("message_id") }.getOrDefault("")
            .takeIf { it.isNotBlank() && it != "null" }
        container.peerBlocks.block(peerNodeId)
        val mail = AbuseReport.mailto(
            subject = say(R.string.msgr_report_subject),
            body = AbuseReport.body(say(R.string.msgr_report_intro), peerNodeId, messageId, local.phantom.companion.BuildConfig.VERSION_NAME),
        )
        val mailed = runCatching {
            container.applicationContext.startActivity(
                android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse(mail))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
        return ok(
            JSONObject()
                .put("blocked", container.peerBlocks.blocks(peerNodeId))
                .put("mailed", mailed)
                .put("address", AbuseReport.ADDRESS),
        )
    }

    private fun markVisible(body: String?): String {
        val id = runCatching { JSONObject(body ?: "{}").optString("conversation_id") }
            .getOrDefault("")
            .takeIf { it.isNotBlank() && it != "null" }
        val events = container.events
        if (id == null) {
            events.visiblePeerChat.value = null
            events.visiblePeerGroup.value = null
        } else if (WebMessengerGroups.find(container.mesh.outbox.value, id) != null) {
            events.visiblePeerGroup.value = id
            events.visiblePeerChat.value = null
        } else {
            events.visiblePeerChat.value = id
            events.visiblePeerGroup.value = null
        }
        if (id != null) local.phantom.companion.service.ShadeDoor.seen(container.applicationContext, id)
        return ok(JSONObject().put("visible", id ?: JSONObject.NULL))
    }

    private fun markChrome(body: String?): String {
        val hidden = runCatching { JSONObject(body ?: "{}").optBoolean("hidden", false) }.getOrDefault(false)
        container.events.webModalOpen.value = hidden
        return ok(JSONObject().put("hidden", hidden))
    }

    private fun unreadMarks(body: String?): String {
        val ids = runCatching { JSONObject(body ?: "{}").optJSONArray("chat_ids") }.getOrNull()
            ?: return fail(400, "bad_body", say(R.string.msgr_bridge_bad_body))
        val marks = (0 until ids.length()).mapNotNull { ids.optString(it).trim().takeIf { id -> id.isNotEmpty() } }.toSet()
        container.events.webUnreadMarks.value = marks
        return ok(JSONObject().put("marked", marks.size))
    }

    // ── жива смуга ───────────────────────────────────────────────────────

    /** Див. [WebMessengerPulse]: кожне поле — з одного справжнього джерела. */
    private fun pulse(): JSONObject {
        val now = System.currentTimeMillis()
        val people = people(container.peers.peers.value)
            .filter { it.dhPubKey.isNotBlank() && !it.keyAlarm }
            .mapTo(HashSet()) { it.id }
        val sky = WebMessengerPulse.sky(container.airAlertWatch.sky.value, now) {
            local.phantom.companion.feature.stream.ui.map.oblastShortUk(it)
        }
        val near = WebMessengerPulse.near(container.peerPoints.points.value, people, now)
        val direct = container.mesh.myInvite.value?.let { PeerInvite.address(it) } != null
        return JSONObject()
            .put(
                "sky",
                sky?.let {
                    JSONObject()
                        .put("oblast", it.oblast)
                        .put("since_at", it.sinceMs?.let(::iso) ?: JSONObject.NULL)
                        .put("feed_silent", it.feedSilent)
                } ?: JSONObject.NULL,
            )
            .put(
                "near",
                JSONArray().also { arr ->
                    near.forEach { n ->
                        arr.put(
                            JSONObject()
                                .put("peer_id", n.peerId)
                                .put("name", n.name)
                                .put("near", n.near ?: JSONObject.NULL)
                                .put("precision", n.precision ?: JSONObject.NULL)
                                .put("at", iso(n.atMs)),
                        )
                    }
                },
            )
            .put(
                "link",
                JSONObject()
                    .put("online", container.networkProbe.online.value)
                    .put("direct", direct)
                    .put("narrow", container.mesh.linkNarrow.value)
                    .put(
                        "narrow_word",
                        if (container.mesh.linkNarrow.value) say(R.string.msgr_link_narrow) else JSONObject.NULL,
                    ),
            )
    }

    /**
     * Мапа — на людині, яка показує місце. Нової мітки НЕ кладемо: людина вже
     * стоїть на мапі своєю точкою чи плямою району, а мітка в центрі району
     * читалась би як «вона тут».
     */
    private fun focusPeer(peerId: String): String {
        val focus = WebMessengerPulse.focusOf(container.peerPoints.points.value, peerId, System.currentTimeMillis())
            ?: return fail(404, "not_sharing", say(R.string.msgr_pulse_not_sharing))
        container.scope.launch(Dispatchers.Main) {
            runCatching {
                container.spatialLayerRepository.requestFocus(
                    local.phantom.companion.core.data.spatial.FocusTarget(
                        lat = focus.lat,
                        lon = focus.lon,
                        zoom = focus.zoom,
                    ),
                )
            }
            container.homeTabRequest.value = local.phantom.companion.ui.home.HomeTab.MAP
        }
        return ok(JSONObject().put("opened", true))
    }

    // ── особа ────────────────────────────────────────────────────────────

    private suspend fun identity(): JSONObject {
        val mesh = container.mesh
        offLane { mesh.refreshInvite() }
        val inv = mesh.myInvite.value
        val me = runCatching { container.identity.get() }.getOrNull()
        val id = inv?.peerId ?: me?.id.orEmpty()
        val fingerprint = inv?.let { PeerInvite.fingerprint(it.pubKey, it.dhPubKey) } ?: ""
        val address = inv?.let { PeerInvite.address(it) }
        // Те саме правило, що й у самому коді: без живого порту адреси немає
        // ніде. Інакше веб діставав би з `bundle` рівно ту IP, яку зрізано з
        // рядка, і показував би її замість «доставка через сховок».
        val reachable = inv?.takeIf { address != null }
        val bundle = JSONObject()
            .put("peer_id", id)
            .put("name", inv?.name ?: "")
            .put("pub_key", inv?.pubKey ?: me?.publicKeyB64.orEmpty())
            .put("dh_pub_key", inv?.dhPubKey ?: me?.dhPublicKeyB64.orEmpty())
            .put("host", reachable?.host ?: "")
        val activeProfile = offLane { container.profileStore.getActive() }
        val avatarUrl = container.profileHerald.ownPhotoFile()
            ?.let { "${WebMessengerAssets.MOUNT}${WebMessengerAssets.FACES}${WebMessengerAssets.ME}?v=${container.profileHerald.rev}&p=${container.profilesMirror.liveActive()?.id.orEmpty()}" }
            ?: activeProfile?.avatarUri?.takeUnless { container.profileHerald.hasClearingOrPhoto }
        val shownName = container.profilesMirror.liveActive()?.ownName?.trim()?.takeIf { it.isNotEmpty() }
            ?: activeProfile?.displayName
        return JSONObject()
            .put("node_id", id)
            // Знак, що міст говорить `reach`: без нього веб не обіцяє «одного сканування досить».
            .put("reach", WebMessengerReach.wire(true))
            .put("bundle", bundle)
            .put("name", shownName ?: inv?.name ?: "")
            .put("display_name", shownName ?: inv?.name ?: "")
            .put("avatar", avatarUrl ?: JSONObject.NULL)
            .put(
                "profile_photos",
                JSONObject(
                    container.profileHerald.photoUrls(
                        container.profilesMirror.state.value.profiles.map { it.id },
                        WebMessengerAssets.MOUNT + WebMessengerAssets.FACES,
                    ),
                ),
            )
            // Скільки байтів ШИФРОТЕКСТУ цей вузол справді візьме.
            //
            // Доти веб тримав власне число (`MEDIA_LIMIT_BYTES`, 4 МіБ на
            // телефоні), і воно збігалося з [PeerFile.MAX_BYTES] лише тому, що
            // хтось колись переписав його руками в обидва місця й не забув.
            // Рівними їх не тримало ніщо: підніми стелю тут — і веб або
            // відмовляв би файлам, які вузол узяв би, або шифрував десятки
            // мегабайтів, щоб дізнатись про відмову вже ПІСЛЯ роботи.
            //
            // Тепер межу оголошує той, хто її виконує, а веб її питає. Поля
            // може не бути на старому хості — веб трактує це як «вузол ще не
            // каже» і лишає чинний гід, а не падає.
            // Контракт: phantom-os-messenger/docs/MEDIA_LIMIT_HANDSHAKE.md
            .put("max_attachment_bytes", PeerFile.MAX_BYTES)
            .put("compact", inv?.let { PeerInvite.encode(it) } ?: "")
            .put("link", inv?.let { PeerInvite.link(it) } ?: "")
            .put("address", address ?: JSONObject.NULL)
            // Веб підписував адресу як «так вас видно ззовні». За NAT це
            // неправда, і вирішує це факт, а не підпис.
            .put("address_is_local", inv?.let { PeerInvite.isLocalAddress(it) } ?: JSONObject.NULL)
            .put("fingerprint", fingerprint)
            .put("fingerprint_pretty", fingerprint.chunked(4).joinToString(" "))
    }

    private suspend fun updateIdentity(body: String?): String {
        val o = body?.let { JSONObject(it) } ?: return fail(400, "bad_body", say(R.string.msgr_bridge_bad_body))
        val newName = o.optString("name").ifBlank { o.optString("display_name") }.trim()
        val newAvatar = o.optString("avatar").trim().takeIf { it.isNotBlank() }

        val renamed = offLane {
            val active = container.profileStore.getActive()
            val before = active?.displayName
            if (active != null) {
                val updated = active.copy(
                    displayName = if (newName.isNotEmpty()) newName else active.displayName,
                    avatarUri = newAvatar ?: active.avatarUri,
                )
                container.profileStore.update(updated)
            }
            container.mesh.refreshInvite()
            active != null && newName.isNotEmpty() && newName != before
        }
        // Запрошення з новим імʼям бачать лише нові знайомі; ті, хто вже тримає
        // нас, дізнаються тільки з кадру профілю.
        if (renamed) container.profileHerald.bump()
        return ok(identity())
    }

    // Власний ключ читається з Keystore на IO; міст і так живе поза головним потоком.
    private suspend fun myInvite(): PeerInvite.Invite? =
        container.mesh.myInvite.value
            ?: offLane { container.mesh.refreshInvite(); container.mesh.myInvite.value }

    private fun roads(): JSONObject {
        // «Через сховок» стояло «налаштовано» завжди. Складання без
        // phantom.licenseHost лишає адресу порожньою, і телефон не робить
        // назовні жодного запиту — дорога є лише на папері.
        val relayConfigured = local.phantom.companion.BuildConfig.LICENSE_BASE_URL.isNotBlank()
        val arr = JSONArray()
            .put(road("direct", say(R.string.msgr_road_direct_title), true, null, "unknown",
                say(R.string.msgr_road_direct_detail),
                say(R.string.msgr_road_direct_howto)))
            .put(road("relay", say(R.string.msgr_road_relay_title), relayConfigured, null, "unknown",
                if (relayConfigured) say(R.string.msgr_road_relay_detail)
                else say(R.string.msgr_road_relay_detail_absent),
                say(R.string.msgr_road_relay_howto)))
        val configured = (1 + if (relayConfigured) 1 else 0)
        return JSONObject().put("roads", arr).put("configured", configured).put("total", arr.length())
    }

    private fun road(id: String, title: String, configured: Boolean, live: Boolean?, state: String, detail: String, howto: String) =
        JSONObject().put("id", id).put("title", title).put("configured", configured)
            .put("live", live ?: JSONObject.NULL).put("state", state).put("detail", detail).put("howto", howto)

    // ── контакти ─────────────────────────────────────────────────────────

    private fun people(all: List<PeerIdentity>) = all.filter { it.kind == PeerIdentity.Kind.PERSON }

    private suspend fun contact(p: PeerIdentity): JSONObject {
        val hasKey = p.dhPubKey.isNotBlank()
        val me = if (hasKey) myInvite() else null
        // Число розмови — 48 цифр, два відбитки поруч. Шість цифр підбирались за
        // секунди, і у веб вони більше не їдуть під жодним іменем.
        val number = me?.let { PeerSafety.number(it.pubKey, it.dhPubKey, p.pubKey, p.dhPubKey) } ?: ""
        val fingerprint = if (hasKey) PeerInvite.fingerprint(p.pubKey, p.dhPubKey) else ""
        // Ключ є ≠ звʼязок є: скан додає людину лише з нашого боку (див. [WebMessengerReach]).
        val mutual = hasKey && WebMessengerReach.mutual(p) {
            container.mesh.outbox.value.any { it.toId == p.id && it.state == PeerMessage.State.DELIVERED }
        }
        return JSONObject()
            .put("id", p.id)
            .put("peer_node_id", p.id)
            .put("display_name", p.name)
            .put("handle", directory.handleOf(p) ?: JSONObject.NULL)
            // Імʼя дав власник цього телефона — кадр профілю його не перебиває.
            .put("name_by_owner", p.nameByOwner)
            .put("peer_address", p.transport ?: JSONObject.NULL)
            .put("safety_number", number)
            .put("safety_number_pretty", PeerSafety.pretty(number))
            .put("fingerprint", fingerprint)
            .put("fingerprint_pretty", fingerprint.chunked(4).joinToString(" "))
            .put("verified", p.verifiedAtMs > 0L)
            .put("blocked", container.peerBlocks.blocks(p.id))
            .put("verified_by_camera", p.isProven)
            .put("key_changed", p.keyAlarm)
            .put("key_changed_at", if (p.keyChangedAtMs > 0L) iso(p.keyChangedAtMs) else JSONObject.NULL)
            .put("key_source", p.keySource.name.lowercase())
            .put("session_ready", mutual)
            .put("reach", WebMessengerReach.wire(mutual))
            .put("created_at", iso(p.addedAtMs))
            // Профіль, що приїхав кадром PH-PRF. Їде В ТОМУ САМОМУ рядку, що й
            // імʼя: веб уже читає контакти скрізь, де малює людину, і другий
            // виклик «а тепер профіль» означав би рядок, у якому імʼя нове, а
            // аватар ще старий.
            .let { profileInto(it, p) }
    }

    /**
     * Три стани кожного поля профілю — на дроті моста, не лише в кадрі.
     *
     * Веб не має права здогадуватись про це з порожнього рядка: «ще не
     * приїхало» і «людина стерла» мусять виглядати на екрані по-різному, а
     * JSON, у якому обидва — `""`, робить це неможливим. Тож стан їде окремим
     * полем `*_state`, а значення — своїм.
     */
    private fun profileInto(o: JSONObject, p: PeerIdentity): JSONObject {
        val seen = local.phantom.companion.data.VisibilityStore.seenShown(
            p,
            container.visibility.choice.value.hides(container.peers.circlesOf(p.id).isNotEmpty()),
        )
        fun state(v: String?): String = when {
            v == null -> "absent"
            v.isEmpty() -> "cleared"
            else -> "set"
        }
        val avatarB64 = p.avatarThumbB64
        val avatarBytes = avatarB64
            ?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
        val avatarKind = avatarBytes?.let { PeerProfile.imageKind(it) }
        return o
            .put("profile_rev", p.profileRev)
            .put("profile_at", if (p.profileAtMs > 0L) iso(p.profileAtMs) else JSONObject.NULL)
            // «Приїхали байти, яких я не вмію прочитати» — окремий стан, а не
            // мовчазне «немає»: чекати на кадр, який уже прийшов, — це вада.
            .put(
                "avatar_state",
                when {
                    avatarB64 == null -> "absent"
                    avatarB64.isEmpty() -> "cleared"
                    avatarKind == null -> "unreadable"
                    else -> "set"
                },
            )
            .put(
                "avatar_thumb",
                if (avatarKind != null && avatarBytes != null) {
                    "data:${avatarKind.mime};base64," + java.util.Base64.getEncoder().encodeToString(avatarBytes)
                } else {
                    JSONObject.NULL
                },
            )
            .put("avatar_bytes", avatarBytes?.size ?: 0)
            // Фото до 512 px — адресою, не байтами: опитування облич іде кожні
            // 20 с, і фото всіх людей у кожній відповіді гріло б телефон.
            .put(
                "photo",
                if (container.peerPhotos.has(p.id)) {
                    "${WebMessengerAssets.MOUNT}${WebMessengerAssets.FACES}${PeerPhotoStore.nameOf(p.id)}?v=${p.profileRev}"
                } else {
                    JSONObject.NULL
                },
            )
            .put("banner_state", state(p.bannerSpec))
            .put(
                "banner",
                container.peerBanners.row(p.bannerSpec, WebMessengerAssets.MOUNT + WebMessengerAssets.BANNERS)?.let { r ->
                    JSONObject()
                        .put("video_still", r.videoStill)
                        .put(
                            "video",
                            container.bannerPull.videoOf(p.id)?.let { v ->
                                JSONObject().put("blob_id", v.blobId).put("size", v.size).put("ready", container.bannerPull.isReady(v))
                            } ?: JSONObject.NULL,
                        )
                        .put(
                            "items",
                            org.json.JSONArray(
                                r.items.map { JSONObject().put("url", it.url ?: JSONObject.NULL).put("fx", it.fx).put("fy", it.fy) },
                            ),
                        )
                } ?: JSONObject.NULL,
            )
            .put("status_emoji_state", state(p.statusEmoji))
            .put("status_emoji", p.statusEmoji ?: JSONObject.NULL)
            .put("status_text_state", state(p.statusText))
            .put("status_text", p.statusText ?: JSONObject.NULL)
            .put("bio_state", state(p.bio))
            .put("bio", p.bio ?: JSONObject.NULL)
            // Коли людину востаннє ЧУЛИ — будь-яким кадром, і зі сховка теж.
            // Не «у мережі»: це факт з віком, а не стан. `lan_seen_at` — чули
            // саме в цій мережі (mDNS чи прямий звʼязок), тобто справді поруч.
            .put("heard_at", if (seen && p.lastSeenMs > 0L) iso(p.lastSeenMs) else JSONObject.NULL)
            .put("lan_seen_at", if (seen && p.lanSeenMs > 0L) iso(p.lanSeenMs) else JSONObject.NULL)
    }

    /**
     * Профілі, які НАМ прислали. Окремі двері поруч із `/contacts`.
     *
     * Потрібні тому, що не кожна поверхня веба читає контакти: шапка чату й
     * рядок переліку знають лише ідентифікатор розмови. Один дешевий виклик
     * на всіх дешевший за виклик на кожного, а головне — усі поверхні
     * отримують ОДНУ картину, а не кожна свою.
     */
    private fun peerProfiles(): JSONArray {
        val arr = JSONArray()
        people(container.peers.peers.value).forEach { p ->
            arr.put(
                profileInto(
                    JSONObject().put("peer_node_id", p.id).put("display_name", p.name),
                    p,
                ),
            )
        }
        return arr
    }

    /**
     * Свій профіль — УСІМ контактам із ключем, одним кадром на кожного.
     *
     * Розсилка саме ВСІМ, а не співрозмовнику в поточній розмові: профіль не
     * належить розмові (контракт, розділ «Кадр PeerProfile»). Тримає й
     * дошле новим контактам [local.phantom.companion.data.ProfileHerald];
     * заблокованим кадр не йде.
     */
    private suspend fun publishProfile(body: String?): String {
        val o = body?.let { JSONObject(it) }
            ?: return fail(400, "bad_body", say(R.string.msgr_bridge_bad_body))

        // Лічильник веде телефон: очищене сховище вкладки відкотило б rev, і
        // КОЖЕН контакт мовчки відкидав би все подальше. Число веба лише піднімає.
        val asked = o.optLong("rev", 0L)

        fun field(key: String): PeerProfile.Field<String> = when {
            !o.has(key) -> PeerProfile.Field.Absent
            o.isNull(key) -> PeerProfile.Field.Cleared
            else -> {
                val v = o.optString(key)
                if (v.isEmpty()) PeerProfile.Field.Cleared else PeerProfile.Field.Set(v)
            }
        }

        // Роду на дроті немає — приймач впізнає його за байтами. Тож не-WebP і
        // не-JPEG відмовляється ТУТ, а не приїжджає до людини нечитаним.
        fun image(key: String): Pair<PeerProfile.Field<ByteArray>?, String?> = when {
            !o.has(key) -> PeerProfile.Field.Absent to null
            o.isNull(key) || o.optString(key).isEmpty() -> PeerProfile.Field.Cleared to null
            else -> {
                val bytes = runCatching { java.util.Base64.getDecoder().decode(o.optString(key)) }.getOrNull()
                when {
                    bytes == null -> null to fail(400, "bad_avatar", say(R.string.msgr_profile_avatar_unreadable))
                    PeerProfile.imageKind(bytes) == null -> null to fail(400, "bad_avatar", say(R.string.msgr_profile_avatar_kind))
                    else -> PeerProfile.Field.Set(bytes) to null
                }
            }
        }
        val (avatar, avatarRefused) = image("avatar")
        avatarRefused?.let { return it }
        val (photo, photoRefused) = image("photo")
        photoRefused?.let { return it }

        // Стелі тут, а не в [PeerProfile.encode]: звідти відмова — голий null
        // без імені поля й числа, а людині треба знати, що скоротити.
        overflow("avatar", avatar!!.byteSize(), PeerProfile.MAX_AVATAR_BYTES)?.let { return it }
        overflow("photo", photo!!.byteSize(), PeerProfile.MAX_PHOTO_BYTES)?.let { return it }
        overflow("status_emoji", field("status_emoji").utf8Size(), PeerProfile.MAX_STATUS_EMOJI_BYTES)?.let { return it }
        overflow("status_text", field("status_text").utf8Size(), PeerProfile.MAX_STATUS_TEXT_BYTES)?.let { return it }
        overflow("bio", field("bio").utf8Size(), PeerProfile.MAX_BIO_BYTES)?.let { return it }

        val out = container.profileHerald.publish(
            asked,
            PeerProfile.Body(
                rev = 0L,
                avatar = avatar,
                statusEmoji = field("status_emoji"),
                statusText = field("status_text"),
                bio = field("bio"),
                photo = photo,
            ),
        ) ?: return fail(422, "frame_failed", say(R.string.msgr_profile_frame_failed))

        val skipped = JSONArray()
        out.skipped.forEach { (id, why) -> skipped.put(JSONObject().put("peer_node_id", id).put("reason", why)) }
        return ok(
            JSONObject()
                .put("rev", out.rev)
                .put("frame_bytes", out.frameBytes)
                .put("sent", JSONArray(out.sent))
                .put("sent_count", out.sent.size)
                .put("skipped", skipped)
                .put("skipped_count", out.skipped.size)
                .put("avatar", identity().opt("avatar")),
        )
    }

    private fun PeerProfile.Field<ByteArray>.byteSize(): Int =
        (this as? PeerProfile.Field.Set)?.value?.size ?: 0

    private fun PeerProfile.Field<String>.utf8Size(): Int =
        (this as? PeerProfile.Field.Set)?.value?.toByteArray(Charsets.UTF_8)?.size ?: 0

    /** Названа відмова замість мовчазної: поле, скільки є і скільки можна. */
    private fun overflow(field: String, size: Int, cap: Int): String? =
        if (size > cap) {
            fail(413, "too_big", say(R.string.msgr_profile_too_big, field, size, cap))
        } else {
            null
        }

    private suspend fun addContact(body: String?): String {
        val o = body?.let { JSONObject(it) } ?: return fail(400, "bad_body", say(R.string.msgr_bridge_bad_body))
        val compact = PeerInvite.findInText(o.optString("compact").ifBlank { null })
        val named = o.optString("display_name").trim()
        val address = o.optString("address").trim()

        if (compact != null) {
            val invite = when (val d = PeerInvite.decode(compact)) {
                is PeerInvite.Decoded.Ph2 -> d.invite
                else -> return fail(400, "bad_key", say(R.string.msgr_bridge_bad_key))
            }
            val peer = offLane { container.mesh.accept(invite, WebMessengerReach.keySource(o.optString("source"))) }
            if (named.isNotEmpty() && named != peer.name) container.peers.rename(peer.id, named)
            val fresh = container.peers.peers.value.firstOrNull { it.id == peer.id } ?: peer
            return ok(contact(fresh))
        }

        // Пряме підключення за мережевою адресою / IP (Мережа & Хмари)
        val rawAddr = if (address.isNotBlank()) address else o.optString("compact").removePrefix("peer:").substringAfter(":")
        val targetAddr = rawAddr.trim().takeIf { it.isNotBlank() }
            ?: return fail(400, "no_key", say(R.string.msgr_bridge_no_invite))

        val cleanHostPort = targetAddr.removePrefix("http://").removePrefix("https://").trimEnd('/')
        val shaBytes = java.security.MessageDigest.getInstance("SHA-256").digest(cleanHostPort.toByteArray(Charsets.UTF_8))
        val shaHex = shaBytes.joinToString("") { "%02x".format(it) }
        val peerId = "node_" + shaHex.take(16)
        val displayName = named.ifEmpty { say(R.string.msgr_bridge_node_name, cleanHostPort) }
        val directKeyB64 = java.util.Base64.getEncoder().encodeToString(shaBytes)

        val directPeer = PeerIdentity(
            id = peerId,
            name = displayName,
            kind = PeerIdentity.Kind.PERSON,
            pubKey = directKeyB64,
            dhPubKey = directKeyB64,
            transport = cleanHostPort,
            addedAtMs = System.currentTimeMillis(),
            lastSeenMs = System.currentTimeMillis(),
            keySource = PeerIdentity.KeySource.REMOTE,
        )

        container.peers.upsert(directPeer)
        val fresh = container.peers.peers.value.firstOrNull { it.id == peerId } ?: directPeer
        return ok(contact(fresh))
    }

    // ── розмови ──────────────────────────────────────────────────────────

    /** Службові відмови помічника обома мовами: лист лишається тією мовою, якою його сказано. */
    private val assistantNotices: AssistantNotices by lazy { AssistantNotices.load(container.applicationContext) }

    private fun phantomConversation(log: List<PeerMessage>): JSONObject {
        val last = WebMessengerKinds.lastForList(log, AgencySessionManager.PHANTOM_AGENT_ID, assistantNotices)?.let { SpokenFold.forPage(it) }
        val me = container.mesh.selfIdFlow.value
        // Порожня розмова малювалась як розмова з останнім листом: рядок-заготовка
        // ставав на місце сказаного, з автором і датою 01.01.2024. Немає листа —
        // немає ні підпису, ні часу.
        val (kind, snippet) = last?.let { AssistantInput.listLine(it.body) } ?: (null to null)
        return JSONObject()
            .put("id", AgencySessionManager.PHANTOM_AGENT_ID)
            .put("title", AgencySessionManager.PHANTOM_AGENT_NAME)
            .put("kind", "phantom")
            .put("circle", "all")
            .put("handle", "@PhantomSymbiote")
            .put("avatar", "P")
            .put("pinned", true)
            .put("archived", false)
            .put("created_at", last?.let { iso(it.sentAtMs) } ?: JSONObject.NULL)
            .put("updated_at", last?.let { iso(it.sentAtMs) } ?: JSONObject.NULL)
            .put("contact_id", AgencySessionManager.PHANTOM_AGENT_ID)
            .put("peer_node_id", AgencySessionManager.PHANTOM_AGENT_ID)
            .put("is_demo", false)
            // Шапка малювала «Звірено» поруч із панеллю, яка в ТІЙ САМІЙ розмові
            // казала «Ключа співрозмовника ще немає». Звірка — це порівняне
            // число безпеки; з локальним агентом її не було й бути не може.
            // Позначка довіри там, де довіряти нема чому, — найгірше, що
            // месенджер уміє написати, тож тут стоїть те, що сталось: не звіряли.
            .put("contact_verified", VERIFIED_NEVER_HAPPENED)
            .put("contact_key_changed", false)
            .put("unread_count", 0)
            .put("last_kind", kind ?: JSONObject.NULL)
            .put("last_snippet", snippet ?: JSONObject.NULL)
            .also { o -> last?.let { WebMessengerCardLine.row(WebMessengerKinds.fields(it.body)) }?.forEach { (k, v) -> o.put(k, jsonOf(v)) } }
            .put("last_author", last?.let { if (it.fromId == me) "me" else "Nightpost" } ?: JSONObject.NULL)
            .put("last_at", last?.let { iso(it.sentAtMs) } ?: iso(System.currentTimeMillis()))
    }

    private fun agencyConversation(log: List<PeerMessage>, session: AgencySessionManager.AgencySession): JSONObject {
        val last = log.filter { (it.toId == session.id || it.fromId == session.id) && WebMessengerKinds.isVisible(it.body) }.maxByOrNull { it.sentAtMs }
        val me = container.mesh.selfIdFlow.value
        val (kind, snippet) = last?.let { AssistantInput.listLine(it.body) } ?: (null to null)
        return JSONObject()
            .put("id", session.id)
            .put("title", session.title)
            .put("kind", "ai")
            .put("circle", "all")
            .put("handle", "@AgencyCouncil")
            .put("avatar", "🏛️")
            .put("pinned", true)
            .put("archived", false)
            .put("created_at", session.createdAtMs.takeIf { it > 0L }?.let { iso(it) } ?: JSONObject.NULL)
            .put("updated_at", last?.let { iso(it.sentAtMs) } ?: JSONObject.NULL)
            .put("contact_id", session.id)
            .put("peer_node_id", session.id)
            .put("is_demo", false)
            // Та сама правда, що й у [phantomConversation]: колегія живе на
            // цьому апараті, ключа співрозмовника в ній немає, звірки не було.
            .put("contact_verified", VERIFIED_NEVER_HAPPENED)
            .put("contact_key_changed", false)
            .put("unread_count", 0)
            .put("last_kind", kind ?: JSONObject.NULL)
            .put("last_snippet", snippet ?: JSONObject.NULL)
            .put("last_author", last?.let { if (it.fromId == me) "me" else "Agency" } ?: JSONObject.NULL)
            .put("last_at", last?.let { iso(it.sentAtMs) } ?: JSONObject.NULL)
    }

    private fun conversations(): JSONArray {
        val log = container.mesh.outbox.value
        val roster = container.peers.peers.value.filter { it.kind == PeerIdentity.Kind.PERSON || devices.isMine(it) }
        val dupes = duplicateNames(people(roster))
        val phantomConv = phantomConversation(log)
        // Свіжа установка показувала «Колегію Агентів» як розмову, якої не було:
        // вбудована сесія існує завжди, тож список вигадував співрозмовника.
        // Вбудована зʼявляється, коли її СКЛИКАЛИ; створену людиною видно одразу,
        // бо її створення — і є та дія.
        val agencyConvs = container.agencyManager.allSessions()
            .filter { s -> !s.isDefault || log.any { it.toId == s.id || it.fromId == s.id } }
            .map { s -> agencyConversation(log, s) }

        val rows = roster.map { p ->
            (WebMessengerKinds.lastVisible(log, p.id)?.sentAtMs ?: p.addedAtMs) to conversation(p, dupes)
        } + WebMessengerGroups.all(log).map { g -> PeerGroup.lastAt(log, g.id) to groupConversation(g) }
        val arr = JSONArray()
        arr.put(savedRow())
        arr.put(phantomConv)
        agencyConvs.forEach { arr.put(it) }
        rows.sortedByDescending { it.first }.forEach { arr.put(it.second) }
        return arr
    }

    /**
     * Лише названі розмови — на дельту (див. [WebMessengerDeltas]). Зниклої
     * розмови в `rows` немає, її id — у `gone`.
     */
    private fun conversationsOf(ids: List<String>): JSONObject {
        val log = container.mesh.outbox.value
        val rows = JSONArray()
        val gone = JSONArray()
        ids.forEach { id ->
            val row = WebMessengerGroups.find(log, id)?.let { groupConversation(it) } ?: conversationOf(id)
            if (row != null) rows.put(row) else gone.put(id)
        }
        return JSONObject().put("rows", rows).put("gone", gone)
    }

    private fun idsOf(query: String): List<String>? =
        query.split('&').firstOrNull { it.startsWith("ids=") }?.substringAfter('=')
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()

    /**
     * Повна видача стає опорою; `?changed=1` віддає лише різницю з нею.
     * Без опори — `{"full":true}`, і веб бере розмову цілком.
     */
    private fun served(conv: String, query: String, rows: JSONArray): String {
        val pairs = (0 until rows.length()).map { i ->
            val o = rows.getJSONObject(i)
            o.optString("id") to o.toString()
        }
        if (query.split('&').none { it == "changed=1" }) {
            if (afterSeq(query) == 0) rowCache.baseline(conv, pairs)
            return ok(rows)
        }
        val d = rowCache.diff(conv, pairs) ?: return ok(JSONObject().put("full", true))
        return ok(
            JSONObject()
                .put("upsert", JSONArray().also { a -> d.upsert.forEach { a.put(JSONObject(it)) } })
                .put("remove", JSONArray(d.remove)),
        )
    }

    private val rowCache = WebMessengerDeltas.RowCache()

    private fun conversationOf(peerId: String): JSONObject? {
        if (peerId == SavedNotes.ID) return savedRow()
        val log = container.mesh.outbox.value
        if (peerId == AgencySessionManager.PHANTOM_AGENT_ID) return phantomConversation(log)
        val agencySession = container.agencyManager.findSession(peerId)
        if (agencySession != null) return agencyConversation(log, agencySession)
        return container.peers.peers.value.firstOrNull { it.id == peerId }?.let {
            conversation(it, duplicateNames(people(container.peers.peers.value)))
        }
    }

    /**
     * Пʼять записів «Ноутбук» поспіль (кожен парний тестовий сеанс лишає
     * свій) — і жоден рядок їх не розрізняв: людина тицяла навмання. Імʼя
     * рідне лишається, доки воно не одне на кількох — тоді до нього йде
     * коротка позначка ключа, а не мовчазний дубль.
     */
    private fun duplicateNames(roster: List<PeerIdentity>): Set<String> =
        roster.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys

    private fun displayTitle(p: PeerIdentity, dupes: Set<String>): String =
        WebMessengerDirectory.title(
            devices.shownName(p), p.name, p.id, directory.handleOf(p), p.name in dupes,
            untitled = say(local.phantom.companion.feature.stream.R.string.peer_card_untitled),
        )

    private fun conversation(p: PeerIdentity, dupes: Set<String> = emptySet()): JSONObject {
        val log = container.mesh.outbox.value
        val last = WebMessengerKinds.lastVisible(log, p.id)
        val lastAtt = last?.let { PeerFile.describe(it.body) }
        val me = container.mesh.selfIdFlow.value
        val row = last?.let { WebMessengerKinds.rowFields(it.body) } ?: emptyMap()
        val kinds = last?.let { WebMessengerKinds.fields(it.body) } ?: emptyMap()
        val snippet = when {
            kinds[WebMessengerKinds.NOTICE] != null -> notice(kinds, solanaWho(p.id, last?.fromId == me))
            !WebMessengerKinds.isSpokenKind(row["last_kind"]) -> null
            // Голосове й кружок рядок називає родом («Голосове»), а не іменем файла запису.
            lastAtt != null -> lastAtt.caption.ifBlank {
                if (lastAtt.kind == PeerFile.VOICE || lastAtt.kind == PeerFile.VIDEO_NOTE) "" else lastAtt.name
            }
            else -> last?.body?.trim()?.replace(SPACES, " ")
        }?.takeIf { it.isNotEmpty() }
        return JSONObject()
            .put("id", p.id)
            .put("title", displayTitle(p, dupes))
            .put("kind", "dm")
            .put("circle", container.peers.circleOf(p.id))
            .put("circles", JSONArray(container.peers.circlesOf(p.id).toList()))
            .put("handle", directory.handleOf(p) ?: JSONObject.NULL)
            .put("mine", devices.isMine(p))
            .put("avatar", JSONObject.NULL)
            .put("pinned", false)
            .put("archived", false)
            .put("created_at", iso(p.addedAtMs))
            .put("updated_at", iso(last?.sentAtMs ?: p.addedAtMs))
            .put("contact_id", p.id)
            .put("peer_node_id", p.id)
            .put("is_demo", false)
            .put("contact_verified", p.verifiedAtMs > 0L)
            .put("contact_key_changed", p.keyAlarm)
            .put("contact_key_changed_at", if (p.keyAlarm && p.keyChangedAtMs > 0L) iso(p.keyChangedAtMs) else JSONObject.NULL)
            .put("contact_key_hold", p.sendHeld)
            .put("disappear_s", container.disappear.current(p.id)?.seconds ?: 0L)
            .put("unread_count", WebMessengerKinds.unread(log, p.id, p.readUpToMs))
            // Рід і пласкі поля рядка — тим самим розбором, що й лист. Сире
            // тіло сюди не потрапляє взагалі: рядок списку — друге місце, де
            // власник бачив `PH-RTE:1788…` цілим.
            .also { o ->
                o.put("last_kind", row["last_kind"] ?: JSONObject.NULL)
                row.forEach { (key, value) -> if (key != "last_kind") o.put(key, jsonOf(value)) }
            }
            // Уривок є лише там, де тіло — слова людини або підпис вкладення.
            // Для картки його складе веб зі слова свого перекладу, а не з дроту.
            .put("last_snippet", snippet ?: JSONObject.NULL)
            .put("last_author", last?.let { if (it.fromId == me) "me" else p.name } ?: JSONObject.NULL)
            .put("last_at", last?.let { iso(it.sentAtMs) } ?: JSONObject.NULL)
            .also { o ->
                val seen = log.filter {
                    (it.toId == p.id || it.fromId == p.id) &&
                        PeerOutbox.isSpoken(it) && WebMessengerKinds.isVisible(it.body)
                }
                calm(o, WebMessengerCalm.pulse(seen, me, System.currentTimeMillis()))
            }
    }

    /** Три числа спокійного потоку — див. [WebMessengerCalm]. */
    private fun calm(o: JSONObject, pulse: WebMessengerCalm.Pulse) {
        o.put("last_self_at", pulse.selfAtMs?.let { iso(it) } ?: JSONObject.NULL)
        o.put("last_peer_at", pulse.peerAtMs?.let { iso(it) } ?: JSONObject.NULL)
        o.put("letters_30d", pulse.letters30d)
    }

    private fun createConversation(body: String?): String {
        val o = body?.let { JSONObject(it) } ?: return fail(400, "bad_body", say(R.string.msgr_bridge_bad_body))
        if (o.optString("kind") == "agency") {
            val title = o.optString("title").trim().ifBlank { say(R.string.msgr_bridge_new_project) }
            val topic = o.optString("topic").trim().ifBlank { say(R.string.msgr_bridge_new_topic) }
            val s = container.agencyManager.createSession(title, topic)
            return ok(agencyConversation(container.mesh.outbox.value, s))
        }
        val wanted = ids(o, "member_ids").ifEmpty { ids(o, "contact_ids") }
        val kind = o.optString("kind")
        if (kind == WebMessengerGroups.KIND || kind == PeerGroup.KIND_CHANNEL || kind == PeerGroup.KIND_SPACE || wanted.isNotEmpty()) {
            return createGroup(
                title = o.optString("title").trim(),
                wanted = wanted,
                avatar = o.optString("avatar").trim(),
                circle = o.optString("circle").trim(),
                about = o.optString("description").trim().ifEmpty { o.optString("about").trim() },
                channel = kind == PeerGroup.KIND_CHANNEL,
                space = kind == PeerGroup.KIND_SPACE,
            )
        }
        val contactId = o.optString("contact_id").ifBlank { null }
            ?: return fail(400, "no_contact", say(R.string.msgr_bridge_needs_contact))
        return conversationOf(contactId)?.let { ok(it) } ?: fail(404, "no_contact", say(R.string.msgr_bridge_no_contact))
    }

    /**
     * Група зʼявляється тоді, коли поїхав її ПЕРШИЙ лист.
     *
     * Свого сховища в групи немає: [PeerGroup.known] складає її з листів, які
     * везуть склад у заголовку. Тож назва без людей — це не порожня група, це
     * ніщо: воно зникне з першим перезапуском, а людина вважатиме, що створила.
     * Тому відмова названа вголос, а не тихо.
     */
    private fun createGroup(
        title: String,
        wanted: List<String>,
        avatar: String = "",
        circle: String = "",
        about: String = "",
        channel: Boolean = false,
        space: Boolean = false,
    ): String {
        if (title.isEmpty()) return fail(400, "bad_title", say(R.string.msgr_bridge_bad_title))
        if (PeerFrames.refuseTyped(title) != null) {
            return fail(422, "service_prefix", say(R.string.peer_typed_service_prefix))
        }
        val me = container.mesh.selfIdFlow.value
        if (me.isBlank()) return fail(503, "no_identity", say(R.string.msgr_bridge_no_identity))
        val roster = container.peers.peers.value
        // Кого не взято — названо у відповіді (`dropped`), а не проковтнуто:
        // група без Петра, про яку екран каже «створено», — це зустріч, на яку
        // половину не покликали.
        val split = WebMessengerGroupInvite.split(wanted, roster, me)
        val members = split.members
        if (members.size + 1 > PeerGroup.capOf(if (space) PeerGroup.KIND_SPACE else PeerGroup.KIND_GROUP)) {
            return fail(409, "full", say(R.string.msgr_group_full))
        }
        val group = PeerGroup.Group(
            id = PeerGroup.newId(),
            name = title,
            memberIds = (listOf(me) + members).distinct(),
        )
        val sent = container.mesh.sendToGroup(group, say(R.string.msgr_group_opening))
        if (sent.isEmpty()) return fail(503, "unsent", say(R.string.msgr_group_unsent))
        if (channel) container.mesh.sendGroupAct(group, PeerGroup.Act.Set(PeerGroup.Setting.KIND, PeerGroup.KIND_CHANNEL))
        if (space) container.mesh.sendGroupAct(group, PeerGroup.Act.Set(PeerGroup.Setting.KIND, PeerGroup.KIND_SPACE))
        // Аж після того, як лист поїхав: група, якої не сталося, не лишає по
        // собі картинку в сховищі, що пережила б її на перезапуск.
        if (avatar.isNotEmpty()) container.groupAvatars.set(group.id, avatar)
        if (circle.isNotEmpty()) container.peers.setCircle(group.id, circle)
        val appliedAbout = if (about.isNotEmpty() && about.length <= PeerGroup.ABOUT_MAX && PeerFrames.refuseTyped(about) == null) {
            container.mesh.sendGroupAct(group, PeerGroup.Act.About(about))
            about
        } else {
            ""
        }
        return ok(
            groupConversation(group.copy(about = appliedAbout)).put(
                "dropped",
                JSONArray().also { arr ->
                    split.dropped.forEach { d ->
                        arr.put(
                            JSONObject()
                                .put("id", d.id)
                                .put("name", d.name ?: JSONObject.NULL)
                                .put("reason", d.reason),
                        )
                    }
                },
            ),
        )
    }

    private fun ids(o: JSONObject, key: String): List<String> {
        val arr = o.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).trim().ifBlank { null } }
    }

    // ── групи ────────────────────────────────────────────────────────────

    private val groupReactions by lazy {
        WebMessengerGroupReactions(
            log = { container.mesh.outbox.value },
            me = { container.mesh.selfIdFlow.value },
            keyed = { container.peers.peers.value.filter { it.dhPubKey.isNotBlank() }.mapTo(HashSet()) { it.id } },
            send = { to, frame -> container.mesh.send(to, frame) },
        )
    }

    private suspend fun groupRoute(
        method: String,
        seg: List<String>,
        query: String,
        body: String?,
        group: PeerGroup.Group,
    ): String {
        val mesh = container.mesh
        return when {
            seg.size == 3 && method == "GET" -> ok(groupConversation(group))
            seg.size == 3 && method == "PATCH" -> renameGroup(group, body)
            // «Видалити розмову» для групи — це стерти її тут. Виходу з групи
            // воно не означає: піти й забути — різні дії, і плутати їх нечесно.
            seg.size == 3 && method == "DELETE" -> {
                val deleted = mesh.clearGroup(group.id) > 0
                val blobs = if (container.peerFiles.forget(group.id)) 1 else 0
                container.groupAvatars.set(group.id, "")
                ok(JSONObject().put("deleted", deleted).put("blobs", blobs))
            }
            seg.size == 4 && seg[3] == "clear" -> {
                val cleared = mesh.clearGroup(group.id)
                val blobs = if (container.peerFiles.forget(group.id)) 1 else 0
                ok(JSONObject().put("cleared", cleared).put("blobs", blobs))
            }
            seg.size == 4 && seg[3] == "read" -> {
                val newest = WebMessengerGroups.feed(mesh.outbox.value, group.id)
                    .filterNot { it.fromId == mesh.selfIdFlow.value }
                    .maxOfOrNull { it.sentAtMs } ?: 0L
                container.groupReads.markRead(group.id, newest)
                local.phantom.companion.service.ShadeDoor.seen(container.applicationContext, group.id)
                reads.readGroup(group, WebMessengerGroups.feed(mesh.outbox.value, group.id), seqOf(body))
                ok(JSONObject().put("unread_count", 0))
            }
            seg.size == 4 && seg[3] == "typing" && method == "POST" ->
                ok(JSONObject().put("sent", typing.signal(group.id, body)))
            seg.size == 4 && seg[3] == "messages" && method == "GET" ->
                served(group.id, query, groupMessages(group, afterSeq(query)))
            seg.size == 4 && seg[3] == "messages" && method == "POST" -> appendToGroup(group, body)
            seg.size == 5 && seg[3] == "messages" && method == "DELETE" -> {
                val line = WebMessengerGroups.feed(mesh.outbox.value, group.id)
                    .firstOrNull { it.id == seg[4] }
                    ?: return fail(404, "no_message", say(R.string.msgr_bridge_no_message))
                // Стирається ВЕСЬ віяр цього рядка: прибрати ту копію, по якій
                // тиснули, означало б лишити рядок на екрані вдруге.
                deleted(revisions.delete(fanOf(line), WebMessengerRevise.forEveryone(query)))
            }
            seg.size == 6 && seg[3] == "messages" && seg[5] == "delivery" ->
                deliveryOf(group, seg[4])
            seg.size == 4 && seg[3] == "members" && method == "GET" -> ok(membersOf(group))
            seg.size == 4 && seg[3] == "members" && method == "POST" -> addMember(group, body)
            seg.size == 5 && seg[3] == "members" && method == "DELETE" -> removeMember(group, seg[4])
            seg.size == 4 && seg[3] == "leave" && method == "POST" -> leaveGroup(group)
            // Щабель, опис і закріплене — у кінці `when`, бо дерево зливають
            // з іншими бригадами: нова гілка вище переформатувала б чужі.
            seg.size == 6 && seg[3] == "members" && seg[5] == "role" && method == "PATCH" ->
                grantRole(group, seg[4], body)
            seg.size == 4 && seg[3] == "about" && method == "PATCH" -> describeGroup(group, body)
            seg.size == 4 && seg[3] == "pin" && method == "POST" -> pinMessage(group, body)
            seg.size == 4 && seg[3] == "settings" && method == "PATCH" -> setGroupSettings(group, body)
            seg.size == 6 && seg[3] == "members" && seg[5] == "rights" && method == "PATCH" ->
                setMemberRights(group, seg[4], body)
            seg.size == 6 && seg[3] == "messages" && seg[5] == "history" && method == "GET" -> {
                val line = WebMessengerGroups.feed(mesh.outbox.value, group.id).firstOrNull { it.id == seg[4] }
                    ?: return fail(404, "no_message", say(R.string.msgr_bridge_no_message))
                ok(versionsOf(revisions.history(line)))
            }
            seg.size == 5 && seg[3] == "messages" && method == "PATCH" -> {
                val line = WebMessengerGroups.feed(mesh.outbox.value, group.id)
                    .firstOrNull { it.id == seg[4] }
                    ?: return fail(404, "no_message", say(R.string.msgr_bridge_no_message))
                val text = body?.let { runCatching { JSONObject(it) }.getOrNull() }?.optString("body")
                    ?: return fail(400, "bad_body", say(R.string.msgr_bridge_bad_body))
                if (text.isBlank()) return fail(400, "empty", say(R.string.msgr_bridge_empty_text))
                when (val r = revisions.edit(fanOf(line), text)) {
                    is WebMessengerRevise.Outcome.Edited -> {
                        val seq = WebMessengerGroups.feed(mesh.outbox.value, group.id)
                            .indexOfFirst { it.id == r.line.id } + 1
                        ok(groupMessage(group, r.line, if (seq > 0) seq else 1, mesh.selfIdFlow.value).put("frame", r.frame))
                    }
                    else -> refusedRevise(r)
                }
            }
            seg.size == 6 && seg[3] == "messages" && seg[5] == "reactions" && method == "POST" -> groupReact(group, seg[4], body)
            else -> fail(404, "not_found", say(R.string.msgr_bridge_no_route, method, "/" + seg.joinToString("/")))
        }
    }

    private fun groupReact(group: PeerGroup.Group, messageId: String, body: String?): String =
        when (val r = groupReactions.handle(group, messageId, body)) {
            is WebMessengerGroupReactions.Outcome.Refused -> fail(r.status, r.code, say(r.why.res))
            is WebMessengerGroupReactions.Outcome.Done -> {
                val seq = WebMessengerGroups.feed(container.mesh.outbox.value, group.id).indexOfFirst { it.id == r.line.id } + 1
                ok(groupMessage(group, r.line, if (seq > 0) seq else 1, container.mesh.selfIdFlow.value).put("missed", JSONArray(r.missed)))
            }
        }

    private fun liveRoom(groupId: String, me: String): JSONObject? {
        val live = WebMessengerGroups.live(container.callHub.live.value, groupId, me, System.currentTimeMillis())
            ?: return null
        return JSONObject()
            .put("call_id", live.callId)
            .put("started_at", iso(live.startedAtMs))
            .put("faces", JSONArray(live.faces))
            .put("mine", live.mine)
    }

    private fun setCircle(id: String, body: String?): String {
        val group = WebMessengerGroups.find(container.mesh.outbox.value, id)
        if (group == null && container.peers.peers.value.none { it.id == id && it.kind == PeerIdentity.Kind.PERSON }) {
            return fail(404, "no_conversation", say(R.string.msgr_bridge_no_conversation))
        }
        val circle = body?.let { runCatching { JSONObject(it).optString("circle") }.getOrNull() }.orEmpty()
        if (!container.peers.setCircle(id, circle)) return fail(400, "bad_circle", say(R.string.msgr_bridge_bad_body))
        return ok(group?.let { groupConversation(it) } ?: conversationOf(id) ?: JSONObject())
    }

    private fun todayTabs(): JSONObject = tabsJson(container.todayTabs.get(container.mesh.selfIdFlow.value))

    private fun putTodayTabs(body: String?): String {
        val o = body?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return fail(400, "bad_body", say(R.string.msgr_bridge_bad_body))
        fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        return ok(tabsJson(container.todayTabs.put(container.mesh.selfIdFlow.value, TodayTabsStore.Tabs(list("order"), list("hidden")))))
    }

    private fun tabsJson(t: TodayTabsStore.Tabs): JSONObject =
        JSONObject().put("order", JSONArray(t.order)).put("hidden", JSONArray(t.hidden))

    private fun standing(group: PeerGroup.Group): PeerGroup.Standing =
        PeerGroup.standing(container.mesh.outbox.value, group, container.mesh.selfIdFlow.value)

    private fun groupConversation(group: PeerGroup.Group): JSONObject {
        val log = container.mesh.outbox.value
        val me = container.mesh.selfIdFlow.value
        val lines = WebMessengerGroups.feed(log, group.id)
        val state = PeerGroup.stateOf(log, group.id)
        val last = lines.lastOrNull()
        val fields = last?.let { groupFields(group, it) } ?: emptyMap()
        val snippet = when {
            // Рядок переліку — друге місце, де сирий дріт потрапляв на очі.
            // Позначка моделі знімається тут із тієї ж причини, що й у тілі.
            aiSaid(fields) != null -> aiSaid(fields)?.trim()?.replace(SPACES, " ")
            fields[WebMessengerKinds.NOTICE] != null -> notice(fields)
            fields[WebMessengerGroups.ACT] != null -> last?.let { actWords(group, it) }
            !WebMessengerKinds.isSpokenKind(fields[WebMessengerKinds.KIND]) -> null
            else -> last?.let { WebMessengerGroups.said(it.body) }?.trim()?.replace(SPACES, " ")
        }?.takeIf { it.isNotEmpty() }
        return JSONObject()
            .put("id", group.id)
            .put("title", group.name)
            .put("kind", WebMessengerGroups.KIND)
            .put("settings", WebMessengerGroups.settingsJson(state))
            .put("circle", container.peers.circleOf(group.id))
            .put("circles", JSONArray(container.peers.circlesOf(group.id).toList()))
            .put("handle", JSONObject.NULL)
            // Обличчя з дроту має ПЕРЕВАГУ над місцевим: місцеве — це те, що
            // поставив цей телефон, дротове — те, що чинне для всіх. Поки
            // обличчя не їхало нікуди, різниці не існувало; тепер існує, і
            // мовчки показувати своє замість спільного означало б, що двоє в
            // одній гілці бачать різні гілки.
            .put(
                "avatar",
                group.face.ifBlank { container.groupAvatars.avatarOf(group.id).orEmpty() }
                    .ifBlank { null } ?: JSONObject.NULL,
            )
            .put("description", group.about.ifBlank { null } ?: JSONObject.NULL)
            .put("pinned", false)
            .put("archived", false)
            .put("standing", standing(group).name.lowercase(java.util.Locale.US))
            .also { o ->
                val pin = state?.pinned
                val line = pin?.let { p ->
                    lines.firstOrNull { it.fromId == p.authorId && it.sentAtMs == p.atMs }
                }
                // Закріплене, якого цей телефон НЕ МАЄ, — це не «нічого
                // закріплено»: рядок закріпили до того, як він сюди приїхав,
                // або він приїхав і був стертий. Порожній id при наявному
                // авторі каже саме це, і поверхня скаже так само.
                o.put(
                    "pinned_message",
                    if (pin == null) {
                        JSONObject.NULL
                    } else {
                        JSONObject()
                            .put("id", line?.id ?: "")
                            .put("author_id", pin.authorId)
                            .put("author", authorName(pin.authorId, me))
                            .put("at", iso(pin.atMs))
                            .put(
                                "text",
                                line?.let { WebMessengerGroups.said(it.body) }
                                    ?.takeIf { !PeerGroup.isSystem(it) }
                                    ?.trim()?.replace(SPACES, " ") ?: JSONObject.NULL,
                            )
                    },
                )
            }
            .put("created_at", iso(lines.firstOrNull()?.sentAtMs ?: 0L))
            .put("updated_at", iso(last?.sentAtMs ?: 0L))
            // Співрозмовника в групи немає, і вигадувати його не можна: веб
            // читає це поле, щоб вирішити, чиє повідомлення чиє, і будь-яке
            // значення тут зробило б чужі репліки власними.
            .put("contact_id", JSONObject.NULL)
            .put("peer_node_id", JSONObject.NULL)
            .put("is_demo", false)
            .put("live_room", liveRoom(group.id, me) ?: JSONObject.NULL)
            .put("plan", WebMessengerPlanRow.of(lines, group.memberIds) { WebMessengerCardMarks.fold(log) }
                ?.let { JSONObject(WebMessengerPlanRow.json(it).toString()) } ?: JSONObject.NULL)
            .put("unread_count", PeerGroup.unread(log, group.id, me, container.groupReads.readUpTo(group.id)))
            .also { o ->
                WebMessengerKinds.shipped(fields).forEach { (key, value) ->
                    if (key !in ROW_HEAVY) o.put("last_" + key, jsonOf(value))
                }
                WebMessengerCardLine.row(fields).forEach { (key, value) -> o.put(key, jsonOf(value)) }
            }
            .put("last_snippet", snippet ?: JSONObject.NULL)
            .put("last_author", last?.let { authorName(it.fromId, me) } ?: JSONObject.NULL)
            .put("last_at", last?.let { iso(it.sentAtMs) } ?: JSONObject.NULL)
            .also { o ->
                // Дії над складом («Петро приєднався») — не листування: мовчазна
                // людина не стає активною від того, що її додали.
                val spoken = lines.filter { WebMessengerGroups.actOf(it.body) == null }
                calm(o, WebMessengerCalm.pulse(spoken, me, System.currentTimeMillis()))
            }
    }

    /**
     * Рід і поля групового рядка.
     *
     * Дія над складом — не «схований службовий вантаж», як у розмові вдвох:
     * тут вона змістовна, і саме нею людина дізнається, що хтось приєднався
     * чи вийшов. Тож вона їде родом «text», а речення до неї складає міст із
     * ресурсів — на дроті летить дієслово, не переклад.
     */
    private fun groupFields(group: PeerGroup.Group, m: PeerMessage): Map<String, Any?> {
        val said = WebMessengerGroups.said(m.body)
            ?: return mapOf(
                WebMessengerKinds.KIND to WebMessengerKinds.BROKEN,
                WebMessengerKinds.BROKEN_KIND to WebMessengerGroups.KIND,
            )
        val act = PeerGroup.decodeAct(said)
            ?: return WebMessengerKinds.fields(said)
        return mapOf(
            WebMessengerKinds.KIND to WebMessengerKinds.TEXT,
            WebMessengerGroups.ACT to when (act) {
                is PeerGroup.Act.Add -> "add"
                is PeerGroup.Act.Remove -> "remove"
                PeerGroup.Act.Leave -> "leave"
                PeerGroup.Act.Rename -> "rename"
                is PeerGroup.Act.Grant -> "grant"
                is PeerGroup.Act.Transfer -> "transfer"
                is PeerGroup.Act.Pin -> if (act.atMs == 0L) "unpin" else "pin"
                is PeerGroup.Act.About -> "about"
                is PeerGroup.Act.Face -> "face"
                is PeerGroup.Act.Set -> "set"
                is PeerGroup.Act.Rights -> "rights"
                is PeerGroup.Act.Space -> "space"
            },
            WebMessengerGroups.ACT_TARGET to (act as? PeerGroup.Act.Add)?.id,
        )
    }

    private fun groupMessages(group: PeerGroup.Group, afterSeq: Int): JSONArray {
        val log = container.mesh.outbox.value
        val me = container.mesh.selfIdFlow.value
        val lines = WebMessengerGroups.feed(log, group.id)
        // Один прохід по журналу на всю розмову, а не на кожен рядок.
        val cardFolded = WebMessengerCardMarks.fold(log)
        val reactFolded = PeerMark.foldAll(log, PeerMark.What.REACTION)
        val arr = JSONArray()
        lines.forEachIndexed { i, m ->
            if (i + 1 > afterSeq) arr.put(groupMessage(group, m, i + 1, me, cardFolded, reactFolded))
        }
        return arr
    }

    private fun groupMessage(
        group: PeerGroup.Group,
        m: PeerMessage,
        seq: Int,
        me: String,
        cardFolded: Map<String, Map<String, List<String>>>? = null,
        reactFolded: Map<String, Map<String, List<String>>>? = null,
    ): JSONObject {
        val fields = groupFields(group, m)
        val said = WebMessengerGroups.said(m.body).orEmpty()
        val log = container.mesh.outbox.value
        val fan = PeerGroup.fanState(log, group.id, m.sentAtMs)
        return JSONObject()
            .put("id", m.id)
            .put("conversation_id", group.id)
            .put("client_id", m.id)
            .put("seq", seq)
            .put("channel_id", WebMessengerGroups.channelOf(m.body) ?: JSONObject.NULL)
            .put("author_id", m.fromId)
            // Підпис під словами моделі — не імʼя того, чий вузол їх привіз.
            // Модель стоїть на ОДНОМУ апараті групи, тож лист неминуче їде
            // чужим конвертом; але приписати його людині означало б вкласти їй
            // у вуста те, чого вона не казала. Хто саме порахував — окремим
            // полем `ai_via`, за тим самим правилом «порожньо = я».
            .put(
                "author_name",
                if (aiSaid(fields) != null) say(R.string.msgr_group_ai_author)
                else authorName(m.fromId, me),
            )
            .also { o ->
                if (aiSaid(fields) != null) o.put(WebMessengerAi.VIA, authorName(m.fromId, me))
            }
            .also { o -> WebMessengerKinds.shipped(fields).forEach { (key, value) -> o.put(key, jsonOf(value)) } }
            .let { cardInto(it, fields) }
            .let { cardMarksInto(it, fields, m, group, cardFolded) }
            .put(
                "reactions",
                reactionsOf(
                    WebMessengerCardMarks.targetOf(m),
                    WebMessengerGroupReactions.onLine(reactFolded ?: PeerMark.foldAll(log, PeerMark.What.REACTION), m, group.memberIds),
                    me,
                ),
            )
            .also { solana.decorate(it, said, m.fromId, m.id) }
            .put("body", if (fields[WebMessengerGroups.ACT] != null) actWords(group, m) else bodyOf(fields, said))
            .put("ciphertext", JSONObject.NULL)
            .put("transport", WebMessengerGroups.road(log, group.id, m, me) ?: JSONObject.NULL)
            .put("reply_to_id", WebMessengerGroups.foldedReplyId(log, group.id, m.replyToId).ifBlank { null } ?: JSONObject.NULL)
            .also { o -> if (m.mentions.isNotEmpty()) o.put("mentions", JSONArray(m.mentions)) }
            .carried(m)
            .put("sent_at", iso(m.sentAtMs))
            .put("edited_at", m.editedAtMs.takeIf { it > 0L }?.let { iso(it) } ?: JSONObject.NULL)
            .put("deleted_at", m.deletedAtMs.takeIf { it > 0L }?.let { iso(it) } ?: JSONObject.NULL)
            // Стан згорнутого рядка — найменш просунутий з усіх копій
            // ([PeerGroup.collapse]): «доставлено», поки одна копія лежить у
            // черзі, було б обіцянкою за тих, хто нічого не отримав.
            .also { o ->
                // Картка посилання — лише там, де посилання є: інакше журнал не перебираємо.
                if (PeerPreview.firstUrl(m.body) != null) {
                    WebMessengerPreview.json(container.mesh.outbox.value, m)?.let { o.put("link_preview", it) }
                }
            }
            .put("delivery_state", state(m.state))
            .let { relayInto(it, m) }
            // Опис вкладення в групі може приїхати ззовні, а байти — ні:
            // шматки возить [PeerMesh.sendMany], і він адресний. Тож стан
            // читається чесно й скаже «немає», а не змовчить.
            .put("attachment_state", PeerFile.describe(said)?.let { stateOf(it) } ?: JSONObject.NULL)
            // Скільки з них уже дійшло. Числа тут, а імена — окремим шляхом:
            // возити склад на кожен рядок дорожче за саму розмову.
            .put("fan_delivered", fan.delivered)
            .also { o -> reads.fanRead(group, m)?.let { o.put("fan_read", it) } }
            .put("fan_total", fan.total)
    }

    private suspend fun appendToGroup(group: PeerGroup.Group, body: String?): String {
        if (standing(group) != PeerGroup.Standing.IN) {
            return fail(409, "not_in_group", say(R.string.msgr_group_gone))
        }
        val o = body?.let { JSONObject(it) } ?: return fail(400, "bad_body", say(R.string.msgr_bridge_bad_body))
        val text = o.optString("body")
        if (text.isBlank()) return fail(400, "empty", say(R.string.msgr_bridge_empty_text))
        when (val gate = WebMessengerGroups.mayPost(container.mesh.outbox.value, group.id, container.mesh.selfIdFlow.value, System.currentTimeMillis())) {
            is WebMessengerGroups.PostGate.Slow ->
                return fail(429, "slow_mode", container.applicationContext.getString(R.string.msgr_group_slow, gate.waitSeconds))
            WebMessengerGroups.PostGate.AdminsOnly -> return fail(403, "channel_admins", say(R.string.msgr_group_channel_admins))
            WebMessengerGroups.PostGate.Open -> Unit
        }
        // Набране не сміє ВДАВАТИ модель. Позначки `PH-AI:` немає в
        // [PeerFrames.Kind] свідомо — інакше `PeerOutbox.isSpoken` не пустив
        // би відповідь у розмову, — тож `refuseTyped` її не судить, і без цієї
        // гілки будь-хто вклав би собі в клавіатуру рядок, що приїде до групи
        // під підписом «⚡ PHANTOM». Та сама відмова, що й для решти позначок:
        // рід називає причину словом, яке людина вже бачила.
        if (WebMessengerAi.isAi(text)) {
            return fail(422, "service_prefix", say(R.string.peer_typed_service_prefix))
        }
        // Той самий заслін, що й у розмові вдвох, і тією самою рукою: група не
        // сміє бути дірою, крізь яку невідомий рід усе ще їде голим тілом.
        val kind = o.optString("kind").ifBlank { WebMessengerKinds.TEXT }
        val replyToId = WebMessengerGroups.replyWithin(container.mesh.outbox.value, group.id, o.optString("reply_to_id"))
        val mentions = o.optJSONArray("mentions")?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() } }.orEmpty()
        val fw = forwardIn(o)
        val qt = quoteIn(o)
        // Канал простору: адресу кладе пристрій, а читачів замкненого каналу
        // рахує рушій прав у вебі. Лист їде лише їм — інших копій немає.
        val channelId = o.optString("channel_id").trim()
        val readers = o.optJSONArray("to")?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() }.toSet() }
        if (channelId.isNotEmpty() || readers != null) {
            if (!PeerGroup.isChannelId(channelId)) return fail(400, "bad_channel", say(R.string.msgr_bridge_bad_body))
            if (PeerGroup.stateOf(container.mesh.outbox.value, group.id)?.isSpace != true) {
                return fail(409, "not_a_space", say(R.string.msgr_bridge_bad_body))
            }
        }
        fun framed(t: String): String = if (channelId.isEmpty()) t else PeerGroup.inChannel(channelId, t) ?: t
        // Голосове чи кружок без опису вкладення — не голос, а слово в чужу бульбашку.
        if (WebMessengerKinds.mediaWithoutDescription(kind, text)) {
            return fail(422, "bad_media", say(R.string.msgr_bridge_media_unreadable))
        }
        val composed = when (val carry = WebMessengerKinds.carry(kind)) {
            WebMessengerKinds.Carry.Typed -> null
            WebMessengerKinds.Carry.Place -> placeFrame(text)
                ?: return fail(422, "bad_place", say(R.string.msgr_bridge_place_unreadable))
            is WebMessengerKinds.Carry.Card -> {
                if (!isJson(text)) {
                    return fail(422, "bad_card", say(R.string.msgr_bridge_card_unreadable))
                }
                PeerCard.encode(PeerCard.Body(carry.what, text))
                    ?: return fail(413, "card_too_big", say(R.string.msgr_bridge_card_too_big))
            }
            is WebMessengerKinds.Carry.Unknown ->
                return fail(422, "kind_not_carried", say(R.string.msgr_bridge_kind_not_carried, carry.kind))
        }
        val sent = if (composed != null) {
            // Складене пристроєм не судиться входом набраного — той стереже
            // НАБРАНЕ й відмовив би власному ж авторові.
            container.mesh.sendToGroup(group, framed(composed), replyToId, o.optBoolean("quiet"), readers, mentions, fw, qt).firstOrNull()
                ?: return fail(503, "unsent", say(R.string.msgr_group_unsent))
        } else if (channelId.isNotEmpty()) {
            if (PeerFrames.refuseTyped(text) != null) {
                return fail(422, "service_prefix", say(R.string.peer_typed_service_prefix))
            }
            container.mesh.sendToGroup(group, framed(text), replyToId, o.optBoolean("quiet"), readers, mentions, fw, qt).firstOrNull()
                ?: return fail(503, "unsent", say(R.string.msgr_group_unsent))
        } else {
            val typed = container.mesh.sendTypedToGroup(group, text, replyToId, o.optBoolean("quiet"), mentions, fw, qt)
            if (typed is PeerFrames.Typed.Refused) {
                return fail(422, "service_prefix", say(R.string.peer_typed_service_prefix))
            }
            (typed as PeerFrames.Typed.Sent).messages.firstOrNull()
                ?: return fail(503, "unsent", say(R.string.msgr_group_unsent))
        }
        val me = container.mesh.selfIdFlow.value
        // Опис поїхав у групу — за ним байти кожному учаснику.
        PeerFile.describe(text)?.let { att ->
            container.peerFiles.copyTo(group.id, att.blobId)
            val bodies = container.peerFiles.bodiesFor(att)
            PeerGroup.fanOut(group, me, readers).forEach { toId ->
                container.mesh.sendMany(toId, bodies)
            }
        }
        if (WebMessengerAi.mentioned(text)) {
            container.agencyManager.refuseGroupAsk(group.id)
        }
        val seq = WebMessengerGroups.feed(container.mesh.outbox.value, group.id)
            .indexOfFirst { it.id == sent.id } + 1
        return ok(groupMessage(group, sent, if (seq > 0) seq else 1, me))
    }

    private fun renameGroup(group: PeerGroup.Group, body: String?): String {
        val o = body?.let { JSONObject(it) }
        val title = o?.optString("title")?.trim().orEmpty()
        // Обличчя міняється без дроту, назва — з дротом. Тому картинка сама по
        // собі не мусить нести назву, інакше змінити її було б неможливо, не
        // перейменувавши групу всім.
        val hasAvatar = o?.has("avatar") == true
        if (title.isEmpty() && !hasAvatar) {
            return fail(400, "bad_title", say(R.string.msgr_bridge_bad_title))
        }
        if (standing(group) != PeerGroup.Standing.IN) {
            return fail(409, "not_in_group", say(R.string.msgr_group_gone))
        }
        var current = group
        if (hasAvatar) {
            // Обличчя гілки більше не місцева примха: воно ЇДЕ. Тому й право
            // на нього те саме, що на назву, і питається до того, як картинка
            // ляже в сховище — інакше в того, кому відмовили, лишалась би своя
            // окрема гілка з чужим обличчям.
            val face = o?.optString("avatar").orEmpty()
            refuseAct(current, PeerGroup.Act.Face(face))?.let { return it }
            if (face.length > PeerGroup.FACE_MAX) {
                return fail(413, "avatar_too_big", say(R.string.msgr_group_avatar_too_big))
            }
            if (!container.groupAvatars.set(current.id, face)) {
                return fail(413, "avatar_too_big", say(R.string.msgr_group_avatar_too_big))
            }
            if (container.mesh.sendGroupAct(current, PeerGroup.Act.Face(face)).isEmpty()) {
                return fail(503, "unsent", say(R.string.msgr_group_unsent))
            }
            current = current.copy(face = face)
        }
        if (title.isEmpty()) return ok(groupConversation(current))
        if (PeerFrames.refuseTyped(title) != null) {
            return fail(422, "service_prefix", say(R.string.peer_typed_service_prefix))
        }
        refuseAct(current, PeerGroup.Act.Rename)?.let { return it }
        if (container.mesh.sendGroupAct(current, PeerGroup.Act.Rename, title).isEmpty()) {
            return fail(503, "unsent", say(R.string.msgr_group_unsent))
        }
        return ok(groupConversation(current.copy(name = title)))
    }

    /** Опис гілки — той самий шлях, те саме право. */
    private fun describeGroup(group: PeerGroup.Group, body: String?): String {
        val about = body?.let { JSONObject(it).optString("about") }?.trim().orEmpty()
        if (standing(group) != PeerGroup.Standing.IN) {
            return fail(409, "not_in_group", say(R.string.msgr_group_gone))
        }
        if (about.length > PeerGroup.ABOUT_MAX) {
            return fail(413, "about_too_big", say(R.string.msgr_group_about_too_big))
        }
        if (PeerFrames.refuseTyped(about) != null) {
            return fail(422, "service_prefix", say(R.string.peer_typed_service_prefix))
        }
        refuseAct(group, PeerGroup.Act.About(about))?.let { return it }
        if (container.mesh.sendGroupAct(group, PeerGroup.Act.About(about)).isEmpty()) {
            return fail(503, "unsent", say(R.string.msgr_group_unsent))
        }
        return ok(groupConversation(group.copy(about = about)))
    }

    /**
     * Щабель людини в гілці.
     *
     * Порожній `role` знімає щабель до учасника — це ПОНИЖЕННЯ, а не
     * «нічого»: окремої дії «зняти адміна» на дроті немає, бо `grant` з
     * найнижчим щаблем і є нею, і другий дієслівний синонім розійшовся б із
     * першим на першій же перевірці прав.
     */
    private fun grantRole(group: PeerGroup.Group, peerId: String, body: String?): String {
        if (standing(group) != PeerGroup.Standing.IN) {
            return fail(409, "not_in_group", say(R.string.msgr_group_gone))
        }
        val wire = body?.let { JSONObject(it).optString("role") }?.trim().orEmpty()
        val role = PeerGroupRoles.Role.of(wire.ifEmpty { PeerGroupRoles.Role.MEMBER.wire })
            ?: return fail(400, "bad_role", say(R.string.msgr_group_why_role_too_high))
        val act = if (role == PeerGroupRoles.Role.OWNER) {
            PeerGroup.Act.Transfer(peerId)
        } else {
            PeerGroup.Act.Grant(peerId, role)
        }
        refuseAct(group, act)?.let { return it }
        if (container.mesh.sendGroupAct(group, act).isEmpty()) {
            return fail(503, "unsent", say(R.string.msgr_group_unsent))
        }
        return ok(membersOf(group))
    }

    /**
     * Закріпити рядок — парою «автор + мить», а не його id.
     *
     * Id у віяра свій на КОЖНУ копію, тож надісланий дротом він указував би в
     * нікуди на кожному чужому пристрої. Веб знає лише id, тож пара читається
     * тут, із власного журналу, — і саме тому закріпити можна лише той рядок,
     * який цей телефон справді має.
     */
    private fun pinMessage(group: PeerGroup.Group, body: String?): String {
        if (standing(group) != PeerGroup.Standing.IN) {
            return fail(409, "not_in_group", say(R.string.msgr_group_gone))
        }
        val messageId = body?.let { JSONObject(it).optString("message_id") }?.trim().orEmpty()
        val act = if (messageId.isEmpty()) {
            PeerGroup.Act.Pin("", 0L)
        } else {
            val line = WebMessengerGroups.feed(container.mesh.outbox.value, group.id)
                .firstOrNull { it.id == messageId }
                ?: return fail(404, "no_message", say(R.string.msgr_bridge_no_message))
            PeerGroup.Act.Pin(line.fromId, line.sentAtMs)
        }
        // Право на закріплення не залежить від того, ЧИЙ рядок закріплюють:
        // модератор на те й модератор, щоб піднімати чуже. Тому судиться сам
        // акт, і зняття судиться ним самим.
        refuseAct(group, act)?.let { return it }
        if (container.mesh.sendGroupAct(group, act).isEmpty()) {
            return fail(503, "unsent", say(R.string.msgr_group_unsent))
        }
        return ok(groupConversation(group))
    }

    /**
     * Хто в групі — засвідчено, але не авторитетно.
     *
     * Склад їде в заголовку кожного листа, тож назвати можна й того, чиї листи
     * тут не відкриваються. Але це слово того, кого ти додав, а не факт про
     * світ, — тому поруч з кожним стоїть [in_roster]: твердження про ТВІЙ
     * список, єдине, що цей пристрій справді може перевірити.
     */
    private fun membersOf(group: PeerGroup.Group): JSONObject {
        val me = container.mesh.selfIdFlow.value
        val log = container.mesh.outbox.value
        // Ролі з ТОГО САМОГО журналу, яким приймач судить акти. Окремого
        // сховища для них немає й не буде: два джерела правди про щабель
        // розійшлись би рівно там, де це найдорожче, — на правах.
        val state = PeerGroup.stateOf(log, group.id)
        val roles = state?.roles ?: PeerGroupRoles.Table(ownerId = "")
        val cap = state?.cap ?: PeerGroup.MAX_MEMBERS
        val arr = JSONArray()
        WebMessengerGroups.members(group, container.peers.peers.value, me).forEach { m ->
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("name", m.name)
                    .put("in_roster", m.inRoster)
                    .put("verified", m.verified)
                    .put("session_ready", m.hasKey)
                    .put("is_me", m.isMe)
                    .put("role", roles.of(m.id).wire)
                    .put(
                        "rights",
                        JSONArray(PeerGroupRoles.Right.entries.filter { roles.has(m.id, it) }.map { it.wire }),
                    )
                    .put(
                        "may_rights",
                        !m.isMe && PeerGroup.refuse(log, group.id, me, PeerGroup.Act.Rights(m.id, emptySet())) == null,
                    )
                    // Кого САМЕ цей оператор може прибрати й підвищити — не
                    // «чи є в нього право взагалі»: адмін не чіпає адміна, і
                    // кнопка над рівним була б кнопкою, що мовчки не робить
                    // нічого. Судить той самий PeerGroup.refuse.
                    .put(
                        "may_remove",
                        !m.isMe && PeerGroup.refuse(log, group.id, me, PeerGroup.Act.Remove(m.id)) == null,
                    )
                    .put(
                        "may_grant",
                        !m.isMe && PeerGroupRoles.Role.entries.any { r ->
                            r != PeerGroupRoles.Role.OWNER &&
                                PeerGroup.refuse(log, group.id, me, PeerGroup.Act.Grant(m.id, r)) == null
                        },
                    )
                    .put(
                        "may_own",
                        !m.isMe && PeerGroup.refuse(log, group.id, me, PeerGroup.Act.Transfer(m.id)) == null,
                    ),
            )
        }
        val addable = JSONArray()
        WebMessengerGroups.addable(group, container.peers.peers.value).forEach { p ->
            addable.put(JSONObject().put("id", p.id).put("name", p.name))
        }
        val powers = JSONObject()
        PeerGroupRoles.Power.entries.forEach { p ->
            powers.put(
                p.name.lowercase(java.util.Locale.US),
                PeerGroupRoles.may(roles, group.memberIds, me, p, cap),
            )
        }
        return JSONObject()
            .put("id", group.id)
            .put("title", group.name)
            .put("members", arr)
            .put("addable", addable)
            .put("max_members", cap)
            .put("authoritative", false)
            .put("standing", standing(group).name.lowercase(java.util.Locale.US))
            .put("my_role", roles.of(me).wire)
            .put("owner_id", roles.ownerId)
            // Право — не факт про світ, а те, що САМЕ ЦЕЙ пристрій застосує зі
            // свого журналу. Пристрій, який не чув половини актів, чесно
            // покаже менше, а не вигадає більше.
            .put("powers", powers)
    }

    /** Налаштування гілки: кожен ключ — окремий акт, суддя той самий. */
    private fun setGroupSettings(group: PeerGroup.Group, body: String?): String {
        if (standing(group) != PeerGroup.Standing.IN) {
            return fail(409, "not_in_group", say(R.string.msgr_group_gone))
        }
        val o = body?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return fail(400, "bad_body", say(R.string.msgr_bridge_bad_body))
        val acts = WebMessengerGroups.settingActs(o)
            ?: return fail(400, "bad_body", say(R.string.msgr_bridge_bad_body))
        if (acts.isEmpty()) return ok(groupConversation(group))
        acts.forEach { act -> refuseAct(group, act)?.let { return it } }
        acts.forEach { act ->
            if (container.mesh.sendGroupAct(group, act).isEmpty()) {
                return fail(503, "unsent", say(R.string.msgr_group_unsent))
            }
        }
        return ok(groupConversation(group))
    }

    private fun setMemberRights(group: PeerGroup.Group, peerId: String, body: String?): String {
        if (standing(group) != PeerGroup.Standing.IN) {
            return fail(409, "not_in_group", say(R.string.msgr_group_gone))
        }
        val arr = body?.let { runCatching { JSONObject(it).optJSONArray("rights") }.getOrNull() }
            ?: return fail(400, "bad_body", say(R.string.msgr_bridge_bad_body))
        val rights = PeerGroupRoles.Right.parse((0 until arr.length()).joinToString(",") { arr.optString(it) })
            ?: return fail(400, "bad_body", say(R.string.msgr_bridge_bad_body))
        val act = PeerGroup.Act.Rights(peerId, rights)
        refuseAct(group, act)?.let { return it }
        if (container.mesh.sendGroupAct(group, act).isEmpty()) {
            return fail(503, "unsent", say(R.string.msgr_group_unsent))
        }
        return ok(membersOf(group))
    }

    private fun addMember(group: PeerGroup.Group, body: String?): String {
        val id = body?.let { JSONObject(it).optString("peer_id") }?.trim().orEmpty()
        if (id.isEmpty()) return fail(400, "bad_body", say(R.string.msgr_bridge_add_who))
        if (standing(group) != PeerGroup.Standing.IN) {
            return fail(409, "not_in_group", say(R.string.msgr_group_gone))
        }
        if (WebMessengerGroups.isFull(group, PeerGroup.stateOf(container.mesh.outbox.value, group.id))) {
            return fail(409, "full", say(R.string.msgr_group_full))
        }
        val peer = container.peers.peers.value.firstOrNull { it.id == id }
            ?: return fail(404, "no_contact", say(R.string.msgr_bridge_no_contact))
        // Без ключа копія не запечатається, і додавання виглядало б зробленим,
        // поки кожен лист до цієї людини мовчки помирав би в черзі.
        if (peer.dhPubKey.isBlank()) return fail(409, "no_key", say(R.string.msgr_group_no_key))
        refuseAct(group, PeerGroup.Act.Add(id))?.let { return it }
        if (container.mesh.sendGroupAct(group, PeerGroup.Act.Add(id)).isEmpty()) {
            return fail(503, "unsent", say(R.string.msgr_group_unsent))
        }
        return ok(membersOf(group.copy(memberIds = group.memberIds + id)))
    }

    private fun removeMember(group: PeerGroup.Group, peerId: String): String {
        if (standing(group) != PeerGroup.Standing.IN) {
            return fail(409, "not_in_group", say(R.string.msgr_group_gone))
        }
        if (peerId !in group.memberIds) return fail(404, "not_member", say(R.string.msgr_group_not_member))
        if (peerId == container.mesh.selfIdFlow.value) {
            return fail(422, "self", say(R.string.msgr_group_not_member))
        }
        refuseAct(group, PeerGroup.Act.Remove(peerId))?.let { return it }
        if (container.mesh.sendGroupAct(group, PeerGroup.Act.Remove(peerId)).isEmpty()) {
            return fail(503, "unsent", say(R.string.msgr_group_unsent))
        }
        return ok(membersOf(group.copy(memberIds = group.memberIds.filterNot { it == peerId })))
    }

    private fun leaveGroup(group: PeerGroup.Group): String {
        if (standing(group) != PeerGroup.Standing.IN) {
            return fail(409, "not_in_group", say(R.string.msgr_group_gone))
        }
        // Вийти можна й без мережі: копії лежатимуть у черзі, і саме тому
        // відповідь каже, скільки людей це вже почуло, а не «готово».
        val sent = container.mesh.sendGroupAct(group, PeerGroup.Act.Leave)
        if (sent.isEmpty()) return fail(503, "unsent", say(R.string.msgr_group_unsent))
        return ok(JSONObject().put("left", true).put("told", sent.size))
    }

    /**
     * Кому дійшло — поіменно.
     *
     * У розмові вдвох доля листа одна. У групі їх стільки, скільки людей, і
     * вони різні: одному доставлено, другий чекає, до третього ключа немає
     * взагалі. Копія — окремий підписаний лист зі своїм адресатом, тож правда
     * про кожного тут не рахується, а просто є.
     *
     * Лише про власні рядки: чужу репліку цей телефон бачить однією копією —
     * своєю, — і про решту віяра не знає нічого. Мовчання тут чесніше за
     * число, зібране з одного свідчення.
     */
    private fun deliveryOf(group: PeerGroup.Group, messageId: String): String {
        val log = container.mesh.outbox.value
        val me = container.mesh.selfIdFlow.value
        val line = log.firstOrNull { it.id == messageId }
            ?: return fail(404, "no_message", say(R.string.msgr_bridge_no_message))
        if (line.fromId != me) {
            return fail(409, "not_mine", say(R.string.msgr_bridge_not_mine))
        }
        val roster = container.peers.peers.value.associateBy { it.id }
        val arr = JSONArray()
        WebMessengerGroups.fan(log, group.id, me, line.sentAtMs).forEach { copy ->
            val peer = roster[copy.toId]
            arr.put(
                JSONObject()
                    .put("peer_id", copy.toId)
                    .put("name", peer?.name?.takeIf { it.isNotBlank() } ?: PeerRoster.shortId(copy.toId))
                    .put("in_roster", peer != null)
                    .put("state", state(copy.state)),
            )
        }
        val fan = PeerGroup.fanState(log, group.id, line.sentAtMs)
        return ok(
            JSONObject()
                .put("message_id", messageId)
                .put("delivered", fan.delivered)
                .put("waiting", fan.waiting)
                .put("failed", fan.failed)
                .put("total", fan.total)
                .put("to", arr),
        )
    }

    /** Речення про дію над складом. На дроті летить дієслово, не переклад. */
    private fun actWords(group: PeerGroup.Group, m: PeerMessage): String {
        val said = WebMessengerGroups.said(m.body).orEmpty()
        val act = PeerGroup.decodeAct(said) ?: return ""
        val me = container.mesh.selfIdFlow.value
        val ctx = appContext()
        val actor = nameOf(m.fromId)
        val mine = m.fromId == me
        return when (act) {
            is PeerGroup.Act.Add -> when {
                mine -> ctx.getString(R.string.msgr_group_sys_add_you_actor, nameOf(act.id))
                act.id == me -> ctx.getString(R.string.msgr_group_sys_add_you_object, actor)
                else -> ctx.getString(R.string.msgr_group_sys_add, actor, nameOf(act.id))
            }
            is PeerGroup.Act.Remove -> when {
                mine -> ctx.getString(R.string.msgr_group_sys_rm_you_actor, nameOf(act.id))
                act.id == me -> ctx.getString(R.string.msgr_group_sys_rm_you_object, actor)
                else -> ctx.getString(R.string.msgr_group_sys_rm, actor, nameOf(act.id))
            }
            PeerGroup.Act.Leave ->
                if (mine) say(R.string.msgr_group_sys_leave_you)
                else ctx.getString(R.string.msgr_group_sys_leave, actor)
            PeerGroup.Act.Rename ->
                if (mine) ctx.getString(R.string.msgr_group_sys_rename_you, group.name)
                else ctx.getString(R.string.msgr_group_sys_rename, actor, group.name)
            is PeerGroup.Act.Grant -> when {
                mine -> ctx.getString(R.string.msgr_group_sys_grant_you_actor, nameOf(act.id), roleWord(act.role))
                act.id == me -> ctx.getString(R.string.msgr_group_sys_grant_you_object, actor, roleWord(act.role))
                else -> ctx.getString(R.string.msgr_group_sys_grant, actor, nameOf(act.id), roleWord(act.role))
            }
            is PeerGroup.Act.Transfer -> when {
                mine -> ctx.getString(R.string.msgr_group_sys_own_you_actor, nameOf(act.id))
                act.id == me -> ctx.getString(R.string.msgr_group_sys_own_you_object, actor)
                else -> ctx.getString(R.string.msgr_group_sys_own, actor, nameOf(act.id))
            }
            is PeerGroup.Act.Pin -> when {
                act.atMs == 0L && mine -> say(R.string.msgr_group_sys_unpin_you)
                act.atMs == 0L -> ctx.getString(R.string.msgr_group_sys_unpin, actor)
                mine -> say(R.string.msgr_group_sys_pin_you)
                else -> ctx.getString(R.string.msgr_group_sys_pin, actor)
            }
            is PeerGroup.Act.About ->
                if (mine) say(R.string.msgr_group_sys_about_you)
                else ctx.getString(R.string.msgr_group_sys_about, actor)
            is PeerGroup.Act.Face ->
                if (mine) say(R.string.msgr_group_sys_face_you)
                else ctx.getString(R.string.msgr_group_sys_face, actor)
            is PeerGroup.Act.Set ->
                if (mine) say(R.string.msgr_group_sys_settings_you)
                else ctx.getString(R.string.msgr_group_sys_settings, actor)
            is PeerGroup.Act.Rights ->
                if (mine) ctx.getString(R.string.msgr_group_sys_rights_you, nameOf(act.id))
                else ctx.getString(R.string.msgr_group_sys_rights, actor, nameOf(act.id))
            is PeerGroup.Act.Space -> ""
        }
    }

    /** Назва щабля людською мовою. На дроті лишається [PeerGroupRoles.Role.wire]. */
    private fun roleWord(role: PeerGroupRoles.Role): String = say(
        when (role) {
            PeerGroupRoles.Role.OWNER -> R.string.msgr_group_role_owner
            PeerGroupRoles.Role.ADMIN -> R.string.msgr_group_role_admin
            PeerGroupRoles.Role.MODERATOR -> R.string.msgr_group_role_mod
            PeerGroupRoles.Role.MEMBER -> R.string.msgr_group_role_member
        },
    )

    /**
     * Відмова приймача, названа причиною, — або null, якщо дія дозволена.
     *
     * Питається тим самим [PeerGroup.refuse], яким судить [PeerGroup.states] на
     * прийманні. Два різні судді на те саме питання дали б кнопку, що
     * натискається і не робить нічого ніде, — саме ту ваду, заради якої
     * причина тут узагалі є.
     */
    private fun refuseAct(group: PeerGroup.Group, act: PeerGroup.Act): String? {
        val why = PeerGroup.refuse(
            container.mesh.outbox.value,
            group.id,
            container.mesh.selfIdFlow.value,
            act,
        ) ?: return null
        val text = say(
            when (why) {
                PeerGroupRoles.Why.NOT_IN_GROUP -> R.string.msgr_group_why_not_in_group
                PeerGroupRoles.Why.SUBJECT_NOT_IN_GROUP -> R.string.msgr_group_why_subject_gone
                PeerGroupRoles.Why.NEEDS_MODERATOR -> R.string.msgr_group_why_needs_moderator
                PeerGroupRoles.Why.NEEDS_ADMIN -> R.string.msgr_group_why_needs_admin
                PeerGroupRoles.Why.NEEDS_OWNER -> R.string.msgr_group_why_needs_owner
                PeerGroupRoles.Why.SUBJECT_OUTRANKS -> R.string.msgr_group_why_outranks
                PeerGroupRoles.Why.ROLE_TOO_HIGH -> R.string.msgr_group_why_role_too_high
                PeerGroupRoles.Why.NOT_A_CHANGE -> R.string.msgr_group_why_no_change
                PeerGroupRoles.Why.GROUP_FULL -> R.string.msgr_group_full
                PeerGroupRoles.Why.ALREADY_IN -> R.string.msgr_group_why_already_in
                PeerGroupRoles.Why.SELF -> R.string.msgr_group_why_self
                PeerGroupRoles.Why.NO_RIGHT -> R.string.msgr_group_why_no_right
            },
        )
        // 403, а не 400: бракує не даних, а права. Веб розрізняє їх, і саме
        // тому може сказати ЧОГО бракує, замість «не вдалось».
        return fail(403, "no_right_" + why.name.lowercase(java.util.Locale.US), text)
    }

    private fun nameOf(peerId: String): String =
        container.peers.peers.value.firstOrNull { it.id == peerId }?.name?.takeIf { it.isNotBlank() }
            ?: PeerRoster.shortId(peerId)

    /** Порожнє імʼя означає «це я» — те саме правило, що й у розмові вдвох. */
    private fun authorName(fromId: String, me: String): String =
        if (fromId == me) "" else nameOf(fromId)

    // ── вкладення ────────────────────────────────────────────────────────

    /**
     * Веб віддає вже ЗАШИФРОВАНІ ним байти, а ключ кладе в тіло листа. Тож
     * тут байти непрозорі: телефон їх не читає й не вміє прочитати — він їх
     * зберігає й везе. Один прохід base64, бо `@JavascriptInterface` уміє
     * лише рядки.
     */
    private fun saveToPhone(body: String?): String {
        val o = body?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return fail(400, "bad_body", say(R.string.msgr_save_bad_body))
        val file = when (val v = WebMessengerSave.judge(o.optString("name"), o.optString("mime"), o.optString("b64"))) {
            is WebMessengerSave.Verdict.Ready -> v
            is WebMessengerSave.Verdict.Refused -> return fail(
                v.status,
                v.code,
                when (v.code) {
                    "too_big" -> say(R.string.msgr_save_too_big, PeerFile.MAX_BYTES / (1024 * 1024))
                    "bad_mime" -> say(R.string.msgr_save_bad_mime)
                    else -> say(R.string.msgr_save_bad_body)
                },
            )
        }
        val saved = WebMessengerSave.write(container.applicationContext.contentResolver, file)
            ?: return fail(500, "write_failed", say(R.string.msgr_save_failed))
        return ok(JSONObject().put("saved", true).put("where", file.shelf.where).put("name", saved))
    }

    private fun takeBlob(body: String?): String {
        val o = body?.let { JSONObject(it) } ?: return fail(400, "bad_body", say(R.string.msgr_files_bad_body))
        val conv = o.optString("conversation_id").trim()
        if (conv.isEmpty()) return fail(400, "no_conversation", say(R.string.msgr_files_no_conversation))
        val raw = o.optString("data_b64")
        if (raw.isEmpty()) return fail(400, "empty_file", say(R.string.msgr_files_empty))
        val bytes = runCatching { android.util.Base64.decode(raw, android.util.Base64.DEFAULT) }
            .getOrNull() ?: return fail(400, "bad_body", say(R.string.msgr_files_unreadable))
        return storeBlob(conv, bytes)
    }

    /** Байти з порту — `ArrayBuffer` без base64 і без JSON (див. [WebMessengerPort]). */
    suspend fun takeBytes(conversationId: String, bytes: ByteArray): String {
        val conv = conversationId.trim()
        if (conv.isEmpty()) return fail(400, "no_conversation", say(R.string.msgr_files_no_conversation))
        return offLane { storeBlob(conv, bytes) }
    }

    private fun storeBlob(conv: String, bytes: ByteArray): String {
        PeerFile.refuse(bytes.size)?.let { why ->
            // Відмова вголос. Файл, який узяли й не повезли, — найгірший з
            // можливих кінців: людина вважає його надісланим.
            return when (why) {
                PeerFile.Refusal.EMPTY -> fail(400, why.code, say(R.string.msgr_files_empty_file))
                PeerFile.Refusal.TOO_BIG -> fail(
                    413,
                    why.code,
                    container.applicationContext.getString(
                        R.string.msgr_files_too_big,
                        PeerFile.MAX_BYTES / (1024 * 1024),
                    ),
                )
            }
        }
        val blobId = container.peerFiles.put(conv, bytes)
            ?: return fail(500, "no_store", say(R.string.msgr_files_no_store))
        return ok(
            JSONObject()
                .put("blob_id", blobId)
                .put("size", bytes.size)
                .put("sha256", PeerFile.sha256Hex(bytes))
                .put("state", "stored"),
        )
    }

    /**
     * Підписати заяву на посилання — і НІЧОГО БІЛЬШЕ.
     *
     * **Навіщо ця гілка.** Байти вкладення тепер можуть їхати сховищем
     * (рішення власника 17.09), а щоб узяти підписане посилання, сервер
     * вимагає заяву, підписану ключем ПРИСТРОЮ. Ключ живе за цією межею й
     * назовні не виходить: веб, що тримає приватний ключ, це веб, із якого
     * ключ витікає першим XSS.
     *
     * **Дві межі, без яких це був би підписувальний оракул.**
     *
     *  1. Підписуються ЛИШЕ заяви на вкладення, і лише двох родів: покласти
     *     обʼєкт такої ваги або прочитати обʼєкт із таким ключем. Ззовні
     *     приходить число або ключ строгої форми — ніколи рядок на підпис.
     *     Речення складає [PeerBlob], разове число й час бере [PeerMesh].
     *  2. Рід речення власний ([PeerBlob.CLAIM_DOMAIN]) і його немає більше
     *     ніде в продукті, тож підпис, узятий тут, не зійде за підпис листа
     *     ([PeerSealing]) чи заяви довідника ([PeerDirectory]).
     *
     * Відмова — це відмова, не порожній підпис: 422 з причиною, бо заява, яку
     * сервер однаково не візьме, краще спиняється тут.
     */
    private fun attachmentClaim(body: String?): String {
        val mesh = container.mesh
        val o = runCatching { JSONObject(body ?: "") }.getOrNull()
            ?: return fail(400, "bad_json", say(R.string.msgr_bridge_bad_json))
        val proof = when (o.optString("op")) {
            "put" -> {
                val size = o.optLong("size", -1L)
                mesh.proveAttachmentPut(size)
                    ?: return fail(422, "bad_size", say(R.string.msgr_claim_bad_size))
            }
            "get" -> {
                val key = o.optString("key")
                mesh.proveAttachmentGet(key)
                    ?: return fail(422, "bad_key", say(R.string.msgr_claim_bad_key))
            }
            else -> return fail(422, "bad_op", say(R.string.msgr_claim_bad_op))
        }
        return ok(
            JSONObject()
                .put("pub_key", proof.pubKey)
                .put("nonce", proof.nonce)
                .put("issued_ms", proof.issuedMs)
                .put("signature", proof.signature),
        )
    }

    private fun blobStatus(blobId: String): String {
        container.peerFiles.settle(blobId)
        // Довжина з каталогу, не з байтів: веб питає цей стан кожні 15 секунд
        // на кожну видиму бульбашку, і читати заради нього цілий файл (до 4 МБ)
        // означало б перечитувати його з диска чотири рази на хвилину.
        val onDisk = container.peerFiles.blob(blobId)?.length()
        val att = container.mesh.outbox.value.asSequence()
            .mapNotNull { PeerFile.carried(it.body) }
            .firstOrNull { it.blobId == blobId }
        val state = when {
            att == null && onDisk != null -> "stored"
            att == null -> "missing"
            else -> stateOf(att)
        }
        return ok(
            JSONObject()
                .put("blob_id", blobId)
                .put("size", att?.size ?: onDisk?.toInt() ?: 0)
                // Відбиток бере лише опис. Порахувати його тут означало б
                // прочитати весь файл — і назвати «відбитком» те, з чим його
                // однаково нема з чим звіряти.
                .put("sha256", att?.sha256 ?: "")
                .put("state", state),
        )
    }

    /**
     * Стан вкладення — з рядків черги, а не з побажань.
     *
     * Свій файл «поїхав» лише коли поїхав КОЖЕН шматок: 170 доставлених із 171
     * означає, що на тому кінці немає нічого. Чужий — з того, що є на диску.
     */
    private fun stateOf(att: PeerFile.Attachment): String {
        val me = container.mesh.selfIdFlow.value
        // Доки сітка не піднялась, ми не знаємо навіть, хто ми. Тоді ЧЕРГА —
        // єдина чесна відповідь: із порожнім «я» власний лист виглядав би
        // чужим, і телефон казав би про своє ж фото «загубилось».
        if (me.isBlank()) return "queued"
        val log = container.mesh.outbox.value
        val line = log.firstOrNull { PeerFile.carried(it.body)?.blobId == att.blobId }
        if (line == null || line.fromId != me) {
            return when (container.peerFiles.fate(line?.fromId.orEmpty(), att)) {
                PeerFileStore.Fate.READY -> "stored"
                PeerFileStore.Fate.RECEIVING -> "queued"
                // Зібралось, але відбиток не збігся: воно не «ще їде», воно не
                // приїде. Веб перекладає це на «зіпсоване», а не на очікування.
                PeerFileStore.Fate.BROKEN -> "broken"
                PeerFileStore.Fate.ABSENT -> "missing"
            }
        }
        // Заголовок, не тіло: інакше кожне читання розмови розбирало б base64
        // всіх шматків усіх файлів.
        val chunks = log.filter { PeerFile.head(it.body)?.blobId == att.blobId }
        if (chunks.isEmpty()) {
            // Шматки повністю доставленого файла черга забуває одразу
            // ([PeerOutbox.prune]), тож їхня відсутність — це «усе поїхало», а
            // не «нічого не клали»: недовезений шматок з журналу не зникає.
            return if (line.state == PeerMessage.State.DELIVERED) "sent" else "queued"
        }
        return when {
            chunks.any { it.state == PeerMessage.State.FAILED } -> "missing"
            chunks.all { it.state == PeerMessage.State.DELIVERED } -> "sent"
            else -> "queued"
        }
    }

    // ── листи ────────────────────────────────────────────────────────────

    private fun messages(peerId: String, afterSeq: Int): JSONArray {
        val allMessages = if (peerId.startsWith("agency_")) {
            container.mesh.outbox.value
                .filter { (it.toId == peerId || it.fromId == peerId) && WebMessengerKinds.isVisible(it.body) }
                .sortedBy { it.sentAtMs }
        } else {
            WebMessengerKinds.dmFeed(container.mesh.outbox.value, peerId)
        }
        val lines = WebMessengerKinds.feed(allMessages).map { SpokenFold.forPage(it) }
        // Згортається ПОВНИЙ журнал, а не стрічка: позначки в стрічці немає за
        // побудовою ([PeerFrames.Kind.REACTION] несе reachesConversation=false),
        // тож фолд по `lines` завжди давав би порожньо — мовчазна поразка з боку
        // того, хто нібито все полагодив.
        //
        // І один прохід на всю розмову, а не [PeerMark.fold] на кожен рядок:
        // на пів тисячі листів друге — чверть мільйона спроб розбору на одне
        // відкриття розмови.
        val marks = PeerMark.foldAll(container.mesh.outbox.value, PeerMark.What.REACTION)
        // Позначки стану карток (`PH-CST:`) — так само раз на розмову.
        val cardFolded = WebMessengerCardMarks.fold(container.mesh.outbox.value)
        val pins = pinsWith(peerId)
        val arr = JSONArray()
        val readPoint = reads.pointOf(peerId)
        lines.forEachIndexed { i, m ->
            if (i + 1 > afterSeq) arr.put(message(m, i + 1, peerId, marks, readPoint, cardFolded, pins))
        }
        return arr
    }

    /**
     * Смайли під рядком — так, як їх читає веб:
     * `{emoji, count, users, names, mine}`.
     *
     * Форма не вигадана: `{emoji, count, users}` уже чекає `Reaction` у типах
     * веб-месенджера, і рівно її складав місцевий `applyReaction`. Число тут
     * ПОХІДНЕ від списку авторів, а не окреме поле з дроту: два незалежні
     * лічильники того самого розійшлися б на першому ж кадрі, який приїхав
     * двічі.
     *
     * `names` додано 18.09, бо `users` — це ідентифікатори вузлів, і веб
     * малював їх людині дослівно (`title={'Реагували: ' + r.users.join()}` у
     * ChatArea.tsx). Імена знає лише цей бік, тож він їх і називає; порядок
     * той самий, що в `users`, тобто це не друге джерело правди, а підпис до
     * першого.
     *
     * `mine` — ДОРОГА власної позначки, і саме її не було. Згортач навмисно
     * сліпий до [PeerMessage.state] (див. [PeerMark.foldAll]), тож смайл у
     * черзі малювався так само, як доставлений: власник називає це «начебто
     * поставилось». Поле є ЛИШЕ коли позначку тримаю я — про чужу позначку
     * питати дорогу нічим: вона приїхала, тобто вона доставлена за самим
     * фактом того, що ми її бачимо.
     */
    private fun reactionsOf(
        targetId: String,
        byEmoji: Map<String, List<String>>,
        me: String,
    ): JSONArray =
        JSONArray().also { arr ->
            byEmoji.forEach { (emoji, users) ->
                arr.put(
                    JSONObject()
                        .put("emoji", emoji)
                        .put("count", users.size)
                        .put("users", JSONArray().also { u -> users.forEach(u::put) })
                        // Імʼя бере ТОЙ САМИЙ [nameOf], яким підписані автори
                        // рядків: другий іменувач розійшовся б із першим на
                        // першому ж перейменуванні співрозмовника. Своє імʼя
                        // тут словом, бо «я» у списку авторів — це не контакт.
                        .put(
                            "names",
                            JSONArray().also { u ->
                                users.forEach {
                                    u.put(if (it == me) say(R.string.msgr_bridge_me) else nameOf(it))
                                }
                            },
                        )
                        .put(
                            "mine",
                            if (me.isBlank() || me !in users) {
                                JSONObject.NULL
                            } else {
                                PeerMark
                                    .latestCast(
                                        container.mesh.outbox.value,
                                        PeerMark.What.REACTION,
                                        targetId,
                                        emoji,
                                        me,
                                    )
                                    ?.let { state(it.state) }
                                    // Позначка тримається, а кадру в журналі
                                    // немає: це не «доставлено», це «не знаю».
                                    ?: JSONObject.NULL
                            },
                        ),
                )
            }
        }

    private fun message(
        m: PeerMessage,
        seq: Int,
        peerId: String,
        /**
         * Згорнуті позначки на всю розмову — або null, коли рядок один: тоді
         * згортається лише він. Той самий згортач в обох випадках.
         */
        marks: Map<String, Map<String, List<String>>>? = null,
        readPoint: PeerRead.Point? = reads.pointOf(peerId),
        /** Згорнуті позначки карток на всю розмову — або null, коли рядок один. */
        cardFolded: Map<String, Map<String, List<String>>>? = null,
        /** P05: закріплене удвох ([pinsWith]); null — поля `pinned` у рядку немає. */
        pins: Set<String>? = null,
    ): JSONObject {
        val me = container.mesh.selfIdFlow.value
        val authorName = when {
            m.fromId == me -> ""
            m.fromId == AgencySessionManager.PHANTOM_AGENT_ID -> AgencySessionManager.PHANTOM_AGENT_NAME
            // Учасники колегії підписуються мовою розмови (ReplyLanguage), як і їхні відповіді.
            else -> AgencySessionManager.ALL_AGENTS.firstOrNull { it.id == m.fromId }?.let { agent ->
                "${agent.avatar} " + local.phantom.companion.core.data.locale.ReplyLanguage
                    .context(container.applicationContext).getString(agent.name)
            } ?: container.peers.peers.value.firstOrNull { it.id == m.fromId }?.name
                ?: container.peers.peers.value.firstOrNull { it.id == peerId }?.name
                ?: m.fromId.take(8)
        }
        val att = PeerFile.describe(m.body)
        val kinds = WebMessengerKinds.fields(m.body)
        val kind = kinds[WebMessengerKinds.KIND]
        return JSONObject()
            .put("id", m.id)
            .put("conversation_id", peerId)
            .put("client_id", m.id)
            .put("seq", seq)
            .put("author_id", m.fromId)
            .put("author_name", authorName)
            // Рід і розібрані поля — з [WebMessengerKinds]; вкладення він читає
            // тим самим розбором, яким його й писав.
            .also { o -> WebMessengerKinds.shipped(kinds).forEach { (key, value) -> o.put(key, jsonOf(value)) } }
            .let { cardInto(it, kinds) }
            .let { cardMarksInto(it, kinds, m, null, cardFolded) }
            .also { solana.decorate(it, m.body, m.fromId, m.id) }
            .also { o ->
                if (m.fromId == AgencySessionManager.PHANTOM_AGENT_ID) {
                    local.phantom.companion.data.MoneyDraftNotes.get(container.applicationContext, m.id)?.let { n ->
                        o.put(
                            "pay_draft",
                            JSONObject()
                                .put("peer_id", n.peerId)
                                .put("token", n.coin.symbol)
                                .put("amount", n.amount ?: "")
                                .put("sent", n.sentIn(container.mesh.outbox.value, me)),
                        )
                    }
                    local.phantom.companion.data.CommitmentNotes.get(container.applicationContext, m.id)?.let { n ->
                        o.put("commitment_card", n.toJson(container.commitmentStore))
                    }
                } else if (m.fromId == me) {
                    local.phantom.companion.data.CommitmentNotes.get(container.applicationContext, m.id)?.let { n ->
                        o.put("commitment_card", n.toJson(container.commitmentStore))
                    } ?: run {
                        val peerName = nameOf(peerId)
                        if (peerName.isNotBlank() && !peerName.startsWith("node_")) {
                            val parsed = local.phantom.companion.self.CommitmentSpeech.offerFor(
                                text = m.body,
                                peerName = peerName,
                                sentAtMs = m.sentAtMs,
                                nowMs = System.currentTimeMillis(),
                                zone = container.phantomSelf.commitments?.zoneId() ?: java.time.ZoneId.systemDefault(),
                            )
                            if (parsed != null && parsed.dueMs != null) {
                                o.put(
                                    "commitment_card",
                                    JSONObject().apply {
                                        put("kind", "offer")
                                        put("person", parsed.person)
                                        put("peer_id", peerId)
                                        put("what", parsed.what)
                                        put("due_ms", parsed.dueMs)
                                        put("source_message_id", m.id)
                                        put("state", "open")
                                    },
                                )
                            }
                        }
                    }
                }
            }
            .also { o ->
                if (kind == WebMessengerKinds.BROKEN) {
                    o.put("broken_reason", say(R.string.peer_frame_broken))
                }
            }
            .put("body", bodyOf(kinds, m.body, solanaWho(peerId, m.fromId == me)))
            .put("ciphertext", JSONObject.NULL)
            .put("transport", WebMessengerKinds.road(m, me) ?: JSONObject.NULL)
            .put("reply_to_id", m.replyToId.ifBlank { null } ?: JSONObject.NULL)
            .carried(m)
            .put("intent_id", container.intents.client.forAnchor(m.id)?.idHex ?: JSONObject.NULL)
            // Смайли під рядком. До 13.09 поля не було взагалі: веб домальовував
            // їх у памʼяті вкладки й губив із першим же `__phantomMeshChanged`,
            // бо стрічка перечитується з мосту цілком. Тепер вони приїжджають
            // із журналу — тобто переживають і оновлення, і перезапуск, і
            // приходять від того, хто їх поставив.
            .put(
                "reactions",
                reactionsOf(
                    m.id,
                    marks?.get(m.id)
                        ?: PeerMark.fold(
                            container.mesh.outbox.value, PeerMark.What.REACTION, m.id,
                        ),
                    me,
                ),
            )
            .also { o -> if (pins != null) o.put("pinned", m.id in pins) }
            .put("sent_at", iso(m.sentAtMs))
            .put("edited_at", m.editedAtMs.takeIf { it > 0L }?.let { iso(it) } ?: JSONObject.NULL)
            .put("deleted_at", m.deletedAtMs.takeIf { it > 0L }?.let { iso(it) } ?: JSONObject.NULL)
            .also { o ->
                if (reads.visible() && m.fromId == me) {
                    o.put("read_at", reads.readAtMs(m, readPoint).takeIf { it > 0L }?.let { iso(it) } ?: JSONObject.NULL)
                }
            }
            .put("delivery_state", state(m.state))
            // Питання без відповіді: причина — ярликом під бульбашкою питання (не листом помічника).
            .also { o ->
                if (m.state == PeerMessage.State.FAILED && m.toId == AgencySessionManager.PHANTOM_AGENT_ID) {
                    container.agencyManager.failureOf(m.id)?.let { o.put("fail_reason", it) }
                }
            }
            .let { relayInto(it, m) }
            .put("attachment_state", att?.let { stateOf(it) } ?: JSONObject.NULL)
    }

    /**
     * Що телефон ЗНАЄ про долю листа поза власною чергою.
     *
     * Аркуш «ШЛЯХ ЛИСТА» казав «лежить на вашому вузлі» і «надішлемо, щойно
     * співрозмовник зʼявиться» над листом, який уже лежав у сховку й уже був
     * звідти забраний. Обидва речення — позитивні твердження, яких телефон не
     * може знати: сховок не говорить про адресата, а квитанції не було.
     *
     * `delivery_state` тут навмисно НЕ змінюється: у бандлі телефона стан
     * розбирає `Ge(e,t)`, і будь-яке нове значення падає в нього у гілку
     * «sent» — тобто спроба сказати точніше зробила б із «у черзі» брехливе
     * «надіслано». Тому точність їде окремими полями, а грубе слово лишається
     * тим, яке не бреше. Текст аркуша — вимога до веб-боку (bridge_status.md).
     *
     * Мить вкидання рахується годинником СЕРВЕРА ([PeerOutbox.heldBefore]:
     * строк мінус TTL), бо своїм годинником телефон назвав би чужий час.
     */
    private fun relayInto(o: JSONObject, m: PeerMessage): JSONObject {
        val stored = m.state == PeerMessage.State.HELD && m.heldNonce.isNotBlank()
        val droppedAtMs = if (stored && m.heldUntilMs > 0L) m.heldUntilMs - PeerRelay.TTL_MS else 0L
        // acked — квитанція БУЛА; stored — сховок узяв байти й нічого не сказав
        // про адресата; local — лист не покидав цього апарата.
        return o
            .put("relay_state", WebMessengerKinds.relayState(m))
            .put("held_at", droppedAtMs.takeIf { it > 0L }?.let { iso(it) } ?: JSONObject.NULL)
            .put("held_until", m.heldUntilMs.takeIf { stored && it > 0L }?.let { iso(it) } ?: JSONObject.NULL)
    }

    /**
     * JSON місця з композера → кадр `PH-LOC:`, або null.
     *
     * Складене звіряється ТИМ САМИМ розбирачем, яким кадр читатиме приймач:
     * другий суд про те, що таке дійсне місце, рано чи пізно розійшовся б із
     * першим, і в дріт поїхала б точка, якої співрозмовник не покаже.
     *
     * `label` і `at` із JSON не їдуть: формат `PH-LOC:` не розширюваний
     * хвостовими полями — будь-що після точності робить її `null` у старих
     * збірках (виміряно 12.09). Назву приймач бере з власного реєстру місць за
     * координатами, час — час самого листа; друге поле часу в кадрі було б
     * другою правдою про ту саму мить.
     */
    /**
     * Чи тіло картки взагалі JSON.
     *
     * На відміну від місця, тіло картки НЕ розбирається на поля й не
     * складається назад: форму знає й малює веб, і розбирач тут був би другою
     * правдою про неї — розійшовся б на першому ж перезбиранні бандла.
     * Перевіряється рівно одне, і ДО дроту: що на тому кінці буде чому
     * розібратись. Не-JSON доїхав би й став порожньою бульбашкою, а порожня
     * бульбашка про себе не каже нічого.
     */
    private fun isJson(body: String): Boolean =
        runCatching { JSONObject(body) }.getOrNull() != null

    private fun placeFrame(body: String): String? {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (!o.has("lat") || !o.has("lon")) return null
        val lat = o.optDouble("lat", Double.NaN)
        val lon = o.optDouble("lon", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return null
        val accuracyM = o.optInt("acc", 0).takeIf { it > 0 }
        val frame = PeerPayload.encodePlace(lat, lon, accuracyM)
        return frame.takeIf { PeerPayload.decodePlace(it) != null }
    }

    /**
     * Id листа, на який це відповідь, — або порожньо.
     *
     * Веб шле `reply_to_id` у кожному листі, міст його викидав і читав назад
     * власне порожнє поле. Звіряється НАЛЕЖНІСТЬ до цієї ж розмови: вигаданий
     * або чужий id дав би в стрічці посилання в нікуди — та сама мовчазна
     * поразка, тільки з боку веба.
     */
    private fun replyWithin(peerId: String, id: String): String {
        if (id.isBlank()) return ""
        val known = container.mesh.outbox.value.any {
            it.id == id && (it.toId == peerId || it.fromId == peerId)
        }
        return if (known) id else ""
    }

    /** Чому лист не поїхав — словом про те, ЩО саме не поїхало. */
    private fun refusedKind(kind: String): String =
        if (WebMessengerKinds.isSpokenKind(kind)) say(R.string.peer_typed_service_prefix)
        else say(R.string.msgr_bridge_kind_not_carried, kind)

    private val reads by lazy { WebMessengerReads.of(container) }

    private val typing by lazy { webTyping(container) }

    private fun seqOf(body: String?): Int =
        body?.let { runCatching { JSONObject(it) }.getOrNull() }?.optInt("seq", 0) ?: 0

    private val revisions by lazy {
        WebMessengerRevise(
            log = { container.mesh.outbox.value },
            me = { container.mesh.selfIdFlow.value },
            revise = { copies, act -> container.mesh.revise(copies, act) },
            keepLocal = { container.mesh.injectMessages(it) },
            forget = { line ->
                container.mesh.deleteLine(line)
                val peerId = if (line.fromId == container.mesh.selfIdFlow.value) line.toId else line.fromId
                val groupId = PeerGroup.decode(line.body)?.group?.id
                val raw = WebMessengerGroups.said(line.body) ?: line.body
                PeerFile.describe(raw)?.let { att ->
                    val droppedPeer = container.peerFiles.drop(peerId, att.blobId)
                    val droppedGroup = groupId != null && container.peerFiles.drop(groupId, att.blobId)
                    if (droppedPeer || droppedGroup) 1 else 0
                } ?: 0
            },
        )
    }

    private fun fanOf(line: PeerMessage): List<PeerMessage> {
        val ids = PeerPurge.line(container.mesh.outbox.value, line).ids
        return listOf(line) + container.mesh.outbox.value.filter { it.id in ids && it.id != line.id && !PeerFile.isChunk(it.body) }
    }

    private fun deleted(r: WebMessengerRevise.Outcome): String =
        (r as? WebMessengerRevise.Outcome.Deleted)?.let {
            ok(
                JSONObject().put("deleted", true).put("for_everyone", it.forEveryone)
                    .put("blobs", it.blobs).put("frame", it.frame),
            )
        } ?: refusedRevise(r)

    private fun refusedRevise(r: WebMessengerRevise.Outcome): String {
        val code = (r as? WebMessengerRevise.Outcome.Refused)?.code ?: "refused"
        val why = when (code) {
            "not_mine" -> R.string.msgr_bridge_edit_not_mine
            "not_words" -> R.string.msgr_bridge_edit_not_words
            "too_long" -> R.string.msgr_bridge_edit_too_long
            "edit_window" -> R.string.msgr_bridge_edit_window
            else -> R.string.peer_typed_service_prefix
        }
        return fail((r as? WebMessengerRevise.Outcome.Refused)?.status ?: 409, code, say(why))
    }

    private fun versionsOf(versions: List<PeerRevise.Version>): JSONObject =
        JSONObject().put(
            "versions",
            JSONArray().apply { versions.forEach { put(JSONObject().put("text", it.text).put("at", iso(it.atMs))) } },
        )

    private fun editLine(peerId: String, messageId: String, body: String?): String {
        val o = body?.let { JSONObject(it) } ?: return fail(400, "bad_body", say(R.string.msgr_bridge_bad_body))
        val text = o.optString("body")
        if (text.isBlank()) return fail(400, "empty", say(R.string.msgr_bridge_empty_text))
        val line = container.mesh.outbox.value.firstOrNull {
            it.id == messageId && (it.toId == peerId || it.fromId == peerId)
        } ?: return fail(404, "no_message", say(R.string.msgr_bridge_no_message))
        val r = revisions.edit(listOf(line), text)
        if (r !is WebMessengerRevise.Outcome.Edited) return refusedRevise(r)
        val seq = WebMessengerKinds.dmFeed(container.mesh.outbox.value, peerId)
            .indexOfFirst { it.id == r.line.id } + 1
        return ok(message(r.line, if (seq > 0) seq else 1, peerId).put("frame", r.frame))
    }

    /**
     * Смайл під чужим рядком — У СІТКУ, а не в памʼять вкладки.
     *
     * Доти дороги не було жодної. `messengerStore.toggleReaction` міняв масив
     * у себе й кликав `messengerNetworkEngine.sendReaction`, а той у телефонній
     * збірці має дві гілки, і ЖОДНА з них не веде в меш: `p2p` шле кадр у
     * браузерну WebRTC-сітку, якої на телефоні немає, а друга — у `this.ws`,
     * сокет до чужого брокера. Тобто співрозмовник не бачив смайла НІКОЛИ, а в
     * того, хто його поставив, він жив до першого ж `__phantomMeshChanged`:
     * стрічка перечитується з мосту цілком і заступає місцевий масив.
     *
     * Напрям дотику («поставити» чи «зняти») вирішує ТОЙ САМИЙ згортач, яким
     * малюється результат ([PeerMark.holds]). Друге, простіше правило — «спитай
     * веб, що в нього намальовано» — розійшлося б із першим рівно тоді, коли
     * поле застаріло на один кадр, і дотик знімав би те, чого немає.
     *
     * Кадр складає МІСТ, а не сторінка: готове тіло з веба не береться ніколи,
     * інакше вкладка клала б у дріт будь-що під виглядом позначки.
     */
    /** P05: закріплене в розмові удвох — лише з тим, хто сам сказав, що вміє `PH-PIN:` (сцена 18+). */
    private fun pinsWith(peerId: String): Set<String>? {
        val peer = container.peers.peers.value.firstOrNull { it.id == peerId } ?: return null
        if (peer.sceneLevel < PeerProfile.SCENE_V1) return null
        val me = container.mesh.selfIdFlow.value.ifBlank { "operator_me" }
        return PeerMark.pinned(container.mesh.outbox.value, me, peerId)
    }

    /** P05: закріпити чи зняти рядок для обох — один кадр `PH-PIN:`, як смайл. */
    private fun pinLine(peerId: String, messageId: String, body: String?): String {
        val o = body?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.takeIf { it.has("on") }
            ?: return fail(400, "bad_body", say(R.string.msgr_bridge_bad_body))
        val line = container.mesh.outbox.value.firstOrNull {
            it.id == messageId && (it.toId == peerId || it.fromId == peerId)
        } ?: return fail(404, "no_message", say(R.string.msgr_bridge_no_message))
        val peer = container.peers.peers.value.firstOrNull { it.id == peerId }
            ?: return fail(404, "no_contact", say(R.string.msgr_bridge_no_speaker))
        if (peer.dhPubKey.isBlank()) return fail(409, "no_key", say(R.string.msgr_bridge_peer_no_key))
        if (peer.sceneLevel < PeerProfile.SCENE_V1) {
            return fail(409, "pin_unsupported", say(R.string.msgr_bridge_pin_unsupported))
        }
        val frame = PeerMark.encode(PeerMark.Mark(PeerMark.What.PIN, messageId, o.optBoolean("on"), PeerMark.PIN_VALUE))
            ?: return fail(422, "bad_mark", say(R.string.msgr_bridge_no_message))
        container.mesh.send(peerId, frame)
            ?: return fail(503, "no_identity", say(R.string.msgr_bridge_no_identity))
        val seq = WebMessengerKinds.dmFeed(container.mesh.outbox.value, peerId)
            .indexOfFirst { it.id == messageId } + 1
        return ok(message(line, if (seq > 0) seq else 1, peerId, pins = pinsWith(peerId)))
    }

    private fun react(peerId: String, messageId: String, body: String?): String {
        val o = body?.let { JSONObject(it) } ?: return fail(400, "bad_body", say(R.string.msgr_bridge_bad_body))
        val emoji = o.optString("emoji")
        if (emoji.isBlank()) return fail(400, "empty", say(R.string.msgr_bridge_empty_text))
        val log = container.mesh.outbox.value
        // Позначка на рядок, якого в ЦІЙ розмові немає, — посилання в нікуди на
        // тому боці. Та сама звірка належності, що й у [replyWithin].
        val line = log.firstOrNull {
            it.id == messageId && (it.toId == peerId || it.fromId == peerId)
        } ?: return fail(404, "no_message", say(R.string.msgr_bridge_no_message))
        val peer = container.peers.peers.value.firstOrNull { it.id == peerId }
            ?: return fail(404, "no_contact", say(R.string.msgr_bridge_no_speaker))
        if (peer.dhPubKey.isBlank()) return fail(409, "no_key", say(R.string.msgr_bridge_peer_no_key))
        val me = container.mesh.selfIdFlow.value.ifBlank { "operator_me" }
        val on = !PeerMark.holds(log, PeerMark.What.REACTION, messageId, emoji, me)
        val frame = PeerMark.encode(PeerMark.Mark(PeerMark.What.REACTION, messageId, on, emoji))
            ?: return fail(422, "bad_mark", say(R.string.msgr_bridge_reaction_unreadable))
        container.mesh.send(peerId, frame)
            ?: return fail(503, "no_identity", say(R.string.msgr_bridge_no_identity))
        // Відповідь — сам рядок, уже зі згорнутими позначками. Веб домальовує
        // дотик одразу, але правду бере звідси: власний оптимістичний масив і
        // журнал розходяться саме тоді, коли кадр не склався.
        val seq = WebMessengerKinds.dmFeed(container.mesh.outbox.value, peerId)
            .indexOfFirst { it.id == messageId } + 1
        return ok(message(line, if (seq > 0) seq else 1, peerId))
    }

    /**
     * Другі двері в ту саму розмову — і саме вони двоїли листи.
     *
     * Розмову з локальним агентом бандл позначає родом «phantom» / «ai», а на
     * такий рід його крамниця бʼє ДВІЧІ за один дотик: спершу кладе слово
     * людини через `/messenger/conversations/<id>/messages`, а потім шле те
     * саме речення сюди, чекаючи в тілі готову відповідь моделі
     * (`Uc.sendMessage` у бандлі → `POST /chat/message`).
     *
     * Доти цей шлях кликав [append] вдруге. У журнал лягав ДРУГИЙ лист від
     * імені людини — з новим id, тим самим текстом і тією самою хвилиною, — і
     * симбіонтові вдруге давалось те саме питання. Один дотик, два однакові
     * рядки праворуч, і вони переживали вихід із розмови, бо це були два
     * справжні записи.
     *
     * Тут не пишеться НІЧОГО. Відповідь синхронно не приїхала б і раніше:
     * розмову веде [AgencySessionManager.dispatchPhantomDialogue] у своєму
     * потоці й кладе сказане в журнал сама.
     */
    private fun askAgent(body: String?): String {
        val o = body?.let { JSONObject(it) } ?: return fail(400, "bad_body", say(R.string.msgr_bridge_bad_body))
        val text = o.optString("message").ifBlank { o.optString("content").ifBlank { o.optString("prompt") } }
        if (text.isBlank()) return fail(400, "empty", say(R.string.msgr_bridge_empty_text))
        return fail(409, "already_in_log", say(R.string.msgr_bridge_second_door))
    }

    /** Відкладені листи: шле будильник телефона тим самим шляхом, що й композер. */
    private val scheduled by lazy {
        WebMessengerScheduled(
            letters = container.scheduledLetters,
            known = { id ->
                id == SavedNotes.ID || WebMessengerGroups.find(container.mesh.outbox.value, id) != null ||
                    container.peers.peers.value.any { it.id == id && it.dhPubKey.isNotBlank() }
            },
            send = { letter -> local.phantom.companion.service.ScheduledLetterSend.via(::dispatch, letter) },
            rearm = { local.phantom.companion.service.ScheduledLetterAlarms.rearm(container.applicationContext, container.scheduledLetters) },
        )
    }

    private fun scheduledAdd(body: String?): String {
        val o = body?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return fail(400, "bad_body", say(R.string.msgr_bridge_bad_body))
        return scheduledOut(
            scheduled.add(
                o.optString("conversation_id"),
                o.optString("text"),
                o.optLong("due_at_ms", 0L),
                o.optBoolean("quiet"),
                o.optString("repeat"),
                o.optBoolean("when_online"),
            ),
        )
    }

    private fun scheduledEdit(id: String, body: String?): String {
        val o = body?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return fail(400, "bad_body", say(R.string.msgr_bridge_bad_body))
        return scheduledOut(
            scheduled.edit(
                id,
                text = if (o.has("text")) o.optString("text") else null,
                dueAtMs = if (o.has("due_at_ms")) o.optLong("due_at_ms") else null,
                repeat = if (o.has("repeat")) o.optString("repeat") else null,
                whenOnline = if (o.has("when_online")) o.optBoolean("when_online") else null,
            ),
        )
    }

    private fun scheduledOut(out: WebMessengerScheduled.Out): String = when (out) {
        is WebMessengerScheduled.Out.One -> ok(letterJson(out.letter))
        is WebMessengerScheduled.Out.Refused -> fail(
            out.status,
            out.code,
            when (out.code) {
                "empty" -> say(R.string.msgr_bridge_empty_text)
                "no_chat" -> say(R.string.msgr_bridge_no_contact)
                "past" -> say(R.string.msgr_bridge_sched_past)
                "no_letter" -> say(R.string.msgr_bridge_sched_gone)
                else -> out.why
            },
        )
    }

    private fun letterJson(l: local.phantom.companion.data.scheduled.ScheduledLetters.Letter): JSONObject =
        JSONObject()
            .put("id", l.id)
            .put("conversation_id", l.conversationId)
            .put("text", l.text)
            .put("due_at_ms", l.dueAtMs)
            .put("created_at_ms", l.createdAtMs)
            .put("quiet", l.quiet)
            .put("repeat", l.repeat)
            .put("when_online", l.whenOnline)
            .put("failure", l.failure.ifBlank { null } ?: JSONObject.NULL)

    /** Строк зникнення розмови вдвох (PeerDisappear). Групи — ні, і це сказано. */
    private fun setTimer(peerId: String, body: String?): String {
        val seconds = body?.let { runCatching { JSONObject(it) }.getOrNull() }?.optLong("seconds", -1L) ?: -1L
        if (seconds !in PeerDisappear.CHOICES_S) return fail(422, "bad_timer", say(R.string.msgr_bridge_bad_body))
        val peer = container.peers.peers.value.firstOrNull { it.id == peerId && it.dhPubKey.isNotBlank() }
            ?: return fail(409, "no_timer", say(R.string.msgr_bridge_no_contact))
        container.disappear.setTimer(peer.id, seconds)
            ?: return fail(503, "no_identity", say(R.string.msgr_bridge_no_identity))
        return ok(JSONObject().put("disappear_s", container.disappear.current(peer.id)?.seconds ?: 0L))
    }

    /** Автор пересланого з листа веба; «як своє» його не шле. */
    private fun forwardIn(o: JSONObject): PeerMessage.Forward? =
        o.optJSONObject("fw")?.let { PeerMessage.forwardOf(it.optString("name"), it.optString("id")) }

    /** Процитована частина; без відповіді не їде (PeerMesh). */
    private fun quoteIn(o: JSONObject): String = PeerMessage.quoteOf(o.optString("qt"))

    /** Переслане й цитата — з чужого кадру теж, тож межі ставимо й на виході. */
    private fun JSONObject.carried(m: PeerMessage): JSONObject {
        val fw = m.fw?.let { PeerMessage.forwardOf(it.name, it.id) }
        val qt = PeerMessage.quoteOf(m.qt)
        return put("fw", fw?.let { JSONObject().put("name", it.name).put("id", it.id) } ?: JSONObject.NULL)
            .put("qt", qt.ifBlank { null } ?: JSONObject.NULL)
    }

    private fun append(peerId: String, body: String?): String {
        val o = body?.let { JSONObject(it) } ?: return fail(400, "bad_body", say(R.string.msgr_bridge_bad_body))
        val text = o.optString("body")
        if (text.isBlank()) return fail(400, "empty", say(R.string.msgr_bridge_empty_text))
        // Та сама відмова, що й у групі: набране не вдає слова моделі.
        // Розмова вдвох так само малює `PH-AI:` підписом «⚡ PHANTOM».
        if (WebMessengerAi.isAi(text)) {
            return fail(422, "service_prefix", say(R.string.peer_typed_service_prefix))
        }
        val me = container.mesh.selfIdFlow.value.ifBlank { "operator_me" }
        // Веб оголошує рід і адресата відповіді в КОЖНОМУ листі, а міст читав
        // саме `body` — решта тихо падала на підлогу. Долю тіла рід тут не
        // вирішує, її вирішує [PeerFrames]; рід лише називає причину відмови
        // тим словом, яким її бачить людина.
        val kind = o.optString("kind").ifBlank { WebMessengerKinds.TEXT }
        val replyToId = replyWithin(peerId, o.optString("reply_to_id"))
        val quiet = o.optBoolean("quiet")
        val fw = forwardIn(o)
        val qt = quoteIn(o)

        if (peerId == AgencySessionManager.PHANTOM_AGENT_ID) {
            // «Ще раз» приходить з id того ж листа: питання те саме, рядок журналу той самий,
            // нової бульбашки від людини не буде — навіть якщо повтор прийшов двічі поспіль.
            // Веб повторює лист із тим самим client_id, коли не дочекався відповіді мосту:
            // питання лягає під id з нього, і повтор знаходить той самий рядок, а не друге питання.
            val clientId = o.optString("client_id").takeIf { it.isNotBlank() }
            val questionId = AskGate.questionIdFor(clientId)
            val known = clientId?.let { id ->
                container.mesh.outbox.value.firstOrNull {
                    (it.id == id || it.id == questionId) && it.fromId == me && it.toId == peerId
                }
            }
            if (known != null) {
                if (known.state == PeerMessage.State.FAILED) container.agencyManager.retryAsk(known, me)
                val seq = WebMessengerKinds.dmFeed(container.mesh.outbox.value, peerId)
                    .indexOfFirst { it.id == known.id } + 1
                return ok(message(known.copy(state = PeerMessage.State.DELIVERED), if (seq > 0) seq else 1, peerId))
            }
            val userMsg = PeerMessage(
                id = questionId ?: ("msg_" + UUID.randomUUID().toString().take(12)),
                fromId = me,
                toId = peerId,
                body = text,
                sentAtMs = System.currentTimeMillis(),
                replyToId = replyToId,
                state = PeerMessage.State.DELIVERED,
            )
            container.mesh.ingestDirect(userMsg)
            // Немає дороги в інтернет — питання їде Наміром до мого ПК (сховком
            // або SMS), а розум телефона все одно відповідає, що вміє сам.
            val asked = AssistantInput.promptOf(text)
            if (asked != null && !InternetPath.has(container.applicationContext)) {
                runCatching { container.intents.client.question(asked, anchor = userMsg.id) }
            }
            container.agencyManager.dispatchPhantomDialogue(text, me, questionId = userMsg.id)
            val seq = WebMessengerKinds.dmFeed(container.mesh.outbox.value, peerId)
                .indexOfFirst { it.id == userMsg.id } + 1
            return ok(message(userMsg, if (seq > 0) seq else 1, peerId))
        }

        if (peerId.startsWith("agency_")) {
            val userMsg = PeerMessage(
                id = "msg_" + UUID.randomUUID().toString().take(12),
                fromId = me,
                toId = peerId,
                body = text,
                sentAtMs = System.currentTimeMillis(),
                replyToId = replyToId,
                state = PeerMessage.State.DELIVERED,
            )
            container.mesh.ingestDirect(userMsg)
            container.agencyManager.dispatchAgencyCouncil(peerId, text, me)
            val allAgency = container.mesh.outbox.value
                .filter { (it.toId == peerId || it.fromId == peerId) && WebMessengerKinds.isVisible(it.body) }
                .sortedBy { it.sentAtMs }
            val seq = WebMessengerKinds.feed(allAgency)
                .indexOfFirst { it.id == userMsg.id } + 1
            return ok(message(userMsg, if (seq > 0) seq else 1, peerId))
        }

        val peer = container.peers.peers.value.firstOrNull { it.id == peerId }
            ?: return fail(404, "no_contact", say(R.string.msgr_bridge_no_speaker))
        val hasKey = peer.dhPubKey.isNotBlank()
        // Звірений контакт змінив ключ — лист чекає на «Продовжити» або нову
        // звірку (PeerIdentity.sendHeld). Відмова словом, а не тиха черга.
        if (peer.sendHeld) return fail(409, "key_hold", say(R.string.msgr_bridge_key_hold))
        // Кадр місця складає МІСТ, а не сторінка. Веб шле JSON композера
        // (`{"lat":…,"lon":…,"at":…}`), і досі міст віддавав його далі СЛОВОМ
        // людини: співрозмовник діставав у бульбашку `{"lat":50.45010,…}` —
        // протокол в очі тому, для кого його ховають.
        //
        // Готове тіло з веба не береться ніколи: інакше сторінка клала б у дріт
        // будь-що під виглядом виміру пристрою, і заслін від підробки походження
        // обходився б саме з того боку, який він стереже.
        // Гілки «все інше» тут більше немає, і саме вона була вадою: рід, для
        // якого міст не вміє скласти кадр, їхав `sendTyped` — тобто голим тілом
        // у чужу бульбашку. Тепер невідомий рід спиняється видимо, з причиною.
        // Голосове чи кружок без опису вкладення — не голос, а слово в чужу бульбашку.
        if (WebMessengerKinds.mediaWithoutDescription(kind, text)) {
            return fail(422, "bad_media", say(R.string.msgr_bridge_media_unreadable))
        }
        val composed = when (val carry = WebMessengerKinds.carry(kind)) {
            WebMessengerKinds.Carry.Typed -> null
            WebMessengerKinds.Carry.Place -> placeFrame(text)
                ?: return fail(422, "bad_place", say(R.string.msgr_bridge_place_unreadable))
            is WebMessengerKinds.Carry.Card -> {
                if (!isJson(text)) {
                    return fail(422, "bad_card", say(R.string.msgr_bridge_card_unreadable))
                }
                PeerCard.encode(PeerCard.Body(carry.what, text))
                    ?: return fail(413, "card_too_big", say(R.string.msgr_bridge_card_too_big))
            }
            is WebMessengerKinds.Carry.Unknown ->
                return fail(422, "kind_not_carried", say(R.string.msgr_bridge_kind_not_carried, carry.kind))
        }
        // Без ключа співрозмовника лист не поїде ніколи — і доти міст просто
        // відмовляв (409 `no_key`). Слова людини не лягали в журнал узагалі:
        // бульбашка жила до виходу з розмови й зникала разом із нею. Порожнеча
        // замість стану — це людина, впевнена, що написала.
        //
        // Тепер рядок лягає ПОРАЗКОЮ: бульбашка каже «не пішло» і переживає
        // повернення в розмову. «Ще раз» на ньому не безглузде — ключ приходить
        // запрошенням, і після обміну та сама черга повезе цей самий лист.
        // Картку переказу складає лише гаманець; набрана вручну вона була б підробкою.
        if (composed == null && local.phantom.companion.core.solana.SolanaNotice.isOurs(text)) {
            return fail(422, "service_prefix", refusedKind(kind))
        }
        if (!hasKey) {
            if (composed == null) {
                PeerFrames.refuseTyped(text)?.let {
                    return fail(422, "service_prefix", refusedKind(kind))
                }
            }
            val stalled = container.mesh.ingestUndeliverable(peerId, composed ?: text, replyToId, fw, qt)
                ?: return fail(503, "no_identity", say(R.string.msgr_bridge_no_identity))
            val stalledSeq = WebMessengerKinds.dmFeed(container.mesh.outbox.value, peerId)
                .indexOfFirst { it.id == stalled.id } + 1
            return ok(message(stalled, if (stalledSeq > 0) stalledSeq else 1, peerId))
        }
        val sent = if (composed != null) {
            // Складене пристроєм не судиться [PeerFrames.refuseTyped]: той
            // стереже НАБРАНЕ, і кадр, щойно складений мостом, він відмовив би
            // власному ж авторові. Та сама дорога, якою йде [MapViewModel]
            // зі своїм маршрутом.
            container.mesh.send(peerId, composed, replyToId, quiet, fw, qt)
                ?: return fail(503, "no_identity", say(R.string.msgr_bridge_no_identity))
        } else {
            val typed = container.mesh.sendTyped(peerId, text, replyToId, quiet, fw, qt)
            if (typed is PeerFrames.Typed.Refused) {
                // Веб мусить показати цю відмову словом. Мовчазне ковтання тут —
                // це людина, впевнена, що написала.
                return fail(422, "service_prefix", refusedKind(kind))
            }
            (typed as PeerFrames.Typed.Sent).messages.firstOrNull()
                ?: return fail(503, "no_identity", say(R.string.msgr_bridge_no_identity))
        }
        // Опис поїхав — за ним байти. Порядок такий, бо приймач без опису не
        // має з чим звіряти відбиток; шматок, що обігнав опис, просто чекає.
        PeerFile.describe(text)?.let { att ->
            container.peerFiles.copyTo(peerId, att.blobId)
            container.mesh.sendMany(peerId, container.peerFiles.bodiesFor(att))
        }
        // Слово з посиланням — картку слідом складає цей телефон (PeerPreview).
        if (composed == null) container.linkPreviews.launch(container.scope, peerId, sent)
        val seq = WebMessengerKinds.dmFeed(container.mesh.outbox.value, peerId)
            .indexOfFirst { it.id == sent.id } + 1
        return ok(message(sent, if (seq > 0) seq else 1, peerId))
    }

    // ── «Збережене» ──────────────────────────────────────────────────────

    private val saved by lazy {
        WebMessengerSaved(
            notes = container.savedNotes,
            me = { container.mesh.selfIdFlow.value },
            journal = object : WebMessengerSaved.Journal {
                override fun lines() = container.mesh.outbox.value
                override fun forget(lines: List<PeerMessage>) = lines.forEach { container.mesh.deleteLine(it) }
            },
            placeFrame = ::placeFrame,
            isJson = ::isJson,
        )
    }

    private fun savedRow(): JSONObject = jsonOf(saved.row(say(R.string.msgr_saved_title))) as JSONObject

    private fun savedRoute(method: String, seg: List<String>, query: String, body: String?): String {
        val o = body?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
        val input = o?.let(::savedInput)
        val imports = o?.optJSONArray("messages")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::savedInput) }
        }
        return when (val r = saved.handle(method, seg, afterSeq(query), input, imports, say(R.string.msgr_saved_title))) {
            is WebMessengerSaved.Reply.Row -> ok(jsonOf(r.fields))
            is WebMessengerSaved.Reply.Feed -> {
                val marks = saved.reactions()
                ok(JSONArray().also { arr -> r.lines.forEach { (seq, m) -> arr.put(savedLine(m, seq, marks)) } })
            }
            is WebMessengerSaved.Reply.Line -> ok(savedLine(r.note, r.seq, saved.reactions()).also { if (r.frame != null) it.put("frame", r.frame) })
            is WebMessengerSaved.Reply.Done -> ok(jsonOf(r.fields))
            is WebMessengerSaved.Reply.Gone -> {
                val blobs = PeerFile.describe(r.note.body)
                    ?.let { if (container.peerFiles.drop(SavedNotes.ID, it.blobId)) 1 else 0 } ?: 0
                ok(JSONObject().put("deleted", true).put("for_everyone", false).put("blobs", blobs).put("frame", "local"))
            }
            is WebMessengerSaved.Reply.Cleared -> {
                val blobs = if (container.peerFiles.forget(SavedNotes.ID)) 1 else 0
                ok(JSONObject().put("cleared", r.count).put("blobs", blobs))
            }
            is WebMessengerSaved.Reply.Refused -> fail(r.status, r.code, savedWhy(r))
        }
    }

    private fun savedInput(o: JSONObject) = WebMessengerSaved.Input(
        kind = o.optString("kind"),
        text = o.optString("body"),
        clientId = o.optString("client_id"),
        replyToId = o.optString("reply_to_id"),
        sentAtMs = o.optString("sent_at").takeIf { it.isNotBlank() }
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L,
        emoji = o.optString("emoji"),
    )

    // Рядок малює той самий [message], що й лист людині; дорогу й долю вкладення
    // він рахує з журналу сітки, де нотаток немає, тож ці три поля — свої.
    private fun savedLine(m: PeerMessage, seq: Int, marks: Map<String, Map<String, List<String>>>): JSONObject {
        val att = PeerFile.describe(m.body)
        if (att != null) container.peerFiles.copyTo(SavedNotes.ID, att.blobId)
        return message(m, seq, SavedNotes.ID, marks)
            .put("transport", WebMessengerSaved.ROAD)
            .put("relay_state", WebMessengerSaved.ROAD)
            .put(
                "attachment_state",
                att?.let {
                    when (container.peerFiles.fate(SavedNotes.ID, it)) {
                        PeerFileStore.Fate.READY -> "stored"
                        PeerFileStore.Fate.BROKEN -> "broken"
                        else -> "missing"
                    }
                } ?: JSONObject.NULL,
            )
    }

    private fun savedWhy(r: WebMessengerSaved.Reply.Refused): String = when (r.why) {
        WebMessengerSaved.Why.BAD_BODY -> say(R.string.msgr_bridge_bad_body)
        WebMessengerSaved.Why.EMPTY -> say(R.string.msgr_bridge_empty_text)
        WebMessengerSaved.Why.SERVICE_PREFIX -> say(R.string.peer_typed_service_prefix)
        WebMessengerSaved.Why.PLACE -> say(R.string.msgr_bridge_place_unreadable)
        WebMessengerSaved.Why.CARD -> say(R.string.msgr_bridge_card_unreadable)
        WebMessengerSaved.Why.CARD_TOO_BIG -> say(R.string.msgr_bridge_card_too_big)
        WebMessengerSaved.Why.KIND -> say(R.string.msgr_saved_kind_not_kept, r.arg)
        WebMessengerSaved.Why.NO_MESSAGE -> say(R.string.msgr_bridge_no_message)
        WebMessengerSaved.Why.NOT_WORDS -> say(R.string.msgr_bridge_edit_not_words)
        WebMessengerSaved.Why.MEDIA -> say(R.string.msgr_saved_media_unreadable)
        WebMessengerSaved.Why.PERMANENT -> say(R.string.msgr_saved_permanent)
    }

    // ── службове ─────────────────────────────────────────────────────────

    /** Текст, який побачить людина, живе в strings.xml — і в обох мовах. */
    /** Рядки мовою, обраною в Nightpost, а не мовою списку телефона (WebView тягне свою, і рядки виходили англійські). */
    private fun appContext(): android.content.Context {
        val base = container.applicationContext
        return local.phantom.companion.core.data.locale.AppLanguage.localized(
            base,
            local.phantom.companion.core.data.locale.AppLanguage.current(base),
        )
    }

    private fun say(id: Int): String = appContext().getString(id)

    private fun say(id: Int, vararg args: Any): String = appContext().getString(id, *args)

    private val SPACES = Regex("\\s+")

    /**
     * Розмова, у якій звіряти нема чого й нема з ким.
     *
     * `null` тут не годиться: бандл на `contact_verified: null` не малює в
     * шапці нічого, а мовчання поруч зі словом «Звірено», яке щойно прибрали,
     * читалось би так само спокійно. `false` каже «Не звірено» й веде в ту саму
     * панель, що вже пояснює причину.
     */
    private val VERIFIED_NEVER_HAPPENED = false

    /** Перелік стає масивом, відсутнє — null'ом JSON. Решта їде як є. */
    private fun jsonOf(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is List<*> -> JSONArray().also { arr -> value.forEach { arr.put(jsonOf(it)) } }
        is Map<*, *> -> JSONObject().also { o ->
            value.forEach { (key, v) -> o.put(key.toString(), jsonOf(v)) }
        }
        else -> value
    }

    /**
     * Тіло листа таким, яким його побачить веб.
     *
     * Три джерела й жодного четвертого: слова людини, JSON картки, яку веб уже
     * вміє малювати, або чесне речення про рід, спроможності якого тут немає.
     * Сирий дріт не їде НІКОЛИ — саме він опинявся в бульбашці, коли рід
     * вирішувався не тут.
     */
    private fun bodyOf(fields: Map<String, Any?>, raw: String, who: local.phantom.companion.core.solana.SolanaNotice.Who? = null): String {
        // Слова моделі — без позначки, якою вони їхали. `raw` тут ще несе
        // `PH-AI:`, і віддати його як є означало б показати людині протокол.
        aiSaid(fields)?.let { return it }
        if (fields[WebMessengerKinds.NOTICE] != null) return notice(fields, who)
        (fields[WebMessengerKinds.CARD] as? Map<*, *>)?.let { return jsonOf(it).toString() }
        return if (WebMessengerKinds.isSpokenKind(fields[WebMessengerKinds.KIND])) raw else ""
    }

    /**
     * Вміст картки — у те поле, з якого його читає стрічка.
     *
     * Класти тіло в `body` не можна: бандл малює віджет лише коли поруч із
     * `type` лежить його ВЛАСНЕ поле (`type==="widget:kanban"&&x.kanbanData`).
     * Без нього картка доїжджає й не видно — порожня бульбашка з годинником,
     * тобто мовчазна поразка на самому кінці дороги.
     *
     * Імʼя поля приїхало з [PeerCard.Card.webField], а не складене тут: два
     * переліки слів веба про ту саму картку розійшлися б.
     *
     * Тіло, яке не розібралось, НЕ їде порожнім обʼєктом: рід міняється на
     * [WebMessengerKinds.BROKEN], і людина бачить зламане, а не порожнє.
     */
    private fun cardInto(o: JSONObject, fields: Map<String, Any?>): JSONObject {
        val field = fields[WebMessengerKinds.CARD_FIELD] as? String ?: return o
        val raw = fields[WebMessengerKinds.CARD_JSON] as? String ?: return o
        val payload = runCatching { JSONObject(raw) }.getOrNull()
            ?: return o
                .put(WebMessengerKinds.KIND, WebMessengerKinds.BROKEN)
                .put(WebMessengerKinds.BROKEN_KIND, fields[WebMessengerKinds.KIND])
                .put("broken_reason", say(R.string.peer_frame_broken))
        return o.put(field, payload)
    }

    /**
     * Позначки стану на рядку картки: `card_marks: {"<значення>": ["<автор>", …]}`.
     *
     * Лише там, де є картка (`CARD_FIELD`): на звичайному листі поля немає.
     * Ціль — [WebMessengerCardMarks.targetOf] (у групі не id копії, а ключ
     * віяра), автори — лише учасники розмови, зняте знято. Згорнутий журнал
     * приходить готовим, коли міст віддає всю стрічку: один прохід на розмову.
     * Форму складає [WebMessengerCardMarks.onLine] — та сама, що у відповіді на дотик.
     */
    private fun cardMarksInto(
        o: JSONObject,
        fields: Map<String, Any?>,
        line: PeerMessage,
        group: PeerGroup.Group?,
        folded: Map<String, Map<String, List<String>>>?,
    ): JSONObject {
        if (fields[WebMessengerKinds.CARD_FIELD] !is String) return o
        val marks = WebMessengerCardMarks.onLine(
            folded ?: WebMessengerCardMarks.fold(container.mesh.outbox.value),
            line,
            group?.memberIds ?: listOf(line.fromId, line.toId),
        )
        return o.put("card_marks", JSONObject(marks.toString()))
    }

    // ── заклади й бали: місце, гаманець, слова ─────────────────────────────

    /**
     * Де я — з воріт місця, а не з веба. Підміна чи відсутність — відмова словом
     * воріт. Що саме годиться за «місце», вирішує [WebMessengerVenues.hereOf]:
     * для «Я на місці» — лише точний і свіжий злитий фікс (клітинка закладу менша
     * за похибку мережевого), для «поруч» — фікс воріт або свіже останнє відоме,
     * але не позиція за IP і не здогад днями давності.
     */
    private fun hereNow(use: WebMessengerVenues.Use): WebMessengerVenues.Here =
        WebMessengerVenues.hereOf(container.locationSnapshotProvider(), use) { say(it) }

    private fun ownWallet(): WebMessengerPoints.Wallet? {
        val state = local.phantom.companion.core.solana.SolanaLayer.state.value
        val address = state.address?.base58 ?: return null
        return WebMessengerPoints.Wallet(
            address = address,
            cluster = state.cluster.wire,
            builtIn = state.kind == local.phantom.companion.core.solana.WalletKind.BUILT_IN,
        )
    }

    private fun messengerEnglish(): Boolean =
        local.phantom.companion.core.data.locale.ReplyLanguage.locale(
            local.phantom.companion.core.data.locale.ReplyLanguage.picked(container.applicationContext),
        ).language == java.util.Locale.ENGLISH.language

    /** Слова моделі або null — це не її лист. Одне читання на тіло, рядок і підпис. */
    private fun aiSaid(fields: Map<String, Any?>): String? =
        fields[WebMessengerAi.SAID] as? String

    /** Хто на другому кінці картки переказу: лише названа людина, не хеш. */
    private fun solanaWho(peerId: String, outgoing: Boolean): local.phantom.companion.core.solana.SolanaNotice.Who? =
        container.peers.peers.value.firstOrNull { it.id == peerId }?.name?.takeIf { it.isNotBlank() }
            ?.let { local.phantom.companion.core.solana.SolanaNotice.Who(it, outgoing) }

    private fun notice(fields: Map<String, Any?>, who: local.phantom.companion.core.solana.SolanaNotice.Who? = null): String {
        val code = fields[WebMessengerKinds.NOTICE] as? String ?: return ""
        val a = (fields[WebMessengerKinds.NOTICE_ARGS] as? List<*>)?.map { it?.toString().orEmpty() }.orEmpty()
        val ctx = container.applicationContext
        fun at(i: Int) = a.getOrElse(i) { "" }
        return when (code) {
            WebMessengerCards.SAY_VOICE -> ctx.getString(R.string.msgr_say_voice, at(0))
            WebMessengerCards.SAY_EVENT ->
                if (at(3).isEmpty()) ctx.getString(R.string.msgr_say_event, at(0), at(1), at(2))
                else ctx.getString(R.string.msgr_say_event_where, at(0), at(1), at(2), at(3))
            WebMessengerCards.SAY_BODILESS -> say(R.string.msgr_say_bodiless)
            WebMessengerCards.SAY_EMPTY -> ctx.getString(R.string.msgr_say_empty, at(0))
            WebMessengerCards.SAY_SOLANA -> local.phantom.companion.core.solana.SolanaNotice.chatLine(ctx, at(0), who).orEmpty()
            else -> ctx.getString(R.string.msgr_say_unknown, at(0))
        }
    }

    // Словник ЗАКРИТИЙ: розбирач бандла знає лише queued/failed/sending, а все
    // інше зсипає в «надіслано» (гілка «інакше» в `Ge`, index-*.js). Тому HELD
    // навмисно згортається в грубе, але чесне "queued": окреме слово "held"
    // бандл не впізнає й покаже галочку «надіслано» на листі, який лежить у
    // сховку й адресата не бачив. Точність сюди приходить лише РАЗОМ з новим
    // бандлом, який її читає, — і окремим полем, не новим значенням цього.
    // Стереже WebMessengerStateVocabularyTest.
    private fun state(s: PeerMessage.State): String = when (s) {
        PeerMessage.State.QUEUED -> "queued"
        PeerMessage.State.SENT -> "sending"
        PeerMessage.State.HELD -> "queued"
        PeerMessage.State.DELIVERED -> "delivered"
        PeerMessage.State.FAILED -> "failed"
    }

    /** Те, чого не буває в РЯДКУ переліку: важке й службове. */
    private val ROW_HEAVY = setOf("route_waypoints", "route_shape")

    private fun afterSeq(query: String): Int =
        query.split('&').firstOrNull { it.startsWith("after_seq=") }?.substringAfter('=')?.toIntOrNull() ?: 0

    private fun iso(ms: Long): String = Instant.ofEpochMilli(if (ms > 0) ms else 0).toString()

    // ── дзвінки 1:1 та ICE ──────────────────────────────────────────────────
    // Список ICE-серверів і TURN-пара — у [WebMessengerIce] (container.webMessengerIce):
    // один інстанс на застосунок, свій кеш, свій HTTP-клієнт.

    /**
     * Сигнал дзвінка — у сітку, а не в HTTP.
     *
     * Доти тут стояв `POST` на адресу вигляду
     * `http://<хост>:<порт>/api/v1/messenger/call/inbound`, де хост і порт —
     * із коду запрошення. На тому порту слухає
     * [local.phantom.companion.core.net.peer.PeerLink], і він читає рядок
     * `PHANTOM/1 <verb> <len>`; усе інше кидає мовчки. Тобто НІ ОДИН дзвінок не
     * доходив до другого апарата, і ця ручка була ручкою в нікуди.
     *
     * Тепер тіло, яке склав рушій у вебі, їде ЦІЛИМ у запечатаному конверті —
     * тією самою дорогою, що й слово, — і на тому боці лягає в
     * `window.__phantomCallSignal`, тобто в той самий рушій. Телефон SDP не
     * читає й не складає: третя правда про формат розійшлася б із рушієм на
     * першому ж перезбиранні бандла.
     *
     * Відповідь лишається тієї самої форми (`delivered`/`call_id`/`detail`), бо
     * про неї домовлено з вебом — і він на ній будує свої «пропозиція не
     * доїхала»/«вузол не прийняв сигнал». Змінилась дорога, не контракт.
     *
     * Група більше не відмовлена. `contact_id` групи веде в [handleGroupCallSignal]:
     * той самий кадр їде [PeerGroup.fanOut] копіями кожному, тією ж дорогою, що
     * й до однієї людини, — інших доріг немає й не буде (лист-сховок мертвий
     * для дзвінка за хвилину, див. [PeerCall.SIGNAL]).
     */
    private suspend fun handleCallSignal(kind: String, body: String?): String {
        if (body.isNullOrBlank()) return fail(400, "bad_request", "empty body")
        val json = runCatching { JSONObject(body) }.getOrElse {
            return fail(400, "bad_format", "invalid json")
        }
        val callId = json.optString("call_id")
        if (callId.isBlank()) return fail(400, "no_call_id", "missing call_id")

        val contactId = json.optString("contact_id").takeIf { it.isNotBlank() }
        val peerNodeId = json.optString("peer_node_id").takeIf { it.isNotBlank() }

        // Рід із закритого словника. Слово, якого рушій не шле, не їде взагалі:
        // поїхати «чимось схожим» означало б стати не тим кадром на тому кінці.
        if (PeerCall.Signalling.ofWeb(kind) == null) {
            return fail(400, "unknown_call_kind", say(R.string.msgr_bridge_call_kind, kind))
        }

        val group = contactId?.let { WebMessengerGroups.find(container.mesh.outbox.value, it) }
        if (group != null) {
            return handleGroupCallSignal(group, kind, callId, json)
        }

        val peer = container.peers.peers.value.firstOrNull {
            (contactId != null && it.id == contactId) || (peerNodeId != null && it.id == peerNodeId)
        } ?: return fail(404, "no_contact", say(R.string.msgr_bridge_no_contact))

        val delivered = offLane {
            container.callHub.post(
                peerId = peer.id,
                kind = kind,
                callId = callId,
                payload = json,
                myNodeId = container.mesh.selfIdFlow.value.ifBlank { "phone_node" },
            )
        }

        if (local.phantom.companion.BuildConfig.DEBUG) runCatching {
            android.util.Log.i(
                "PhantomCall",
                "bridge $kind delivered=$delivered canCall=${container.mesh.canCall(peer.id)} live=${container.mesh.relayLiveReady()} dh=${peer.dhPubKey.isNotBlank()}",
            )
        }

        // Немає прямої дороги — так і кажемо, замість «набираємо…» над сигналом
        // у нікуди. Сховок для дзвінка закритий за побудовою: лист лежить там до
        // семи діб, а дзвінок мертвий за хвилину.
        val res = JSONObject()
            .put("delivered", delivered)
            .put("call_id", callId)
            .put(
                "detail",
                when {
                    delivered -> ""
                    !container.mesh.canCall(peer.id) -> say(R.string.msgr_bridge_call_no_address)
                    else -> say(R.string.msgr_bridge_call_refused)
                },
            )

        return ok(res)
    }

    /**
     * Запрошення гілки в кімнату. Кожному учаснику — СВОЯ нога.
     *
     * `delivered` тут — не одне «так» на всіх: із N людей стани різні
     * по-справжньому (сховок для дзвінка закритий, тож «не поруч» — це «не
     * подзвонило», а не «зачекає»), і одне булеве значення сховало б саме те,
     * що людині треба почути.
     *
     * Це вже НЕ розсилка одного кадру, як було до 16.09. [PeerCallHub.invite]
     * заводить у кімнаті окрему ногу на кожного, і рушій у вебі домовляється з
     * кожним апаратом окремо — повна сітка. Стеля ніг заміряна, а не вигадана
     * ([PeerCallLine.MAX_LEGS], метод і число — `docs/MEETS_SCALE.md`), і тим,
     * хто в неї не влазить, кадр не шлеться взагалі: `too_many` каже скільком,
     * щоб веб промовив це людині, а не мовчав.
     */
    private suspend fun handleGroupCallSignal(
        group: PeerGroup.Group,
        kind: String,
        callId: String,
        payload: JSONObject,
    ): String {
        val me = container.mesh.selfIdFlow.value
        val members = PeerGroup.fanOut(group, me)
        if (members.isEmpty()) {
            return ok(
                JSONObject()
                    .put("delivered", false)
                    .put("call_id", callId)
                    .put("delivered_count", 0)
                    .put("member_count", 0)
                    .put("detail", say(R.string.msgr_bridge_call_group_empty)),
            )
        }
        val fan = offLane {
            container.callHub.invite(
                groupId = group.id,
                memberIds = members,
                kind = kind,
                callId = callId,
                payload = payload,
                myNodeId = me.ifBlank { "phone_node" },
            )
        }
        return ok(
            JSONObject()
                .put("delivered", fan.delivered > 0)
                .put("call_id", callId)
                .put("delivered_count", fan.delivered)
                .put("member_count", fan.total)
                .put("too_many", fan.tooMany)
                .put("max_legs", PeerCallLine.MAX_LEGS)
                .put(
                    "detail",
                    when {
                        fan.tooMany > 0 -> say(
                            R.string.msgr_bridge_call_room_full, fan.tooMany, PeerCallLine.MAX_LEGS,
                        )
                        fan.delivered == fan.total -> ""
                        else -> say(R.string.msgr_bridge_call_group_partial, fan.delivered, fan.total)
                    },
                ),
        )
    }

    /**
     * Кімната, як її бачить телефон: нога за ногою, стан за станом.
     *
     * Рушій у вебі знає лише ті зʼєднання, які збудував САМ. Ноги, заведені
     * поки WebView не було на екрані (дзвінок у закритий застосунок,
     * «відхилити» зі шторки, нога, яка згасла за хвилину), у ньому не
     * існують — і без цієї ручки він домальовував би кімнату з власної
     * памʼяті, тобто вигадував би її.
     *
     * Час у полях — мілісекунди епохи, а не «скільки триває»: тривалість
     * порахує той, хто малює, і порахує від справжньої мітки, а не від миті,
     * коли він спитав.
     */
    private fun callRoom(): String {
        val room = container.callHub.room.value
        val legs = JSONArray()
        for (leg in PeerCallLine.legsOf(room)) {
            legs.put(
                JSONObject()
                    .put("peer_id", leg.peerId)
                    .put("name", nameOfPeer(leg.peerId))
                    .put("foot", leg.foot.name.lowercase())
                    .put("since_ms", leg.sinceMs)
                    .put("why", leg.why ?: JSONObject.NULL)
                    // Дорога до цього апарата просто зараз. Нога в кімнаті й
                    // дорога до неї — різні речі: нога є, а дороги може не
                    // бути, і мовчати про це не можна.
                    .put("reachable", container.mesh.canCall(leg.peerId)),
            )
        }
        return ok(
            JSONObject()
                .put("call_id", room?.callId ?: JSONObject.NULL)
                .put("group_id", room?.groupId ?: JSONObject.NULL)
                .put("legs", legs)
                .put("live", PeerCallLine.liveLegs(room).size)
                .put("max_legs", PeerCallLine.MAX_LEGS)
                .put("room_left", PeerCallLine.roomLeft(room))
                .put("crowded", PeerCallLine.isCrowded(room))
                // Не лише «тісно вже», а З ЯКОЇ НОГИ тісно. Веб малює
                // попередження сам і має вирішувати це на своїх числах: інакше
                // він або питав би міст щоразу, або тримав би другу копію
                // стелі — і та копія розійшлася б із цією на першій же правці.
                .put("crowded_at", PeerCallLine.CROWDED_LEGS),
        )
    }

    /** Імʼя ноги: людина з реєстру, гілка зі сховка, інакше короткий хеш. */
    private fun nameOfPeer(id: String): String =
        container.peers.peers.value.firstOrNull { it.id == id }?.name
            ?: PeerGroup.known(container.mesh.outbox.value).firstOrNull { it.id == id }?.name
            ?: PeerRoster.shortId(id)

    private fun whereRoute(method: String, peerId: String, verb: String?, body: String?): String {
        val hub = container.peopleWhere
        fun state() = ok(JSONObject(WebMessengerWhere.fields(hub.view(peerId), System.currentTimeMillis())))
        if (container.peers.peers.value.none { it.id == peerId }) {
            return fail(404, "no_contact", say(R.string.msgr_bridge_no_contact))
        }
        return when {
            verb == null && method == "GET" -> state()
            verb == null && method == "POST" -> {
                val o = body?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?: return fail(400, "bad_body", say(R.string.msgr_bridge_bad_body))
                val precision = WebMessengerWhere.precisionOf(o.optString("precision"))
                    ?: return fail(400, "bad_precision", say(R.string.msgr_where_bad_choice))
                val meet = o.optBoolean("meet", false)
                val now = System.currentTimeMillis()
                val until = if (meet) {
                    WebMessengerWhere.meetUntil(precision, o.optLong("until_ms", 0L), now)
                } else {
                    WebMessengerWhere.untilFor(if (o.isNull("minutes")) null else o.optInt("minutes", -1), now)
                } ?: return fail(400, "bad_span", say(R.string.msgr_where_bad_choice))
                if (!hub.setPrecision(peerId, precision, until, meet)) {
                    return fail(403, "not_eligible", say(R.string.msgr_where_not_ready))
                }
                state()
            }
            verb == null && method == "DELETE" -> {
                hub.setPrecision(peerId, null)
                hub.debugMine(peerId, null)
                state()
            }
            verb == "map" && method == "POST" -> {
                val focus = hub.focus(peerId) ?: return fail(404, "no_position", say(R.string.msgr_where_gone))
                container.scope.launch(Dispatchers.Main) {
                    runCatching {
                        container.spatialLayerRepository.requestFocus(
                            local.phantom.companion.core.data.spatial.FocusTarget(focus.lat, focus.lon, focus.zoom),
                        )
                    }
                    container.homeTabRequest.value = local.phantom.companion.ui.home.HomeTab.MAP
                }
                ok(JSONObject().put("opened", true))
            }
            else -> fail(404, "not_found", say(R.string.msgr_bridge_no_path, "where/$peerId/${verb.orEmpty()}"))
        }
    }

    /** Мережа й Keystore — на IO: смуга порту тим часом бере наступний запит. */
    private suspend fun <T> offLane(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun ok(data: Any): String =
        JSONObject().put("ok", true).put("status", 200).put("data", data).toString()

    private fun fail(status: Int, code: String, detail: String): String =
        JSONObject().put("ok", false).put("status", status).put("code", code).put("detail", detail).toString()

    private companion object {
        /**
         * Слова, які під `/call/` є ДІЯМИ над кімнатою, а не родами сигналу.
         *
         * Живуть списком, а не вкрапленням у кожну гілку: інакше наступний, хто
         * додасть дію, забуде відняти її від [handleCallSignal], і вона мовчки
         * поїде туди як невідомий рід. Роди — закритий словник
         * [PeerCall.Signalling], і ці двоє в ньому свідомо відсутні.
         */
        val CALL_ROOM_VERBS = setOf("room", "leave", "drop", "ready", "audio-route")
    }
}

/** Вибір теми рідного боку словом вебу: «Як сонце» — Living і «як телефон». */
internal fun webThemeModeOf(mode: local.phantom.companion.core.design.theme.ThemeMode): String = when (mode) {
    local.phantom.companion.core.design.theme.ThemeMode.Dark -> "night"
    local.phantom.companion.core.design.theme.ThemeMode.Light -> "day"
    local.phantom.companion.core.design.theme.ThemeMode.Living,
    local.phantom.companion.core.design.theme.ThemeMode.System -> "auto"
}

/** Слово вебу → вибір рідного боку; невідоме слово — нічого не міняємо. */
internal fun nativeThemeModeOf(word: String): local.phantom.companion.core.design.theme.ThemeMode? = when (word) {
    "night" -> local.phantom.companion.core.design.theme.ThemeMode.Dark
    "day" -> local.phantom.companion.core.design.theme.ThemeMode.Light
    "auto" -> local.phantom.companion.core.design.theme.ThemeMode.Living
    else -> null
}
