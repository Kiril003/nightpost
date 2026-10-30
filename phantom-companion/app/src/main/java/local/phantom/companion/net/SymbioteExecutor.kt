package local.phantom.companion.net

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import local.phantom.companion.R
import local.phantom.companion.core.sensor.nav.ConsistencyGate
import local.phantom.companion.data.BodyLadder
import local.phantom.companion.feature.godmode.engine.ActionBounds
import local.phantom.companion.feature.godmode.engine.ActionTarget
import local.phantom.companion.feature.godmode.engine.AppAction
import local.phantom.companion.feature.godmode.engine.NotificationRecentStore
import local.phantom.companion.feature.godmode.engine.ScreenContextStore
import local.phantom.companion.feature.godmode.engine.ScrollDirection
import local.phantom.companion.feature.godmode.service.AccessibilityServiceActionRegistry
import local.phantom.companion.feature.godmode.service.UiSnapshot
import local.phantom.companion.service.NotificationLook

/**
 * Друге тіло симбіота: пульс на ПК і виконання його прохань.
 *
 * Кожне прохання спершу йде крізь [BodyLadder] — драбину, яку тримає сам
 * телефон. Раніше тут виконувалось будь-що, що приходило з ПК.
 */
class SymbioteExecutor(
    private val context: Context,
    private val api: PhantomApi,
    private val link: PhantomLink,
    private val scope: CoroutineScope,
    /**
     * Єдине джерело позиції для всього, що йде на ПК. Раніше тут напряму
     * опитувались GPS_PROVIDER/NETWORK_PROVIDER — і підмінений фікс (Ліма при
     * реальному Києві) виїжджав з пристрою повз усі перевірки.
     */
    private val awaitVerdict: suspend () -> ConsistencyGate.Verdict?,
    private val ladder: BodyLadder = BodyLadder.of(context),
    private val speak: suspend (String) -> Result<Unit>,
) {
    private val tag = "SymbioteExec"
    private var loop: Job? = null
    private var ear: Job? = null
    private var guard: Job? = null
    private val turn = Mutex()
    private val answers = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val offers = ConcurrentHashMap<String, PhantomApi.SymbioteCommand>()
    private var finderTone: Ringtone? = null

    private val hands = object : SymbioteOrders.Body {
        override suspend fun perform(command: PhantomApi.SymbioteCommand) = execute(command)
        override suspend fun askYes(command: PhantomApi.SymbioteCommand, what: String): Boolean {
            val answer = CompletableDeferred<Boolean>()
            answers[command.id] = answer
            showAsk(command, context.getString(R.string.kt_sym_asks, what), yesLabel = context.getString(R.string.kt_sym_yes))
            return try {
                answer.await()
            } finally {
                answers.remove(command.id)
                cancelNote(command.id)
            }
        }
        override fun suggest(command: PhantomApi.SymbioteCommand, what: String) {
            offers[command.id] = command
        }
        override fun report(what: String) = Unit
        override suspend fun ringOnce() = withContext(Dispatchers.Main) { keepFinderTone() }
        override fun finding(on: Boolean) = showFinding(on)
    }

    private val orders = SymbioteOrders(ladder, hands, scope)

    private val tapped = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id = intent.getStringExtra(EXTRA_ID).orEmpty()
            when (intent.action) {
                ACTION_FOUND -> orders.stopRinging()
                ACTION_YES, ACTION_NO -> {
                    val yes = intent.action == ACTION_YES
                    answers[id]?.complete(yes)
                    offers.remove(id)?.let { offer ->
                        cancelNote(id)
                        if (yes) scope.launch(Dispatchers.IO) { api.postResult(execute(offer)) }
                    }
                }
            }
        }
    }

    fun start() {
        if (loop?.isActive == true) return
        ears()
        loop = scope.launch(Dispatchers.IO) {
            Log.i(tag, "петля пішла")
            while (isActive) {
                runCatching { tick() }
                    .onFailure { Log.d(tag, "оберт не вдався: ${it.message}") }
                delay(TICK_MS)
            }
        }
        listen()
    }

    @Synchronized
    private fun ears() {
        if (guard?.isActive == true) return
        ContextCompat.registerReceiver(
            context,
            tapped,
            IntentFilter().apply {
                addAction(ACTION_FOUND)
                addAction(ACTION_YES)
                addAction(ACTION_NO)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        guard = scope.launch {
            ladder.stopped.collect { stopped -> if (stopped) orders.halt() }
        }
    }

    /**
     * Прохання, що народилось на самому телефоні (чат PHANTOM, налагодження adb),
     * — та сама драбина, те саме «так» сповіщенням, що й для листа з ПК.
     */
    suspend fun handleLocal(
        command: PhantomApi.SymbioteCommand,
        post: suspend (PhantomApi.ResultIn) -> Unit,
    ) {
        ears()
        orders.handle(command, post)
    }

    private fun listen() {
        if (ear?.isActive == true) return
        ear = scope.launch(Dispatchers.IO) {
            link.events.collect { event ->
                if (event.channel != "symbiote" || event.type != "command") return@collect
                runCatching { drainInbox() }
                    .onFailure { Log.d(tag, "поштовх не вдався: ${it.message}") }
            }
        }
    }

    private suspend fun tick() = turn.withLock {
        api.postPresence(readPresence())
        deliver()
    }

    private suspend fun drainInbox() = turn.withLock { deliver() }

    private suspend fun deliver() {
        for (command in api.fetchInbox()) {
            orders.handle(command) { api.postResult(it) }
        }
    }

    private suspend fun readPresence(): PhantomApi.PresenceOut {
        val battery = runCatching {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrNull()
        val charging = runCatching {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.isCharging
        }.getOrNull()
        // Пульс іде на ПК кожні 30 с і теж ніс координати повз гейт. У пульсі
        // немає куди покласти сумнів, тож він несе лише живе небо: утримана
        // точка може бути годинної давнини, а на тому кінці її покажуть як «зараз».
        val fix = (gatedFix() as? Fix.Allowed)?.takeIf { it.fromSky }
        return PhantomApi.PresenceOut(
            battery_pct = battery?.takeIf { it in 0..100 },
            charging = charging,
            network = "wifi",
            foreground = "Nightpost",
            lat = fix?.lat,
            lon = fix?.lon,
            accuracy_m = fix?.accuracyM,
        )
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Або позиція, яку гейт дозволив назвати, або відмова з причиною. */
    private sealed interface Fix {
        data class Allowed(
            val lat: Double,
            val lon: Double,
            val accuracyM: Double,
            val trust: String,
            val origin: String,
            val note: String?,
            /** Live sky. False = a held anchor, which may be an hour old. */
            val fromSky: Boolean,
        ) : Fix

        data class Refused(val why: String) : Fix
    }

    private suspend fun gatedFix(): Fix {
        if (!hasLocationPermission()) return Fix.Refused("немає дозволу на місце")
        val verdict = awaitVerdict()
            ?: return Fix.Refused("перевірка позиції ще не дала відповіді — місця не віддаю")
        if (verdict.status == ConsistencyGate.Status.SPOOFED) {
            // Мережева «підстраховка» гейта тут теж не їде: на тому кінці з
            // payload беруть координати, а не речення поруч із ними.
            return Fix.Refused(verdict.message ?: "підміна GPS — місця не віддаю")
        }
        if (!verdict.hasPosition) {
            return Fix.Refused(verdict.message ?: "місця ще немає")
        }
        return Fix.Allowed(
            lat = verdict.displayLat,
            lon = verdict.displayLon,
            accuracyM = verdict.displayHAccM.toDouble(),
            trust = when (verdict.status) {
                ConsistencyGate.Status.TRUSTED -> "перевірено"
                ConsistencyGate.Status.DEGRADED -> "приблизно"
                else -> "остання перевірена"
            },
            origin = when (verdict.source) {
                ConsistencyGate.Source.FUSED -> "супутники"
                ConsistencyGate.Source.NETWORK_HOLD -> "мережа"
                else -> "утримання"
            },
            note = verdict.message,
            fromSky = verdict.source == ConsistencyGate.Source.FUSED,
        )
    }

    /**
     * Дієслово тіла з дзвінка PHANTOM. Драбину ([BodyLadder.judge]) і «так»
     * людини вже пройдено в мості дзвінка; тут — лише ті самі руки.
     */
    suspend fun actFromCall(verb: String, args: Map<String, String>): PhantomApi.ResultIn =
        execute(
            PhantomApi.SymbioteCommand(
                id = "call_" + java.util.UUID.randomUUID().toString().take(8),
                verb = verb,
                args = args,
                issued_by = "phantom_call",
            ),
        )

    private suspend fun execute(command: PhantomApi.SymbioteCommand): PhantomApi.ResultIn =
        when (command.verb) {
            "phone.locate" -> locate(command)
            "phone.ring" -> ring(command)
            "phone.notify" -> notify(command)
            "phone.speak" -> speakOut(command)
            "phone.wifi_scan" -> wifiScan(command)
            // SEE — очі симбіота.
            "phone.screen.read" -> screenRead(command)
            "phone.notifications.recent" -> notificationsRecent(command)
            "phone.state" -> state(command)
            // ACT — руки симбіота (крізь службу доступності).
            "phone.app.open" -> appOpen(command)
            "phone.tap" -> tap(command)
            "phone.type" -> type(command)
            "phone.scroll" -> scroll(command)
            "phone.back" -> globalAct(command, AppAction.Back, "назад")
            "phone.home" -> globalAct(command, AppAction.Home, "на головний екран")
            "phone.intent" -> intent(command)
            "phone.clipboard.set" -> clipboardSet(command)
            else -> PhantomApi.ResultIn(
                command_id = command.id,
                ok = false,
                detail = "цього тіло ще не вміє: ${command.verb}",
            )
        }

    /** Останній знімок екрана, щоб phone.tap/phone.type знали, що таке «n3». */
    @Volatile private var lastScreen: UiSnapshot? = null

    private suspend fun screenRead(command: PhantomApi.SymbioteCommand): PhantomApi.ResultIn =
        withContext(Dispatchers.Main) {
            val snap = AccessibilityServiceActionRegistry.snapshotUi()
                ?: return@withContext PhantomApi.ResultIn(
                    command.id, ok = false,
                    detail = "не бачу екран — служба доступності Nightpost вимкнена",
                    payload = mapOf("status" to "accessibility_off"),
                )
            lastScreen = snap
            if (snap.banking) {
                // Банк закрито для SEE: чесно кажемо, а не віддаємо порожнечу як екран.
                return@withContext PhantomApi.ResultIn(
                    command.id, ok = false,
                    detail = snap.render(),
                    payload = mapOf("status" to "blocked_banking"),
                )
            }
            PhantomApi.ResultIn(
                command.id, ok = true,
                detail = "екран прочитано",
                payload = mapOf("screen" to snap.render(), "app" to (snap.packageName ?: "")),
            )
        }

    private fun notificationsRecent(command: PhantomApi.SymbioteCommand): PhantomApi.ResultIn {
        val limit = command.args["limit"]?.toIntOrNull()?.coerceIn(1, 20) ?: 5
        val recent = NotificationRecentStore.recent(limit)
        if (recent.isEmpty()) {
            return PhantomApi.ResultIn(command.id, ok = true, detail = "свіжих сповіщень немає")
        }
        val packed = recent.joinToString("\n") { n ->
            "${n.appLabel}: ${n.title}${if (n.text.isBlank()) "" else " — ${n.text}"}"
        }
        return PhantomApi.ResultIn(
            command.id, ok = true,
            detail = "сповіщень: ${recent.size}",
            payload = mapOf("notifications" to packed, "count" to recent.size.toString()),
        )
    }

    private suspend fun state(command: PhantomApi.SymbioteCommand): PhantomApi.ResultIn {
        val bm = runCatching {
            context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        }.getOrNull()
        val battery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        val charging = bm?.isCharging
        val payload = buildMap {
            battery?.let { put("battery_pct", it.toString()) }
            charging?.let { put("charging", it.toString()) }
            put("network", "wifi")
            ScreenContextStore.current()?.let { put("foreground", it.appLabel ?: it.packageName) }
            // Місце — лише якщо гейт його дозволив назвати (та сама дорога, що й phone.locate).
            when (val fix = gatedFix()) {
                is Fix.Allowed -> {
                    put("lat", fix.lat.toString())
                    put("lon", fix.lon.toString())
                    put("accuracy_m", fix.accuracyM.toString())
                }
                is Fix.Refused -> put("location", fix.why)
            }
        }
        val words = buildString {
            battery?.let { append("батарея $it%") }
            if (charging == true) append(", заряджається")
        }.ifBlank { "стан віддано" }
        return PhantomApi.ResultIn(command.id, ok = true, detail = words, payload = payload)
    }

    private suspend fun appOpen(command: PhantomApi.SymbioteCommand): PhantomApi.ResultIn {
        val app = command.args["app"].orEmpty()
        if (app.isBlank()) {
            return PhantomApi.ResultIn(command.id, ok = false, detail = "не сказано, який застосунок")
        }
        val ok = withContext(Dispatchers.Main) {
            AccessibilityServiceActionRegistry.performOne(AppAction.OpenApp(app))
        }
        return PhantomApi.ResultIn(
            command.id, ok = ok,
            detail = if (ok) "відкрито: $app" else "не вдалося відкрити «$app» (служба доступності?)",
        )
    }

    private suspend fun tap(command: PhantomApi.SymbioteCommand): PhantomApi.ResultIn {
        val action: AppAction = when {
            command.args["ref"] != null -> {
                val node = nodeByRef(command.args["ref"]!!)
                    ?: return PhantomApi.ResultIn(
                        command.id, ok = false,
                        detail = "не знаю, що таке «${command.args["ref"]}» — спершу phone.screen.read",
                    )
                AppAction.Tap(node.centerX, node.centerY)
            }
            command.args["text"] != null -> AppAction.Click(ActionTarget(text = command.args["text"]))
            command.args["x"] != null && command.args["y"] != null ->
                AppAction.Tap(command.args["x"]!!.toInt(), command.args["y"]!!.toInt())
            else -> null
        } ?: return PhantomApi.ResultIn(command.id, ok = false, detail = "не сказано, куди торкнутись")
        val ok = withContext(Dispatchers.Main) { AccessibilityServiceActionRegistry.performOne(action) }
        return PhantomApi.ResultIn(
            command.id, ok = ok,
            detail = if (ok) "торкнувся" else "дотик не вдався (служба доступності?)",
        )
    }

    private suspend fun type(command: PhantomApi.SymbioteCommand): PhantomApi.ResultIn {
        val text = command.args["text"].orEmpty()
        if (text.isBlank()) {
            return PhantomApi.ResultIn(command.id, ok = false, detail = "нема що набирати")
        }
        val ref = command.args["ref"]
        val node = ref?.let { nodeByRef(it) }
        if (ref != null && node == null) {
            return PhantomApi.ResultIn(
                command.id, ok = false,
                detail = "не знаю, що таке «$ref» — спершу phone.screen.read",
            )
        }
        // У поля паролів PHANTOM не набирає — ні свого, ні чужого.
        if (node?.isPassword == true) {
            return PhantomApi.ResultIn(
                command.id, ok = false,
                detail = "це поле пароля — туди не набираю",
                payload = mapOf("status" to "refused_password"),
            )
        }
        val target = node?.let {
            it.resourceId?.let { id -> ActionTarget(resourceId = id) }
                ?: ActionTarget(bounds = ActionBounds(it.centerX, it.centerY, 1, 1))
        }
        val ok = withContext(Dispatchers.Main) {
            AccessibilityServiceActionRegistry.performOne(AppAction.Type(text, target))
        }
        return PhantomApi.ResultIn(
            command.id, ok = ok,
            detail = if (ok) "набрано" else "набрати не вдалося (поле не редаговане?)",
        )
    }

    private suspend fun scroll(command: PhantomApi.SymbioteCommand): PhantomApi.ResultIn {
        val dir = when (command.args["direction"]?.lowercase()) {
            "up", "вгору" -> ScrollDirection.UP
            "left", "вліво" -> ScrollDirection.LEFT
            "right", "вправо" -> ScrollDirection.RIGHT
            else -> ScrollDirection.DOWN
        }
        val ok = withContext(Dispatchers.Main) {
            AccessibilityServiceActionRegistry.performOne(AppAction.Scroll(dir))
        }
        return PhantomApi.ResultIn(
            command.id, ok = ok,
            detail = if (ok) "прокручено" else "прокрутити нічого (служба доступності?)",
        )
    }

    private suspend fun globalAct(
        command: PhantomApi.SymbioteCommand,
        action: AppAction,
        what: String,
    ): PhantomApi.ResultIn {
        val ok = withContext(Dispatchers.Main) { AccessibilityServiceActionRegistry.performOne(action) }
        return PhantomApi.ResultIn(
            command.id, ok = ok,
            detail = if (ok) what else "не вдалося: $what (служба доступності?)",
        )
    }

    private fun intent(command: PhantomApi.SymbioteCommand): PhantomApi.ResultIn {
        val action = command.args["action"].orEmpty().lowercase()
        val data = command.args["data"].orEmpty()
        // Лише безпечні наміри: НЕ дзвонимо самі — відкриваємо набирач; НЕ надсилаємо
        // SMS — відкриваємо вікно листа. Власник тисне останню кнопку сам.
        val intent: Intent = when (action) {
            "dial" -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:$data"))
            "sms" -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$data"))
            "web", "view" -> Intent(Intent.ACTION_VIEW, Uri.parse(data))
            "maps" -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(data)}"))
            "settings" -> Intent(data.ifBlank { android.provider.Settings.ACTION_SETTINGS })
            else -> null
        } ?: return PhantomApi.ResultIn(
            command.id, ok = false,
            detail = "незрозуміла дія: «$action» (dial/sms/web/maps/settings)",
        )
        return runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            PhantomApi.ResultIn(command.id, ok = true, detail = "відкрито: $action")
        }.getOrElse {
            PhantomApi.ResultIn(command.id, ok = false, detail = "не відкрилось: ${it.message}")
        }
    }

    private suspend fun clipboardSet(command: PhantomApi.SymbioteCommand): PhantomApi.ResultIn {
        val text = command.args["text"].orEmpty()
        if (text.isBlank()) {
            return PhantomApi.ResultIn(command.id, ok = false, detail = "нема що класти в буфер")
        }
        return withContext(Dispatchers.Main) {
            runCatching {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Nightpost", text))
                PhantomApi.ResultIn(command.id, ok = true, detail = "покладено в буфер")
            }.getOrElse {
                PhantomApi.ResultIn(command.id, ok = false, detail = "буфер не прийняв: ${it.message}")
            }
        }
    }

    private fun nodeByRef(ref: String): local.phantom.companion.feature.godmode.service.UiSnapshotNode? {
        val i = ref.removePrefix("n").trim().toIntOrNull() ?: return null
        return lastScreen?.nodes?.getOrNull(i)
    }

    private suspend fun locate(command: PhantomApi.SymbioteCommand): PhantomApi.ResultIn =
        when (val fix = gatedFix()) {
            is Fix.Refused -> PhantomApi.ResultIn(
                command_id = command.id,
                ok = false,
                detail = fix.why,
            )
            is Fix.Allowed -> PhantomApi.ResultIn(
                command_id = command.id,
                ok = true,
                detail = fix.note ?: "місце віддано",
                payload = mapOf(
                    "lat" to fix.lat.toString(),
                    "lon" to fix.lon.toString(),
                    "accuracy_m" to fix.accuracyM.toString(),
                    "trust" to fix.trust,
                    "origin" to fix.origin,
                ),
            )
        }

    private suspend fun ring(command: PhantomApi.SymbioteCommand): PhantomApi.ResultIn =
        withContext(Dispatchers.Main) {
            runCatching {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val ringtone = RingtoneManager.getRingtone(context, uri)
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                audio?.let {
                    ringtone?.audioAttributes = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .build()
                }
                ringtone?.play()
                PhantomApi.ResultIn(command.id, ok = true, detail = "дзвоню")
            }.getOrElse {
                PhantomApi.ResultIn(command.id, ok = false, detail = "звук не вийшов: ${it.message}")
            }
        }

    private suspend fun speakOut(command: PhantomApi.SymbioteCommand): PhantomApi.ResultIn {
        val text = command.args["text"].orEmpty()
        if (text.isBlank()) {
            return PhantomApi.ResultIn(command.id, ok = false, detail = "нема що казати")
        }
        val said = speak(text)
        return PhantomApi.ResultIn(
            command_id = command.id,
            ok = said.isSuccess,
            detail = if (said.isSuccess) "сказано" else
                "голос мовчить: ${said.exceptionOrNull()?.message ?: "невідомо"}",
        )
    }

    private fun wifiScan(command: PhantomApi.SymbioteCommand): PhantomApi.ResultIn {
        if (!hasLocationPermission()) {
            return PhantomApi.ResultIn(command.id, ok = false, detail = "без дозволу на місце радіо мовчить")
        }
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return PhantomApi.ResultIn(command.id, ok = false, detail = "радіо недоступне")
        // Дві різні відповіді, які досі звучали однаково. `.getOrNull().orEmpty()`
        // перетворював виняток на порожній список, і «система відмовила» ставало
        // невідрізнимим від «радіо нічого не бачить». Перше — наша сліпота,
        // друге — вимір. Приймач не мусить їх плутати.
        val scan = runCatching { wifi.scanResults }
        val seen = scan.getOrNull()
            ?: return PhantomApi.ResultIn(
                command.id,
                ok = false,
                detail = "не вдалося спитати радіо: ${scan.exceptionOrNull()?.message ?: "без деталей"}",
            )
        if (seen.isEmpty()) {
            return PhantomApi.ResultIn(command.id, ok = false, detail = "радіо нічого не бачить")
        }
        val packed = seen
            .sortedByDescending { it.level }
            .take(MAX_APS)
            .joinToString(";") { ap ->
                val freq = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) ap.frequency else 0
                "${ap.BSSID},${ap.level},$freq"
            }
        return PhantomApi.ResultIn(
            command_id = command.id,
            ok = true,
            detail = "видно ${seen.size} точок",
            payload = mapOf("aps" to packed, "count" to seen.size.toString()),
        )
    }

    private fun notify(command: PhantomApi.SymbioteCommand): PhantomApi.ResultIn {
        val text = command.args["body"] ?: command.args["text"] ?: "Nightpost кличе"
        return runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Симбіот", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(NotificationLook.smallIcon)
                .setColor(NotificationLook.accent)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setAutoCancel(true)
                // Текст приходить із ПК і застосунок не знає про нього нічого.
                // Чуже речення на замкненому екрані — це чуже речення на столі.
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(NotificationLook.smallIcon)
                        .setColor(NotificationLook.accent)
                        .setContentTitle(context.getString(R.string.app_name))
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .build(),
                )
                .build()
            manager.notify(NOTIFY_ID, notification)
            PhantomApi.ResultIn(command.id, ok = true, detail = "показано")
        }.getOrElse {
            PhantomApi.ResultIn(command.id, ok = false, detail = "не показалось: ${it.message}")
        }
    }

    private fun manager(): NotificationManager {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Симбіот", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        return manager
    }

    private fun tap(action: String, id: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            code,
            Intent(action).setPackage(context.packageName).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun noteId(commandId: String) = ASK_ID_BASE + (commandId.hashCode() and 0xFFFF)

    /**
     * На замкненому екрані — лише «Компʼютер чекає на відповідь», без кнопок:
     * хто тримає чужий замкнений телефон, не скаже «так» за власника.
     */
    private fun showAsk(command: PhantomApi.SymbioteCommand, text: String, yesLabel: String) {
        runCatching {
            val id = noteId(command.id)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(NotificationLook.smallIcon)
                .setColor(NotificationLook.accent)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setAutoCancel(true)
                .addAction(0, yesLabel, tap(ACTION_YES, command.id, id * 2))
                .addAction(0, "Ні", tap(ACTION_NO, command.id, id * 2 + 1))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(NotificationLook.smallIcon)
                        .setColor(NotificationLook.accent)
                        .setContentTitle(context.getString(R.string.app_name))
                        .setContentText("Компʼютер чекає на відповідь")
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .build(),
                )
                .build()
            manager().notify(id, notification)
        }.onFailure { Log.d(tag, "питання не показалось: ${it.message}") }
    }

    private fun cancelNote(commandId: String) {
        runCatching { manager().cancel(noteId(commandId)) }
    }

    /** Той, хто знайшов телефон, мусить заглушити його, не розблоковуючи. */
    private fun showFinding(on: Boolean) {
        runCatching {
            if (!on) {
                manager().cancel(FIND_ID)
                scope.launch(Dispatchers.Main) {
                    finderTone?.stop()
                    finderTone = null
                }
                return
            }
            val found = tap(ACTION_FOUND, "", FIND_ID)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(NotificationLook.smallIcon)
                .setColor(NotificationLook.accent)
                .setContentTitle("Телефон шукають")
                .setContentText("Торкнись, щоб замовкнув")
                .setOngoing(true)
                .setContentIntent(found)
                .setDeleteIntent(found)
                .addAction(0, "Знайшов", found)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
            manager().notify(FIND_ID, notification)
        }.onFailure { Log.d(tag, "пошук не показався: ${it.message}") }
    }

    private fun keepFinderTone() {
        if (finderTone?.isPlaying == true) return
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val tone = finderTone ?: RingtoneManager.getRingtone(context, uri)?.also {
                it.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.isLooping = true
            }
            finderTone = tone
            tone?.play()
        }.onFailure { Log.d(tag, "дзвін не вийшов: ${it.message}") }
    }

    companion object {
        private const val TICK_MS = 30_000L
        private const val CHANNEL_ID = "phantom.symbiote"
        private const val NOTIFY_ID = 4711
        private const val FIND_ID = 4713
        private const val ASK_ID_BASE = 47_000
        private const val MAX_APS = 24
        private const val ACTION_FOUND = "local.phantom.companion.symbiote.FOUND"
        private const val ACTION_YES = "local.phantom.companion.symbiote.YES"
        private const val ACTION_NO = "local.phantom.companion.symbiote.NO"
        private const val EXTRA_ID = "command_id"
    }
}
