package local.phantom.companion.ui.messenger

/**
 * Чи людина бачить месенджер — для «прочитано» (DL-2, ніч 09.10).
 *
 * Відправник бачив подвійну галочку, поки телефон адресата лежав на робочому
 * столі: WebView після «Додому» лишав `document.visibilityState = visible`,
 * і веб розчитував лист у відкритій розмові одразу. Тепер хост на ON_START і
 * ON_STOP застосунку штовхає стан у сторінку; бандл оголошує приймача сам
 * (`services/appForeground.ts`) і відкладає прочитане, поки застосунок у фоні.
 */
internal const val WEB_FOREGROUND_SINK = "__phantomForeground"

internal fun webForegroundPushJs(seen: Boolean): String =
    "window.$WEB_FOREGROUND_SINK && window.$WEB_FOREGROUND_SINK($seen);"
