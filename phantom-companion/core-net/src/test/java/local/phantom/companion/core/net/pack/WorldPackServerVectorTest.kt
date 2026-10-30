package local.phantom.companion.core.net.pack

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ОДИН контракт підпису пакетів для телефона й сервера — доведений спільним
 * вектором.
 *
 * Той самий файл лежить у двох деревах (`platform-site/server/tests/vectors/
 * world_pack_vector.json`) і підписаний СПРАВЖНІМ підписувачем сервера
 * (`sign_world_packs.py`). Власний тест-підписувач у Kotlin доводив би лише те,
 * що наша криптографія дорівнює сама собі; розходження канонізації двох мов
 * видно тільки звідси.
 *
 * Розійтись вони можуть найтихіше саме на українській назві: перед підписом
 * кожен символ поза ASCII екранується як `\uXXXX`, і довге тире в
 * «Україна — дороги» проходить цим шляхом. Зелене тут означає, що дзеркало,
 * підписане нашим ключем на сервері, телефон таки прийме.
 */
class WorldPackServerVectorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val vector = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader?.getResourceAsStream(VECTOR)) {
            "вектор $VECTOR не знайдено у test resources"
        }.reader(Charsets.UTF_8).readText(),
    ).jsonObject

    private val publicKey =
        vector.getValue("ed25519").jsonObject.getValue("pub_hex").jsonPrimitive.content
    private val catalogJson = vector.getValue("world_catalog_json").jsonPrimitive.content
    private val manifestJson = vector.getValue("manifest_json").jsonPrimitive.content

    private class Fake(root: File, key: String, build: Int) : WorldPackGate(root, { key }, { build }) {
        override fun applyVerifiedFile(packId: String, file: File) = Unit
    }

    /** 140 — рівно той поріг, що стоїть у векторі: межа, а не запас. */
    private fun gate(key: String = publicKey, build: Int = 140) = Fake(temp.newFolder(), key, build)

    @Test
    fun `a manifest the real server signed is accepted here`() {
        val want = (gate().inspect(manifestJson) as PackInspection.Ready).want
        assertEquals("road-mesh-ua", want.packId)
        assertEquals(3, want.version)
        assertEquals(41_000_000L, want.bytes)
    }

    /** Поле `minAppBuild` справжнього манiфесту не лише читається — звіряється. */
    @Test
    fun `the real manifest's build floor is enforced on an older build`() {
        val outcome = gate(build = 139).inspect(manifestJson)
        assertEquals(
            "пак просить збірку 140, а ця — 139: спершу оновіть застосунок",
            (outcome as PackInspection.Refused).reason.detail,
        )
    }

    @Test
    fun `one changed digit in the real manifest is enough to reject it`() {
        val bumped = manifestJson.replace("41000000", "410000")
        assertTrue(gate().inspect(bumped) is PackInspection.Refused)
    }

    @Test
    fun `a catalog the real server signed is browsable here`() = runTest {
        val client = WorldPackCatalogClient(
            baseUrl = { "https://packs.example" },
            publicKeyHex = { publicKey },
            engine = MockEngine { respond(catalogJson, HttpStatusCode.OK) },
        )
        val packs = (client.catalog() as WorldCatalog.Ok).packs
        assertEquals("road-mesh-ua", packs.single().id)
        assertEquals("Україна — дороги й транспорт", packs.single().labelUa)
    }

    /** Довге тире й кирилиця йдуть у підпис екранованими — тут це й міряється. */
    @Test
    fun `one letter of the Ukrainian label is enough to reject the real catalog`() = runTest {
        val bumped = catalogJson.replace("Україна", "Украіна")
        val client = WorldPackCatalogClient(
            baseUrl = { "https://packs.example" },
            publicKeyHex = { publicKey },
            engine = MockEngine { respond(bumped, HttpStatusCode.OK) },
        )
        assertTrue(client.catalog() is WorldCatalog.Unavailable)
    }

    private companion object {
        const val VECTOR = "world_pack_vector.json"
    }
}
