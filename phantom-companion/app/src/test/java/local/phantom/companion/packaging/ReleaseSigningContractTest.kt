package local.phantom.companion.packaging

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ДОГОВІР ПРО ПІДПИС РЕЛІЗУ.
 *
 * Заслін «немає keystore.properties — релізу немає» стояв, щоб свіжий клон не
 * випустив APK, підписаний відлагоджувальним ключем із паролем «phantom», що
 * лежить у цьому ж репозиторії. Він і далі мусить стояти.
 *
 * Але 09.09 виявилось, чого він НЕ вберіг: релізна збірка не влазить у ноутбук
 * (Gradle просить ~7 ГБ), а релізний ключ не має покидати ноутбука — і замість
 * чесного непідписаного артефакта з хмари на роздачу поїхала 690-МБ DEBUG-збірка,
 * підписана рівно тим ключем, від якого заслін боронив. Заборона, що лишає
 * гіршу дорогу відкритою, робить систему менш безпечною, а не більш.
 *
 * Тому договір тепер із трьох частин, і кожна тут перевірена:
 *   1. без ключа й без явного дозволу реліз НЕ збирається (як і було);
 *   2. `-Pphantom.unsignedRelease=true` дає артефакт БЕЗ ПІДПИСУ ВЗАГАЛІ —
 *      явним `signingConfig = null`, а не «просто ключа не знайшлось»;
 *   3. така збірка кричить про себе в лог.
 *
 * Читаємо файл складання текстом — той самий прийом, що в [AbiCeilingTest]:
 * інакше цих трьох властивостей не видно нізвідки, окрім самого релізу.
 */
class ReleaseSigningContractTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("не знайшов корінь дерева (каталог feature-stream)")
    }

    private val buildFile: String by lazy {
        val f = File(repoRoot(), "app/build.gradle.kts")
        check(f.isFile) { "не знайшов app/build.gradle.kts — дерево переїхало" }
        f.readText()
    }

    @Test
    fun `реліз без ключа не збирається, доки не сказано явно`() {
        assertTrue(
            "зник заслін на releaseKeystore == null — свіжий клон знову випустить " +
                "APK, підписаний ключем із паролем «phantom» із цього ж репозиторію",
            Regex("""emitsReleaseArtifact && releaseKeystore == null && !unsignedRelease""")
                .containsMatchIn(buildFile),
        )
    }

    @Test
    fun `непідписаний реліз справді лишається без підпису`() {
        assertTrue(
            "у buildTypes.release немає явного signingConfig = null під unsignedRelease. " +
                "Без нього артефакт підхопив би наявний ключ, і «непідписана збірка для " +
                "хмари» мовчки поїхала б підписаною",
            Regex("""if \(unsignedRelease\)\s*\{\s*signingConfig = null""")
                .containsMatchIn(buildFile),
        )
    }

    @Test
    fun `вимикач читається лише з командного рядка`() {
        assertTrue(
            "unsignedRelease почав читатись із local.properties — вимикач осів би на " +
                "машині, і «непідписано» стало б тихою властивістю ноутбука",
            !Regex("""unsignedRelease[\s\S]{0,200}?localProps\.getProperty""")
                .containsMatchIn(buildFile),
        )
    }

    @Test
    fun `непідписана збірка каже про себе вголос`() {
        assertTrue(
            "зник logger.lifecycle про unsignedRelease — непідписаний APK неможливо " +
                "відрізнити від готового, читаючи лог збірки",
            Regex("""emitsReleaseArtifact && unsignedRelease[\s\S]{0,400}?logger\.lifecycle""")
                .containsMatchIn(buildFile),
        )
    }
}
