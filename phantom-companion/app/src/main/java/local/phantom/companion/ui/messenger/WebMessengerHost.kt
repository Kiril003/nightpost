package local.phantom.companion.ui.messenger

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.content.Intent
import android.net.Uri
import android.webkit.ValueCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import local.phantom.companion.core.design.theme.LocalPhantomFontScale
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.PhantomThemeSpec
import local.phantom.companion.core.design.theme.dayPhase
import kotlin.math.roundToInt
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import local.phantom.companion.R
import local.phantom.companion.core.design.R as DesignR
import local.phantom.companion.core.design.permission.PermissionReason
import local.phantom.companion.core.design.permission.rememberPermissionGate
import local.phantom.companion.feature.stream.permission.rememberLocationAccess
import local.phantom.companion.feature.stream.R as StreamR
import local.phantom.companion.PhantomApp
import java.io.ByteArrayInputStream
import java.io.IOException
import android.Manifest
import android.content.pm.PackageManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import androidx.core.content.ContextCompat

/**
 * Що робити з проханням веба про камеру.
 *
 * Винесено з `onPermissionRequest` окремою функцією рівно для того, щоб цю
 * політику можна було перевірити тестом: усередині `@Composable` вона
 * доводиться лише оком, а вада, яку вона лікує, оком і не видна.
 *
 * **Вада, заради якої це існує.** Раніше рішення було `ours && cameraOnly &&
 * held → grant, інакше deny`, і в коментарі стояло, що дозвіл «дає рідний
 * сканер». Але застосунок стартує одразу у веб-месенджер: людина зі свіжим
 * встановленням до рідного сканера не проходить НІКОЛИ. Тобто сканування QR —
 * єдиний спосіб додати людину, не набираючи рядок руками, — було зачинене за
 * побудовою для кожного, хто ще нічого не налаштував.
 *
 * На робочому апараті цього не побачити: там `CAMERA: granted=true` з давніх
 * прогонів, і QR працює. «Працює в мене» тут означало «зачинено в неї».
 */
internal enum class CameraAsk { GRANT, ASK, DENY }

internal fun cameraVerdict(
    ours: Boolean,
    cameraOnly: Boolean,
    held: Boolean,
    alreadyAsking: Boolean,
): CameraAsk = when {
    // Межі, які були й лишаються: чуже походження й будь-який ресурс, крім
    // відео, не обговорюються.
    !ours || !cameraOnly -> CameraAsk.DENY
    held -> CameraAsk.GRANT
    // Два вікна запиту одночасно неможливі: перший `PermissionRequest` завис
    // би назавжди, і сканер лишився б чорним без жодного пояснення.
    alreadyAsking -> CameraAsk.DENY
    else -> CameraAsk.ASK
}

/**
 * Що робити з проханням веба про аудіо/відео для дзвінків та сканера.
 * Підтримує RESOURCE_VIDEO_CAPTURE та RESOURCE_AUDIO_CAPTURE.
 */
internal enum class MediaAsk { GRANT, ASK, DENY }

internal fun mediaVerdict(
    ours: Boolean,
    validResources: Boolean,
    allHeld: Boolean,
    alreadyAsking: Boolean,
): MediaAsk = when {
    !ours || !validResources -> MediaAsk.DENY
    allHeld -> MediaAsk.GRANT
    alreadyAsking -> MediaAsk.DENY
    else -> MediaAsk.ASK
}


/** Порожнеча ще до мережі — див. [Served.Blocked]. */
private fun blocked(): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

/**
 * «Цього файла тут немає» — і саме 404, а не порожнеча.
 *
 * Веб розрізняє їх по-різному: 404 він читає як «блоб ще не приїхав» і показує
 * очікування, а порожню відповідь зі станом 200 — як підмінені байти, бо
 * відбиток не збігається. Мовчазна порожнеча звинуватила б мережу в підробці
 * там, де шматки просто ще в дорозі.
 */
private fun notHere(): WebResourceResponse =
    WebResourceResponse(
        "text/plain",
        "utf-8",
        404,
        "Not Found",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0)),
    )

/** «Приїхало й виявилось не тим» — стан, з якого вже не вийти чеканням. */
private fun gone(): WebResourceResponse =
    WebResourceResponse(
        "text/plain",
        "utf-8",
        410,
        "Gone",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0)),
    )

/** Сторінка бандла — разом із політикою, яку [WebMessengerAssets.CSP] пояснює. */
private fun page(mime: String, stream: java.io.InputStream): WebResourceResponse =
    WebResourceResponse(mime, "utf-8", 200, "OK", WebMessengerAssets.headersFor(mime), stream)

/**
 * Віддати посилання системі — або сказати, що не віддамо.
 *
 * Мовчазна гілка тут була б тим самим, що й раніше: палець натиснув, нічого
 * не сталось, і людина не знає чому.
 */
private fun openOutside(context: android.content.Context, url: String) {
    if (!WebMessengerAssets.opensOutside(url)) {
        android.widget.Toast
            .makeText(context, context.getString(R.string.msgr_web_link_refused), android.widget.Toast.LENGTH_SHORT)
            .show()
        return
    }
    val opened = runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess
    if (!opened) {
        android.widget.Toast
            .makeText(context, context.getString(R.string.msgr_web_link_no_browser), android.widget.Toast.LENGTH_SHORT)
            .show()
    }
}

/**
 * Байти вкладення з каталогу застосунку.
 *
 * Тип навмисне `application/octet-stream`, а не картинкою: те, що лежить на
 * диску, — шифротекст веба, і розшифровує його сам веб у себе. Оголосити це
 * картинкою означало б запросити `<img src>` просто на шифротекст і отримати
 * биту картинку без жодного пояснення.
 */
