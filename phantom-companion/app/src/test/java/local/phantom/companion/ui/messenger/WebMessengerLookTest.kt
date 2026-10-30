package local.phantom.companion.ui.messenger

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.data.appearance.Appearance
import local.phantom.companion.data.appearance.Shade
import local.phantom.companion.data.appearance.Wallpaper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WebMessengerLookTest {

    private var look = Appearance(accent = "sage", wallpaper = Wallpaper.photo("wall-abc.jpg"), wallpaperPicked = true, dim = Shade(12, 55), blur = Shade(0, 6))
    private var photo: ByteArray? = null
    private val route = WebMessengerLook(
        object : WebMessengerLook.Port {
            override fun look() = look
            override fun setChat(chatId: String, wallpaper: Wallpaper?): Appearance {
                look = look.copy(chats = if (wallpaper == null) look.chats - chatId else look.chats + (chatId to wallpaper))
                return look
            }
            override fun putChatPhoto(chatId: String, bytes: ByteArray): Appearance? {
                if (bytes.isEmpty()) return null
                photo = bytes
                return setChat(chatId, Wallpaper.photo("chat-1.jpg"))
            }
        },
    )

    private fun call(method: String, path: String, body: String? = null) =
        Json.parseToJsonElement(route.handle(method, path.trim('/').split('/'), body)).jsonObject

    @Test
    fun `веб отримує акцент, тло з адресою і тіні Дня й Ночі`() {
        val d = call("GET", "/messenger/appearance")["data"]!!.jsonObject
        assertEquals("sage", d["accent"]!!.jsonPrimitive.content)
        assertEquals("/webmsgr/_look/wall-abc.jpg", d["wallpaper"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("true", d["wallpaperPicked"]!!.jsonPrimitive.content)
        assertEquals("55", d["dim"]!!.jsonObject["night"]!!.jsonPrimitive.content)
        assertEquals("6", d["blur"]!!.jsonObject["night"]!!.jsonPrimitive.content)
    }

    @Test
    fun `своє тло розмови - вбудоване, жодного, назад до загального`() {
        call("PUT", "/messenger/appearance/chats/c1", """{"kind":"builtin","id":"clay"}""")
        assertEquals(Wallpaper.builtin("clay"), look.chats["c1"])
        call("PUT", "/messenger/appearance/chats/c1", """{"kind":"none"}""")
        assertEquals(Wallpaper.None, look.chats["c1"])
        val d = call("PUT", "/messenger/appearance/chats/c1", """{"kind":"default"}""")["data"]!!.jsonObject
        assertEquals(0, d["chats"]!!.jsonObject.size)
        assertEquals("400", call("PUT", "/messenger/appearance/chats/c1", """{"kind":"builtin","id":"lava"}""")["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `фото з веба приходить як data-URL`() {
        val bytes = byteArrayOf(9, 8, 7)
        val url = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes)
        val d = call("PUT", "/messenger/appearance/chats/c2", """{"kind":"photo","data":"$url"}""")["data"]!!.jsonObject
        assertArrayEquals(bytes, photo)
        assertEquals("/webmsgr/_look/chat-1.jpg", d["chats"]!!.jsonObject["c2"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("400", call("PUT", "/messenger/appearance/chats/c2", """{"kind":"photo","data":"javascript:1"}""")["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `фото тла віддає перехоплювач, а не бандл`() {
        assertEquals(Served.Look("wall-abc.jpg"), WebMessengerAssets.resolve("https://phantom.local/webmsgr/_look/wall-abc.jpg") { null })
    }
}
