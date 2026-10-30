package local.phantom.companion.core.data.poi

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class NationalCorpusMirrorTest {

    @Test
    fun `види довідника країни збігаються зі скриптом, що його пече`() {
        val script = File("../scripts/bake_country_places.py").readText()
        val labelled = Regex("""LABELLED = \{(.*?)\n\}""", RegexOption.DOT_MATCHES_ALL).find(script)!!.groupValues[1]
        val shops = Regex("""SHOP_VALUES = \{(.*?)\}""", RegexOption.DOT_MATCHES_ALL).find(script)!!.groupValues[1]
        val baked = Regex("""\("\w+", "(\w+)"\): """").findAll(labelled).map { it.groupValues[1] }.toSet() +
            Regex(""""(\w+)"""").findAll(shops).map { it.groupValues[1] }.toSet()
        assertEquals(baked.sorted(), NationalCorpus.KINDS.sorted())
    }
}
