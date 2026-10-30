package local.phantom.companion.data

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerOutbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Стирання проти насоса — гонка, яку тут відтворено, а не переказано.
 *
 * Людина стирає рядок з головної гілки в ту саму мить, коли насос на своїй
 * дописує лист, порахований від списку, де стертий рядок ще був. Насос
 * записував другим — і повертав стерте, ще й на диск.
 */
class PeerOutboxHolderTest {

    // Гонки нижче про порядок під замком, тож письменник тут — сам публікатор.
    private val inline = Executor { it.run() }

    private fun msg(id: String) = PeerMessage(
        id = id, fromId = "me", toId = "them", body = "текст", sentAtMs = 0L,
    )

    @Test(timeout = 20_000)
    fun `a delete is not undone by a flush write counted before it`() {
        val saved = CopyOnWriteArrayList<List<PeerMessage>>()
        val holder = PeerOutboxHolder(listOf(msg("лишається"), msg("стерте")), inline) { saved += it }
        val pumpRead = CountDownLatch(1)
        val deleteWritten = CountDownLatch(1)
        val tries = AtomicInteger()

        val pump = thread(isDaemon = true) {
            holder.publish { current ->
                if (tries.getAndIncrement() == 0) {
                    pumpRead.countDown()
                    // Стирання лягає САМЕ між цим читанням і записом насоса.
                    deleteWritten.await()
                }
                PeerOutbox.markDelivered(current, "лишається")
            }
        }

        assertTrue(pumpRead.await(10, TimeUnit.SECONDS))
        holder.publish { PeerOutbox.delete(it, setOf("стерте")) }
        deleteWritten.countDown()
        pump.join(10_000)

        assertEquals(listOf("лишається"), holder.messages.value.map { it.id })
        assertEquals(PeerMessage.State.DELIVERED, holder.messages.value.single().state)
        assertTrue(saved.none { written -> written.any { it.id == "стерте" } })
        assertEquals(2, tries.get())
        assertEquals(2, saved.size)
    }

    /**
     * Підміна стану була атомарною, а запис — ні. Поки перший лист шифрувався,
     * другий встигав і порахуватись, і лягти на диск раніше — тож диск
     * закінчувався першим листом, а екран показував обидва.
     */
    @Test(timeout = 20_000)
    fun `what the screen shows is what the disk ends up holding`() {
        val saved = CopyOnWriteArrayList<List<PeerMessage>>()
        val firstWriteStarted = CountDownLatch(1)
        val secondPublished = CountDownLatch(1)
        val gate = AtomicBoolean(true)
        val holder = PeerOutboxHolder(emptyList(), inline) { snapshot ->
            if (gate.getAndSet(false)) {
                firstWriteStarted.countDown()
                secondPublished.await(10, TimeUnit.SECONDS)
            }
            saved += snapshot
        }

        val slow = thread(isDaemon = true) { holder.publish { it + msg("перший") } }
        assertTrue(firstWriteStarted.await(10, TimeUnit.SECONDS))

        // Другий лягає САМЕ поки триває шифрування першого.
        holder.publish { it + msg("ДРУГИЙ") }
        secondPublished.countDown()
        slow.join(10_000)

        assertEquals(listOf("перший", "ДРУГИЙ"), holder.messages.value.map { it.id })
        assertEquals(holder.messages.value, saved.last())
    }

    /**
     * Перший публікатор сам платив диском: міст стояв, поки шифрувався й
     * переписувався весь журнал, а JS на тому боці чекав.
     */
    @Test(timeout = 20_000)
    fun `publish returns before a slow disk write finishes and never on the caller thread`() {
        val caller = Thread.currentThread()
        val writers = CopyOnWriteArrayList<Thread>()
        val release = CountDownLatch(1)
        val holder = PeerOutboxHolder(emptyList()) {
            writers += Thread.currentThread()
            release.await(10, TimeUnit.SECONDS)
        }

        val t0 = System.nanoTime()
        holder.publish { it + msg("слово") }
        val tookMs = (System.nanoTime() - t0) / 1_000_000

        assertTrue("publish чекав на диск ${tookMs} мс", tookMs < 1_000)
        assertEquals(listOf("слово"), holder.messages.value.map { it.id })
        release.countDown()
        assertTrue(holder.flush())
        assertTrue(writers.isNotEmpty())
        assertTrue(writers.none { it === caller })
    }

    @Test(timeout = 20_000)
    fun `a failed write does not wedge the writer`() {
        val saved = CopyOnWriteArrayList<List<String>>()
        val first = AtomicBoolean(true)
        val holder = PeerOutboxHolder(emptyList()) { snapshot ->
            if (first.getAndSet(false)) error("диск повний")
            saved += snapshot.map { it.id }
        }

        holder.publish { it + msg("перший") }
        assertTrue(holder.flush())
        holder.publish { it + msg("другий") }
        assertTrue(holder.flush())

        assertEquals(listOf("перший", "другий"), saved.last())
    }

    @Test(timeout = 20_000)
    fun `durable is false for a line the disk refused and true once a write takes it`() {
        val broken = AtomicBoolean(true)
        val holder = PeerOutboxHolder(writer = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            if (broken.get()) error("диск не прийняв")
        }
        holder.publish { it + msg("перший") }
        assertEquals(false, holder.durable("перший", 5_000))

        broken.set(false)
        holder.publish { it + msg("другий") }
        assertTrue(holder.durable("перший", 5_000))
    }

    @Test(timeout = 20_000)
    fun `durable answers as soon as its own line lands, not when the writer goes quiet`() {
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        val holder = PeerOutboxHolder(writer = pool) { Thread.sleep(20) }
        val stop = AtomicBoolean(false)
        val noise = thread(isDaemon = true) {
            var n = 0
            while (!stop.get()) { holder.publish { it + msg("шум-${n++}") }; Thread.sleep(5) }
        }
        Thread.sleep(50)
        holder.publish { it + msg("лист") }
        try {
            assertTrue(holder.durable("лист", 3_000))
        } finally {
            stop.set(true)
            noise.join()
        }
    }
}
