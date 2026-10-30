package local.phantom.companion.data.intent

/**
 * Двері, якими SMS-приймач збірки `field` віддає SMS від мого шлюзу клієнтові
 * Намірів. Приймач живе у флейворі й не знає контейнера; хто тримає клієнта,
 * той і ставить [sink]. Немає [sink] — SMS лишається системі, як звичайна.
 */
object IntentInbox {

    @Volatile
    var sink: ((String) -> Boolean)? = null

    /** True — це була відповідь на моє прохання. */
    fun deliver(text: String): Boolean = runCatching { sink?.invoke(text) ?: false }.getOrDefault(false)
}
