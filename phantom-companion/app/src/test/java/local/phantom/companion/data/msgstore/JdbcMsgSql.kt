package local.phantom.companion.data.msgstore

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.concurrent.atomic.AtomicInteger

/** Той самий SQL сховища над sqlite-jdbc: без шифру, але з тими ж тригерами й FTS5. */
internal class JdbcMsgSql(file: File) : MsgSql, AutoCloseable {

    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
    private var depth = 0

    /** Скільки рядків-операцій дійшло до диска: доказ «записано різницею». */
    val rowWrites = AtomicInteger()

    override fun exec(sql: String, vararg args: Any?) {
        conn.prepareStatement(sql).use { st ->
            bind(st, args)
            st.execute()
        }
    }

    override fun batch(sql: String, rows: List<Array<Any?>>) {
        if (rows.isEmpty()) return
        conn.prepareStatement(sql).use { st ->
            rows.forEach { args ->
                bind(st, args)
                st.executeUpdate()
                rowWrites.incrementAndGet()
            }
        }
    }

    override fun <T> query(sql: String, vararg args: Any?, row: (MsgRow) -> T): List<T> =
        conn.prepareStatement(sql).use { st ->
            bind(st, args)
            st.executeQuery().use { rs ->
                val r = object : MsgRow {
                    override fun str(i: Int): String = rs.getString(i + 1) ?: ""
                    override fun long(i: Int): Long = rs.getLong(i + 1)
                }
                val out = ArrayList<T>()
                while (rs.next()) out += row(r)
                out
            }
        }

    override fun <T> tx(block: () -> T): T {
        if (depth++ > 0) return try { block() } finally { depth-- }
        conn.autoCommit = false
        try {
            val r = block()
            conn.commit()
            return r
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            conn.autoCommit = true
            depth--
        }
    }

    override fun close() = conn.close()

    private fun bind(st: PreparedStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, a ->
            when (a) {
                null -> st.setNull(i + 1, java.sql.Types.NULL)
                is String -> st.setString(i + 1, a)
                is Long -> st.setLong(i + 1, a)
                is Int -> st.setLong(i + 1, a.toLong())
                else -> st.setString(i + 1, a.toString())
            }
        }
    }
}
