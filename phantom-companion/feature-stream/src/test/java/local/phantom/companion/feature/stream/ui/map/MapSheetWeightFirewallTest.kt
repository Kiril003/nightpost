package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Вага аркушів мапи — ратчет, не побажання.
 *
 * Три хвороби, які власник побачив за хвилину на склі і які повертаються самі
 * собою, бо кожна з них — «просто ще один рядок»:
 *
 *  1. **Медальйон.** Залитий диск 32-40dp навколо 16-22dp значка. Коли він
 *     стоїть у КОЖНОМУ рядку секції, він не розрізняє нічого — лише додає
 *     маси зліва. Вісім однакових годинників у «НЕЩОДАВНЄ» були саме цим.
 *  2. **Вітринний заголовок.** `PhantomDisplay` 26sp названий назвою місця:
 *     при шрифті 1.15 довга українська назва ламалась на два рядки по 32sp і
 *     пік аркуша ставав переважно власним заголовком.
 *  3. **Емодзі замість іконки.** 📍 🔎 ⭐ несуть власний колір, власний кегль
 *     і власний стиль посеред мальованих однотонних значків, а TalkBack читає
 *     їх англійськими назвами.
 *
 * ЗАКОН ВОРІТ (спільний із рештою firewall-тестів тут): скан читає чужі
 * модулі як звичайні файли, тож Gradle не бачить залежності й уважає задачу
 * актуальною. Ганяти на кожних воротах хвилі з `--rerun`.
 */
class MapSheetWeightFirewallTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private fun source(path: String): File {
        val file = File(repoRoot(), path)
        assertTrue("$path не знайдено — перевірка вже нічого не боронить", file.isFile)
        return file
    }

    /** Рядки, що справді щось малюють: коментар і імпорт не малюють нічого. */
    private fun drawnLines(path: String): List<String> =
        source(path).readLines().filterNot {
            val t = it.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("import ")
        }

    private val searchSheet =
        "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/MapSearchSheet.kt"

    /**
     * Аркуш пошуку живе в кількох файлах відтоді, як став шторкою: оболонка
     * зі щаблями, перший екран, набране, спільні рядки. Скани нижче читають
     * СІМʼЮ, інакше кожен винос у сусідній файл тихо звільняв би від
     * перевірки — а це рівно той спосіб, у який ратчет перестає боронити.
     */
    private val searchFamily = listOf(
        searchSheet,
        "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/MapSearchSheetShell.kt",
        "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/MapSearchFirstScreen.kt",
        "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/MapSearchResults.kt",
        "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/MapSearchRows.kt",
    )

    private val sheetHost =
        "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/SheetHost.kt"
    private val dossierSheet =
        "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/dossier/PlaceDossierSheet.kt"
    private val textRoles =
        "core-design/src/main/java/local/phantom/companion/core/design/theme/PhantomTextRoles.kt"

    private val drawSheets =
        "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/MapDrawSheets.kt"

    private val sheets = searchFamily + listOf(sheetHost, dossierSheet, drawSheets)

    @Test
    fun `sheet titles are Title, never the 26sp Display`() {
        val offenders = sheets.filter { path ->
            drawnLines(path).any { it.contains("PhantomDisplay(") }
        }

        assertEquals(
            "заголовок аркуша знову набрано вітринним 26sp: " + offenders.joinToString(", ") +
                "\n\nНазва місця читається з руки один раз — їй досить PhantomTitle 20sp/26. " +
                "PhantomDisplay лишається тим, чим є: число або код, який звіряють здалеку.",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `PhantomTitle keeps its 20sp over 26sp metrics`() {
        val body = source(textRoles).readText()
        val decl = body.substringAfter("fun PhantomTitle(", "")
        assertTrue("роль PhantomTitle зникла з PhantomTextRoles.kt", decl.isNotEmpty())

        val metrics = decl.substringBefore("\n}")
        assertTrue(
            "PhantomTitle більше не 20sp — а на цей кегль спираються чотири аркуші мапи",
            metrics.contains("scaled(20.sp)"),
        )
        assertTrue(
            "PhantomTitle втратив lineHeight 26sp: двоярусна назва знову злипнеться",
            metrics.contains("scaled(26.sp)"),
        )
    }

    @Test
    fun `no emoji stands in for a drawn icon in the map sheets`() {
        val emoji = listOf("📍", "🔎", "⭐", "🚓", "🚧", "💥")
        val offenders = sheets.flatMap { path ->
            drawnLines(path).filter { line -> emoji.any { line.contains(it) } }
                .map { "$path: ${it.trim()}" }
        }

        assertEquals(
            "емодзі знову стоїть замість мальованого значка:\n" + offenders.joinToString("\n") +
                "\n\nВласний колір, власний кегль, англійська назва в TalkBack.",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the point sheet is a list, not a fence`() {
        // Виклики, не оголошення: `private fun PointDivider()` мусить лишитись.
        val calls = drawnLines(sheetHost).count { it.trim() == "PointDivider()" }

        assertTrue(
            "аркуш точки знову розгороджено дайверами ($calls шт.). " +
                "Рядок 48dp із постійною іконковою колонкою розділяє себе сам; " +
                "вісім ліній робили з контекстного меню паркан на повний екран. " +
                "Дозволена рівно одна межа — над дрібними діями.",
            calls <= 1,
        )
    }

    // ── Шкіра v1.2 на всіх аркушах, що піднімаються з мапи ────────────────
    //
    // 03.10. Власник: «найгірше — де лишилось СТАРЕ: такий контраст між старим
    // мотлохом і новим». П'ятнадцять ModalBottomSheet у SheetHost кожен сам
    // собі вирішував вигляд зі старої теми; хаб малював чорну плиту #090D16
    // усередині світлого аркуша; п'ять таблиць «вид транспорту → колір»
    // сперечались між собою. Медальйонна дія й пігулка стану досьє
    // (DossierIconAction / DossierStatusPill) були мертві — їх видалено, а
    // старий пін на них ставав порожнім; на його місці — цей сторож.

    private val mapDir = "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map"

    /** Файли аркушів мапи v1.2: каркас, вміст, смуга маршруту, досьє, хаб. */
    private val v12Sheets = listOf(
        "SheetHost.kt",
        "MapSheetScaffold.kt",
        "MapSheetParts.kt",
        "MapDrawSheets.kt",
        "MapAnswerSheet.kt",
        "MapCommandSheet.kt",
        "dossier/PlaceDossierSheet.kt",
        "dossier/DossierInside.kt",
        "dossier/DossierObjectCards.kt",
        "dossier/DossierParts.kt",
        "dossier/DossierTransitCard.kt",
        "dossier/DepartureBoardSection.kt",
        "here/HereNowBlock.kt",
        "transit/TransitHubSheet.kt",
        "transit/TransitHubTabs.kt",
        "transit/TransitVehicleMarkerOverlay.kt",
        "transit/TransitModeTint.kt",
    ).map { "$mapDir/$it" }

    /** Єдиний файл аркушів, якому дозволено писати колір числом. */
    private val modeTint = "$mapDir/transit/TransitModeTint.kt"

    /** Єдиний файл, що відкриває вікно аркуша. */
    private val scaffold = "$mapDir/MapSheetScaffold.kt"

    @Test
    fun `map sheets speak v1_2 tokens, never the old theme`() {
        // Старі ролі тексту беруть колір зі старої теми й старі кеглі (підпис
        // 11sp проти 12sp v1.2); LocalStateAccent — колір стану, не дії;
        // Icons.* — стокові значки Material посеред лінійних lc_* і гліфів.
        val forbidden = listOf(
            "LocalPhantomTheme",
            "LocalStateAccent",
            "PhantomKicker",
            "PhantomDataText",
            "PhantomCaption",
            "PhantomRadii",
            "Icons.",
            "Color.White",
        )
        val offenders = v12Sheets.flatMap { path ->
            drawnLines(path)
                .filter { line -> forbidden.any { line.contains(it) } }
                .map { "${path.substringAfterLast('/')}: ${it.trim()}" }
        }

        assertEquals(
            "аркуш мапи знову говорить старою темою:\n" + offenders.joinToString("\n") +
                "\n\nКольори — LocalPhantomColors, текст — PhantomText.* (SheetText/SheetBody/…), " +
                "значки — lc_* або PhantomGlyph.",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `colour by number lives in one table`() {
        val offenders = (v12Sheets - modeTint).flatMap { path ->
            drawnLines(path)
                .filter { it.contains("Color(0x") }
                .map { "${path.substringAfterLast('/')}: ${it.trim()}" }
        }

        assertEquals(
            "колір числом поза TransitModeTint:\n" + offenders.joinToString("\n") +
                "\n\nТон теми — токен; тон виду транспорту — TransitModeTint; колір " +
                "лінії — з даних (colourArgb / MetroLine.colour).",
            emptyList<String>(),
            offenders,
        )
        assertTrue(
            "TransitModeTint більше не тримає таблицю — запасний тон виду зник",
            drawnLines(modeTint).any { it.contains("Color(0x") },
        )
    }

    @Test
    fun `every map sheet opens through the one scaffold`() {
        // З дужкою: `rememberModalBottomSheetState` — стан, не вікно, і SheetHost
        // свідомо його тримає (BottomChromeInsetFirewallTest шукає це слово).
        val call = Regex("""(?<![A-Za-z])ModalBottomSheet\(""")
        val offenders = (v12Sheets - scaffold).flatMap { path ->
            drawnLines(path)
                .filter { call.containsMatchIn(it) }
                .map { "${path.substringAfterLast('/')}: ${it.trim()}" }
        }
        assertEquals(
            "аркуш мапи знову відкриває власний ModalBottomSheet:\n" + offenders.joinToString("\n") +
                "\n\nКаркас один — MapSheet: raised, scrim, statusBarsPadding, SheetWindowBars, " +
                "ручка lineStrong. Свій вигляд у кожного — це рівно те, що власник назвав мотлохом.",
            emptyList<String>(),
            offenders,
        )

        val body = drawnLines(scaffold).joinToString("\n")
        listOf(
            "SheetWindowBars()" to "смуги вікна аркуша знову не фарбуються — біла смуга навігації (A52, 02.10)",
            "statusBarsPadding()" to "ручка знову лягає під рядок стану",
            "containerColor = c.raised" to "тло аркуша — не raised",
            "c.lineStrong" to "ручка не lineStrong — уночі її не видно (1.2:1)",
        ).forEach { (needle, why) -> assertTrue(why, body.contains(needle)) }

        val hosted = Regex("""(?<![A-Za-z])MapSheet\(""").findAll(drawnLines(sheetHost).joinToString("\n")).count()
        assertTrue("SheetHost більше не відкриває аркуші через MapSheet ($hosted)", hosted >= 15)
    }

    @Test
    fun `the search list ends on the shell's own reserve, without a fade`() {
        val body = searchFamily.flatMap { drawnLines(it) }.joinToString("\n")

        // Еволюція 2026-08-23 (BottomChromeInset-файрвол): резерв низу несе
        // union bottomChromeInsets() ОДИН раз на контейнері (per-axis MAX
        // рейки та IME); LocalBottomChromeInset у цій родині — дабл-каунт і
        // заборонений. Стиль D (18.10): розчину-градієнта більше немає —
        // список закінчується на резерві, як у платформних списках.
        assertTrue(
            "резерв низу зник: контейнер більше не бере bottomChromeInsets()",
            body.contains("bottomChromeInsets()"),
        )
        assertTrue(
            "повернувся LocalBottomChromeInset — це дабл-каунт до union",
            !body.contains("LocalBottomChromeInset"),
        )
        assertTrue(
            "повернувся градієнт унизу списку — стиль D без градієнтів",
            !body.contains("Brush."),
        )
        assertTrue(
            "повернувся fadingEdges на весь LazyColumn: це offscreen-шар, тобто " +
                "ціна кадру на кожен свайп (його власний kdoc це й каже)",
            !body.contains("fadingEdges"),
        )
    }

    @Test
    fun `recent queries are capped for display, not for storage`() {
        val body = searchFamily.flatMap { drawnLines(it) }.joinToString("\n")

        assertTrue(
            "кап показу нещодавніх зник — вісім рядків знову зʼїдають екран",
            body.contains("recent.take(RECENT_SHOWN)"),
        )
        assertTrue(
            "onClearRecent більше не викликається: «очистити» стало б кнопкою, " +
                "яка нічого не робить",
            body.contains("onClick = onClearRecent"),
        )
    }

    @Test
    fun `the dossier never invents a data age`() {
        val body = drawnLines(dossierSheet).joinToString("\n")

        assertTrue(
            "рядок джерела зник із «Деталей» — оператор знову не знає, чи " +
                "«Відчинено» це жива відповідь чи памʼять пристрою",
            body.contains("sourceLine(dossier.identitySource)"),
        )
        // Невідоме походження мусить давати null, а не текст: рівно одна гілка
        // `null -> null` і жодного `else ->`, який підставив би заглушку.
        val branch = body.substringAfter("fun sourceLine(", "").substringBefore("\n}")
        assertTrue("sourceLine зникла", branch.isNotEmpty())
        assertTrue(
            "sourceLine дістала гілку-заглушку: походження невідоме — рядка нема",
            branch.contains("null -> null") && !branch.contains("else ->"),
        )
    }
}
