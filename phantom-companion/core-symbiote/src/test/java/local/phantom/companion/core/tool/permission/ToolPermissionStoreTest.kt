package local.phantom.companion.core.tool.permission

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * JVM-only tests for [InMemoryToolPermissionStore].
 * Production [EncryptedToolPermissionStore] is verified on-device only.
 */
class ToolPermissionStoreTest {

    private lateinit var store: ToolPermissionStore

    @Before
    fun setUp() {
        store = InMemoryToolPermissionStore()
    }

    @Test
    fun `default decision is ASK`() {
        assertEquals(Decision.ASK, store.decision("shell"))
        assertEquals(Decision.ASK, store.decision("http"))
    }

    @Test
    fun `setDecision persists ALWAYS`() {
        store.setDecision("shell", Decision.ALWAYS)
        assertEquals(Decision.ALWAYS, store.decision("shell"))
    }

    @Test
    fun `setDecision persists NEVER`() {
        store.setDecision("http", Decision.NEVER)
        assertEquals(Decision.NEVER, store.decision("http"))
    }

    @Test
    fun `setDecision overwrites previous value`() {
        store.setDecision("shell", Decision.ALWAYS)
        store.setDecision("shell", Decision.NEVER)
        assertEquals(Decision.NEVER, store.decision("shell"))
    }

    @Test
    fun `clearAll resets all decisions to ASK`() {
        store.setDecision("shell", Decision.ALWAYS)
        store.setDecision("http", Decision.NEVER)
        store.clearAll()
        assertEquals(Decision.ASK, store.decision("shell"))
        assertEquals(Decision.ASK, store.decision("http"))
    }

    @Test
    fun `independent tool IDs do not interfere`() {
        store.setDecision("shell", Decision.ALWAYS)
        store.setDecision("http", Decision.NEVER)
        assertEquals(Decision.ALWAYS, store.decision("shell"))
        assertEquals(Decision.NEVER, store.decision("http"))
        assertEquals(Decision.ASK, store.decision("file"))
    }
}
