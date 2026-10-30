package local.phantom.companion.data.nearby

/**
 * Що їде між двома телефонами поруч. Усередині кадру — той самий конверт
 * [local.phantom.companion.core.net.peer.PeerEnvelope], що й по LAN, під
 * ключем пари; назад — квитанція, яку без цього ключа не скласти.
 */
sealed interface NearbyPacket {
    data class Frame(val frame: String) : NearbyPacket
    data class Receipt(val receipt: String) : NearbyPacket

    companion object {
        /** Межа одного BYTES-пакета Nearby Connections. */
        const val MAX_BYTES = 32 * 1024

        private const val FRAME = "PHN1F"
        private const val RECEIPT = "PHN1R"

        fun encode(packet: NearbyPacket): ByteArray? {
            val text = when (packet) {
                is Frame -> FRAME + packet.frame
                is Receipt -> RECEIPT + packet.receipt
            }
            return text.toByteArray(Charsets.UTF_8).takeIf { it.size <= MAX_BYTES }
        }

        fun decode(bytes: ByteArray?): NearbyPacket? {
            if (bytes == null || bytes.isEmpty() || bytes.size > MAX_BYTES) return null
            val text = String(bytes, Charsets.UTF_8)
            return when {
                text.startsWith(FRAME) -> text.removePrefix(FRAME).takeIf { it.isNotBlank() }?.let(::Frame)
                text.startsWith(RECEIPT) -> text.removePrefix(RECEIPT).takeIf { it.isNotBlank() }?.let(::Receipt)
                else -> null
            }
        }
    }
}