private fun facePhoto(container: local.phantom.companion.data.AppContainer, name: String): WebResourceResponse {
    val file = when {
        name == WebMessengerAssets.ME -> container.profileHerald.ownPhotoFile()
        name.startsWith(local.phantom.companion.data.ProfileHerald.OWN_FACE) -> container.profileHerald.photoFileOf(name)
        else -> container.peerPhotos.file(name)
    }
    val bytes = file?.let { runCatching { it.readBytes() }.getOrNull() } ?: return notHere()
    val kind = local.phantom.companion.core.net.peer.PeerProfile.imageKind(bytes) ?: return notHere()
    // Адреса несе ?v=rev, тож новий кадр — нова адреса, і кеш не бреше.
    return WebResourceResponse(
        kind.mime,
        null,
        200,
        "OK",
        mapOf("Cache-Control" to "private, max-age=31536000, immutable"),
        java.io.ByteArrayInputStream(bytes),
    )
}

/** Імʼя — хеш байтів, тож кеш навіки чесний: інші байти — інше імʼя. */
private fun bannerPhoto(container: local.phantom.companion.data.AppContainer, name: String): WebResourceResponse {
    val bytes = container.peerBanners.file(name)?.let { runCatching { it.readBytes() }.getOrNull() } ?: return notHere()
    val kind = local.phantom.companion.core.net.peer.PeerProfile.imageKind(bytes) ?: return notHere()
    return WebResourceResponse(
        kind.mime,
        null,
        200,
        "OK",
        mapOf("Cache-Control" to "private, max-age=31536000, immutable"),
        java.io.ByteArrayInputStream(bytes),
    )
}

private fun lookPhoto(container: local.phantom.companion.data.AppContainer, name: String): WebResourceResponse {
    val bytes = container.appearance.file(name)?.let { runCatching { it.readBytes() }.getOrNull() } ?: return notHere()
    // Нове фото — нове імʼя файла, тож старе з кешу не покажеться.
    return WebResourceResponse(
        "image/jpeg",
        null,
        200,
        "OK",
        mapOf("Cache-Control" to "private, max-age=31536000, immutable"),
        java.io.ByteArrayInputStream(bytes),
    )
}

private fun previewPhoto(container: local.phantom.companion.data.AppContainer, name: String): WebResourceResponse {
    val bytes = container.previewStore.readPreviewJpeg(name) ?: return notHere()
    return WebResourceResponse(
        "image/jpeg",
        null,
        200,
        "OK",
        mapOf("Cache-Control" to "no-store"),
        java.io.ByteArrayInputStream(bytes),
    )
}

private fun blobBytes(container: local.phantom.companion.data.AppContainer, rel: String): WebResourceResponse {
    val blobId = local.phantom.companion.core.net.peer.PeerFileRoute.blobOf(rel) ?: return notHere()
    val file = (container.peerFiles.blob(blobId) ?: container.peerFiles.takeIf { it.settle(blobId) }?.blob(blobId))
        // Зібралось і не збіглося за відбитком. 410 замість 404, бо це не
        // «ще їде»: воно вже приїхало і виявилось не тим. Веб на 404 малює
        // очікування, і людина чекала б вічно.
        ?: return if (container.peerFiles.isBroken(blobId)) gone() else notHere()
    // Між перевіркою й відкриттям файл могли стерти: «очистити розмову» і
    // «стерти лист» ходять іншим потоком. Виняток тут упав би на потоці
    // WebView, а не в наших руках.
    val stream = runCatching { java.io.FileInputStream(file) }.getOrNull() ?: return notHere()
    return WebResourceResponse(
        "application/octet-stream",
        null,
        200,
        "OK",
        mapOf("Cache-Control" to "no-store"),
        stream,
    )
}

/**
 * Веб-месенджер, що живе ВСЕРЕДИНІ застосунку.
 *
 * Рішення власника, 31.08: «якщо ти не можеш аналогічно вебу вигляд створити
 * то веб і роби». Спроба наблизити Compose до веба дала «уточнення, а не
 * перелом» — і власник це побачив на першому ж кадрі.
 *
 * **Це не вікно в ПК.** Попередній `WebMessengerScreen` показував вузол по
 * мережі й вимагав спареного компʼютера; тут бандл лежить в `assets/webmsgr`
 * і працює без вузла, без мережі, у літаку.
 *
 * Рішення «що віддати на цей шлях» живе в [WebMessengerAssets] — тут лише
 * переклад його в `WebResourceResponse`.
 */
