package local.phantom.companion.core.net.peer

import java.nio.ByteBuffer

/**
 * Положення для людей на мапі: один кадр незмінної довжини на всі роди.
 *
 * Тіло — `PH-WHR:` + base64url рівно [PAYLOAD_BYTES] байтів. Район, квартал,
 * точка, «стерти» й «де ти?» мають однакову довжину, тож і конверт
 * ([PeerEnvelope]) над ними однакової довжини: ні дріт, ні сховок не бачать,
 * яку точність людина дала саме цьому одержувачу.
 *
 * Район несе номер клітинки, а не координату — точної точки в ньому немає й
 * не може бути. Хвіст набивки нульовий, і [decode] відмовляє кадру з ненульовим
 * хвостом: сховати щось у набивці не вийде навіть у власного кодувальника.
 */
object PeerWhere {

    const val TAG = "PH-WHR:"
    const val VERSION = 1
    const val PAYLOAD_BYTES = 48
    const val BODY_CHARS = 71

    enum class Precision(internal val code: Int, val level: PeerWhereGrid.Level?) {
        DISTRICT(1, PeerWhereGrid.Level.DISTRICT),
        QUARTER(2, PeerWhereGrid.Level.QUARTER),
        EXACT(3, null),
    }

    enum class Motion { UNKNOWN, STILL, FOOT, VEHICLE }

    /** Дім і робота — лише з точним місцем: у районі «вдома» звужувало б пляму до підʼїзду для того, хто знає адресу. */
    enum class Spot { NONE, HOME, WORK }

    /** Стан без механіки: чим людина зайнята, а не цифри. Один байт у тій самій довжині кадру. */
    data class State(val motion: Motion = Motion.UNKNOWN, val spot: Spot = Spot.NONE, val lowBattery: Boolean = false) {
        internal val code: Int get() = motion.ordinal or (spot.ordinal shl 2) or (if (lowBattery) 0x10 else 0)

        internal companion object {
            fun of(code: Int): State? {
                if (code and 0xE0 != 0) return null
                val spot = (code shr 2) and 3
                if (spot >= Spot.entries.size) return null
                return State(Motion.entries[code and 3], Spot.entries[spot], code and 0x10 != 0)
            }
        }
    }

    val QUIET = State()

    private const val OP_POSITION = 1
    private const val OP_ERASE = 2
    private const val OP_ASK = 3

    sealed interface Frame {
        val atMs: Long
    }

    sealed interface Position : Frame {
        val expiresAtMs: Long
        val seq: Int
        val precision: Precision
        val state: State
    }

    data class Area(
        val cell: PeerWhereGrid.Cell,
        override val atMs: Long,
        override val expiresAtMs: Long,
        override val seq: Int,
        override val state: State = QUIET,
    ) : Position {
        override val precision: Precision
            get() = Precision.entries.first { it.level == cell.level }
    }

    data class Point(
        val lat: Double,
        val lon: Double,
        val accuracyM: Int?,
        override val atMs: Long,
        override val expiresAtMs: Long,
        override val seq: Int,
        override val state: State = QUIET,
    ) : Position {
        override val precision: Precision get() = Precision.EXACT
    }

    /** «Стерти мене»: приймач прибирає точку й не бере старших за цю мить. */
    data class Erase(override val atMs: Long) : Frame

    /** «Де ти?» Відповідає лише той, хто дав дозвіл, і лише з дозволеною точністю. */
    data class Ask(override val atMs: Long) : Frame

    fun isWhere(body: String): Boolean = body.startsWith(TAG)

    /**
     * Взаємно додані, зі спільним ключем, без тривоги про заміну ключа. Звірки
     * камерою власник не вимагає (26.09): це техніка на екрані, а підміну ключа
     * все одно ловить [PeerIdentity.keyAlarm].
     */
    fun mayReceive(peer: PeerIdentity): Boolean =
        peer.dhPubKey.isNotBlank() && !peer.keyAlarm && peer.introducedAtMs > 0L

