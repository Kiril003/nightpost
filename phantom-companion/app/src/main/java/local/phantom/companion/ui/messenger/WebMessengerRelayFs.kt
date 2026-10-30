package local.phantom.companion.ui.messenger

/**
 * `/messenger/relayfs` — «Профіль › Для розробника › Пряма секретність сховку (v5)»
 * (docs/crypto/RELAY_FS.md §10).
 *
 *   GET  /messenger/relayfs        стан: прапорець, мій SPK, ключі по людях, печатка останнього листа
 *   POST /messenger/relayfs/on     увімкнути (переживає перезапуск), відповідь — новий стан
 *   POST /messenger/relayfs/off    вимкнути
 *
 * Без Android-типів, щоб відповідь доводилась звичайним JUnit: стан уже JSON
 * (`RelayFsStatus.json`), тут лише конверт мосту.
 */
class WebMessengerRelayFs(private val port: Port) {

    interface Port {
        /** `RelayFsStatus.json(...)`. */
        fun view(): String
        fun setEnabled(on: Boolean)
    }

    fun claims(seg: List<String>): Boolean = seg.size >= 2 && seg[1] == SEGMENT

    fun handle(method: String, seg: List<String>): String = runCatching {
        when {
            seg.size == 2 && method == "GET" -> ok(port.view())
            seg.size == 3 && method == "POST" && seg[2] in setOf("on", "off") -> {
                port.setEnabled(seg[2] == "on")
                ok(port.view())
            }
            else -> fail(404, "not_found")
        }
    }.getOrElse { fail(500, "relayfs_failed") }

    private fun ok(data: String): String = """{"ok":true,"status":200,"data":$data}"""

    private fun fail(status: Int, code: String): String = """{"ok":false,"status":$status,"code":"$code"}"""

    companion object {
        const val SEGMENT = "relayfs"
    }
}
