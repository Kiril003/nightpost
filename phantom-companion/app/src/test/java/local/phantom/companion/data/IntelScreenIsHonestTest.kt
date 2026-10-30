package local.phantom.companion.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Аудит 05.09 знайшов «Розвідку» цілком макетом: сім лямбд, нуль даних, і
 * літерали, що вдавали вигадані інсайти («Мілан», «Токіо»), сигнали («Q3»),
 * вузли та підпис пошуку. Заодно — порожня кнопка зміни фото в профілі,
 * мертвий графік і порада сховку про мітки з мапи, яких мапа вже не пише.
 *
 * 05.09, друга хвиля: макет прибрано не заміною на новий рендер, а
 * підключенням до реєстру кімнат/людей/памʼяті, який довго стояв написаним і
 * недосяжним за мертвим `PeopleBehindLock` (`MeshGateReachTest` документує
 * саму недосяжність). Писати реєстр удруге означало б розійтись із цим кодом
 * за тиждень.
 *
 * 06.09 той реєстр звузився й перейменувався: `PeopleScreen` став
 * `PeerKeysScreen` — люди й ключі, без жодної розмови. Розмови жили на ньому
 * другим списком поруч із веб-месенджером, і власник відкрив застосунок і
 * побачив обидва. Файл лишився `PeopleScreen.kt`; сюди дивиться саме імʼя
 * ФУНКЦІЇ, бо вигадку повертає виклик, а не назва файла.
 *
 * 02.10 «Розвідку» і «Стратегію» знято як недосяжні разом з їхніми перевірками;
 * решта сторожів цього файла стереже живе.
 *
 * 04.10 (profile-hub) `PeopleScreen.kt` пішов цілком: `PeerKeysScreen` не
 * складав жоден маршрут, а разом із ним пішли `IntelSections.kt` (кімнати,
 * люди, памʼять реєстру) і `PeerHandshakeSheet.kt`. Перевірки нижче на ці файли
 * не дивились — вони стережуть ресурси й навграф.
 *
 * Тест дивиться на ПРОВОДКУ й на текст ресурсів, а не на компільованість:
 * вигадка компілюється так само чисто, як правда.
 */
class IntelScreenIsHonestTest {

    private val root = File("../").canonicalFile

    private fun read(rel: String): String {
        val f = File(root, rel)
        assertTrue("немає файла $rel", f.isFile)
        return f.readText()
    }

    @Test
    fun `вигадані рядки Розвідки прибрано з обох locale`() {
        for (path in listOf(
            "feature-stream/src/main/res/values-uk/strings_intel.xml",
            "feature-stream/src/main/res/values/strings_intel.xml",
        )) {
            val xml = read(path)
            for (key in listOf(
                "intel_insight_1_title", "intel_insight_2_title",
                "intel_feed_1_title", "intel_feed_2_title",
                "intel_node_project_a", "intel_node_new",
            )) {
                assertFalse("$path усе ще містить вигаданий $key", xml.contains("name=\"$key\""))
            }
        }
    }

    @Test
    fun `зміна фото профілю щось робить`() {
        val tab = read("app/src/main/java/local/phantom/companion/ui/home/ProfileTab.kt")
        assertTrue("дотик по фото не відкриває галерею", tab.contains("onPhoto = { photoPicker.launch(images) }"))
        assertTrue("вибране фото нікуди не пишеться", tab.contains("savePhoto(context, face, uri)") && tab.contains("face.setPhoto(thumb, photo)"))
    }

    @Test
    fun `порада сховку про мітки з мапи прибрана в обох locale`() {
        for (path in listOf(
            "feature-stream/src/main/res/values-uk/strings.xml",
            "feature-stream/src/main/res/values/strings.xml",
        )) {
            val xml = read(path)
            assertFalse(
                "$path усе ще обіцяє «зберегти» на мітці мапи — SavedPlaceMigration стирає ці рядки на кожному завантаженні",
                xml.contains("name=\"vault_empty_how_map\""),
            )
            assertFalse(
                "$path усе ще має групу vault_group_place, якої більше нема в VaultKind",
                xml.contains("name=\"vault_group_place\""),
            )
        }
    }

    @Test
    fun `VaultKind Place прибрано з порядку читання`() {
        val catalog = read(
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/vault/VaultCatalog.kt",
        )
        assertFalse(
            "VaultKind.Place більше не існує — мітки живуть у saved_places, а Vault-рядок стирається на кожному завантаженні",
            Regex("""\bPlace\s*[,;(]""").containsMatchIn(catalog) || catalog.contains("VaultKind.Place"),
        )
    }
}
