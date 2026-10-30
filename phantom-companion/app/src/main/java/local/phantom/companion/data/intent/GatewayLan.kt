package local.phantom.companion.data.intent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import local.phantom.companion.core.net.intent.IntentGateway
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * LAN-дорога шлюзу до мого ПК: `POST /api/v1/intents` (lane=sms) — ті самі
 * двері, що приймають Намір від будь-якої смуги. Печатка і є допуск, тож
 * токена тут немає. Таймаути короткі: шлюз живе у вікні приймання SMS, а
 * не встиг — кадр піде сховком, і відсів за id на ПК не дасть виконати двічі.
 */
class GatewayLan(private val base: () -> String?) : IntentGateway.LanPort {

    override suspend fun post(frame: ByteArray): IntentGateway.Lan = withContext(Dispatchers.IO) {
        val root = base()?.trimEnd('/')?.takeIf { it.startsWith("http") } ?: return@withContext IntentGateway.Lan.Unreachable
        runCatching {
            val conn = URL("$root/api/v1/intents").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = CONNECT_MS
                conn.readTimeout = READ_MS
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                val body = JSONObject()
                    .put("frame", Base64.getEncoder().withoutPadding().encodeToString(frame))
                    .put("lane", "sms")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                when (conn.responseCode) {
                    200 -> {
                        val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                        val b64 = JSONObject(text).optString("frame")
                        val raw = Base64.getDecoder().decode(b64 + "=".repeat((4 - b64.length % 4) % 4))
                        IntentGateway.Lan.Answer(raw)
                    }
                    204 -> IntentGateway.Lan.NoAnswer
                    else -> IntentGateway.Lan.Unreachable
                }
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(IntentGateway.Lan.Unreachable)
    }

    private companion object {
        const val CONNECT_MS = 2_000
        /** Разом із зʼєднанням — у межах вікна приймання SMS (див. IntentSmsReceiver). */
        const val READ_MS = 6_000
    }
}
