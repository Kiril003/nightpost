package local.phantom.companion.core.ai.route

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.BrainProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Мова інтерфейсу їде до розуму сервера заголовком, не полем тіла: живий сервер
 * пересилає тіло в Google як є, і невідоме поле «lang» там — 400. Заголовок він
 * просто не читає, тож нова збірка безпечна і до розгортання сервера.
 */
class BrainLangHeaderTest {

    private val seen = mutableListOf<HttpRequestData>()
    private val sse = """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]}}]}""" + "\n\n"

    @Before
    fun reset() {
        seen.clear()
        BrainProfiles.restore(null)
    }

    private fun client() = HttpClient(MockEngine { req ->
        seen += req
        if (req.url.encodedPath.endsWith("/api/brain/status")) {
            respond("""{"brain":true}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        } else {
            respond(sse, HttpStatusCode.OK, headersOf("Content-Type", "text/event-stream"))
        }
    }) { expectSuccess = false }

    private fun route(lang: () -> String?) = NightpostBrainRoute(
        base = "https://brain.test",
        installId = { "AbCdEfGhIjKlMnOpQrStUv" },
        allowed = { true },
        httpClient = client(),
        nowMs = { 0L },
        uiLanguage = lang,
    )

    private fun post() = seen.single { it.method == HttpMethod.Post }
    private fun bodyOf(req: HttpRequestData) = (req.body as TextContent).text

    @Test
    fun `the ui language rides in X-Nightpost-Lang and never in the body`() = runTest {
        route { "uk" }.complete(AIIntent.Text(prompt = "ok")).toList()
        assertEquals("uk", post().headers[NightpostBrainRoute.LANG_HEADER])
        assertFalse(bodyOf(post()).contains("\"lang\""))
    }

    @Test
    fun `czech and english pass, region tags fold to the language`() = runTest {
        route { "cs" }.complete(AIIntent.Text(prompt = "ok")).toList()
        assertEquals("cs", post().headers[NightpostBrainRoute.LANG_HEADER])
        seen.clear()
        route { "en-GB" }.complete(AIIntent.Text(prompt = "ok")).toList()
        assertEquals("en", post().headers[NightpostBrainRoute.LANG_HEADER])
    }

    @Test
    fun `wave languages pass so a German messenger gets German answers`() = runTest {
        for ((asked, sent) in listOf("de" to "de", "pt-BR" to "pt", "id" to "id", "tr" to "tr")) {
            seen.clear()
            route { asked }.complete(AIIntent.Text(prompt = "ok")).toList()
            assertEquals(asked, sent, post().headers[NightpostBrainRoute.LANG_HEADER])
        }
    }

    @Test
    fun `an unknown or missing language sends no header`() = runTest {
        route { "xx" }.complete(AIIntent.Text(prompt = "ok")).toList()
        assertNull(post().headers[NightpostBrainRoute.LANG_HEADER])
        seen.clear()
        route { null }.complete(AIIntent.Text(prompt = "ok")).toList()
        assertNull(post().headers[NightpostBrainRoute.LANG_HEADER])
    }
}
