package local.phantom.companion.ui.messenger

import local.phantom.companion.core.net.peer.PeerFileRoute
import java.io.InputStream

/**
 * Що перехоплювач віддасть на конкретний шлях.
 *
 * Рішення винесене з `WebViewClient`, бо всередині нього його не можна ані
 * побачити, ані запитати без апарата: 04.09 бандл цілий день посилався на
 * `/assets/index-*.js` від кореня, перехоплювач віддавав порожній `text/plain`,
 * і жоден зелений сторож цього не бачив — усі дивились на файли бандла й на
 * ФОРМУ рядка в index.html, а не на те, що міст справді віддасть.
 */
sealed interface Served {

    /**
     * ЗАМОК. Бандл несе згадки 29 зовнішніх походжень — хмарні моделі, чужі
     * аватарки, CDN і публічний MQTT-брокер, який у вебі возить відкриті
     * повідомлення. Жодне з них не сміє ожити на телефоні власника.
     *
     * Тому дозвіл влаштований як БІЛИЙ перелік з одного пункту: наш власний
     * origin. Усе інше не «блокується правилом», а **не існує**. Вимикати
     * нічого не треба: те, чого немає в переліку, не поїде, навіть якщо завтра
     * в бандлі зʼявиться нова адреса.
     */
    data object Blocked : Served

    /** Вкладення: байти лежать не в бандлі, а в каталозі застосунку. */
    data class Attachment(val rel: String) : Served

    /** Фото людини з профілю (або своє — [WebMessengerAssets.ME]). */
    data class Face(val name: String) : Served

    /** Фото банера людини за хешем ([local.phantom.companion.data.PeerBannerStore]). */
    data class Banner(val name: String) : Served

    /** Фото тла з Профілю › Вигляд ([local.phantom.companion.data.appearance.AppearanceStore]). */
    data class Look(val name: String) : Served

    data class Preview(val name: String) : Served

    data class Asset(val mime: String, val path: String, val stream: InputStream) : Served

    /** Невідоме імʼя — це маршрут усередині односторінкового веба, не втрата. */
    data class Fallback(val mime: String, val stream: InputStream) : Served
}

/**
 * **Чому власний перехоплювач, а не `WebViewAssetLoader`.** Штатний
 * `AssetsPathHandler` шукає від кореня `assets/`, а наш бандл лежить у
 * підкаталозі — шлях `/webmsgr/assets/x.js` він звів би до `assets/assets/x.js`.
 */
object WebMessengerAssets {

    const val ORIGIN = "https://phantom.local"
    const val MOUNT = "/webmsgr/"
    const val ASSET_ROOT = "webmsgr"
    const val INDEX = "index.html"
    const val ENTRY = ORIGIN + MOUNT + INDEX
    const val FACES = "_faces/"
    const val ME = "me"
    const val BANNERS = "_banners/"
    const val LOOK = "_look/"
    const val PREVIEW = "preview/"
    private val PREVIEW_JPG = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg$")

    /**
     * Друга половина огорожі: [Served] стереже лише те, що ПРОХОДИТЬ через
     * `shouldInterceptRequest`, а рукостискання WebSocket повз нього не йде
     * взагалі. Тому чужі хости мусить відмовити сама сторінка.
     *
     * Виміряно 04.09 у Chromium на цій політиці: `ws://localhost:8731/ws`
     * (свій хост) — readyState 0, дозволено; `ws://127.0.0.1:8731/ws` — той
     * самий сервер іншим іменем хоста — і `ws://broker.example.org:1883`
     * обидва readyState 3 з «violates ... connect-src 'self'».
     *
     * Звідси головне, чого не видно з рядка: `'self'` за специфікацією
     * покриває і `wss://` на ТОЙ САМИЙ хост, тож `wss://phantom.local/ws`
     * ця політика НЕ спиняє — його глушить [NO_WEBSOCKET]. Робота політики
     * тут інша: публічний MQTT-брокер і будь-який інший чужий хост не
     * піднімуть сокета, навіть коли завтра в бандл їх упишуть знову.
     *
     * `script-src`/`style-src` тут свідомо нема: у бандлі виміряно
     * `new Function` (MessengerLayout-*.js) і вбудований `<style>` в
     * index.html, тож `default-src 'self'` довелося б одразу продірявити
     * `'unsafe-eval'` і `'unsafe-inline'` — а все, що вони стерегли б,
     * і так не проходить повз перехоплювач.
     */
    const val CSP = "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data:; font-src 'self'; media-src 'self' blob: data:; connect-src 'self' blob: data:; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

