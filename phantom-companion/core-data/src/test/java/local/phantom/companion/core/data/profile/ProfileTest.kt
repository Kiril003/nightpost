package local.phantom.companion.core.data.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure-JVM unit coverage for the [Profile] / [PairedHost] domain
 * invariants. Room-level DAO tests live in `androidTest/` and run
 * against the on-device emulator (Robolectric on aarch64 is too
 * fragile to gate the build on for this Radxa host); the pieces
 * exercised here are the value semantics and validation that downstream
 * code relies on.
 */
class ProfileTest {

    private fun freshProfile(
        id: String = "profile-1",
        displayName: String = "Кирило",
        role: Role = Role.ROOT,
        trustScore: Float = 1f,
        pairedHosts: List<PairedHost> = emptyList(),
        isActive: Boolean = false,
    ): Profile = Profile(
        id = id,
        displayName = displayName,
        role = role,
        avatarUri = null,
        createdAtEpochMs = 1_700_000_000_000L,
        pinHashArgon2 = "argon2id\$v=19\$m=65536,t=3,p=1\$saltsaltsaltsalt\$hash",
        biometricEnrolled = false,
        nfcUid = null,
        voicePrintHashHex = null,
        familiarRarity = FamiliarRarity.NORMAL,
        behavioralModelEncryptedBase64 = "AAAAAA==",
        pairedHosts = pairedHosts,
        trustScore = trustScore,
        isActive = isActive,
    )

    private fun freshHost(
        hostId: String = "radxa-loft",
        isPrimary: Boolean = true,
        capabilities: Set<HostCapability> = setOf(HostCapability.STATE, HostCapability.SENSOR),
    ): PairedHost = PairedHost(
        hostId = hostId,
        lanUrl = "http://192.168.1.10:8000",
        deviceJwt = "header.payload.sig",
        deviceEd25519PrivBase64 = "MIIBIjANBgkqhkiG9w0BAQE=",
        lastSeenAtEpochMs = 1_700_000_500_000L,
        isPrimary = isPrimary,
        capabilities = capabilities,
    )

    // ── Profile validation ─────────────────────────────────────────

    @Test fun `Profile rejects blank id`() {
        assertThrowsIAE { freshProfile(id = "") }
    }

    @Test fun `Profile rejects blank displayName`() {
        assertThrowsIAE { freshProfile(displayName = "  ") }
    }

    @Test fun `Profile accepts an empty displayName (name is optional)`() {
        assertEquals("", freshProfile(displayName = "").displayName)
    }

    @Test fun `Profile rejects displayName longer than MAX_DISPLAY_NAME_LENGTH`() {
        val tooLong = "x".repeat(Profile.MAX_DISPLAY_NAME_LENGTH + 1)
        assertThrowsIAE { freshProfile(displayName = tooLong) }
    }

    @Test fun `Profile accepts displayName exactly at the cap`() {
        val atLimit = "x".repeat(Profile.MAX_DISPLAY_NAME_LENGTH)
        val p = freshProfile(displayName = atLimit)
        assertEquals(atLimit, p.displayName)
    }

    @Test fun `Profile rejects negative createdAt`() {
        assertThrowsIAE {
            Profile(
                id = "id",
                displayName = "n",
                role = Role.GUEST,
                avatarUri = null,
                createdAtEpochMs = -1L,
                pinHashArgon2 = "h",
                biometricEnrolled = false,
                nfcUid = null,
                voicePrintHashHex = null,
                familiarRarity = FamiliarRarity.OFF,
                behavioralModelEncryptedBase64 = "",
                pairedHosts = emptyList(),
                trustScore = 0.5f,
            )
        }
    }

    @Test fun `Profile rejects blank pinHashArgon2`() {
        assertThrowsIAE {
            Profile(
                id = "id",
                displayName = "n",
                role = Role.GUEST,
                avatarUri = null,
                createdAtEpochMs = 0L,
                pinHashArgon2 = "",
                biometricEnrolled = false,
                nfcUid = null,
                voicePrintHashHex = null,
                familiarRarity = FamiliarRarity.OFF,
                behavioralModelEncryptedBase64 = "",
                pairedHosts = emptyList(),
                trustScore = 0.5f,
            )
        }
    }

    @Test fun `Profile rejects trustScore below zero`() {
        assertThrowsIAE { freshProfile(trustScore = -0.01f) }
    }

    @Test fun `Profile rejects trustScore above one`() {
        assertThrowsIAE { freshProfile(trustScore = 1.01f) }
    }

    @Test fun `Profile accepts boundary trustScores`() {
        assertEquals(0f, freshProfile(trustScore = 0f).trustScore, 0f)
        assertEquals(1f, freshProfile(trustScore = 1f).trustScore, 0f)
    }

