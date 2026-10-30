package local.phantom.companion.ui.messenger

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.data.appearance.Appearance
import local.phantom.companion.data.appearance.Wallpaper

/**
 * `/messenger/appearance` — Профіль › Вигляд для веба: акцент, тло (загальне й
 * своє для розмови), приглушення й розмиття для Дня й Ночі, щільність.
 */
class WebMessengerLook(private val port: Port) {

    interface Port {
        fun look(): Appearance
        fun setChat(chatId: String, wallpaper: Wallpaper?): Appearance

        /** null — байти не картинка. */
        fun putChatPhoto(chatId: String, bytes: ByteArray): Appearance?
    }

    fun claims(seg: List<String>): Boolean = seg.size >= 2 && seg[1] == "appearance"

    fun handle(method: String, seg: List<String>, body: String?): String = runCatching {
        when {
            seg.size == 2 && method == "GET" -> ok(webLook(port.look()))
            seg.size == 4 && seg[2] == "chats" && method == "PUT" -> setChat(seg[3], body)
            seg.size == 4 && seg[2] == "chats" && method == "DELETE" -> ok(webLook(port.setChat(seg[3], null)))
            else -> fail(404, "not_found")
        }
    }.getOrElse { fail(500, "appearance_error") }

    private fun setChat(chatId: String, body: String?): String {
        if (chatId.isBlank()) return fail(400, "bad_chat")
        val o = runCatching { Json.parseToJsonElement(body ?: "").jsonObject }.getOrNull() ?: return fail(400, "bad_body")
        val look = when (o.str("kind")) {
            "default" -> port.setChat(chatId, null)
            Wallpaper.NONE -> port.setChat(chatId, Wallpaper.None)
            Wallpaper.BUILTIN -> {
                val id = o.str("id")
                if (id !in Wallpaper.BUILTINS) return fail(400, "bad_wallpaper")
                port.setChat(chatId, Wallpaper.builtin(id))
            }
            Wallpaper.PHOTO -> {
                val bytes = dataUrlBytes(o.str("data")) ?: return fail(400, "bad_photo")
                port.putChatPhoto(chatId, bytes) ?: return fail(415, "not_image")
            }
            else -> return fail(400, "bad_kind")
        }
        return ok(webLook(look))
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()

    private fun ok(data: JsonElement): String = buildJsonObject {
        put("ok", true)
        put("status", 200)
        put("data", data)
    }.toString()

    private fun fail(status: Int, code: String): String = buildJsonObject {
        put("ok", false)
        put("status", status)
        put("code", code)
        put("detail", "")
    }.toString()

    companion object {
        /** Фото з веба — до 12 МБ байтів; більше веб не надсилає (сам стискає). */
        private const val MAX_PHOTO = 12 * 1024 * 1024

        fun dataUrlBytes(url: String): ByteArray? {
            if (!url.startsWith("data:image/")) return null
            val b64 = url.substringAfter("base64,", "").ifEmpty { return null }
            if (b64.length > MAX_PHOTO / 3 * 4 + 4) return null
            return runCatching { Base64.getDecoder().decode(b64) }.getOrNull()
        }

        private fun wallpaperJson(w: Wallpaper): JsonElement = buildJsonObject {
            put("kind", w.kind)
            w.id?.let { put("id", it) }
            w.file?.let { put("url", WebMessengerAssets.MOUNT + WebMessengerAssets.LOOK + it) }
        }

        fun webLook(a: Appearance): JsonElement = buildJsonObject {
            put("accent", a.accent)
            put("wallpaper", wallpaperJson(a.wallpaper))
            put("wallpaperPicked", a.wallpaperPicked)
            put("chats", buildJsonObject { a.chats.forEach { (id, w) -> put(id, wallpaperJson(w)) } })
            put("dim", buildJsonObject { put("day", a.dim.day); put("night", a.dim.night) })
            put("blur", buildJsonObject { put("day", a.blur.day); put("night", a.blur.night) })
            put("bubbles", a.bubbles)
            put("list", a.list)
        }
    }
}
