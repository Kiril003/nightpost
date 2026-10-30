package local.phantom.companion.data.backup

import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import local.phantom.companion.core.design.theme.FONT_SCALE_PREF_KEY
import local.phantom.companion.core.design.theme.THEME_MODE_PREF_KEY
import local.phantom.companion.core.design.theme.THEME_PREFS_FILE
import local.phantom.companion.core.net.peer.PeerFile
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerWhere
import local.phantom.companion.data.ClaimKey
import local.phantom.companion.data.CustomCircle
import local.phantom.companion.data.DeviceIdentityStore

@Serializable
data class BackupManifest(
    val v: Int = 1,
    @SerialName("at_ms") val atMs: Long = 0L,
    val entries: List<String> = ALLOWED_SECTIONS,
    val root: RootKey? = null,
    val claims: ClaimKeyEntry? = null,
    val roster: RosterSection = RosterSection(),
    val chats: ChatsSection = ChatsSection(),
    val mem: MemorySection = MemorySection(),
    val settings: SettingsSection = SettingsSection(),
    val profiles: ProfilesSection = ProfilesSection(),
    @SerialName("web_kv") val web: WebSection = WebSection(),
    @SerialName("self_kv") val selfFiles: SelfFilesSection = SelfFilesSection(),
    @SerialName("local_kv") val local: LocalSection = LocalSection(),
) {

    @Serializable
    data class RootKey(
        val id: String,
        @SerialName("ed_pub") val edPub: String,
        @SerialName("ed_priv") val edPriv: String,
        @SerialName("dh_pub") val dhPub: String,
        @SerialName("dh_priv") val dhPriv: String,
    ) {
        fun toIdentity(): DeviceIdentityStore.Identity = DeviceIdentityStore.Identity(
            id = id,
            publicKeyB64 = edPub,
            privateKey = b64Decode(edPriv),
            dhPublicKeyB64 = dhPub,
            dhPrivateKey = b64Decode(dhPriv),
        )
    }

    @Serializable
    data class ClaimKeyEntry(
        @SerialName("ed_pub") val edPub: String,
        @SerialName("ed_priv") val edPriv: String,
    ) {
        fun toClaimKey(): ClaimKey = ClaimKey(
            publicKeyB64 = edPub,
            privateKey = b64Decode(edPriv),
        )
    }

    @Serializable
    data class RosterSection(
        @SerialName("peers_b64") val peersB64: List<String> = emptyList(),
        @SerialName("circles_b64") val circlesB64: Map<String, List<String>> = emptyMap(),
        @SerialName("usr_circles_b64") val usrCirclesB64: List<String> = emptyList(),
        val declined: List<String> = emptyList(),
        val blocked: List<String> = emptyList(),
        @SerialName("handles_b64") val handlesB64: Map<String, String> = emptyMap(),
    ) {
        fun peers(): List<PeerIdentity> =
            peersB64.mapNotNull { runCatching { json.decodeFromString<PeerIdentity>(b64DecodeStr(it)) }.getOrNull() }

        fun circles(): Map<String, Set<String>> =
            circlesB64.mapValues { (_, list) -> list.map(::b64DecodeStr).toSet() }

        fun customCircles(): List<CustomCircle> =
            usrCirclesB64.mapNotNull { runCatching { json.decodeFromString<CustomCircle>(b64DecodeStr(it)) }.getOrNull() }

        fun handles(): Map<String, String> =
            handlesB64.mapValues { (_, v) -> b64DecodeStr(v) }
    }

    @Serializable
    data class ChatsSection(
        @SerialName("msgs_b64") val msgsB64: List<String> = emptyList(),
        @SerialName("saved_b64") val savedB64: List<String> = emptyList(),
        @SerialName("reads_flg") val readsFlg: Int = 1,
        @SerialName("grp_reads_b64") val grpReadsB64: Map<String, Long> = emptyMap(),
    ) {
        fun messages(): List<PeerMessage> =
            msgsB64.mapNotNull { runCatching { json.decodeFromString<PeerMessage>(b64DecodeStr(it)) }.getOrNull() }

        fun savedNotes(): List<PeerMessage> =
            savedB64.mapNotNull { runCatching { json.decodeFromString<PeerMessage>(b64DecodeStr(it)) }.getOrNull() }

        fun readReceiptsEnabled(): Boolean = readsFlg != 0

        fun groupReads(): Map<String, Long> =
            grpReadsB64.entries.associate { (k, v) -> b64DecodeStr(k) to v }
    }

    @Serializable
    data class MemorySection(
        @SerialName("items_b64") val itemsB64: List<String> = emptyList(),
    ) {
        fun items(): List<JsonObject> =
            itemsB64.mapNotNull { runCatching { json.parseToJsonElement(b64DecodeStr(it)).jsonObject }.getOrNull() }
    }

    @Serializable
    data class SettingsSection(
        @SerialName("prefs_b64") val prefsB64: Map<String, Map<String, String>> = emptyMap(),
        @SerialName("typed_b64") val typedB64: Map<String, Map<String, String>> = emptyMap(),
    ) {
        fun prefs(): Map<String, Map<String, String>> = decodeNested(prefsB64)

        fun typed(): Map<String, Map<String, Any>> {
            val tagged = decodeNested(typedB64).mapValues { (_, m) ->
                m.mapNotNull { (k, raw) -> raw.indexOf(':').takeIf { it > 0 }?.let { k to (raw.substring(0, it) to raw.substring(it + 1)) } }.toMap()
            }
            val source: Map<String, Map<String, Pair<String?, String>>> =
                if (typedB64.isNotEmpty()) tagged else prefs().mapValues { (_, m) -> m.mapValues { (_, v) -> null to v } }
            val out = LinkedHashMap<String, Map<String, Any>>()
            for ((store, entries) in source) {
                val clean = LinkedHashMap<String, Any>()
                for ((key, tv) in entries) {
                    val kind = kindOf(store, key) ?: continue
                    coerce(kind, tv.second)?.let { clean[key] = it }
                }
                if (clean.isNotEmpty()) out[store] = clean
            }
            return out
        }

        private fun decodeNested(src: Map<String, Map<String, String>>): Map<String, Map<String, String>> =
            src.entries.associate { (storeB64, entriesB64) ->
                b64DecodeStr(storeB64) to entriesB64.entries.associate { (kB64, vB64) ->
                    b64DecodeStr(kB64) to b64DecodeStr(vB64)
                }
            }
    }

    enum class Kind(val tag: String) { STR("s"), BOOL("b"), INT("i"), LONG("l"), FLOAT("f"), SET("ss") }

    @Serializable
    data class ProfilesSection(
        @SerialName("profiles_b64") val profilesB64: List<String> = emptyList(),
    ) {
        fun profiles(): List<ProfileEntry> =
            profilesB64.mapNotNull { runCatching { json.decodeFromString<ProfileEntry>(b64DecodeStr(it)) }.getOrNull() }
    }

    @Serializable
    data class WebSection(
        @SerialName("items_b64") val itemsB64: Map<String, String> = emptyMap(),
    ) {
        fun items(): Map<String, String> =
            itemsB64.entries.associate { (k, v) -> b64DecodeStr(k) to b64DecodeStr(v) }
    }

    @Serializable
    data class SelfFilesSection(
        @SerialName("files_b64") val filesB64: Map<String, String> = emptyMap(),
    ) {
        fun files(): Map<String, ByteArray> =
            filesB64.entries.associate { (k, v) -> b64DecodeStr(k) to b64Decode(v) }
    }

    @Serializable
    data class LocalSection(
        @SerialName("slots_b64") val slotsB64: Map<String, Map<String, String>> = emptyMap(),
        @SerialName("rows_b64") val rowsB64: Map<String, List<String>> = emptyMap(),
        @SerialName("media_left") val media: List<MediaLeft> = emptyList(),
    ) {
        fun slots(): Map<String, Map<String, String>> =
            slotsB64.entries.associate { (s, m) -> b64DecodeStr(s) to m.entries.associate { (k, v) -> b64DecodeStr(k) to b64DecodeStr(v) } }
                .mapValues { (slot, m) -> m.filterKeys { BackupLocal.allowedSlot(slot, it) } }
                .filterValues { it.isNotEmpty() }

        fun rows(table: String): List<String> = rowsB64[table].orEmpty().map(::b64DecodeStr)
    }

    @Serializable
    data class MediaLeft(val kind: String, val name: String, val bytes: Long)

    @Serializable
    data class ProfileEntry(
        val id: String,
        val displayName: String,
        val role: String,
        val createdAtEpochMs: Long,
        val pinHashArgon2: String,
        val biometricEnrolled: Boolean = false,
        val familiarRarity: String = "NORMAL",
        val trustScore: Float = 1.0f,
        val isActive: Boolean = false,
    )

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        val ALLOWED_SECTIONS = listOf("root", "claims", "roster", "chats", "mem", "settings", "profiles", "web_kv", "self_kv", "local_kv")

        val ALLOWED_SETTINGS_STORES = setOf(
            THEME_PREFS_FILE,
            "phantom_quiet_hours_v1",
            "phantom_link_previews_v1",
            "phantom_lockscreen_reveal_v1",
            "phantom_brain_prefs",
        )

        // Лише ключі, що мають сенс на іншому телефоні: app_night_mode_set — кеш системи цього апарата.
        fun kindOf(store: String, key: String): Kind? = when (store) {
            THEME_PREFS_FILE -> when (key) {
                THEME_MODE_PREF_KEY, "ui_theme" -> Kind.STR
                FONT_SCALE_PREF_KEY -> Kind.FLOAT
                else -> null
            }
            "phantom_quiet_hours_v1" -> when (key) {
                "on" -> Kind.BOOL
                "from", "to" -> Kind.STR
                "except" -> Kind.SET
                else -> null
            }
            "phantom_link_previews_v1" -> if (key == "make") Kind.BOOL else null
            "phantom_lockscreen_reveal_v1" -> if (key == "default" || key.startsWith("chat:")) Kind.STR else null
            "phantom_brain_prefs" -> if (key == "routing_strategy") Kind.STR else null
            else -> null
        }

        private fun coerce(kind: Kind, raw: String): Any? = when (kind) {
            Kind.STR -> raw
            Kind.BOOL -> raw.toBooleanStrictOrNull()
            Kind.INT -> raw.toIntOrNull()
            Kind.LONG -> raw.toLongOrNull()
            Kind.FLOAT -> raw.toFloatOrNull()
            Kind.SET -> decodeSet(raw)
        }

        // Старі копії писали набір як toString(): «[a, b]».
        private fun decodeSet(raw: String): Set<String>? {
            runCatching { json.decodeFromString<List<String>>(raw) }.getOrNull()?.let { return it.toSet() }
            val t = raw.trim()
            if (!t.startsWith("[") || !t.endsWith("]")) return null
            return t.substring(1, t.length - 1).split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }

        private fun tagOf(kind: Kind, value: Any): String? {
            val payload = when (kind) {
                Kind.SET -> (value as? Set<*>)?.filterIsInstance<String>()?.sorted()?.let { json.encodeToString(it) }
                else -> value.toString().takeIf { coerce(kind, it) != null }
            } ?: return null
            return kind.tag + ":" + payload
        }

        private val FORBIDDEN_STORE_PATTERNS = listOf(
            "solana",
            "wallet",
            "vault",
            "bip39",
            "mnemonic",
            "seed",
            "phantom_pair",
            "own_self",
            "msgr",
            "peer_photos",
            "photo",
            "media",
        )

        private val FORBIDDEN_KEY_NAMES = setOf(
            "secret",
            "secret_bound",
            "mnemonic",
            "seed",
            "words",
            "wallet_words",
            "paired_device",
            "devicejwt",
            "deviceed25519priv",
            "deviceed25519pub",
        )

        private val TOKEN_REGEX = Regex("(?<![A-Za-z0-9+/=_-])[a-z]+(?![A-Za-z0-9+/=_-])")
        private val WORD_REGEX = Regex("[a-z]+")
        private val MNEMONIC_SHAPE = Regex("^([a-z]{3,8}\\s+){11,23}[a-z]{3,8}$")

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun decode(raw: String): BackupManifest? =
            runCatching { json.decodeFromString(serializer(), raw) }.getOrNull()

        fun collect(
            identity: DeviceIdentityStore.Identity?,
            claimKey: ClaimKey?,
            peers: List<PeerIdentity> = emptyList(),
            circles: Map<String, Set<String>> = emptyMap(),
            customCircles: List<CustomCircle> = emptyList(),
            declined: Set<String> = emptySet(),
            blocked: Set<String> = emptySet(),
            handles: Map<String, String> = emptyMap(),
            messages: List<PeerMessage> = emptyList(),
            savedNotes: List<PeerMessage> = emptyList(),
            readReceiptsEnabled: Boolean = true,
            groupReads: Map<String, Long> = emptyMap(),
            memoryItems: List<JsonObject> = emptyList(),
            candidateSettings: Map<String, Map<String, Any?>> = emptyMap(),
            profiles: List<ProfileEntry> = emptyList(),
            web: Map<String, String> = emptyMap(),
            selfFiles: Map<String, ByteArray> = emptyMap(),
            slots: Map<String, Map<String, String>> = emptyMap(),
            rows: Map<String, List<String>> = emptyMap(),
            media: List<MediaLeft> = emptyList(),
            atMs: Long = System.currentTimeMillis(),
        ): BackupManifest {
            val deviceIds = peers.filter { it.kind == PeerIdentity.Kind.DEVICE }.mapTo(HashSet()) { it.id }
            val cleanPeers = peers
                .filter { it.kind == PeerIdentity.Kind.PERSON }
                .map { it.copy(avatarThumbB64 = null) }

            val cleanCircles = circles
                .filterKeys { it !in deviceIds }
                .filterValues { it.isNotEmpty() }
                .mapValues { (_, set) -> set.map(::b64EncodeStr) }

            val cleanHandles = handles
                .filterKeys { it !in deviceIds }
                .mapValues { (_, h) -> b64EncodeStr(h) }

            val cleanMessages = messages.filter { msg ->
                msg.toId !in deviceIds &&
                    msg.fromId !in deviceIds &&
                    !PeerFile.isChunk(msg.body) &&
                    !PeerWhere.isWhere(msg.body)
            }

            val cleanSaved = savedNotes.filter { msg ->
                !PeerFile.isChunk(msg.body) && !PeerWhere.isWhere(msg.body)
            }

            val cleanSettings = sanitizeSettings(candidateSettings)

            return BackupManifest(
                v = 1,
                atMs = atMs,
                entries = ALLOWED_SECTIONS,
                root = identity?.let {
                    RootKey(
                        id = it.id,
                        edPub = it.publicKeyB64,
                        edPriv = b64Encode(it.privateKey),
                        dhPub = it.dhPublicKeyB64,
                        dhPriv = b64Encode(it.dhPrivateKey),
                    )
                },
                claims = claimKey?.let {
                    ClaimKeyEntry(
                        edPub = it.publicKeyB64,
                        edPriv = b64Encode(it.privateKey),
                    )
                },
                roster = RosterSection(
                    peersB64 = cleanPeers.map { b64EncodeStr(json.encodeToString(PeerIdentity.serializer(), it)) },
                    circlesB64 = cleanCircles,
                    usrCirclesB64 = customCircles.map { b64EncodeStr(json.encodeToString(CustomCircle.serializer(), it)) },
                    declined = declined.filterNot { it in deviceIds }.sorted(),
                    blocked = blocked.filterNot { it in deviceIds }.sorted(),
                    handlesB64 = cleanHandles,
                ),
                chats = ChatsSection(
                    msgsB64 = cleanMessages.map { b64EncodeStr(json.encodeToString(PeerMessage.serializer(), it)) },
                    savedB64 = cleanSaved.map { b64EncodeStr(json.encodeToString(PeerMessage.serializer(), it)) },
                    readsFlg = if (readReceiptsEnabled) 1 else 0,
                    grpReadsB64 = groupReads.entries.associate { (k, v) -> b64EncodeStr(k) to v },
                ),
                mem = MemorySection(
                    itemsB64 = memoryItems.map { b64EncodeStr(it.toString()) },
                ),
                settings = SettingsSection(
                    typedB64 = cleanSettings.entries.associate { (store, map) ->
                        b64EncodeStr(store) to map.entries.associate { (k, v) ->
                            b64EncodeStr(k) to b64EncodeStr(v)
                        }
                    },
                ),
                profiles = ProfilesSection(
                    profilesB64 = profiles.map { b64EncodeStr(json.encodeToString(ProfileEntry.serializer(), it)) },
                ),
                web = WebSection(
                    itemsB64 = web.filterKeys(WebBackupStash::allowed).entries.associate { (k, v) -> b64EncodeStr(k) to b64EncodeStr(v) },
                ),
                selfFiles = SelfFilesSection(
                    filesB64 = selfFiles.filterKeys(BackupSelfFiles::allowed).entries.associate { (k, v) -> b64EncodeStr(k) to b64Encode(v) },
                ),
                local = LocalSection(
                    slotsB64 = slots.entries.associate { (slot, m) ->
                        b64EncodeStr(slot) to m.filterKeys { BackupLocal.allowedSlot(slot, it) }.entries.associate { (k, v) -> b64EncodeStr(k) to b64EncodeStr(v) }
                    }.filterValues { it.isNotEmpty() },
                    rowsB64 = rows.filterKeys { it in BackupLocal.TABLES }.mapValues { (_, list) -> list.map(::b64EncodeStr) },
                    media = media,
                ),
            )
        }

        fun sanitizeSettings(
            candidate: Map<String, Map<String, Any?>>,
        ): Map<String, Map<String, String>> {
            val out = LinkedHashMap<String, Map<String, String>>()
            for ((store, entries) in candidate) {
                val lowerStore = store.lowercase()
                if (store !in ALLOWED_SETTINGS_STORES) continue
                if (FORBIDDEN_STORE_PATTERNS.any { lowerStore.contains(it) }) continue
                val cleanEntries = LinkedHashMap<String, String>()
                for ((k, v) in entries) {
                    if (v == null) continue
                    val lowerKey = k.lowercase()
                    if (lowerKey in FORBIDDEN_KEY_NAMES) continue
                    if (FORBIDDEN_STORE_PATTERNS.any { lowerKey.contains(it) }) continue
                    val kind = kindOf(store, k) ?: continue
                    val text = if (v is Set<*>) v.joinToString(" ") else v.toString()
                    if (MNEMONIC_SHAPE.matches(text.trim().lowercase())) continue
                    cleanEntries[k] = tagOf(kind, v) ?: continue
                }
                if (cleanEntries.isNotEmpty()) {
                    out[store] = cleanEntries
                }
            }
            return out
        }

        fun scanWalletWords(manifestText: String, wordList: Collection<String>): List<String> {
            val dict = wordList.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            if (dict.isEmpty()) return emptyList()

            val hits = LinkedHashSet<String>()
            TOKEN_REGEX.findAll(manifestText).forEach { m ->
                if (m.value in dict) hits += m.value
            }

            val parsed = decode(manifestText)
            if (parsed != null) {
                val decodedTexts = buildList {
                    parsed.settings.prefs().forEach { (store, entries) ->
                        add(store)
                        entries.forEach { (k, v) ->
                            add(k)
                            add(v)
                        }
                    }
                    parsed.settings.typed().forEach { (store, entries) ->
                        add(store)
                        entries.forEach { (k, v) ->
                            add(k)
                            add(if (v is Set<*>) v.joinToString(" ") else v.toString())
                        }
                    }
                }
                for (text in decodedTexts) {
                    val words = WORD_REGEX.findAll(text.lowercase()).map { it.value }.toList()
                    if (dict.size <= 24) {
                        words.filter { it in dict }.forEach { hits += it }
                    } else if (words.size >= 12 && words.count { it in dict } >= 12) {
                        words.filter { it in dict }.forEach { hits += it }
                    }
                }
            }
            return hits.toList()
        }

        private fun b64Encode(bytes: ByteArray): String =
            Base64.getEncoder().withoutPadding().encodeToString(bytes)

        private fun b64Decode(text: String): ByteArray =
            Base64.getDecoder().decode(text)

        private fun b64EncodeStr(text: String): String =
            b64Encode(text.toByteArray(Charsets.UTF_8))

        private fun b64DecodeStr(text: String): String =
            b64Decode(text).toString(Charsets.UTF_8)
    }
}