@Composable
fun WebMessengerHost(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    /** Хтось поверх (дзвінок PHANTOM) забирає «Назад» собі; сторінка лишається на екрані. */
    takesBack: Boolean = true,
    padBottom: Boolean = true,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = remember { (context.applicationContext as PhantomApp).container }
    val fontScale = LocalPhantomFontScale.current

    // ── Камера для сканування QR ──────────────────────────────────────────
    //
    // Раніше тут стояло `if (… && held) grant else deny`, і в коментарі чесно
    // було написано, що дозвіл «дає рідний сканер». Але застосунок стартує
    // одразу у веб-месенджер, тож людина зі свіжим встановленням до рідного
    // сканера НЕ ПРОХОДИТЬ ніколи: `getUserMedia` відмовляє, і єдиний спосіб
    // додати людину без набирання рядка руками зачинений за побудовою.
    //
    // Вада не видна на робочому апараті: там `CAMERA: granted=true` з давніх
    // прогонів, і QR працює. Саме тому її знайшли читанням, а не спробою —
    // «працює в мене» тут означає «зачинено в неї».
    //
    // Тепер вікно запиту показуємо в МОМЕНТ ПОТРЕБИ: WebView власного не має,
    // тож запит веде застосунок, а відповідь повертається в той самий
    // `PermissionRequest`. Відмовила — deny, і веб чесно пише, що камери
    // немає; дозволила — grant, і сканер відкривається тут же.
    val mediaAsking = remember { mutableStateOf(false) }
    val cameraGate = rememberPermissionGate(
        permissions = listOf(Manifest.permission.CAMERA),
        reason = PermissionReason(DesignR.drawable.lc_camera, StreamR.string.perm_camera_title, StreamR.string.perm_camera_body),
    )
    val micGate = rememberPermissionGate(
        permissions = listOf(Manifest.permission.RECORD_AUDIO),
        reason = PermissionReason(DesignR.drawable.lc_mic, StreamR.string.mic_look_title, StreamR.string.mic_look_body),
    )
    val callGate = rememberPermissionGate(
        permissions = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
        reason = PermissionReason(DesignR.drawable.lc_phone, StreamR.string.perm_call_title, StreamR.string.perm_call_body),
    )
    // «Моє місце» у розмові: без відповіді тут геолокація вебу висіла б вічно.
    val placeAccess = rememberLocationAccess(
        PermissionReason(
            DesignR.drawable.lc_locate,
            StreamR.string.perm_location_share_title,
            StreamR.string.perm_location_share_body,
        ),
    )

    // Вибір файлів, фото та документів: WebView сам діалогу не показує,
    // тож на <input type="file"> Android кличе onShowFileChooser, а застосунок
    // відкриває камеру, Photo Picker чи вибір документів (`WebFileChooser`).
    // Результат повертаємо у filePathCallback стабільними копіями.
    val chooser = remember { java.util.concurrent.atomic.AtomicReference<WebFileChooserSession?>(null) }
    val pickScope = androidx.compose.runtime.rememberCoroutineScope()
    val deliverPicked: (Array<Uri>) -> Unit = { uris ->
        val callback = chooser.get()?.takePicked(uris)
        if (callback != null) {
            pickScope.launch {
                val copies = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    WebPickedFiles.copyAll(context, uris)
                }
                callback.onReceiveValue(copies)
            }
        }
    }
    val mediaOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { deliverPicked(listOfNotNull(it).toTypedArray()) }
    val mediaMany = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { deliverPicked(it.toTypedArray()) }
    val docOne = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { deliverPicked(listOfNotNull(it).toTypedArray()) }
    val docMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { deliverPicked(it.toTypedArray()) }
    val photoShot = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { chooser.get()?.onCaptured(it) }
    val videoShot = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { chooser.get()?.onCaptured(it) }
    if (chooser.get() == null) {
        chooser.set(WebFileChooserSession(
            askCamera = { answer -> cameraGate.request(answer) },
            captureTarget = { video -> runCatching { WebFileChooser.captureTarget(context, video) }.getOrNull() },
            takePhoto = { photoShot.launch(it) },
            takeVideo = { videoShot.launch(it) },
            pickMedia = { request, multiple -> if (multiple) mediaMany.launch(request) else mediaOne.launch(request) },
            openDocuments = { mimes, multiple -> if (multiple) docMany.launch(mimes) else docOne.launch(mimes) },
        ))
    }

    // Тема: рідний бік — єдине джерело. Жива палітра йде за сонцем, тож
    // prefers-color-scheme у вебі показав би не те, що навколо WebView.
    // Пишемо в контейнер (звідти читає PhantomBridge.getTheme/getThemePhase)
    // ДО loadUrl нижче: бандл питає міст ще до першого кадру, і без цього
    // перший кадр мав би фазу з минулого відкриття.
    val webThemeSpec = LocalPhantomTheme.current
    val themePayload = remember(webThemeSpec) { webThemePayload(webThemeSpec) }
    val themePhase = remember(webThemeSpec) { webThemePhase(webThemeSpec) }
    val sunLook = remember(webThemeSpec) { webSunLook(webThemeSpec) }
    container.webTheme.value = themePayload
    container.webThemePhase.value = themePhase
    container.webSunLook.value = sunLook

    val canvas = local.phantom.companion.core.design.tokens.LocalPhantomColors.current.canvas.toArgb()

    // Збірка 16: WebView міг уже стартувати з Bootstrap (WebMessengerPrewarm) —
    // тоді бандл вантажився, поки компонувалась домівка. Інакше — тут.
    var web by remember {
        mutableStateOf(
            WebMessengerPrewarm.take(context)
                ?: createMessengerWeb(context, container, canvas, (fontScale * 100).roundToInt()),
        )
    }
    val webView = web.view
    // Android вбив рендерер (памʼять): мертвий WebView геть, новий на його місце.
    // Розмову, що йшла, нова сторінка підніме сама (POST /call/ready → resume).
    LaunchedEffect(web) {
        web.gone.first { it }
        val dead = web.view
        (dead.parent as? ViewGroup)?.removeView(dead)
        dead.destroy()
        web = createMessengerWeb(context, container, canvas, (fontScale * 100).roundToInt())
    }
    // Двері, що живуть у композиції (системні дозволи, вибір файлів), —
    // чіпляються тут, а не при створенні: прогрітий WebView народжується до
    // того, як у домівки зʼявляються launcher-и.
    DisposableEffect(webView) {
        webView.apply {
            // Камера та мікрофон — лише нашому бандлу (сканування QR та дзвінки 1:1).
            // Межі ті самі: чужому походженню відмова, стороннім ресурсам відмова.
            // Коли дозволу ще немає, ми його ПИТАЄМО через діалог системи.
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    val ours = request.origin?.toString()?.trimEnd('/') == WebMessengerAssets.ORIGIN
                    val validResources = request.resources.isNotEmpty() && request.resources.all {
                        it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                    }
                    val needsCamera = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    val needsMic = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    val hasCamera = !needsCamera || ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                    val hasMic = !needsMic || ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    val allHeld = hasCamera && hasMic

                    val verdict = mediaVerdict(
                        ours = ours,
                        validResources = validResources,
                        allHeld = allHeld,
                        alreadyAsking = mediaAsking.value,
                    )
                    when (verdict) {
                        MediaAsk.GRANT -> request.grant(request.resources)
                        MediaAsk.DENY -> request.deny()
                        MediaAsk.ASK -> {
                            val gate = when {
                                needsCamera && needsMic -> callGate
                                needsCamera -> cameraGate
                                else -> micGate
                            }
                            mediaAsking.value = true
                            gate.request { ok ->
                                mediaAsking.value = false
                                if (ok) request.grant(request.resources) else request.deny()
                            }
                        }
                    }
                }

                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?,
                    callback: GeolocationPermissions.Callback?,
                ) {
                    if (callback == null) return
                    if (!geoOriginTrusted(origin)) {
                        callback.invoke(origin, false, false)
                        return
                    }
                    placeAccess.request { ok -> callback.invoke(origin, ok, false) }
                }

                override fun onShowFileChooser(
                    view: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?,
                ): Boolean = chooser.get()?.show(filePathCallback, WebFileChooser.plan(fileChooserParams)) ?: false
            }
        }
        onDispose { }
    }

    // Поштовх на зміну: фаза сонця перейшла межу — веб перефарбовується на
    // льоту, без перезапуску. Перший аргумент — day/night для старого бандла
    // (він звіряє `=== "day"` і другого аргументу не бачить), другий — одна з
    // чотирьох фаз для нового.
    LaunchedEffect(webView, themePayload, themePhase, sunLook) {
        container.webTheme.value = themePayload
        container.webThemePhase.value = themePhase
        container.webSunLook.value = sunLook
        webView.setBackgroundColor(canvas)
        webView.evaluateJavascript(
            "window.__phantomTheme && window.__phantomTheme(" +
                "${org.json.JSONObject.quote(themePayload)}, ${org.json.JSONObject.quote(themePhase)}, ${org.json.JSONObject.quote(sunLook)});",
            null,
        )
    }

    // Мова месенджера — мова Nightpost. Зміна мови перестворює активність, тож
    // хост перечитує вибір і штовхає його в ту саму сторінку; на завантаженні
    // веб питає сам (`PhantomBridge.getLanguage`).
    // Читання — виклик системної служби (LocaleManager), тож не на кожну перекомпозицію.
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val language = remember(context, configuration) { webLanguage(context) }
    LaunchedEffect(webView, language) {
        webView.evaluateJavascript(webLanguagePushJs(language), null)
    }

    // Профіль › Вигляд змінився — веб перечитує його (`PhantomBridge.getAppearance`).
    LaunchedEffect(webView) {
        container.appearance.state.drop(1).collect {
            webView.evaluateJavascript("window.__phantomAppearance && window.__phantomAppearance();", null)
        }
    }

    // Повзунок розміру шрифту живе в налаштуваннях застосунку, а WebView
    // створюється один раз — без цього веб лишався б на масштабі, який був
    // на мить відкриття.
    LaunchedEffect(webView, fontScale) {
        webView.settings.textZoom = (fontScale * 100).roundToInt()
    }

    // Вхідний лист або зміна стану — веб дочитує лише зачеплені розмови й
    // рядки, згладжено за 50 мс. Перечитати все — лише старому бандлу.
    LaunchedEffect(webView) {
        val smoother = WebMessengerDeltas.Smoother(this) { t ->
            webView.evaluateJavascript(meshDeltaJs(t.json()), null)
        }
        var prev = container.mesh.outbox.value
        container.mesh.outbox.collect { next ->
            if (next === prev) return@collect
            val was = prev
            prev = next
            val me = container.mesh.selfIdFlow.value
            smoother.offer(
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    WebMessengerDeltas.touched(was, next, me)
                },
            )
        }
    }

    // Дозволи SMS просить ЛИШЕ екран «Без інтернету» (після пояснення людині):
    // міст кладе прохання в IntentPermit, а системне вікно показуємо тут.
    // Відповідь людини веб дізнається тією самою подією, що й зміну журналу.
    val smsGate = rememberPermissionGate(
        permissions = local.phantom.companion.data.intent.IntentPermit.PERMISSIONS.toList(),
        reason = PermissionReason(DesignR.drawable.lc_phone, R.string.perm_sms_title, R.string.perm_sms_body),
    )
    LaunchedEffect(webView) {
        local.phantom.companion.data.intent.IntentPermit.requests.collect {
            smsGate.request {
                webView.evaluateJavascript("window.__phantomIntentsChanged && window.__phantomIntentsChanged();", null)
            }
        }
    }

    val calendarGate = rememberPermissionGate(
        permissions = listOf(local.phantom.companion.data.CalendarPermit.PERMISSION),
        reason = PermissionReason(DesignR.drawable.lc_hourglass, R.string.perm_calendar_title, R.string.perm_calendar_body),
    )
    LaunchedEffect(webView) {
        local.phantom.companion.data.CalendarPermit.requests.collect {
            calendarGate.request {
                webView.evaluateJavascript("window.__phantomDayChanged && window.__phantomDayChanged();", null)
            }
        }
    }

    // «Мої прохання» змінились (смуга, квитанція ПК, відповідь) — листа про це немає.
    LaunchedEffect(webView) {
        container.intents.client.journal.drop(1).collect {
            webView.evaluateJavascript("window.__phantomIntentsChanged && window.__phantomIntentsChanged();", null)
        }
    }

    // Мережа підтвердила переказ — листа про це немає, картку треба перечитати.
    LaunchedEffect(webView) {
        local.phantom.companion.core.solana.SolanaLayer.revision.drop(1).collect {
            webView.evaluateJavascript("window.__phantomMeshChanged && window.__phantomMeshChanged();", null)
        }
    }

    // Нагадування поставили чи зняли (помічник у розмові, лист, спрацювання) —
    // рядок «Сьогодні» перечитує список, не чекаючи, поки застосунок згорнуть.
    LaunchedEffect(webView) {
        WebMessengerReminders.changes(
            container.reminderStore.pendingFlow(50).map { rows -> rows.map { it.id to it.triggerAtMs } },
        ).collect { webView.evaluateJavascript(WebMessengerReminders.CHANGED_JS, null) }
    }

    // Звук розмови зняли в Налаштуваннях › Сповіщення — веб перечитує той самий перелік.
    LaunchedEffect(webView) {
        container.chatHush.state.drop(1).collect { webView.evaluateJavascript(WebMessengerHush.CHANGED_JS, null) }
    }

    // Розмова в гілці почалась чи згасла — листа про це немає, тож окремо.
    // Поновлення «я ще тут» кожні 30 с складу не міняють і список не будять.
    LaunchedEffect(webView) {
        var was: Set<Pair<String, Triple<String, Long, Set<String>>>>? = null
        container.callHub.live
            .map { board -> board.values.map { it.groupId to Triple(it.callId, it.startedAtMs, it.faces.keys) }.toSet() }
            .distinctUntilChanged()
            .collect { now ->
                val before = was
                was = now
                if (before == null) return@collect
                val t = WebMessengerDeltas.Touch()
                ((before - now) + (now - before)).forEach { t.convs += it.first }
                if (!t.isEmpty()) webView.evaluateJavascript(meshDeltaJs(t.json()), null)
            }
    }

    // Тап по сповіщенню PHANTOM: відкрити саме його розмову. Якщо бандл ще не
    // піднявся, він сам забере прохання через `/messenger/self/open`.
    LaunchedEffect(webView) {
        local.phantom.companion.self.PhantomVisits.openChat.collect { id ->
            if (id == null) return@collect
            webView.evaluateJavascript(
                "window.__phantomOpenChat ? window.__phantomOpenChat(${org.json.JSONObject.quote(id)}) : false",
            ) { opened ->
                if (opened == "true") local.phantom.companion.self.PhantomVisits.openChat.compareAndSet(id, null)
            }
        }
    }

    LaunchedEffect(webView) {
        WebNav.pending.collectLatest { ask ->
            if (ask == null) return@collectLatest
            if (WebNav.deliver(ask, run = { script ->
                    kotlinx.coroutines.suspendCancellableCoroutine<String?> { c ->
                        webView.evaluateJavascript(script) { c.resume(it) }
                    }
                }) == WebNav.Outcome.MISSING
            ) {
                // Незданий запит не має спливти на наступному WebView (поворот, тема) за кілька хвилин.
                WebNav.pending.compareAndSet(ask, null)
            }
        }
    }

    LaunchedEffect(webView) {
        kotlinx.coroutines.flow.combine(container.profilesMirror.pendingActive, container.profilesMirror.retry) { id, _ -> id }.collectLatest { id ->
            if (id == null) return@collectLatest
            container.profilesMirror.deliverPendingActive(id, run = { script ->
                kotlinx.coroutines.suspendCancellableCoroutine<String?> { c ->
                    webView.evaluateJavascript(script) { c.resume(it) }
                }
            })
        }
    }

    // Чернетка зустрічі від помічника: веб відкриває аркуш, надсилає людина.
    // Сторінка може ще підніматись — тоді `__phantomOpenMeetupDraft` ще немає, і
    // хост пробує знову, а не вважає прохання виконаним. З черги знімається, коли
    // веб відповів: «відкрив» чи «відмовив» (повтор лише повторив би його сповіщення).
    LaunchedEffect(webView) {
        WebMessengerMeetupDraft.pending.collectLatest { ask ->
            if (ask == null) return@collectLatest
            WebMessengerMeetupDraft.deliver(ask, run = { script ->
                kotlinx.coroutines.suspendCancellableCoroutine<String?> { c ->
                    webView.evaluateJavascript(script) { c.resume(it) }
                }
            })
        }
    }

    // «Де я» йде повз журнал, тож `outbox` вище про нього мовчить.
    LaunchedEffect(webView) {
        container.peopleWhere.changes.collect {
            webView.evaluateJavascript("window.__phantomWhereChanged && window.__phantomWhereChanged();", null)
        }
    }

    LaunchedEffect(webView) {
        WebMessengerAssistantLive.flushes(container.agencyManager.liveFeed).collect { f ->
            webView.evaluateJavascript(
                "window.__phantomAssistantLive && window.__phantomAssistantLive(${WebMessengerAssistantLive.json(f)});",
                null,
            )
            WebMessengerAssistantLive.shown.value = f
        }
    }

    LaunchedEffect(webView) {
        val typing = webTyping(container)
        container.typingIn.collect { m ->
            val seen = typing.seen(m) ?: return@collect
            webView.evaluateJavascript(
                "window.__phantomTyping && window.__phantomTyping(" +
                    "${org.json.JSONObject.quote(seen.chatId)}, ${org.json.JSONObject.quote(seen.name)}, ${seen.on});",
                null,
            )
        }
    }

    // Дзвінок: тіло, що приїхало по сітці, лягає в рушій, який його й чекає.
    //
    // Імʼя не вигадане — його оголошує САМ бандл (`window.__phantomCallSignal =
    // p => callEngine.onSignal(p)`), і рушій за ним читає `kind` та `call_id`.
    // Тому тіло віддається слово в слово: телефон SDP не розбирає.
    //
    // Підʼєднання, а не збирання потоку: WebView живе, лише поки месенджер на
    // екрані, і те, що приїхало до його появи, тримає вузол
    // ([local.phantom.companion.data.PeerCallHub.attachWeb]) — інакше
    // пропозиція, що приїхала за мить до відкриття, гинула б у тиші.
    DisposableEffect(webView) {
        container.callHub.attachWeb { payload ->
            webView.post {
                val arg = org.json.JSONObject.quote(payload)
                webView.evaluateJavascript(
                    "window.${local.phantom.companion.data.PeerCallHub.WEB_SINK} && " +
                        "window.${local.phantom.companion.data.PeerCallHub.WEB_SINK}" +
                        "(JSON.parse($arg));",
                    null,
                )
            }
        }
        onDispose { container.callHub.detachWeb() }
    }

    // Бандл оголошує приймача вже після завантаження; до того кадри тримає вузол.
    LaunchedEffect(webView) {
        awaitCallSink(
            declared = { webView.callSinkDeclared() },
            onReady = { container.callHub.webReady() },
            giveUpMs = CALL_SINK_GIVE_UP_MS,
        )
    }

    // Передача розпізнаного мовлення у веб-композер (Vosk + Moonshine)
    LaunchedEffect(webView) {
        container.voiceManager.partialResults.collect { partial ->
            if (local.phantom.companion.core.voice.turn.VoiceFloor.heldByOther(null)) return@collect
            val escaped = org.json.JSONObject.quote(partial)
            webView.evaluateJavascript("window.__phantomVoiceText && window.__phantomVoiceText($escaped, false);", null)
        }
    }
    LaunchedEffect(webView) {
        container.voiceManager.sttResults.collect { finalResult ->
            if (local.phantom.companion.core.voice.turn.VoiceFloor.heldByOther(null)) return@collect
            val escaped = org.json.JSONObject.quote(finalResult)
            webView.evaluateJavascript("window.__phantomVoiceText && window.__phantomVoiceText($escaped, true);", null)
        }
    }

    // Амплітуда мікрофона для орба. Сира йде з кожним буфером аудіо, і кожен
    // evaluateJavascript — це перехід у рендерер і кадр сторінки.
    LaunchedEffect(webView) {
        WebMessengerPort.audioFrames(container.voiceManager.audioLevel).collect { level ->
            webView.evaluateJavascript("window.__phantomAudioLevel && window.__phantomAudioLevel($level);", null)
        }
    }

    // Повернення в месенджер — сигнал транспорту: черга й сховок пробують зараз,
    // а не на наступному такті чи за годину.
    LaunchedEffect(webView) { container.mesh.onForeground() }

    // Один шлях назад для жеста, апаратної кнопки й ось цього намальованого
    // кола: інакше коло виходило б із месенджера НАПРЯМУ, поки жест і кнопка
    // вже вміли спершу повернути в список (SPA сама кладе запис в історію на
    // кожен відкритий шар — canGoBack тому й каже правду).
    //
    // Спершу питаємо саму сторінку (`window.__phantomBack`): розмова, відкрита
    // тапом по сповіщенню, кладе запис в історію без жесту в сторінці, і
    // Chromium такий запис для «Назад» пропускає — `canGoBack()` казав false, і
    // BACK викидав із застосунку замість повернути в список (A52, 02.10).
    // `canGoBack()` лишається запасом для бандла без цього входу.
    val goBack: () -> Unit = {
        webView.evaluateJavascript(WEB_BACK_JS) { handled ->
            if (handled != "true") {
                if (webView.canGoBack()) webView.goBack() else onBack()
            }
        }
    }

    BackHandler(enabled = active && takesBack, onBack = goBack)

    // Hidden behind the map or the profile: no frames, no JS timers, no surface.
    // Дзвінок — виняток: onPause() глушить звук сторінки, а рушій розмови живе тут.
    val callOpen by container.callHub.lineOpen.collectAsStateWithLifecycle()
    LaunchedEffect(webView, callOpen) {
        local.phantom.companion.service.CallKeep.renderer(local.phantom.companion.service.CallKeep.WebRenderer(webView), callOpen)
    }
    LaunchedEffect(webView, active, callOpen) {
        if (active || callOpen) {
            webView.visibility = android.view.View.VISIBLE
            webView.onResume()
        } else {
            webView.onPause()
            webView.visibility = android.view.View.GONE
        }
    }

    // «Взяти» з плашки поверх замка: Talking може прийти раніше, ніж екран став перед людиною, а з фону
    // служба розмови не стартує. Щойно застосунок на передньому плані — службу заводять.
    val callOwner = LocalLifecycleOwner.current
    LaunchedEffect(callOpen) {
        if (!callOpen) return@LaunchedEffect
        callOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            local.phantom.companion.service.PeerCallNotifications.holdCall(context.applicationContext)
        }
    }

    // Застосунок пішов у фон (Додому, інший застосунок, замок) — сторінка
    // цього не бачить: WebView лишає visibilityState = visible. Кажемо самі,
    // інакше лист у відкриту розмову ставав «прочитаним» на робочому столі (DL-2).
    val seenOwner = LocalLifecycleOwner.current
    DisposableEffect(webView, seenOwner) {
        val watch = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> webView.evaluateJavascript(webForegroundPushJs(true), null)
                Lifecycle.Event.ON_STOP -> webView.evaluateJavascript(webForegroundPushJs(false), null)
                else -> Unit
            }
        }
        seenOwner.lifecycle.addObserver(watch)
        onDispose { seenOwner.lifecycle.removeObserver(watch) }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Месенджер зник з екрана — жодна розмова вже не «читається»,
            // і шторка знову має право сказати про лист.
            container.events.visiblePeerChat.value = null
            container.events.visiblePeerGroup.value = null
            container.events.webModalOpen.value = false
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }

    // Веб малювався для десктопа: без цього він лізе під годинник згори і
    // під навігаційну смугу знизу — власник побачив це першим.
    //
    // systemBars ЗЛИТО з ime, не додано окремим паддінгом: клавіатура закриває
    // WebView знизу так само, як навігаційну смугу, і без цього обʼєднання
    // сама сторінка ніколи не дізнається, що їй лишилось менше висоти —
    // композер під клавіатурою зникав разом з рештою стрічки.
    val insets = if (padBottom) {
        WindowInsets.systemBars.union(WindowInsets.ime)
    } else {
        WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    }
    // Рідний ескіз домівки поверх, доки веб не змалював свій перший кадр
    // (onPageCommitVisible): той самий ескіз, що й `#boot` в index.html, тож
    // передача непомітна. Знімаємо через два кадри після сигналу — WebView
    // мусить устигнути вивести змальоване, інакше між ескізами блимне голе
    // полотно.
    val painted by web.painted.collectAsStateWithLifecycle()
    var skeleton by remember(web) { mutableStateOf(!web.painted.value) }
    LaunchedEffect(painted) {
        if (painted && skeleton) {
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.withFrameNanos { }
            skeleton = false
            local.phantom.companion.StartMarks.mark("web:skeletonOff")
        }
    }
    Box(modifier = modifier.fillMaxSize().windowInsetsPadding(insets)) {
        androidx.compose.runtime.key(webView) { AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize()) }
        if (skeleton) local.phantom.companion.core.design.component.MessengerSkeleton()
    }
}

