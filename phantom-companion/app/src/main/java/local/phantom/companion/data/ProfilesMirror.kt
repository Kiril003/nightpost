package local.phantom.companion.data

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class FaceOverride(
    val name: String? = null,
    val status: String? = null,
    val bio: String? = null,
)

typealias Quiet = ProfilesMirror.Quiet

class ProfilesMirror(
    private val dir: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val onChanged: (Change) -> Unit = {},
) {

    /**
     * [legacy] — міст v1: людям іде обличчя активного профілю, тож кожне перемикання їх стосується.
     * Інакше: [faces] — профілі, чиє обличчя змінилось; [owners] — комусь змінився профіль-власник.
     */
    data class Change(
        val legacy: Boolean,
        val faces: Set<String> = emptySet(),
        val owners: Boolean = false,
    )

    @Serializable
    data class Quiet(
        val from: String,
        val to: String,
    )

    /** Власна вкладка напряму (веб-екран): id із закритого переліку, підпис уже мовою застосунку. */
    @Serializable
    data class Slot(
        val id: String,
        val label: String,
    )

    @Serializable
    data class Profile(
        val id: String,
        val name: String = "",
        val kind: String = "",
        val passGroups: List<String> = emptyList(),
        val quiet: Quiet? = null,
        /** `sound`, `vibrate` або `silent`; null — звук. */
        val alert: String? = null,
        val expiresAt: Long? = null,
        val faces: Map<String, FaceOverride> = emptyMap(),
        /** Імʼя в цьому профілі; порожнє — основне. */
        val ownName: String = "",
        /** Акцент профілю з tokens.json (`sea`, `rose`…); null — загальний. */
        val accent: String? = null,
        val glyph: String? = null,
        /** Рідні вкладки смуги (`map`, `wallet`), які цей профіль показує; null — усі (старе дзеркало). */
        val bar: List<String>? = null,
        val slots: List<Slot>? = null,
    ) {
        val hidesHandle: Boolean get() = kind == "unnamed" || kind == "temp"

        internal fun passes(circles: Set<String>): Boolean =
            passGroups.isEmpty() || passGroups.any { it in circles }

        fun faceFor(circles: Set<String>): FaceOverride? {
            if (circles.isEmpty() || faces.isEmpty()) return null
            val ring = PeerCircle.closestRing(circles)
            if (ring != null) faces[ring]?.let { return it }
            return circles.firstNotNullOfOrNull { c -> if (c !in PeerCircle.ALL) faces[c] else null }
        }
    }

    @Serializable
    data class Mirror(
        val v: Int = 0,
        val activeId: String? = null,
        val profiles: List<Profile> = emptyList(),
        val peerProfile: Map<String, String> = emptyMap(),
        val chatProfile: Map<String, String> = emptyMap(),
    ) {
        val active: Profile?
            get() = activeId?.let { id -> profiles.firstOrNull { it.id == id } }

        val main: Profile?
            get() = profiles.firstOrNull { it.kind == "personal" } ?: profiles.firstOrNull()

        fun profile(id: String?): Profile? = id?.let { profiles.firstOrNull { p -> p.id == it } }

        fun ownerOf(peerId: String): Profile? =
            if (v < 2) active else profile(peerProfile[peerId]) ?: main
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val mirrorFile = File(dir, "mirror.json")

    private val _pendingActive = MutableStateFlow<String?>(null)
    val pendingActive: StateFlow<String?> = _pendingActive.asStateFlow()
    private val _retry = MutableStateFlow(0)
    val retry: StateFlow<Int> = _retry.asStateFlow()
    @Volatile private var stalled: String? = null

    private val _state: MutableStateFlow<Mirror>
    val state: StateFlow<Mirror>
    init {
        val (initial, pending) = loadInitial()
        _state = MutableStateFlow(initial)
        state = _state.asStateFlow()
        _pendingActive.value = pending
    }

    fun faceFor(circles: Set<String>): FaceOverride? = liveActive()?.faceFor(circles)

    fun profile(id: String?): Profile? = _state.value.profile(id)

    /** Профіль, чиє обличчя бачить ця людина. */
    fun ownerOf(peerId: String): Profile? {
        liveActive()
        return _state.value.ownerOf(peerId)
    }

    fun inviteName(mainName: String?): String? =
        liveActive()?.ownName?.trim()?.takeIf { it.isNotEmpty() } ?: mainName

    fun speaksAs(peerId: String): Boolean {
        val a = liveActive() ?: return true
        return ownerOf(peerId)?.id == a.id
    }

    fun liveActive(): Profile? {
        val a = _state.value.active
        if (a?.expiresAt == null || a.expiresAt > now()) return a
        expire()
        return _state.value.active
    }

    @Synchronized
    private fun expire() {
        val m = _state.value
        val (cleaned, pending) = prune(m)
        if (cleaned == m) return
        _state.value = cleaned
        save(cleaned)
        if (pending != null) _pendingActive.value = pending
        onChanged(Change(legacy = cleaned.v < 2, owners = true))
    }

    @Synchronized
    fun apply(raw: String): Boolean {
        val parsed = runCatching { json.decodeFromString<Mirror>(raw) }.getOrNull() ?: return false
        if (parsed.v !in VERSIONS) return false
        val prev = _state.value
        val (next, pending) = prune(parsed)
        _state.value = next
        save(next)
        val waiting = _pendingActive.value
        when {
            waiting == null -> Unit
            // Другий setActive того самого id стер би «назад» на вебі.
            waiting == parsed.activeId -> _pendingActive.compareAndSet(waiting, null)
            waiting == stalled -> {
                stalled = null
                _retry.value += 1
            }
        }
        if (pending != null) _pendingActive.value = pending
        changeOf(prev, next)?.let(onChanged)
        return true
    }

    private fun changeOf(prev: Mirror, next: Mirror): Change? {
        if (next.v < 2) {
            val facesChanged = prev.profiles.map { Triple(it.id, it.faces, it.ownName) } != next.profiles.map { Triple(it.id, it.faces, it.ownName) }
            return if (prev.activeId != next.activeId || facesChanged) Change(legacy = true) else null
        }
        val before = prev.profiles.associate { it.id to Triple(it.faces, it.ownName, it.kind) }
        val faces = next.profiles.filter { before.containsKey(it.id) && before[it.id] != Triple(it.faces, it.ownName, it.kind) }
            .map { it.id }.toSet()
        val owners = prev.v < 2 || prev.peerProfile != next.peerProfile || prev.main?.id != next.main?.id ||
            prev.profiles.map { it.id }.toSet() != next.profiles.map { it.id }.toSet()
        return if (faces.isEmpty() && !owners) null else Change(legacy = false, faces = faces, owners = owners)
    }

    /** «Я» на телефоні просить перемкнути профіль; доставить веб-хост, щойно сторінка жива. */
    fun requestActive(id: String) {
        if (id.isNotBlank()) _pendingActive.value = id
    }

    fun setActiveScript(id: String): String =
        "(function(){var p=window.__phantomProfiles;" +
            "if(!p||typeof p.setActive!=='function')return 'missing';" +
            "return window.__phantomProfiles.setActive(${JsonPrimitive(id)})===false?'refused':'opened';})()"

    suspend fun deliverPendingActive(
        id: String,
        run: suspend (script: String) -> String?,
        pause: suspend (ms: Long) -> Unit = { kotlinx.coroutines.delay(it) },
    ) {
        repeat(TRIES) {
            val raw = run(setActiveScript(id))?.trim('"')
            if (raw == "opened" || raw == "refused") {
                _pendingActive.compareAndSet(id, null)
                return
            }
            pause(RETRY_MS)
        }
        stalled = id
    }

    private fun loadInitial(): Pair<Mirror, String?> {
        val raw = runCatching {
            if (!mirrorFile.isFile) return Mirror(v = 1) to null
            json.decodeFromString<Mirror>(mirrorFile.readText())
        }.getOrNull()?.takeIf { it.v in VERSIONS } ?: return Mirror(v = 1) to null
        val (cleaned, pending) = prune(raw)
        if (cleaned != raw) save(cleaned)
        return cleaned to pending
    }

    private fun prune(raw: Mirror): Pair<Mirror, String?> {
        val nowMs = now()
        val kept = raw.profiles.filter { it.expiresAt == null || it.expiresAt > nowMs }
        if (kept.size == raw.profiles.size) return raw to null
        val activeStillValid = raw.activeId != null && kept.any { it.id == raw.activeId }
        val activeWasExpired = raw.activeId != null && !activeStillValid && raw.profiles.any { it.id == raw.activeId }
        val nextActiveId = when {
            activeStillValid -> raw.activeId
            activeWasExpired -> kept.firstOrNull { it.kind == "personal" }?.id
            else -> raw.activeId?.takeIf { id -> kept.any { it.id == id } }
        }
        val pending = if (activeWasExpired) nextActiveId else null
        return raw.copy(activeId = nextActiveId, profiles = kept) to pending
    }

    private fun save(m: Mirror) {
        dir.mkdirs()
        val tmp = File(dir, "mirror.json.tmp")
        tmp.writeText(json.encodeToString(m))
        if (!tmp.renameTo(mirrorFile)) {
            mirrorFile.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private companion object {
        val VERSIONS = 1..2
        const val TRIES = 20
        const val RETRY_MS = 500L
    }
}
