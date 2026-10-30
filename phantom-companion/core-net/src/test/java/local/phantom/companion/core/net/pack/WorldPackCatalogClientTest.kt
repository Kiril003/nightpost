package local.phantom.companion.core.net.pack

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldPackCatalogClientTest {

    private val signer = WorldPackSigner()

    private fun client(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        base: String = "https://packs.example",
        key: String = signer.publicHex,
        onRequest: () -> Unit = {},
    ) = WorldPackCatalogClient(
        baseUrl = { base },
        publicKeyHex = { key },
        engine = MockEngine { onRequest(); respond(body, status) },
    )

    private val oneRow = """
        {"packs":[
          {"id":"basemap-ua-detailed","kind":"BASEMAP","country":"UA",
           "labelUa":"Україна — основа (детально)","bytes":1300000000,
           "bakedAt":"2026-09-01T00:00:00Z","minAppBuild":140}
        ]}
    """.trimIndent()

    @Test
    fun `empty base is NotConfigured — never a request nobody asked for`() = runTest {
        var requested = false
        val c = client(signer.sign(oneRow), base = "", onRequest = { requested = true })
        assertEquals(WorldCatalog.NotConfigured, c.catalog())
        assertTrue(!requested)
    }

    @Test
    fun `a build with no trust anchor never spends bytes on a catalog it cannot accept`() = runTest {
        var requested = false
        val c = client(signer.sign(oneRow), key = "", onRequest = { requested = true })
        assertTrue(c.catalog() is WorldCatalog.Unavailable)
        assertTrue("каталог качали ключем, якого немає", !requested)
    }

    @Test
    fun `a signed catalog is parsed into entries`() = runTest {
        val result = client(signer.sign(oneRow)).catalog() as WorldCatalog.Ok
        assertEquals(1, result.packs.size)
        assertEquals("basemap-ua-detailed", result.packs.single().id)
    }

    @Test
    fun `the same catalog without its signature is refused`() = runTest {
        assertTrue(client(oneRow).catalog() is WorldCatalog.Unavailable)
    }

    @Test
    fun `a catalog signed by somebody else is refused`() = runTest {
        val stranger = WorldPackSigner()
        assertTrue(client(stranger.sign(oneRow)).catalog() is WorldCatalog.Unavailable)
    }

    @Test
    fun `one row swapped after signing is enough to refuse the whole catalog`() = runTest {
        val tampered = signer.sign(oneRow).replace("1300000000", "42")
        assertTrue(client(tampered).catalog() is WorldCatalog.Unavailable)
    }

    @Test
    fun `a row without a declared size is dropped, not shown as zero`() = runTest {
        val body = """{"packs":[{"id":"x","kind":"BASEMAP","country":"UA","labelUa":"Х","bytes":0}]}"""
        val result = client(signer.sign(body)).catalog() as WorldCatalog.Ok
        assertTrue(result.packs.isEmpty())
    }

    @Test
    fun `malformed JSON is an honest Unavailable, never a crash`() = runTest {
        assertTrue(client("not json").catalog() is WorldCatalog.Unavailable)
    }

    @Test
    fun `a 404 host is Unavailable, not an empty catalog`() = runTest {
        assertTrue(client("нема", status = HttpStatusCode.NotFound).catalog() is WorldCatalog.Unavailable)
    }
}