/**
 * Палітра рідного боку в рядок для веба — рівно `day` або `night`.
 *
 * Форму диктує бандл: він звіряє `=== "day"` і все інше зводить до ночі. Тому
 * кольори сюди не кладуться — не з ощадливості, а тому, що читача в них немає
 * і не було б видно, якби вони розійшлися з рідними.
 */
/** Закрити верхній шар сторінки; "true" — закрито, інше — шарів немає (див. usePhoneBackStack). */
internal const val WEB_BACK_JS =
    "(function(){try{return !!(window.__phantomBack && window.__phantomBack());}catch(e){return false;}})()"

/** Новий бандл бере дельту; старий знає лише «перечитай усе». */
internal fun meshDeltaJs(json: String): String =
    "window.__phantomMeshDelta ? window.__phantomMeshDelta($json) : " +
        "(window.__phantomMeshChanged && window.__phantomMeshChanged());"

internal fun webThemePayload(spec: PhantomThemeSpec): String = if (spec.isDark) "night" else "day"

/**
 * Фаза доби для нового бандла — `night|dawn|morning|day|golden|dusk`. Ручні
 * режими дають дві (Light → day, Dark → night), Living — шість. Полярність фази
 * завжди збігається з [webThemePayload].
 */
internal fun webThemePhase(spec: PhantomThemeSpec): String = spec.dayPhase().web

