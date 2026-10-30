package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Кнопка «зберегти офлайн-територію» в MapChrome.kt ховається за
 * `canSaveOfflineRegion`. Знайдено 05.09: MapScreen.kt загортав свій (справді
 * необовʼязковий) `onSaveOfflineRegion` у лямбду для MapChrome, а лямбда сама
 * була БЕЗУМОВНО не-null — умова `if (onSaveOfflineRegion != null)` у
 * MapChrome ставала тотожністю, і кнопка не могла зникнути, навіть якби
 * якийсь виклик MapScreen колись передав null. MapChrome саму функцію
 * ніколи й не кличе (кнопка лише відкриває панель), тож правильний тип —
 * `Boolean`, а не функція, за якою немає виклику: не лікує обгортку, а
 * прибирає причину пастки. Той самий клас вади, що
 * [local.phantom.companion.data.ProfileIsKeptTest] ловив на профілі.
 *
 * Друга вада поруч, в `onSaveView` панелі: рядок «зберігаю територію»
 * друкувався РАНІШЕ за перевірку, чи обробник узагалі є, — тобто міг
 * збрехати про дію, якої не було.
 *
 * Читає джерело текстом (як WebBundleFirewallTest), бо саме джерело і несе
 * ваду — Compose тут виконувати нічим. Перевіряє ПОВЕДІНКУ (тип параметра,
 * звідки береться його значення, порядок перевірки й сповіщення), а не
 * конкретний вигляд лямбди — інша форма коду з тим самим дефектом мусить
 * так само червоніти.
 */
class OfflineRegionHandlerHonestyTest {

    private fun mapRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val root = dir ?: error("не знайшов корінь дерева (каталог feature-stream)")
        return File(root, "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map")
    }

    private val chromeSource by lazy { File(mapRoot(), "MapChrome.kt").readText() }
    private val screenSource by lazy { File(mapRoot(), "MapScreen.kt").readText() }

    @Test
    fun `MapChrome питає можливість, а не тримає функцію без виклику`() {
        assertTrue(
            "MapChrome знову приймає onSaveOfflineRegion як функцію — вона тут " +
                "ніколи не викликається (кнопка лише відкриває панель), тож будь-яка " +
                "обгортка навколо неї може стати безумовно не-null і пастка повернеться",
            "canSaveOfflineRegion: Boolean" in chromeSource,
        )
        assertFalse(
            "onSaveOfflineRegion як тип параметра MapChrome — стара форма пастки",
            "onSaveOfflineRegion: ((bounds" in chromeSource,
        )
        assertTrue(
            "кнопка офлайн-території більше не питає canSaveOfflineRegion",
            "if (canSaveOfflineRegion)" in chromeSource,
        )
    }

    @Test
    fun `canSaveOfflineRegion — живий null-чек, а не хардкод`() {
        val at = screenSource.indexOf("canSaveOfflineRegion = ")
        assertTrue("виклик MapChrome не передає canSaveOfflineRegion", at > 0)
        val value = screenSource.substring(at, minOf(screenSource.length, at + 60))
        assertTrue(
            "canSaveOfflineRegion мусить читати СПРАВЖНІЙ необовʼязковий обробник " +
                "(`onSaveOfflineRegion != null`), інакше це просто хардкод, який ніколи " +
                "не сховає кнопку — та сама пастка в новому вбранні:\n$value",
            "onSaveOfflineRegion != null" in value,
        )
    }

    @Test
    fun `«зберігаю територію» в панелі не звучить, коли зберігати нічим`() {
        val at = screenSource.indexOf("onSaveView = {")
        assertTrue("не знайшов onSaveView у панелі офлайн-територій", at > 0)
        val body = screenSource.substring(at, minOf(screenSource.length, at + 900))
        val nullCheck = body.indexOf("save == null")
        val notice = body.indexOf("postNotice(savingRegionText)")
        assertTrue("перевірка `save == null` зникла з onSaveView", nullCheck > 0)
        assertTrue(
            "«зберігаю територію» знову друкується РАНІШЕ за перевірку, чи обробник " +
                "узагалі є — так воно бреше, коли зберігати нічим",
            notice > nullCheck,
        )
    }

    /** ДОКАЗ ЧЕРВОНОГО: та сама перевірка на вихідній, ще не полагодженій формі. */
    @Test
    fun `перевірка ловить стару форму — функцію без виклику й безумовне сповіщення`() {
        val buggyChrome = "onSaveOfflineRegion: ((bounds: OfflineBounds, styleUrl: String) -> Unit)?,"
        assertFalse(
            "стара форма параметра MapChrome не мала б проходити новий тест типу",
            "canSaveOfflineRegion: Boolean" in buggyChrome,
        )

        val buggyOnSaveView = """
            onSaveView = {
                val map = ui.mapRef
                if (map == null) {
                    viewModel.postNotice(noMapYetText)
                } else {
                    val b = map.projection.visibleRegion.latLngBounds
                    viewModel.postNotice(savingRegionText)
                    onSaveOfflineRegion?.invoke(
        """.trimIndent()
        val notice = buggyOnSaveView.indexOf("postNotice(savingRegionText)")
        val nullCheck = buggyOnSaveView.indexOf("save == null")
        assertTrue(
            "у старій формі сповіщення справді стоїть без попередньої перевірки — " +
                "пастка саме тут",
            notice > 0 && nullCheck < 0,
        )
    }
}
