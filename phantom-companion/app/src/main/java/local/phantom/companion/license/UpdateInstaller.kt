package local.phantom.companion.license

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import local.phantom.companion.core.license.AvailableBuild

/**
 * Забрати оголошену збірку й ПЕРЕДАТИ КЕРМО системному встановлювачу.
 *
 * Ми не встановлюємо нічого самі: остаточне «Встановити» тисне людина в діалозі
 * Android. Наша частина закінчується на тому, що зійшлася сума, оголошена в
 * підписаній політиці, — до того файл нікому не показується.
 */
class UpdateInstaller(
    private val app: Context,
    private val scope: CoroutineScope,
    private val link: suspend () -> String?,
    private val fetcher: UpdateFetcher = UpdateFetcher(app.cacheDir),
) {

    sealed interface Stage {
        data object Idle : Stage
        data class Fetching(val received: Long, val total: Long) : Stage
        data class Ready(val file: File) : Stage

        /** Спинилось, але завантажене лишилось: продовжити можна звідти ж. */
        data class Stalled(val noSpace: Boolean) : Stage

        /** Прийшло не те, що підписав власник ключа. Файл видалено. */
        data object Mismatch : Stage

        /**
         * Архів читається, сума зійшлась — але це НЕ наш застосунок.
         *
         * Сума доводить цілісність байтів, а не тотожність застосунку. Віддати
         * такий файл системному установнику означає, що Android поставить ДРУГУ
         * програму поруч, а не оновить нашу, — і людина побачить дві іконки
         * замість оновлення, без жодного слова про те, що сталось.
         *
         * [samePackage] розрізняє дві різні біди: чужий пакет (не наш
         * застосунок узагалі) і наш пакет, підписаний іншим ключем (спроба
         * підміни або збірка з чужого дерева).
         */
        data class WrongApp(val samePackage: Boolean) : Stage
    }

    private val _stage = MutableStateFlow<Stage>(Stage.Idle)
    val stage: StateFlow<Stage> = _stage.asStateFlow()

    private var job: Job? = null

    fun start(build: AvailableBuild) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { run(build) }
    }

    /** Пауза, не скасування: байти на диску переживуть і вихід із застосунку. */
    fun pause() {
        job?.cancel()
        job = null
        _stage.value = Stage.Idle
    }

    fun forget() {
        pause()
        fetcher.forget()
    }

    /**
     * Останній заслін перед тим, як кермо піде системі.
     *
     * `UpdateFetcher` звіряє SHA-256 із підписаної політики — це цілісність:
     * байти ті, що оголосив власник ключа. Але цілісність не відповідає на
     * питання «а це взагалі МИ?». Архів із чужим `packageName` встановиться
     * поруч другою програмою; архів із нашим пакетом, але чужим підписом,
     * Android відхилить сам — уже після того, як людина натисне «Встановити»,
     * і поясненням буде системна помилка, а не наше слово.
     *
     * Тому тут читаються дві речі з самого архіву: ідентифікатор пакета й
     * підписант, і обидва звіряються з нашими власними. Не збіглось —
     * повертаємо null і кажемо про це на екрані.
     *
     * Симптом, з якого це почалось: власник повідомив, що оновлення «створює
     * новий застосунок замість оновлення». Головна причина була в іншому —
     * у нього стояла debug-збірка з суфіксом `.debug`, тобто для Android
     * інший застосунок, — але оновлювач, який віддає будь-який APK не питаючи,
     * зробив би те саме й без суфікса.
     */
    fun installIntent(file: File): Intent? {
        val refusal = whyNotOurs(file)
        if (refusal != null) {
            _stage.value = refusal
            return null
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** null — архів наш. Інакше причина відмови. */
    private fun whyNotOurs(file: File): Stage.WrongApp? {
        val pm = app.packageManager
        val archive = runCatching {
            pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrNull() ?: return Stage.WrongApp(samePackage = false)

        if (archive.packageName != app.packageName) return Stage.WrongApp(samePackage = false)

        val ours = runCatching {
            pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
        }.getOrNull() ?: return Stage.WrongApp(samePackage = true)

        return if (sameSigner(archive.signingInfo, ours)) null else Stage.WrongApp(samePackage = true)
    }

    /**
     * Порівнюємо НАБОРИ сертифікатів, а не перший-ліпший: у застосунку з
     * поворотом ключа їх законно кілька, і рівність множин — єдина відповідь,
     * що не бреше ні в бік «чуже», ні в бік «своє».
     */
    private fun sameSigner(archive: SigningInfo?, ours: SigningInfo?): Boolean {
        val a = archive?.certificates()?.toSet() ?: return false
        val b = ours?.certificates()?.toSet() ?: return false
        return a.isNotEmpty() && a == b
    }

    private fun SigningInfo.certificates(): List<String> =
        (if (hasMultipleSigners()) apkContentsSigners else signingCertificateHistory)
            .orEmpty()
            .map { it.toCharsString() }

    private suspend fun run(build: AvailableBuild) {
        fetcher.forget(keep = build)
        _stage.value = Stage.Fetching(0, build.sizeBytes)
        val url = link()
        if (url == null) {
            _stage.value = Stage.Stalled(noSpace = false)
            return
        }
        var result = attempt(build, url)
        // Одна ТИХА переспроба: перша невдача суми може бути диском чи обірваним
        // проксі, друга така сама — вже факт про джерело, і саме його варто
        // сказати вголос.
        if (result.isMismatch()) result = attempt(build, url)
        _stage.value = when {
            result is UpdateFetcher.Result.Ready -> Stage.Ready(result.file)
            result.isMismatch() -> Stage.Mismatch
            else -> Stage.Stalled(
                noSpace = (result as UpdateFetcher.Result.Failed).reason ==
                    UpdateFetcher.Reason.Storage,
            )
        }
    }

    private suspend fun attempt(build: AvailableBuild, url: String): UpdateFetcher.Result =
        fetcher.fetch(build, url) { received ->
            _stage.value = Stage.Fetching(received, build.sizeBytes)
        }

    private fun UpdateFetcher.Result.isMismatch(): Boolean =
        this is UpdateFetcher.Result.Failed && reason == UpdateFetcher.Reason.Mismatch

    companion object {
        const val APK_TYPE = "application/vnd.android.package-archive"
    }
}
