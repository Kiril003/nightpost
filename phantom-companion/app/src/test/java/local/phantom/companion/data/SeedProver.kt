package local.phantom.companion.data

import local.phantom.companion.core.net.crypto.Ed25519

/**
 * Підписувач для тестів: ключ — зерно з серверного файлу векторів, число й час
 * фіксовані. Підписує РЕЧЕННЯ САМОЇ ЗАЯВИ ([DeviceClaim.sentence]) — рівно те, що
 * на пристрої робить `ClaimSigner.prove`, тож підпис порівнюється з серверним
 * вектором байт у байт.
 */
internal class SeedProver(
    private val seed: ByteArray,
    private val pub: String,
    private val nonce: String,
    private val issuedMs: Long,
) : (DeviceClaim) -> PeerMesh.AttachmentProof? {
    override fun invoke(claim: DeviceClaim): PeerMesh.AttachmentProof {
        val sig = Ed25519.sign(seed, claim.sentence(pub, nonce, issuedMs).toByteArray(Charsets.UTF_8))
        return PeerMesh.AttachmentProof(pub, nonce, issuedMs, java.util.Base64.getEncoder().encodeToString(sig))
    }
}
