package local.phantom.companion.ui.messenger

import java.io.File
import local.phantom.companion.data.SavedNotes
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Дзеркало контракту «Збереженого»: id, рід і коло, які віддає вузол, мусять
 * бути тими словами, якими розмову впізнає веб. Розійдуться — нотатки знову
 * житимуть лише у вкладці.
 *
 * Бандл у пакунку перевіряється завжди. Джерело — коли поруч лежить дерево
 * месенджера (`PHANTOM_WEB_SRC` або сусіднє `phantom-os`); на воротах без нього
 * тест пропускається, і це видно як skipped у XML.
 */
class SavedNotesWebContractTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("корінь дерева не знайдено")
    }

    private val bundle by lazy {
        File(repoRoot(), "app/src/main/assets/webmsgr/assets").listFiles { f -> f.name.endsWith(".js") }
            .orEmpty().joinToString("\n") { it.readText() }
    }

    @Test
    fun `бандл у пакунку впізнає розмову за тим самим id і колом`() {
        assertTrue("бандл порожній — нема з чим звіряти", bundle.isNotEmpty())
        assertTrue(
            "бандл не знає id ${SavedNotes.ID}",
            bundle.contains("\"${SavedNotes.ID}\""),
        )
        // Редизайн 01.10 впізнає «Збережене» за родом рядка (kind → type), а не за
        // колом: `e.id==="chat_saved_notes"||e.type==="saved"` (isSavedNotes).
        // Міст шле і рід, і коло тим самим словом — звіряємо те, що веб читає.
        assertTrue(
            "бандл не впізнає рід «${WebMessengerSaved.KIND}»",
            bundle.contains("type===\"${WebMessengerSaved.KIND}\""),
        )
    }

    private fun webSrc(): File? {
        val env = System.getenv("PHANTOM_WEB_SRC")?.let(::File)
        val sibling = File(repoRoot().parentFile, "phantom-os/src/frontend/src")
        return listOfNotNull(env, sibling).firstOrNull { File(it, "stores/messengerStore.ts").isFile }
    }

    @Test
    fun `джерело веба чекає ту саму форму рядка`() {
        val src = webSrc()
        val store = src?.let { File(it, "stores/messengerStore.ts").readText() }.orEmpty()
        assumeTrue("поруч немає дерева веб-месенджера", store.contains("openSavedNotes"))
        val api = File(src, "services/messengerApi.ts").readText()
        val types = File(src, "types/messenger.ts").readText()

        // Id живе в одній константі API, стор читає її, а не пише літерал.
        assertTrue(
            "веб зве «Збережене» іншим id",
            Regex("""SAVED_NOTES_ID\s*=\s*'${SavedNotes.ID}'""").containsMatchIn(api),
        )
        assertTrue("стор не бере id «Збереженого» з API", store.contains("SAVED_NOTES_ID"))
        // Список впізнає «Збережене» за родом рядка (kind → type), який шле міст.
        val listFormat = File(src, "components/messenger/chatListFormat.ts").takeIf { it.isFile }?.readText().orEmpty()
        assertTrue(
            "веб не впізнає «Збережене» за родом 'saved'",
            listFormat.contains("type === '${WebMessengerSaved.CIRCLE}'"),
        )
        assertTrue("веб бере рід розмови не з поля kind", api.contains("type: (row.kind as Chat['type'])"))
        assertTrue("веб бере коло не з поля circle", api.contains("circle: (row.circle as Chat['circle'])"))
        // Рід "saved" веб бере як є (див. chatFromNode), але в союзі ChatType його
        // ще немає — це робота веб-етапу, не розбіжність дроту.
        val chatCircle = types.substringAfter("export type ChatCircle").substringBefore(";")
        assertTrue("у ChatCircle немає '${WebMessengerSaved.CIRCLE}'", chatCircle.contains("'${WebMessengerSaved.CIRCLE}'"))
    }
}
