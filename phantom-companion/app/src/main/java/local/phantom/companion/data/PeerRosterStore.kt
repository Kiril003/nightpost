package local.phantom.companion.data

import android.content.Context
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerRoster

/** Коло людини чи групи на «Сьогодні». [NONE] — без кола. */
object PeerCircle {
    const val NONE = "all"
    const val MAX_CUSTOM = 8
    val ALL = listOf("personal", "close", "friends", "social", "work")

    fun closestRing(circles: Set<String>): String? = ALL.firstOrNull { it in circles }
}

@Serializable
data class CustomCircle(val id: String, val name: String)

/**
 * Everyone PHANTOM has proved a key with, kept at rest under the Keystore.
 *
 * Separate from [PairedDeviceStore] on purpose: that one holds the operator's
 * session with his OWN server — a token, an address, an expiry. This holds
 * identities, which outlive any session and belong to no server. Merging them
 * is how a roster ends up singular, which is exactly where this started.
 */
class PeerRosterStore internal constructor(private val slot: PeerLogSlot) {

    constructor(context: Context) : this(EncryptedSlot(context, FILE_NAME))

    private val json = Json { ignoreUnknownKeys = true }
    private val rng = SecureRandom()

    private val _peers = MutableStateFlow(released(read()))
    val peers: StateFlow<List<PeerIdentity>> = _peers.asStateFlow()

    private val _customCircles = MutableStateFlow(readCustomCircles())
    val customCircles: StateFlow<List<CustomCircle>> = _customCircles.asStateFlow()

    private val _memberships = MutableStateFlow(readMemberships(_customCircles.value))
    val memberships: StateFlow<Map<String, Set<String>>> = _memberships.asStateFlow()

    private val _circles = MutableStateFlow(derivePrimary(_memberships.value, _customCircles.value))
    val circles: StateFlow<Map<String, String>> = _circles.asStateFlow()

    @Volatile
    private var policy: CirclePolicy? = null

    fun bindWhere(where: CircleWhere) {
        policy = CirclePolicy(where, slot)
    }

    fun isCircle(circle: String): Boolean =
        circle in PeerCircle.ALL || _customCircles.value.any { it.id == circle }

    fun circlesOf(id: String): Set<String> = _memberships.value[id] ?: emptySet()

    fun membersOf(circle: String): Set<String> =
        _memberships.value.entries.mapNotNullTo(LinkedHashSet()) { (id, set) -> id.takeIf { circle in set } }

    fun circleOf(id: String): String = _circles.value[id] ?: PeerCircle.NONE

    fun addToCircle(id: String, circle: String): Boolean {
        if (!isCircle(circle)) return false
        val before = circlesOf(id)
        val after = before + circle
        if (after != before) {
            writeMemberships(_memberships.value + (id to after))
            policy?.changed(id, before, after)
        }
        return true
    }

    fun removeFromCircle(id: String, circle: String): Boolean {
        if (!isCircle(circle)) return false
        val before = circlesOf(id)
        if (circle !in before) return true
        val after = before - circle
        val next = if (after.isEmpty()) _memberships.value - id else _memberships.value + (id to after)
        writeMemberships(next)
        policy?.changed(id, before, after)
        return true
    }

    /** Ключ — id людини або групи. Чуже слово відкидається, [PeerCircle.NONE] знімає коло. */
    fun setCircle(id: String, circle: String): Boolean {
        if (circle != PeerCircle.NONE && !isCircle(circle)) return false
        val before = circlesOf(id)
        val after = if (circle == PeerCircle.NONE) emptySet() else setOf(circle)
        if (after != before) {
            val next = if (after.isEmpty()) _memberships.value - id else _memberships.value + (id to after)
            writeMemberships(next)
            policy?.changed(id, before, after)
        }
        return true
    }

