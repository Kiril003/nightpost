package local.phantom.companion.feature.godmode.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppActionAuthorizerTest {

    private val sensitive = setOf("com.ftband.mono", "com.lastpass.lpandroid")

    private fun auth(
        sensitiveCheck: (String) -> Boolean = { sensitive.contains(it) },
        vaultLocked: Boolean = false,
    ) = AppActionAuthorizer(
        isSensitiveApp = sensitiveCheck,
        isVaultLocked = { vaultLocked },
    )

    @Test
    fun `click on sensitive app is denied with banking text`() {
        val result = auth().authorize(
            action = AppAction.Click(ActionTarget(text = "Pay")),
            currentPackage = "com.ftband.mono",
        )
        result as AuthorizationResult.Denied
        assertEquals(AppActionAuthorizer.REASON_SENSITIVE_APP, result.reasonKey)
        assertTrue(
            "operator-visible reason mentions banking: ${result.displayText}",
            result.displayText.contains("банкінг"),
        )
    }

    @Test
    fun `type on sensitive app is denied`() {
        val result = auth().authorize(
            action = AppAction.Type(value = "secret"),
            currentPackage = "com.lastpass.lpandroid",
        )
        assertTrue(result is AuthorizationResult.Denied)
    }

    @Test
    fun `click on non-sensitive app is allowed`() {
        val result = auth().authorize(
            action = AppAction.Click(ActionTarget(text = "Open")),
            currentPackage = "org.telegram.messenger",
        )
        assertEquals(AuthorizationResult.Allowed, result)
    }

    @Test
    fun `scroll on sensitive app is allowed (inert UX)`() {
        val result = auth().authorize(
            action = AppAction.Scroll(direction = ScrollDirection.DOWN),
            currentPackage = "com.ftband.mono",
        )
        assertEquals(AuthorizationResult.Allowed, result)
    }

    @Test
    fun `back home recents on sensitive app are allowed`() {
        val a = auth()
        for (action in listOf(AppAction.Back, AppAction.Home, AppAction.Recents)) {
            val res = a.authorize(action, currentPackage = "com.ftband.mono")
            assertEquals("$action stays allowed in sensitive app", AuthorizationResult.Allowed, res)
        }
    }

    @Test
    fun `vault locked denies even safe global actions`() {
        val result = auth(vaultLocked = true).authorize(
            action = AppAction.Back,
            currentPackage = "any.package",
        )
        result as AuthorizationResult.Denied
        // reasonKey — сталий контракт, за ним і перевіряємо. displayText — це
        // текст для оператора: він перекладний, і привʼязка до англійського
        // «vault» ламала цей тест щоразу після локалізації, хоча сама відмова
        // жодного разу не ламалась.
        assertEquals(AppActionAuthorizer.REASON_VAULT_LOCKED, result.reasonKey)
        assertTrue("оператор має отримати пояснення", result.displayText.isNotBlank())
    }

    @Test
    fun `vault locked takes precedence over sensitive app`() {
        // Same action, two reasons stack — vault wins so the operator
        // gets a single «vault» message, not the «banking» follow-up.
        val result = auth(vaultLocked = true).authorize(
            action = AppAction.Click(ActionTarget(text = "Pay")),
            currentPackage = "com.ftband.mono",
        )
        result as AuthorizationResult.Denied
        assertEquals(AppActionAuthorizer.REASON_VAULT_LOCKED, result.reasonKey)
    }
}
