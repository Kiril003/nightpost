package local.phantom.companion.core.net.pack

import java.io.File
import java.util.Properties

/**
 * Shared [PackGate] plumbing for a single-file world pack (basemap tile set
 * or road-mesh database). [PackFetcher] itself needs no changes — it already
 * treats "pack" as an opaque list of named, hashed, sized files; only what
 * happens to the verified bytes differs per kind, which is
 * [applyVerifiedFile]'s whole job.
 *
 * Перша брама — не поля, а підпис ([WorldPackTrust]): усе, що [inspect]
 * читає далі, приходить із того самого документа, тож без підпису воно
 * доводить лише те, що дзеркало вміє рахувати sha256.
 *
 * Друга — поріг збірки (`minAppBuild`), дзеркало `PackInstaller.read` у
 * пакiв країн. Перепечений під новішу схему пак на старій збірці не «трохи
 * гірший»: 30.08 такий пак убив маршрут, який беззубий вів 11 км.
 *
 * Version bookkeeping is a flat `Properties` file rather than the country
 * pack's SQLite-backed store: a world pack has no installed-asset list to
 * diff against, only a version number to remember so a same-or-older
 * manifest is a no-op, not a re-download.
 */
abstract class WorldPackGate(
    private val rootDir: File,
    // Лямбда, а не знімок: ворота будуються раніше, ніж :app встигає покласти
    // ключ у WorldPackLink, і знімок назавжди лишився б порожнім.
    private val publicKeyHex: () -> String = { WorldPackLink.publicKeyHex() },
    // Теж лямбда, з тієї ж причини.
    private val appBuild: () -> Int = { WorldPackLink.appBuild() },
) : PackGate {

    final override fun inspect(manifestText: String): PackInspection {
        untrusted(manifestText)?.let { return PackInspection.Refused(it) }
        val doc = WorldPackWire.parseManifest(manifestText)
            ?: return PackInspection.Refused(PackFailure.Refused("зіпсований манiфест пака"))
        if (!doc.wellFormed) {
            return PackInspection.Refused(
                PackFailure.Refused("манiфест без розміру або суми — рахувати доставлене нічим"),
            )
        }
        tooNew(doc)?.let { return PackInspection.Refused(it) }
        return PackInspection.Ready(
            PackWant(
                doc.id,
                doc.version,
                listOf(PackWantFile(fileName(doc.id), doc.sha256, doc.bytes)),
                minAppBuild = doc.minAppBuild,
            ),
        )
    }

    final override fun installedVersion(packId: String): Int? =
        readVersions().getProperty(packId)?.toIntOrNull()

    final override fun stagingDir(packId: String): File = File(rootDir, "staging/$packId").apply { mkdirs() }

    final override fun promote(packId: String, manifestText: String): PackPromotion {
        // Другий раз навмисне: у потоці PackFetcher манiфест уже пройшов
        // inspect, але promote — відкритий метод, і непідписаний пак не має
        // ставати паком навіть тоді, коли його внесли повз фетчер.
        untrusted(manifestText)?.let { return PackPromotion.Refused(it) }
        val doc = WorldPackWire.parseManifest(manifestText)
            ?: return PackPromotion.Refused(PackFailure.Refused("зіпсований манiфест пака"))
        tooNew(doc)?.let {
            // Як у CountryPackGate: за пак, якого ця збірка не читає, гігабайти
            // на диску не тримають.
            stagingDir(packId).deleteRecursively()
            return PackPromotion.Refused(it)
        }
        val staged = File(stagingDir(packId), fileName(doc.id))
        if (!staged.isFile) {
            return PackPromotion.Refused(PackFailure.Refused("файл пака не довантажено"))
        }
        return try {
            // Байти вже пройшли sha256-звірку в PackFetcher — тут лише
            // рішення, куди їм належати.
            applyVerifiedFile(packId, staged)
            writeVersion(packId, doc.version)
            PackPromotion.Installed(doc.version)
        } catch (t: Throwable) {
            PackPromotion.Refused(PackFailure.Disk(t.message ?: "не вдалося внести пак"))
        } finally {
            staged.parentFile?.deleteRecursively()
        }
    }

    /** Move/import the verified [file] wherever this pack kind belongs. */
    protected abstract fun applyVerifiedFile(packId: String, file: File)

    /** Після підпису, бо поріг приходить із того самого підписаного документа. */
    private fun tooNew(doc: WorldPackManifestDoc): PackFailure.Refused? {
        val build = appBuild()
        if (build >= doc.minAppBuild) return null
        return PackFailure.Refused(
            "пак просить збірку ${doc.minAppBuild}, а ця — $build: спершу оновіть застосунок",
        )
    }

    private fun untrusted(manifestText: String): PackFailure.Signature? =
        when (WorldPackTrust.fault(manifestText, publicKeyHex())) {
            null -> null
            WorldPackTrust.Fault.NO_ANCHOR ->
                PackFailure.Signature("ключа звірки у цій збірці немає — жоден пакет не поїде")
            WorldPackTrust.Fault.MALFORMED ->
                PackFailure.Signature("опис пакета не читається як документ")
            WorldPackTrust.Fault.NOT_SIGNED ->
                PackFailure.Signature("опис пакета не підписано — дзеркало могло підмінити дороги")
            WorldPackTrust.Fault.BAD_SIGNATURE ->
                PackFailure.Signature("не сходиться з ключем цієї збірки — пакет не поїде")
        }

    protected open fun fileName(packId: String): String = "$packId.bin"

    private fun versionsFile() = File(rootDir, "versions.properties")

    private fun readVersions(): Properties = Properties().apply {
        val f = versionsFile()
        if (f.isFile) f.inputStream().use { load(it) }
    }

    private fun writeVersion(packId: String, version: Int) {
        val props = readVersions()
        props.setProperty(packId, version.toString())
        rootDir.mkdirs()
        versionsFile().outputStream().use { props.store(it, null) }
    }
}
