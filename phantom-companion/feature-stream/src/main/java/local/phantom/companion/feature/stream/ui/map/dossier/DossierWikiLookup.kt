package local.phantom.companion.feature.stream.ui.map.dossier

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * P0 — пошук статті ЗА ІДЕНТИФІКАТОРОМ сутності, а не за координатою.
 *
 * `PlaceDossierClient.fetchWiki` уміє рівно одне: геопошук у радіусі 400 м.
 * Для сутності цього замало навіть теоретично — навколо бібліотеки завжди
 * знайдеться ближча стаття (проспект, район, сусідня памʼятка), і жодне
 * сортування за відстанню не зробить її статтею про бібліотеку.
 *
 * Тому тут два запити, які закривають саме сутнісний випадок:
 *  • [byTitle] — стаття за назвою з тегу `wikipedia` (з редиректами);
 *  • [titleForWikidata] — Q-ідентифікатор → назва статті в українському
 *    (або, як запасний, англійському) розділі.
 *
 * ЧОМУ це живе тут, а не в `:core-net`. Клієнт у `core-net` належить іншій
 * території і ділиться з `place.info` та NAV-6; додавати туди метод під час
 * паралельної роботи означало б правити чужий контракт. Реалізація свідомо
 * мінімальна (один `httpGet`, ті самі keyless-джерела, той самий
 * `User-Agent`) і ховається за інтерфейсом, тож коли клієнт у `core-net`
 * навчиться цих двох запитів, [MediaWikiEntityLookup] просто зникає.
 */
internal interface WikiEntityLookup {

    /** Стаття за точною назвою (редиректи розгортаються). */
    suspend fun byTitle(ref: WikiTitleRef): DossierArticle?

    /** Q-ідентифікатор → стаття в найкращому доступному розділі. */
    suspend fun titleForWikidata(wikidataId: String): WikiTitleRef?
}

/** Стаття у тому вигляді, в якому її показує досьє. */
internal data class DossierArticle(
    val title: String,
    val extract: String,
    val thumbnailUrl: String?,
    val pageUrl: String,
    /** `wikibase_item` статті — доказ тотожності проти тегу `wikidata`. */
    val wikidataId: String?,
) {
    fun asCandidate(fromEntityTag: Boolean): WikiCandidate =
        WikiCandidate(title, extract, wikidataId, fromEntityTag)
}

internal class MediaWikiEntityLookup(
    private val wikipediaHost: (String) -> String = { lang -> "https://$lang.wikipedia.org/w/api.php" },
    private val wikidataUrl: String = "https://www.wikidata.org/w/api.php",
) : WikiEntityLookup {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun byTitle(ref: WikiTitleRef): DossierArticle? {
        val encoded = URLEncoder.encode(ref.title, "UTF-8")
        val url = wikipediaHost(ref.lang) +
            "?action=query&format=json&redirects=1&titles=$encoded" +
            "&prop=extracts%7Cpageimages%7Cpageprops&ppprop=wikibase_item" +
            "&exintro=1&explaintext=1&piprop=thumbnail&pithumbsize=640"
        val body = httpGet(url) ?: return null
        return runCatching {
            val pages = json.parseToJsonElement(body).jsonObject["query"]?.jsonObject
                ?.get("pages")?.jsonObject?.values?.mapNotNull { it as? JsonObject }
                ?: return null
            // `pages` містить ключ «-1» для неіснуючої статті — у неї нема
            // ані витягу, ані сторінкових властивостей, тож вона відсіється.
            pages.firstNotNullOfOrNull { page -> page.toArticle(ref.lang) }
        }.getOrNull()
    }

    override suspend fun titleForWikidata(wikidataId: String): WikiTitleRef? {
        val sites = PREFERRED_WIKI_LANGS.joinToString("%7C") { "${it}wiki" }
        val url = "$wikidataUrl?action=wbgetentities&format=json&props=sitelinks" +
            "&ids=$wikidataId&sitefilter=$sites"
        val body = httpGet(url) ?: return null
        return runCatching {
            val links = json.parseToJsonElement(body).jsonObject["entities"]?.jsonObject
                ?.get(wikidataId)?.jsonObject?.get("sitelinks")?.jsonObject ?: return null
            // Порядок переваги наш, а не той, у якому API віддало ключі.
            PREFERRED_WIKI_LANGS.firstNotNullOfOrNull { lang ->
                links["${lang}wiki"]?.jsonObject?.get("title")?.jsonPrimitive?.content
                    ?.takeIf { it.isNotBlank() }
                    ?.let { WikiTitleRef(lang, it) }
            }
        }.getOrNull()
    }

    private fun JsonObject.toArticle(lang: String): DossierArticle? {
        val title = this["title"]?.jsonPrimitive?.content ?: return null
        val extract = this["extract"]?.jsonPrimitive?.content?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return null
        return DossierArticle(
            title = title,
            extract = extract,
            thumbnailUrl = this["thumbnail"]?.jsonObject?.get("source")?.jsonPrimitive?.content,
            pageUrl = WikiTitleRef(lang, title).pageUrl,
            wikidataId = this["pageprops"]?.jsonObject?.get("wikibase_item")?.jsonPrimitive?.content,
        )
    }

    /** Той самий контракт, що й у `PlaceDossierClient`: тиха відмова = null. */
    private suspend fun httpGet(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
                connectTimeout = 8_000
                readTimeout = 12_000
            }
            try {
                if (conn.responseCode in 200..299) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    null
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private companion object {
        const val USER_AGENT = "PhantomCompanion/0.1 (operator)"
    }
}
