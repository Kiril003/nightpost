package local.phantom.companion.data

import android.content.Context
import local.phantom.companion.BuildConfig
import local.phantom.companion.core.ai.BrainProfiles
import local.phantom.companion.core.ai.route.BrainAttestedStore
import local.phantom.companion.core.ai.route.NightpostBrainRoute
import local.phantom.companion.ui.messenger.webLanguage

/**
 * Розум сервера Nightpost для апарата без ключа й без ПК. Номер установки —
 * випадковий, у звичайних налаштуваннях: це лічильник ліміту, а не особа.
 *
 * Місце в ланцюзі — ПК → свій ключ → сервер → правила. Бриф казав «ключ → ПК → на
 * пристрої → сервер»: ПК лишився перед ключем, як було до брифу (спарений компʼютер —
 * це власна машина, питання не виходить до Google), а кроку «на пристрої» в ланцюзі
 * немає — модель в APK не їде (`MODELS_BASE_URL` порожній).
 *
 * Людина з живим своїм ключем сюди не потрапляє ([NightpostBrainRoute.mayAsk]): її
 * питання йде до Google під її ключем. Мертвий ключ ([OwnKeyGuard]) поступається серверу.
 */
fun nightpostBrainRoute(
    context: Context,
    prefs: BrainPreferences,
    /** Та сама особа, що в контейнері: друга копія сховища на холодному старті могла б записати другу пару ключів. */
    identityStore: DeviceIdentityStore,
    ownKeyDead: () -> Boolean = { false },
    ownKey: () -> String?,
): NightpostBrainRoute {
    val store = context.applicationContext.getSharedPreferences("nightpost_brain", Context.MODE_PRIVATE)
    BrainProfiles.restore(store.getString("profiles", null))
    val install by lazy {
        NightpostBrainRoute.keepInstallId(
            read = { store.getString("install_id", null) },
            write = { store.edit().putString("install_id", it).apply() },
        )
    }
    return NightpostBrainRoute(
        base = BuildConfig.LICENSE_BASE_URL,
        installId = { install },
        allowed = { NightpostBrainRoute.mayAsk(prefs.routing.value, ownKey(), ownKeyDead()) },
        signer = DeviceBrainSigner(identityStore),
        attestedStore = BrainAttestedStore.migrate(
            readString = { store.getString(BrainAttestedStore.KIND_KEY, null) },
            readLegacyBoolean = { store.getBoolean(BrainAttestedStore.LEGACY_BOOL_KEY, false) },
            writeString = { value ->
                store.edit().apply {
                    if (value == null) remove(BrainAttestedStore.KIND_KEY)
                    else putString(BrainAttestedStore.KIND_KEY, value)
                }.apply()
            },
            clearLegacyBoolean = { store.edit().remove(BrainAttestedStore.LEGACY_BOOL_KEY).apply() },
        ),
        profilesStore = { saved -> store.edit().putString("profiles", saved).apply() },
        uiLanguage = { webLanguage(context.applicationContext) },
    )
}
