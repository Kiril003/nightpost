package local.phantom.companion.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Тривога про підмінений ключ живе в одному місці — `PeerRoster.upsert` — і
 * вона варта рівно стільки, скільки шляхів у реєстр її оминають: нуль.
 *
 * PeerMesh не збудувати в JVM-тесті (Keystore, EncryptedSharedPreferences),
 * тож правило пінується по джерелу: сховище пише ключі лише через правила
 * реєстру, а меш ніколи не переписує ключ у рядку руками. Обидва входи з
 * дроту доведено кадрами в core-net (PeerKeyIntakeTest); тут — що між кадром
 * і реєстром немає бічних дверей.
 *
 * Читає чужі файли як сирий ввід-вивід — невидимо для up-to-date Gradle.
 * Ганяти з `--rerun-tasks`, як робить scripts/verify.sh.
 */
class PeerKeyIntakeFirewallTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "core-net").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with core-net/ not found") as Nothing
    }

    private fun source(path: String): String {
        val file = File(repoRoot(), path)
        assertTrue("$path не знайдено — перевірка вже нічого не боронить", file.isFile)
        return file.readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\\n]*"), "")
    }

    private val store = "app/src/main/java/local/phantom/companion/data/PeerRosterStore.kt"
    private val mesh = "app/src/main/java/local/phantom/companion/data/PeerMesh.kt"

    @Test
    fun `the roster store writes keys through the roster's rule only`() {
        val text = source(store)
        assertFalse("сховище само нічого не копіює — правила в PeerRoster", text.contains(".copy("))
        assertFalse(
            "ключ у сховищі не присвоюється напряму",
            Regex("""\b(dhPubKey|pubKey)\s*=""").containsMatchIn(text),
        )
        // Кожен write(...) крім оголошення несе значення, зроблене правилом
        // реєстру. Форм запису дві, і друга коштувала нам червоного релізу:
        //
        //   write(PeerRoster.markMet(...))          — правило прямо в дужках
        //   val next = PeerRoster.absorbProfile(…)  — правило спершу в змінну,
        //   write(next)                               бо між ними є перевірка
        //
        // Сторож дивився лише 120 знаків УПЕРЕД від `write(`, тож другу форму
        // читав як обхід правила. 18.09 вона зʼявилась разом із кадром PH-PRF
        // (9a3445cf) — правило було ціле, червоним був сам сторож. Ослабити
        // вікно не можна: тоді `write(будьЩо)` пройде. Тому аргумент-змінна
        // мусить бути ЗВʼЯЗАНА з PeerRoster у цьому ж файлі.
        //
        // `slot.write(` — диск, а не реєстр: його кличе лише тіло `fun write`
        // і `clear()`, і значення туди приходить уже з-під правила.
        val calls = Regex("""(?<!fun )(?<!slot\.)write\(([^)\n]*)""").findAll(text).toList()
        assertTrue("write( мусить кликатись", calls.isNotEmpty())
        for (call in calls) {
            val at = call.range.first
            val window = text.substring(at, minOf(text.length, at + 120))
            if (window.contains("PeerRoster.")) continue
            val arg = call.groupValues[1].trim()
            val boundByRule = arg.matches(Regex("""[A-Za-z_][A-Za-z0-9_]*""")) &&
                Regex("""\b(?:val|var)\s+${Regex.escape(arg)}\s*(?::[^=\n]*)?=\s*PeerRoster\.""")
                    .containsMatchIn(text)
            assertTrue(
                "запис у реєстр в обхід PeerRoster: …${window.take(80).replace("\n", " ")}",
                boundByRule,
            )
        }
    }

    @Test
    fun `the mesh never rewrites a key on a row by hand`() {
        val text = source(mesh)
        assertFalse(
            "PeerMesh поклав ключ у рядок через copy(...), оминувши тривогу",
            Regex("""copy\([^)]*\b(dhPubKey|pubKey)\s*=""").containsMatchIn(text),
        )
        assertTrue("обидва входи з дроту йдуть у roster.upsert", text.split("roster.upsert(").size >= 3)
    }
}
