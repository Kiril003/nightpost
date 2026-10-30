package local.phantom.companion.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Каталог у коді звіряється з тим, що СПРАВДІ лежить у роздачі.
 *
 * Фікстура `models-SHA256SUMS.txt` — копія `SHA256SUMS.txt` із кореня роздачі
 * (`phantom-oracle:~/road-mesh-bake/serve/models/`), знята 12.09. Формат:
 * `<sha256>  <байти>  <ключ>`.
 *
 * ДВІ СИСТЕМИ ВІДЛІКУ, і плутати їх не можна: ключі маніфеста відносні до самої
 * теки `models/` (бо він у ній і лежить), а ключі каталогу — до кореня сайту,
 * тобто несуть `models/` у собі. Тому тут префікс знімається перед звіркою.
 *
 * Навіщо саме так: 12.09 у каталозі стояв `voice_styles/M1.json`, якого в
 * роздачі немає взагалі, і поруч — Vosk, що вів на alphacephei.com. Обидва
 * читалися як робочі записи. «Шлях є в коді, а в роздачі нема» не видно ні
 * компілятором, ні оком — лише звіркою з джерелом правди.
 *
 * Тест НЕ ходить у мережу: він порівнює два переліки. Живу роздачу доводить
 * curl бригади пакетів, не ворота.
 */
class CatalogMatchesStorageTest {

    private data class Entry(val sha: String, val bytes: Long)

    private val storage: Map<String, Entry> by lazy {
        val text = javaClass.classLoader!!
            .getResourceAsStream("models-SHA256SUMS.txt")
            ?.bufferedReader()?.readText()
            ?: error("фікстура models-SHA256SUMS.txt зникла — звіряти нема з чим")
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val p = line.split(Regex("\\s+"))
                if (p.size < 3) null else p[2] to Entry(p[0], p[1].toLong())
            }
            .toMap()
    }

    /** Ключі, які код обіцяє взяти з нашої роздачі. */
    private fun ourKeys(): List<Triple<String, String?, Long?>> =
        ModelType.entries.flatMap { type ->
            val parts = type.parts.filter { it.isOurs }
                .map { Triple(it.url, it.sha256, it.bytes) }
            val single = type.downloadUrl
                ?.takeIf { !it.startsWith("http") }
                ?.let { listOf(Triple(it, type.downloadSha256, type.downloadBytes.takeIf { b -> b > 0 })) }
                .orEmpty()
            parts + single
        }

    @Test
    fun `кожен наш ключ існує в роздачі`() {
        val missing = ourKeys().map { it.first }
            .filterNot { storage.containsKey(it.removePrefix("models/")) }
        assertTrue(
            "каталог обіцяє файли, яких у роздачі немає — саме так жив «voice_styles/M1.json»:\n" +
                missing.joinToString("\n") { "  $it" } +
                "\nУ роздачі є: " + storage.keys.sorted().joinToString(", "),
            missing.isEmpty(),
        )
    }

    @Test
    fun `суми й розміри збігаються з роздачею`() {
        val drift = mutableListOf<String>()
        for ((key, sha, bytes) in ourKeys()) {
            val real = storage[key.removePrefix("models/")] ?: continue
            if (sha != null && !sha.equals(real.sha, ignoreCase = true)) {
                drift += "  $key: у коді sha $sha, у роздачі ${real.sha}"
            }
            if (bytes != null && bytes != real.bytes) {
                drift += "  $key: у коді $bytes Б, у роздачі ${real.bytes} Б"
            }
        }
        assertTrue("каталог розійшовся з роздачею:\n" + drift.joinToString("\n"), drift.isEmpty())
    }

    /**
     * Маніфест не говорить про себе.
     *
     * 12.09 у ньому стояв рядок `…  298  SHA256SUMS.txt` — твердження, хибне
     * ЗА ПОБУДОВОЮ: щойно у файл дописують рядок, його власні сума й розмір
     * змінюються, і запис перестає сходитись мовчки. На момент виміру файл
     * важив 1695 Б проти обіцяних 298. Суму самого маніфеста несе окремий
     * файл поруч (`SHA256SUMS.txt.sha256`) — там вона може бути правдою.
     *
     * Той самий клас, що й решта сьогоднішніх: перевірка, яка описує не той
     * предмет, що міряє.
     */
    @Test
    fun `маніфест не містить рядка про самого себе`() {
        assertTrue(
            "у фікстурі є запис про SHA256SUMS.txt — він хибний за побудовою, " +
                "бо змінюється від власного дописування; перезніміть із живого файла",
            storage.keys.none { it == "SHA256SUMS.txt" || it.endsWith("/SHA256SUMS.txt") },
        )
    }

    @Test
    fun `вухо береться з нашої роздачі, а не з чужого хоста`() {
        val vosk = ModelType.VOSK.downloadUrl
        assertTrue("Vosk знову тягнеться з чужого хоста: $vosk", vosk?.startsWith("http") != true)
        assertEquals("models/vosk/vosk-model-small-uk-v3-small.zip", vosk)
        assertEquals(
            "ee05b6a8c796c53c61cd1c821d38e897e5bd67b4d691d8797ec8f9e5100d4247",
            ModelType.VOSK.downloadSha256,
        )
        assertEquals(143_914_407L, ModelType.VOSK.downloadBytes)
    }
}
