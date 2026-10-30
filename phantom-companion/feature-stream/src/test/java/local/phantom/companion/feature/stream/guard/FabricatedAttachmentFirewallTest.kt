package local.phantom.companion.feature.stream.guard

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Вкладення, якого немає, не має права називатись.
 *
 * Аркуш вкладень у рідних екранах розмови клав у поле рядок дроту з вигаданим
 * вмістом: «tactical_briefing.pdf;148KB» для файла, якого ніхто не вибирав,
 * і «voice_<мілісекунди>» для запису, якого ніхто не робив. Приймач малює з
 * такого рядка картку файла з назвою й вагою — тобто екран показує людині
 * вкладення, за яким на диску немає жодного байта, і жоден шматок ніколи не
 * поїде: справжня дорога для байтів — PeerFile (PH-FCH:) і PeerFileStore, і
 * веде до неї тільки міст веб-месенджера.
 *
 * Тег без вмісту — це розбір: `startsWith("PH-FILE:")`, `removePrefix(...)`.
 * Він дозволений і потрібен, бо приймати те, що приїхало, доводиться далі.
 * Заборонений саме тег, у якому ВЖЕ щось написано: такий рядок хтось склав
 * тут, а не прочитав із дроту.
 *
 * Живе у feature-stream, бо цей модуль ганяє воріт
 * (`:feature-stream:testDebugUnitTest`), і читає чужі модулі як звичайні
 * файли — Gradle не бачить цих входів, тож при зміні лише app/ ганяти з
 * `--rerun`.
 */
class FabricatedAttachmentFirewallTest {

    @Test
    fun `no main source mints an attachment tag with content in it`() {
        val offenders = mutableListOf<String>()
        for (file in mainSources()) {
            for ((n, line) in strip(file).withIndex()) {
                if (MINTED.containsMatchIn(line)) {
                    offenders += "${file.path.substringAfter(root().path + "/")}:${n + 1}: ${line.trim()}"
                }
            }
        }
        assertTrue(
            "рядок дроту складено тут, а не прочитано з дроту — це вкладення, " +
                "якого немає на диску й якого ніхто не повезе:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /**
     * Сам сторож теж має вміти червоніти. Якби регулярка перестала ловити
     * вигадане, попередній тест зеленів би на будь-якому дереві й нічого не
     * стеріг би — тож тут вона зустрічає рівно ті рядки, які колись стояли в
     * коді, і рівно ті, які мусять лишитись дозволеними.
     */
    @Test
    fun `the pattern catches minted tags and spares parsing prefixes`() {
        val minted = listOf(
            """draft = "PH-FILE:tactical_briefing.pdf;148KB"""",
            """draft = "PH-FILE:tactical_plan.pdf;320KB"""",
            """draft = "PH-VOICE:15;voice_${'$'}{System.currentTimeMillis()}"""",
            """onChange("PH-VOICE:${'$'}finalSec;voice_${'$'}{System.currentTimeMillis()}")""",
            """draft = "PH-IMAGE:recon_point_1.jpg"""",
            """draft = "PH-KEY:GROUP_${'$'}{group.id}"""",
        )
        for (line in minted) {
            assertTrue("не спіймано вигадане: $line", MINTED.containsMatchIn(line))
        }
        val parsing = listOf(
            """clean.startsWith("PH-FILE:")""",
            """val data = clean.removePrefix("PH-FILE:")""",
            """said.startsWith("PH-IMAGE:")""",
            """internal const val CHUNK = "PH-FCH:"""",
            """"PH-VOICE" -> R.string.people_kind_voice""",
        )
        for (line in parsing) {
            assertTrue("розбір помилково названо вигадкою: $line", !MINTED.containsMatchIn(line))
        }
    }

    /** Коментар — це розмова про код, і ловити його означало б ловити пояснення. */
    private fun strip(file: File): List<String> =
        file.readLines().filterNot {
            val t = it.trim()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }

    /** Усе, що збирається в застосунок. `src/test` і `src/androidTest` — ні. */
    private fun mainSources(): List<File> =
        root().listFiles().orEmpty()
            .filter { it.isDirectory && File(it, "src/main").isDirectory }
            .flatMap { File(it, "src/main").walkTopDown().filter { f -> f.extension == "kt" } }

    private fun root(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return requireNotNull(dir) { "корінь репозиторію не знайдено" }
    }

    private companion object {
        /**
         * Тег вкладення, за двокрапкою якого одразу стоїть щось, крім лапки:
         * саме це «щось» і є вигаданим іменем, вагою чи тривалістю.
         */
        val MINTED = Regex("\"PH-(?:FILE|VOICE|IMAGE|KEY):[^\"]")
    }
}
