package local.phantom.companion.packaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * СТЕЛЯ ABI.
 *
 * До 0.10.21 tasks-genai несла один `libllm_inference_engine_jni.so` під
 * arm64-v8a. З 0.10.22 їх чотири, і виміряно на розпакованих AAR 0.10.27:
 * arm64-v8a 12 842 184, x86_64 15 053 104, x86 17 215 648, armeabi-v7a
 * 8 313 108 байт. Дві ABI, яких тут нема, важать 24,35 МіБ.
 *
 * Стелю неможливо поставити вдруге: AGP відмовляє конфігурувати проєкт, якщо
 * поряд зі `splits.abi` зʼявиться `defaultConfig.ndk.abiFilters`. Тож увесь
 * захист — це `include` і `isUniversalApk`, дві властивості в одному файлі,
 * які ніщо, крім цього тесту, не тримає.
 *
 * Список звужувати теж не можна: `scripts/release.sh` віддає людям обидві
 * збірки (arm64 + x86_64), тож викидання x86_64 забрало б у релізу половину.
 */
class AbiCeilingTest {

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
    fun `перелік ABI лишається рівно тим, що їде людям`() {
        val include = Regex("""include\(([^)]*)\)""")
            .findAll(buildFile)
            .map { it.groupValues[1] }
            .firstOrNull { it.contains("arm64-v8a") }
            ?: error("у app/build.gradle.kts не знайдено splits.abi.include з arm64-v8a")

        val abis = Regex("\"([^\"]+)\"").findAll(include).map { it.groupValues[1] }.toList()
        assertEquals(
            "splits.abi.include змінився. Це ЄДИНА стеля ABI у проєкті — abiFilters " +
                "поряд із splits AGP не приймає. Кожна зайва ABI тягне ще один " +
                "libllm_inference_engine_jni.so (x86 16,42 МіБ, armeabi-v7a 7,93 МіБ), " +
                "а кожна прибрана ламає release.sh, який віддає arm64 + x86_64.",
            listOf("arm64-v8a", "x86_64"),
            abis,
        )
    }

    @Test
    fun `AAB має ту саму стелю, що й APK`() {
        val filters = Regex("""ndk\.abiFilters \+= listOf\(([^)]*)\)""").find(buildFile)?.groupValues?.get(1)
            ?: error("bundle без ndk.abiFilters: AAB повезе всі ABI з бібліотек")
        val include = Regex("""include\(([^)]*)\)""").findAll(buildFile).map { it.groupValues[1] }
            .first { it.contains("arm64-v8a") }
        val abis = { s: String -> Regex("\"([^\"]+)\"").findAll(s).map { it.groupValues[1] }.toList() }
        assertEquals(abis(include), abis(filters))
        assertTrue(
            "splits мають вимикатись лише для bundle",
            Regex("""isEnable\s*=\s*!bundleBuild""").containsMatchIn(buildFile),
        )
    }

    /**
     * Універсальний APK склеїв би всі ABI назад в один файл — рівно те, від
     * чого `include` вище й боронить, тільки іншим шляхом і без жодного
     * попередження в збірці.
     */
    @Test
    fun `універсальний APK лишається вимкненим`() {
        assertTrue(
            "isUniversalApk став true — усі ABI з include знову в одному APK",
            Regex("""isUniversalApk\s*=\s*false""").containsMatchIn(buildFile),
        )
    }
}
