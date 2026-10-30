package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Кожен `var` у [MapUiState] — це або пульт, або труп.
 *
 * Аудит мапи 05.09 знайшов три: `indoorEnabled` (оголошено `= true`, ніхто
 * не пише — завжди-істинна умова), `routeCompareOpen` (пишуть лише `false` —
 * писар є, живого запису нема, аркуш недосяжний), і `alarmSheetOpen` (писар
 * і читач справжні, але сам виклик писаря був відрізаний умовою, що не мала
 * стосунку до тривоги — тиха своя область не відкривала «Небо», хоча людині
 * там є на що подивитись). Перші два ловить перший тест тут; третій —
 * другий, бо жоден мір «є писар/читач» його не бачить: виклик написаний і
 * технічно існує, а от чи справді до нього доходить дотик — інше питання.
 *
 * Композиції тут не видно (модуль не має androidTest) — доводиться джерело,
 * тим самим методом, що й AnchorPickReachTest/DrawReachTest.
 *
 * Третій тест тут — від штабу, не з першого аудиту: перша правка `canOpen`
 * лишала мертвою саму мить холодного старту (ні GPS-фіксу, ні жодного разу
 * прочитаної стрічки). `AlertBanner`'s own early return бачив це як «нічого
 * казати» й гасив пігулку — рівно там, де AirAlertWatch.kt каже прямо:
 * мовчання мережі ніколи не читається як безпека.
 */
class MapUiStateReachTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private val mapDir: File
        get() = File(
            repoRoot(),
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map",
        )

    private fun sceneStateSource(): String = File(mapDir, "MapSceneState.kt").readText()

    /** Усе під ui/map, КРІМ файлу, де самі поля оголошені: писар чи читач у
     *  власному оголошенні (наприклад `by mutableStateOf(false)`) нічого не
     *  доводить — це не читання і не запис ЗОВНІ, воно існує без жодного
     *  споживача. */
    private fun sourceOutsideSceneState(): String =
        mapDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "MapSceneState.kt" }
            .joinToString("\n") { it.readText() }

    /** RHS-значення, якими живі прапорці закриваються — власного «увімкнено»
     *  вони не доводять, тож писар лише в ці значення — це writer без дії. */
    private val CLOSING_RHS = setOf("false", "null", "emptyList()", "emptyMap()", "\"\"")

    private fun hasActivatingWrite(name: String, source: String): Boolean {
        val assignment = Regex("""\b${Regex.escape(name)}\s*=\s*([^=\n;][^\n]*)""")
        return assignment.findAll(source).any { m ->
            val head = m.groupValues[1].trim().trimEnd(',').substringBefore(' ')
            head !in CLOSING_RHS
        }
    }

    @Test
    fun `кожне поле MapUiState має живий запис і читача поза MapSceneState_kt`() {
        val decl = Regex("""var (\w+)\s+by\s+mutable\w*StateOf""")
        val fields = decl.findAll(sceneStateSource()).map { it.groupValues[1] }.toList()
        assertTrue("регекс оголошень нічого не знайшов — файл переїхав чи змінив форму", fields.size > 30)

        val outside = sourceOutsideSceneState()
        val dead = fields.filter { name ->
            val reads = Regex("""\b${Regex.escape(name)}\b""").findAll(outside).count()
            !hasActivatingWrite(name, outside) || reads < 2
        }
        assertTrue(
            "поля без живого запису чи читача поза MapSceneState.kt — мертвий прапорець, " +
                "як indoorEnabled/routeCompareOpen з аудиту 05.09 (writer, що пише лише " +
                "false/null, — не писар, а сам факт лишення поля мертвим): $dead",
            dead.isEmpty(),
        )
    }

    @Test
    fun `дотик по пігулці тривоги відкриває Небо і за тихого відомого неба`() {
        val chromeParts = File(mapDir, "MapChromeParts.kt").readText()
        val canOpenLine = chromeParts.substringAfter("val canOpen =").substringBefore("\n")
        assertTrue(
            "canOpen знову рахує лише активну тривогу — тиха своя область (відома, " +
                "але спокійна) знову не відкриває аркуш «Небо» з журналом і маршрутом " +
                "до укриття:\n$canOpenLine",
            "ownOblast != null" in canOpenLine,
        )
    }

    @Test
    fun `пігулка не мовчить на холодному старті — коли стрічку ще ЖОДНОГО разу не читали`() {
        val chromeParts = File(mapDir, "MapChromeParts.kt").readText()
        val body = chromeParts.substringAfter("internal fun AlertBanner(")
        val earlyReturn = body.substringAfter("if (ownOblast == null").substringBefore("\n")
        val canOpenLine = body.substringAfter("val canOpen =").substringBefore("\n")

        // GPS ще не дав фіксу (ownOblast == null) — і стрічку тривог теж
        // жодного разу не читали (NOFEED, activeCount == 0, бо рахунок і сам
        // рахується з тієї ж непрочитаної стрічки). За старою умовою це і
        // читалось як «нема про що казати», хоча AirAlertWatch.kt прямо
        // забороняє саме цей висновок: мовчання мережі — це «не знаю», не
        // «безпечно».
        assertTrue(
            "рання відмова знову гасить пігулку на NOFEED — мовчання мережі знову " +
                "читається як безпека рівно там, де AirAlertWatch.kt це забороняє:\n$earlyReturn",
            "feedSilent" in earlyReturn,
        )
        assertTrue(
            "canOpen знову не пускає дотик, коли стрічку ще ні разу не читали — " +
                "«Небо» на холодному старті знову недосяжне:\n$canOpenLine",
            "feedSilent" in canOpenLine,
        )
    }
}
