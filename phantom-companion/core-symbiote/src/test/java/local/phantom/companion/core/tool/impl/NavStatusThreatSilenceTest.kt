package local.phantom.companion.core.tool.impl

import android.app.Application
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Нуль загроз» — це твердження про безпеку, і його не можна вигадувати.
 *
 * `nav.status` обіцяє моделі у власному описі «загрози коридору (камери, КПП)
 * та статус комендантської години». Проводка при цьому передавала жорсткий
 * `activeThreatsCount = 0`, якого не породив жоден скан, а інструмент
 * друкував його в `threats_count`. Модель читала число як вимір і переказувала
 * людині, що коридор чистий — людині, яка саме вирішує, чи їхати ним.
 *
 * Хибна температура — це неправда про комфорт. Це — неправда про безпеку, і
 * різниця не в тоні, а в тому, чим людина ризикує, повіривши.
 *
 * Ключова властивість: щойно «не знайшли» стає **числом**, воно робиться
 * невідрізненним від «ніхто не дивився». Тому відомий нуль і невідомість
 * мусять виглядати по-різному — і в словах, і в структурі.
 *
 * ── ЯК ЦЕ ЧЕРВОНІЄ НА СТАРОМУ КОДІ ──
 * Тест навмисне збудований на значеннях за замовчуванням, тому компілюється і
 * зі старим `activeThreatsCount: Int = 0`. Там `threats_count` = 0 присутній,
 * `threats_status` немає, а речення про неперевіреність не друкується — падають
 * усі три перевірки. Якби тест вимагав нового типу, він би не червонів, а не
 * збирався, і це нічого не доводило б.
 *
 * ── ЧОГО ЦЕЙ ТЕСТ НЕ БАЧИТЬ ──
 *  • Він не знає, чи скан загроз існує. Він стереже лише те, щоб ВІДСУТНІСТЬ
 *    скану не подавалась як результат.
 *  • Він не перевіряє інші інструменти того ж роду. Підстановка «нуль замість
 *    невідомо» — клас, а не випадок: сусідній розрахунок бала ризику досі
 *    рахує укриття як `0`, коли їхнє джерело недоступне.
 *  • Він нічого не каже про те, чи модель ПОСЛУХАЄТЬСЯ слів. Ми прибираємо
 *    підставу для хибного твердження, а не гарантуємо поведінку моделі.
 */
class NavStatusThreatSilenceTest {

    private fun ctx() = ToolContext(
        appContext = Application(),
        operatorId = "operator",
        requestId = "req_nav_threats",
        approvedPermissions = emptySet(),
    )

    /** Рівно те, що віддавала проводка: ведення активне, і більше нічого відомого. */
    private fun unscannedSnapshot() = NavTelemetrySnapshot(isGuiding = true)

    @Test
    fun unscannedCorridorIsNeverPublishedAsZeroThreats() = runTest {
        val tool = NavStatusTool(telemetryProvider = { unscannedSnapshot() })

        val result = tool.execute(buildJsonObject {}, ctx()) {}
        assertTrue("статус мусить лишатись успішним — невідомість не є помилкою", result is ToolResult.Ok)
        val ok = result as ToolResult.Ok

        assertNull(
            "модель отримала threats_count, якого ніхто не рахував: " +
                "число неможливо відрізнити від виміру, і воно читається як «коридор перевірено»",
            ok.structured?.get("threats_count"),
        )
        assertEquals(
            "відсутність скану мусить бути НАЗВАНА, а не показана порожнечею — " +
                "мовчання модель заповнює зручним припущенням",
            "не перевірено",
            ok.structured?.get("threats_status")?.jsonPrimitive?.content,
        )
        assertTrue(
            "слова для моделі мусять прямо забороняти твердження про відсутність загроз, " +
                "інакше вона скаже «чисто» з власної ввічливості. Було: ${ok.output}",
            ok.output.contains("НЕ перевірялись"),
        )
    }

    @Test
    fun uncheckedCurfewIsNotSilentlyReadAsNoCurfew() = runTest {
        val tool = NavStatusTool(telemetryProvider = { unscannedSnapshot() })

        val ok = tool.execute(buildJsonObject {}, ctx()) {} as ToolResult.Ok

        assertEquals(
            "порожній curfew_warning має два протилежні значення — «немає комендантської» і " +
                "«ніхто не питав»; без явного статусу модель обирає заспокійливе",
            "не перевірено",
            ok.structured?.get("curfew_status")?.jsonPrimitive?.content,
        )
        assertTrue(
            "і це мусить прозвучати словами. Було: ${ok.output}",
            ok.output.contains("не перевірено"),
        )
    }
}
