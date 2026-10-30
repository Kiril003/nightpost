package local.phantom.companion.core.design

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Сторожа переписують, не знімають.
 *
 * Найдешевший спосіб зробити ворота зеленими — прибрати перевірку, яка їх
 * тримала червоними. Це майже ніколи не злий намір: сторожа зносить разом із
 * пакетом, який він стеріг, разом із віджетом, разом із «мертвим» кодом. І
 * сліду це не лишає: тести зелені, бо перевірки більше немає, а не бо
 * поведінка правильна.
 *
 * У цьому дереві останній на час написання коміт `4535b30c` («три бригади»)
 * видалив `PoiSearchTest.kt` цілком — 161 рядок, 29 перевірок — разом із
 * пакетом `core-data/.../poi/search/`, який той стеріг. Серед знятого була вся
 * поверхня екранування запиту до FTS: незакрита лапка, гола диспозиція `OR`,
 * двокрапка, дужка. Поверхня нікуди не поділась — операторський текст і далі
 * стає виразом `MATCH`, тільки вже в `OfflinePoiSearch`. У повідомленні коміта
 * про зняття не сказано жодного слова.
 *
 * Закон дому названий давно — **коли контракт змінено свідомо, сторожа
 * ПЕРЕПИСУЮТЬ під новий контракт**. Цей файл робить із звичаю заслін.
 *
 * ## Правило
 *
 * Червоно, коли в ОДНОМУ комміті одночасно:
 *
 *  1. щось змінено під `src/main/` будь-якого модуля — поведінка рухалась; і
 *  2. з-під `src/test/` пішли перевірки, яким не знайшлося пари.
 *
 * ## «Не знайшлося пари» — рахунок у два кроки
 *
 * Саме ці два кроки роблять заслін тихим на чесній роботі:
 *
 *  - **крок 1, у межах файла.** Знята перевірка гаситься доданою ТОГО Ж РОДУ
 *    (`assertEquals` проти `assertEquals`). Це переписування сторожа під новий
 *    контракт — санкціонована дія, вона не має червоніти;
 *  - **крок 2, між файлами.** Те, що лишилось, гаситься доданим рядком
 *    ДОСЛІВНО. Це переїзд перевірки в інший файл — теж не втрата.
 *
 * Що пережило обидва кроки — перевірка, яка вийшла з дерева і нікуди не
 * прийшла.
 *
 * Наївне «знятих більше, ніж доданих» тут НЕ працює, і це заміряно, а не
 * припущено: на тих самих 80 комітах воно дає 2 червоних і пропускає рівно
 * `4535b30c` — там зняли 45 перевірок і додали 171, арифметика зійшлась із
 * запасом. Валовий баланс каже, скільки перевірок у дереві; він мовчить про
 * те, чи нові стережуть те саме, що старі.
 *
 * ## Клапан
 *
 * Рядок у повідомленні коміта, що починається з `СТОРОЖ-ЗНЯТО:` і несе бодай
 * десять символів пояснення, пропускає коміт. Заборонити зняття — не мета;
 * мета — щоб воно не траплялось МОВЧКИ. Клапан коштує один рядок, бо заслін,
 * який дорого обходити, обходять іншим способом.
 *
 * ## Чого цей заслін НЕ бачить
 *
 * Це головна частина файла, і вона важливіша за правило. Заслін накриває
 * менше, ніж здається; читач мусить знати межу, а не здогадуватись про неї.
 *
 *  1. **Двокроковий обхід — найдешевша дірка, і вона незакривна.** Розклади
 *     одну дію на два коміти — і обидва зелені. Коміт N знімає перевірку й не
 *     чіпає `src/main`: умова 1 не виконана, зелено. Коміт N+1 змінює
 *     поведінку й не чіпає тести: знімати нічого, зелено. Разом — тиха зміна
 *     поведінки без сторожа, і в жодному з двох комітів немає нічого, за що
 *     можна вхопитись. Жодне правило, яке дивиться на ОДИН коміт, цього не
 *     закриє: докази лежать у двох різних місцях історії, і кожен окремо
 *     невинний. Хто знає про заслін і хоче його обійти — обійде за дві
 *     хвилини.
 *  2. **Заслін завжди на коміт позаду.** Він питає `HEAD` — тобто коміт, який
 *     УЖЕ зроблено, — і жодного разу не дивиться в робоче дерево. Незакомічені
 *     зміни для нього не існують. Отже зняття ловиться не на тих воротах, що
 *     проводжали сам коміт, а на НАСТУПНИХ: спершу зняття лягає в історію,
 *     і аж потім хтось про нього чує. Поза оглядом лишається й усе, що
 *     переписує поняття «останній»: `--amend`, rebase, squash, cherry-pick, а
 *     також будь-який коміт хвилі, між якими ворота не бігали, — заслін бачить
 *     лише вершину, решта хвилі проїжджає непрочитаною.
 *
 *     Заміряно на живому 30.08.2026, і це не думка. Штаб зняв сторожа в
 *     `RouteOrderTest`, змінив `core-nav/src/main`, закомітив без визнання —
 *     і повідомив, що заслін мовчав. Хронологія з reflog: прогін о 05:00:47,
 *     коміт `31a284fb` о 05:01:26, скасування о 05:01:28. Заслін судив
 *     попередній, невинний коміт, бо в момент прогону зняття лежало ще
 *     НЕЗАКОМІЧЕНИМ, а закомічене прожило дві секунди, і в те вікно ворота не
 *     бігали. Сама різниця того коміта тепер стоїть тут взірцем
 *     (`quietRemovalReproduction`) і дає «червоно».
 *
 *     Мораль не в тому, що правило погане, а в тому, що ця сліпота
 *     спрацьовує САМА, без наміру: людина ганяє ворота перед комітом, а не
 *     після. Тому «зелено» від цього файла означає рівно «останній
 *     ЗАКОМІЧЕНИЙ стан чистий» — і тепер він друкує, який саме коміт судив,
 *     щоб зелене не читали як вирок про те, чого він не бачив.
 *  3. **Себе він не стереже.** Якщо тести `:core-design` перестануть бігати —
 *     модуль випав із команди воріт, теку виключили з таски, цей файл просто
 *     видалили — не скаже ніхто. Заслін, що зник, поводиться точно як заслін,
 *     що мовчить, і відрізнити їх ззовні нема чим. Це рівно той провал, заради
 *     якого він написаний, і на собі він його не ловить.
 *  4. **Ослаблення на місці з тим самим родом.** `assertEquals(a, b)` →
 *     `assertEquals(a, a)`, `assertTrue(реальнаУмова)` → `assertTrue(true)`,
 *     звужений діапазон, знята гілка `verify`. Рід збігся — крок 1 гасить. Це
 *     свідома ціна: без гасіння по роду заслін червонів би на кожному чесному
 *     переписуванні. Зміна роду (`assertEquals` → `assertNotNull`) при цьому
 *     ловиться.
 *  5. **Тест лишили, але вимкнули.** `@Ignore` — це доданий рядок, знятого
 *     нема. Так само: модуль прибрано з `settings.gradle.kts`, клас
 *     перейменовано повз шаблон прогону, `assumeTrue(false)` на початку.
 *  6. **Поведінку змінили там, де сторожа НІКОЛИ не було.** Знімати нічого —
 *     видно нічого. Заслін боронить наявні перевірки; вимагати нову він не
 *     вміє і не вдає, що вміє.
 *  7. **Коміти злиття.** `git show` на злитті друкує комбіновану різницю, яка
 *     порожня, якщо не було конфліктів. Зняття, що приїхало злиттям з чужої
 *     гілки, заслін не бачить узагалі.
 *  8. **Розпізнавання текстове, не смислове.** Перевіркою вважається рядок,
 *     що ВИГЛЯДАЄ як перевірка: `assert*(`, `verify*(`, `fail(`, `@Test` —
 *     поза імпортами й коментарями. Знятий `verifySignature(` у допоміжному
 *     коді порахується перевіркою, хоч нею не є; сторож, схований у власний
 *     хелпер (`expectRefusal { }`), не порахується ніяк. Сторож, що живе
 *     шел-скриптом у теці `scripts`, правилом лінта, у маніфесті або
 *     числом-стелею ратчета, для цього заслону не існує.
 *  9. **Баланс можна докупити.** Один `assertTrue(true)` — це один доданий
 *     рядок, і він гасить одне справжнє зняття того ж роду в тому ж файлі.
 *     Ніхто не звіряє, чи додане варте знятого.
 * 10. **Клапан тримається на чесності.** Проходить будь-який рядок із токеном
 *     і десятьма символами після нього; ніхто не перевіряє, що пояснення
 *     правдиве й що воно називає саме того сторожа, який пішов. Спрацює й
 *     тоді, коли токен потрапив у повідомлення випадково.
 * 11. **Поведінкою вважається лише `src/main/`.** Зміна у теці `scripts`, у
 *     `gradle/libs.versions.toml` чи в самих файлах `build.gradle.kts` умови 1
 *     не піднімає — такий коміт зі зняттям перевірки читається зеленим.
 *
 * Підсумок межі, і це не застереження дрібним шрифтом, а призначення файла:
 * **він робить зняття помітним, а не неможливим.** Він не боронить дерево від
 * того, хто хоче зняти сторожа, — від такого боронить лише те, що зняття
 * доведеться назвати вголос. Він боронить дерево від того, хто зняв сторожа й
 * сам цього не помітив. Другий випадок трапляється на порядок частіше.
 *
 * ## Форма, заміряна перед написанням
 *
 * На 80 комітах, що передували цьому файлу, правило червоніє на 11 — приблизно
 * один із семи. Найбільший червоний — `ee55dad0` («фундамент перебудови мапи»,
 * 444 зняті перевірки), найменші — коміти з однією. Якщо ця частка поповзе
 * вгору, це не привід прибрати заслін; це привід прочитати, що саме дім почав
 * знімати.
 *
 * GATE LAW (спільний з іншими вартовими цього модуля): читає git як зовнішній
 * процес, повз перевірку актуальності Gradle. Ганяти з `--rerun`.
 */
