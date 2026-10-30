package local.phantom.companion.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Драбина телефона й ПК читають один контракт —
 * `phantom-os/src/backend/symbiote/contract.json`. Досі дієслова, сфери й
 * сходинки набирались руками двічі, і розійтися їм не заважало ніщо.
 */
class BodyLadderContractTest {

    private fun contract() = run {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val root = dir ?: fail("корінь дерева не знайдено") as Nothing
        val file = File(root.parentFile, "phantom-os/src/backend/symbiote/contract.json")
        assertTrue("немає ${file.path}", file.isFile)
        Json.parseToJsonElement(file.readText()).jsonObject
    }

    @Test
    fun `сфери й сходинки ті самі, що в контракті`() {
        val c = contract()
        assertEquals(
            c["spheres"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
            BodyLadder.Sphere.entries.associate { it.key to it.title },
        )
        assertEquals(
            c["levels"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
            BodyLadder.Rung.entries.associate { it.key to it.title },
        )
    }

    @Test
    fun `кожне телефонне дієслово контракту є в драбині з тією ж сферою й видом`() {
        val verbs = contract()["verbs"]!!.jsonObject
            .filterValues { it.jsonObject["body"]!!.jsonPrimitive.content == "phone" }
        assertEquals(verbs.keys, BodyLadder.RULES.keys)
        verbs.forEach { (verb, raw) ->
            val spec = raw.jsonObject
            val rule = BodyLadder.RULES.getValue(verb)
            assertEquals(verb, spec["sphere"]!!.jsonPrimitive.content, rule.sphere?.key ?: "")
            assertEquals(verb, spec["kind"]!!.jsonPrimitive.content, rule.kind.name.lowercase())
            assertEquals(
                verb,
                spec["risk"]!!.jsonPrimitive.content == "irreversible",
                rule.irreversible,
            )
        }
    }
}
