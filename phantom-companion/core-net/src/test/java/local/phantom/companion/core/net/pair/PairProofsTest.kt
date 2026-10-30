package local.phantom.companion.core.net.pair

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.Hkdf
import local.phantom.companion.core.net.crypto.X25519
import local.phantom.companion.core.net.crypto.bytesToB64
import local.phantom.companion.core.net.crypto.bytesToHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PairProofsTest {

    @Test fun `proof verifies on the server side using the same primitives`() {
        // Server side — generate a fresh X25519 keypair the way the
        // backend's pair_crypto.PairingSession does.
        val server = X25519.generateKeypair()
        val nonceBytes = ByteArray(16) { (it + 0x40).toByte() }
        val nonceB64 = bytesToB64(nonceBytes)
        val pairId = "session-abcd-1234"

        val qr = PairingQr(
            v = 1,
            host = "phantom.local",
            ip = "192.168.1.10",
            port = 8000,
            pairId = pairId,
            serverPubB64 = bytesToB64(server.publicKey),
            serverCertSha256 = "dev-no-pin",
            exp = Long.MAX_VALUE,
            nonce = nonceB64,
        )

        // Phone side — generate ephemeral X25519 + long-term Ed25519,
        // run [PairProofs.buildClientProof].
        val ephemeral = X25519.generateKeypair()
        val device = Ed25519.generateKeypair()
        val proof = PairProofs.buildClientProof(
            qr = qr,
            clientPriv = ephemeral.privateKey,
            devicePubEd25519Raw = device.publicKey,
        )

        // Server side — re-derive shared key from server priv + client
        // pub + nonce, recompute the HMAC, expect the proof to match.
        val sharedFromServer = X25519.ecdh(server.privateKey, ephemeral.publicKey)
        val sharedKey = Hkdf.derive(
            ikm = sharedFromServer,
            salt = nonceBytes,
            info = PairProofs.HKDF_INFO,
            length = 32,
        )
        assertArrayEquals(
            "shared key must match across the ECDH boundary",
            sharedKey,
            proof.sharedKey,
        )

        val expectedHmac = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(sharedKey, "HmacSHA256"))
            doFinal(pairId.toByteArray(Charsets.UTF_8) + device.publicKey)
        }
        assertEquals(bytesToB64(expectedHmac), proof.clientProofB64)
    }

    @Test fun `different device key yields different proof`() {
        val server = X25519.generateKeypair()
        val nonce = bytesToB64(ByteArray(16) { 7 })
        val qr = PairingQr(
            v = 1, host = "phantom.local", ip = "192.168.1.10", port = 8000,
            pairId = "p1",
            serverPubB64 = bytesToB64(server.publicKey),
            serverCertSha256 = "dev-no-pin",
            exp = Long.MAX_VALUE, nonce = nonce,
        )
        val eph = X25519.generateKeypair()
        val a = Ed25519.generateKeypair()
        val b = Ed25519.generateKeypair()
        val proofA = PairProofs.buildClientProof(qr, eph.privateKey, a.publicKey)
        val proofB = PairProofs.buildClientProof(qr, eph.privateKey, b.publicKey)
        assertNotEquals(proofA.clientProofB64, proofB.clientProofB64)
    }

    @Test fun `same inputs are deterministic`() {
        val server = X25519.generateKeypair()
        val nonce = bytesToB64(ByteArray(16) { (it * 3).toByte() })
        val qr = PairingQr(
            v = 1, host = "h", ip = "1.2.3.4", port = 8000,
            pairId = "deterministic",
            serverPubB64 = bytesToB64(server.publicKey),
            serverCertSha256 = "dev-no-pin",
            exp = Long.MAX_VALUE, nonce = nonce,
        )
        val eph = X25519.generateKeypair()
        val device = Ed25519.generateKeypair()
        val a = PairProofs.buildClientProof(qr, eph.privateKey, device.publicKey)
        val b = PairProofs.buildClientProof(qr, eph.privateKey, device.publicKey)
        assertEquals(a, b)
        assertEquals(a.clientProofB64, b.clientProofB64)
    }

    @Test fun `HKDF info string is byte-equivalent to backend constant`() {
        // backend: pair_crypto._HKDF_INFO = b"PHANTOM OS/mobile-pair-v1"
        // Documented as a regression guard — if either side renames the
        // string, this assertion catches the drift.
        assertEquals(
            "PHANTOM OS/mobile-pair-v1",
            String(PairProofs.HKDF_INFO, Charsets.UTF_8),
        )
    }

    // -----------------------------------------------------------------------
    // verifyServerProof — phone verifies server signed device JWT with shared key
    // -----------------------------------------------------------------------

    @Test fun `verifyServerProof accepts a server-issued proof`() {
        val sharedKey = ByteArray(32) { it.toByte() }
        val deviceJwt = "header.payload.signature"
        val expected = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(sharedKey, "HmacSHA256"))
            doFinal(deviceJwt.toByteArray(Charsets.UTF_8))
        }
        val proofB64 = bytesToB64(expected)
        assertEquals(true, PairProofs.verifyServerProof(sharedKey, deviceJwt, proofB64))
    }

    @Test fun `verifyServerProof rejects a tampered proof`() {
        val sharedKey = ByteArray(32) { it.toByte() }
        val deviceJwt = "header.payload.signature"
        val expected = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(sharedKey, "HmacSHA256"))
            doFinal(deviceJwt.toByteArray(Charsets.UTF_8))
        }
        // Flip the last byte — a single-bit forgery must be rejected.
        expected[expected.size - 1] = (expected[expected.size - 1].toInt() xor 0x01).toByte()
        assertEquals(false, PairProofs.verifyServerProof(sharedKey, deviceJwt, bytesToB64(expected)))
    }

    @Test fun `verifyServerProof rejects malformed base64 without throwing`() {
        val sharedKey = ByteArray(32) { it.toByte() }
        // Malformed base64 — must not throw, must return false.
        assertEquals(
            false,
            PairProofs.verifyServerProof(sharedKey, "jwt", "@@@not-base64@@@"),
        )
    }

    @Test fun `verifyServerProof rejects when shared key differs`() {
        val keyA = ByteArray(32) { it.toByte() }
        val keyB = ByteArray(32) { (it + 1).toByte() }
        val deviceJwt = "header.payload.signature"
        val proofA = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(keyA, "HmacSHA256"))
            doFinal(deviceJwt.toByteArray(Charsets.UTF_8))
        }
        // The phone holds keyB; the proof was signed with keyA → mismatch.
        assertEquals(false, PairProofs.verifyServerProof(keyB, deviceJwt, bytesToB64(proofA)))
    }

    @Test fun `proof uses 32-byte HKDF output (HMAC-SHA256 key size)`() {
        val server = X25519.generateKeypair()
        val nonce = bytesToB64(ByteArray(16) { 1 })
        val qr = PairingQr(
            v = 1, host = "h", ip = "1.2.3.4", port = 8000,
            pairId = "p", serverPubB64 = bytesToB64(server.publicKey),
            serverCertSha256 = "dev-no-pin", exp = Long.MAX_VALUE, nonce = nonce,
        )
        val eph = X25519.generateKeypair()
        val device = Ed25519.generateKeypair()
        val proof = PairProofs.buildClientProof(qr, eph.privateKey, device.publicKey)
        assertEquals(32, proof.sharedKey.size)
        // HMAC-SHA256 output is always 32 bytes; base64 of 32 bytes is 44 chars w/ pad.
        // We don't assert exact length here because b64ToBytes is the
        // ground truth path — instead probe that the hex round-trips
        // sensibly.
        assertEquals(64, bytesToHex(proof.sharedKey).length)
    }
}
