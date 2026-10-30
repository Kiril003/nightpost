package local.phantom.companion.data.intent

/**
 * Двері, якими SMS-приймач збірки `field` віддає SMS моїх телефонів
 * домашньому шлюзу. Хто тримає шлюз, той ставить [sink]. Повертається, коли
 * шлюз закінчив (відповідь пішла SMS або кадр поклався в сховок).
 */
object GatewayInbox {

    @Volatile
    var sink: (suspend (from: String, text: String) -> Unit)? = null

    suspend fun deliver(from: String, text: String) {
        runCatching { sink?.invoke(from, text) }
    }
}
