package local.phantom.companion

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * «А те, що воно навіть не дзвонить, тебе не тривожить?» — власник, 21.09.
 *
 * Тривожить. Причин було дві, і жодна з них не про мережу.
 *
 * **Перша: грати не було чому.** Канал сповіщення заводився з
 * `enableVibration(true)` і без жодного `setSound`. Android під
 * `CallStyle.forIncomingCall` рингтона САМ не грає — його дає застосунок.
 * Тобто вхідний дзвінок віддавав одне тенькання й вібрацію.
 *
 * **Друга: кадр зі сховка горів мовчки.** `keepArrived` розпізнавав дзвінок і
 * повертав true, не розбудивши нікого. Причина в коментарі була чесна — лист
 * лежить у сховку до семи діб, і будити апарат кадром із позавчора не можна, —
 * але вона однаково накривала кадр віком три секунди, тобто живий виклик, що
 * просто не знайшов прямої дороги.
 *
 * ## Чому сторож дивиться в джерело, а не запускає
 *
 * Обидві правки — це домовленість з ANDROID: що система зробить зі звуком
 * каналу і коли вона його програє. Юніт-тест цього не виконає: `Ringtone`,
 * `AudioAttributes` і канали живуть у платформі, якої тут немає. Запустити це
 * по-справжньому може лише прогін НА АПАРАТІ, а теки `app/src/androidTest/` у
 * дереві не існує взагалі — це окремий борг, названий у звіті.
 *
 * Тому тут стережеться те, що СТЕРЕЖЕТЬСЯ ЧЕСНО: що рішення не зникло з коду.
 * Це не доказ, що телефон дзвонить, — і цей тест такого не стверджує.
 */
class CallActuallyRingsTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь дерева не знайдено") as Nothing
    }

    private fun source(rel: String): String {
        val f = File(repoRoot(), rel)
        assertTrue("сторож осліп: немає $rel", f.isFile)
        // Коментарі геть: інакше сторожа наситить оцей-таки KDoc, який називає
        // і setSound, і RING_LIFE_MS. Двічі за добу вже ловили тест, зелений
        // від власного пояснення.
        return f.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
    }

    @Test
    fun `вхідний дзвінок має чим дзвонити`() {
        val src = source("app/src/main/java/local/phantom/companion/service/PeerCallNotifications.kt")
        assertTrue(
            "канал дзвінка знову без звуку — Android під CallStyle рингтона сам не грає, " +
                "і людина дістане одне тенькання замість дзвінка",
            src.contains("setSound("),
        )
        assertTrue(
            "звук дзвінка йде не розмовним ужитком: без USAGE_NOTIFICATION_RINGTONE він " +
                "слухатиме гучність сповіщень, а не дзвінка, і глушитиметься музикою",
            src.contains("USAGE_NOTIFICATION_RINGTONE"),
        )
        assertTrue(
            "імʼя каналу повернулось на німу редакцію: налаштування каналу НЕЗМІННІ після " +
                "створення, тож на апаратах, де він уже заведений, звук не зʼявиться ніколи",
            src.contains("phantom_peer_call_v2"),
        )
    }

    @Test
    fun `живий дзвінок зі сховка будить апарат`() {
        val src = source("app/src/main/java/local/phantom/companion/data/PeerMesh.kt")
        val fn = Regex("""private fun keepArrived\(.*?\n    \}""", RegexOption.DOT_MATCHES_ALL)
            .find(src)?.value
            ?: fail("не знайшов keepArrived: сторож не читає того, що стереже") as Nothing
        assertTrue(
            "кадр дзвінка зі сховка знову горить мовчки — виклик, що не знайшов прямої " +
                "дороги, не розбудить нікого, і телефон не задзвонить",
            fn.contains("onCall("),
        )
        assertTrue(
            "межу між дзвінком і спогадом вигадали наново: вона вже оголошена як " +
                "PeerCall.RING_LIFE_MS — стільки живе сам виклик",
            fn.contains("RING_LIFE_MS"),
        )
    }

    /**
     * Сам спосіб дивитись теж мусить бути доведений: якщо вирізання коментарів
     * зламається, обидві перевірки почнуть наїдатися KDoc цього ж класу.
     */
    @Test
    fun `сторож не наїдається коментарем`() {
        val raw = File(repoRoot(), "app/src/main/java/local/phantom/companion/service/PeerCallNotifications.kt").readText()
        val cut = source("app/src/main/java/local/phantom/companion/service/PeerCallNotifications.kt")
        assertTrue(
            "з файлу зник пояснювальний коментар про німий канал — перевірка вище більше " +
                "нічого не доводить, перепиши її",
            raw.contains("phantom_peer_call_v1"),
        )
        assertTrue(
            "вирізання коментарів не працює: німе імʼя лишилось після очищення, отже " +
                "сторож рахує слова, а не код",
            !cut.contains("До 21.09"),
        )
    }
}
