package local.phantom.companion.ui.messenger

import java.net.URLEncoder

// Скарга — лист людини в підтримку. Тексту листування в ньому немає: він наскрізно
// зашифрований, і сервер його не бачив; лише ідентифікатори, за якими людина
// зможе показати переписку сама, якщо захоче.
object AbuseReport {
    const val ADDRESS = "support@phantom-os.dev"

    fun mailto(subject: String, body: String): String =
        "mailto:$ADDRESS?subject=${enc(subject)}&body=${enc(body)}"

    // URLEncoder кодує пробіл як «+», а поштовики показують його буквально.
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun body(intro: String, peerNodeId: String, messageId: String?, build: String): String = buildString {
        appendLine(intro)
        appendLine()
        appendLine("peer: $peerNodeId")
        if (!messageId.isNullOrBlank()) appendLine("message: $messageId")
        append("build: $build")
    }
}
