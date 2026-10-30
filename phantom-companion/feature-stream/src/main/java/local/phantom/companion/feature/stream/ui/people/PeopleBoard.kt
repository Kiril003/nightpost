package local.phantom.companion.feature.stream.ui.people

/**
 * Колір плитки людини — з відбитка ключа.
 *
 * Тут був ще й облік зобовʼязань (`duty()`, `undelivered()`) для правого
 * стовпчика рядка розмови. Рядок розмови пішов із застосунку 06.09 разом із
 * другим месенджером, і облік лишився б написаним та не викликаним. [Duty]
 * лишається лише тому, що на неї спираються `ChatFolders`/`IntelFrame` — теж
 * уже недосяжні; підуть вони — піде й вона.
 */
internal object PeopleBoard {

    enum class Duty { UNDELIVERED, UNREAD, YOUR_TURN, HERE, QUIET }

    /**
     * Колір плитки береться з відбитка ключа, а не з імені: два «Олеги» —
     * той і підроблений — мають бути різні на вигляд ще до того, як хтось
     * дочитає цифри. Це дані про особу, не стан, тож слову тут нічого не
     * заміняє.
     */
    fun hue(fingerprint: String): Float {
        var h = FNV_OFFSET
        for (c in fingerprint) {
            h = (h xor c.code) * FNV_PRIME
        }
        return (((h % 360) + 360) % 360).toFloat()
    }

    private const val FNV_OFFSET = -2128831035
    private const val FNV_PRIME = 16777619
}
