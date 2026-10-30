package local.phantom.companion.core.voice.live

import java.util.Base64

/** Підроблений Gemini Live: записує все, що телефон відіслав, і дає «говорити» за сервер. */
class FakeConnector : LiveConnector {
    inner class Sock(val url: String, val listener: LiveSocket.Listener) : LiveSocket {
        val sent = mutableListOf<String>()
        var closed = false
        override fun send(text: String): Boolean {
            if (closed) return false
            sent += text
            return true
        }

        override fun close() {
            closed = true
        }

        fun open() = listener.onOpen()
        fun server(json: String) = listener.onFrame(json)
        fun audioFrames() = sent.count { it.startsWith("{\"realtimeInput\"") }
        fun setups() = sent.filter { it.startsWith("{\"setup\"") }
    }

    val sockets = mutableListOf<Sock>()
    val last: Sock get() = sockets.last()

    override fun open(url: String, listener: LiveSocket.Listener): LiveSocket =
        Sock(url, listener).also { sockets += it }
}

class FakeSpeaker : CallSpeaker {
    val log = mutableListOf<String>()
    var playedBytes = 0
    override fun play(pcm24k: ByteArray) {
        playedBytes += pcm24k.size
        log += "play"
    }

    override fun flush() {
        log += "flush"
    }

    override fun duck(on: Boolean) {
        log += "duck:$on"
    }
}

class RecordingHands : CallHands {
    val sent = mutableListOf<Pair<String, String>>()
    val acted = mutableListOf<String>()
    var verdict: BodyVerdict = BodyVerdict.Ask
    override suspend fun recall(query: String) = Outcome(true, "Сестра — Марта.")
    override suspend fun mapLookup(query: String) = Outcome(true, "Аптека за 300 м.")
    override suspend fun readMessages(who: String?) = Outcome(true, "Марта: буду о сьомій")
    override suspend fun person(who: String): Person =
        if (who.lowercase().startsWith("март")) Person.One("peer_marta", "Марта") else Person.NotFound("«$who» немає в реєстрі")

    override suspend fun sendMessage(personId: String, text: String): Outcome {
        sent += personId to text
        return Outcome(true, "лист пішов у дорогу")
    }

    override fun bodyVerdict(verb: String) = verdict
    override fun bodyTitle(verb: String) = "подати звук"
    override suspend fun bodyAct(verb: String, args: Map<String, String>): Outcome {
        acted += verb
        return Outcome(true, "дзвоню")
    }
}

object Frames {
    const val SETUP_OK = """{"setupComplete":{}}"""
    const val INTERRUPTED = """{"serverContent":{"interrupted":true}}"""
    const val TURN_DONE = """{"serverContent":{"turnComplete":true}}"""

    fun audio(bytes: Int = 960): String {
        val b64 = Base64.getEncoder().encodeToString(ByteArray(bytes))
        return """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$b64"}}]}}}"""
    }

    fun heard(text: String) = """{"serverContent":{"inputTranscription":{"text":"$text"}}}"""
    fun said(text: String) = """{"serverContent":{"outputTranscription":{"text":"$text"}}}"""
    fun goAway() = """{"goAway":{"timeLeft":"10s"}}"""
    fun resumable(h: String) = """{"sessionResumptionUpdate":{"newHandle":"$h","resumable":true}}"""
    fun toolCall(id: String, name: String, args: String) =
        """{"toolCall":{"functionCalls":[{"id":"$id","name":"$name","args":$args}]}}"""
}

/** 20 мс мови (гучно) або тиші, 16 кГц PCM16. */
fun pcm(loud: Boolean): ByteArray {
    val out = ByteArray(640)
    if (loud) {
        var i = 0
        while (i < out.size) {
            val s: Int = if ((i / 2) % 2 == 0) 4000 else -4000
            out[i] = (s and 0xFF).toByte()
            out[i + 1] = ((s shr 8) and 0xFF).toByte()
            i += 2
        }
    }
    return out
}
