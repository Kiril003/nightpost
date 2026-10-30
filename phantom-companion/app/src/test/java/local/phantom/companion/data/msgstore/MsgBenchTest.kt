package local.phantom.companion.data.msgstore

import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerOutbox
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Замір JVM, не телефона: «було» — те, що робив старий запис (увесь журнал у JSON,
 * AES-GCM, файл), «стало» — різниця в SQLite без шифру. Числа друкуються в звіт.
 */
class MsgBenchTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val n = 5000

    private fun rows() = (1..n).map {
        line(it, from = if (it % 2 == 0) "oksana" else "me", to = if (it % 2 == 0) "me" else "oksana",
            body = "рядок $it — звичайна репліка середньої довжини, як у живій розмові")
    }

    private fun p95(xs: List<Long>) = xs.sorted()[(xs.size * 95) / 100].let { it / 1000.0 }

    @Test
    fun `one letter written, and a 5000-line conversation opened, before and after`() {
        val rows = rows()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val file = File.createTempFile("legacy", ".xml")
        val legacyWrite = { list: List<PeerMessage> ->
            val text = json.encodeToString(list)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key)
            file.writeBytes(c.doFinal(text.toByteArray()))
            c.iv
        }
        var iv = legacyWrite(rows)
        val before = (1..60).map { i ->
            val t = System.nanoTime()
            iv = legacyWrite(PeerOutbox.markDelivered(rows, "m-${n - i}"))
            (System.nanoTime() - t) / 1000
        }.drop(10)
        val legacyOpen = (1..30).map {
            val t = System.nanoTime()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            val all = json.decodeFromString<List<PeerMessage>>(String(c.doFinal(file.readBytes())))
            all.filter { it.toId == "oksana" || it.fromId == "oksana" }.sortedByDescending { it.sentAtMs }.take(50)
            (System.nanoTime() - t) / 1000
        }.drop(5)

        Phone().use { phone ->
            val store = phone.boot()
            store.load()
            store.save(rows)
            var current = rows
            val after = (1..60).map { i ->
                current = PeerOutbox.markDelivered(current, "m-${n - i}")
                val t = System.nanoTime()
                store.save(current)
                (System.nanoTime() - t) / 1000
            }.drop(10)
            val base = store.history!!
            val conv = convOf(rows[0])
            val page = (1..30).map {
                val t = System.nanoTime()
                base.lines.page(conv, limit = 50)
                (System.nanoTime() - t) / 1000
            }.drop(5)
            val whole = (1..30).map {
                val t = System.nanoTime()
                base.lines.page(conv, limit = n)
                (System.nanoTime() - t) / 1000
            }.drop(5)
            val boot = (1..5).map {
                val t = System.nanoTime()
                phone.boot().load()
                (System.nanoTime() - t) / 1000
            }

            println("BENCH write-one-letter p95 ms: before=${p95(before)} after=${p95(after)} (n=$n)")
            println("BENCH open-conversation-5000 p95 ms: before(all JSON)=${p95(legacyOpen)} after(page 50)=${p95(page)} after(all 5000)=${p95(whole)}")
            println("BENCH cold-load-5000 max ms: ${boot.max() / 1000.0}")
            assertTrue(p95(after) < p95(before))
        }
        file.delete()
    }
}
