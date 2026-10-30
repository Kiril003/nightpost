package local.phantom.companion.data.backup

import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.memory.Ledger
import local.phantom.companion.core.data.profile.FamiliarRarity
import local.phantom.companion.core.data.profile.Profile
import local.phantom.companion.core.data.profile.Role
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.data.AppContainer
import local.phantom.companion.data.ClaimKey
import local.phantom.companion.data.CustomCircle
import local.phantom.companion.data.DeviceIdentityStore
import local.phantom.companion.data.PeerHandleBook

sealed class RestoreResult {
    data class Success(val peersCount: Int, val messagesCount: Int, val profilesCount: Int) : RestoreResult()
    object NotEmpty : RestoreResult()
    data class Error(val reason: Reason) : RestoreResult()

    enum class Reason { INVALID, WRITE }
}

internal class RestorePlan(
    val identity: DeviceIdentityStore.Identity?,
    val claimKey: ClaimKey?,
    val peers: List<PeerIdentity>,
    val circles: Map<String, Set<String>>,
    val customCircles: List<CustomCircle>,
    val declined: Set<String>,
    val blocked: Set<String>,
    val handles: Map<String, String>,
    val messages: List<PeerMessage>,
    val savedNotes: List<PeerMessage>,
    val readReceipts: Boolean,
    val groupReads: Map<String, Long>,
    val memory: List<JsonObject>,
    val settings: BackupManifest.SettingsSection,
    val profiles: List<BackupManifest.ProfileEntry>,
    val web: Map<String, String>,
    val selfFiles: Map<String, ByteArray>,
    val local: LocalPlan,
) {
    companion object {
        // Секції розбираються мовчки (mapNotNull); тут кожен рядок мусить розібратись, інакше копію не чіпаємо.
        fun of(m: BackupManifest): RestorePlan? = runCatching {
            val peers = m.roster.peers().takeIf { it.size == m.roster.peersB64.size }
            val custom = m.roster.customCircles().takeIf { it.size == m.roster.usrCirclesB64.size }
            val msgs = m.chats.messages().takeIf { it.size == m.chats.msgsB64.size }
            val saved = m.chats.savedNotes().takeIf { it.size == m.chats.savedB64.size }
            val mem = m.mem.items().takeIf { it.size == m.mem.itemsB64.size }
            val profiles = m.profiles.profiles().takeIf { it.size == m.profiles.profilesB64.size }
            if (peers == null || custom == null || msgs == null || saved == null || mem == null || profiles == null) return null
            if (profiles.distinctBy { it.id }.size != profiles.size) return null
            val identity = m.root?.toIdentity()
            if (identity != null && (identity.privateKey.isEmpty() || identity.dhPrivateKey.size != 32 || identity.id.isBlank())) return null
            m.settings.typed()
            RestorePlan(
                identity = identity,
                claimKey = m.claims?.toClaimKey(),
                peers = peers,
                circles = m.roster.circles(),
                customCircles = custom,
                declined = m.roster.declined.toSet(),
                blocked = m.roster.blocked.toSet(),
                handles = m.roster.handles(),
                messages = msgs,
                savedNotes = saved,
                readReceipts = m.chats.readReceiptsEnabled(),
                groupReads = m.chats.groupReads(),
                memory = mem,
                settings = m.settings,
                profiles = profiles,
                web = m.web.items().filterKeys(WebBackupStash::allowed),
                selfFiles = m.selfFiles.files().takeIf { f -> f.size == m.selfFiles.filesB64.size && f.keys.all(BackupSelfFiles::allowed) } ?: return null,
                local = LocalPlan.of(m.local) ?: return null,
            )
        }.getOrNull()
    }
}

internal interface RestoreTarget {
    fun hasUserData(): Boolean
    var restorePending: Boolean
    fun write(plan: RestorePlan)
    fun keepKey(key: BackupFormat.SealedKey, uri: String?)
}

internal fun restoreBackup(
    manifest: BackupManifest,
    key: BackupFormat.SealedKey,
    uri: String?,
    target: RestoreTarget,
): RestoreResult = restoreInto(manifest, target) { target.keepKey(key, uri) }

internal fun restoreInto(manifest: BackupManifest, target: RestoreTarget, after: () -> Unit = {}): RestoreResult {
    val plan = RestorePlan.of(manifest) ?: return RestoreResult.Error(RestoreResult.Reason.INVALID)
    // Дані з недовершеного відновлення — не чужі: їх можна перекрити тією ж копією.
    if (target.hasUserData() && !target.restorePending) return RestoreResult.NotEmpty
    return try {
        target.restorePending = true
        target.write(plan)
        after()
        target.restorePending = false
        RestoreResult.Success(plan.peers.size, plan.messages.size, plan.profiles.size)
    } catch (e: Exception) {
        RestoreResult.Error(RestoreResult.Reason.WRITE)
    }
}

