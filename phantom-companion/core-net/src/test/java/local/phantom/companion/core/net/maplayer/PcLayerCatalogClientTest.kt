package local.phantom.companion.core.net.maplayer

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The failure modes matter more than the happy path here — this client is
 * the only thing standing between a phone showing an honest reason and a
 * blank/frozen layer row. No real network: every response comes from
 * [MockEngine].
 */
class PcLayerCatalogClientTest {

    private fun json(body: String, status: HttpStatusCode = HttpStatusCode.OK) = MockEngine { _ ->
        respond(content = body, status = status, headers = headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private fun clientWith(engine: MockEngine) =
        PcLayerCatalogClient(pinnedCertSha256 = { "irrelevant-in-tests" }, engine = engine)

    private val bounds = PcLayerBounds(southLat = 50.0, westLon = 30.0, northLat = 51.0, eastLon = 31.0)

    // ── catalog ─────────────────────────────────────────────────────────

    @Test
    fun `catalog keeps only local_db, bbox-queried, offline-marked layers`() = runTest {
        val body = """
            {"layers":[
              {"id":"cliff_scree","name_ua":"Скелі та осипи","category":"terrain",
               "available_offline":true,"source":{"type":"local_db","bbox_required":true}},
              {"id":"air_raid_ua","name_ua":"Повітряна тривога","category":"live",
               "available_offline":false,"source":{"type":"rest_polling","bbox_required":false}},
              {"id":"cell_towers","name_ua":"Вежі стільникового звʼязку","category":"infra",
               "available_offline":true,"source":{"type":"local_db","bbox_required":false}}
            ],"total":3}
        """.trimIndent()
        val src = clientWith(json(body))

        val layers = src.listPullableLayers("https://pc.local", "jwt")

        assertEquals(1, layers.size)
        assertEquals("cliff_scree", layers.first().id)
    }

    @Test
    fun `an empty catalog is a legitimate answer, not an error`() = runTest {
        val src = clientWith(json("""{"layers":[],"total":0}"""))

        val layers = src.listPullableLayers("https://pc.local", "jwt")

        assertTrue(layers.isEmpty())
    }

    @Test
    fun `a 401 on the catalog names access, not a generic failure`() = runTest {
        val src = clientWith(json("{}", HttpStatusCode.Unauthorized))

        val ex = runCatching { src.listPullableLayers("https://pc.local", "jwt") }.exceptionOrNull()

        assertTrue(ex is PcLayerLinkException)
        assertTrue(ex!!.message!!.contains("доступі"))
    }

    @Test
    fun `malformed catalog JSON is rejected with a reason, not a crash string`() = runTest {
        val src = clientWith(json("{ not json"))

        val ex = runCatching { src.listPullableLayers("https://pc.local", "jwt") }.exceptionOrNull()

        assertTrue(ex is PcLayerLinkException)
    }

    // ── extract ─────────────────────────────────────────────────────────

    @Test
    fun `a successful extract carries the raw body, its feature count and its byte size`() = runTest {
        val body = """{"type":"FeatureCollection","features":[{"a":1},{"a":2}],"total":2}"""
        val src = clientWith(json(body))

        val result = src.fetchExtract("https://pc.local", "jwt", "cliff_scree", bounds)

        assertTrue(result is PcLayerCatalogClient.ExtractResult.Success)
        val success = result as PcLayerCatalogClient.ExtractResult.Success
        assertEquals(2, success.featureCount)
        assertEquals(body.toByteArray(Charsets.UTF_8).size.toLong(), success.bytes)
        assertEquals(body, success.rawJson)
    }

    @Test
    fun `a missing total falls back to counting the features array`() = runTest {
        val body = """{"type":"FeatureCollection","features":[{"a":1},{"a":2},{"a":3}]}"""
        val src = clientWith(json(body))

        val result = src.fetchExtract("https://pc.local", "jwt", "cliff_scree", bounds)

        assertTrue(result is PcLayerCatalogClient.ExtractResult.Success)
        assertEquals(3, (result as PcLayerCatalogClient.ExtractResult.Success).featureCount)
    }

    @Test
    fun `a 404 reads as the PC not having wired this layer yet, not a generic error`() = runTest {
        val src = clientWith(json("Not Found", HttpStatusCode.NotFound))

        val result = src.fetchExtract("https://pc.local", "jwt", "drains_ditches", bounds)

        assertTrue(result is PcLayerCatalogClient.ExtractResult.Failure)
        assertTrue((result as PcLayerCatalogClient.ExtractResult.Failure).message.contains("не ввімкнув"))
    }

    @Test
    fun `a 401 on the extract names access, matching the catalog's wording style`() = runTest {
        val src = clientWith(json("{}", HttpStatusCode.Unauthorized))

        val result = src.fetchExtract("https://pc.local", "jwt", "cliff_scree", bounds)

        assertTrue(result is PcLayerCatalogClient.ExtractResult.Failure)
        assertTrue((result as PcLayerCatalogClient.ExtractResult.Failure).message.contains("доступі"))
    }

    @Test
    fun `an unparseable body fails closed instead of claiming zero features`() = runTest {
        val src = clientWith(json("""{"type":"FeatureCollection"}"""))

        val result = src.fetchExtract("https://pc.local", "jwt", "cliff_scree", bounds)

        assertTrue(result is PcLayerCatalogClient.ExtractResult.Failure)
    }

    @Test
    fun `a broken connection resolves to Failure, never an uncaught exception`() = runTest {
        val src = clientWith(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) })

        val result = try {
            src.fetchExtract("https://pc.local", "jwt", "cliff_scree", bounds)
        } catch (t: Throwable) {
            fail("fetchExtract must never throw — got $t")
            return@runTest
        }

        assertTrue(result is PcLayerCatalogClient.ExtractResult.Failure)
    }
}
