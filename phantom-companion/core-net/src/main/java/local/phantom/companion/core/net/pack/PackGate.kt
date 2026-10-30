package local.phantom.companion.core.net.pack

import java.io.File

/**
 * Закон пакiв, поданий фетчеру: `:core-data` бачить `:core-net`, зворотного
 * ребра нема — тож підпис, версія і каталог `files/country/` фетчеру
 * недоступні за побудовою модулів. Реалізація — `country/CountryPackGate`.
 */
interface PackGate {

    /**
     * Розібрати манiфест, ЗВІРИТИ ПІДПИС і поріг збірки (`minAppBuild`).
     * Версію навмисне не звіряє: «те саме вже стоїть» — не відмова, і
     * різницю рахує той, хто качає.
     */
    fun inspect(manifestText: String): PackInspection

    fun installedVersion(packId: String): Int?

    /** Читачам пакiв невидимий, поки манiфест не ляже на місце. */
    fun stagingDir(packId: String): File

    fun promote(packId: String, manifestText: String): PackPromotion
}

sealed interface PackInspection {
    data class Ready(val want: PackWant) : PackInspection
    data class Refused(val reason: PackFailure) : PackInspection
}

sealed interface PackPromotion {
    data class Installed(val version: Int) : PackPromotion
    data class Refused(val reason: PackFailure) : PackPromotion
}

data class PackWant(
    val packId: String,
    val version: Int,
    val files: List<PackWantFile>,
    /**
     * Нижче цієї збірки пак не розуміють; 0 — порогу не оголошено. Звіряють
     * ворота ДО [PackInspection.Ready]; тут цифра лише їде далі.
     */
    val minAppBuild: Int = 0,
) {
    val bytes: Long get() = files.sumOf { it.bytes }
}

/** [bytes] додатний: без розміру відсоток завантаження був би вигаданим. */
data class PackWantFile(val name: String, val sha256: String, val bytes: Long)