    /**
     * Заглушка WebSocket, яку [WebMessengerHost] виконує ДО скриптів сторінки.
     *
     * Бандл копіюється в APK руками з іншого дерева, тож прапорець збірки веба
     * тут не гарантує нічого: ця сторона мусить триматись сама.
     *
     * Чому заглушка, а не виняток. Веб ловить конструктор у `try` і на кидок
     * САМ призначає переспробу (`websocket.ts:_open` → `_scheduleReconnect`) —
     * кидок лишив би той самий вічний цикл 1с→30с, який ми й прийшли зняти.
     * Заглушка ж навіки лишається в CONNECTING: подій не буде, отже не буде
     * ані `onclose`, ані переспроби, а перевірка «вже підʼєднуюсь» відсікає
     * повторні виклики. `console.warn` — щоб це було видно, а не мовчало.
     */
    const val NO_WEBSOCKET = "(function(){function S(u){this.url=String(u);this.readyState=0;" +
        "this.bufferedAmount=0;this.protocol='';this.extensions='';this.binaryType='blob';" +
        "this.onopen=this.onmessage=this.onclose=this.onerror=null;" +
        "console.warn('[phantom] WebSocket is off in the phone messenger: '+this.url)}" +
        "S.prototype.send=function(){};S.prototype.close=function(){};" +
        "S.prototype.addEventListener=function(){};S.prototype.removeEventListener=function(){};" +
        "S.prototype.dispatchEvent=function(){return false};" +
        "S.CONNECTING=S.prototype.CONNECTING=0;S.OPEN=S.prototype.OPEN=1;" +
        "S.CLOSING=S.prototype.CLOSING=2;S.CLOSED=S.prototype.CLOSED=3;" +
        "try{Object.defineProperty(window,'WebSocket',{value:S,writable:false,configurable:false})}" +
        "catch(x){window.WebSocket=S}})();"

    /**
     * Мова Nightpost для веб-месенджера — до скриптів сторінки.
     *
     * Бандл бере мову ще до першого кадру з `localStorage["phantom_language"]`
     * (`settingsBootstrap.applyBootstrapLanguage` → `<html lang>` і рядки
     * `useTranslation`). Рідний бік кладе туди вибір першого екрана або
     * Профілю › Мова, тож месенджер говорить тією ж мовою, що й решта
     * застосунку, а без цього рядка лишався б на своїй українській.
     * Тег — із закритого переліку [WEB_LOCALES], а не з рядка людини.
     */
    fun languageScript(tag: String): String {
        require(tag in WEB_LOCALES) { tag }
        return "(function(){try{localStorage.setItem('phantom_language','$tag')}catch(x){}" +
            "document.documentElement.setAttribute('lang','$tag')})();"
    }

    /**
     * Що показати замість месенджера, коли заглушку поставити нічим.
     *
     * `addDocumentStartJavaScript` є не в кожному WebView. Доти невдача цієї
     * перевірки просто пропускала заглушку і йшла вантажити сторінку: у бандлі
     * лежать 4 виклики `new WebSocket(`, а `connect-src 'self'` за
     * специфікацією НЕ спиняє свій же `wss://phantom.local/ws`. Разом із
     * заглушкою не ставився й міст, тож `window.PhantomBridge` ставав `undefined`
     * — і перелік ICE розгортався у три STUN-сервери Google. Тобто рівно на
     * тих апаратах, де захисту нема, вмикались обидві дороги назовні.
     *
     * Тому сторінка не відкривається взагалі. Скриптів тут немає навмисне:
     * це остання сторінка, якій можна вірити.
     */
    const val UNGUARDED_PAGE =
        "<!doctype html><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<style>html{background:#0C110D;color:#F8FAF8;font:14px/1.5 sans-serif}" +
            "body{margin:0;display:flex;min-height:100vh;align-items:center;padding:24px}" +
            "p{margin:0;max-width:34em}b{color:#F4AF25;display:block;margin-bottom:8px}</style>" +
            "<p><b>Месенджер не відкрито.</b>Цей переглядач сторінок не дає поставити замок " +
            "перед скриптами сторінки, а без нього сторінка може піти в мережу сама. " +
            "Оновіть «Android System WebView» у застосунках телефона.</p>"

