package local.phantom.companion.ui.messenger

import local.phantom.companion.R
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.data.AppContainer
import org.json.JSONObject

/** Мої ПК і ноутбуки: не люди, а пристрої того самого власника. */
internal class WebMessengerMine(private val container: AppContainer) {

    @Volatile private var repaired = false

    fun isMine(p: PeerIdentity): Boolean {
        if (!repaired) repairPairedNode()
        return p.kind == PeerIdentity.Kind.DEVICE || looksLikeDevice(p.name)
    }

    // Спарений до 26.09 ПК ставав «людиною», щойно надсилав звичайне запрошення.
    private fun repairPairedNode() {
        repaired = true
        val node = runCatching { container.store.load()?.nodePeerId }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        if (container.peers.peers.value.any { it.id == node && it.kind == PeerIdentity.Kind.PERSON }) {
            container.peers.setKind(node, PeerIdentity.Kind.DEVICE)
        }
    }

    fun mark(body: String?, conversationOf: (String) -> JSONObject?): String {
        val o = body?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return fail(400, "bad_body", say(R.string.msgr_bridge_bad_body))
        val id = o.optString("peer_id")
        if (container.peers.peers.value.none { it.id == id }) {
            return fail(404, "no_contact", say(R.string.msgr_bridge_no_contact))
        }
        container.peers.setKind(id, if (o.optBoolean("mine")) PeerIdentity.Kind.DEVICE else PeerIdentity.Kind.PERSON)
        return conversationOf(id)?.let {
            JSONObject().put("ok", true).put("status", 200).put("data", it).toString()
        } ?: fail(404, "no_contact", say(R.string.msgr_bridge_no_contact))
    }

    // Типову назву ПК записано мовою дня спарювання; рядок читає її мовою сьогодні.
    fun shownName(p: PeerIdentity): String =
        if (p.kind == PeerIdentity.Kind.DEVICE) shownDeviceName(p.name, defaultPcNames, say(R.string.paired_pc_name)) else p.name

    private val defaultPcNames: Set<String> by lazy {
        val ctx = container.applicationContext
        listOf("en", "uk", "cs").map { tag ->
            val conf = android.content.res.Configuration(ctx.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(tag)) }
            ctx.createConfigurationContext(conf).getString(R.string.paired_pc_name)
        }.toSet()
    }

    private fun say(id: Int): String = container.applicationContext.getString(id)

    private fun fail(status: Int, code: String, detail: String): String =
        JSONObject().put("ok", false).put("status", status).put("code", code).put("detail", detail).toString()
}

/**
 * Test pairings left rows named «ПК-вузол», «Ноутбук», «ПК · PHANTOM OS» as PERSON.
 * Same rule as the web's `peopleIdentity.looksLikeDevice`, so both halves agree.
 * Cyrillic and Latin words sit in separate literals: the English-in-Ukrainian gate reads a literal as copy.
 */
private val DEVICE_NAME = Regex(
    "^(?:пк|ноутбук|комп[ʼ'’]?ютер)(?:\\b|[\\s·\\-–—]|$)|[-\\s]вузол$" +
        "|^(?:pc|laptop|desktop|computer)(?:\\b|[\\s·\\-–—]|$)|phantom\\s*os|^e2e\\b|[-_]reply[-_]|\\bnode[-_]|^s\\d[-_]",
    RegexOption.IGNORE_CASE,
)

internal fun looksLikeDevice(name: String?): Boolean = DEVICE_NAME.containsMatchIn(name.orEmpty().trim())

internal fun shownDeviceName(name: String, defaults: Set<String>, current: String): String =
    if (name.trim() in defaults) current else name
