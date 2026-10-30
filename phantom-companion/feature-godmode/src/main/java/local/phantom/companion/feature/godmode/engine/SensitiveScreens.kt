package local.phantom.companion.feature.godmode.engine

/**
 * Межі зору симбіота: куди він не дивиться навіть із дозволом.
 *
 * Статут: «FLAG_SECURE / поля паролів ніколи не читаються. Застосунки банків —
 * за замовчуванням закриті для SEE». Поля паролів гасить сам обхід дерева
 * ([ScreenAwarenessService.collectInteractive] не кладе їх текст); цей файл —
 * про банки: пакет застосунку на передньому плані зіставляється з переліком,
 * і якщо це банк, читання екрана відмовляється до того, як дерево взагалі
 * збереться. Чиста логіка, щоб перевірятись звичайним тестом.
 *
 * Перелік — не замок (власник може дозволити вручну), а чесне «за замовчуванням
 * ні»: там, де код із SMS і баланс, мовчання безпечніше за знімок.
 */
object SensitiveScreens {

    /** Точні пакети гаманців і банків, де SEE закрито за замовчуванням. */
    private val BANKING_PACKAGES: Set<String> = setOf(
        "com.privatbank.ap24",
        "ua.privatbank.ap24s",
        "com.ftband.mono",
        "ua.com.cs.android", // Sense SuperApp (Альфа)
        "ua.aval.dbo.client.android",
        "ua.oschadbank.online",
        "ua.pumb.mobile",
        "com.idbank.android",
        "ua.vostok.online",
        "com.google.android.apps.walletnfcrel",
        "com.paypal.android.p2pmobile",
        "com.revolut.revolut",
        "com.wise.android",
    )

    /** Фрагменти в імені пакета, що видають банк/гаманець і без точного збігу. */
    private val BANKING_HINTS: List<String> = listOf(
        "bank", "mono", "privat24", "wallet", "oschad", "pumb", "paypal", "revolut",
    )

    fun isBankingPackage(packageName: String?): Boolean {
        val pkg = packageName?.trim()?.lowercase() ?: return false
        if (pkg.isEmpty()) return false
        if (pkg in BANKING_PACKAGES) return true
        return BANKING_HINTS.any { pkg.contains(it) }
    }

    /** Дотик — лише в чужому застосунку, не в [ProtectedWindows] і не в банку. Невідоме вікно — ні. */
    fun mayTouch(windowPackage: String?, ownPackage: String, action: AppAction): Boolean =
        !ProtectedWindows.touches(action) ||
            (!ProtectedWindows.covers(windowPackage, ownPackage) && !isBankingPackage(windowPackage))

    fun mayTouch(windowPackage: String?, ownPackage: String, command: GodModeCommand): Boolean =
        when (command) {
            is GodModeCommand.ClickText ->
                mayTouch(windowPackage, ownPackage, AppAction.Click(ActionTarget(text = command.text)))
            is GodModeCommand.TypeText ->
                mayTouch(windowPackage, ownPackage, AppAction.Type(value = command.input, target = ActionTarget(text = command.targetNodeText)))
            is GodModeCommand.GlobalAction -> true
        }

    /** Текст, яким заміняється вміст поля пароля в будь-якому знімку. */
    const val PASSWORD_MASK: String = "[поле пароля]"
}
