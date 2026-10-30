package local.phantom.companion.ui.messenger

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Чи файли справді ДОСЯЖНІ, а не просто написані.
 *
 * Правильність кадру, шматків і шляху доводиться в core-net справжніми
 * ключами й справжнім конвертом. Тут доводиться інше, і в цьому дереві це
 * ловилось уже тричі: підсистема написана, вкрита тестами й не підʼєднана
 * нічим. Зелений тест на функцію, якої ніхто не кличе, — найдорожча брехня.
 *
 * Чому текстом, а не викликом. Міст відповідає `org.json`, а в модулі `app`
 * юніт-тести бачать від нього заглушку: `org.json` немає в офлайновому кеші
 * Gradle, тож підняти справжній не було чим. Отже про МІСТ перевіряється те, що
 * з тексту видно надійно: що маршрут існує і що відмову знято.
 *
 * Порядок перевірок у перехоплювачі — уже викликом: рішення живе в
 * [WebMessengerAssets], і `org.json` йому не потрібен.
 *
 * GATE LAW: читає файли як текст. Входи задачі оголошені.
 */
class WebMessengerFilesReachTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь дерева не знайдено") as Nothing
    }

    private fun source(rel: String): String {
        val f = File(repoRoot(), rel)
        if (!f.isFile) fail("немає файла $rel")
        return f.readText()
    }

    private val bridge by lazy {
        source("app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt")
    }
    private val container by lazy {
        source("app/src/main/java/local/phantom/companion/data/AppContainer.kt")
    }

    /** Бандл, у якому є ВСЕ: тоді хибному порядку перевірок нема за чим сховатись. */
    private val everything: (String) -> InputStream? = { ByteArrayInputStream(ByteArray(0)) }

    @Test
    fun `міст більше не відмовляє файлам`() {
        // Рівно той рядок, який власник побачив би першим: «через міст
        // телефона це ще не їде: files».
        val refusal = Regex("""seg\[1\] == "relay".*?not_bridged""", RegexOption.DOT_MATCHES_ALL)
            .find(bridge)?.value ?: fail("відмови not_bridged більше немає взагалі — перевір тест") as Nothing
        assertFalse(
            "files досі стоїть у переліку не-перевезеного",
            refusal.contains("\"files\""),
        )
    }

    @Test
    fun `міст більше не відмовляє дзвінкам та ICE`() {
        val refusal = Regex("""seg\[1\] == "relay".*?not_bridged""", RegexOption.DOT_MATCHES_ALL)
            .find(bridge)?.value ?: fail("відмови not_bridged більше немає взагалі — перевір тест") as Nothing
        assertFalse("ice досі стоїть у переліку не-перевезеного", refusal.contains("\"ice\""))
        assertFalse("call досі стоїть у переліку не-перевезеного", refusal.contains("\"call\""))
        assertTrue(
            "маршрут ICE не веде до WebMessengerIce",
            bridge.contains("container.webMessengerIce.answer()"),
        )
        assertTrue("маршрут handleCallSignal відсутній у мосту", bridge.contains("handleCallSignal("))
    }

    @Test
    fun `маршрути файлів існують у мосту`() {
        for (needle in listOf(
            """seg[1] == "files" && seg.size == 2 && method == "POST"""",
            "takeBlob(body)",
            """seg[3] == "status"""",
            "blobStatus(",
        )) {
            assertTrue("у мосту немає «$needle»", bridge.contains(needle))
        }
    }

    @Test
    fun `надісланий опис тягне за собою байти`() {
        // Без цього виклику опис поїхав би сам: у розмові зʼявилось би фото,
        // якого на тому кінці немає й ніколи не буде.
        assertTrue(
            "append не ставить шматки в чергу",
            bridge.contains("container.mesh.sendMany(peerId, container.peerFiles.bodiesFor(att))"),
        )
    }

    /**
     * Одна дія, не сто сімдесят одна. Журнал шифрується й переписується цілком
     * на кожну зміну, тож шматки, покладені поодинці, кладуть телефон — і
     * кладуть тим сильніше, чим більше фото. Компілятор тут мовчить: обидва
     * варіанти правильні, повільний просто не працює в руках.
     */
    @Test
    fun `шматки кладуться в чергу однією дією`() {
        val mesh = source("app/src/main/java/local/phantom/companion/data/PeerMesh.kt")
        assertTrue("немає гуртового відправлення", mesh.contains("fun sendMany("))
        assertFalse(
            "міст досі шле шматки поодинці",
            bridge.contains("peerFiles.bodiesFor(att).forEach") ||
                bridge.contains("bodiesFor(att).map { container.mesh.send("),
        )
    }

    @Test
    fun `лист каже про стан вкладення, а не мовчить`() {
        assertFalse(
            "attachment_state лишився заглушкою NULL",
            bridge.contains("""put("attachment_state", JSONObject.NULL)"""),
        )
        assertTrue(bridge.contains("""put("attachment_state", att?.let { stateOf(it) }"""))
    }

    /**
     * Порядок, а не наявність. Перехоплювач на невідоме імʼя віддає
     * `index.html` — так задумано, веб односторінковий. Але якщо перевірка
     * вкладення стане ПІСЛЯ цього запасного шляху, `<img>` отримає сторінку
     * HTML замість байтів: без помилки, без журналу, просто «фото не
     * показується». Компілятор такого не ловить.
     *
     * Доводиться викликом, а не читанням тексту: бандл тут відповідає на будь-яке
     * імʼя, тож хибному порядку нема за чим сховатись.
     */
    @Test
    fun `вкладення перехоплюється раніше за пошук у бандлі`() {
        val id = "0123456789abcdef0123456789abcdef"
        val served = WebMessengerAssets.resolve(
            "${WebMessengerAssets.ORIGIN}${WebMessengerAssets.MOUNT}_files/$id",
            everything,
        )
        assertTrue("<img> дістане файл бандла замість байтів вкладення: $served", served is Served.Attachment)
    }

    @Test
    fun `замок на вихід за межі каталогу стоїть перед видачею вкладень`() {
        assertEquals(
            "чуже походження доходить до маршруту вкладень",
            Served.Blocked,
            WebMessengerAssets.resolve("https://evil.example${WebMessengerAssets.MOUNT}_files/x", everything),
        )
        assertEquals(
            "«..» проходить крізь маршрут вкладень",
            Served.Blocked,
            WebMessengerAssets.resolve(
                "${WebMessengerAssets.ORIGIN}${WebMessengerAssets.MOUNT}_files/../secret",
                everything,
            ),
        )
    }

    @Test
    fun `те, що приїхало, лягає на диск`() {
        // Складання підвішене за прибуття листа, а не за сповіщення: шторка
        // мовчить, коли розмова відкрита, і саме тоді файл мусить збиратись.
        assertTrue(
            "absorb не стоїть на шляху прибуття",
            container.contains("peerFiles.absorb(message"),
        )
        assertTrue(
            "onArrived не веде до onPeerMessage",
            container.contains("onArrived = ::onPeerMessage"),
        )
    }

    @Test
    fun `стерта розмова не лишає по собі фотографій`() {
        assertTrue(
            "clear не чіпає байти на диску",
            bridge.contains("container.peerFiles.forget(seg[2])"),
        )
    }

    /**
     * Найважливіший тест у файлі, і написаний він після того, як усе решта вже
     * було зеленим.
     *
     * Кожне твердження вище читає КОТЛІН і доводить, що маршрут існує. Жодне з
     * них не доводить, що хтось його кличе — а кличе його зібраний веб-бандл в
     * `assets`, який лежить у дереві готовим і не перезбирається складанням.
     * Поки в ньому був попередній бандл, весь цей фронт був написаний,
     * покритий тестами й недосяжний: веб слав `POST /messenger/files/upload`
     * через `XMLHttpRequest` повз міст, отримував порожню відповідь зі станом
     * 200 і зависав назавжди — людина обирала фото, і не ставалось нічого.
     *
     * Тому тут перевіряється сам бандл: чи є в ньому ті САМІ рядки, які
     * відповідає котлін. Це і є різниця між «написано» і «доїжджає».
     */
    @Test
    fun `зібраний веб справді кличе міст, а не вузол`() {
        val assets = File(repoRoot(), "app/src/main/assets/webmsgr/assets")
        assertTrue("бандла немає — перевір шлях", assets.isDirectory)
        val js = assets.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".js") }
            ?.joinToString("\n") { it.readText() }
            ?: fail("у бандлі немає жодного .js") as Nothing

        // Завантаження: рівно те тіло, яке читає takeBlob.
        assertTrue(
            "бандл не шле вкладення через міст: немає POST /messenger/files з data_b64",
            js.contains("\"/messenger/files\"") && js.contains("data_b64"),
        )
        // Читання байтів: рівно той префікс, який віддає перехоплювач.
        assertTrue(
            "бандл не бере байти з нашого origin: немає /webmsgr/_files/",
            js.contains("/webmsgr/_files/"),
        )
        // Зіпсоване відрізняється від «ще не приїхало» лише станом 410.
        assertTrue(
            "бандл не розрізняє зіпсоване вкладення (410) і те, що ще їде (404)",
            js.contains("410"),
        )
    }

    @Test
    fun `у шторці не JSON з ключем`() {
        assertTrue(
            "сповіщення покаже сирий опис вкладення",
            container.contains("PeerFile.describe(notice.text)"),
        )
    }
}
