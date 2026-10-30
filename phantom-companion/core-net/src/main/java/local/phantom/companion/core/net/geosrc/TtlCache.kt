package local.phantom.companion.core.net.geosrc

/**
 * Кеш відповідей із часом життя запису.
 *
 * Потрібен не заради швидкості, а заради поваги до чужих лімітів: карта
 * перемальовується на кожен рух пальця, і без кеша один жест зʼїдав би
 * добову квоту OpenSky. TTL добирається під природу даних — секунди для
 * літаків, десятки хвилин для повітря, години для пожеж.
 *
 * Витіснення — LRU за розміром: на телефоні памʼять дорожча за зайвий
 * мережевий виклик, тому [maxEntries] тримається малим.
 *
 * Клас потокобезпечний: клієнти викликаються з довільних корутинних
 * диспетчерів, а `@Synchronized` тут дешевший за мʼютекс, бо всі операції
 * — це кілька дій над мапою без призупинення.
 */
class TtlCache<K : Any, V : Any>(
    private val ttlMs: Long,
    private val maxEntries: Int = 32,
    private val clock: GeoClock = SystemGeoClock,
) {
    private class Entry<V>(val value: V, val storedAtMs: Long)

    // accessOrder = true — порядок ітерації стає порядком звернень, тож
    // найстаріший за використанням запис завжди перший.
    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)

    /**
     * Значення, якщо воно є і не протухло. Протухлий запис видаляється
     * одразу — інакше він займав би місце в LRU до кінця сесії.
     */
    @Synchronized
    fun get(key: K): V? {
        val entry = entries[key] ?: return null
        if (clock.nowMs() - entry.storedAtMs >= ttlMs) {
            entries.remove(key)
            return null
        }
        return entry.value
    }

    /** Кладе значення, витісняючи найстаріший за використанням запис при переповненні. */
    @Synchronized
    fun put(key: K, value: V) {
        entries[key] = Entry(value, clock.nowMs())
        while (entries.size > maxEntries) {
            val iterator = entries.keys.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
    }

    /** Вік запису в мілісекундах або `null`, якщо запису немає. Для діагностики в UI. */
    @Synchronized
    fun ageMs(key: K): Long? = entries[key]?.let { clock.nowMs() - it.storedAtMs }

    /** Видаляє один запис (наприклад, після 401 — кеш може містити відповідь на старий ключ). */
    @Synchronized
    fun invalidate(key: K) {
        entries.remove(key)
    }

    /** Повністю очищає кеш. */
    @Synchronized
    fun clear() {
        entries.clear()
    }

    /** Кількість записів, включно з протухлими, які ще не чіпали. */
    @Synchronized
    fun size(): Int = entries.size

    /** Час життя запису в мілісекундах — реєстр показує його користувачеві. */
    val ttlMillis: Long get() = ttlMs
}
