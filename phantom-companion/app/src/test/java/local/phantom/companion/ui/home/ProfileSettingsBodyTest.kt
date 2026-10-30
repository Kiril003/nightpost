package local.phantom.companion.ui.home

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import local.phantom.companion.R
import local.phantom.companion.core.data.locale.AppLanguage
import local.phantom.companion.core.design.component.NavRow
import local.phantom.companion.core.design.component.RowDivider
import local.phantom.companion.core.design.theme.AmberNight
import local.phantom.companion.core.design.theme.PhantomTheme
import local.phantom.companion.navigation.Routes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import local.phantom.companion.core.design.R as DesignR

/** N1-3: Налаштування — три картки без заголовків, Server замість «Nightpost server», мова без прапора. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "en-w411dp-h1600dp-xhdpi")
class ProfileSettingsBodyTest {

    private var controller: ActivityController<ComponentActivity>? = null
    private var recomposer: Recomposer? = null
    private val clock = BroadcastFrameClock()
    private var frameNanos = 0L
    private val visited = mutableListOf<String>()
    private var versionTaps = 0

    @After fun close() {
        recomposer?.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        controller?.pause()?.stop()?.destroy()
    }

    private fun facts(developer: Boolean = false) = SettingsFacts(
        appearance = "Auto",
        language = "English",
        devices = null,
        server = "Nightpost",
        version = "0.1.0",
        developer = developer,
    )

    private fun open(
        developer: Boolean = false,
        body: (@androidx.compose.runtime.Composable () -> Unit)? = null,
    ): ComponentActivity {
        val c = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller = c
        val ctx = Dispatchers.Main + clock
        val r = Recomposer(ctx)
        recomposer = r
        CoroutineScope(ctx + Job()).launch { r.runRecomposeAndApplyChanges() }
        val points = c.get().getString(R.string.profile_points)
        c.get().setContent(parent = r) {
            PhantomTheme(spec = AmberNight) {
                Column {
                    if (body != null) body() else SettingsBody(
                        facts = facts(developer),
                        go = { visited += it },
                        onVersion = { versionTaps++ },
                        onCalls = { visited += "calls" },
                        onPeople = { visited += "people" },
                        onPages = { visited += "pages" },
                        points = {
                            RowDivider()
                            NavRow(points, DesignR.drawable.lc_star) { visited += "points" }
                        },
                    )
                }
            }
        }
        repeat(30) {
            Snapshot.sendApplyNotifications()
            frameNanos += 50_000_000L
            clock.sendFrame(frameNanos)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
        }
        return c.get()
    }

    private fun roots(v: View): List<ViewRootForTest> = when (v) {
        is ViewRootForTest -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { roots(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun all(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::all)

    private fun screen(a: ComponentActivity) = roots(a.window.decorView).flatMap { all(it.semanticsOwner.unmergedRootSemanticsNode) }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun group(a: ComponentActivity, n: Int) =
        screen(a).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == "settings-group-$n" }

    private fun inGroup(a: ComponentActivity, n: Int, labels: List<String>): List<String> =
        all(group(a, n) ?: error("немає картки $n")).flatMap { it.texts() }.filter { it in labels }

    private fun button(a: ComponentActivity, label: String) =
        screen(a).filter { n -> n.config.getOrNull(SemanticsActions.OnClick) != null && all(n).any { label in it.texts() } }
            .minByOrNull { all(it).size }

    private fun s(a: ComponentActivity, id: Int) = a.getString(id)

    @Test fun `three cards in order with their rows and no caption`() {
        val a = open()
        val g1 = group(a, 1)
        val g2 = group(a, 2)
        val g3 = group(a, 3)
        assertNotNull("картки 1 немає", g1)
        assertNotNull("картки 2 немає", g2)
        assertNotNull("картки 3 немає", g3)
        assertTrue("картки йдуть згори вниз", g1!!.boundsInRoot.top < g2!!.boundsInRoot.top && g2.boundsInRoot.top < g3!!.boundsInRoot.top)
        val lastOfOne = button(a, s(a, R.string.profile_pages))!!
        val firstOfTwo = button(a, s(a, R.string.profile_privacy))!!
        assertTrue("між картками проміжок, а не стик", firstOfTwo.boundsInRoot.top - lastOfOne.boundsInRoot.bottom >= 16f * a.resources.displayMetrics.density)

        val one = listOf(
            R.string.profile_appearance,
            R.string.profile_language,
            R.string.profile_notifications,
            R.string.profile_calls,
            R.string.profile_people,
            R.string.profile_pages,
        ).map { s(a, it) }
        val two = listOf(R.string.profile_privacy, R.string.profile_devices, R.string.profile_offline_maps, R.string.profile_data).map { s(a, it) }
        val three = listOf(
            R.string.profile_server, R.string.profile_points, R.string.profile_help, R.string.help_version,
        ).map { s(a, it) }
        assertEquals(one, inGroup(a, 1, one + two + three))
        assertEquals(two, inGroup(a, 2, one + two + three))
        assertEquals(three, inGroup(a, 3, one + two + three))
        assertFalse("заголовка «Your key» над групою немає", screen(a).any { "Your key" in it.texts() })
    }

    @Test fun `the developer row sits right after Help only when unlocked`() {
        val a = open(developer = true)
        val labels = listOf(R.string.profile_help, R.string.profile_developer, R.string.help_version).map { s(a, it) }
        assertEquals(labels, inGroup(a, 3, labels))
        close()
        val b = open(developer = false)
        assertNull(button(b, s(b, R.string.profile_developer)))
    }

    @Test fun `every row opens its own place`() {
        val a = open()
        val expected = listOf(
            R.string.profile_appearance to ProfileSection.APPEARANCE.route,
            R.string.profile_language to ProfileSection.LANGUAGE.route,
            R.string.profile_notifications to ProfileSection.NOTIFICATIONS.route,
            R.string.profile_privacy to ProfileSection.PRIVACY.route,
            R.string.profile_devices to ProfileSection.DEVICES.route,
            R.string.profile_offline_maps to Routes.SettingsMap.path,
            R.string.profile_data to ProfileSection.DATA.route,
            R.string.profile_server to ProfileSection.PHANTOM.route,
            R.string.profile_help to ProfileSection.HELP.route,
        )
        expected.forEach { (label, _) ->
            (button(a, s(a, label)) ?: error("немає рядка «${s(a, label)}»")).config[SemanticsActions.OnClick].action!!.invoke()
        }
        assertEquals(expected.map { it.second }, visited)
    }

    /** Аудит 18.10 №15: налаштування дзвінків жили лише в ⋮ однієї розмови; тепер — рядок «Дзвінки» для всіх. */
    @Test fun `calls row sits after notifications and opens app-wide call settings`() {
        val a = open()
        val labels = listOf(s(a, R.string.profile_notifications), s(a, R.string.profile_calls))
        assertEquals("«Дзвінки» в першій картці одразу після сповіщень", labels, inGroup(a, 1, labels))
        assertTrue("рядок не каже «для всіх дзвінків»", screen(a).flatMap { it.texts() }.contains(s(a, R.string.profile_calls_all)))
        button(a, s(a, R.string.profile_calls))!!.config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(listOf("calls"), visited)
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "app/src/main/java").isDirectory }
        val section = File(root, "app/src/main/java/local/phantom/companion/ui/home/ProfileSettings.kt").readText()
            .substringAfter("internal fun SettingsSection(").substringBefore("internal class SettingsFacts")
        val wire = section.substringAfter("onCalls = {").substringBefore("},")
        assertTrue("рядок не відчиняє веб-шторку дзвінків", "WebNav.request(WebNav.Door.OpenTab(CALL_SETTINGS_TAB))" in wire)
        assertTrue("не повертає на вкладку розмов", "homeTabRequest.value = HomeTab.CHATS" in wire)
        assertTrue("лишає людину в Налаштуваннях над розмовою", "back()" in wire)
        assertEquals("ключ домовлено з веб-половиною (L5)", "call-settings", CALL_SETTINGS_TAB)
    }

    @Test fun `profiles live on You, settings has no second door`() {
        val a = open()
        assertTrue("«Профілі» досі в Налаштуваннях", button(a, s(a, R.string.profile_profiles)) == null)
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "app/src/main/java").isDirectory }
        val tab = File(root, "app/src/main/java/local/phantom/companion/ui/home/ProfileTab.kt").readText()
        val wire = tab.substringAfter("onProfiles = {").substringBefore("},")
        assertTrue("«Я» не відчиняє керування профілями", "WebNav.request(WebNav.Door.OpenProfiles)" in wire && "HomeTab.CHATS" in wire)
    }

    /** R-GAP #5: веб-«Мої люди» і «Мої сторінки» відчинялись лише з настільного ⋮. */
    @Test fun `people and pages rows follow calls and open their web places`() {
        val a = open()
        val labels = listOf(R.string.profile_calls, R.string.profile_people, R.string.profile_pages).map { s(a, it) }
        assertEquals(labels, inGroup(a, 1, labels))
        button(a, s(a, R.string.profile_people))!!.config[SemanticsActions.OnClick].action!!.invoke()
        button(a, s(a, R.string.profile_pages))!!.config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(listOf("people", "pages"), visited)
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "app/src/main/java").isDirectory }
        val section = File(root, "app/src/main/java/local/phantom/companion/ui/home/ProfileSettings.kt").readText()
            .substringAfter("internal fun SettingsSection(").substringBefore("internal class SettingsFacts")
        val people = section.substringAfter("onPeople = {").substringBefore("},")
        val pages = section.substringAfter("onPages = {").substringBefore("},")
        assertTrue("«Мої люди» не відчиняє веб-людей", "WebNav.openPlace(WebNav.PLACE_PEOPLE)" in people && "back()" in people)
        assertTrue("«Мої сторінки» не відчиняє Builder", "WebNav.openPlace(WebNav.PLACE_BUILDER)" in pages && "back()" in pages)
    }

    @Test fun `version taps the developer door and the key rows are plain facts`() {
        val a = open()
        button(a, s(a, R.string.help_version))!!.config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(1, versionTaps)
        val words = screen(a).flatMap { it.texts() }
        assertTrue("версія на екрані: $words", "0.1.0" in words)
        val v = plainVersion()
        assertFalse("версія має суфікс: $v", '-' in v || '+' in v)
        assertEquals("0.1.0", plainVersion("0.1.0-tier1+5d9c91fa"))
        assertEquals("0.1.0", plainVersion("0.1.0-tier1"))
    }

    @Test fun `Settings carry no key fingerprint or key date, Devices › This phone does`() {
        val a = open()
        val words = screen(a).flatMap { it.texts() }
        assertFalse("відбиток у Налаштуваннях: $words", s(a, R.string.profile_key_short) in words)
        assertFalse("дата ключа в Налаштуваннях: $words", s(a, R.string.profile_key_created) in words)
        close()
        val b = open(body = { DeviceKeyRows(fingerprint = "ab12 cd34", created = "12 Oct 2026") })
        val there = screen(b).flatMap { it.texts() }
        assertTrue("Цей телефон: $there", listOf(s(b, R.string.devices_this_phone), "ab12 cd34", "12 Oct 2026").all { it in there })
        assertNull(button(b, s(b, R.string.profile_key_short)))
    }

    @Test fun `the assistant row says Assistant and Nightpost, never Server or the doubled brand`() {
        val a = open()
        val row = button(a, s(a, R.string.profile_server))
        assertNotNull("рядка Помічник немає", row)
        val words = all(row!!).flatMap { it.texts() }
        assertEquals(listOf("Assistant", "Nightpost"), words)
        assertFalse(screen(a).flatMap { it.texts() }.any { it.equals("Server", ignoreCase = true) || it.contains("Nightpost server", ignoreCase = true) })
        assertEquals("Nightpost", s(a, mindValue(pc = false, ownKey = false, server = local.phantom.companion.core.ai.route.NightpostBrainRoute.Standing.UP)!!))
    }

    @Test fun `battery grant row on notifications page shows label and state without explanation sentence`() {
        val a = open(body = {
            local.phantom.companion.ui.settings.BatteryGrantEntry(
                local.phantom.companion.ui.settings.PermissionHealth(background = false),
            )
        })
        val words = screen(a).flatMap { it.texts() }
        assertTrue("немає підпису рядка батареї: $words", s(a, R.string.look_battery_title) in words)
        assertTrue("немає дії Надати: $words", s(a, R.string.look_grant) in words)
        assertFalse("пояснювальне речення досі на екрані: $words", s(a, R.string.look_battery_body) in words)
    }

    @Test fun `the language value has no flag`() {
        for (l in AppLanguage.entries) {
            val v = languageValue(l)
            assertEquals(l.nativeName, v)
            var i = 0
            while (i < v.length) {
                val cp = v.codePointAt(i)
                assertFalse("«$v» несе прапор", cp in 0x1F1E6..0x1F1FF)
                i += Character.charCount(cp)
            }
        }
        val a = open()
        assertTrue(screen(a).flatMap { it.texts() }.contains("English"))
    }

    @Test fun `the assistant strings exist in every language without Server or brain icon`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "app/src/main/java").isDirectory }
        val expectedLabels = mapOf(
            "values" to "Assistant",
            "values-uk" to "Помічник",
            "values-cs" to "Asistent",
        )
        for ((dir, expectedLabel) in expectedLabels) {
            val xml = File(root, "app/src/main/res/$dir/strings_profile_face.xml").readText()
            fun value(name: String) = Regex("""<string name="$name">([^<]*)</string>""").find(xml)?.groupValues?.get(1)
            assertEquals("$dir: значення рядка Помічник", "Nightpost", value("profile_value_server"))
            assertEquals("$dir: підпис рядка Помічник", expectedLabel, value("profile_server"))
        }
        val settings = File(root, "app/src/main/java/local/phantom/companion/ui/home/ProfileSettings.kt").readText()
        assertTrue("екран не кличе SettingsBody", "SettingsBody(" in settings.substringAfter("internal fun SettingsSection("))
        assertFalse("рядок Помічник досі малює мозок", "lc_brain" in settings)
        assertTrue("версія не очищена через plainVersion", "version = plainVersion()" in settings)
        assertTrue("мова без прапора досі з прапором", "it.flag" !in settings && "language.flag" !in settings)
        assertTrue("мова не через languageValue", "languageValue(AppLanguage.current(context))" in settings)
    }
}
