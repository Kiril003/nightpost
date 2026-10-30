package local.phantom.companion.data

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import local.phantom.companion.BuildConfig
import local.phantom.companion.core.data.vault.VaultCipher
import local.phantom.companion.core.data.vault.VaultDao
import local.phantom.companion.core.data.vault.VaultEntity
import local.phantom.companion.core.data.vault.VaultStore
import local.phantom.companion.feature.godmode.engine.AppAction
import local.phantom.companion.feature.godmode.engine.AppActionAuthorizer
import local.phantom.companion.feature.godmode.engine.AuthorizationResult
import local.phantom.companion.feature.godmode.engine.ProtectedWindows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GodModeAuthorizerWiringTest {

    private val vault = VaultStore(
        dao = object : VaultDao {
            override fun getAll(): Flow<List<VaultEntity>> = flowOf(emptyList())
            override suspend fun getById(id: String): VaultEntity? = null
            override suspend fun insert(entry: VaultEntity) = Unit
            override suspend fun delete(id: String) = Unit
        },
        cipher = object : VaultCipher {
            override fun encrypt(plaintext: String) = plaintext
            override fun decrypt(stored: String) = stored
        },
    )

    private fun reason(r: AuthorizationResult): String? = (r as? AuthorizationResult.Denied)?.reasonKey

    @Test
    fun `the vault clamp stops every app action and lifts with the unlock`() {
        val auth = appActionAuthorizer({ vault }, BuildConfig.APPLICATION_ID)
        assertEquals(AuthorizationResult.Allowed, auth.authorize(AppAction.Back, "org.telegram.messenger"))

        vault.lockNow()
        assertEquals(AppActionAuthorizer.REASON_VAULT_LOCKED, reason(auth.authorize(AppAction.Back, "org.telegram.messenger")))
        assertEquals(AppActionAuthorizer.REASON_VAULT_LOCKED, reason(auth.authorize(AppAction.Tap(1, 1), "org.telegram.messenger")))

        vault.unlock()
        assertEquals(AuthorizationResult.Allowed, auth.authorize(AppAction.Back, "org.telegram.messenger"))
    }

    @Test
    fun `a vault that cannot be read counts as locked`() {
        val auth = appActionAuthorizer({ error("vault not ready") }, BuildConfig.APPLICATION_ID)
        assertEquals(AppActionAuthorizer.REASON_VAULT_LOCKED, reason(auth.authorize(AppAction.Back, "org.telegram.messenger")))
    }

    @Test
    fun `this build's own package is a protected window`() {
        val id = BuildConfig.APPLICATION_ID
        val root = ProtectedWindows.OWN_APPLICATION_ID
        val store = ProtectedWindows.STORE_APPLICATION_ID
        assertTrue("applicationId $id вийшов з-під коренів $root і $store — сімʼю варіантів не впізнати", listOf(root, store).any { id == it || id.startsWith("$it.") })
        val auth = appActionAuthorizer({ vault }, id)
        for (pkg in listOf(id, root, "$root.debug", "$root.seeker", store, "$store.debug")) {
            assertEquals("дотик у «$pkg»", AppActionAuthorizer.REASON_PROTECTED_WINDOW, reason(auth.authorize(AppAction.Tap(1, 1), pkg)))
        }
    }

    @Test
    fun `the boot arms the accessibility registry with the real vault clamp`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "feature-stream").isDirectory }
        val boot = File(root, "app/src/main/java/local/phantom/companion/data/ContainerBoot.kt").readText()
        assertTrue(
            "реєстр Accessibility озброєно не appActionAuthorizer({ vaultStore }, …)",
            Regex("""AccessibilityServiceActionRegistry\.authorizer\s*=\s*appActionAuthorizer\(\{\s*vaultStore\s*},\s*applicationContext\.packageName\)""").containsMatchIn(boot),
        )
        val stubbed = File(root, "app/src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""isVaultLocked\s*=\s*\{\s*false\s*}""").containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertFalse("замок сховища знову заглушка: $stubbed", stubbed.isNotEmpty())
    }
}