    /**
     * Зовнішнє посилання: чи можна віддати його системі.
     *
     * Текст листа пише інша людина, а `Intent.ACTION_VIEW` розбирає далеко не
     * саму лише адресу: `intent://…#Intent;…end` — це вибір компонента чужими
     * руками. Тож перелік білий, а не чорний.
     *
     * `phantom://` тут нема навмисне, хоч його й ловить сам застосунок: доти
     * посилання не робило НІЧОГО, і вписати свою схему означало б дати чужому
     * листу одним дотиком завести наш власний обробник запрошень із чужими
     * параметрами. Полагодити мертвий дотик — не привід відчинити нові двері.
     */
    fun opensOutside(url: String): Boolean {
        val scheme = url.substringBefore(':', "").lowercase()
        return scheme == "http" || scheme == "https"
    }

    /** Заголовки відповіді. CSP має сенс лише на самому документі. */
    /**
     * ЧОМУ ТУТ ТЕПЕР Є `script-src`, хоч раніше свідомо не було.
     *
     * Стару політику писали, коли в бандлі жив `new Function` — і сувора
     * заборона зламала б застосунок. 05.09 виміряно, що це було не «поки що
     * не можна», а діра: на ПК виконання чужого коду блокує Tauri
     * (`script-src 'self'`), у вебі — бекенд, а на ТЕЛЕФОНІ не блокувало
     * ніщо, бо жодного заголовка тут не віддавалось. Тобто єдина поверхня,
     * де співрозмовник міг надіслати картку з кодом і отримати виконання
     * з доступом до мосту, до особи й до файлів, — це був телефон.
     *
     * Що ця політика ламає: рівно один шлях — виконання коду з рядка. Він
     * УЖЕ мертвий на ПК і в вебі, тож телефон просто зрівнюється з ними.
     *
     * Перевірено на самому бандлі перед увімкненням: у `index.html` один
     * тег `<script>` із src (не вбудований), нуль inline-обробників, і один
     * `<style>` — тому `style-src` має `'unsafe-inline'`, а `script-src` не
     * має. `'unsafe-eval'` немає й не буде: саме він і є та діра.
     */
    fun headersFor(mime: String): Map<String, String> =
        if (mime == "text/html") mapOf("Content-Security-Policy" to CSP) else emptyMap()

    /** [open] дістає байти за іменем ВСЕРЕДИНІ бандла; null — немає такого. */
    fun resolve(url: String, open: (String) -> InputStream?): Served {
        if (!url.startsWith(ORIGIN + MOUNT)) return Served.Blocked

        val rel = url.removePrefix(ORIGIN + MOUNT).substringBefore('?').substringBefore('#')
        // Вихід за межі каталогу — не помилка шляху, а спроба прочитати чуже.
        if (rel.contains("..")) return Served.Blocked

        // ПЕРЕД пошуком у бандлі: запасний шлях нижче віддає index.html на
        // будь-яке невідоме імʼя, тож картинка прийшла б у веб як сторінка
        // HTML — без помилки, без журналу, просто «фото не показується».
        if (PeerFileRoute.isFileRequest(rel)) return Served.Attachment(rel)
        if (rel.startsWith(FACES)) return Served.Face(rel.removePrefix(FACES))
        if (rel.startsWith(LOOK)) return Served.Look(rel.removePrefix(LOOK))
        if (rel.startsWith(BANNERS)) return Served.Banner(rel.removePrefix(BANNERS))
        if (rel.startsWith(PREVIEW)) {
            val previewName = rel.removePrefix(PREVIEW)
            return if (PREVIEW_JPG.matches(previewName)) Served.Preview(previewName) else Served.Blocked
        }

        val name = if (rel.isEmpty()) INDEX else rel
        open(name)?.let { return Served.Asset(mimeOf(name), name, it) }
        // Тип — від того, що ВІДДАЄМО, а не від того, що просили: на
        // `/webmsgr/chat/123` тип запиту дав би octet-stream на тілі HTML, і
        // WebView замість екрана почав би завантаження.
        return open(INDEX)?.let { Served.Fallback(mimeOf(INDEX), it) } ?: Served.Blocked
    }

    fun mimeOf(path: String): String = when {
        path.endsWith(".html") -> "text/html"
        path.endsWith(".js") || path.endsWith(".mjs") -> "text/javascript"
        path.endsWith(".css") -> "text/css"
        path.endsWith(".json") -> "application/json"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
        path.endsWith(".webp") -> "image/webp"
        path.endsWith(".woff2") -> "font/woff2"
        path.endsWith(".woff") -> "font/woff"
        path.endsWith(".ttf") -> "font/ttf"
        path.endsWith(".wasm") -> "application/wasm"
        path.endsWith(".ico") -> "image/x-icon"
        else -> "application/octet-stream"
    }
}
