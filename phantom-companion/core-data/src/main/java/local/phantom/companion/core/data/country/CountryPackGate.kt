package local.phantom.companion.core.data.country

import java.io.File
import local.phantom.companion.core.net.pack.PackFailure
import local.phantom.companion.core.net.pack.PackGate
import local.phantom.companion.core.net.pack.PackInspection
import local.phantom.companion.core.net.pack.PackPromotion
import local.phantom.companion.core.net.pack.PackWant
import local.phantom.companion.core.net.pack.PackWantFile

/**
 * Тут підпис, версія, ліцензія і підміна каталогу; у фетчера — лише байти.
 *
 * Свідомо НЕ робить пак активним сам: перевстановлення [CountryPackRegistry]
 * скинуло б памʼять про країну й ручний вибір оператора, а ті живуть у
 * налаштуваннях застосунку, яких `:core-data` не бачить. Реєстр оновлює
 * викликач через [onInstalled]; нема хука — пак підхопиться наступним стартом.
 */
class CountryPackGate(
    private val store: CountryPackStore,
    private val onInstalled: (InstalledPack) -> Unit = {},
) : PackGate {

    override fun inspect(manifestText: String): PackInspection =
        when (val outcome = store.inspect(manifestText)) {
            is PackInstall.Rejected -> PackInspection.Refused(failureOf(outcome))
            is PackInstall.Accepted -> {
                val manifest = outcome.pack.manifest
                val unhashed = PackInstaller.unhashedAssets(manifest)
                if (unhashed.isNotEmpty()) {
                    PackInspection.Refused(
                        PackFailure.Refused("асети без хеша: ${unhashed.joinToString()}"),
                    )
                } else {
                    PackInspection.Ready(
                        PackWant(
                            packId = manifest.id,
                            version = manifest.version,
                            files = manifest.files.map { PackWantFile(it.name, it.sha256, it.bytes) },
                            minAppBuild = manifest.minAppBuild,
                        ),
                    )
                }
            }
        }

    override fun installedVersion(packId: String): Int? = store.installedVersion(packId)

    override fun stagingDir(packId: String): File = store.stagingDir(packId)

    override fun promote(packId: String, manifestText: String): PackPromotion =
        when (val outcome = store.installStaged(manifestText)) {
            is PackInstall.Accepted -> {
                onInstalled(outcome.pack)
                PackPromotion.Installed(outcome.pack.version)
            }
            is PackInstall.Rejected -> {
                // Неповне вистоювання лікується наступною спробою — решта ні,
                // і тримати за них десятки мегабайт нема за що.
                if (outcome.reason != PackReject.INCOMPLETE) store.discardStaged(packId)
                PackPromotion.Refused(failureOf(outcome))
            }
        }

    private fun failureOf(rejected: PackInstall.Rejected): PackFailure = when (rejected.reason) {
        PackReject.BAD_SIGNATURE, PackReject.NO_TRUST_ANCHOR -> PackFailure.Signature(rejected.detail)
        else -> PackFailure.Refused(rejected.detail)
    }
}
