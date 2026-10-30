package local.phantom.companion.core.net.siren

import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.peer.PeerIntent

/**
 * «Сирена-маяк» на телефоні — перевірка підписаного знімка тривог (розд. 07 §10).
 *
 * ДЖЕРЕЛО ПРАВДИ — сервер: `platform-site/server/siren.py`. Кожен байт звірено
 * зі спільним вектором `siren_vector.json`. Знімок може привезти будь-хто
 * (сховок, SMS, сусід) — телефон вірить лише підпису закріпленого ключа і
 * сам рахує вік:
 *  * чужий підпис, криві байти, невідомий id ключа, годинник із майбутнього —
 *    знімка НЕМАЄ (null);
 *  * старший за [FRESH_S] — правда про тоді, але не «зараз» ([Snapshot.fresh]).
 *
 *     0 версія · 1 id ключа · 2..5 seq · 6..9 observed_at · 10..13 бітмапа ·
 *     14..27 типи по 4 біти · 28..91 Ed25519(DOMAIN ‖ байти 0..27)
 */
object SirenSnapshot {

    const val VERSION = 1
    const val BODY_BYTES = 28
    const val SNAPSHOT_BYTES = 92
    const val FRESH_S = 5 * 60L
    const val SKEW_S = 5 * 60L
    val DOMAIN: ByteArray = "PHANTOM siren v1\u0000".toByteArray(Charsets.UTF_8)

    data class Snapshot(
        val keyId: Int,
        val seq: Long,
        val observedAt: Long,
        /** Область → код типу (лише області під тривогою). */
        val alerts: Map<String, Int>,
    ) {
        fun fresh(nowS: Long): Boolean = nowS - observedAt in 0..FRESH_S

        fun ageMinutes(nowS: Long): Long = ((nowS - observedAt).coerceAtLeast(0)) / 60
    }

    fun decodeBody(raw: ByteArray): Snapshot? {
        if (raw.size < BODY_BYTES || (raw[0].toInt() and 0xFF) != VERSION) return null
        val bitmap = u32(raw, 10)
        val alerts = LinkedHashMap<String, Int>()
        PeerIntent.OBLAST_ORDER.forEachIndexed { i, oblast ->
            if ((bitmap shr i) and 1L == 1L) {
                val nib = raw[14 + i / 2].toInt() and 0xFF
                alerts[oblast] = if (i % 2 == 0) nib ushr 4 else nib and 0x0F
            }
        }
        return Snapshot(raw[1].toInt() and 0xFF, u32(raw, 2), u32(raw, 6), alerts)
    }

    /** [keys] — id ключа → відкритий ключ (32 Б). [nowS] — щоб відкинути годинник із майбутнього. */
    fun verify(snapshot: ByteArray, keys: Map<Int, ByteArray>, nowS: Long? = null): Snapshot? {
        if (snapshot.size != SNAPSHOT_BYTES) return null
        val body = snapshot.copyOfRange(0, BODY_BYTES)
        val sig = snapshot.copyOfRange(BODY_BYTES, SNAPSHOT_BYTES)
        val pub = keys[body[1].toInt() and 0xFF] ?: return null
        if (!runCatching { Ed25519.verify(pub, DOMAIN + body, sig) }.getOrDefault(false)) return null
        val snap = decodeBody(body) ?: return null
        if (nowS != null && snap.observedAt > nowS + SKEW_S) return null
        return snap
    }

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)
}

/**
 * Закріплені ключі тривог продукту: id → відкритий ключ.
 *
 * ПОРОЖНЬО, доки власник не видав ключ тривог: сервер сайту без
 * ALERTS_SIGNING_KEY не підписує нічого, тож і закріплювати нема чого.
 * Порожній список означає «жоден знімок не перевірено» — і телефон чесно
 * не показує маяк, а не показує його без перевірки. Видати ключ:
 * `docs/bypass/STATUS.md`, розділ «Сирена-маяк».
 */
object SirenKeys {
    val PINNED: Map<Int, ByteArray> = emptyMap()
}
