package local.phantom.companion.feature.stream.report

import local.phantom.companion.feature.stream.ui.report.ReportBlock
import local.phantom.companion.feature.stream.ui.report.ReportKind
import local.phantom.companion.feature.stream.ui.report.ReportPayload
import local.phantom.companion.feature.stream.ui.report.ReportTone
import local.phantom.companion.feature.stream.ui.report.ReportWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Розбір payload. Головна властивість, яку тут перевіряють, — **мʼякість**:
 * один зіпсований блок не має коштувати оператору всього документа.
 */
class ReportPayloadTest {

    @Test
    fun `повний payload розбирається у документ із розділами`() {
        val doc = ReportPayload.parse(
            """
            {
              "id": "trip-2026-07-21",
              "kind": "trip",
              "kicker": "звіт про поїздку",
              "title": "Хрещатик → Бориспіль",
              "period": "21 липня 2026 · 08:12–09:04",
              "lede": "Дорога зайняла на 9 хвилин більше обіцяного.",
              "headline": [
                {"label":"Відстань","value":"38,4","unit":"км"},
                {"label":"У дорозі","value":"52","unit":"хв","delta":"+9 хв","delta_good":false}
              ],
              "spark": [0, 24, 61, 88, 92, 44],
              "sections": [
                {
                  "id":"route", "kicker":"маршрут", "title":"Як пройшла дорога",
                  "blocks":[
                    {"block":"prose","text":"Виїзд із Печерська о 08:12."},
                    {"block":"speed-profile","limit_kmh":50,
                     "samples":[{"t":"08:12","kmh":0},{"t":"08:20","kmh":62}]}
                  ]
                }
              ],
              "sources": ["GNSS 1 Гц", "OBD-II"],
              "methodology": ["Швидкість — медіана за 5 с."],
              "generated_at": "21.07.2026 09:10"
            }
            """.trimIndent(),
        )

        assertEquals("trip-2026-07-21", doc.id)
        assertEquals(ReportKind.TRIP, doc.kind)
        assertEquals("Хрещатик → Бориспіль", doc.title)
        assertEquals(2, doc.headline.size)
        assertEquals(false, doc.headline[1].deltaGood)
        assertEquals(6, doc.spark.size)
        assertEquals(1, doc.sections.size)
        assertEquals("route", doc.sections[0].id)
        assertEquals(2, doc.sections[0].blocks.size)
        assertTrue(doc.sections[0].blocks[1] is ReportBlock.SpeedProfile)
        assertEquals(listOf("GNSS 1 Гц", "OBD-II"), doc.sources)
        assertEquals(false, doc.isEmpty)
    }

    @Test
    fun `невідомий тип блока випадає, решта розділу виживає`() {
        val doc = ReportPayload.parse(
            """
            {"title":"Т","sections":[{"title":"Р","blocks":[
              {"block":"hologram","wow":1},
              {"block":"prose","text":"вцілів"}
            ]}]}
            """.trimIndent(),
        )
        assertEquals(1, doc.sections[0].blocks.size)
        assertEquals("вцілів", (doc.sections[0].blocks[0] as ReportBlock.Prose).text)
    }

    @Test
    fun `рвана теплова матриця відкидається — вона малюється, але бреше`() {
        val doc = ReportPayload.parse(
            """
            {"title":"Т","sections":[{"title":"Р","blocks":[
              {"block":"heat-matrix","values":[[1,2,3],[4,5]]},
              {"block":"heat-matrix","key":"ok","values":[[1,2],[3,4]]}
            ]}]}
            """.trimIndent(),
        )
        assertEquals(1, doc.sections[0].blocks.size)
        assertEquals(2, (doc.sections[0].blocks[0] as ReportBlock.HeatMatrix).values.size)
    }

    @Test
    fun `ряд з однієї точки не потрапляє в лінійну діаграму`() {
        val doc = ReportPayload.parse(
            """
            {"title":"Т","sections":[{"title":"Р","blocks":[
              {"block":"line-series","series":[{"name":"WOG","points":[54.9]}]}
            ]}]}
            """.trimIndent(),
        )
        assertTrue(doc.sections.isEmpty())
    }

    @Test
    fun `колесо з невідомою позицією відсіюється`() {
        val blocks = ReportPayload.parseBlocks(
            ReportPayload.json.parseToJsonElement(
                """
                [{"block":"tires","items":[
                  {"pos":"fl","bar":2.3},{"pos":"spare","bar":2.0},{"pos":"rr","bar":2.1}
                ]}]
                """.trimIndent(),
            ) as kotlinx.serialization.json.JsonArray,
        )
        val tires = blocks.single() as ReportBlock.Tires
        assertEquals(2, tires.items.size)
    }

