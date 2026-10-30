package local.phantom.companion.data.backup

import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCreateTest {

    @Test
    fun `a failed first backup leaves no empty file behind`() {
        var discarded = 0
        val ok = writeFirstBackup(
            open = { ByteArrayOutputStream() },
            seal = { it.write(1); throw IOException("disk full") },
            discard = { discarded++ },
        )
        assertFalse(ok)
        assertEquals(1, discarded)
    }

    @Test
    fun `no stream is a failure too`() {
        var discarded = 0
        assertFalse(writeFirstBackup(open = { null }, seal = {}, discard = { discarded++ }))
        assertEquals(1, discarded)
    }

    @Test
    fun `a good backup keeps its file`() {
        var discarded = 0
        val out = ByteArrayOutputStream()
        assertTrue(writeFirstBackup(open = { out }, seal = { it.write(7) }, discard = { discarded++ }))
        assertEquals(0, discarded)
        assertEquals(1, out.size())
    }
}
