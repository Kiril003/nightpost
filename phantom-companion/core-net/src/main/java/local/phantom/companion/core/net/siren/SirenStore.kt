package local.phantom.companion.core.net.siren

import java.util.Base64

/**
 * Останній ПЕРЕВІРЕНИЙ знімок «Сирени-маяка» на телефоні — хоч би якою
 * смугою він приїхав: відповідь Наміру (сховок, SMS), прямий запит до сайту
 * («рюкзак»), пізніше сусід.
 *
 * Береться лише знімок із закріпленим підписом і лише новіший за наявний
 * (спершу за моментом підтвердження, потім за seq): перевізник, що підсовує
 * старий знімок, нічого не змінить. Вік рахується в момент показу, не в
 * момент запису.
 */
class SirenStore(
    private val slot: Slot,
    private val keys: () -> Map<Int, ByteArray> = { SirenKeys.PINNED },
    private val nowS: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    interface Slot {
        fun read(): String?
        fun write(value: String)
    }

    private val lock = Any()
    private var raw: ByteArray? = slot.read()?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

    /** True — знімок перевірено й він новіший за той, що був. */
    fun offer(snapshot: ByteArray): Boolean = synchronized(lock) {
        val fresh = SirenSnapshot.verify(snapshot, keys(), nowS()) ?: return false
        val old = raw?.let { SirenSnapshot.verify(it, keys()) }
        if (old != null && (old.observedAt > fresh.observedAt || (old.observedAt == fresh.observedAt && old.seq >= fresh.seq))) {
            return false
        }
        raw = snapshot.copyOf()
        slot.write(Base64.getEncoder().encodeToString(snapshot))
        true
    }

    /** Перевірений знімок або null. Чи він «зараз» — [SirenSnapshot.Snapshot.fresh]. */
    fun current(): SirenSnapshot.Snapshot? = synchronized(lock) { raw?.let { SirenSnapshot.verify(it, keys()) } }

    fun rawBytes(): ByteArray? = synchronized(lock) { raw?.copyOf() }
}
