package local.phantom.companion.core.data.country

import java.io.File

/**
 * Каталоги, у яких пак стоїть, поки він ще не пак:
 * `.staging/<id>` → `.retired/<id>` → `<id>`.
 *
 * Службові каталоги лежать у корені й усе одно невидимі: пак розпізнається
 * манiфестом РІВНО НА ОДИН рівень нижче кореня, а їхній лежить на два.
 *
 * POSIX не вміє атомарно замінити непорожній каталог, тож підміна — два
 * renameʼи. Вікно між ними не «мале», а ЗАКРИТЕ: смерть процесу лишає одну з
 * двох розрізнюваних форм, і [recover] дочитує кожну однозначно.
 */
internal object PackStaging {

    const val STAGING = ".staging"
    const val RETIRED = ".retired"

    fun dir(root: File, id: String): File = File(File(root, STAGING), id)

    fun promote(root: File, id: String): Boolean {
        val staged = dir(root, id)
        if (!staged.isDirectory) return false
        val live = File(root, id)
        if (!live.exists()) {
            live.parentFile?.mkdirs()
            return staged.renameTo(live)
        }
        val retired = File(File(root, RETIRED), id)
        retired.parentFile?.mkdirs()
        if (retired.exists()) retired.deleteRecursively()
        if (!live.renameTo(retired)) return false
        if (!staged.renameTo(live)) {
            retired.renameTo(live)
            return false
        }
        retired.deleteRecursively()
        return true
    }

    /** `.retired` без `<id>` — підміна не встигла, вернути; з `<id>` — встигла, прибрати. */
    fun recover(root: File) {
        val retiredRoot = File(root, RETIRED)
        retiredRoot.listFiles()?.filter { it.isDirectory }?.forEach { retired ->
            val live = File(root, retired.name)
            if (live.exists()) retired.deleteRecursively() else retired.renameTo(live)
        }
        retiredRoot.takeIf { it.isDirectory && it.list()?.isEmpty() == true }?.delete()
    }

    fun discard(root: File, id: String): Boolean = dir(root, id).deleteRecursively()
}
