package local.phantom.companion.core.net.map

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import local.phantom.companion.core.net.geosrc.GeoClock
import local.phantom.companion.core.net.geosrc.MinIntervalLimiter
import local.phantom.companion.core.net.geosrc.SourceUnavailable
import local.phantom.companion.core.net.geosrc.SystemGeoClock
import java.util.concurrent.ConcurrentHashMap

/**
 * Єдиний реєстр дзеркал Overpass на весь застосунок.
 *
 * До ATLAS-0.1 список жив у пʼяти файлах трьох модулів, і вони вже
 * розійшлися: один ніс дзеркало maps.mail.ru, інший не мав User-Agent
 * узагалі. Дзеркала — це і ліцензійне, і безпекове рішення (чий сервер
 * бачить, куди дивиться оператор), тож воно приймається в одному місці.
 */
object OverpassMirrors {

    /** Публічні інстанси просять ідентифікувати клієнта; анонімний трафік ріжуть першим. */
    const val USER_AGENT: String = "PhantomCompanion/0.1 (operator)"

    /** Порядок = спад швидкості й стабільності, перевірений на живих запитах. */
    val ENDPOINTS: List<String> = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://lz4.overpass-api.de/api/interpreter",
        "https://z.overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    )

    /** Хост без схеми та шляху — для журналу. */
    fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').lowercase()

    /**
     * Родина хостів для спільного бюджету.
     *
     * `overpass-api.de`, `lz4.overpass-api.de` і `z.overpass-api.de` — це
     * один оператор і одне залізо: рахувати їм окремі ліміти означало б
     * потроїти навантаження на нього, формально лишаючись «ввічливими».
     */
    fun familyOf(url: String): String {
        val labels = hostOf(url).split('.')
        return if (labels.size <= 2) labels.joinToString(".") else labels.takeLast(2).joinToString(".")
    }
}

/**
 * Спільний бюджет запитів до Overpass.
 *
 * Ліміт двоярусний навмисно. Ярус родини хостів тримає домовленість із
 * конкретним оператором дзеркала; глобальний ярус тримає загальний профіль
 * застосунку, інакше перебір пʼяти дзеркал підряд виглядав би як пʼять
 * ввічливих клієнтів замість одного нетерплячого.
 *
 * Чекання обмежене [MAX_WAIT_MS]: під повітряною тривогою сканування
 * укриттів має або піти в мережу, або чесно сказати «джерело зайняте», а не
 * висіти нескінченно з порожнім екраном.
 */
class OverpassBudget(
    private val familyIntervalMs: Long = FAMILY_INTERVAL_MS,
    private val familyBurst: Int = FAMILY_BURST,
    globalIntervalMs: Long = GLOBAL_INTERVAL_MS,
    globalBurst: Int = GLOBAL_BURST,
    private val clock: GeoClock = SystemGeoClock,
) {
    private val gate = Mutex()
    private val global = MinIntervalLimiter(globalIntervalMs, globalBurst, clock)
    private val families = ConcurrentHashMap<String, MinIntervalLimiter>()

    /**
     * Займає місце в бюджеті для запиту на [url], за потреби чекаючи.
     *
     * @return `null` — можна йти в мережу; інакше причина відмови.
     */
    suspend fun acquire(url: String, maxWaitMs: Long = MAX_WAIT_MS): SourceUnavailable? {
        val enteredAt = clock.nowMs()
        val family = families.getOrPut(OverpassMirrors.familyOf(url)) {
            MinIntervalLimiter(familyIntervalMs, familyBurst, clock)
        }
        // Переговори під мʼютексом: без нього дві корутини бачать той самий
        // єдиний жетон і обидві вважають його своїм.
        return gate.withLock {
            var refusal: SourceUnavailable? = null
            var granted = false
            while (!granted) {
                val wait = maxOf(family.retryAfterMs(), global.retryAfterMs())
                if (wait <= 0L) {
                    // Під мʼютексом і після нульового retryAfter обидва жетони
                    // гарантовано на місці — годинник лише йде вперед.
                    family.tryAcquire()
                    global.tryAcquire()
                    granted = true
                } else if (clock.nowMs() - enteredAt + wait > maxWaitMs) {
                    // Ліміт відлічується від входу в чергу, а не від захоплення
                    // мʼютекса: інакше останній у черзі чекав би суму всіх пауз.
                    refusal = SourceUnavailable.RateLimited(wait)
                    break
                } else {
                    delay(wait)
                }
            }
            refusal
        }
    }

    companion object {
        /** Домовленість із оператором дзеркала — той самий крок, що вже перевірений у Crystal-клієнті. */
        const val FAMILY_INTERVAL_MS: Long = 2_000L

        /** Сплеск на дві дії: відкриття екрана — це bbox + деталі одразу. */
        const val FAMILY_BURST: Int = 2

        const val GLOBAL_INTERVAL_MS: Long = 1_000L
        const val GLOBAL_BURST: Int = 3

        const val MAX_WAIT_MS: Long = 12_000L

        /**
         * Бюджет на процес. Усі клієнти Overpass — у :core-net, :core-geo,
         * :core-data та :feature-stream — витрачають саме його.
         */
        val shared: OverpassBudget = OverpassBudget()
    }
}
