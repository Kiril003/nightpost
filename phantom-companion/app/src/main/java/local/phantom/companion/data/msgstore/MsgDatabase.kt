package local.phantom.companion.data.msgstore

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteProgram
import java.io.File
import local.phantom.companion.core.data.VaultCrypto
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

internal interface MsgRow {
    fun str(i: Int): String
    fun long(i: Int): Long
}

/** Той мінімум SQL, якого треба сховищу: на телефоні — SQLCipher, у тестах JVM — JDBC. */
internal interface MsgSql {
    fun exec(sql: String, vararg args: Any?)
    fun batch(sql: String, rows: List<Array<Any?>>)
    fun <T> query(sql: String, vararg args: Any?, row: (MsgRow) -> T): List<T>
    fun <T> tx(block: () -> T): T
}

/** Розмови на диску: рядки, підсумки, повнотекстовий індекс, службові позначки. */
internal class MsgBase(val sql: MsgSql) {

    val lines = MsgLines(sql)
    val summaries = ConvSummaries(sql)
    val fts = MsgFts(sql)

    init {
        sql.exec("CREATE TABLE IF NOT EXISTS meta (k TEXT NOT NULL PRIMARY KEY, v TEXT NOT NULL)")
        sql.tx {
            val version = runCatching { meta(VERSION_KEY)?.toInt() }.getOrNull() ?: 0
            if (version < VERSION) {
                (MsgLines.SCHEMA + ConvSummaries.SCHEMA + MsgFts.SCHEMA).forEach { sql.exec(it) }
                val have = sql.query("PRAGMA table_info(line)") { it.str(1) }.toSet()
                MsgLines.ADDED.filter { (name, _) -> name !in have }
                    .forEach { (_, column) -> sql.exec("ALTER TABLE line ADD COLUMN $column") }
                setMeta(VERSION_KEY, VERSION.toString())
            }
        }
    }

    fun meta(key: String): String? =
        sql.query("SELECT v FROM meta WHERE k = ?", key) { it.str(0) }.firstOrNull()

    fun setMeta(key: String, value: String) {
        sql.exec("INSERT INTO meta (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v", key, value)
    }

    companion object {
        const val VERSION = 2
        const val VERSION_KEY = "schema"
        const val FILE_NAME = "phantom-msg.db"
        private const val TAG = "MsgBase"

        /**
         * Не піднялось — null, і журнал лишається на старих дверях до наступного
         * запуску. Файл, який наявним ключем не відчиняється, відкладається
         * осторонь, а не стирається.
         */
        fun open(context: Context): MsgBase? {
            val app = context.applicationContext
            if (!VaultCrypto.nativeLoaded) return null
            val file = app.getDatabasePath(FILE_NAME)
            val key = MsgKey.loadOrCreate(app, dbExists = file.exists()) ?: return null
            return runCatching { MsgBase(SupportMsgSql(helper(app, key).writableDatabase)) }
                .recoverCatching { first ->
                    Log.e(TAG, "база розмов не відчинилась, відкладаю осторонь", first)
                    setAside(file)
                    MsgBase(SupportMsgSql(helper(app, key).writableDatabase))
                }
                .onFailure { Log.e(TAG, "база розмов недоступна", it) }
                .getOrNull()
        }

        private fun helper(app: Context, key: ByteArray): SupportSQLiteOpenHelper =
            SupportOpenHelperFactory(key).create(
                SupportSQLiteOpenHelper.Configuration.builder(app)
                    .name(FILE_NAME)
                    .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    })
                    .build(),
            ).also { it.setWriteAheadLoggingEnabled(true) }

        fun setAside(file: File) {
            val stamp = System.currentTimeMillis()
            listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
                val f = File(file.path + suffix)
                if (f.exists()) f.renameTo(File("${file.path}.broken-$stamp$suffix"))
            }
        }
    }
}

internal class SupportMsgSql(private val db: SupportSQLiteDatabase) : MsgSql {

    override fun exec(sql: String, vararg args: Any?) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args)
    }

    override fun batch(sql: String, rows: List<Array<Any?>>) {
        if (rows.isEmpty()) return
        db.compileStatement(sql).use { st ->
            rows.forEach { args ->
                st.clearBindings()
                bind(st, args)
                st.execute()
            }
        }
    }

    override fun <T> query(sql: String, vararg args: Any?, row: (MsgRow) -> T): List<T> =
        db.query(SimpleSQLiteQuery(sql, args)).use { c ->
            val r = object : MsgRow {
                override fun str(i: Int): String = c.getString(i) ?: ""
                override fun long(i: Int): Long = c.getLong(i)
            }
            val out = ArrayList<T>(c.count.coerceAtLeast(0))
            while (c.moveToNext()) out += row(r)
            out
        }

    override fun <T> tx(block: () -> T): T {
        db.beginTransaction()
        try {
            val r = block()
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }

    private fun bind(st: SupportSQLiteProgram, args: Array<Any?>) {
        args.forEachIndexed { i, a ->
            when (a) {
                null -> st.bindNull(i + 1)
                is String -> st.bindString(i + 1, a)
                is Long -> st.bindLong(i + 1, a)
                is Int -> st.bindLong(i + 1, a.toLong())
                else -> st.bindString(i + 1, a.toString())
            }
        }
    }
}