    @Test fun `Profile defaults isActive to false`() {
        assertFalse(freshProfile().isActive)
    }

    @Test fun `FamiliarRarity Default is NORMAL`() {
        assertEquals(FamiliarRarity.NORMAL, FamiliarRarity.Default)
    }

    // ── PairedHost validation ──────────────────────────────────────

    @Test fun `PairedHost rejects blank hostId`() {
        assertThrowsIAE { freshHost(hostId = "") }
    }

    @Test fun `PairedHost rejects negative lastSeenAt`() {
        assertThrowsIAE {
            PairedHost(
                hostId = "h",
                lanUrl = "u",
                deviceJwt = "j",
                deviceEd25519PrivBase64 = "k",
                lastSeenAtEpochMs = -1L,
                isPrimary = false,
                capabilities = emptySet(),
            )
        }
    }

    @Test fun `PairedHost preserves capability set`() {
        val caps = setOf(HostCapability.STATE, HostCapability.MAP, HostCapability.AGENT_ACTING)
        val h = freshHost(capabilities = caps)
        assertEquals(caps, h.capabilities)
    }

    // ── Mapping round-trip ─────────────────────────────────────────

    @Test fun `Profile to Entity to Domain preserves every field`() {
        val original = freshProfile(
            isActive = true,
            pairedHosts = listOf(
                freshHost("home", isPrimary = true, capabilities = setOf(HostCapability.STATE, HostCapability.SENSOR)),
                freshHost("lab", isPrimary = false, capabilities = setOf(HostCapability.MAP)),
            ),
        )
        val entity = original.toEntity()
        val hosts = original.pairedHosts.map { it.toEntity(original.id).toDomain() }
        val rehydrated = entity.toDomain(hosts)

        assertEquals(original.id, rehydrated.id)
        assertEquals(original.displayName, rehydrated.displayName)
        assertEquals(original.role, rehydrated.role)
        assertEquals(original.familiarRarity, rehydrated.familiarRarity)
        assertEquals(original.trustScore, rehydrated.trustScore, 0f)
        assertEquals(original.isActive, rehydrated.isActive)
        assertEquals(original.behavioralModelEncryptedBase64, rehydrated.behavioralModelEncryptedBase64)
        assertEquals(original.pairedHosts, rehydrated.pairedHosts)
    }

    @Test fun `PairedHost CSV capabilities round-trip`() {
        val converters = ProfileConverters()
        val caps = setOf(
            HostCapability.STATE,
            HostCapability.SENSOR,
            HostCapability.AGENT,
            HostCapability.AGENT_ACTING,
        )
        val csv = converters.capabilitiesToCsv(caps)
        val restored = converters.capabilitiesFromCsv(csv)
        assertEquals(caps, restored)
    }

    @Test fun `ProfileConverters drop unknown capability names (forward-compat)`() {
        val converters = ProfileConverters()
        val mixed = "STATE,SOMETHING_FROM_THE_FUTURE,SENSOR,, ,MAP"
        val parsed = converters.capabilitiesFromCsv(mixed)
        assertEquals(setOf(HostCapability.STATE, HostCapability.SENSOR, HostCapability.MAP), parsed)
    }

    @Test fun `ProfileConverters empty CSV returns empty set, never null`() {
        val converters = ProfileConverters()
        assertTrue(converters.capabilitiesFromCsv("").isEmpty())
        assertTrue(converters.capabilitiesFromCsv("   ").isEmpty())
    }

    @Test fun `ProfileConverters fallback role for unknown name (forward-compat)`() {
        val converters = ProfileConverters()
        // OPERATOR is the safe middle ground; the host-side server stores
        // the source of truth so a transient mismatch resolves on next sync.
        assertEquals(Role.OPERATOR, converters.stringToRole("FUTURE_ADMIN"))
        assertEquals(FamiliarRarity.NORMAL, converters.stringToRarity("FUTURE_LEVEL"))
    }

    @Test fun `Profile data class equality uses values, not references`() {
        val a = freshProfile()
        val b = freshProfile()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test fun `Profile copy works for mutating non-validated fields`() {
        val a = freshProfile()
        val b = a.copy(displayName = "Гість")
        assertNotEquals(a, b)
        assertEquals("Гість", b.displayName)
    }

    @Test fun `Profile entity preserves null avatar and optional fields`() {
        val p = freshProfile()
        val e = p.toEntity()
        assertNull(e.avatarUri)
        assertNull(e.nfcUid)
        assertNull(e.voicePrintHashHex)
    }

    // ── Helpers ────────────────────────────────────────────────────

    private inline fun assertThrowsIAE(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
