package local.phantom.companion.license

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Заслін на число, що описує минуле.
 *
 * `publishedBuild` у app/build.gradle.kts — остання збірка, що поїхала до
 * людей. Заслін у тому ж файлі звіряє наступний номер ІЗ НИМ, тобто з
 * константою: він ловить руку, що задала номер нижче, і структурно не може
 * зловити саму константу, що відстала від світу. Двічі відставала (1 -> 6,
 * 6 -> 7), і в другий раз наслідок був такий:
 *
 *  - у файлі 6, у кабінеті вже видано 7;
 *  - наступний реліз виходить під номером 7 — тим, що вже стоїть у людей;
 *  - publish-build.sh читає 7 з APK, сервер приймає, політика оголошує 7;
 *  - телефон, який ОНОВИВСЯ ВЧАСНО й стоїть на 7, рахує в
 *    [local.phantom.companion.core.license.UpdateOffer.announcedFor]
 *    `7 <= 7` і не бачить оновлення. Ніколи.
 *
 * Ламається рівно для тих, хто оновлювався вчасно. Play на дублікат номера
 * лається гучно, кабінет — ні: увесь шлях лишається зеленим.
 *
 * Тому вимір перенесено з константи на СВІТ: scripts/check-published-build.sh
 * питає в сервера, що той оголошує. Тут доводиться поведінка цього скрипта —
 * запуском справжнього скрипта, а не переказом його змісту.
 *
 * **Мережі тут немає.** `PHANTOM_POLICY_FILE` завжди виставлений, тож скрипт
 * бере документ із файла-заглушки й не ходить нікуди. Живу звірку робить
 * release.sh у мить релізу — саме там, де вона й доречна: гейт `verify.sh`
 * ходить `--offline` і крутиться десятки разів на день.
 */
class PublishedBuildNotStaleTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private val guard = "scripts/check-published-build.sh"

    /** Документ у формі, яку справді віддає /api/policy. */
    private fun policy(build: Int, platform: String = "android") = """
        {"v":1,"min_build":0,"latest":{"build":$build,"platform":"$platform",
         "version":"0.1.0-tier1","filename":"app-field-arm64-v8a-release.apk",
         "sha256":"${"b".repeat(64)}","size":55643067,
         "url":"https://phantom-os.dev/account","requires":"open","device_ok":true}}
    """.trimIndent()

    private data class Verdict(val code: Int, val text: String) {
        /** Перше слово рядка — машинний контракт скрипта. */
        val kind: String get() = text.substringBefore(":").trim()
    }

    /** @param policy `null` = документа в руках немає (сервер не відповів). */
    private fun check(
        declared: String?,
        policy: String?,
        unverifiedAllowed: Boolean = false,
    ): Verdict {
        val fixture = File.createTempFile("phantom-policy", ".json").apply { deleteOnExit() }
        if (policy != null) fixture.writeText(policy) else fixture.delete()

        val cmd = mutableListOf("bash", guard)
        declared?.let { cmd += it }
        val pb = ProcessBuilder(cmd).directory(repoRoot()).redirectErrorStream(true)
        // Виставлений ЗАВЖДИ — і коли документа немає. Саме це тримає тест поза
        // мережею: без нього гілка «немає документа» пішла б дзвонити наживо.
        pb.environment()["PHANTOM_POLICY_FILE"] = fixture.absolutePath
        if (unverifiedAllowed) pb.environment()["PHANTOM_PUBLISHED_UNVERIFIED"] = "1"

        val p = pb.start()
        val text = p.inputStream.bufferedReader().readText().trim()
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            fail("$guard не завершився за 60 с")
        }
        return Verdict(p.exitValue(), text)
    }

    @Test
    fun `a number that fell behind the world stops the release`() {
        // Рівно те, що сталося: у файлі 6, у кабінеті 7.
        val v = check(declared = "6", policy = policy(7))
        assertEquals("відставання від світу мусить бути відмовою, а не міткою", 1, v.code)
        assertEquals("stale", v.kind)
        // Речення мусить назвати число, до якого піднімати, — інакше воно
        // повідомляє про біду й лишає людину її шукати.
        assertTrue("вердикт не називає 7: ${v.text}", v.text.contains("7"))
    }

    @Test
    fun `the offline valve does not open a definite disagreement`() {
        // Клапан існує для «не дістався сервера». Якщо сервер ВІДПОВІВ і не
        // погодився, чесних причин збирати далі немає — і клапан не сміє їх
        // вигадати. Без цієї перевірки заслін мав би обхід в одну змінну.
        val v = check(declared = "6", policy = policy(7), unverifiedAllowed = true)
        assertEquals("PHANTOM_PUBLISHED_UNVERIFIED обійшов stale", 1, v.code)
        assertEquals("stale", v.kind)
    }

    @Test
    fun `agreement passes`() {
        val v = check(declared = "7", policy = policy(7))
        assertEquals(v.text, 0, v.code)
        assertEquals("ok", v.kind)
    }

    @Test
    fun `every verdict names the worktree and branch it measured`() {
        // 05.09: тривогу «publishedBuild = 6» підняли, прочитавши
        // rebuild/mobile-day1 замість integration/beta — те саме імʼя файла,
        // інша гілка, і жодного способу помітити це в переказі. Відтоді вимір
        // носить своє походження з собою: вердикт без гілки переказати не можна.
        val branch = ProcessBuilder("git", "branch", "--show-current")
            .directory(repoRoot()).start()
            .inputStream.bufferedReader().readText().trim()

        listOf(
            check(declared = "7", policy = policy(7)),
            check(declared = "6", policy = policy(7)),
            check(declared = "8", policy = policy(7)),
            check(declared = "7", policy = null),
            check(declared = "7", policy = null, unverifiedAllowed = true),
        ).forEach { v ->
            assertTrue("вердикт не називає дерева: ${v.text}", v.text.contains(repoRoot().name))
            if (branch.isNotEmpty()) {
                assertTrue("вердикт не називає гілки: ${v.text}", v.text.contains(branch))
            }
        }
    }

    @Test
    fun `a number ahead of the cabinet warns but does not block`() {
        // Асиметрія навмисна: збірка могла поїхати в Play повз кабінет, і
        // найгірше з цього — пропущений номер, якого телефон не помічає
        // (порівняння строге). Відставання ж коштує мовчазним дублікатом.
        val v = check(declared = "8", policy = policy(7))
        assertEquals("випередження не є аварією й не має спиняти реліз", 0, v.code)
        assertEquals("ahead", v.kind)
    }

    @Test
    fun `no document is a refusal, not a silent pass`() {
        val v = check(declared = "7", policy = null)
        assertEquals("незвірене пройшло мовчки — це гірше за відсутній заслін", 1, v.code)
        assertEquals("unknown", v.kind)
        assertTrue(
            "відмова не називає, як зібрати без мережі: ${v.text}",
            v.text.contains("PHANTOM_PUBLISHED_UNVERIFIED"),
        )
    }

    @Test
    fun `no document plus a deliberate valve passes, and says so out loud`() {
        // Реліз у потязі лишається можливим — ціною одного вписаного слова,
        // яке потім видно в журналі релізу.
        val v = check(declared = "7", policy = null, unverifiedAllowed = true)
        assertEquals(v.text, 0, v.code)
        assertEquals("unverified", v.kind)
        assertTrue("рядок не признається, що звірки не було: ${v.text}", v.text.contains("НЕ ЗВІРЕНО"))
    }

    @Test
    fun `a document that cannot be read is unknown, never zero`() {
        // Порожнє чи покалічене — «не знаю». Прочитати таке як 0 означало б
        // тихо погодитись із будь-яким числом у файлі.
        listOf("", "not json at all", """{"latest":null}""", """{"latest":{"build":"сім"}}""")
            .forEach { doc ->
                val v = check(declared = "7", policy = doc)
                assertEquals("документ <$doc> прийнято за відповідь", 1, v.code)
                assertEquals("unknown", v.kind)
            }
    }

    @Test
    fun `an announcement for another platform is not our answer`() {
        val v = check(declared = "7", policy = policy(9, platform = "ios"))
        assertEquals("чужа платформа порахована як наша", 1, v.code)
        assertEquals("unknown", v.kind)
    }

    // ── проводка: заслін мусить не лише існувати, а й спрацьовувати ──────────

    @Test
    fun `with no argument the guard reads the real constant from the build file`() {
        // Найтихіший спосіб зробити цей заслін марним — лишити його читати
        // щось інше, ніж рядок, на якому стоїть реліз. Тому: не подаємо числа
        // взагалі, а оголошуємо на одиницю більше за те, що СПРАВДІ записане в
        // app/build.gradle.kts, і вимагаємо `stale`.
        val declared = Regex("""^val publishedBuild = (\d+)""", RegexOption.MULTILINE)
            .find(File(repoRoot(), "app/build.gradle.kts").readText())
            ?.groupValues?.get(1)?.toInt()
            ?: fail("у app/build.gradle.kts немає рядка `val publishedBuild = <число>`") as Nothing

        val v = check(declared = null, policy = policy(declared + 1))
        assertEquals("скрипт без аргументу читає не ту константу: ${v.text}", 1, v.code)
        assertEquals("stale", v.kind)

        val same = check(declared = null, policy = policy(declared))
        assertEquals(same.text, 0, same.code)
        assertEquals("ok", same.kind)
    }

    @Test
    fun `the release path actually calls the guard`() {
        // Урок дому: написане й не викликане стереже рівно нічого.
        val release = File(repoRoot(), "scripts/release.sh")
        val text = release.readText()
        val call = text.lines().filter { it.contains("check-published-build.sh") && !it.trimStart().startsWith("#") }
        assertTrue("scripts/release.sh не викликає $guard — заслін мертвий", call.isNotEmpty())
        assertTrue(
            "виклик не спиняє реліз (немає die/exit на невдачі): $call",
            call.any { it.contains("die") || it.contains("exit") },
        )
        // Викликати треба ДО того, як щось коштує часу: спершу черга за
        // машинним замком (до 40 хв), потім збірка. Звірка після них означала б
        // дізнатись про згорілий номер через годину.
        val at = text.indexOf("check-published-build.sh")
        assertTrue(
            "звірка стоїть після черги за замком — реліз дізнається про біду через годину",
            at < text.indexOf("flock -w"),
        )
        assertTrue(
            "звірка стоїть після першого запуску Gradle",
            at < text.indexOf("./gradlew"),
        )
        assertTrue("$guard не виконуваний", File(repoRoot(), guard).canExecute())
    }
}
