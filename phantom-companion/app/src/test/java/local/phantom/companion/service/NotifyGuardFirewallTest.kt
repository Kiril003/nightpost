package local.phantom.companion.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.fail

/**
 * Кожен вихід у шторку має власний замок.
 *
 * 30 серпня 2026 лінт заговорив у цьому дереві вперше за весь час і показав
 * вісім `MissingPermission`. Чотири з них були справжні, і всі чотири однакової
 * форми: **замок стояв не на всіх входах**.
 *
 *  - `PeerNotifications.post()` — до нього ведуть ТРИ функції
 *    (`notifyMessage`, `noteOwnReply`, `noteReplyFailed`), а перевірка стояла
 *    лише в першій;
 *  - `PeerNotifications.summarise()` і `MessengerNotifications.summarise()` —
 *    окремі входи, замків не мали зовсім (другий пішов із дерева 06.09 разом
 *    із вузловим месенджером; перший на місці й далі під наглядом);
 *  - `ReminderAlarms.notify()` — у файлі не було ЖОДНОЇ перевірки
 *    `POST_NOTIFICATIONS`.
 *
 * Чому це не видно і не падає: `NotificationManagerCompat.notify()` при
 * відібраному дозволі **не кидає винятку**. Він тихо не робить нічого. Тобто
 * нагадування просто не зʼявлялось, і жодна зі сторін про це не дізнавалась —
 * рівно розрив між «нічого немає» і «не вдалося дізнатись».
 *
 * ## Чому саме такий тест, а не юніт
 *
 * Перевірка дозволу — виклик каркаса Android. Щоб покрити її юнітом, потрібен
 * Robolectric; він оголошений у каталозі версій, але в модулі `:app` **не
 * підʼєднаний**, і жоден тест тут його не вживає. Підключати його заради цього
 * означало б тягнути в офлайнові ворота залежність, яка догружає свої
 * android-all архіви з мережі під час прогону.
 *
 * Тому це сторож рівнем вище: він читає вихідники й вимагає, щоб **функція,
 * яка публікує, сама несла замок**. Це слабше за юніт і сильніше за нічого.
 *
 * ## Чого він НЕ ловить — щоб зелене не читалось ширше, ніж воно є
 *
 * Він не знає, чи замок правильний: `if (!allowed(context)) return` і
 * `if (!allowed(context)) { }` для нього однакові. Він не бачить замків, що
 * лежать у викликача (це навмисно — саме така конструкція і зламалась). І він
 * нічого не каже про те, чи сповіщення дійсно доходить: виробничі
 * «оптимізатори» глушать служби мовчки, і жоден тест у цьому дереві на це не
 * відповідає.
 *
 * GATE LAW (спільний з вартовими в core-design): читає вихідники як файли,
 * повз перевірку актуальності Gradle. Ганяти з `--rerun`.
 */
class NotifyGuardFirewallTest {

    /** Виклик, що публікує в шторку. */
    private val publishes = Regex("""NotificationManagerCompat\s*\.\s*from\([^)]*\)\s*\.\s*notify\(""")

    /**
     * Замок. Форми дві, бо в домі вживані обидві: помічник `allowed(...)` і
     * перевірка дозволу на місці.
     */
    private val guards = Regex(
        """(allowed\(|notificationsAllowed\(|POST_NOTIFICATIONS|areNotificationsEnabled\()""",
    )

    /** Початок функції верхнього рівня всередині обʼєкта/класу. */
    private val funHead = Regex("""^ {4}(?:@\w+\s+)*(?:private |internal |public )?(?:suspend )?fun """)

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь дерева з feature-stream/ не знайдено") as Nothing
    }

    /**
     * Повертає імена функцій, які публікують без замка в СВОЄМУ тілі.
     *
     * Метод навмисно тупий: від рядка з публікацією йдемо вгору до найближчого
     * заголовка функції, і шукаємо замок у проміжку. Замок нижче за публікацію
     * не рахується — він її вже не боронить.
     */
    internal fun unguarded(source: String): List<String> {
        val lines = source.lines()
        val out = mutableListOf<String>()
        lines.forEachIndexed { i, line ->
            if (!publishes.containsMatchIn(line)) return@forEachIndexed
            var head = -1
            for (j in i downTo 0) {
                if (funHead.containsMatchIn(lines[j])) { head = j; break }
            }
            if (head < 0) { out += "рядок ${i + 1}: публікація поза функцією"; return@forEachIndexed }
            val body = lines.subList(head, i).joinToString("\n")
            if (!guards.containsMatchIn(body)) {
                val name = Regex("""fun\s+([A-Za-z0-9_]+)""").find(lines[head])?.groupValues?.get(1)
                    ?: "?"
                out += "$name (рядок ${i + 1})"
            }
        }
        return out
    }

    /**
     * Спершу доводимо, що сторож УМІЄ почервоніти.
     *
     * Без цього він міг би нічого не ловити через зіпсований вираз і виглядати
     * точно так само, як сторож, якому нічого ловити. Зразок нижче — це
     * `ReminderAlarms` у тому вигляді, у якому він жив до 30 серпня.
     */
    @Test
    fun `сторож червоніє на коді, який тут справді був`() {
        val before = """
            object ReminderAlarms {
                fun notify(context: Context, reminder: ReminderEntity) {
                    val notification = NotificationCompat.Builder(context, CHANNEL_ID).build()
                    runCatching {
                        NotificationManagerCompat.from(context).notify(reminder.id.hashCode(), notification)
                    }.onFailure { Log.w(TAG, "refused", it) }
                }
            }
        """.trimIndent()
        assertEquals(
            "сторож не побачив публікації без замка — тоді його зелене нічого не варте",
            1,
            unguarded(before).size,
        )

        val after = before.replace(
            "    fun notify(context: Context, reminder: ReminderEntity) {",
            "    fun notify(context: Context, reminder: ReminderEntity) {\n" +
                "        if (!notificationsAllowed(context)) return",
        )
        assertTrue(
            "замок доданий, а сторож усе одно скаржиться — він рахує не те",
            unguarded(after).isEmpty(),
        )
    }

    @Test
    fun `жодна функція не публікує в шторку без перевірки дозволу`() {
        val files = File(repoRoot(), "app/src/main").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it to it.readText() }
            .filter { (_, text) -> publishes.containsMatchIn(text) }
            .toList()

        assertTrue(
            "жодного місця публікації не знайдено — сторож уже нічого не боронить",
            files.isNotEmpty(),
        )

        val offenders = files.flatMap { (file, text) ->
            unguarded(text).map { "${file.name}: $it" }
        }

        assertEquals(
            "публікація в шторку без перевірки POST_NOTIFICATIONS. Це не падіння: " +
                "notify() при відібраному дозволі тихо не робить нічого, тож сповіщення " +
                "просто не зʼявиться, і ніхто не дізнається.\n    " +
                offenders.joinToString("\n    ") +
                "\n\nЗамок ставте на вузькому місці — у функції, що публікує, а не у " +
                "викликачів: до PeerNotifications.post() вело три входи, і саме тому " +
                "перевірка в одному з них нічого не боронила.",
            emptyList<String>(),
            offenders,
        )
    }
}
