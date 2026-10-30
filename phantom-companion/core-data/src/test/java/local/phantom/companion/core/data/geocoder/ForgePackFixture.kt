package local.phantom.companion.core.data.geocoder

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.zip.GZIPInputStream

/**
 * Зріз пакета кузні (Кишинів, Бєльці, Тирасполь) і той самий алгоритм над ним
 * через JDBC — бо ворота ARM, а рідного SQLite там немає.
 *
 * Зріз зроблено ТИМ САМИМ `forge/geocoder.py`-схемою з повного пакета Молдови;
 * очікування в [ForgeGeocoderVectorTest] пораховано `forge/geocode.py` над ЦИМ
 * файлом, і 13 з 15 запитів дають той самий рядок у рядок результат, що й
 * опубліковані вектори повної країни.
 */
object ForgePackFixture {

    fun unpack(into: File): File {
        val dest = File(into, "geocoder-md-slice.sqlite")
        if (dest.isFile && dest.length() > 0) return dest
        val stream = javaClass.classLoader!!.getResourceAsStream("forge/geocoder-md-slice.sqlite.gz")
            ?: error("фікстури пакета кузні немає в test resources")
        GZIPInputStream(stream).use { input -> dest.outputStream().use { input.copyTo(it) } }
        return dest
    }

    fun resource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)?.bufferedReader()?.use { it.readText() }
            ?: error("немає ресурсу $name")
}

class JdbcPackDb(path: String) : PackDb {

    private val connection: Connection =
        DriverManager.getConnection("jdbc:sqlite:$path")

    override fun query(sql: String, args: Array<String>): PackCursor {
        val statement = connection.prepareStatement(sql)
        args.forEachIndexed { i, value -> statement.setString(i + 1, value) }
        return JdbcCursor(statement.executeQuery())
    }

    override fun close() = connection.close()

    private class JdbcCursor(private val rows: ResultSet) : PackCursor {
        override fun next(): Boolean = rows.next()
        override fun text(column: Int): String? = rows.getString(column + 1)
        override fun long(column: Int): Long = rows.getLong(column + 1)
        override fun double(column: Int): Double = rows.getDouble(column + 1)
        override fun int(column: Int): Int = rows.getInt(column + 1)
        override fun close() {
            val statement = rows.statement
            rows.close()
            statement.close()
        }
    }
}
