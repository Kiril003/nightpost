package local.phantom.companion.feature.stream.ui.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ФАЄРВОЛ ПРОТИ ТЕАТРУ В `ui/people`.
 *
 * 05.09 аудит зняв з цього дерева: фальшивий запис голосу (`LaunchedEffect` з
 * `delay(1000)` замість `AudioRecord`, три копії — у композері, у мертвому
 * `Composer` рідного екрана розмови, і в кнопці «Голос» аркуша вкладень), картку
 * «P2P голосовий ефір» (`AudioHuddleBar`) без сигналізації й транспорту,
 * реакції, що рахують власний тап без особи (`mutableStateMapOf` без ключа),
 * і кілька зашитих payload-літералів («tactical_briefing.pdf», фальшиве фото
 * «recon_point_…»). Читає дерево текстом, як компілятор не читає, — рядок, а
 * не поведінку, тому що саме рядком театр повертається найлегше (скопійований
 * шматок коду з іншого місця).
 *
 * `PH-IMAGE:` і, від 05.09 увечері, `PH-VOICE:` навмисно НЕ заборонені як
 * голі префікси: рендер уже отриманих повідомлень (`said.startsWith(...)`,
 * `RenderCardPayload`) лишається — поле все ще карбує ці теги, і старі листи
 * вже лежать у чергах. `PeerMessageCards.kt` читає `PH-VOICE:` чесною
 * карткою «голосове повідомлення · Nс» без хвилі, транскрипту чи кнопки
 * відтворення. Заборонений саме ВМІСТ, який хтось написав тут, а не прочитав
 * із дроту, — цю різницю тепер ловить окремий `FabricatedAttachmentFirewallTest`
 * (guard/), узятий з іншої сесії; цей файл називає конкретні вигадки поіменно.
 *
 * Того ж дня другий аудит додав ще шар у тих самих файлах: таймер
 * самознищення (TTL) без механізму самознищення, вигаданий RSSI біля кожного
 * учасника групи, і `NodeDossierSheet` — ціле вигадане досьє з чужим
 * алгоритмом ключа (Secp256k1 замість Ed25519/X25519). Той самий заслін —
 * бо той самий клас хиби.
 */
class PeerTheatreFirewallTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("не знайшов корінь дерева (каталог feature-stream)")
    }

    private val peopleDir
        get() = File(
            repoRoot(),
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/people",
        )

    /**
     * Підлога, а не лічильник: доводить, що тека знайдена. Нуль файлів дав би
     * зелене ні про що.
     *
     * Було `> 20` при 29 файлах. 06.09 з теки пішли десять — увесь рідний
     * екран розмови (`ChatHub`, `PeerChatScreen`, `PeerChatChrome`,
     * `PeerChatActions`, `PeerChatMotion`, `PeerGroupChatScreen`,
     * `PeerGroupMembers`, `GroupDetailSheet`, `PeopleGroups`, `PeerPayloadUi`),
     * прийшов `PeerFace.kt`, лишилось 20. Підлога опущена до 15 не тому, що
     * ворота почервоніли, а тому, що вона міряла кількість файлів тодішнього
     * екрана; сьогодні вона міряє лише «тека на місці».
     *
     * 02.10 стрічку зняли — у теці 14 файлів, підлога 14.
     *
     * 04.10 (profile-hub): мертві `PeopleScreen.kt`, `PeerHandshakeSheet.kt` і
     * `IntelSections.kt` (тримали лише одне одного) пішли — 11 файлів, підлога 11.
     */
    private fun sources(): List<File> =
        peopleDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList().also {
            check(it.size >= 11) { "у ${peopleDir.path} знайдено підозріло мало файлів: ${it.size}" }
        }

    private fun grep(needle: String, files: List<File> = sources()): List<String> =
        files.filter { it.readText().contains(needle) }.map { it.name }.sorted()

    @Test
    fun `читач PH-VOICE лишається чесним — без хвилі, транскрипту чи кнопки відтворення`() {
        // Не банить "PH-VOICE:" саму — цей префікс тепер законно розбирає
        // вхідний лист (HonestVoiceNotice). Ловить САМЕ повернення декорацій:
        // намальовану форму хвилі — це завжди той самий літерал масиву — і
        // зашитий транскрипт, якого жоден мікрофон не писав.
        val hits = grep("Транскрипт AI") +
            grep("Аудіозапис сесії. Узгоджено спільні кроки та пріоритети") +
            grep("listOf(0.3f, 0.6f, 0.9f, 0.4f, 0.7f, 0.5f, 0.8f, 0.2f, 0.6f, 1.0f, 0.4f, 0.7f)")
        assertEquals(
            "декорація фальшивого запису повернулась — хвиля чи транскрипт " +
                "без AudioRecord позаду:\n    " + hits.joinToString("\n    "),
            emptyList<String>(),
            hits,
        )
    }

    @Test
    fun `немає фальшивого фото з мітки часу — конкретні вигадані імена файлів`() {
        val patterns = listOf("PH-IMAGE:recon_point_", "PH-IMAGE:group_recon_")
        val hits = patterns.flatMap { p -> grep(p).map { "$it: $p" } }
        assertEquals(
            "вигадане імʼя фотознімка з System.currentTimeMillis() замість реальної камери:\n    " +
                hits.joinToString("\n    "),
            emptyList<String>(),
            hits,
        )
    }

    @Test
    fun `немає зашитого файлу-приманки tactical_briefing або tactical_plan`() {
        val hits = grep("tactical_briefing") + grep("tactical_plan")
        assertEquals(
            "зашите імʼя файлу без реального пікера чи вмісту позаду:\n    " + hits.joinToString("\n    "),
            emptyList<String>(),
            hits.sorted(),
        )
    }

    @Test
    fun `AudioHuddleBar не повертається — ані файл, ані виклик`() {
        assertTrue(
            "AudioHuddleBar.kt відсутній у дереві",
            !File(peopleDir, "AudioHuddleBar.kt").exists(),
        )
        val hits = grep("AudioHuddleBar")
        assertEquals(
            "P2P «голосовий ефір» без сигналізації й транспорту повернувся текстом:\n    " +
                hits.joinToString("\n    "),
            emptyList<String>(),
            hits,
        )
    }

    @Test
    fun `реакції не рахують сам себе — нема mutableStateMapOf у цьому дереві`() {
        // Увесь функціонал реакцій (подвійний тап, швидкі емодзі в аркуші дій,
        // EmojiReactionRow) прибрано разом зі станом, що його ніс: окремого
        // способу порахувати реакцію без цього стану в дереві не лишилось.
        val hits = grep("mutableStateMapOf")
        assertEquals(
            "mutableStateMapOf у people/ — це стан реакцій, який рахував свій " +
                "тап без особи й не зберігався:\n    " + hits.joinToString("\n    "),
            emptyList<String>(),
            hits,
        )
    }

    @Test
    fun `немає вигаданого нуля мілісекунд у написі про транспорт`() {
        val hits = grep("0ms")
        assertEquals(
            "\"0ms\" — літерал непроміряної затримки з видаленого TransportSecurityBadge:\n    " +
                hits.joinToString("\n    "),
            emptyList<String>(),
            hits,
        )
    }

    @Test
    fun `мертві композабли не повертаються без виклику`() {
        // «Написане, але не викликане» — той самий клас дефекту двічі за два
        // дні деінде в цьому проєкті. Перелік — точні імена того, що видалили
        // саме тому, що на них не було жодного виклику.
        val deadNames = listOf(
            "VoiceMessageBubble", "FileAttachmentBubble", "TransportSecurityBadge",
            "FloatingReactionBar", "RichMessageActionSheet", "InteractiveVoiceCard",
            "PinnedMessageHeader", "EmojiReactionRow",
        )
        val hits = deadNames.flatMap { name -> grep(name).map { "$it: $name" } }
        assertEquals(
            "композабл, який видалили як мертвий, зʼявився знову текстом:\n    " + hits.joinToString("\n    "),
            emptyList<String>(),
            hits,
        )
    }

    @Test
    fun `немає таймера самознищення без механізму самознищення`() {
        // PeerRelay.TTL_MS — це строк життя листа в сховку, не строк життя
        // повідомлення на телефоні; PeerLive.expiresAtMs — строк дії позначки
        // місця. Жоден з них не стирає повідомлення в чаті.
        val hits = grep("САМОЗНИЩЕННЯ") + grep("selectedTtlHours")
        assertEquals(
            "перемикач TTL повернувся, а самознищення повідомлень у core-net " +
                "як не було, так і нема:\n    " + hits.sorted().joinToString("\n    "),
            emptyList<String>(),
            hits.sorted(),
        )
    }

    @Test
    fun `немає вигаданого RSSI біля учасника групи`() {
        val hits = grep("dBm")
        assertEquals(
            "\"dBm\" — RSSI, якого жоден код у цьому дереві не міряє:\n    " +
                hits.joinToString("\n    "),
            emptyList<String>(),
            hits,
        )
    }

    @Test
    fun `NodeDossierSheet не повертається — вигадана особа з чужим алгоритмом`() {
        assertTrue(
            "NodeDossierSheet.kt відсутній у дереві",
            !File(peopleDir, "NodeDossierSheet.kt").exists(),
        )
        // Ed25519/X25519 — реальні алгоритми цього застосунку; Secp256k1 тут
        // ніколи не використовувався, і напис лише прикрашав вигадку.
        val hits = grep("Secp256k1")
        assertEquals(
            "чужий алгоритм ключа вигулькнув знову:\n    " + hits.joinToString("\n    "),
            emptyList<String>(),
            hits,
        )
    }

    @Test
    fun `сторож може почервоніти`() {
        // Проба живе в системному temp, не в дереві проєкту: тест, який пише
        // у src/main заради самоперевірки, — сам собі ризик паралельної збірки.
        val probe = File.createTempFile("theatre-probe", ".kt").apply {
            writeText(
                "val x = \"Транскрипт AI\" // AudioHuddleBar mutableStateMapOf 0ms " +
                    "САМОЗНИЩЕННЯ -64 dBm Secp256k1",
            )
            deleteOnExit()
        }
        try {
            val probeFiles = listOf(probe)
            assertTrue(grep("Транскрипт AI", probeFiles).isNotEmpty())
            assertTrue(grep("AudioHuddleBar", probeFiles).isNotEmpty())
            assertTrue(grep("mutableStateMapOf", probeFiles).isNotEmpty())
            assertTrue(grep("0ms", probeFiles).isNotEmpty())
            assertTrue(grep("САМОЗНИЩЕННЯ", probeFiles).isNotEmpty())
            assertTrue(grep("dBm", probeFiles).isNotEmpty())
            assertTrue(grep("Secp256k1", probeFiles).isNotEmpty())
        } finally {
            probe.delete()
        }
    }
}