/** Кадр сонця для вебу — та сама точка, що в оболонці; ручна тема й полюси — порожньо (плоский D). */
internal fun webSunLook(spec: PhantomThemeSpec): String = spec.sun?.web.orEmpty()

private const val CALL_SINK_GIVE_UP_MS = 120_000L

private suspend fun WebView.callSinkDeclared(): Boolean = suspendCancellableCoroutine { done ->
    evaluateJavascript("typeof window.${local.phantom.companion.data.PeerCallHub.WEB_SINK} === 'function'") {
        if (done.isActive) done.resume(it == "true")
    }
}

/**
 * WebView месенджера і прапорець «веб уже змалював свій перший кадр»
 * (`onPageCommitVisible`): доти поверх лежить рідний ескіз домівки.
 */
internal class MessengerWeb(
    val view: WebView,
    val painted: kotlinx.coroutines.flow.StateFlow<Boolean>,
    val gone: kotlinx.coroutines.flow.StateFlow<Boolean>,
)

internal fun hideMessengerScrollbars(view: WebView) {
    view.isVerticalScrollBarEnabled = false
    view.isHorizontalScrollBarEnabled = false
}

/**
 * Створює WebView месенджера й одразу починає вантажити бандл — без Compose,
 * щоб це можна було зробити і з Bootstrap (прогрів, [WebMessengerPrewarm]), і
 * з самого [WebMessengerHost]. Тема (`container.webTheme`) має бути записана
 * ДО цього виклику: бандл питає міст ще до першого кадру.
 */
