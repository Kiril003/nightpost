package local.phantom.companion.data.backup

import android.content.Context
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import local.phantom.companion.data.AppContainer
import local.phantom.companion.data.PeerHandleBook

fun AppContainer.collectBackupManifest(): BackupManifest {
    val app = applicationContext
    val exportedMemory = runCatching {
        (memoryRuntime.ledger.export()["items"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
    }.getOrDefault(emptyList())

    val exportedProfiles = runCatching {
        kotlinx.coroutines.runBlocking {
            profileStore.allProfiles().map { p ->
                BackupManifest.ProfileEntry(
                    id = p.id,
                    displayName = p.displayName,
                    role = p.role.name,
                    createdAtEpochMs = p.createdAtEpochMs,
                    pinHashArgon2 = p.pinHashArgon2,
                    biometricEnrolled = p.biometricEnrolled,
                    familiarRarity = p.familiarRarity.name,
                    trustScore = p.trustScore,
                    isActive = p.isActive,
                )
            }
        }
    }.getOrDefault(emptyList())

    return BackupManifest.collect(
        identity = runCatching { identity.get() }.getOrNull(),
        claimKey = runCatching { claimKeys.get() }.getOrNull(),
        peers = peers.peers.value,
        circles = peers.memberships.value,
        customCircles = peers.customCircles.value,
        declined = peers.declinedIds(),
        handles = runCatching { PeerHandleBook.open(app).all() }.getOrDefault(emptyMap()),
        blocked = peerBlocks.blocked.value,
        messages = outboxStore.load(),
        savedNotes = savedNotes.all(),
        readReceiptsEnabled = readReceipts.enabled.value,
        groupReads = groupReads.marks.value,
        memoryItems = exportedMemory,
        candidateSettings = BackupSettings.read(app),
        profiles = exportedProfiles,
        web = webBackupStash.all(),
        selfFiles = BackupSelfFiles.collect(java.io.File(app.filesDir, "profile_self")),
        slots = collectLocalSlots(),
        rows = collectLocalRows(),
        media = BackupLocal.mediaIn(java.io.File(app.filesDir, local.phantom.companion.core.net.peer.PeerFileRoute.DIR)),
    )
}

fun AppContainer.sealBackup(output: OutputStream, passphrase: CharArray): BackupFormat.SealedKey =
    BackupFormat.sealManifest(
        manifest = collectBackupManifest(),
        output = output,
        passphrase = passphrase,
        keyStore = backupKeys,
    )

fun AppContainer.resealBackup(output: OutputStream): BackupFormat.Header? =
    BackupFormat.resealManifest(
        manifest = collectBackupManifest(),
        output = output,
        keyStore = backupKeys,
    )

fun AppContainer.openBackup(input: InputStream, passphrase: CharArray): BackupManifest? =
    BackupFormat.openManifest(input = input, passphrase = passphrase)
