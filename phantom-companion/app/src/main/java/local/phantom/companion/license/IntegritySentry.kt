package local.phantom.companion.license

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

class IntegritySentry(private val context: Context) {

    enum class Flag {
        ForeignSignature,
        Debuggable,
        SideloadedStoreBuild,
        Emulator,
    }

    data class Report(
        val flags: Set<Flag>,
        /**
         * Обидва відбитки їдуть разом із прапорцем, бо екран показує їх людині:
         * без них лишається «щось не так», яке нікому нічим звірити.
         */
        val signatureSha256: String? = null,
        val expectedSha256: String = "",
    ) {
        val tampered: Boolean get() = Flag.ForeignSignature in flags
        val suspicious: Boolean get() = flags.isNotEmpty()
    }

    fun inspect(expectedSignatureSha256: String, storeBuild: Boolean): Report = report(
        actualSignatureSha256 = signingSha256(),
        expectedSignatureSha256 = expectedSignatureSha256,
        debuggable = (
            context.applicationInfo.flags and
                android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
            ) != 0,
        storeBuild = storeBuild,
        installer = installer(),
        emulator = isEmulator(),
    )

    fun signingSha256(): String? = runCatching {
        val pm = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val first = signatures?.firstOrNull() ?: return null
        MessageDigest.getInstance("SHA-256")
            .digest(first.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun installer(): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
    }.getOrNull()

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("sdk_gphone", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk", ignoreCase = true)

    companion object {
        private val PLAY_INSTALLERS = setOf("com.android.vending", "com.google.android.feedback")

        /**
         * Рішення винесене з [inspect] не заради краси. У :app немає ні
         * Robolectric, ні `isReturnDefaultValues`, тож усе, що торкається
         * Context, у JVM-тесті кидає виняток — а саме там і жив розрахунок
         * прапорців. Тут той самий код, яким рахує пристрій, і його вже можна
         * перевірити запуском, а не читанням.
         */
        fun report(
            actualSignatureSha256: String?,
            expectedSignatureSha256: String,
            debuggable: Boolean,
            storeBuild: Boolean,
            installer: String?,
            emulator: Boolean,
        ): Report {
            val flags = mutableSetOf<Flag>()

            // Порожній очікуваний відбиток = звіряти нема з чим (debug-збірка):
            // мовчимо, бо інакше кожна збірка з ноутбука кричала б про підміну.
            if (expectedSignatureSha256.isNotBlank() &&
                actualSignatureSha256 != null &&
                !actualSignatureSha256.equals(expectedSignatureSha256, ignoreCase = true)
            ) {
                flags += Flag.ForeignSignature
            }

            if (debuggable) flags += Flag.Debuggable
            if (storeBuild && installer !in PLAY_INSTALLERS) flags += Flag.SideloadedStoreBuild
            if (emulator) flags += Flag.Emulator

            return Report(
                flags = flags,
                signatureSha256 = actualSignatureSha256,
                expectedSha256 = expectedSignatureSha256,
            )
        }
    }
}
