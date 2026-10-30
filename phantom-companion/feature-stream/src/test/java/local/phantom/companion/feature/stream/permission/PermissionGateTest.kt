package local.phantom.companion.feature.stream.permission

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.core.design.permission.GateAsk
import local.phantom.companion.core.design.permission.PermissionGate
import local.phantom.companion.core.design.permission.PermissionGateModel
import local.phantom.companion.core.design.permission.PermissionReason
import local.phantom.companion.core.design.permission.appSettingsIntent
import local.phantom.companion.core.design.permission.canAskAgain
import local.phantom.companion.core.design.permission.rememberPermissionGate
import local.phantom.companion.feature.stream.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Дозвіл питають у мить потреби: спершу рядок «навіщо», потім системне вікно;
 * відмова не зачиняє функцію назавжди; коли Android більше не показує вікна —
 * шлях у налаштування застосунку, а не мовчання.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionGateTest {

    @Test
    fun `перше прохання - спершу причина, системне вікно лише після «Дозволити»`() {
        val model = PermissionGateModel()
        val answers = mutableListOf<Boolean>()

        model.request(granted = false) { answers += it }

        assertEquals(GateAsk.REASON, model.ask)
        assertTrue("системне вікно ще не відкривали", answers.isEmpty())
        assertTrue("«Дозволити» веде в системне вікно", model.allow())
        assertEquals(GateAsk.ASKING, model.ask)
    }

    @Test
    fun `після відмови наступне використання питає знову`() {
        val model = PermissionGateModel()
        val answers = mutableListOf<Boolean>()

        model.request(granted = false) { answers += it }
        model.allow()
        model.onResult(granted = false, canAskAgain = true)
        // FR-5: жодної другої картки одразу після «ні» — функція отримує відмову.
        assertEquals("після першої відмови картка не виринає знову", GateAsk.NONE, model.ask)
        assertEquals(listOf(false), answers)

        model.request(granted = false) { answers += it }
        assertEquals(GateAsk.REASON, model.ask)
        assertTrue(model.allow())
        model.onResult(granted = true, canAskAgain = true)
        assertEquals(listOf(false, true), answers)
        assertEquals(GateAsk.NONE, model.ask)
    }

    @Test
    fun `назавжди відмовлено - пропонує налаштування застосунку`() {
        val model = PermissionGateModel()
        val answers = mutableListOf<Boolean>()

        model.request(granted = false) { answers += it }
        model.allow()
        model.onResult(granted = false, canAskAgain = false)
        assertEquals(GateAsk.BLOCKED, model.ask)
        model.openSettings()
        assertEquals("веб не висить: відповідь прийшла", listOf(false), answers)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = appSettingsIntent(context)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${context.packageName}", intent.data.toString())
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `друге прохання поверх першого відповідає першому, а не губить його`() {
        val model = PermissionGateModel()
        val first = mutableListOf<Boolean>()

        model.request(granted = false) { first += it }
        model.request(granted = false) { }

        assertEquals(listOf(false), first)
        assertEquals(GateAsk.REASON, model.ask)
    }

    @Test
    fun `блок - лише відмова без rationale після справжньої відмови`() {
        assertTrue("перша відмова без rationale ще не блок", canAskAgain(granted = false, rationale = false, deniedBefore = false))
        assertFalse("друга поспіль - блок", canAskAgain(granted = false, rationale = false, deniedBefore = true))
        assertTrue("rationale є - вікно ще покажуть", canAskAgain(granted = false, rationale = true, deniedBefore = true))
        assertTrue("дозвіл є - питати нема чого", canAskAgain(granted = true, rationale = false, deniedBefore = true))
    }

    @Test
    fun `дозвіл «лише цього разу» спливає - брама все одно показує системне вікно`() {
        val rig = Rig()
        rig.prefs().edit().putBoolean(Manifest.permission.CAMERA, true).apply()

        rig.gate.request { }
        assertEquals("не «Android більше не питатиме»", GateAsk.REASON, rig.gate.stage)
        rig.gate.allow()

        assertEquals("системне вікно справді запущено", 1, rig.registry.launches)
        rig.close()
    }

    @Test
    fun `одна відмова - тиша до наступного входу, друга - налаштування`() {
        val rig = Rig()
        val answers = mutableListOf<Boolean>()

        rig.gate.request { answers += it }
        rig.gate.allow()
        assertEquals("FR-5: після першого «ні» картка не виринає", GateAsk.NONE, rig.gate.stage)
        assertEquals(listOf(false), answers)

        // Людина знову йде у функцію: Android ще пояснює (rationale) — питаємо знову.
        rig.rationale(true)
        rig.gate.request { answers += it }
        assertEquals(GateAsk.REASON, rig.gate.stage)
        rig.rationale(false)
        rig.gate.allow()
        assertEquals("ОС показала вікно двічі", 2, rig.registry.launches)
        assertEquals(GateAsk.BLOCKED, rig.gate.stage)
        assertEquals("відповіді ще немає - картка налаштувань на екрані", listOf(false), answers)
        rig.close()
    }

    @Test
    fun `остаточна відмова - наступний вхід одразу каже «вимкнено», а не першу картку`() {
        val rig = Rig()
        rig.prefs().edit().putBoolean("denied:${Manifest.permission.CAMERA}", true).apply()
        rig.rationale(false)

        rig.gate.request { }
        // FR-4: раніше тут знову стояло «Nightpost просить камеру / Дозволити».
        assertEquals(GateAsk.BLOCKED, rig.gate.stage)
        assertEquals("марного системного вікна не було", 0, rig.registry.launches)
        rig.close()
    }

    @Test
    fun `дозвіл дано - відповідь true рівно раз і картки немає`() {
        val rig = Rig(grantOnLaunch = true)
        val answers = mutableListOf<Boolean>()

        rig.gate.request { answers += it }
        rig.gate.allow()

        assertEquals(listOf(true), answers)
        assertEquals(GateAsk.NONE, rig.gate.stage)
        rig.close()
    }

    @Test
    fun `відмова не випаровується після дозволу - наступне спливання не блок`() {
        val rig = Rig(grantOnLaunch = false)
        rig.gate.request { }
        rig.gate.allow()
        rig.grantOnLaunch = true
        rig.rationale(true)
        rig.gate.request { }
        rig.gate.allow()
        assertEquals(GateAsk.NONE, rig.gate.stage)

        assertFalse(
            "після дозволу стара відмова забута",
            rig.prefs().getBoolean("denied:${Manifest.permission.CAMERA}", false),
        )
        rig.close()
    }

    private class FakeRegistry(private val onLaunch: () -> Unit) : ActivityResultRegistry() {
        var launches = 0

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            launches++
            onLaunch()
            @Suppress("UNCHECKED_CAST")
            dispatchResult(requestCode, emptyMap<String, Boolean>() as O)
        }
    }

    private class Rig(grantOnLaunch: Boolean = false) {
        var grantOnLaunch: Boolean = grantOnLaunch
        val registry = FakeRegistry {
            if (this.grantOnLaunch) {
                shadowOf(ApplicationProvider.getApplicationContext<Application>())
                    .grantPermissions(Manifest.permission.CAMERA)
            }
        }
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        private val activity = controller.get()
        lateinit var gate: PermissionGate

        init {
            val owner = object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }
            activity.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    gate = rememberPermissionGate(
                        permissions = listOf(Manifest.permission.CAMERA),
                        reason = PermissionReason(
                            local.phantom.companion.core.design.R.drawable.lc_camera,
                            R.string.perm_camera_title,
                            R.string.perm_camera_body,
                        ),
                    )
                }
            }
            shadowOf(Looper.getMainLooper()).idle()
        }

        fun prefs() = activity.getSharedPreferences("permission_gate", Context.MODE_PRIVATE)

        fun rationale(show: Boolean) =
            shadowOf(activity.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.CAMERA, show)

        fun close() {
            controller.pause().stop().destroy()
        }
    }
}
