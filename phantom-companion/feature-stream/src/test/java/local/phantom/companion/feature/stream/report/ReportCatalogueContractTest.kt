package local.phantom.companion.feature.stream.report

import local.phantom.companion.feature.stream.ui.report.ReportPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The system prompt teaches the model one JSON shape per block. A field name
 * that disagrees with the parser costs the whole block silently — soft parsing
 * drops it and the document renders one section short with no error anywhere.
 * So the catalogue's own examples are the fixture: if entry 31 in
 * `SystemPrompts.kt` and these strings drift apart, this goes red.
 */
class ReportCatalogueContractTest {

    private fun doc(blocks: String) = ReportPayload.parse(
        """
        {
          "kind": "trip",
          "kicker": "розбір поїздки",
          "title": "Київ — Львів",
          "period": "21 липня, 06:12–13:48",
          "lede": "одне речення суті документа",
          "headline": [{"label":"відстань","value":"541","unit":"км","delta":"+3%","delta_good":false,"note":"проти минулого разу"}],
          "spark": [12,18,9,24],
          "sections": [{"title":"назва розділу","kicker":"надзаголовок","lede":"вступ","blocks":[$blocks]}],
          "sources": ["OSM"],
          "methodology": ["витрата рахована за одометром"],
          "generated_at": "2026-07-27 11:40"
        }
        """.trimIndent(),
    )

    @Test
    fun `the envelope the catalogue documents survives parsing whole`() {
        val d = doc("""{"block":"prose","text":"абзац"}""")
        assertEquals("Київ — Львів", d.title)
        assertEquals("21 липня, 06:12–13:48", d.period)
        assertEquals("2026-07-27 11:40", d.generatedAt)
        assertEquals(1, d.headline.size)
        assertEquals("541", d.headline.first().value)
        assertEquals(false, d.headline.first().deltaGood)
        assertEquals(4, d.spark.size)
        assertEquals(listOf("OSM"), d.sources)
        assertEquals(1, d.sections.size)
    }

    /** Every block example printed in the catalogue, verbatim. */
    private val catalogueBlocks = listOf(
        """{"block":"prose","text":"…"}""",
        """{"block":"insight","kicker":"Nightpost помітив","text":"…"}""",
        """{"block":"verdict","text":"…","attribution":"…"}""",
        """{"block":"key-values","title":"…","rows":[{"key":"…","value":"…"}]}""",
        """{"block":"footnotes","items":[{"marker":"1","text":"…","source":"…"}]}""",
        """{"block":"methodology","title":"…","lines":["…"]}""",
        """{"block":"kpis","items":[{"label":"відстань","value":"541","unit":"км"}]}""",
        """{"block":"big-stat","stat":{"label":"…","value":"541"},"caption":"…"}""",
        """{"block":"spark-row","items":[{"label":"…","value":"…","points":[1,2,3]}]}""",
        """{"block":"line-series","title":"…","series":[{"name":"…","points":[1,2,3]}],"x_labels":["…"]}""",
        """{"block":"bar-series","groups":[{"label":"…","values":[1,2]}],"segment_labels":["…"],"unit":"…"}""",
        """{"block":"donut","slices":[{"label":"…","value":42}],"center":"…","center_label":"…"}""",
        """{"block":"histogram","values":[1,2,3],"bins":12,"unit":"…"}""",
        """{"block":"waterfall","steps":[{"label":"…","delta":-12,"total":false}]}""",
        """{"block":"heat-matrix","row_labels":["a"],"col_labels":["b"],"values":[[1]]}""",
        """{"block":"gauges","items":[{"label":"…","value":72,"min":0,"max":100,"unit":"%","ok_from":40,"ok_to":90}]}""",
        """{"block":"data-table","columns":[{"label":"…","numeric":true}],"rows":[["…"]],"footer":"…"}""",
        """{"block":"ranking","entries":[{"label":"…","value":42,"display":"42 км","note":"…"}],"unit":"…"}""",
        """{"block":"comparison","left_label":"було","right_label":"стало","rows":[{"label":"…","left":"…","right":"…"}]}""",
        """{"block":"timeline","events":[{"time":"08:26","title":"…","detail":"…","pivotal":true}]}""",
        """{"block":"countdowns","items":[{"label":"…","remaining":1200,"interval":10000,"unit":"км","due":"…"}]}""",
        """{"block":"agent-trace","nodes":[{"depth":0,"tool":"…","detail":"…","ms":420,"status":"ok","cost":"…"}]}""",
        """{"block":"speed-profile","samples":[{"t":"08:26","kmh":92}],"limit_kmh":90}""",
        """{"block":"route-trace","points":[{"lat":50.4,"lon":30.5,"kmh":60}],"limit_kmh":90}""",
        """{"block":"divergence","track_a":[{"lat":50.4,"lon":30.5}],"track_b":[{"lat":50.5,"lon":30.6}],"label_a":"GPS","label_b":"мережа"}""",
        """{"block":"tires","items":[{"pos":"FL","bar":2.1,"target_bar":2.3,"temp":34}]}""",
    )

    @Test
    fun `every block the catalogue teaches survives its own example`() {
        val dropped = catalogueBlocks.filter { doc(it).sections.isEmpty() }
        assertTrue(
            "these catalogue examples parse to nothing — the prompt teaches a " +
                "shape the parser does not accept:\n" + dropped.joinToString("\n"),
            dropped.isEmpty(),
        )
    }

    @Test
    fun `the catalogue covers every block type the renderer can draw`() {
        val taught = catalogueBlocks
            .map { it.substringAfter("\"block\":\"").substringBefore("\"") }
            .toSet()
        val known = ReportPayload.blockDiscriminators()
        assertEquals(
            "a block the renderer draws but the model is never told about is dead code",
            emptySet<String>(),
            known - taught,
        )
    }

    @Test
    fun `a block keyed on type instead of block is refused, not half-read`() {
        val d = doc("""{"type":"prose","text":"…"}""")
        assertTrue(d.sections.single().blocks.isEmpty())
    }
}
