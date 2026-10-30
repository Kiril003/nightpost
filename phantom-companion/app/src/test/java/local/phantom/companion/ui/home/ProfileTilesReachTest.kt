package local.phantom.companion.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Дві дії шапки «Я»: код і налаштування. Саме обличчя правиться дотиком
 * по ньому — фото, обкладинка, імʼя — а не плиткою, що веде на форму.
 */
class ProfileTilesReachTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }

    private fun src(path: String) = File(root, "app/src/main/java/local/phantom/companion/$path").readText()

    @Test
    fun `дії обличчя ведуть у свої розділи`() {
        assertEquals(listOf("CODE", "SETTINGS"), ProfileAction.entries.map { it.name })
        assertNull("код відкривається на місці, не маршрутом", ProfileAction.CODE.route)
        assertEquals(ProfileSection.SETTINGS.route, ProfileAction.SETTINGS.route)
    }

    @Test
    fun `кнопки шапки мають підписи в усіх мовах`() {
        val tab = src("ui/home/ProfileTab.kt")
        val enumBody = tab.substringAfter("enum class ProfileAction").substringBefore(";")
        val names = Regex("""R\.string\.(\w+)""").findAll(enumBody).map { it.groupValues[1] }.toList()
        assertEquals(2, names.size)
        for (dir in listOf("values-uk", "values", "values-cs")) {
            val xml = File(root, "app/src/main/res/$dir").listFiles()!!.joinToString("") { it.readText() }
            names.forEach { n -> assertTrue("$dir/$n немає", Regex("""<string name="$n">[^<]+</string>""").containsMatchIn(xml)) }
        }
    }

    @Test
    fun `профіль малює кожну дію і правиться на місці`() {
        val tab = src("ui/home/ProfileTab.kt")
        assertTrue("дії не намальовано", "ProfileAction.entries.forEach" in tab)
        assertTrue("дія не веде своїм маршрутом", "action.route?.let { go(it) }" in tab)
        assertTrue("код не відкривається", "ProfileAction.CODE -> showCode = true" in tab)
        assertTrue("дотик по імені не відкриває правку", "{ naming = true }" in tab)
        assertTrue("дотик по фото не відкриває галерею", "photoPicker.launch(images)" in tab)
        assertTrue("дотик по банеру не відкриває редактор", "onOpen = onBanner" in tab && "EditRow.BANNER -> openBanner()" in tab)
        assertTrue("кола не передано в картку кіл", "ProfileFace.circles(circlesMap)" in tab && "CirclesCard(" in tab)
        assertTrue("моментів не намальовано", "Highlights(" in tab && "ProfileFace.highlights(" in tab)
        assertTrue("залишені моменти не доходять до профілю", "container.moments.highlights(meId)" in tab)
        assertTrue("мініатюра не з оригіналу", "moments.image(m)" in tab && "ProfileFace.decodeAt(" in tab)
        assertTrue("одна головна дія — «Редагувати»", "R.string.profile_edit, DesignR.drawable.lc_pencil, primary = true, tag = \"me-edit\", onClick = onEdit" in tab)
        assertTrue("банер додається з «Редагувати»", "EditRow.BANNER -> openBanner()" in tab && "tag = \"me-banner-add\"" !in tab)
    }

    @Test
    fun `плитки теми пишуть тему тим самим обробником`() {
        val sections = src("ui/home/ProfileSections.kt")
        val appearance = sections.substringAfter("private fun AppearanceSection").substringBefore("RowGroup(caption = stringResource(R.string.appearance_text))")
        assertTrue("тему більше не обирають плитками", "ThemeTiles(" in appearance)
        assertTrue("плитки пишуть тему не туди", "onSelect = vm::setThemeMode" in appearance)
        listOf("ThemeMode.Living", "ThemeMode.Light", "ThemeMode.Dark").forEach {
            assertTrue("$it зник із вибору", it in appearance)
        }
        val tiles = src("ui/home/ThemePreviewTiles.kt")
        assertTrue("плитка не кличе onSelect свого режиму", "{ onSelect(mode) }" in tiles)
    }
}
