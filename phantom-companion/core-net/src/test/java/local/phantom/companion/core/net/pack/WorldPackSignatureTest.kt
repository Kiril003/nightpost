package local.phantom.companion.core.net.pack

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.io.File
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.net.crypto.bytesToHex
import local.phantom.companion.core.net.crypto.sha256
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ВАДА 07.09: світові паки їхали БЕЗ ПІДПИСУ.
 *
 * [WorldPackGate.inspect] звіряв рівно дві речі — що розмір додатний і що
 * рядок sha256 має форму шістнадцяткового. Обидві бере з ТОГО САМОГО
 * документа, який і перевіряється: манiфест, який сам собі оголошує суму
 * файла, доводить лише те, що дзеркало вміє рахувати sha256. Поля підпису в
 * `WorldPackManifestDoc` не було взагалі, а контракт [PackGate.inspect]
 * обіцяв «ЗВІРИТИ ПІДПИС» великими літерами.
 *
 * Наслідок вимірюється одним реченням: будь-яке дзеркало могло підмінити
 * дорожній граф разом із самоузгодженим манiфестом, і телефон прийняв би це
 * як свій. Поки роздача йде з нашої машини, ціна мала; дзеркала на чужих
 * машинах без цієї перевірки неможливі.
 *
 * Постава та сама, що в паках країн (`PackInstaller`) і в ліцензійних
 * воротах: ПОРОЖНІЙ ключ звірки означає «не приймається жоден завантажений
 * пак», а не «приймаються всі».
 */
class WorldPackSignatureTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class Fake(root: File) : WorldPackGate(root) {
        override fun applyVerifiedFile(packId: String, file: File) = Unit
    }

    private fun hash(bytes: String) = bytesToHex(sha256(bytes.toByteArray()))

    private fun unsignedManifest() =
        """{"id":"road-mesh-ua","version":1,"bytes":41000000,"sha256":"${hash("payload")}"}"""

    private fun unsignedCatalog() = """
        {"packs":[
          {"id":"road-mesh-ua","kind":"ROAD_MESH","country":"UA",
           "labelUa":"Україна — дороги","bytes":41000000}
        ]}
    """.trimIndent()

    @Test
    fun `an unsigned manifest is refused — a mirror must not be able to swap the road graph`() {
        val outcome = Fake(temp.newFolder("root")).inspect(unsignedManifest())
        assertTrue(
            "манiфест без підпису прийнято: дзеркало може підмінити дорожній граф",
            outcome is PackInspection.Refused,
        )
    }

    @Test
    fun `an unsigned catalog is Unavailable, never a browsable list`() = runTest {
        val client = WorldPackCatalogClient(
            baseUrl = { "https://packs.example" },
            engine = MockEngine { respond(unsignedCatalog(), HttpStatusCode.OK) },
        )
        assertTrue(
            "каталог без підпису показано людині як справжній",
            client.catalog() is WorldCatalog.Unavailable,
        )
    }
}
