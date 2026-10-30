package local.phantom.companion.core.net.pair

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PinnedTrustTest {

    private fun trustManagerOf(pin: () -> String): X509TrustManager {
        val client = PinnedTrust.apply(OkHttpClient.Builder(), pin).build()
        return requireNotNull(client.x509TrustManager)
    }

    private fun certWithFingerprint(hex: String): X509Certificate {
        val der = ByteArray(0)
        return object : X509Certificate() {
            override fun getEncoded(): ByteArray = der
            override fun toString(): String = "тестовий сертифікат"
            override fun checkValidity() = Unit
            override fun checkValidity(date: java.util.Date?) = Unit
            override fun getVersion(): Int = 3
            override fun getSerialNumber(): java.math.BigInteger = java.math.BigInteger.ONE
            override fun getIssuerDN(): java.security.Principal? = null
            override fun getSubjectDN(): java.security.Principal? = null
            override fun getNotBefore(): java.util.Date = java.util.Date(0)
            override fun getNotAfter(): java.util.Date = java.util.Date(Long.MAX_VALUE)
            override fun getTBSCertificate(): ByteArray = der
            override fun getSignature(): ByteArray = der
            override fun getSigAlgName(): String = "SHA256withECDSA"
            override fun getSigAlgOID(): String = ""
            override fun getSigAlgParams(): ByteArray? = null
            override fun getIssuerUniqueID(): BooleanArray? = null
            override fun getSubjectUniqueID(): BooleanArray? = null
            override fun getKeyUsage(): BooleanArray? = null
            override fun getBasicConstraints(): Int = -1
            override fun getPublicKey(): java.security.PublicKey? = null
            override fun verify(key: java.security.PublicKey?) = Unit
            override fun verify(key: java.security.PublicKey?, sigProvider: String?) = Unit
            override fun hasUnsupportedCriticalExtension(): Boolean = false
            override fun getCriticalExtensionOIDs(): MutableSet<String> = mutableSetOf()
            override fun getNonCriticalExtensionOIDs(): MutableSet<String> = mutableSetOf()
            override fun getExtensionValue(oid: String?): ByteArray? = null
        }.also { require(PinnedTrust.fingerprintOf(it) == hex) { "фікстура розійшлась" } }
    }

    private val emptyDerFingerprint =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    @Test
    fun `сертифікат із тим самим відбитком приймається`() {
        val tm = trustManagerOf { emptyDerFingerprint }
        tm.checkServerTrusted(arrayOf(certWithFingerprint(emptyDerFingerprint)), "ECDHE")
    }

    @Test
    fun `великі літери й двокрапки не заважають`() {
        val shouty = emptyDerFingerprint.uppercase().chunked(2).joinToString(":")
        val tm = trustManagerOf { shouty }
        tm.checkServerTrusted(arrayOf(certWithFingerprint(emptyDerFingerprint)), "ECDHE")
    }

    @Test
    fun `чужий сертифікат відхиляється`() {
        val tm = trustManagerOf { "0".repeat(64) }
        assertThrows(CertificateException::class.java) {
            tm.checkServerTrusted(arrayOf(certWithFingerprint(emptyDerFingerprint)), "ECDHE")
        }
    }

    @Test
    fun `без відбитка не довіряємо нікому`() {
        val tm = trustManagerOf { "" }
        assertThrows(CertificateException::class.java) {
            tm.checkServerTrusted(arrayOf(certWithFingerprint(emptyDerFingerprint)), "ECDHE")
        }
    }

    @Test
    fun `сентинел не відмикає зʼєднання`() {
        val tm = trustManagerOf { PairingQr.DEV_NO_PIN_SENTINEL }
        assertThrows(CertificateException::class.java) {
            tm.checkServerTrusted(arrayOf(certWithFingerprint(emptyDerFingerprint)), "ECDHE")
        }
    }

    @Test
    fun `порожній ланцюг відхиляється`() {
        val tm = trustManagerOf { emptyDerFingerprint }
        assertThrows(CertificateException::class.java) {
            tm.checkServerTrusted(emptyArray(), "ECDHE")
        }
    }

    @Test
    fun `жоден центр сертифікації не вважається своїм`() {
        val tm = trustManagerOf { emptyDerFingerprint }
        assertEquals(0, tm.acceptedIssuers.size)
    }
}
