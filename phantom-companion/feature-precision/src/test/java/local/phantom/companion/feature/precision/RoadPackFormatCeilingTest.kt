package local.phantom.companion.feature.precision

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Сторож стелі формату пакета — окремий від [PackRecipeVerdictTest], який
 * стереже лише КОСМЕТИЧНИЙ вирок ("старий рецепт"), показаний ПІСЛЯ успішного
 * імпорту. Цей тест стереже саму МОЖЛИВІСТЬ імпорту.
 *
 * ЩО ЦЕ СТЕРЕЖЕ. `RoadPackImporter.importFromPath` сьогодні читає `ways` за
 * фіксованою формою (`id, name, oneway, pts[, tags]`) і жодного разу не
 * питає `PRAGMA user_version`, перш ніж повірити цій формі, — версія лягає в
 * [RoadPackImporter.Result.Success.formatVersion] лише ПІСЛЯ того, як рядки
 * вже прочитані. Поки `ways` не змінюється (v1→v3: v2 додав `tags`, v3 додав
 * окремі `stops`/`rails`/`routes`/`meta` — саму `ways` жодного разу), це не
 * шкодить. Але жодного запобіжника нема: якщо рецепт колись перепише ФОРМУ
 * `ways`, застосунок мовчки повірить, що розуміє пакет, якого не перевіряв, —
 * і або впаде на сирому винятку SQLite, або (гірше) хибно розбере рядки.
 *
 * [RoadPackImporter.MAX_KNOWN_PACK_FORMAT] — межа, звірена рукою на 5 вересня
 * 2026: усе до v3 включно перевірено, `ways` та сама. Пакет ПОНАД цю межу
 * мусить відмовитись ГОЛОСНО, з поясненням і порадою «онови застосунок», а не
 * мовчки прочитатись, як старий формат.
 */
@RunWith(RobolectricTestRunner::class)
class RoadPackFormatCeilingTest {

    private fun ctx() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Пече мінімальний, але СПРАВЖНІЙ пакет: та сама форма `ways`, що й
     *  mesh_bake.py, з довільною версією рецепта. */
    private fun bakePack(file: File, userVersion: Int) {
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL(
            "CREATE TABLE ways(cell INTEGER NOT NULL, id INTEGER NOT NULL, name TEXT, " +
                "oneway INTEGER NOT NULL, pts TEXT NOT NULL, tags TEXT, PRIMARY KEY(cell, id))",
        )
        db.execSQL(
            "INSERT INTO ways(cell,id,name,oneway,pts,tags) VALUES (?,?,?,?,?,?)",
            arrayOf<Any?>(
                2522L, 1001L, "вул. Тестова", 0L,
                "50.450000,30.520000;50.451000,30.529000", "highway=primary|maxspeed=50",
            ),
        )
        db.execSQL("PRAGMA user_version = $userVersion")
        db.close()
    }

    @Test
    fun `реальна версія kyiv-v3 (3) з дисків власника мусить пройти`() = runTest {
        val fixture = File.createTempFile("phantom_road_mesh_v3", ".db")
        assertTrue(fixture.delete())
        bakePack(fixture, userVersion = 3)

        val result = RoadPackImporter.importFromFile(ctx(), fixture, deleteAfter = true)

        val success = result as? RoadPackImporter.Result.Success
            ?: throw AssertionError("пакет v3 мусить прийнятись, отримано: $result")
        assertEquals(1, success.wayCount)
        assertEquals(3, success.formatVersion)
    }

    @Test
    fun `пакет, спечений рецептом новішим за підтримуваний, відмовляється ГОЛОСНО`() = runTest {
        val tooNew = RoadPackImporter.MAX_KNOWN_PACK_FORMAT + 1
        val fixture = File.createTempFile("phantom_road_mesh_future", ".db")
        assertTrue(fixture.delete())
        bakePack(fixture, userVersion = tooNew)

        val result = RoadPackImporter.importFromFile(ctx(), fixture, deleteAfter = true)

        val failure = result as? RoadPackImporter.Result.Failure
            ?: throw AssertionError(
                "пакет v$tooNew новіший за перевірену межу " +
                    "(${RoadPackImporter.MAX_KNOWN_PACK_FORMAT}) — застосунок не сміє мовчки " +
                    "повірити, що розуміє його; отримано: $result",
            )
        assertTrue(
            "повідомлення мусить називати причину, а не бути сирим винятком: ${failure.message}",
            failure.message.contains(tooNew.toString()) &&
                failure.message.contains(RoadPackImporter.MAX_KNOWN_PACK_FORMAT.toString()),
        )
    }
}