    @Test
    fun `однакові ключі блоків розводяться — інакше злипнеться стан гідратації`() {
        val doc = ReportPayload.parse(
            """
            {"title":"Т","sections":[{"title":"Р","blocks":[
              {"block":"kpis","items":[{"label":"a","value":"1"}]},
              {"block":"kpis","items":[{"label":"b","value":"2"}]}
            ]}]}
            """.trimIndent(),
        )
        val keys = doc.sections[0].blocks.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `розділ без жодного вцілілого блока і без назви випадає`() {
        val doc = ReportPayload.parse(
            """
            {"title":"Т","sections":[
              {"blocks":[{"block":"prose","text":""}]},
              {"title":"Живий","blocks":[{"block":"prose","text":"є"}]}
            ]}
            """.trimIndent(),
        )
        assertEquals(1, doc.sections.size)
        assertEquals("Живий", doc.sections[0].title)
    }

    @Test
    fun `нерозбірливий payload дає порожній документ, а не виняток`() {
        val doc = ReportPayload.parse("{ це не json")
        assertEquals("", doc.title)
        assertTrue(doc.isEmpty)
        assertTrue(doc.sections.isEmpty())
    }

    @Test
    fun `порожній документ визнає себе порожнім`() {
        val doc = ReportPayload.parse("""{"title":"Тиждень","period":"14–20 липня"}""")
        assertTrue(doc.isEmpty)
        assertEquals("Тиждень", doc.title)
    }

    @Test
    fun `невідома родина звіту падає в generic, а не валить розбір`() {
        val doc = ReportPayload.parse("""{"title":"Т","kind":"звіт-про-все"}""")
        assertEquals(ReportKind.GENERIC, doc.kind)
    }

    @Test
    fun `більше трьох чисел у шапці не пускаємо в картку-передачу`() {
        val doc = ReportPayload.parse(
            """
            {"title":"Т","headline":[
              {"label":"a","value":"1"},{"label":"b","value":"2"},
              {"label":"c","value":"3"},{"label":"d","value":"4"}
            ]}
            """.trimIndent(),
        )
        assertEquals(3, doc.headline.size)
    }

    @Test
    fun `рядок довший за шапку обрізається по колонках таблиці`() {
        val doc = ReportPayload.parse(
            """
            {"title":"Т","sections":[{"title":"Р","blocks":[
              {"block":"data-table",
               "columns":[{"label":"Дата"},{"label":"Літри","numeric":true}],
               "rows":[["18.07","41,2","зайве"],["19.07","38,0"]]}
            ]}]}
            """.trimIndent(),
        )
        val table = doc.sections[0].blocks[0] as ReportBlock.DataTable
        assertEquals(listOf("18.07", "41,2"), table.rows[0])
        assertEquals(2, table.rows[1].size)
    }

    @Test
    fun `важкі блоки резервують висоту, легкі — ні`() {
        val doc = ReportPayload.parse(
            """
            {"title":"Т","sections":[{"title":"Р","blocks":[
              {"block":"prose","text":"текст"},
              {"block":"route-trace","points":[{"lat":50.45,"lon":30.52},{"lat":50.46,"lon":30.53}]}
            ]}]}
            """.trimIndent(),
        )
        val blocks = doc.sections[0].blocks
        assertEquals(ReportWeight.LIGHT, blocks[0].weight)
        assertEquals(0, blocks[0].reservedHeightDp)
        assertEquals(ReportWeight.HEAVY, blocks[1].weight)
        assertTrue(blocks[1].reservedHeightDp > 100)
    }

    @Test
    fun `тон читається з дротового імені`() {
        val doc = ReportPayload.parse(
            """
            {"title":"Т","sections":[{"title":"Р","blocks":[
              {"block":"insight","text":"розбіжність","tone":"crit"}
            ]}]}
            """.trimIndent(),
        )
        assertEquals(ReportTone.CRIT, (doc.sections[0].blocks[0] as ReportBlock.Insight).tone)
    }

    @Test
    fun `розділ без явного id отримує якір із назви`() {
        val doc = ReportPayload.parse(
            """
            {"title":"Т","sections":[{"title":"Паливо та економіка",
              "blocks":[{"block":"prose","text":"є"}]}]}
            """.trimIndent(),
        )
        assertEquals("паливо-та-економіка", doc.sections[0].id)
    }
}
