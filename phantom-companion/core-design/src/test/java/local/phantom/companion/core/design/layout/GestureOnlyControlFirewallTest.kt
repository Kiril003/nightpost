package local.phantom.companion.core.design.layout

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Кнопка, яку бачить лише палець.
 *
 * `Modifier.pointerInput { awaitFirstDown() … }` і `detectTapGestures` не
 * публікують у семантиці НІЧОГО. Вузол виходить у дерево доступності як
 * `ImageView` з `clickable=false` і без натискного предка: дотиком він
 * працює, а штатним жестом читача з екрана — ні. Це не «гірше озвучено», це
 * недосяжно.
 *
 * Знайдено живими тестерами на Galaxy A52 19.09, збірка 14: у нижній рейці
 * `Написати`, `Мапа` і `Люди` мали `clickable=true` (вони на `.clickable`), а
 * **центральний жовтий орб «Голос» і `Скриня`** — ні. Орб — головна кнопка
 * застосунку. Обидва сиділи на власних детекторах жестів, бо їм потрібен
 * розбір «тап проти утримання» тонший за системний long-press.
 *
 * Ліки — не викинути жест, а ОГОЛОСИТИ дію поруч із ним:
 * `semantics { role = Role.Button; onClick { … } }`. Жест лишається для
 * пальця, семантика дає читачеві з екрана те, що він уміє виконати.
 *
 * ## Що цей сторож уміє і чого не вміє
 *
 * Одиниця огляду — БЛОК `@Composable`, а не рядок і не назва: перейменування
 * композабла, іконки чи рядка його не обдурить, бо шукаються самі оголошення
 * (`awaitFirstDown`/`detectTapGestures` проти `semantics{…onClick}` і
 * `.clickable`/`.combinedClickable`/`.selectable`/`.toggleable`).
 *
 * ## Чого цей сторож НЕ доводить — назване вголос
 *
 * 1. Він не запускає TalkBack. Доведено, що ДІЮ оголошено поруч із жестом;
 *    не доведено, що читач з екрана справді її виконує — це лише пристрій.
 * 2. Блок, де жест і семантика живуть у РІЗНИХ композабах одного файла, тут
 *    не розрізняється. Таких у дереві сьогодні немає; якщо зʼявиться —
 *    почервоніє, і це доведеться сказати вголос, а не тихо внести в [ground].
 * 3. Він не судить, чи оголошена дія ПРАВИЛЬНА. Орб має два жести (тап —
 *    живий діалог, утримання — PTT), і семантика несе лише тап: утримання
 *    читач з екрана відтворити не може, і вдавати протилежне тут нічим.
 *
 * GATE LAW (спільний з рештою firewall-ів): читає чужі модулі як сирі файли,
 * повз up-to-date Gradle. Прогін лише з `--rerun`/`--rerun-tasks`.
 */
class GestureOnlyControlFirewallTest {

    /**
     * Ґрунт станом на 20.09, ПІСЛЯ зняття орба й Скрині.
     *
     * Список, а не число: імена в повідомленні кажуть, що саме зрушило, і
     * заміна одного порушника іншим не проходить тихо під рівним лічильником.
     *
     * 02.10 четверо пішли разом зі стрічкою й Континуумом; 04.10 —
     * `AttachmentWidget.kt::ZoomableImageDialog` разом із картками стрічки.
     * Цей НЕ перевірений — він успадкований: `VaultOpenFace` виглядає як
     * справжня кнопка й чекає на свою хвилю. Стеля фіксує ґрунт, а не
     * стверджує, що він здоровий.
     */
    private val ground = listOf(
        "VaultScreen.kt::VaultOpenFace",
    )

    private val uiRoots = listOf(
        "app/src/main",
        "feature-stream/src/main",
        "feature-godmode/src/main",
        "feature-ambient/src/main",
        "feature-onboarding/src/main",
        "feature-car/src/main",
        "feature-precision/src/main",
        "core-design/src/main",
    )

    /** Роботи цього сторожа немає без цих двох: вони несуть рейку і чат. */
    private val rootsThatMustNotEmpty = setOf("app/src/main", "feature-stream/src/main")

    private val gesture = Regex("""detectTapGestures\(|awaitFirstDown\(""")

    /** `semantics { … onClick … }` — оголошена дія, яку читач уміє виконати. */
    private val declaredAction = Regex("""semantics\s*(\([^)]*\))?\s*\{[^}]*onClick""", RegexOption.DOT_MATCHES_ALL)

    /** Модифікатори, що самі приносять і роль, і дію. */
    private val builtInAction = Regex("""\.clickable\(|\.combinedClickable\(|\.selectable\(|\.toggleable\(""")

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь репозиторію з feature-stream/ не знайдено") as Nothing
    }

    private fun composablesIn(relPath: String): List<File> {
        val dir = File(repoRoot(), relPath)
        // Та сама передумова, що й у сусідніх firewall-ів: зниклий корінь
        // дає порожній обхід, нуль порушників і ЗЕЛЕНЕ ні про що.
        if (!dir.isDirectory) fail("сторож дивиться в порожнє: не існує $relPath")
        val kt = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        if (kt.isEmpty()) fail("тека є, жодного .kt: $relPath")
        return kt.filter { it.readText().contains("@Composable") }
    }

    private fun offenders(): List<String> = uiRoots.flatMap { composablesIn(it) }.flatMap { f ->
        f.readText().split(Regex("""(?=@Composable)""")).mapNotNull { block ->
            if (!gesture.containsMatchIn(block)) return@mapNotNull null
            if (declaredAction.containsMatchIn(block) || builtInAction.containsMatchIn(block)) {
                return@mapNotNull null
            }
            val name = Regex("""fun\s+(\w+)""").find(block)?.groupValues?.get(1) ?: "?"
            "${f.name}::$name"
        }
    }.sorted()

    @Test
    fun `the sweep never inspects nothing`() {
        for (root in rootsThatMustNotEmpty) {
            assertTrue(
                "корінь '$root' не дав жодного @Composable — шлях застарів, і " +
                    "кожна інша перевірка тут зеленіє ні про що.",
                composablesIn(root).isNotEmpty(),
            )
        }
    }

    @Test
    fun `controls driven only by a raw gesture never grow`() {
        assertEquals(
            "керування, що живе лише в жесті і не оголошує дії в семантиці.\n" +
                "  ↑ зросло: цей вузол вийде в дерево доступності як ImageView з " +
                "clickable=false. Пальцем він працює, читачем з екрана — ні. " +
                "Додай `semantics { role = Role.Button; onClick { … } }` поруч із " +
                "`pointerInput`, а не замість нього.\n" +
                "  ↓ впало: добре. Перепиши `ground` під новий список.",
            ground,
            offenders(),
        )
    }
}
