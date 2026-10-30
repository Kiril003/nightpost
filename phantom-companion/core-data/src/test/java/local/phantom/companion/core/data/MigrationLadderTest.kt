package local.phantom.companion.core.data

import android.database.Cursor
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * The gate that replaced `fallbackToDestructiveMigration()`.
 *
 * Room's fallback turned "somebody bumped @Database(version) without writing a
 * Migration" — a mistake CI can see — into "the operator's vault, chats and
 * memory are dropped on next launch". With the fallback gone, that same mistake
 * is a crash on the user's phone instead, which is no better. This test is what
 * makes it neither: the ladder is checked against the exported schema files, so
 * a missing edge fails the build.
 */
class MigrationLadderTest {

    private val schemaDir: File by lazy {
        val relative = "schemas/local.phantom.companion.core.data.PhantomDatabase"
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .take(4)
            .map { File(it, relative) }
            .firstOrNull { it.isDirectory }
            ?: error("exported Room schemas not found — is room.schemaLocation still set?")
    }

    /** Newest schema KSP exported, i.e. the current @Database(version = …). */
    private fun currentVersion(): Int =
        schemaDir.listFiles { f -> f.name.endsWith(".json") }
            .orEmpty()
            .mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            .maxOrNull()
            ?: error("no exported schemas in $schemaDir")

    @Test
    fun `every registered migration moves exactly one version`() {
        PhantomDatabase.ALL_MIGRATIONS.forEach { m ->
            assertEquals(
                "migration ${m.startVersion}->${m.endVersion} must be a single step",
                m.startVersion + 1,
                m.endVersion,
            )
        }
    }

    @Test
    fun `ladder is contiguous from 1 to the current schema version`() {
        val current = currentVersion()
        val edges = PhantomDatabase.ALL_MIGRATIONS.map { it.startVersion to it.endVersion }.toSet()
        val missing = (1 until current).filterNot { (it to it + 1) in edges }
        assertTrue(
            "no migration registered for version(s) ${missing.joinToString()} -> next. " +
                "Every @Database version bump must ship its Migration: there is no " +
                "destructive fallback to hide behind.",
            missing.isEmpty(),
        )
    }

    @Test
    fun `ladder does not run past the current schema version`() {
        val current = currentVersion()
        val stray = PhantomDatabase.ALL_MIGRATIONS.filter { it.endVersion > current }
        assertTrue(
            "migration(s) to version(s) ${stray.map { it.endVersion }} registered but no " +
                "schema exported — was @Database(version) bumped?",
            stray.isEmpty(),
        )
    }

    @Test
    fun `no duplicate edges`() {
        val edges = PhantomDatabase.ALL_MIGRATIONS.map { it.startVersion to it.endVersion }
        assertEquals("duplicate migration edge registered", edges.size, edges.toSet().size)
    }

    @Test
    fun `every schema version between 1 and the current one was exported`() {
        val exported = schemaDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            .toSet()
        val missing = (1..currentVersion()).filterNot { it in exported }
        assertTrue(
            "schemas/${missing.joinToString { "$it.json" }} missing — the contiguity check " +
                "reads the newest exported schema, so a hole here makes it lie",
            missing.isEmpty(),
        )
    }

    /**
     * A registered edge is not the same as a correct one. With the destructive
     * fallback gone, a migration that runs but forgets a table or a column is no
     * longer a silent wipe — it is Room throwing «Migration didn't properly
     * handle» on the operator's phone, on every launch, with no way back. So the
     * SQL each edge actually executes is captured and checked against what the
     * two exported schemas say changed between them.
     */
    @Test
    fun `every edge creates the tables and columns its schema diff demands`() {
        val current = currentVersion()
        for (from in 1 until current) {
            val to = from + 1
            val before = tables(from)
            val after = tables(to)
            val sql = capturedSql(PhantomDatabase.ALL_MIGRATIONS.single { it.startVersion == from })

            for ((table, columns) in after) {
                val known = before[table]
                if (known == null) {
                    assertTrue(
                        "$from->$to: `$table` is new in $to.json but the migration never creates it",
                        sql.any { it.contains("create table $table") || it.contains("create virtual table $table") },
                    )
                    continue
                }
                // A virtual table has no ALTER … ADD COLUMN — FTS4 can only be
                // dropped and re-declared. The new-table branch above already
                // counts `create virtual table` as creation; not counting it as
                // recreation here failed every legitimate FTS column change.
                val recreated = sql.any {
                    it.contains("create table $table") || it.contains("create virtual table $table")
                }
                for (column in columns - known) {
                    assertTrue(
                        "$from->$to: `$table`.`$column` is new in $to.json but the migration " +
                            "neither adds, renames nor recreates it",
                        recreated || sql.any {
                            it.contains("alter table $table") &&
                                (it.contains("add column $column") || it.contains("to $column"))
                        },
                    )
                }
            }
        }
    }

    /** tableName -> column names, read off the exported schema for [version]. */
    private fun tables(version: Int): Map<String, Set<String>> {
        val root = Json.parseToJsonElement(File(schemaDir, "$version.json").readText())
        return root.jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray.associate { entity ->
            val e = entity.jsonObject
            e["tableName"]!!.jsonPrimitive.content to
                e["fields"]!!.jsonArray.map { it.jsonObject["columnName"]!!.jsonPrimitive.content }.toSet()
        }
    }

    /**
     * Runs [migration] against a database that records instead of executing.
     * Nothing here needs a SQLite engine — the question is what statements the
     * edge issues, and a dynamic proxy answers it without Robolectric, which
     * cannot fetch its android-all jar on an offline build anyway.
     */
    private fun capturedSql(migration: Migration): List<String> {
        val statements = mutableListOf<String>()
        val db = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            when (method.name) {
                "execSQL" -> statements.add(args!![0] as String)
                "query" -> emptyCursor()
                else -> zeroOf(method.returnType)
            }
        } as SupportSQLiteDatabase
        migration.migrate(db)
        return statements.map {
            it.replace("`", "").replace(Regex("\\s+"), " ").trim().lowercase()
                .replace("if not exists ", "")
        }
    }

    private fun emptyCursor(): Cursor = Proxy.newProxyInstance(
        Cursor::class.java.classLoader,
        arrayOf(Cursor::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "moveToNext", "moveToFirst" -> false
            else -> zeroOf(method.returnType)
        }
    } as Cursor

    private fun zeroOf(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        Short::class.javaPrimitiveType -> 0.toShort()
        Byte::class.javaPrimitiveType -> 0.toByte()
        else -> null
    }
}
