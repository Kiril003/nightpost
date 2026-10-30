package local.phantom.companion.core.net.peer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Чесний стан v5 для екрана «Профіль › Для розробника» (RELAY_FS.md §10) —
 * рівно та форма, яку читає `phantom-os/src/frontend/src/services/relayFsApi.ts`.
 *
 * Лише лічильники й позначки з [RelayFsKeys]: чиї ключі в кого, чим
 * запечатано останній лист. Нічого не обертає і не видає — дивитись на стан
 * не можна так, щоб він змінився.
 */
object RelayFsStatus {

    @Serializable
    data class Seal(val sealing: String, val why: String, @SerialName("at_ms") val atMs: Long)

    @Serializable
    data class Spk(val id: Long, @SerialName("age_h") val ageH: Long)

    @Serializable
    data class Contact(
        val id: String,
        val name: String,
        @SerialName("v5_capable") val v5Capable: Boolean,
        @SerialName("my_spk_sent") val mySpkSent: Boolean,
        @SerialName("my_opks_issued") val myOpksIssued: Int,
        @SerialName("their_keys") val theirKeys: Boolean,
        @SerialName("their_bundle_age_min") val theirBundleAgeMin: Long?,
        @SerialName("their_opks_held") val theirOpksHeld: Int,
        @SerialName("last_out") val lastOut: Seal?,
        @SerialName("last_in") val lastIn: Seal?,
    )

    @Serializable
    data class View(
        val enabled: Boolean,
        @SerialName("my_spk") val mySpk: Spk?,
        val contacts: List<Contact>,
    )

    data class Person(val id: String, val name: String, val dhPubKey: String)

    private val JSON = Json { encodeDefaults = true; explicitNulls = true }

    private fun seal(note: RelayFsKeys.Note?): Seal? = note?.let { Seal(it.sealing.name, it.why, it.atMs) }

    fun of(keys: RelayFsKeys, people: List<Person>, enabled: Boolean, nowMs: Long): View {
        val s = keys.snapshot()
        val current = s.spks.firstOrNull { it.retiredMs == 0L }
        return View(
            enabled = enabled,
            mySpk = current?.let { Spk(it.id, (nowMs - it.createdMs).coerceAtLeast(0) / 3_600_000) },
            contacts = people.map { p ->
                Contact(
                    id = p.id,
                    name = p.name,
                    v5Capable = p.dhPubKey.isNotBlank(),
                    mySpkSent = current != null && s.sentSpk[p.id] == current.id,
                    myOpksIssued = s.opks.count { it.peer == p.id },
                    theirKeys = s.theirs.any { it.peer == p.id },
                    theirBundleAgeMin = s.theirs.firstOrNull { it.peer == p.id }?.let { (nowMs - it.receivedMs).coerceAtLeast(0) / 60_000 },
                    theirOpksHeld = s.theirs.firstOrNull { it.peer == p.id }?.opks?.size ?: 0,
                    lastOut = seal(keys.lastOut(p.id)),
                    lastIn = seal(keys.lastIn(p.id)),
                )
            },
        )
    }

    fun json(view: View): String = JSON.encodeToString(View.serializer(), view)
}