    fun createCircle(name: String): CustomCircle? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > 32) return null
        val current = _customCircles.value
        if (current.size >= PeerCircle.MAX_CUSTOM) return null
        val existingIds = current.map { it.id }.toSet()
        var candidate: String
        do {
            val bytes = ByteArray(4)
            rng.nextBytes(bytes)
            candidate = "u:" + bytes.joinToString("") { "%02x".format(it) }
        } while (candidate in existingIds)
        val created = CustomCircle(candidate, trimmed)
        writeCustomCircles(current + created)
        return created
    }

    fun deleteCircle(circle: String): Boolean {
        val current = _customCircles.value
        if (current.none { it.id == circle }) return false
        val nextCustom = current.filterNot { it.id == circle }
        writeCustomCircles(nextCustom)
        val affected = ArrayList<Triple<String, Set<String>, Set<String>>>()
        val nextMemberships = LinkedHashMap<String, Set<String>>()
        for ((id, set) in _memberships.value) {
            if (circle in set) {
                val after = set - circle
                if (after.isNotEmpty()) nextMemberships[id] = after
                affected.add(Triple(id, set, after))
            } else {
                nextMemberships[id] = set
            }
        }
        writeMemberships(nextMemberships, nextCustom)
        affected.forEach { (id, before, after) -> policy?.changed(id, before, after) }
        return true
    }

    fun shareCircle(circle: String, untilMs: Long): Int {
        val p = policy ?: return 0
        if (!isCircle(circle)) return 0
        return p.share(membersOf(circle), untilMs)
    }

    fun upsert(peer: PeerIdentity, atMs: Long = System.currentTimeMillis()) =
        write(PeerRoster.upsert(_peers.value, peer, atMs))

    /** Відновлює список контактів і кола з резервної копії. */
    fun adopt(
        peers: List<PeerIdentity>,
        memberships: Map<String, Set<String>> = emptyMap(),
        customCircles: List<CustomCircle> = emptyList(),
        declinedList: Set<String> = emptySet(),
    ) {
        writeCustomCircles(customCircles)
        writeMemberships(memberships, customCircles)
        writeDeclined(declinedList)
        write(PeerRoster.adoptBackup(peers))
    }

    /** Щойно спарений ПК. Спарювання — місцева дія, тож і засув знімає. */
    fun adoptOwnDevice(peer: PeerIdentity, atMs: Long = System.currentTimeMillis()) {
        allow(peer.id)
        write(PeerRoster.adoptOwnDevice(_peers.value, peer, atMs))
    }

    /**
     * Прибрати — і щоб лишилось прибраним.
     *
     * Знайомство впускає всякого, хто бачив наш код, а код у прибраного нікуди
     * не подівся: без засува він вставив би себе назад наступним кадром, і
     * «прибрати» було б виставою. Знімає засув лише місцева дія — [allow].
     */
    fun remove(id: String) {
        if (_peers.value.any { it.id == id && it.kind == PeerIdentity.Kind.PERSON }) {
            writeDeclined(declined + id)
        }
        write(PeerRoster.remove(_peers.value, id))
        val before = _memberships.value[id]
        if (before != null) {
            writeMemberships(_memberships.value - id)
            policy?.changed(id, before, emptySet())
        }
    }

    fun isDeclined(id: String): Boolean = id in declined

    fun declinedIds(): Set<String> = declined

    /** Додали руками — засув знято. Дріт не знімає його ніколи. */
    fun allow(id: String) {
        if (id in declined) writeDeclined(declined - id)
    }

    fun rename(id: String, name: String) = write(PeerRoster.rename(_peers.value, id, name))

    fun setKind(id: String, kind: PeerIdentity.Kind) = write(PeerRoster.setKind(_peers.value, id, kind))

    /**
     * A peer just spoke. Written through rather than kept in memory: presence
     * that dies with the process would make every cold start claim nobody is
     * there — and a roster that forgets is worse than one that is empty.
     */
    fun sawIt(id: String, nowMs: Long = System.currentTimeMillis()) =
        write(PeerRoster.sawIt(_peers.value, id, nowMs))

    /** [sawIt], but the caller can prove this LAN carried the contact — see [PeerRoster.sawNearby]. */
    fun sawNearby(id: String, nowMs: Long = System.currentTimeMillis()) =
        write(PeerRoster.sawNearby(_peers.value, id, nowMs))

    /**
     * Кадр [local.phantom.companion.core.net.peer.PeerProfile] від цієї людини.
     *
     * Пишеться наскрізь, бо профіль мусить пережити перезапуск: інакше кожен
     * холодний старт показував би порожню картку, доки співрозмовник не
     * надішле кадр наново — а надішле він його лише при НАСТУПНІЙ своїй зміні.
     *
     * Повертає true лише коли щось справді змінилось: повтор сіткового
     * переказу не має ані писати на диск, ані смикати екран.
     */
    fun absorbProfile(
        fromId: String,
        body: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val next = PeerRoster.absorbProfile(_peers.value, fromId, body, nowMs)
        if (next === _peers.value) return false
        write(next)
        return true
    }

    /**
     * Ключ зустрічі з цією людиною склався. Пишеться наскрізь, бо саме на
     * перезапуск і чекав би той, хто хоче повернути пару на довічний ключ.
     */
    fun markMet(id: String, atMs: Long = System.currentTimeMillis()) =
        write(PeerRoster.markMet(_peers.value, id, atMs))

    /**
     * Доведено, що їхній реєстр нас має. Наскрізь, бо саме перезапуску й чекало
     * б знайомство, яке щоразу слалося б наново тим, хто давно нас тримає.
     */
    fun markIntroduced(id: String, atMs: Long = System.currentTimeMillis()) =
        write(PeerRoster.markIntroduced(_peers.value, id, atMs))

    /**
     * The number matched, or the operator withdrew that claim. [source] comes
     * only from the surface that read the number off the other screen.
     */
    fun setVerified(
        id: String,
        verified: Boolean,
        atMs: Long = System.currentTimeMillis(),
        source: PeerIdentity.KeySource? = null,
    ) = write(
        if (verified) {
            PeerRoster.markVerified(_peers.value, id, atMs, source)
        } else {
            PeerRoster.clearVerified(_peers.value, id)
        },
    )

    /** «Продовжити» після зміни ключа звіреної людини: пауза знімається, тривога лишається. */
    fun continueAfterKeyChange(id: String) = write(PeerRoster.continueAfterKeyChange(_peers.value, id))

    /** Хто ще хоче знати, що межа прочитаного зрушила (зникаючі повідомлення). */
    @Volatile var onRead: ((id: String, uptoMs: Long) -> Unit)? = null

    /** The conversation was looked at. Written through — unread must survive. */
    fun markRead(id: String, uptoMs: Long = System.currentTimeMillis()) {
        write(PeerRoster.markRead(_peers.value, id, uptoMs))
        onRead?.invoke(id, uptoMs)
    }

    fun clear() {
        val previous = _memberships.value
        slot.write(KEY_PEERS, null)
        slot.write(KEY_DECLINED, null)
        slot.write(KEY_CIRCLES, null)
        slot.write(KEY_CIRCLE_SETS, null)
        slot.write(KEY_CIRCLE_CUSTOM, null)
        _peers.value = emptyList()
        _customCircles.value = emptyList()
        _memberships.value = emptyMap()
        _circles.value = emptyMap()
        declined = emptySet()
        previous.forEach { (id, before) -> policy?.changed(id, before, emptySet()) }
    }

    @Volatile
    private var declined: Set<String> = readDeclined()

    private fun readDeclined(): Set<String> {
        val raw = slot.read(KEY_DECLINED) ?: return emptySet()
        return runCatching { json.decodeFromString<Set<String>>(raw) }.getOrDefault(emptySet())
    }

    private fun writeDeclined(next: Set<String>) {
        declined = next
        slot.write(KEY_DECLINED, json.encodeToString(next))
    }

    private fun readCustomCircles(): List<CustomCircle> {
        val raw = slot.read(KEY_CIRCLE_CUSTOM) ?: return emptyList()
        return runCatching { json.decodeFromString<List<CustomCircle>>(raw) }
            .getOrDefault(emptyList())
            .filter { it.id.matches(CUSTOM_ID_REGEX) && it.name.isNotBlank() && it.name.length <= 32 }
            .distinctBy { it.id }
            .take(PeerCircle.MAX_CUSTOM)
    }

    private fun writeCustomCircles(next: List<CustomCircle>) {
        slot.write(KEY_CIRCLE_CUSTOM, json.encodeToString(next))
        _customCircles.value = next
    }

    private fun readMemberships(custom: List<CustomCircle>): Map<String, Set<String>> {
        val valid = PeerCircle.ALL.toSet() + custom.map { it.id }
        val rawSets = slot.read(KEY_CIRCLE_SETS)
        if (rawSets != null) {
            val decoded = runCatching { json.decodeFromString<Map<String, List<String>>>(rawSets) }
                .getOrDefault(emptyMap())
            return decoded.mapValues { (_, list) -> list.filter { it in valid }.toSet() }
                .filterValues { it.isNotEmpty() }
        }
        val rawOld = slot.read(KEY_CIRCLES) ?: return emptyMap()
        val oldMap = runCatching { json.decodeFromString<Map<String, String>>(rawOld) }
            .getOrDefault(emptyMap())
        val migrated = LinkedHashMap<String, Set<String>>()
        for ((id, oldCircle) in oldMap) {
            val mapped = when (oldCircle) {
                "family" -> "personal"
                "study", "travel" -> "social"
                else -> oldCircle
            }
            if (mapped in PeerCircle.ALL) {
                migrated[id] = setOf(mapped)
            }
        }
        slot.write(KEY_CIRCLE_SETS, json.encodeToString(migrated.mapValues { it.value.toList() }))
        slot.write(KEY_CIRCLES, null)
        return migrated
    }

    private fun writeMemberships(
        next: Map<String, Set<String>>,
        custom: List<CustomCircle> = _customCircles.value,
    ) {
        val cleaned = next.filterValues { it.isNotEmpty() }
        slot.write(KEY_CIRCLE_SETS, json.encodeToString(cleaned.mapValues { it.value.toList() }))
        _memberships.value = cleaned
        _circles.value = derivePrimary(cleaned, custom)
    }

    private fun derivePrimary(
        sets: Map<String, Set<String>>,
        custom: List<CustomCircle>,
    ): Map<String, String> {
        val order = PeerCircle.ALL + custom.map { it.id }
        val result = LinkedHashMap<String, String>()
        for ((id, memberSet) in sets) {
            val primary = order.firstOrNull { it in memberSet }
            if (primary != null) result[id] = primary
        }
        return result
    }

    private fun read(): List<PeerIdentity> {
        val raw = slot.read(KEY_PEERS) ?: return emptyList()
        return runCatching { json.decodeFromString<List<PeerIdentity>>(raw) }
            .getOrDefault(emptyList())
    }

    /**
     * Один раз на апарат: заглушка «Новий контакт» з прапорцем власника
     * поступається справжньому імені людини ([PeerRoster.releaseLegacyPlaceholders]).
     * Позначка ставиться навіть коли лікувати нічого — щоб не вертатись сюди
     * на кожному старті й не зняти прапорець з імені, яке власник колись дасть
     * сам, хай навіть таке саме.
     */
    private fun released(roster: List<PeerIdentity>): List<PeerIdentity> {
        if (slot.read(KEY_PLACEHOLDER_RELEASED) != null) return roster
        val next = PeerRoster.releaseLegacyPlaceholders(roster)
        if (next !== roster) slot.write(KEY_PEERS, json.encodeToString(next))
        slot.write(KEY_PLACEHOLDER_RELEASED, "1")
        return next
    }

    private fun write(next: List<PeerIdentity>) {
        slot.write(KEY_PEERS, json.encodeToString(next))
        _peers.value = next
    }

    private companion object {
        const val FILE_NAME = "phantom_peers_v1"
        const val KEY_PEERS = "peers"
        const val KEY_DECLINED = "declined"
        const val KEY_CIRCLES = "circles"
        const val KEY_CIRCLE_SETS = "circle_sets"
        const val KEY_CIRCLE_CUSTOM = "circle_custom"
        const val KEY_PLACEHOLDER_RELEASED = "placeholder_released_v1"
        val CUSTOM_ID_REGEX = Regex("^u:[0-9a-f]{8}$")
    }
}
