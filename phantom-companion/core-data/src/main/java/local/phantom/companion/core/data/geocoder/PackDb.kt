package local.phantom.companion.core.data.geocoder

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable

/**
 * Найвужча щілина до пакета: рядки й стовпці, більше нічого.
 *
 * Потрібна не заради абстракції, а тому що ворота їдуть на ARM, де
 * Robolectric не має рідного SQLite: без цієї щілини алгоритм пакета
 * лишився б недоведеним на воротах узагалі.
 */
interface PackCursor : Closeable {
    fun next(): Boolean
    fun text(column: Int): String?
    fun long(column: Int): Long
    fun double(column: Int): Double
    fun int(column: Int): Int
}

interface PackDb : Closeable {
    fun query(sql: String, args: Array<String>): PackCursor
}

class AndroidPackDb(private val db: SQLiteDatabase) : PackDb {

    override fun query(sql: String, args: Array<String>): PackCursor =
        AndroidPackCursor(db.rawQuery(sql, args))

    override fun close() = db.close()

    private class AndroidPackCursor(private val cursor: Cursor) : PackCursor {
        override fun next(): Boolean = cursor.moveToNext()
        override fun text(column: Int): String? = cursor.getString(column)
        override fun long(column: Int): Long = cursor.getLong(column)
        override fun double(column: Int): Double = cursor.getDouble(column)
        override fun int(column: Int): Int = cursor.getInt(column)
        override fun close() = cursor.close()
    }
}
