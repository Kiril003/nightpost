package local.phantom.companion.core.net.peer

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Превʼю посилання, яке складає ВІДПРАВНИК.
 *
 * Одержувач за посиланням не ходить ніколи: інакше сайт дізнавався б, хто й
 * коли прочитав лист, і міг би віддати кожному свою картинку. Тож заголовок,
 * опис і маленьке зображення бере телефон того, хто посилання надіслав, і
 * везе їх тим самим наскрізним шифруванням окремим кадром
 * `PH-LPV:<id листа>;<json>`. Кадр стає не рядком, а частиною свого листа —
 * так само, як смайл ([PeerMark]): і прикладається лише тоді, коли його автор
 * той самий, що й автор листа, — чужий кадр не підмалює вам чужу картку.
 *
 * Збірка, що кадру не знає, просто бачить лист без картки.
 */
object PeerPreview {

    const val TAG = "PH-LPV:"

    const val MAX_TITLE = 200
    const val MAX_DESCRIPTION = 300
    const val MAX_SITE = 80
    /** Байтів мініатюри: JPEG до ~240 px. Більше — картка без зображення, а не довший кадр. */
    const val MAX_IMAGE_BYTES = 12 * 1024
    /** Тіло кадру цілком; конверт сховка тримає 64 КіБ ([PeerWire]). */
    const val MAX_BODY_CHARS = 20_000
    /** Сторінки читається не більше — заголовки живуть у `<head>`. */
    const val MAX_HTML_BYTES = 256 * 1024

    data class Preview(
        val url: String,
        val title: String,
        val description: String = "",
        val site: String = "",
        /** JPEG-мініатюра або null. */
        val image: ByteArray? = null,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Preview && url == other.url && title == other.title && description == other.description &&
                site == other.site && (image?.contentEquals(other.image ?: ByteArray(0)) ?: (other.image == null))

        override fun hashCode(): Int = listOf(url, title, description, site, image?.contentHashCode()).hashCode()
    }

    fun isPreview(body: String): Boolean = body.startsWith(TAG)

    private val URL = Regex("""https://[^\s<>"'«»]+""", RegexOption.IGNORE_CASE)

    /** Перше https-посилання в набраному; кінцева пунктуація речення — не частина адреси. */
    fun firstUrl(text: String): String? {
        val raw = URL.find(text)?.value ?: return null
        val trimmed = raw.trimEnd('.', ',', '!', '?', ':', ';', ')', ']', '…')
        return trimmed.takeIf { it.length > "https://".length && it.length <= 2048 }
    }

    private fun clip(s: String, max: Int): String {
        val one = s.replace(Regex("\\s+"), " ").trim()
        return if (one.length <= max) one else one.take(max - 1).trimEnd() + "…"
    }

    fun encode(targetId: String, p: Preview): String? {
        if (targetId.isBlank() || targetId.contains(';') || p.title.isBlank()) return null
        val o = buildJsonObject {
            put("u", p.url)
            put("t", clip(p.title, MAX_TITLE))
            if (p.description.isNotBlank()) put("d", clip(p.description, MAX_DESCRIPTION))
            if (p.site.isNotBlank()) put("s", clip(p.site, MAX_SITE))
            p.image?.takeIf { it.size <= MAX_IMAGE_BYTES }?.let { put("i", Base64.getEncoder().encodeToString(it)) }
        }
        val body = "$TAG$targetId;$o"
        return body.takeIf { it.length <= MAX_BODY_CHARS }
    }

    fun decode(body: String): Pair<String, Preview>? {
        if (!isPreview(body) || body.length > MAX_BODY_CHARS) return null
        val rest = body.removePrefix(TAG)
        val cut = rest.indexOf(';').takeIf { it > 0 } ?: return null
        val target = rest.substring(0, cut)
        val o = runCatching { Json.parseToJsonElement(rest.substring(cut + 1)) as? JsonObject }.getOrNull() ?: return null
        fun str(k: String): String = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
        val url = str("u").takeIf { it.startsWith("https://", ignoreCase = true) } ?: return null
        val title = str("t").takeIf { it.isNotBlank() } ?: return null
        val image = str("i").takeIf { it.isNotEmpty() }
            ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            ?.takeIf { it.size <= MAX_IMAGE_BYTES }
        return target to Preview(
            url = url,
            title = clip(title, MAX_TITLE),
            description = clip(str("d"), MAX_DESCRIPTION),
            site = clip(str("s"), MAX_SITE),
            image = image,
        )
    }

    /**
     * Картка листа [m] з журналу: останній кадр від АВТОРА листа, що вказує на
     * нього, і лише якщо адреса в картці справді є в тексті листа.
     */
    fun of(log: List<PeerMessage>, m: PeerMessage): Preview? =
        log.asSequence()
            .filter { it.fromId == m.fromId && isPreview(it.body) }
            .mapNotNull { decode(it.body) }
            .lastOrNull { (target, p) -> target == m.id && m.body.contains(p.url) }
            ?.second

    // ── читання сторінки ───────────────────────────────────────────────────

    data class Meta(val title: String, val description: String, val site: String, val imageUrl: String?)

    private val META = Regex("""<meta\s[^>]*>""", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("""([a-zA-Z:_-]+)\s*=\s*("([^"]*)"|'([^']*)')""")
    private val TITLE = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** Open Graph, потім Twitter, потім `<title>`. Без заголовка — картки немає. */
    fun parse(html: String, pageUrl: String): Meta? {
        val head = html.take(MAX_HTML_BYTES)
        val tags = META.findAll(head).map { tag ->
            ATTR.findAll(tag.value).associate { a ->
                a.groupValues[1].lowercase() to unescape(a.groupValues[3].ifEmpty { a.groupValues[4] })
            }
        }.toList()
        fun meta(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
            tags.firstOrNull { (it["property"] ?: it["name"])?.lowercase() == key }?.get("content")?.takeIf { it.isNotBlank() }
        }
        val title = meta("og:title", "twitter:title")
            ?: TITLE.find(head)?.groupValues?.get(1)?.let(::unescape)?.takeIf { it.isNotBlank() }
            ?: return null
        val image = meta("og:image:secure_url", "og:image", "twitter:image")?.let { resolve(pageUrl, it) }
        return Meta(
            title = clip(title, MAX_TITLE),
            description = clip(meta("og:description", "twitter:description", "description") ?: "", MAX_DESCRIPTION),
            site = clip(meta("og:site_name") ?: hostOf(pageUrl), MAX_SITE),
            imageUrl = image?.takeIf { it.startsWith("https://", ignoreCase = true) },
        )
    }

    fun hostOf(url: String): String =
        url.removePrefix("https://").removePrefix("HTTPS://").substringBefore('/').substringBefore('?')
            .substringAfter('@').removePrefix("www.")

    private fun resolve(base: String, ref: String): String = when {
        ref.startsWith("https://", ignoreCase = true) || ref.startsWith("http://", ignoreCase = true) -> ref
        ref.startsWith("//") -> "https:$ref"
        ref.startsWith("/") -> "https://" + hostOfRaw(base) + ref
        else -> base.substringBeforeLast('/') + "/" + ref
    }

    private fun hostOfRaw(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore('?')

    private fun unescape(s: String): String = s
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
}
