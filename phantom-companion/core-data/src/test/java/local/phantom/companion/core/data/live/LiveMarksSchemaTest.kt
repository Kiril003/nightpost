package local.phantom.companion.core.data.live

import java.io.File
import local.phantom.companion.core.net.live.LiveMarksConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Храповик на схему живого шару.
 *
 * Міграцію застосовують централізовано, тож помилка в ній не падає жодною
 * збіркою — вона просто відкриває чужим людям право стирати мітки. Тому
 * політики перевіряються тут, на рівні тексту: дешево і на кожному прогоні.
 */
class LiveMarksSchemaTest {

    private val sql: String by lazy {
        val file = findUpwards("supabase/migrations/0001_live_marks.sql")
        assertNotNull("міграцію живого шару не знайдено", file)
        file!!.readText().lowercase()
    }

    @Test
    fun `RLS увімкнено`() {
        assertTrue(sql.contains("enable row level security"))
    }

    @Test
    fun `anon не має права правити чи стирати чужі мітки`() {
        assertFalse("зʼявилась політика update для anon", Regex("for\\s+update").containsMatchIn(sql))
        assertFalse("зʼявилась політика delete для anon", Regex("for\\s+delete").containsMatchIn(sql))
    }

    @Test
    fun `anon має рівно два привілеї — читати й додавати`() {
        assertTrue(Regex("grant\\s+select,\\s*insert\\s+on\\s+public\\.live_marks\\s+to\\s+anon").containsMatchIn(sql))
        assertTrue(Regex("revoke\\s+update,\\s*delete\\s+on\\s+public\\.live_marks\\s+from\\s+anon").containsMatchIn(sql))
    }

    @Test
    fun `стеля TTL стоїть у політиці вставки, а не лише в клієнті`() {
        assertTrue(sql.contains("interval '2 hours'"))
        assertTrue(sql.contains("with check"))
    }

    @Test
    fun `читання віддає лише живі мітки`() {
        assertTrue(Regex("using\\s*\\(\\s*expires_at\\s*>\\s*now\\(\\)").containsMatchIn(sql))
    }

    @Test
    fun `таблиця не має жодного поля про особу`() {
        listOf("user_id", "auth.uid", "email", "phone", "operator_lat", "operator_lon").forEach {
            assertFalse("схема тягне особу: $it", sql.contains(it))
        }
    }

    @Test
    fun `регіональний індекс на місці`() {
        assertTrue(Regex("on\\s+public\\.live_marks\\s*\\(geohash6,\\s*expires_at\\)").containsMatchIn(sql))
    }

    @Test
    fun `клієнт не шле жодного виду, який остання міграція відкидає`() {
        val dir = findUpwards("supabase/migrations/0001_live_marks.sql")!!.parentFile
        val server = serverKinds(dir.listFiles { f -> f.extension == "sql" }!!.sortedBy { it.name }.map { it.readText() })
        assertEquals(emptySet<String>(), kindsServerRejects(LiveMarksConfig.ALLOWED_KINDS, server))
    }

    @Test
    fun `сторож червоніє, коли в клієнта є вид, якого сервер не знає`() {
        val server = serverKinds(listOf("alter table t add constraint k check (kind in ('ДТП'));"))
        assertEquals(setOf("ДТП"), server)
        assertEquals(setOf("патруль"), kindsServerRejects(setOf("ДТП", "патруль"), server))
        assertEquals(emptySet<String>(), kindsServerRejects(setOf("ДТП"), setOf("ДТП", "патруль")))
    }

    private fun serverKinds(migrations: List<String>): Set<String> {
        val lastCheck = migrations
            .flatMap { Regex("""check \(kind in \(([^)]*)\)\)""").findAll(it).toList() }
            .last().groupValues[1]
        return Regex("'([^']*)'").findAll(lastCheck).map { it.groupValues[1] }.toSet()
    }

    private fun kindsServerRejects(client: Set<String>, server: Set<String>): Set<String> = client - server

    private fun findUpwards(relative: String): File? {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }
}
