package local.phantom.companion.feature.stream.guard

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Що замкнений телефон має право сказати — як храповик, а не як побажання.
 *
 * Сповіщення малюється ДО замка застосунку. Тож повний текст повідомлення в
 * шторці скасовував замок ще до того, як до нього доходило: телефон екраном
 * догори на столі або в чужих руках на блокпосту віддавав розмову екіпажу без
 * жодного розблокування. Це не приватність, це склад людей і планів.
 *
 * Живе в feature-stream, бо саме цей модуль ганяє воріт
 * (`:feature-stream:testDebugUnitTest`) — за зразком уже наявних тут firewall-
 * тестів. Тест читає чужі модулі як звичайні файли, тож Gradle не бачить його
 * входів: при зміні лише app/ його треба ганяти з `--rerun`.
 */
class LockScreenLeakFirewallTest {

    /** Файли, що несуть у шторку слова людини або її імʼя. */
    private val carriesHumanContent = listOf(
        PEER,
        "app/src/main/java/local/phantom/companion/service/ReminderAlarms.kt",
        "app/src/main/java/local/phantom/companion/service/NavigationService.kt",
        "app/src/main/java/local/phantom/companion/net/SymbioteExecutor.kt",
    )

    @Test
    fun `human content never reaches a locked screen unredacted`() {
        for (path in carriesHumanContent) {
            val src = source(path)
            assertTrue(
                "$path ховає вміст, але не дає заміни — система намалює «Вміст приховано»",
                src.contains("setPublicVersion("),
            )
            if (path == PEER) continue
            assertTrue(
                "$path посилає в шторку слова людини без VISIBILITY_PRIVATE",
                src.contains("setVisibility(NotificationCompat.VISIBILITY_PRIVATE)"),
            )
        }
    }

    /**
     * Рівень для повідомлень екіпажу мусить приходити зі сховища, а не бути
     * константою: сталий рівень означає або витік у всіх, або неможливість
     * підняти його для однієї розмови, яку оператор свідомо лишає відкритою.
     */
    @Test
    fun `crew messages take their level from the store, per conversation`() {
        val src = source(PEER)
        assertTrue(
            "рівень розкриття не читається зі сховища",
            src.contains("LockScreenRevealStore.of(context).forConversation("),
        )
        assertTrue(
            "видимість плашки не привʼязана до прочитаного рівня",
            src.contains("setVisibility(reveal.visibility())"),
        )
    }

    /**
     * Тривогу читають на бігу до укриття. Сповіщення, заради якого треба
     * розблокувати телефон, не читає ніхто — воно марне саме тоді, коли єдиний
     * раз потрібне.
     */
    @Test
    fun `air raid stays readable without unlocking`() {
        val src = source("app/src/main/java/local/phantom/companion/service/AirAlertNotifications.kt")
        assertTrue(
            "тривога має лишатись VISIBILITY_PUBLIC",
            src.contains("setVisibility(NotificationCompat.VISIBILITY_PUBLIC)"),
        )
        assertTrue(
            "канал тривоги має дзвонити потоком будильника, інакше вимкнений звук сповіщень його глушить",
            src.contains("AudioAttributes.USAGE_ALARM"),
        )
    }

    /**
     * Канал в Android незмінний після створення: рівень, вкручений у нього,
     * не застосується до вже встановленої бази й не дасть винятку на розмову.
     * Зелений тест і жоден реальний телефон — саме той клас дефекту.
     */
    @Test
    fun `lockscreen level is never baked into a channel`() {
        for (path in carriesHumanContent + AIR_ALERT) {
            assertFalse(
                "$path вкручує рівень у канал замість сповіщення",
                source(path).contains("setLockscreenVisibility"),
            )
        }
    }

    /**
     * Коментарі відрізаються, і це не косметика: пояснення «свідомо БЕЗ
     * setLockscreenVisibility» саме по собі валило перевірку, а слово
     * `setPublicVersion` у чужому коментарі так само легко дало б хибне «все
     * гаразд». Тест має читати код, а не розмову про код.
     */
    private fun source(relPath: String): String {
        val file = File(repoRoot(), relPath)
        assertTrue("не знайдено: $relPath", file.isFile)
        return file.readLines()
            .filterNot { val t = it.trim(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n")
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return requireNotNull(dir) { "корінь репозиторію не знайдено" }
    }

    private companion object {
        const val PEER = "app/src/main/java/local/phantom/companion/service/PeerNotifications.kt"
        const val AIR_ALERT =
            "app/src/main/java/local/phantom/companion/service/AirAlertNotifications.kt"
    }
}
