package local.phantom.companion.core.net.pack

import java.security.SecureRandom
import local.phantom.companion.core.license.SignedDocument
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Складач світових пакiв, якого на пристрої не буває. Дзеркало
 * `core-data/PackSigning`: та сама канонізація й той самий Ed25519 — інакше
 * тест доводив би роботу власної криптографії, а не тієї, що в продукті.
 */
class WorldPackSigner {

    private val priv = Ed25519PrivateKeyParameters(SecureRandom())

    val publicHex: String = priv.generatePublicKey().encoded
        .joinToString("") { "%02x".format(it) }

    /** [body] — JSON-обʼєкт БЕЗ поля `sig`; повертає його ж із підписом. */
    fun sign(body: String): String {
        val payload = SignedDocument.canonicalBytes(SignedDocument.parse(body)!!)
        val signer = Ed25519Signer().apply { init(true, priv) }
        signer.update(payload, 0, payload.size)
        val sig = java.util.Base64.getEncoder().encodeToString(signer.generateSignature())
        return body.trimEnd().dropLast(1).trimEnd().trimEnd(',') + ""","sig":"$sig"}"""
    }
}