    fun encode(frame: Frame): String? {
        val buf = ByteBuffer.allocate(PAYLOAD_BYTES)
        buf.put(VERSION.toByte())
        when (frame) {
            is Area -> {
                if (!PeerWhereGrid.isValid(frame.cell) || !timesOk(frame)) return null
                buf.put(OP_POSITION.toByte()).put(frame.precision.code.toByte()).put(0)
                buf.putLong(frame.atMs).putLong(frame.expiresAtMs).putInt(frame.seq)
                buf.putInt(frame.cell.row).putInt(frame.cell.col).putShort(0)
                buf.put(frame.state.copy(spot = Spot.NONE).code.toByte())
            }
            is Point -> {
                if (frame.lat !in -90.0..90.0 || frame.lon !in -180.0..180.0 || !timesOk(frame)) return null
                val acc = (frame.accuracyM ?: 0).coerceIn(0, 0xFFFF)
                buf.put(OP_POSITION.toByte()).put(Precision.EXACT.code.toByte()).put(0)
                buf.putLong(frame.atMs).putLong(frame.expiresAtMs).putInt(frame.seq)
                buf.putInt(Math.round(frame.lat * 1e7).toInt()).putInt(Math.round(frame.lon * 1e7).toInt())
                buf.putShort(acc.toShort())
                buf.put(frame.state.code.toByte())
            }
            is Erase, is Ask -> {
                if (frame.atMs <= 0L) return null
                buf.put((if (frame is Erase) OP_ERASE else OP_ASK).toByte()).put(0).put(0)
                buf.putLong(frame.atMs)
            }
        }
        return TAG + b64.encodeToString(buf.array())
    }

    fun decode(body: String): Frame? {
        if (body.length != BODY_CHARS || !isWhere(body)) return null
        val raw = runCatching { unb64.decode(body.substring(TAG.length)) }.getOrNull() ?: return null
        if (raw.size != PAYLOAD_BYTES) return null
        val buf = ByteBuffer.wrap(raw)
        if (buf.get().toInt() != VERSION) return null
        val op = buf.get().toInt()
        val precision = buf.get().toInt()
        if (buf.get().toInt() != 0) return null
        val atMs = buf.long
        val expires = buf.long
        val seq = buf.int
        val a = buf.int
        val b = buf.int
        val acc = buf.short.toInt() and 0xFFFF
        val state = State.of(buf.get().toInt() and 0xFF) ?: return null
        while (buf.hasRemaining()) if (buf.get().toInt() != 0) return null
        if (atMs <= 0L) return null
        return when (op) {
            OP_ERASE, OP_ASK -> {
                if (precision != 0 || expires != 0L || seq != 0 || a != 0 || b != 0 || acc != 0 || state != QUIET) return null
                if (op == OP_ERASE) Erase(atMs) else Ask(atMs)
            }
            OP_POSITION -> {
                if (expires <= atMs || seq < 0) return null
                val p = Precision.entries.firstOrNull { it.code == precision } ?: return null
                val level = p.level
                if (level != null) {
                    if (acc != 0 || state.spot != Spot.NONE) return null
                    val cell = PeerWhereGrid.Cell(level, a, b)
                    if (!PeerWhereGrid.isValid(cell)) return null
                    Area(cell, atMs, expires, seq, state)
                } else {
                    val lat = a / 1e7
                    val lon = b / 1e7
                    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
                    Point(lat, lon, acc.takeIf { it > 0 }, atMs, expires, seq, state)
                }
            }
            else -> null
        }
    }

    private fun timesOk(p: Position): Boolean = p.atMs > 0L && p.expiresAtMs > p.atMs && p.seq >= 0

    private val b64 = java.util.Base64.getUrlEncoder().withoutPadding()
    private val unb64 = java.util.Base64.getUrlDecoder()
}
