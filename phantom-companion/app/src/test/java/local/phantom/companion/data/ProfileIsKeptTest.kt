package local.phantom.companion.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Профіль мусить лишатись профілем, а не формою, що все забуває (A52, 04.09).
 * З 10.10 профіль один — обличчя на вкладці; другого екрана-форми немає, а
 * кожна правка на місці пише туди, звідки її читають люди й веб.
 */
class ProfileIsKeptTest {

    private val root = File("../").canonicalFile

    private fun read(rel: String): String {
        val f = File(root, rel)
        assertTrue("немає файла $rel", f.isFile)
        return f.readText()
    }

    private val tab get() = read("app/src/main/java/local/phantom/companion/ui/home/ProfileTab.kt")

    @Test
    fun `другого профілю немає`() {
        assertFalse(
            "форма профілю повернулась",
            File(root, "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/profile/ProfileScreen.kt").exists(),
        )
        val routes = read("app/src/main/java/local/phantom/companion/navigation/Routes.kt")
        assertFalse("маршрут другого профілю повернувся", Regex("""Routes\("profile"\)""").containsMatchIn(routes))
        val nav = read("app/src/main/java/local/phantom/companion/navigation/PhantomNavGraph.kt")
        assertFalse("граф знову малює форму профілю", "ProfileScreen(" in nav || "ProfileDestination" in nav)
    }

    @Test
    fun `обличчя читає збережене, а не порожній стан`() {
        val t = tab
        assertTrue("імʼя не з ProfileStore", "container.profileStore.getActive()" in t)
        assertTrue("фото не з кадру профілю", "container.profileHerald" in t && "herald.ownPhotoFile()" in t)
        assertTrue("банер не з диска", "loadOwnBanner(herald)" in t && "herald.ownBannerVideo()" in t)
        assertTrue("старе фото не переноситься", "face.adoptLegacyPhoto(active?.avatarUri)" in t)
    }

    @Test
    fun `кожна правка на місці кудись пишеться`() {
        val t = tab
        assertTrue("імʼя нікуди не пишеться", "face.setName(draft)" in t)
        assertTrue("фото нікуди не пишеться", "face.setPhoto(thumb, photo)" in t)
        assertTrue("банер нікуди не пишеться", "herald.publishBanner(photos, clip?.file)" in t)
        val app = read("app/src/main/java/local/phantom/companion/data/AppContainer.kt")
        val wiring = app.substringAfter("val selfFace").substringBefore("\n    }\n")
        assertTrue("імʼя не доходить до ProfileStore — запрошення його не побачить", "profileStore.update(" in wiring)
        assertTrue("запрошення не оновлюється після нового імені", "refreshInvite()" in wiring)
    }

    @Test
    fun `порожнє імʼя не підмінюється чужим`() {
        val t = tab
        assertFalse("запасним іменем стояло «Kiril»", "\"Kiril\"" in t)
        assertTrue("порожнє імʼя мусить питати, а не підмінятись", "R.string.profile_name_missing" in t)
    }
}