@SuppressLint("SetJavaScriptEnabled")
internal fun createMessengerWeb(
    context: android.content.Context,
    container: local.phantom.companion.data.AppContainer,
    canvas: Int,
    zoom: Int,
): MessengerWeb {
    local.phantom.companion.StartMarks.mark("web:create")
    // Лише налагоджувальна збірка: дає підключити DevTools по кабелю й
    // викликати міст напряму — інакше він перевіряється тільки оком.
    if (local.phantom.companion.BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
    val painted = kotlinx.coroutines.flow.MutableStateFlow(false)
    val gone = kotlinx.coroutines.flow.MutableStateFlow(false)
    val view = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            // Полотно теми, а не прозорість: під прозорим WebView до першого
            // кадру вебу проступало кремове полотно Compose (білий спалах на
            // холодному старті). Поверх, доки веб не змалювався, лежить
            // рідний ескіз домівки (MessengerSkeleton) — див. WebMessengerHost.
            setBackgroundColor(canvas)
            overScrollMode = View.OVER_SCROLL_NEVER
            hideMessengerScrollbars(this)
            WebHaptics.attach(this)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                // Дороги до диска нема: бандл приходить лише через перехоплювач
                // нижче, тож file:// не потрібен нікому — і тому закритий.
                allowFileAccess = false
                allowContentAccess = true
                databaseEnabled = true
                // Голос співрозмовника вмикається не з дотику, а коли доїхав (A52: play() → NotAllowedError).
                mediaPlaybackRequiresUserGesture = false
                // Без цього рядка WebView множить КОЖЕН текст на системний
                // масштаб шрифту, якого застосунок не питав і не показує.
                // Виміряно 14.09 на 1080×2400: system font_scale 1.30 підняв
                // підпис 15px → 19.5px, а коробки лишились ті самі, і при
                // «великому» розмірі екрана (320 CSS px) назву розмови різало
                // 186→109px, рядок задачі 251→170px. Рідні екрани вже йдуть за
                // повзунком застосунку — веб іде за ним самим.
                textZoom = zoom
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val served = WebMessengerAssets.resolve(request.url.toString()) { name ->
                        try {
                            context.assets.open("${WebMessengerAssets.ASSET_ROOT}/$name")
                        } catch (_: IOException) {
                            null
                        }
                    }
                    return when (served) {
                        is Served.Blocked -> blocked()
                        is Served.Attachment -> blobBytes(container, served.rel)
                        is Served.Face -> facePhoto(container, served.name)
                        is Served.Look -> lookPhoto(container, served.name)
                        is Served.Banner -> bannerPhoto(container, served.name)
                        is Served.Preview -> previewPhoto(container, served.name)
                        is Served.Asset -> page(served.mime, served.stream)
                        is Served.Fallback -> page(served.mime, served.stream)
                    }
                }

                /**
                 * Зовнішнє посилання не відкриваємо всередині месенджера.
                 *
                 * Доти тут стояло саме лише `true` — «не вантаж». Ні браузера,
                 * ні відмови: палець по посиланню не робив рівно нічого.
                 */
                override fun onPageCommitVisible(view: WebView, url: String) {
                    local.phantom.companion.StartMarks.mark("web:commitVisible")
                    painted.value = true
                }

                override fun onPageFinished(view: WebView, url: String) {
                    local.phantom.companion.StartMarks.mark("web:pageFinished")
                    painted.value = true
                }

                /** Без `true` Android за вбитим рендерером вбиває і весь застосунок, а з ним розмову. */
                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                    android.util.Log.w("PhantomWeb", "renderer gone, crash=${detail.didCrash()}")
                    gone.value = true
                    return true
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val url = request.url.toString()
                    if (url.startsWith(WebMessengerAssets.ORIGIN + WebMessengerAssets.MOUNT)) return false
                    openOutside(context, url)
                    return true
                }
            }
            // Рукостискання WebSocket не проходить через перехоплювач вище, тож
            // глушимо його ДО скриптів сторінки. На телефоні жоден екран із
            // сокета не живиться: розмови, листи й люди приходять мостом нижче.
            // CSP додає до цього чужі хости (див. [WebMessengerAssets.CSP]).
            //
            // Доти невдала перевірка фічі просто пропускала заглушку і йшла
            // вантажити сторінку — і саме там, де замка нема, у бандлі лишались
            // живими 4 `new WebSocket(` і три STUN Google (перелік ICE закритий
            // умовою `window.PhantomBridge`, а міст ставився тим самим блоком).
            // Без заглушки сторінка не відкривається взагалі.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(
                    this,
                    WebMessengerAssets.NO_WEBSOCKET,
                    setOf(WebMessengerAssets.ORIGIN),
                )
                // Мова Nightpost (перший екран / Профіль › Мова) — до бандла,
                // щоб перший же кадр месенджера був тією мовою, а не своєю.
                WebViewCompat.addDocumentStartJavaScript(
                    this,
                    WebMessengerAssets.languageScript(webLanguage(context)),
                    setOf(WebMessengerAssets.ORIGIN),
                )
                if (local.phantom.companion.BuildConfig.DEBUG) {
                    WebViewCompat.addDocumentStartJavaScript(
                        this,
                        local.phantom.companion.StartMarks.PAINT_PROBE,
                        setOf(WebMessengerAssets.ORIGIN),
                    )
                }
                // Міст у меш: веб кличе /messenger/*, відповідає телефон.
                // Старий синхронний `call` лишається запасом; новий бандл спершу
                // шукає асинхронний порт (MESSENGER_V2 §2.2).
                val bridge = WebMessengerBridge(container)
                addJavascriptInterface(bridge, "PhantomBridge")
                WebMessengerPort(container.scope, { post(it) }, bridge::dispatch, bridge::takeBytes)
                    .install(this, WebMessengerAssets.ORIGIN)
                loadUrl(WebMessengerAssets.ENTRY)
                local.phantom.companion.StartMarks.mark("web:loadUrl")
            } else {
                loadDataWithBaseURL(
                    null,
                    WebMessengerAssets.UNGUARDED_PAGE,
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        }
    return MessengerWeb(view, painted, gone)
}

internal fun geoOriginTrusted(origin: String?): Boolean = origin?.trimEnd('/') == WebMessengerAssets.ORIGIN
