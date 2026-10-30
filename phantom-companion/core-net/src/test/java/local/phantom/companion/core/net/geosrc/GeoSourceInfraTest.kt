package local.phantom.companion.core.net.geosrc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тести спільної інфраструктури зовнішніх геоджерел.
 *
 * Усі ці джерела — чужі безкоштовні сервіси з маленькими й цілком реальними
 * лімітами. Тому кеш і обмежувач частоти тут не оптимізація, а умова того, що
 * нас не заблокують після першого ж дня роботи застосунку в місті. Логіка
 * навмисно винесена з клієнтів у спільні примітиви й перевіряється окремо на
 * керованому годиннику — на справжньому часі такі тести були б повільні й
 * плаваючі.
 */
class GeoSourceInfraTest {

    /** Годинник, яким керує тест: без нього перевірка TTL тривала б годинами. */
    private class FakeClock(var now: Long = 0L) : GeoClock {
        override fun nowMs(): Long = now
    }

    // ————————————————————————————————————————————————————————————————
    // Кеш із часом життя
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `значення живе рівно стільки, скільки дозволено`() {
        val clock = FakeClock()
        val cache = TtlCache<String, String>(ttlMs = 10_000L, clock = clock)

        cache.put("київ", "AQI 42")
        assertEquals("щойно покладене має читатись", "AQI 42", cache.get("київ"))

        clock.now = 9_999L
        assertEquals("до закінчення TTL значення чинне", "AQI 42", cache.get("київ"))

        clock.now = 10_001L
        assertNull("протухле значення не має віддаватись", cache.get("київ"))
    }

    @Test
    fun `протухлий запис звільняє місце одразу`() {
        // Інакше мертві записи тримали б місце в LRU до кінця сесії й витісняли
        // живі — кеш перетворився б на кладовище.
        val clock = FakeClock()
        val cache = TtlCache<String, String>(ttlMs = 1_000L, maxEntries = 4, clock = clock)

        cache.put("a", "1")
        assertEquals(1, cache.size())

        clock.now = 2_000L
        cache.get("a")

        assertEquals("протухлий запис мав зникнути при зверненні", 0, cache.size())
    }

    @Test
    fun `витісняється найдавніше використаний, а не найдавніше покладений`() {
        val clock = FakeClock()
        val cache = TtlCache<String, String>(ttlMs = 100_000L, maxEntries = 3, clock = clock)

        cache.put("a", "1")
        cache.put("b", "2")
        cache.put("c", "3")
        cache.get("a") // «a» знову свіжий за зверненням

        cache.put("d", "4")

        assertNotNull("до «a» щойно зверталися — він має вціліти", cache.get("a"))
        assertNull("«b» був найдавнішим за зверненням", cache.get("b"))
        assertEquals(3, cache.size())
    }

    @Test
    fun `вік запису відомий, а для відсутнього — невідомий`() {
        val clock = FakeClock()
        val cache = TtlCache<String, String>(ttlMs = 60_000L, clock = clock)
        cache.put("k", "v")

        clock.now = 4_500L

        assertEquals(4_500L, cache.ageMs("k"))
        assertNull(cache.ageMs("немає"))
    }

    @Test
    fun `очищення прибирає все`() {
        val cache = TtlCache<String, String>(ttlMs = 60_000L)
        cache.put("a", "1")
        cache.put("b", "2")
        cache.clear()
        assertEquals(0, cache.size())
    }

    // ————————————————————————————————————————————————————————————————
    // Обмежувач частоти
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `сплеск дозволено, далі — рівний темп`() {
        // Сплеск потрібен для реального сценарію: карта відкрилась і одразу
        // просить кілька шарів. Рівний темп після нього — щоб фонове оновлення
        // не перетворилось на потік.
        val clock = FakeClock()
        val limiter = MinIntervalLimiter(minIntervalMs = 1_000L, burst = 3, clock = clock)

        assertTrue("перший запит зі сплеску", limiter.tryAcquire())
        assertTrue("другий запит зі сплеску", limiter.tryAcquire())
        assertTrue("третій запит зі сплеску", limiter.tryAcquire())
        assertFalse("сплеск вичерпано — четвертий має бути відхилений", limiter.tryAcquire())

        clock.now = 1_000L
        assertTrue("через секунду має накапати один дозвіл", limiter.tryAcquire())
        assertFalse("але лише один", limiter.tryAcquire())
    }

    @Test
    fun `час до повтору повідомляється, а не вгадується`() {
        val clock = FakeClock()
        val limiter = MinIntervalLimiter(minIntervalMs = 2_000L, burst = 1, clock = clock)
        limiter.tryAcquire()

        val wait = limiter.retryAfterMs()

        assertTrue("очікування має бути в межах інтервалу: $wait", wait in 1..2_000L)
    }

    // ————————————————————————————————————————————————————————————————
    // Добова квота
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `квота витрачається і не йде в мінус`() {
        // Живий приклад: у мережі стеження за літаками ліміт кредитний, і
        // вартість запиту зростає з площею. Перевитрата означає блокування.
        val clock = FakeClock()
        val quota = DailyQuota(limitPerDay = 10, clock = clock)

        assertTrue(quota.spend(4))
        assertEquals(6, quota.remaining())
        assertFalse("списати більше, ніж лишилось, не можна", quota.spend(7))
        assertEquals("невдале списання не має нічого змінювати", 6, quota.remaining())
        assertTrue(quota.spend(6))
        assertEquals(0, quota.remaining())
        assertFalse(quota.canSpend(1))
    }

    @Test
    fun `квота оновлюється наступної доби`() {
        val clock = FakeClock()
        val quota = DailyQuota(limitPerDay = 5, clock = clock)
        quota.spend(5)
        assertEquals(0, quota.remaining())

        clock.now = 24 * 60 * 60 * 1000L + 1

        assertEquals("нова доба — нова квота", 5, quota.remaining())
    }

    @Test
    fun `час скидання квоти відомий`() {
        val clock = FakeClock(now = 1_000L)
        val quota = DailyQuota(limitPerDay = 3, clock = clock)
        quota.spend(1)

        val resetAt = quota.resetAtMs()

        assertTrue("скидання має бути в майбутньому: $resetAt", resetAt > clock.now)
    }

    // ————————————————————————————————————————————————————————————————
    // Прямокутник запиту
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `валідний прямокутник Києва приймається`() {
        val kyiv = GeoBBox(minLat = 50.34, minLon = 30.24, maxLat = 50.59, maxLon = 30.82)

        assertTrue(kyiv.isValid)
        assertTrue("Хрещатик має бути всередині", kyiv.contains(50.4501, 30.5234))
        assertFalse("Львів — ні", kyiv.contains(49.84, 24.03))
    }

    @Test
    fun `вивернутий або нечисловий прямокутник відхиляється`() {
        assertFalse("min > max", GeoBBox(51.0, 30.0, 50.0, 31.0).isValid)
        assertFalse("NaN", GeoBBox(Double.NaN, 30.0, 51.0, 31.0).isValid)
        assertFalse("широта поза Землею", GeoBBox(-95.0, 30.0, 51.0, 31.0).isValid)
    }

    @Test
    fun `канонічний запис однаковий для однакових меж`() {
        // Цей рядок — ключ кеша. Якби він залежав від порядку полів або
        // форматування, кеш мовчки промахувався б на кожному запиті.
        val a = GeoBBox(50.34, 30.24, 50.59, 30.82)
        val b = GeoBBox(50.34, 30.24, 50.59, 30.82)
        assertEquals(a.canonical(), b.canonical())
    }
}
