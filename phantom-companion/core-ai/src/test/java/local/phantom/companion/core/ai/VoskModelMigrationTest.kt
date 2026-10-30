package local.phantom.companion.core.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Зміна моделі слуху має дійти до телефона, на якому стара вже лежить.
 *
 * Модель не їде в APK — її тягне [HearingProvisioner] один раз і більше
 * ніколи, бо `checkPresence` каже «наявно». Тому підміна URL сама собою
 * нічого не міняє: власник, який має nano з лютого 2021, лишається з нею
 * назавжди, і всі слова, яких у її словнику немає — «дрон», «бпла»,
 * «трьохсотий», «позивний», «азимут» — так і не зʼявляться.
 *
 * Ці тести тримають три речі: стара тека рахується ВІДСУТНЬОЮ, нова —
 * наявною, і після завантаження стара не лишається лежати на диску.
 */
class VoskModelMigrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val stale = "vosk-model-small-uk-v3-nano"

    /** Розклад моделі на диску: `am/final.mdl` — те, що шукає `Model()`. */
    private fun layOutModel(root: File, dirName: String) {
        listOf("am", "conf", "graph", "ivector").forEach { File(root, "$dirName/$it").mkdirs() }
        File(root, "$dirName/am/final.mdl").writeText("acoustic")
    }

    private fun zipOf(dirName: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            listOf(
                "$dirName/am/final.mdl",
                "$dirName/conf/model.conf",
                "$dirName/graph/words.txt",
            ).forEach { path ->
                zip.putNextEntry(ZipEntry(path))
                zip.write("x".toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun managerServing(body: ByteArray, scope: CoroutineScope) = ModelManager(
        client = HttpClient(
            MockEngine {
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentLength, body.size.toString()),
                )
            }
        ),
        rootDir = tmp.root,
        scope = scope,
        // Каталог носить лише ключ; адресу дає MODELS_BASE_URL (12.09).
        modelsBaseUrl = { "https://storage.example/models" },
        // Тіло тут — підробка на кілька байтів, а не справжній архів: цей тест
        // міряє РОЗКЛАДКУ на диску, не суми. Звірку сум міряє окремий сторож.
        verifyDownloads = false,
    )

    @Test
    fun `a previous model on disk does not count as the hearing model`() {
        layOutModel(File(tmp.root, ModelType.VOSK.fileName), stale)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = managerServing(ByteArray(0), scope)

        assertNull(
            "тека попередньої моделі — це не чинна модель",
            manager.resolveVoskModelDir(),
        )
        assertFalse(
            "поки вона зветься «наявно», HearingProvisioner ніколи не потягне нову",
            manager.states.value[ModelType.VOSK]?.isPresent == true,
        )
        scope.cancel()
    }

    @Test
    fun `the shipped model is found and reported present`() {
        layOutModel(File(tmp.root, ModelType.VOSK.fileName), VOSK_MODEL_DIR)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = managerServing(ByteArray(0), scope)

        assertEquals(
            File(tmp.root, "${ModelType.VOSK.fileName}/$VOSK_MODEL_DIR").absolutePath,
            manager.resolveVoskModelDir()?.absolutePath,
        )
        assertTrue(manager.states.value[ModelType.VOSK]?.isPresent == true)
        scope.cancel()
    }

    @Test
    fun `the catalogue url unpacks into the directory presence looks for`() {
        assertEquals(
            "URL і тека мають описувати ОДНУ модель — інакше після завантаження " +
                "presence лишиться false і слух качатиметься по колу",
            "models/vosk/$VOSK_MODEL_DIR.zip",
            ModelType.VOSK.downloadUrl,
        )
    }

    @Test
    fun `downloading replaces the previous model instead of stacking beside it`() = runBlocking {
        val outer = File(tmp.root, ModelType.VOSK.fileName)
        layOutModel(outer, stale)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = managerServing(zipOf(VOSK_MODEL_DIR), scope)

        manager.startDownload(ModelType.VOSK)
        withTimeout(10_000) {
            while (manager.states.value[ModelType.VOSK]?.isPresent != true) delay(10)
        }

        assertTrue(
            "нова модель має бути на диску",
            File(outer, "$VOSK_MODEL_DIR/am/final.mdl").exists(),
        )
        assertFalse(
            "стара модель — 127 МіБ мертвої ваги; вона має піти",
            File(outer, stale).exists(),
        )
        scope.cancel()
    }
}
