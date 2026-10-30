package local.phantom.companion.service

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Підписка на сповіщення про тривогу мусить стояти ПЕРЕД стартом спостерігача.
 *
 * **Що це стереже.** `AirAlertWatch.notices` — `SharedFlow` без повтору: усе,
 * що випромінили, поки підписників немає, зникає **мовчки**, і `tryEmit` про це
 * не скаржиться, бо повернене значення ніхто не читає. Якщо `watch.start()`
 * стоїть вище за `notices.collect`, то між ними існує вікно, у яке може
 * провалитись **перше сповіщення про повітряну тривогу**.
 *
 * **Чому це не було дефектом 31.08 — і чому все одно полагоджено.** Ланцюг до
 * єдиного `tryEmit` у `AirAlertWatch` проходить через мережевий обмін
 * (`fetchBase` → `fetchSiren` → `fetchDetail`), а єдиний швидкий офлайн-вихід
 * `republish()` не випромінює нічого. Тобто вікно було **недосяжне**: підписник
 * устигав приєднатись на наступному диспетчі, поки тривав запит.
 *
 * Це правильність, позичена в **повільності сусіда**. Перша ж розумна
 * оптимізація — «показати останні відомі тривоги миттєво з кешу» — зробила б
 * випромінювання синхронним, і сповіщення почало б зникати. Найгірше: така
 * оптимізація виглядає як покращення, приходить із добрими намірами й проходить
 * рецензію, а вада, яку вона підриває, **не має симптому** — журнал збережеться,
 * просто сповіщення не буде.
 *
 * Тому порядок закріплено тестом, а не лише коментарем: сьогодні ми вже бачили
 * коментар, який стверджував «безумовно» про річ, що стояла під умовою.
 *
 * **Сліпі плями:** читає джерело, а не поведінку; знайде лише ці два рядки в
 * цьому файлі; не помітить, якщо підписку винесуть у метод із іншою назвою.
 */
class AirAlertSubscribeOrderTest {

    private fun source(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "app").isDirectory) dir = dir.parentFile
        val root = dir ?: fail("корінь репозиторію не знайдено") as Nothing
        val f = File(root, "app/src/main/java/local/phantom/companion/service/AirAlertService.kt")
        assertTrue("AirAlertService.kt зник — сторож більше нічого не стереже", f.isFile)
        return f.readText()
    }

    /** Чиста функція — тому доказ червоного не потребує правки справжнього файла. */
    private fun subscribesBeforeStart(text: String): Boolean {
        val collectAt = text.indexOf("notices.collect")
        val startAt = text.indexOf("watch.start()")
        if (collectAt < 0 || startAt < 0) return false
        return collectAt < startAt
    }

    @Test
    fun `підписка стоїть перед стартом спостерігача`() {
        assertTrue(
            "watch.start() опинився ВИЩЕ за notices.collect. Між ними вікно, у яке " +
                "мовчки провалюється перше сповіщення про тривогу: SharedFlow без повтору " +
                "викидає те, що випромінили без підписників, а результат tryEmit ніхто не " +
                "читає. start() ідемпотентний — переставити його нижче безкоштовно.",
            subscribesBeforeStart(source()),
        )
    }

    /** ДОКАЗ ЧЕРВОНОГО: сторож мусить упасти саме на тому порядку, який ми прибрали. */
    @Test
    fun `сторож ловить зворотний порядок`() {
        val bad = """
            watch.start()
            scope.launch { watch.notices.collect { notify(it) } }
        """.trimIndent()
        assertTrue("зворотний порядок мусить ловитись", !subscribesBeforeStart(bad))

        val good = """
            scope.launch { watch.notices.collect { notify(it) } }
            watch.start()
        """.trimIndent()
        assertTrue("правильний порядок мусить проходити", subscribesBeforeStart(good))
    }
}