internal fun restoreExtras(plan: RestorePlan, handles: PeerHandleBook, ledger: Ledger, web: WebBackupStash, selfDir: java.io.File) {
    plan.handles.forEach { (id, handle) -> handles.remember(id, handle, 0L) }
    if (plan.memory.isNotEmpty()) ledger.applyChanges(plan.memory.map(::memoryUpsert), "backup")
    if (plan.web.isNotEmpty()) web.restore(plan.web)
    if (plan.selfFiles.isNotEmpty()) BackupSelfFiles.restore(selfDir, plan.selfFiles)
}

private fun memoryUpsert(item: JsonObject): JsonObject = buildJsonObject {
    item["id"]?.let { put("id", it) }
    put("op", "upsert")
    put("lamport", 1)
    put("item", item)
}

private const val OLD_PHONE_HINT_MS = 30L * 24 * 60 * 60 * 1000

internal fun showOldPhoneHint(restoredAtMs: Long, nowMs: Long): Boolean =
    restoredAtMs > 0L && nowMs - restoredAtMs in 0 until OLD_PHONE_HINT_MS

fun AppContainer.applyBackup(opened: BackupFormat.Opened, uri: String?): RestoreResult =
    restoreBackup(opened.manifest, opened.key, uri, ContainerRestoreTarget(this))

internal class ContainerRestoreTarget(private val c: AppContainer) : RestoreTarget {

    private val journal = c.applicationContext.getSharedPreferences(JOURNAL, Context.MODE_PRIVATE)

    override fun hasUserData(): Boolean =
        c.peers.peers.value.any { it.kind == PeerIdentity.Kind.PERSON } || c.outboxStore.load().isNotEmpty()

    override var restorePending: Boolean
        get() = journal.getBoolean(KEY_PENDING, false)
        set(value) {
            journal.edit().putBoolean(KEY_PENDING, value).commit()
        }

    override fun write(plan: RestorePlan) {
        val app = c.applicationContext
        BackupSettings.write(app, plan.settings)
        if (plan.profiles.isNotEmpty()) {
            runBlocking { c.profileStore.adoptProfiles(plan.profiles.map(::toProfile)) }
        }
        c.peers.adopt(
            peers = plan.peers,
            memberships = plan.circles,
            customCircles = plan.customCircles,
            declinedList = plan.declined,
        )
        c.peerBlocks.adopt(plan.blocked)
        c.outboxStore.save(plan.messages)
        if (plan.savedNotes.isNotEmpty()) c.savedNotes.adopt(plan.savedNotes)
        c.readReceipts.set(plan.readReceipts)
        c.groupReads.adopt(plan.groupReads)
        restoreExtras(plan, PeerHandleBook.open(app), c.memoryRuntime.ledger, c.webBackupStash, java.io.File(app.filesDir, "profile_self"))
        c.restoreLocal(plan.local)
        plan.claimKey?.let(c.claimKeys::adopt)
        plan.identity?.let(c.identity::adopt)
        // apply() пише на диск пізніше; перезапуск процесу після відновлення його б загубив.
        FLUSH.forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().commit() }
    }

    override fun keepKey(key: BackupFormat.SealedKey, uri: String?) {
        c.backupKeys.save(key)
        c.backupKeys.setBackupUri(uri)
        c.backupKeys.setLastBackupEpochMs(if (uri != null) System.currentTimeMillis() else 0L)
        c.backupKeys.setRestoredAtMs(System.currentTimeMillis())
    }

    private fun toProfile(p: BackupManifest.ProfileEntry) = Profile(
        id = p.id,
        displayName = p.displayName,
        role = runCatching { Role.valueOf(p.role) }.getOrDefault(Role.OPERATOR),
        avatarUri = null,
        createdAtEpochMs = p.createdAtEpochMs,
        pinHashArgon2 = p.pinHashArgon2,
        biometricEnrolled = p.biometricEnrolled,
        nfcUid = null,
        voicePrintHashHex = null,
        familiarRarity = runCatching { FamiliarRarity.valueOf(p.familiarRarity) }.getOrDefault(FamiliarRarity.NORMAL),
        behavioralModelEncryptedBase64 = "",
        pairedHosts = emptyList(),
        trustScore = p.trustScore,
        isActive = p.isActive,
    )

    companion object {
        const val JOURNAL = "phantom_restore_v1"
        const val KEY_PENDING = "pending"
        val FLUSH = listOf(
            "phantom_peers_v1",
            "phantom_blocked_v1",
            "phantom_group_reads_v1",
            "phantom_read_receipts_v1",
            "phantom_identity_v1",
            "phantom_outbox_v1",
            "phantom_saved_notes_v1",
            "phantom_peer_handles_v1",
        )
    }
}
