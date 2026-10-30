package local.phantom.companion.privacy

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Дзвінок без цього дозволу німий — і німий МОВЧКИ.
 *
 * 20.09, A52: рушій у WebView відкривав дзвінок, тримав його тридцять секунд і
 * вмирав за таймером. Жодної відмови людині, жодного діалогу, жодного червоного
 * рядка. У logcat при цьому шість разів за 0,35 с:
 *
 *     W cr_media: Requires MODIFY_AUDIO_SETTINGS and RECORD_AUDIO.
 *                 No audio device will be available for recording
 *
 * Chromium вимагає обидва дозволи В ПАРІ. `RECORD_AUDIO` у маніфесті був,
 * `MODIFY_AUDIO_SETTINGS` — не було ніде в дереві. Через це мікрофон не
 * відкрився б НІ ЗА ЯКИХ умов мережі: ні в одному Wi-Fi, ні через реле, ні з
 * месенджером на екрані. Уся решта дзвінкової машинерії стояла за цим рядком.
 *
 * ## Чому тут розбір, а не `contains`
 *
 * KDoc вище і коментар у самому маніфесті ПИШУТЬ ці імена словами. Сторож, що
 * шукає підрядок, лишився б зеленим після видалення самого `<uses-permission>` —
 * його наситив би власний коментар. Таке вже ловили двічі за добу: сира
 * перевірка тексту `build.gradle.kts` не бачила, що рядок закоментували.
 *
 * Тому: спершу ВИРІЗАЄМО коментарі `<!-- ... -->`, і лише тоді шукаємо елемент.
 */
class CallMicPermissionTest {

    private fun manifest(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val root = dir ?: fail("корінь дерева не знайдено") as Nothing
        val f = File(root, "app/src/main/AndroidManifest.xml")
        assertTrue("сторож осліп: немає AndroidManifest.xml", f.isFile)
        return f.readText()
    }

    /** Маніфест без жодного коментаря — тільки те, що справді оголошено. */
    private fun declared(): String =
        manifest().replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")

    private fun declares(permission: String): Boolean =
        Regex("""<uses-permission\s+android:name\s*=\s*"android\.permission\.$permission"""")
            .containsMatchIn(declared())

    @Test
    fun `дзвінок має чим узяти мікрофон`() {
        assertTrue(
            "у маніфесті немає MODIFY_AUDIO_SETTINGS — Chromium не віддасть пристрій " +
                "запису, і дзвінок буде німий мовчки, без жодного слова людині",
            declares("MODIFY_AUDIO_SETTINGS"),
        )
        assertTrue(
            "у маніфесті немає RECORD_AUDIO — друга половина тієї самої пари",
            declares("RECORD_AUDIO"),
        )
    }

    /**
     * Сам спосіб дивитись теж має бути доведений.
     *
     * Якщо вирізання коментарів колись зламається, перевірка вище почне
     * наїдатися власним KDoc і зеленітиме вічно. Тут це зловлено: у маніфесті
     * слово всередині коментаря Є, і після вирізання його бути НЕ МУСИТЬ.
     */
    @Test
    fun `сторож не наїдається коментарем`() {
        val raw = manifest()
        val cut = declared()
        assertTrue(
            "у маніфесті зник пояснювальний коментар про цей дозвіл — перевірка нижче " +
                "більше нічого не доводить, перепиши її",
            raw.contains("<!--") && "MODIFY_AUDIO_SETTINGS" in raw.substringAfter("<!--"),
        )
        val commentedMentions = raw.split("MODIFY_AUDIO_SETTINGS").size - 1
        val declaredMentions = cut.split("MODIFY_AUDIO_SETTINGS").size - 1
        assertTrue(
            "вирізання коментарів не працює: у сирому тексті $commentedMentions згадок, " +
                "після вирізання $declaredMentions — сторож рахує слова, а не оголошення",
            commentedMentions > declaredMentions,
        )
    }
}
