package local.phantom.companion.copy

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Правила копірайту: жодної прози понад 70 символів поза явно дозволеним списком (en, uk, cs). */
class ProseLengthCopyTest {

    @Test
    fun `рядки інтерфейсу en, uk і cs не перевищують 70 символів без дозволу`() {
        val files = File("..").listFiles().orEmpty()
            .map { File(it, "src/main/res") }
            .filter { it.isDirectory }
            .flatMap { res -> LOCALES.mapNotNull { File(res, it).takeIf(File::isDirectory) } }
            .flatMap { dir -> dir.listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }.orEmpty().toList() }
        assertTrue("рядків не знайдено — сторож дивиться в порожнечу", files.size > 20)
        val hits = files.flatMap { file ->
            val xml = COMMENT.replace(file.readText(), "")
            val mod = "${file.parentFile.parentFile.parentFile.parentFile.parentFile.name}/${file.parentFile.name}/${file.name}"
            val stringHits = STRING_TAG.findAll(xml)
                .filterNot { "translatable=\"false\"" in it.groupValues[1] }
                .mapNotNull { m ->
                    val key = NAME_ATTR.find(m.groupValues[1])?.groupValues?.get(1) ?: return@mapNotNull null
                    val text = m.groupValues[2]
                    if (text.length > MAX_LEN && key !in ALLOW) "$mod $key (${text.length})" else null
                }
                .toList()
            val pluralHits = GROUP_TAG.findAll(xml)
                .filterNot { "translatable=\"false\"" in it.groupValues[1] }
                .flatMap { g ->
                    val key = NAME_ATTR.find(g.groupValues[1])?.groupValues?.get(1) ?: return@flatMap emptySequence()
                    ITEM_TAG.findAll(g.groupValues[2]).mapNotNull { item ->
                        val text = item.groupValues[1]
                        if (text.length > MAX_LEN && key !in ALLOW) "$mod $key (${text.length})" else null
                    }
                }
                .toList()
            stringHits + pluralHits
        }
        assertEquals(emptyList<String>(), hits)
    }

    private companion object {
        const val MAX_LEN = 70
        val LOCALES = listOf("values", "values-uk", "values-cs")
        val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        val NAME_ATTR = Regex("""\bname="([^"]+)"""")
        val STRING_TAG = Regex("""<string\s([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val GROUP_TAG = Regex("""<(?:plurals|string-array)\b([^>]*)>(.*?)</(?:plurals|string-array)>""", RegexOption.DOT_MATCHES_ALL)
        val ITEM_TAG = Regex("""<item\b[^>]*>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
        val ALLOW = setOf(
            "mapl_b_brief_system", // model prompt, not UI
            "accessibility_description", // disclosure
            "whisper_overlay_rationale_body", // disclosure
            "perm_sms_body", // disclosure
            "voice_no_permission", // disclosure
            "perm_blocked_body", // disclosure
            "sol_nearby_why", // disclosure
            "sol_nearby_needs", // disclosure
            "lic_map_body", // legal
            "lic_bundled_note", // legal
            "license_signature_meaning", // legal
            "maplibre_mapActionDescription", // a11y
            "peer_card_dirs", // a11y
            "sol_backup_why", // seed
            "sol_backup_warning", // seed
            "sol_restore_warning", // seed
        )
    }
}
