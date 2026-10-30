package local.phantom.companion.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import local.phantom.companion.core.net.peer.PeerWhere

interface CircleWhere {
    fun granted(id: String): Boolean
    fun grant(id: String, untilMs: Long): Boolean
    fun revoke(id: String)
}

class HubWhere(private val hub: PeerWhereHub) : CircleWhere {
    override fun granted(id: String): Boolean = id in hub.grants.value
    override fun grant(id: String, untilMs: Long): Boolean = hub.setPrecision(id, PeerWhere.Precision.EXACT, untilMs)
    override fun revoke(id: String) {
        hub.setPrecision(id, null)
    }
}

class CirclePolicy internal constructor(
    private val where: CircleWhere,
    private val slot: PeerLogSlot,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var remembered: Set<String> = readRemembered()

    fun changed(id: String, before: Set<String>, after: Set<String>) {
        val hadLoc = CircleRules.canSeeLocation(before)
        val hasLoc = CircleRules.canSeeLocation(after)
        if (!hadLoc && hasLoc) {
            if (!where.granted(id) && where.grant(id, 0L)) {
                writeRemembered(remembered + id)
            }
        } else if (hadLoc && !hasLoc) {
            if (id in remembered) {
                where.revoke(id)
                writeRemembered(remembered - id)
            }
        }
    }

    fun share(members: Set<String>, untilMs: Long): Int {
        if (untilMs <= System.currentTimeMillis()) return 0
        var count = 0
        for (id in members) {
            if (where.grant(id, untilMs)) count++
        }
        return count
    }

    private fun readRemembered(): Set<String> {
        val raw = slot.read(KEY_GRANTS) ?: return emptySet()
        return runCatching { json.decodeFromString<Set<String>>(raw) }.getOrDefault(emptySet())
    }

    private fun writeRemembered(next: Set<String>) {
        remembered = next
        slot.write(KEY_GRANTS, json.encodeToString(next))
    }

    private companion object {
        const val KEY_GRANTS = "circle_grants"
    }
}
