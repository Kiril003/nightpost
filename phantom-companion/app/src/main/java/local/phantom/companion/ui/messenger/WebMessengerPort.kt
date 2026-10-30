package local.phantom.companion.ui.messenger

import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Асинхронний порт веб ↔ телефон (MESSENGER_V2 §2.2, хвиля 1Б).
 *
 * Доти кожен `PhantomBridge.call` тримав JS-потік сторінки, поки рідний бік
 * рахував, а всередині стояло блокування корутиною з мережею — до 12 с мертвого
 * екрана на ногу дзвінка. Тут веб шле `{id, method, path, body}` і йде далі;
 * відповідь `{"id":…,"r":<та сама обгортка ok/fail>}` приходить окремим листом.
 *
 * Байти не їдуть JSON-ом: заголовок `{id, bin:{conversation_id}}` і одразу за
 * ним `ArrayBuffer`. WebView тримає порядок листів одного відправника, тож
 * пара складається без номерів у самих байтах.
 *
 * Усе, що приходить, приходить на головному потоці; робота йде на [lane]
 * (слова) або [bulkLane] (файли), відповідь — назад через [toMain]
 * (`JavaScriptReplyProxy` вимагає UI-потік).
 */
class WebMessengerPort(
    private val scope: CoroutineScope,
    private val toMain: (Runnable) -> Unit,
    private val handle: suspend (method: String, path: String, body: String?) -> String,
    private val takeBytes: suspend (conversationId: String, bytes: ByteArray) -> String,
    private val lane: CoroutineDispatcher = LANE,
    private val bulkLane: CoroutineDispatcher = BULK_LANE,
) {

    fun interface Reply {
        fun post(message: String)
    }

    private class Pending(val id: Int, val conversationId: String)

    /** Лише головний потік: і лист, і байти за ним приходять туди. */
    private val waitingBytes = IdentityHashMap<Any, Pending>()

    @Volatile var binary: Boolean = false

    fun onText(raw: String, from: Any, reply: Reply) {
        val o = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        if (o.str("t") == "hello") {
            reply.post(buildJsonObject { put("t", "hello"); put("bin", binary) }.toString())
            return
        }
        val id = (o["id"] as? JsonPrimitive)?.intOrNull ?: return
        val bin = o["bin"] as? JsonObject
        if (bin != null) {
            waitingBytes[from] = Pending(id, bin.str("conversation_id").orEmpty())
            return
        }
        val method = o.str("method") ?: return answer(reply, id, BAD)
        val path = o.str("path") ?: return answer(reply, id, BAD)
        val body = o.str("body")
        scope.launch(if (isBulk(path)) bulkLane else lane) { answer(reply, id, safely { handle(method, path, body) }) }
    }

    fun onBytes(bytes: ByteArray, from: Any, reply: Reply) {
        val p = waitingBytes.remove(from) ?: return
        scope.launch(bulkLane) { answer(reply, p.id, safely { takeBytes(p.conversationId, bytes) }) }
    }

    private fun answer(reply: Reply, id: Int, out: String) {
        // Обгортка вже JSON — вкладається як є, без другого кодування рядка.
        val msg = "{\"id\":$id,\"r\":$out}"
        toMain(Runnable { runCatching { reply.post(msg) } })
    }

    private suspend fun safely(block: suspend () -> String): String = try {
        block()
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        failJson(500, "bridge_error", t.message ?: t.javaClass.simpleName)
    }

    /** true — порт стоїть і веб його побачить як `window.PhantomPort`. */
    fun install(webView: WebView, origin: String): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
        binary = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)
        WebViewCompat.addWebMessageListener(webView, NAME, setOf(origin)) { _, message, _, isMainFrame, proxy ->
            if (!isMainFrame) return@addWebMessageListener
            val reply = Reply { proxy.postMessage(it) }
            if (binary && message.type == WebMessageCompat.TYPE_ARRAY_BUFFER) {
                onBytes(message.arrayBuffer, proxy, reply)
            } else {
                message.data?.let { onText(it, proxy, reply) }
            }
        }
        return true
    }

    companion object {
        const val NAME = "PhantomPort"

        /**
         * Одна смуга на порт і старий `call`: порядок запитів той самий, що
         * й був, а мережа йде з неї геть через `withContext(IO)`, тож смуга
         * не стоїть, поки хтось чекає на сокет.
         */
        val LANE: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

        /**
         * Окрема смуга для байтів і файлових шляхів: фото на 30 МБ, яке
         * шифрується й ріжеться на шматки, не має тримати «привіт» за собою.
         */
        val BULK_LANE: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

        private val BULK = Regex("^/(?:api/v1|[^/?]+)/(files|attachments)(/|\\?|$)")

        fun isBulk(path: String): Boolean = BULK.containsMatchIn(path)

        /** Рівень мікрофона для веба — не частіше ~15 разів на секунду і лише коли змінився. */
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        fun audioFrames(levels: Flow<Float>, periodMs: Long = AUDIO_FRAME_MS): Flow<Float> =
            levels.sample(periodMs).distinctUntilChanged()

        const val AUDIO_FRAME_MS = 66L

        private const val SYNC_WAIT_S = 30L

        private val BAD = failJson(400, "bad_request", "bad port message")

        /**
         * Старий синхронний вхід чекає за контрактом — JS жде рядок. Живе лише
         * для WebView без порту; новий бандл сюди не ходить.
         */
        fun await(scope: CoroutineScope, block: suspend () -> String): String {
            val done = CompletableFuture<String>()
            scope.launch(LANE) {
                runCatching { block() }
                    .onSuccess { done.complete(it) }
                    .onFailure { done.completeExceptionally(it) }
            }
            return runCatching { done.get(SYNC_WAIT_S, TimeUnit.SECONDS) }.getOrElse { t ->
                failJson(504, "bridge_timeout", t.message ?: t.javaClass.simpleName)
            }
        }

        internal fun failJson(status: Int, code: String, detail: String): String = buildJsonObject {
            put("ok", false)
            put("status", status)
            put("code", code)
            put("detail", detail)
        }.toString()

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
