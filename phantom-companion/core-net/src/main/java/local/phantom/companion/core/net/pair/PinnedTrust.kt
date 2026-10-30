package local.phantom.companion.core.net.pair

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.MessageDigest
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/** Довіра рівно до одного сертифіката — того, чий відбиток приїхав у QR. */
object PinnedTrust {

    fun fingerprintOf(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString("") { "%02x".format(it) }

    fun normalize(pin: String): String =
        pin.trim().lowercase().replace(":", "").replace(" ", "")

    fun apply(builder: OkHttpClient.Builder, pin: () -> String): OkHttpClient.Builder {
        val trust = SinglePinTrustManager(pin)
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<javax.net.ssl.TrustManager>(trust), null)
        return builder
            .sslSocketFactory(context.socketFactory, trust)
            .hostnameVerifier { _, _ -> true }
    }

    /**
     * Довіра без відбитка — рівно для одного запиту, який той відбиток і несе.
     *
     * Вхід за кодом — це курка і яйце: перевіряти сертифікат ще нема чим, бо
     * його відбиток лежить у відповіді. Шифрування тут усе одно виграє в
     * попереднього відкритого HTTP — пасивний слухач більше не читає ані код,
     * ані ключі. Далі, на /pair/claim, зʼєднання вже прибите до відбитка.
     */
    fun bootstrap(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val trust = AcceptAnyTrustManager()
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<javax.net.ssl.TrustManager>(trust), null)
        return builder
            .sslSocketFactory(context.socketFactory, trust)
            .hostnameVerifier { _, _ -> true }
    }

    private class AcceptAnyTrustManager : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty()) throw CertificateException("сервер не показав сертифіката")
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("клієнтські сертифікати тут не приймаються")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private class SinglePinTrustManager(private val pin: () -> String) : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull()
                ?: throw CertificateException("сервер не показав сертифіката")
            val wanted = normalize(runCatching { pin() }.getOrDefault(""))
            if (wanted.isEmpty() || wanted == PairingQr.DEV_NO_PIN_SENTINEL) {
                throw CertificateException("немає відбитка — нема кому довіряти")
            }
            val got = fingerprintOf(leaf)
            if (got != wanted) {
                throw CertificateException(
                    "не той сервер: чекали ${wanted.take(16)}…, побачили ${got.take(16)}…",
                )
            }
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("клієнтські сертифікати тут не приймаються")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
