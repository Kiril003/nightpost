package local.phantom.companion.data.appearance

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceStoreTest {

    private val root: File = Files.createTempDirectory("look").toFile()
    private var me = "node-a"
    private fun store() = AppearanceStore(root) { me }

    @Test
    fun `вибір переживає перезапуск`() {
        store().update { it.copy(accent = "sage", dim = Shade(day = 10, night = 60), bubbles = Appearance.COMPACT) }
        val again = store().apply { bind() }.state.value
        assertEquals("sage", again.accent)
        assertEquals(Shade(10, 60), again.dim)
        assertEquals(Appearance.COMPACT, again.bubbles)
    }

    @Test
    fun `холодний старт бере останню особу ще до ключів`() {
        store().update { it.copy(accent = "iris") }
        me = ""
        assertEquals("iris", store().state.value.accent)
    }

    @Test
    fun `кожна особа має свій вигляд`() {
        store().update { it.copy(accent = "rose", wallpaper = Wallpaper.builtin("sand"), wallpaperPicked = true) }
        me = "node-b"
        val b = store().apply { bind() }
        assertEquals(Appearance(), b.state.value)
        b.update { it.copy(accent = "sea") }
        me = "node-a"
        val a = store().apply { bind() }.state.value
        assertEquals("rose", a.accent)
        assertEquals(Wallpaper.builtin("sand"), a.wallpaper)
    }

    @Test
    fun `тло розмови перекриває загальне і знімається`() {
        val s = store()
        s.update { it.copy(wallpaper = Wallpaper.builtin("slate"), wallpaperPicked = true) }
        s.setChat("c1", Wallpaper.None)
        assertEquals(Wallpaper.None, s.state.value.wallpaperFor("c1"))
        assertEquals(Wallpaper.builtin("slate"), s.state.value.wallpaperFor("c2"))
        s.setChat("c1", null)
        assertEquals(Wallpaper.builtin("slate"), s.state.value.wallpaperFor("c1"))
    }

    @Test
    fun `нове фото замінює старе і старий файл прибрано`() {
        val s = store()
        val first = s.putPhoto(AppearanceStore.Target.Everywhere, byteArrayOf(1, 2, 3)).wallpaper.file!!
        assertNotNull(s.file(first))
        val second = s.putPhoto(AppearanceStore.Target.Everywhere, byteArrayOf(4)).wallpaper.file!!
        assertNull(s.file(first))
        assertEquals(1, s.file(second)!!.length())
        val chat = s.putPhoto(AppearanceStore.Target.Chat("c1"), byteArrayOf(5)).chats.getValue("c1").file!!
        assertTrue(chat.startsWith("chat-"))
        assertNotNull(s.file(second))
    }

    @Test
    fun `типове тло рівне, рельєф лише свідомим вибором`() {
        assertEquals(Wallpaper.None, store().state.value.wallpaper)
        val lookFile = File(root, "${AppearanceStore.slotOf("node-a")}/look.json").apply { parentFile?.mkdirs() }
        lookFile.writeText("""{"wallpaper":{"kind":"builtin","id":"contour"}}""")
        assertEquals(Wallpaper.None, store().apply { bind() }.state.value.wallpaper)
        lookFile.writeText("""{"wallpaper":{"kind":"builtin","id":"contour"},"wallpaperPicked":true}""")
        val picked = store().apply { bind() }
        assertEquals(Wallpaper.builtin("contour"), picked.state.value.wallpaper)
        picked.update { it.copy(accent = "sage") }
        assertEquals(Wallpaper.builtin("contour"), store().apply { bind() }.state.value.wallpaper)
        lookFile.writeText("""{"wallpaper":{"kind":"none"},"wallpaperPicked":true}""")
        assertEquals(Wallpaper.None, store().apply { bind() }.state.value.wallpaper)
    }

    @Test
    fun `чуже або зіпсоване не проходить`() {
        val s = store()
        val v = s.update {
            it.copy(
                accent = "neon",
                dim = Shade(-5, 400),
                blur = Shade(99, 3),
                list = "huge",
                wallpaper = Wallpaper.builtin("lava"),
                chats = mapOf("c" to Wallpaper.photo("../../etc/passwd")),
            )
        }
        assertEquals("amber", v.accent)
        assertEquals(Shade(0, Appearance.DIM_MAX), v.dim)
        assertEquals(Shade(Appearance.BLUR_MAX, 3), v.blur)
        assertEquals(Appearance.COMFORTABLE, v.list)
        assertEquals(Wallpaper.None, v.wallpaper)
        assertTrue(v.chats.isEmpty())
        assertNull(s.file("../look.json"))
        File(root, "${AppearanceStore.slotOf("node-a")}/look.json").writeText("{not json")
        assertEquals(Appearance(), store().apply { bind() }.state.value)
        assertFalse(store().state.value.accent.isEmpty())
    }

    @Test
    fun `вбудований візерунок глушиться на ту саму міру, що й у вебі, фото — ні`() {
        assertEquals(0.775f, Appearance.scrim(55, Wallpaper.BUILTIN), 0.001f)
        assertEquals(0.9f, Appearance.scrim(80, Wallpaper.BUILTIN), 0.001f)
        assertEquals(0.2f, Appearance.scrim(20, Wallpaper.PHOTO), 0.001f)
    }
}
