package local.phantom.companion.ui.messenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * ЧИ БАНДЛ І МІСТ ЗБІГАЮТЬСЯ.
 *
 * 04.09 01:30, перший кадр на справжньому склі: месенджер — порожня сторінка.
 * `index.html` просив `/assets/index-*.js` від кореня, а перехоплювач пускає
 * лише те, що під точкою монтування `webmsgr` — три модулі дістали порожній
 * `text/plain`, рендерер упав. Так було в КОЖНІЙ збірці з 502c2f06 по a1cecdb8.
 *
 * Ніхто цього не бачив, бо сторожі перевіряли файли бандла (карти, модалки,
 * вміст) і ФОРМУ рядка в index.html. Форма рядка не є відповіддю моста. Тому
 * тут піднімається сам перехоплювач і в нього просять рівно ті шляхи, які
 * лежать в index.html: зелене означає, що бандл підʼєднається, а не що він
 * гарно написаний.
 */
class WebHostServesBundleTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("не знайшов корінь дерева (каталог feature-stream)")
    }

    private val bundle get() = File(repoRoot(), "app/src/main/assets/webmsgr")

    /** Усе, що сторінка тягне сама: від кореня або з мережі. Без винятків. */
    private fun refs(index: String): List<String> =
        Regex("""(?:src|href)="([^"]+)"""").findAll(index)
            .map { it.groupValues[1] }
            .filter { it.startsWith("/") || it.startsWith("http") }
            .toList()

    private fun fromDisk(name: String): InputStream? =
        File(bundle, name).takeIf { it.isFile }?.inputStream()

    /** Бандл, у якому є ВСЕ: тоді хибному порядку перевірок нема за чим сховатись. */
    private val everything: (String) -> InputStream? = { ByteArrayInputStream(ByteArray(0)) }

    private fun serve(path: String, open: (String) -> InputStream? = ::fromDisk): Served =
        WebMessengerAssets.resolve(WebMessengerAssets.ORIGIN + path, open)

    @Test
    fun `перехоплювач віддає кожне посилання index html`() {
        val index = File(bundle, "index.html")
        assertTrue("бандла немає: ${index.path}", index.isFile)
        val refs = refs(index.readText())
        check(refs.size >= 3) {
            "index.html дав ${refs.size} посилань — сторож не перевірив нічого; зелене на порожньому не є зеленим"
        }

        val lost = refs.mapNotNull { ref ->
            when (val served = serve(ref)) {
                is Served.Asset ->
                    if (served.mime == WebMessengerAssets.mimeOf(ref)) null
                    else "$ref → тип ${served.mime}, а не ${WebMessengerAssets.mimeOf(ref)}"
                is Served.Blocked -> "$ref → порожній text/plain: поза ${WebMessengerAssets.MOUNT}"
                is Served.Fallback -> "$ref → index.html замість файла: у бандлі такого імені немає"
                is Served.Attachment -> "$ref → маршрут вкладення, а не файл бандла"
                is Served.Face -> "$ref → маршрут фото людини, а не файл бандла"
                is Served.Look -> "$ref → маршрут фото тла, а не файл бандла"
                is Served.Banner -> "$ref → маршрут фото банера, а не файл бандла"
                is Served.Preview -> "$ref → маршрут попереднього перегляду, а не файл бандла"
            }
        }
        assertEquals(
            "WebView не отримає ${lost.size} з ${refs.size} посилань index.html — сторінка буде порожня:\n    " +
                lost.joinToString("\n    "),
            emptyList<String>(),
            lost,
        )
    }

    /**
     * Доказ того, що цей сторож уміє почервоніти, і рівно на тому класі вади.
     * Фікстура — справжній index.html з 502c2f06, того самого бандла, який
     * місяць їхав власнику порожньою сторінкою.
     */
    @Test
    fun `бандл 502c2f06 не проходить — жодного посилання міст не віддавав`() {
        val old = javaClass.classLoader!!.getResourceAsStream("webmsgr-index-502c2f06.html")!!
            .use { it.readBytes().decodeToString() }
        val refs = refs(old)
        check(refs.size >= 3) { "фікстура зламаного бандла порожня: ${refs.size} посилань" }

        val blocked = refs.filter { serve(it, everything) == Served.Blocked }
        assertEquals(
            "сторож перестав ловити бандл, зібраний без base=/webmsgr/ — саме він дав перший кадр 04.09",
            refs,
            blocked,
        )
    }

    @Test
    fun `посилання від кореня перехоплювач не віддає`() {
        assertEquals(Served.Blocked, serve("/assets/index-CBinIyUv.js", everything))
    }

    @Test
    fun `вихід за межі каталогу не віддає нічого`() {
        assertEquals(Served.Blocked, serve("/webmsgr/../secret", everything))
    }

    /**
     * Не лише «дістає index.html», а й «як сторінку». Тип від ЗАПИТАНОГО імені
     * дав би `application/octet-stream` на тілі HTML: WebView такого не малює,
     * а починає завантаження — той самий беззвучний клас вади, що й порожній
     * месенджер. `foo.bin` тут не файл, якого бракує, а маршрут усередині веба.
     */
    @Test
    fun `невідомий шлях дістає index html, і саме як сторінку`() {
        for (path in listOf("/webmsgr/chat/123", "/webmsgr/foo.bin")) {
            val served = serve(path)
            assertTrue("$path не дістає index.html: $served", served is Served.Fallback)
            assertEquals(
                "$path віддається не як сторінка — WebView почне завантаження замість екрана",
                "text/html",
                (served as Served.Fallback).mime,
            )
        }
    }

    /**
     * Вкладення розпізнається ПЕРЕД пошуком у бандлі. Бандл тут відповідає на
     * будь-яке імʼя: якби перевірка стояла після нього, `<img>` отримав би
     * сторінку HTML замість байтів — без помилки, без журналу, просто «фото не
     * показується».
     */
    @Test
    fun `вкладення йде своєю дорогою, а не в бандл`() {
        val id = "0123456789abcdef0123456789abcdef"
        assertEquals(Served.Attachment("_files/$id"), serve("/webmsgr/_files/$id", everything))
        assertEquals("«..» проходить крізь маршрут вкладень", Served.Blocked, serve("/webmsgr/_files/../x", everything))
    }
}
