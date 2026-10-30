package local.phantom.companion

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Looper
import android.view.KeyEvent
import androidx.activity.OnBackPressedCallback
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.net.peer.PeerCall
import local.phantom.companion.core.net.peer.PeerCallLine
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerInvite
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.ui.home.HomeTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowKeyguardManager
import org.robolectric.shadows.ShadowToast
import org.w3c.dom.Element

/**
 * Оболонка: чи доходить до застосунку те, що приходить ззовні, — посилання
 * сайту, дзвінок на замкненому екрані, дозволи в злитому маніфесті.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ShellReachTest {

    private val app get() = RuntimeEnvironment.getApplication()

    private val invite = PeerInvite.Invite(
        peerId = "a1b2c3d4e5f6a7b8",
        name = "Марта",
        pubKey = "o3NKOiXtZDvHEzBuaevdkgoKEPmHCAfUGKfhlxmjevg",
        dhPubKey = "JHxQvxJ0EJ0kOxCkYq2mR5vP1kQ0oZ8mVhq7bT3sXWA",
        host = "",
        port = 0,
    )

    private fun view(url: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)

    @Test
    fun `посилання сайту на запрошення відчиняє PHANTOM, а не браузер`() {
        val pm = app.packageManager
        val found = pm.queryIntentActivities(view(PeerInvite.webLink(invite)), 0)
            .map { it.activityInfo.name }
        assertEquals(listOf(MainActivity::class.java.name), found)
        assertEquals(listOf(MainActivity::class.java.name), pm.queryIntentActivities(view("https://phantom-os.dev/i"), 0).map { it.activityInfo.name })
        listOf("https://phantom-os.dev/", "https://phantom-os.dev/download", "https://phantom-os.dev/index.html", "https://evil.example/i/").forEach {
            assertTrue(it, pm.queryIntentActivities(view(it), 0).none { r -> r.activityInfo.packageName == app.packageName })
        }
    }

    @Test
    fun `https-намір доходить до прийняття запрошення`() {
        val intent = view(PeerInvite.webLink(invite))
        val raw = invitePayload(intent)
        assertNotNull(raw)
        assertTrue(isTappedInvite(intent))
        val decoded = PeerInvite.decode(PeerInvite.findInText(raw) ?: raw)
        assertEquals(PeerInvite.Decoded.Ph2(invite), decoded)

        val empty = view("https://phantom-os.dev/i/")
        assertTrue("порожнє посилання сайту мовчки не губиться", isTappedInvite(empty))
        assertEquals(PeerInvite.Decoded.NotInvite, PeerInvite.decode(invitePayload(empty)))

        assertEquals(null, invitePayload(view("https://evil.example/i/#${PeerInvite.encode(invite)}")))
        assertEquals(PeerInvite.Decoded.Ph2(invite), PeerInvite.decode(invitePayload(view(PeerInvite.link(invite)))))
    }

    @Test
    fun `обірване посилання сайту доходить до MainActivity і не губиться мовчки`() {
        Robolectric.buildActivity(MainActivity::class.java, view("https://phantom-os.dev/i/")).create()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(app.getString(R.string.invite_link_bad), ShadowToast.getTextOfLatestToast())
    }

    private fun dismissPending(): Boolean =
        ShadowKeyguardManager::class.java.getDeclaredField("callback").apply { isAccessible = true }.get(null) != null

    @Test
    fun `дзвінок над замком — керування видно, піти з нього можна лише через PIN, і відмова не відмикає`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val keyguard = shadowOf(app.getSystemService(KeyguardManager::class.java))
        var unlocked = 0
        val lock = CallOverLock(activity) { unlocked++ }
        keyguard.setKeyguardLocked(true)

        assertTrue(lock.open(PeerCallLine.Line.Ringing("c1", "p1", 1L)))
        assertTrue(shadowOf(activity).showWhenLocked)
        assertTrue(shadowOf(activity).turnScreenOn)
        assertTrue(lock.follow(PeerCallLine.Line.Talking("c1", "p1", 2L)))
        assertFalse("узяв слухавку — PIN не закриває «покласти»", dismissPending())

        assertTrue(lock.guarding)
        assertTrue(lock.leave())
        assertTrue(dismissPending())
        keyguard.setKeyguardLocked(true)
        assertFalse(dismissPending())
        assertTrue("скасований PIN питається знову", lock.leave())
        assertTrue(dismissPending())
        keyguard.setKeyguardLocked(false)
        assertEquals(1, unlocked)
        assertFalse("замок знято — далі вільно", lock.leave())

        assertFalse(lock.follow(PeerCallLine.Line.Over("c1", "p1", "hangup")))
        assertFalse(shadowOf(activity).showWhenLocked)
        assertFalse(shadowOf(activity).turnScreenOn)
    }

    @Test
    fun `розмова, що вже йде, теж стоїть над замком — наприклад, після перестворення активності`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val lock = CallOverLock(activity)
        assertTrue(lock.open(PeerCallLine.Line.Talking("c1", "p1", 2L)))
        assertTrue(shadowOf(activity).showWhenLocked)
    }

    @Test
    fun `стара плашка дзвінка без живої лінії замка не обходить`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val lock = CallOverLock(activity)
        assertFalse(lock.open(PeerCallLine.Line.Idle))
        assertFalse(lock.open(PeerCallLine.Line.Over("c1", "p1", null)))
        assertFalse(lock.open(PeerCallLine.Line.Dialing("c1", "p1", 1L)))
        assertFalse(shadowOf(activity).showWhenLocked)
        assertFalse(shadowOf(activity).turnScreenOn)
    }

    private fun callFrame(what: PeerCall.Signalling, payload: String) = PeerMessage(
        id = "m-${what.mark}",
        fromId = caller,
        toId = "me",
        body = PeerCall.encodeSignal(PeerCall.Signal("call_shell_1", what, payload))!!,
        sentAtMs = System.currentTimeMillis(),
    )

    // Без вікна на екрані стеження за клавішею нікому вести — UP несе його сам.
    private fun back(activity: Activity) {
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        activity.dispatchKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0, 0, -1, 0, KeyEvent.FLAG_TRACKING))
    }

    private val caller = "a1b2c3d4e5f6a7b8"

    // Шторка дзвінка читає реєстр через AndroidKeyStore, якого Robolectric не має;
    // лінію хаб ставить ДО шторки, тож падіння шторки лінії не зачіпає.
    private fun absorb(frame: PeerMessage) {
        runCatching { PhantomApp.container().callHub.absorb(frame) }
    }

    private fun ringingOverLock(): Pair<MainActivity, ShadowKeyguardManager> {
        absorb(callFrame(PeerCall.Signalling.OFFER, """{"sdp":"v=0","media":"audio"}"""))
        assertTrue(PhantomApp.container().callHub.line.value is PeerCallLine.Line.Ringing)
        val keyguard = shadowOf(app.getSystemService(KeyguardManager::class.java))
        keyguard.setKeyguardLocked(true)
        val activity = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(Intent.ACTION_VIEW, Uri.parse("phantom://call")),
        ).create().get()
        shadowOf(Looper.getMainLooper()).idle()
        return activity to keyguard
    }

    @Test
    @Config(application = PhantomApp::class)
    fun `phantom-call у справжній MainActivity — над замком «назад» просить PIN, а не веде на мапу`() {
        val (activity, keyguard) = ringingOverLock()
        assertTrue(shadowOf(activity).showWhenLocked)
        assertTrue(shadowOf(activity).turnScreenOn)
        assertFalse(dismissPending())

        var reachedApp = 0
        activity.onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { reachedApp++ }
        })
        back(activity)
        assertEquals("«назад» над замком не веде на мапу", 0, reachedApp)
        assertTrue(dismissPending())
        keyguard.setKeyguardLocked(true)
        back(activity)
        assertTrue("скасований PIN питається знову", dismissPending())
        assertEquals(0, reachedApp)

        keyguard.setKeyguardLocked(false)
        back(activity)
        assertEquals("замок знято — «назад» знову веде застосунком", 1, reachedApp)

        absorb(callFrame(PeerCall.Signalling.HANGUP, """{"reason":"hangup"}"""))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(shadowOf(activity).showWhenLocked)
        assertFalse(shadowOf(activity).turnScreenOn)
    }

    @Test
    @Config(application = PhantomApp::class)
    fun `phantom-call у справжній MainActivity — вкладка чи інша розмова над замком повертають у дзвінок`() {
        val (_, keyguard) = ringingOverLock()
        val container = PhantomApp.container()
        fun idle() = shadowOf(Looper.getMainLooper()).idle()

        container.events.visibleTab.value = HomeTab.CHATS
        container.events.visiblePeerChat.value = caller
        idle()
        assertFalse(dismissPending())
        container.peerChatRequest.value = null
        container.events.visiblePeerChat.value = null
        idle()
        assertTrue("вийшов зі сторінки дзвінка у вебі — PIN", dismissPending())
        assertEquals("і повернуто в дзвінок", caller, container.peerChatRequest.value)
        keyguard.setKeyguardLocked(true)

        container.events.visiblePeerChat.value = caller
        container.peerChatRequest.value = null
        idle()
        container.events.visibleTab.value = HomeTab.WALLET
        idle()
        assertTrue("вкладка гаманця над замком — PIN", dismissPending())
        assertEquals(caller, container.peerChatRequest.value)
    }

    @Test
    fun `піти з дзвінка — це вкладка, чужа розмова чи список, а дорога до дзвінка — ні`() {
        val caller = "p1"
        fun spot(chats: Boolean = true, open: String? = null) = CallSpot(chats, open)
        assertTrue(CallSpot.leaves(spot(open = caller), spot(chats = false, open = caller), caller))
        assertTrue(CallSpot.leaves(spot(open = caller), spot(open = null), caller))
        assertTrue(CallSpot.leaves(spot(open = null), spot(open = "p2"), caller))
        assertTrue(CallSpot.leaves(spot(open = caller), spot(open = "g1"), caller))
        assertFalse("дзвінок відчиняє розмову з тим, хто дзвонить", CallSpot.leaves(spot(open = "p2"), spot(open = caller), caller))
        assertFalse(CallSpot.leaves(spot(open = null), spot(open = caller), caller))
        assertFalse("дорогою до неї веб проходить список", CallSpot.leaves(spot(open = "p2"), spot(open = null), caller))
        assertFalse("з мапи на розмови — це повернення", CallSpot.leaves(spot(chats = false), spot(), caller))
    }

    @Test
    fun `Bluetooth для розмови, взятої над замком, питається, щойно замок знято`() = runBlocking {
        val line = MutableStateFlow<PeerCallLine.Line>(PeerCallLine.Line.Talking("c1", "p1", 1L))
        val unlocks = MutableStateFlow(0)
        val seen = mutableListOf<PeerCallLine.Line>()
        val job = launch(Dispatchers.Unconfined) {
            CallBluetoothAsk.moments(line, unlocks).collect { seen += it }
        }
        unlocks.value++
        job.cancel()
        assertEquals("лінія та сама, але замок знято — ще одна нагода спитати", 2, seen.size)
    }

    @Test
    fun `батарея питається за людину, яку додали руками, а не за незнайомця, що представився сам`() {
        fun person(id: String, added: Long, introduced: Long) = PeerIdentity(
            id = id, name = id, kind = PeerIdentity.Kind.PERSON, pubKey = "k$id",
            addedAtMs = added, introducedAtMs = introduced,
        )
        val stranger = person("s", added = 100L, introduced = 100L)
        val scanned = person("c", added = 100L, introduced = 0L)
        val answered = person("a", added = 100L, introduced = 250L)
        val pc = PeerIdentity(id = "pc", name = "ПК", kind = PeerIdentity.Kind.DEVICE, pubKey = "kpc", addedAtMs = 1L)
        assertEquals(0, BatteryAskAfterContact.addedByHand(listOf(stranger, pc)))
        assertEquals(2, BatteryAskAfterContact.addedByHand(listOf(stranger, scanned, answered, pc)))
    }

    @Test
    fun `злитий маніфест несе службу дзвінка й дозволи, без яких вона мовчить`() {
        val pm = app.packageManager
        val info = pm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        val asked = info.requestedPermissions.orEmpty().toSet()
        listOfNotNull(
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS".takeIf { Distribution.batteryAsk },
            "android.permission.FOREGROUND_SERVICE_CAMERA",
            "android.permission.FOREGROUND_SERVICE_MICROPHONE",
            "android.permission.USE_FULL_SCREEN_INTENT",
        ).forEach { assertTrue("у злитому маніфесті немає $it", it in asked) }

        val call = mergedManifest().byName("service", "local.phantom.companion.service.PeerCallService")
        assertNotNull("у злитому маніфесті немає PeerCallService", call)
        assertEquals("false", call!!.attr("exported"))
        val types = call.attr("foregroundServiceType").split('|').toSet()
        assertEquals(setOf("microphone", "camera"), types)
        val declared = pm.getPackageInfo(app.packageName, PackageManager.GET_SERVICES).services.orEmpty()
            .firstOrNull { it.name == "local.phantom.companion.service.PeerCallService" }
        assertNotNull("PackageManager не бачить PeerCallService", declared)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            declared!!.foregroundServiceType,
        )
    }

    @Test
    fun `фільтр сайту перевіряється й не тягне за собою чужих хостів`() {
        val filters = mergedManifest().byName("activity", MainActivity::class.java.name)!!
            .getElementsByTagName("intent-filter").let { n -> (0 until n.length).map { n.item(it) as Element } }
        val site = filters.filter { f -> f.data().any { it.attr("host") == "phantom-os.dev" } }
        assertEquals(1, site.size)
        assertEquals("true", site.single().attr("autoVerify"))
        assertTrue(site.single().data().all { it.attr("scheme").isEmpty() || it.attr("scheme") == "https" })
        assertTrue(site.single().data().all { it.attr("host").isEmpty() || it.attr("host") == "phantom-os.dev" })
    }

    @Test
    fun `Bluetooth питається, коли розмова вже йде, і лише там, де він оголошений`() {
        val talking = PeerCallLine.Line.Talking("c1", "p1", 1L)
        fun ask(
            line: PeerCallLine.Line = talking,
            sdk: Int = 34,
            declared: Boolean = true,
            granted: Boolean = false,
            asked: Boolean = false,
            locked: Boolean = false,
        ) = CallBluetoothAsk.wanted(line, sdk, declared, granted, asked, locked)

        assertTrue(ask())
        assertFalse("діалог поверх дзвінка, що дзвонить, ховав би «взяти»", ask(line = PeerCallLine.Line.Ringing("c1", "p1", 1L)))
        assertFalse(ask(line = PeerCallLine.Line.Dialing("c1", "p1", 1L)))
        assertFalse(ask(line = PeerCallLine.Line.Idle))
        assertFalse("до API 31 це не діалог", ask(sdk = 30))
        assertFalse("store його не оголошує — система відмовила б мовчки", ask(declared = false))
        assertFalse(ask(granted = true))
        assertFalse("раз на встановлення", ask(asked = true))
        assertFalse("над замком спершу зняти замок", ask(locked = true))
    }

    @Test
    fun `виняток з економії батареї питається після першої людини, не на старті`() {
        val fresh = BatteryAskAfterContact.step(BatteryAskAfterContact.UNSEEN, 0)
        assertEquals(BatteryAskAfterContact.Step(ask = false, seen = 0), fresh)
        assertEquals(BatteryAskAfterContact.Step(ask = true, seen = 1), BatteryAskAfterContact.step(0, 1))

        val upgraded = BatteryAskAfterContact.step(BatteryAskAfterContact.UNSEEN, 5)
        assertEquals("той, хто вже має людей, не отримує діалог на старті", false, upgraded.ask)
        assertEquals(5, upgraded.seen)
        assertEquals(true, BatteryAskAfterContact.step(5, 6).ask)
        assertEquals(BatteryAskAfterContact.Step(ask = false, seen = 4), BatteryAskAfterContact.step(5, 4))
        assertEquals(false, BatteryAskAfterContact.step(0, 0).ask)
        assertEquals(
            "посеред знайомства не питаємо і людину не забуваємо",
            BatteryAskAfterContact.Step(ask = false, seen = 0),
            BatteryAskAfterContact.step(0, 1, settled = false),
        )
        assertEquals(true, BatteryAskAfterContact.step(0, 1, settled = true).ask)
    }

    @Test
    fun `обидва запитання стоять у справжньому вході активності`() {
        val src = File(repoRoot(), "app/src/main/java/local/phantom/companion/MainActivity.kt").readText()
        val create = src.substringAfter("override fun onCreate(").substringBefore("\n    }\n")
        assertTrue("onCreate не стежить за людьми й лінією", "askAtTheRightMoment()" in create)
        val body = src.substringAfter("private fun askAtTheRightMoment()").substringBefore("\n    }\n")
        assertTrue("CallBluetoothAsk.wanted(" in body)
        assertTrue("BatteryAskAfterContact.step(" in body)
        assertTrue("ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" in src)
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("не знайшов корінь дерева (каталог feature-stream)")
    }

    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun Element.attr(name: String): String = getAttributeNS(androidNs, name)

    private fun Element.data(): List<Element> =
        getElementsByTagName("data").let { n -> (0 until n.length).map { n.item(it) as Element } }

    private fun Element.byName(tag: String, name: String): Element? =
        getElementsByTagName(tag).let { n -> (0 until n.length).map { n.item(it) as Element } }
            .firstOrNull { it.attr("name") == name }

    private fun mergedManifest(): Element {
        val props = Properties()
        javaClass.classLoader!!.getResourceAsStream("com/android/tools/test_config.properties")!!.use { props.load(it) }
        val path = props.getProperty("android_merged_manifest")
        val file = File(path)
        assertTrue("немає злитого маніфесту: $path", file.isFile)
        return DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file).documentElement
    }
}