class GuardRemovalFirewallTest {

    private val amnestyToken = "СТОРОЖ-ЗНЯТО:"
    private val amnestyMinReason = 10

    private val checkShape =
        Regex("""(?:\b(assert\w*|verify\w*|fail)\s*\()|(@Test\b)""", RegexOption.IGNORE_CASE)

    /** Перевірка, що вийшла з дерева і нікуди не прийшла. */
    private data class Gone(val file: String, val line: String)

    private data class Verdict(
        val mainTouched: Boolean,
        val gone: List<Gone>,
        val amnesty: String?,
    ) {
        val red: Boolean get() = mainTouched && gone.isNotEmpty() && amnesty == null
    }

    /**
     * Рід перевірки або `null`. Імпорти й коментарі відсікаються першими:
     * `import org.junit.Assert.assertEquals` має форму перевірки і нею не є, а
     * видалений блок KDoc інакше порахувався б десятком знятих сторожів.
     */
    private fun checkKind(body: String): String? {
        val t = body.trim()
        if (t.startsWith("import ") || t.startsWith("package ") ||
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        ) {
            return null
        }
        val m = checkShape.find(t) ?: return null
        return if (m.groupValues[2].isNotEmpty()) "@Test" else m.groupValues[1].lowercase()
    }

    private fun amnestyIn(message: String): String? = message.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith(amnestyToken) }
        .map { it.removePrefix(amnestyToken).trim() }
        .firstOrNull { it.length >= amnestyMinReason }

    private fun pathAfter(raw: String, prefix: String): String? {
        val p = raw.trim().substringBefore('\t')
        if (p == "/dev/null") return null
        return if (p.startsWith(prefix)) p.removePrefix(prefix) else p
    }

    /** Ядро — чиста функція над текстом різниці й повідомленням коміта. */
    private fun judge(commitMessage: String, diff: String): Verdict {
        var inHeader = false
        var oldPath: String? = null
        var newPath: String? = null
        var mainTouched = false
        val added = mutableMapOf<String, MutableList<Pair<String, String>>>()
        val removed = mutableMapOf<String, MutableList<Pair<String, String>>>()

        for (line in diff.lineSequence()) {
            // Заголовок відсікається прапорцем, а не переліком префіксів: рядок
            // коду, що починається з «-- », у різниці стає «--- » і не
            // відрізняється від заголовка інакше як позицією.
            if (line.startsWith("diff --git ")) {
                inHeader = true
                oldPath = null
                newPath = null
                continue
            }
            if (line.startsWith("@@")) {
                inHeader = false
                continue
            }
            if (inHeader) {
                if (line.startsWith("--- ")) oldPath = pathAfter(line.substring(4), "a/")
                if (line.startsWith("+++ ")) newPath = pathAfter(line.substring(4), "b/")
                continue
            }
            if (!line.startsWith("+") && !line.startsWith("-")) continue

            // Для видаленого файла newPath порожній (`+++ /dev/null`), для
            // нового — oldPath; беремо той, що є.
            val path = newPath ?: oldPath ?: continue
            if (path.contains("/src/main/") || path.startsWith("src/main/")) mainTouched = true
            // Варіантні набори теж тести: `src/testDebug/`, `src/testFieldDebug/`,
            // `src/androidTestDebug/`. До 21.09 тут стояло рівно два імені,
            // `/src/test/` і `/src/androidTest/`, і через це ПЕРЕЇЗД перевірки
            // у варіантний набір читався як її ЗНИКНЕННЯ: пішла з видимого
            // місця, прийшла в невидиме, сторож оголосив її знятою.
            //
            // Спіймано на собі 21.09. Три перевірки з ForeignSignatureIsVisibleTest
            // переїхали в `app/src/testDebug/`, бо питали BuildConfig, різний по
            // типах збірки, і в release падали з причини, яка не є вадою. Сторож
            // назвав це зняттям і поклав реліз — маючи рацію за наміром і
            // помиляючись за висновком.
            //
            // Сліпа пляма тут дорожча за хибне спрацювання: варіантний набір —
            // це якраз те місце, куди переносять перевірку, щоб вона перестала
            // бігати всюди. Не бачити його означає не бачити цілого класу
            // тихих знять.
            val isTest = Regex("""(?:^|/)src/(?:android)?[Tt]est[A-Za-z0-9]*/""")
                .containsMatchIn(path)
            if (!isTest) continue

            val body = line.substring(1).trim()
            val kind = checkKind(body) ?: continue
            val sink = if (line.startsWith("+")) added else removed
            sink.getOrPut(path) { mutableListOf() } += kind to body
        }

        // Крок 1 — у межах файла, за родом. Переписаний сторож гасить сам себе.
        val leftRemoved = mutableListOf<Triple<String, String, String>>()
        val leftAdded = mutableListOf<String>()

        for ((path, items) in removed) {
            val pool = added[path].orEmpty().groupingBy { it.first }.eachCount().toMutableMap()
            for ((kind, text) in items) {
                val n = pool[kind] ?: 0
                if (n > 0) pool[kind] = n - 1 else leftRemoved += Triple(path, kind, text)
            }
        }
        for ((path, items) in added) {
            val pool = removed[path].orEmpty().groupingBy { it.first }.eachCount().toMutableMap()
            for ((kind, text) in items) {
                val n = pool[kind] ?: 0
                if (n > 0) pool[kind] = n - 1 else leftAdded += text
            }
        }

        // Крок 2 — між файлами, ДОСЛІВНО. Переїзд перевірки — не втрата.
        val movedIn = leftAdded.groupingBy { it }.eachCount().toMutableMap()
        val gone = mutableListOf<Gone>()
        for ((path, _, text) in leftRemoved) {
            val n = movedIn[text] ?: 0
            if (n > 0) movedIn[text] = n - 1 else gone += Gone(path.substringAfterLast('/'), text)
        }

        return Verdict(mainTouched, gone, amnestyIn(commitMessage))
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь дерева з feature-stream/ не знайдено") as Nothing
    }

    private fun git(vararg args: String): String {
        val root = repoRoot()
        val proc = try {
            ProcessBuilder(listOf("git", "-C", root.path) + args)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            fail(
                "ЗАСЛІН НЕ ПРАЦЮЄ, а не «пропущено»: git не запускається " +
                    "(${e.javaClass.simpleName}: ${e.message}).\n" +
                    "Заслін читає останній коміт через git. Тихо пропущений " +
                    "заслін гірший за відсутній, тому це червоне.\n" +
                    "Полагодь git у середовищі прогону або зніми цей заслін " +
                    "свідомо — але не мовчки.",
            )
            return ""
        }
        // Читати ДО waitFor: різниця великого коміта переповнює буфер труби, і
        // обидві сторони стають назавжди.
        val out = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(60, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            fail("ЗАСЛІН НЕ ПРАЦЮЄ: git ${args.joinToString(" ")} не відповів за 60 с.")
        }
        if (proc.exitValue() != 0) {
            fail(
                "ЗАСЛІН НЕ ПРАЦЮЄ, а не «пропущено»: git ${args.joinToString(" ")} " +
                    "вийшов із кодом ${proc.exitValue()} у ${root.path}.\n" +
                    "Вивід:\n${out.take(600)}\n" +
                    "Найімовірніше це не репозиторій (експорт `git archive`) або " +
                    "немає жодного коміта.",
            )
        }
        return out
    }

    @Test
    fun `git і останній коміт досяжні — тихого пропуску немає`() {
        val head = git("rev-parse", "HEAD").trim()
        assertTrue(
            "git відповів, але HEAD не схожий на коміт: «$head». Заслін мовчати не має права.",
            Regex("[0-9a-f]{40}").matches(head),
        )
    }

    @Test
    fun `останній коміт не знімає сторожа мовчки`() {
        val head = git("log", "-1", "--format=%h %s").trim()
        val message = git("log", "-1", "--format=%B")
        val diff = git(
            "show", "HEAD", "--no-color", "--no-ext-diff", "--no-textconv",
            "--unified=0", "--find-renames", "--format=",
        )
        val verdict = judge(message, diff)

        // Зелене мусить казати, ЩО саме воно судило.
        //
        // 30.08.2026 штаб зняв сторожа, закомітив і повідомив, що заслін мовчав.
        // Заслін судив попередній коміт: прогін стався за 39 секунд ДО того, як
        // зняття лягло в історію. Довести це вдалося лише через reflog, бо
        // зелений вирок не лишав по собі жодного сліду про свій предмет. Тепер
        // лишає: цей рядок їде у `<system-out>` звіту, і «10 тестів, 0 провалів»
        // більше не можна прочитати як вирок про коміт, якого заслін не бачив.
        println("ЗАСЛІН СУДИВ КОМІТ: $head — ${if (verdict.red) "ЧЕРВОНО" else "чисто"}")

        if (!verdict.red) return

        fail(
            buildString {
                append("Коміт змінив поведінку і забрав сторожа, який її стеріг.\n\n")
                append("  $head\n\n")
                append("Пішли з дерева й нікуди не прийшли (${verdict.gone.size}):\n")
                verdict.gone.take(12).forEach { append("  ${it.file}: ${it.line.take(110)}\n") }
                if (verdict.gone.size > 12) append("  … і ще ${verdict.gone.size - 12}\n")
                append("\nЗакон дому: контракт змінено свідомо — сторожа ПЕРЕПИСУЮТЬ під\n")
                append("новий контракт, а не знімають. Перепиши перевірку — і це згасне\n")
                append("саме собою.\n\n")
                append("Якщо сторож мусив піти, скажи це вголос — окремим рядком у\n")
                append("повідомленні коміта (`git commit --amend`):\n\n")
                append("  $amnestyToken <чому саме ця перевірка більше не має сенсу>\n\n")
                append("Заслін не забороняє зняття. Він забороняє ТИХЕ зняття.")
            },
        )
    }

    @Test
    fun `взірець — чиста зміна в main без руху тестів проходить`() {
        val diff = """
            diff --git a/core-sensor/src/main/java/local/phantom/companion/core/sensor/nav/GraphCoasting.kt b/core-sensor/src/main/java/local/phantom/companion/core/sensor/nav/GraphCoasting.kt
            index 1111111..2222222 100644
            --- a/core-sensor/src/main/java/local/phantom/companion/core/sensor/nav/GraphCoasting.kt
            +++ b/core-sensor/src/main/java/local/phantom/companion/core/sensor/nav/GraphCoasting.kt
            @@ -12 +12 @@
            -    private const val MAX_COAST_SECONDS = 30
            +    private const val MAX_COAST_SECONDS = 45
        """.trimIndent()

        val verdict = judge("fix(коустинг): межа тримається довше на тунелі", diff)

        assertTrue("поведінка рухалась — це має бути видно", verdict.mainTouched)
        assertEquals("сторожів не чіпали — нема кого оплакувати", emptyList<Gone>(), verdict.gone)
        assertFalse(verdict.red)
    }

    @Test
    fun `взірець — сторож, знятий поруч зі зміною main, червоніє`() {
        val verdict = judge("три бригади: живий пошук місць", guardRemovalDiff)

        assertTrue(verdict.red)
        assertEquals(1, verdict.gone.size)
        assertEquals("PoiSearchTest.kt", verdict.gone.single().file)
        assertTrue(verdict.gone.single().line.contains(" OR "))
    }

    /**
     * Головна форма, заради якої рахунок двокроковий: `guardRemovalDiff` додає
     * перевірок ВЧЕТВЕРО більше, ніж знімає. Наївне «знятих більше, ніж
     * доданих» на такому комміті зелене — саме так воно пропустило `4535b30c`
     * (45 знятих проти 171 доданої).
     */
    @Test
    fun `взірець — валове «доданих більше» не викуповує знятого`() {
        fun checksOn(sign: String, header: String) = guardRemovalDiff.lineSequence()
            .count { it.startsWith(sign) && !it.startsWith(header) && checkKind(it.substring(1)) != null }

        assertTrue("взірець мусить мати перевагу доданих", checksOn("+", "+++") > checksOn("-", "---"))
        assertTrue("і попри перевагу — червоний", judge("живий пошук місць", guardRemovalDiff).red)
    }

    @Test
    fun `взірець — рядок-визнання пропускає той самий коміт`() {
        val message = """
            три бригади: живий пошук місць

            $amnestyToken пакет poi/search видалено як мертвий, а поверхню
            екранування запиту вже стереже OfflinePoiSearchTest.
        """.trimIndent()

        val verdict = judge(message, guardRemovalDiff)

        assertEquals(1, verdict.gone.size)
        assertFalse("клапан мав пропустити — зняття назване вголос", verdict.red)
    }

    @Test
    fun `взірець — перевірка, що просто переїхала у інший файл, не є зняттям`() {
        val diff = """
            diff --git a/core-data/src/main/java/local/phantom/companion/core/data/poi/OfflinePoiSearch.kt b/core-data/src/main/java/local/phantom/companion/core/data/poi/OfflinePoiSearch.kt
            index 1111111..2222222 100644
            --- a/core-data/src/main/java/local/phantom/companion/core/data/poi/OfflinePoiSearch.kt
            +++ b/core-data/src/main/java/local/phantom/companion/core/data/poi/OfflinePoiSearch.kt
            @@ -12 +12 @@
            -    private const val TAG = "search"
            +    private const val TAG = "poi-search"
            diff --git a/core-data/src/test/java/local/phantom/companion/core/data/poi/PoiSearchTest.kt b/core-data/src/test/java/local/phantom/companion/core/data/poi/PoiSearchTest.kt
            deleted file mode 100644
            index 3333333..0000000
            --- a/core-data/src/test/java/local/phantom/companion/core/data/poi/PoiSearchTest.kt
            +++ /dev/null
            @@ -8,3 +0,0 @@
            -    @Test
            -    fun `гола диспозиція не виживає`() {
            -        assertTrue("no bare disjunction may survive", !expr.contains(" OR "))
            diff --git a/core-data/src/test/java/local/phantom/companion/core/data/poi/PoiTextMatchTest.kt b/core-data/src/test/java/local/phantom/companion/core/data/poi/PoiTextMatchTest.kt
            new file mode 100644
            index 0000000..4444444
            --- /dev/null
            +++ b/core-data/src/test/java/local/phantom/companion/core/data/poi/PoiTextMatchTest.kt
            @@ -0,0 +1,3 @@
            +    @Test
            +    fun `гола диспозиція не виживає`() {
            +        assertTrue("no bare disjunction may survive", !expr.contains(" OR "))
        """.trimIndent()

        val verdict = judge("refactor(пошук): перевірка переїхала у свій файл", diff)

        assertTrue(verdict.mainTouched)
        assertEquals("переїзд — не втрата", emptyList<Gone>(), verdict.gone)
        assertFalse(verdict.red)
    }

    @Test
    fun `взірець — переписаний на місці сторож не є зняттям`() {
        val diff = """
            diff --git a/core-design/src/main/java/local/phantom/companion/core/design/theme/Theme.kt b/core-design/src/main/java/local/phantom/companion/core/design/theme/Theme.kt
            index 1111111..2222222 100644
            --- a/core-design/src/main/java/local/phantom/companion/core/design/theme/Theme.kt
            +++ b/core-design/src/main/java/local/phantom/companion/core/design/theme/Theme.kt
            @@ -20 +20 @@
            -    val default = ThemeMode.Dark
            +    val default = ThemeMode.Solar
            diff --git a/core-design/src/test/java/local/phantom/companion/core/design/theme/ThemeModeTest.kt b/core-design/src/test/java/local/phantom/companion/core/design/theme/ThemeModeTest.kt
            index 3333333..4444444 100644
            --- a/core-design/src/test/java/local/phantom/companion/core/design/theme/ThemeModeTest.kt
            +++ b/core-design/src/test/java/local/phantom/companion/core/design/theme/ThemeModeTest.kt
            @@ -14 +14 @@
            -        assertEquals(ThemeMode.Dark, parseStoredThemeMode(null))
            +        assertEquals(ThemeMode.Solar, parseStoredThemeMode(null))
        """.trimIndent()

        val verdict = judge("feat(тема): жива тема повертається типовою", diff)

        assertFalse("сторожа переписали — це санкціонована дія", verdict.red)
    }

    @Test
    fun `взірець — визнання без причини не є визнанням`() {
        val bare = judge("живий пошук місць\n\n$amnestyToken\n", guardRemovalDiff)
        val curt = judge("живий пошук місць\n\n$amnestyToken бо\n", guardRemovalDiff)

        assertTrue("голий токен — не визнання", bare.red)
        assertTrue("двобуквене «бо» — не пояснення", curt.red)
    }

    @Test
    fun `взірець — зняте у тестах без руху main не червоніє, і це названа дірка`() {
        val diff = guardRemovalDiff.lineSequence()
            .dropWhile { !it.startsWith("diff --git a/core-data/src/test/") }
            .joinToString("\n")

        val verdict = judge("chore(пошук): прибрано тест мертвого пакета", diff)

        assertFalse("main не рухався — умова 1 не виконана", verdict.mainTouched)
        assertEquals("сторож усе одно пішов, і заслін це знає", 1, verdict.gone.size)
        assertFalse("…але мовчить: перший крок двокрокового обходу", verdict.red)
    }

    /**
     * ВІДТВОРЕННЯ 30.08.2026, дослівно з коміта `31a284fb`.
     *
     * Штаб перевірив заслін на сліпоті: зняв багаторядковий `assertFalse` під
     * однорядковий коментар, дописав рядок у `core-nav/src/main`, закомітив без
     * визнання — і повідомив, що заслін мовчав. Заслін не мовчав: його прогін
     * стався о 05:00:47, а коміт народився о 05:01:26 і був скасований о
     * 05:01:28. Він судив попередній, невинний коміт (див. сліпоту 2).
     *
     * Цей взірець тримає ту частину, яку тоді запідозрили даремно: на ЦЬОМУ
     * тексті різниці вирок мусить бути «червоно». Якщо він колись позеленіє —
     * заслін таки осліп, і це вже буде не хибне звинувачення.
     *
     * Тут же гинуть чотири здогади, які тоді висували: різницю не обмежено
     * жодним префіксом шляху (`core-nav` видно); зарахування в межах файла не
     * гасить зняте змінами в інших файлах; модуль тесту й модуль `src/main`
     * не мусять збігатися; голий `assertFalse(` без аргументів у тому ж рядку
     * перевіркою ВВАЖАЄТЬСЯ.
     */
    @Test
    fun `взірець — багаторядковий assertFalse, знятий під коментар, червоніє`() {
        val verdict = judge("тимчасовий коміт для перевірки заслону", quietRemovalReproduction)

        assertTrue("зміна в core-nav/src/main мусить бути видна", verdict.mainTouched)
        assertEquals("сторож пішов рівно один", 1, verdict.gone.size)
        assertEquals("RouteOrderTest.kt", verdict.gone.single().file)
        assertEquals("assertFalse(", verdict.gone.single().line)
        assertTrue("тихе зняття — це червоно, і крапка", verdict.red)

        // І те саме, коли поведінка рухалась в ІНШОМУ модулі, ніж тест: заслін
        // не вимагає, щоб main і тест були з одного модуля.
        val crossModule = quietRemovalReproduction.replace("core-nav/src/main", "core-geo/src/main")
        assertTrue("зняття не рятується переїздом причини в інший модуль", judge("x", crossModule).red)
    }

    /**
     * Дослівний вивід
     * `git show --unified=0 --find-renames --format=` на комміті `31a284fb`.
     * Не «схоже на», а те саме, включно з порожнім доданим рядком і суфіксом
     * контексту після `@@` — саме на такому тексті заслін і працює насправді.
     */
    private val quietRemovalReproduction = """
        diff --git a/core-nav/src/main/java/local/phantom/companion/core/nav/RouteOrder.kt b/core-nav/src/main/java/local/phantom/companion/core/nav/RouteOrder.kt
        index 3ed5ca42..03f61bd0 100644
        --- a/core-nav/src/main/java/local/phantom/companion/core/nav/RouteOrder.kt
        +++ b/core-nav/src/main/java/local/phantom/companion/core/nav/RouteOrder.kt
        @@ -28,0 +29,2 @@ suspend fun routeGraphFirst(
        +
        +// зміна поведінки, заради якої сторожа й прибрали
        diff --git a/core-nav/src/test/java/local/phantom/companion/core/nav/RouteOrderTest.kt b/core-nav/src/test/java/local/phantom/companion/core/nav/RouteOrderTest.kt
        index 4120a2d6..e8d0d967 100644
        --- a/core-nav/src/test/java/local/phantom/companion/core/nav/RouteOrderTest.kt
        +++ b/core-nav/src/test/java/local/phantom/companion/core/nav/RouteOrderTest.kt
        @@ -41,4 +41 @@ class RouteOrderTest {
        -        assertFalse(
        -            "мережу не можна питати, коли свій граф уже відповів — це і є ті 12 секунд",
        -            networkAsked,
        -        )
        +        // перевірку тихо знято
    """.trimIndent()

    /**
     * Форма `4535b30c`: `src/main` рухається, старий тестовий файл зникає
     * цілком, а поруч народжується новий — із більшою кількістю перевірок,
     * але про інше. Рядок `@Test` дослівно збігається і гаситься кроком 2;
     * перевірка екранування запиту не має пари ніде.
     */
    private val guardRemovalDiff = """
        diff --git a/core-data/src/main/java/local/phantom/companion/core/data/poi/OfflinePoiSearch.kt b/core-data/src/main/java/local/phantom/companion/core/data/poi/OfflinePoiSearch.kt
        index 1111111..2222222 100644
        --- a/core-data/src/main/java/local/phantom/companion/core/data/poi/OfflinePoiSearch.kt
        +++ b/core-data/src/main/java/local/phantom/companion/core/data/poi/OfflinePoiSearch.kt
        @@ -22 +22 @@
        -    fun toMatchQuery(raw: String): String? = FtsQuery.matchExpression(raw)
        +    fun toMatchQuery(raw: String): String? = raw.split(" ").joinToString(" ") { it + "*" }
        diff --git a/core-data/src/test/java/local/phantom/companion/core/data/poi/PoiSearchTest.kt b/core-data/src/test/java/local/phantom/companion/core/data/poi/PoiSearchTest.kt
        deleted file mode 100644
        index 3333333..0000000
        --- a/core-data/src/test/java/local/phantom/companion/core/data/poi/PoiSearchTest.kt
        +++ /dev/null
        @@ -31,2 +0,0 @@
        -    @Test
        -        assertTrue("no bare disjunction may survive", !expr.contains(" OR "))
        diff --git a/core-data/src/test/java/local/phantom/companion/core/data/poi/PoiTextMatchTest.kt b/core-data/src/test/java/local/phantom/companion/core/data/poi/PoiTextMatchTest.kt
        new file mode 100644
        index 0000000..4444444
        --- /dev/null
        +++ b/core-data/src/test/java/local/phantom/companion/core/data/poi/PoiTextMatchTest.kt
        @@ -0,0 +1,4 @@
        +    @Test
        +        assertEquals("«крещатик» знаходить «Хрещатик»", 1, hits.size)
        +        assertEquals("дві помилки — це вже інше слово", 0, far.size)
        +        assertEquals("сам номер нікого не описує", 0, digits.size)
    """.trimIndent()
}
