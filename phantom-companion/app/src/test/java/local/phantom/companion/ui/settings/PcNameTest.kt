package local.phantom.companion.ui.settings

import kotlinx.serialization.json.Json
import local.phantom.companion.R
import local.phantom.companion.core.net.pair.PairClaimResponse
import local.phantom.companion.data.PairedDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en", application = android.app.Application::class)
class PcNameTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun device(username: String = "phantom", computerName: String = ""): PairedDevice =
        PairedDevice(
            lanUrl = "https://192.168.1.10:8443",
            devNoPin = true,
            deviceId = "dev-1",
            userId = "user-1",
            username = username,
            deviceJwt = "jwt",
            expiresAt = "2026-10-18T00:00:00Z",
            deviceEd25519Priv = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            deviceEd25519Pub = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=",
            computerName = computerName,
        )

    @Test
    fun paired_computer_label_prefers_computer_name() {
        val fallback = ctx.getString(R.string.pair_computer_fallback)
        val d = device(username = "phantom", computerName = "studio-pc")
        assertEquals("studio-pc", pairedComputerLabel(d, fallback))
    }

    @Test
    fun paired_computer_label_falls_back_to_computer_never_username() {
        val fallback = ctx.getString(R.string.pair_computer_fallback)
        val d = device(username = "phantom", computerName = "   ")
        val label = pairedComputerLabel(d, fallback)
        assertEquals("Computer", label)
        assertNotEquals("phantom", label)
    }

    @Test
    fun legacy_paired_device_json_without_computer_name_decodes_to_empty() {
        val raw = """
            {
              "lanUrl": "https://192.168.1.10:8443",
              "devNoPin": true,
              "deviceId": "dev-1",
              "userId": "user-1",
              "username": "phantom",
              "deviceJwt": "jwt",
              "expiresAt": "2026-10-18T00:00:00Z",
              "deviceEd25519Priv": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
              "deviceEd25519Pub": "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="
            }
        """.trimIndent()
        val decoded = Json.decodeFromString<PairedDevice>(raw)
        assertEquals("", decoded.computerName)
        assertEquals(
            "Computer",
            pairedComputerLabel(decoded, ctx.getString(R.string.pair_computer_fallback)),
        )
    }

    @Test
    fun claim_response_without_computer_name_decodes_to_empty() {
        val raw = """
            {
              "device_jwt": "jwt",
              "device_id": "dev-1",
              "expires_at": "2026-10-18T00:00:00Z",
              "server_proof": "proof",
              "user": {
                "id": "u-1",
                "username": "phantom",
                "role": "ROOT"
              },
              "profile": {
                "id": "p-1",
                "user_id": "u-1",
                "display_name": "Primary",
                "role": "ROOT",
                "is_primary": true
              }
            }
        """.trimIndent()
        val decoded = Json.decodeFromString<PairClaimResponse>(raw)
        assertEquals("", decoded.computerName)
    }
}
