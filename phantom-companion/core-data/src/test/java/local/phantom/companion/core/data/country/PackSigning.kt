package local.phantom.companion.core.data.country

import local.phantom.companion.core.license.SignedDocument
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/**
 * Складач паків, якого на пристрої не буває. Дзеркало
 * `core-license/TestSigner`: та сама канонізація, той самий Ed25519 — інакше
 * тест доводив би роботу власної криптографії, а не тієї, що в продукті.
 */
class PackSigner {

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

/**
 * Пак Польщі: рамка, дім у Варшаві — і більше нічого. Не урізаний пак, а
 * повноцінний: жодної стрічки тривог, жодної геометрії, жодного асета,
 * порожній GTFS. Саме та форма, на якій видно, що відсутність типізована
 * ПОПОЛЬНО, а не однією гілкою «країна не наша».
 */
fun polandManifest(
    version: Int = 1,
    minAppBuild: Int = 0,
    extra: String = "",
): String = """
{"id":"PL","title":"Польща","version":$version,"minAppBuild":$minAppBuild,
 "bounds":{"minLat":49.0,"minLon":14.1,"maxLat":54.9,"maxLon":24.2},
 "home":{"lat":52.2297,"lon":21.0122,"zoom":12.0}$extra}
""".trimIndent().replace("\n", "")
