package local.phantom.companion.feature.onboarding.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Голос заголовка першого запуску.
 *
 * 03.09 штаб вирішив, що кожен розділ відкриває серифна корона
 * (`PhantomEditorialTitle`), бо шість заголовків `FontWeight.ExtraBold`
 * 24–28sp говорили чужим голосом. Мова форми 1.2 (03.10) прибрала серифи з
 * продукту зовсім: заголовок — роль шкали `PhantomText` (Manrope), і вся
 * ієрархія тримається шкалою, а не гарнітурою. Сторож переписаний під новий
 * контракт, а не знятий: старий ворог (саморобний жирний гротеск із
 * власним кеглем) лишається забороненим, а серифна корона стала тим самим
 * «чужим голосом», яким колись був гротеск.
 *
 * Сторож читає джерело файлом (повз up-to-date Gradle — на валі з `--rerun`)
 * і червоніє на старому коді за побудовою: він шукає саме ті шаблони, що були.
 */
class OnboardingCrownTest {

    private val screens = listOf("NameStep.kt", "OnboardingScreen.kt")

    private fun uiDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-onboarding").isDirectory) dir = dir.parentFile
        val d = File(
            dir ?: fail("repo root with feature-onboarding/ not found") as Nothing,
            "feature-onboarding/src/main/java/local/phantom/companion/feature/onboarding/ui",
        )
        assertTrue("тека екранів онбордингу не знайдена — сторож нічого не боронить", d.isDirectory)
        return d
    }

    /** Гротескний заголовок: `Text(` з `FontWeight.ExtraBold` і кеглем ≥ 22sp в одному виклику. */
    private val groteskHeading = Regex(
        """Text\((?:[^()]|\([^()]*\))*?FontWeight\.ExtraBold(?:[^()]|\([^()]*\))*?fontSize = (2[2-9]|3\d)\.sp""" +
            """|Text\((?:[^()]|\([^()]*\))*?fontSize = (2[2-9]|3\d)\.sp(?:[^()]|\([^()]*\))*?FontWeight\.ExtraBold""",
    )

    /** Заголовок на ролі шкали мови форми, а не на власному кеглі. */
    private val scaleHeading = Regex("""style = PhantomText\.(display|title1|title2)\b""")

    @Test
    fun `every onboarding screen opens with a heading on the form scale`() {
        val dir = uiDir()
        screens.forEach { name ->
            val src = File(dir, name).readText()
            assertTrue("$name: заголовок не на ролі PhantomText", scaleHeading.containsMatchIn(src))
            val stray = groteskHeading.find(src)
            assertTrue("$name: гротескний заголовок повернувся: ${stray?.value?.take(80)}", stray == null)
            assertTrue("$name: серифна корона повернулась", !src.contains("PhantomEditorial"))
        }
    }
}
