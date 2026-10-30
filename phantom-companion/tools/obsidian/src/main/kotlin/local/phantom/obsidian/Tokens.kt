package local.phantom.obsidian

/**
 * ЄДИНЕ місце в цьому модулі, де взагалі дозволено писати колір.
 *
 * Значення — дослівна копія таблиці «Кольори-ролі» з
 * `docs/atlas/OBSIDIAN_TOKENS_V0.md`; `TokensDocTest` читає той файл і
 * порівнює кожен рядок, тож документ і код не можуть розійтися мовчки.
 * `NoLiteralInkTest` стежить за зворотним боком: жоден інший файл модуля не
 * має права містити hex.
 *
 * Картографічні кольори (поле, скеля, промзона, кожен щабель дороги) тут НЕ
 * лежать — вони виводяться з цих ролей арифметикою в [CartoInk].
 */
/**
 * Сутінки — НЕ третя колонка в документі, а ніч, зсунута до `accent-warm`:
 * документ визначає dusk саме так («ніч із теплішим скримом»), тож таблиця
 * лишається на двох стовпцях, а різницю несе [warmth].
 */
enum class Polarity(internal val warmth: Double) {
    NIGHT(0.0),
    DAY(0.0),
    DUSK(0.10),
}

data class ColorToken(val name: String, val night: String, val day: String) {
    fun value(polarity: Polarity): String = if (polarity == Polarity.DAY) day else night
}

data class TypeToken(val name: String, val size: Double, val weight: Int, val tracking: Double)

object Tokens {

    val colors: List<ColorToken> = listOf(
        ColorToken("ph.color.ground", "#0A0C10", "#F8F4EE"),
        ColorToken("ph.color.surface", "#12151B", "#FFFFFF"),
        ColorToken("ph.color.surface-raised", "#1A1E26", "#F0EAE2"),
        ColorToken("ph.color.glass", "rgba(18,22,27,.72)", "rgba(255,255,255,.78)"),
        ColorToken("ph.color.scrim", "rgba(5,6,10,.65)", "rgba(30,23,18,.4)"),
        ColorToken("ph.color.border", "#343B47", "#D2C6B7"),
        ColorToken("ph.color.ink", "#F4F1EC", "#17110C"),
        ColorToken("ph.color.ink-muted", "#B6BCC6", "#4E4339"),
        ColorToken("ph.color.ink-faint", "#939AA6", "#665A4D"),
        ColorToken("ph.color.accent", "#F2A93B", "#C77B21"),
        ColorToken("ph.color.accent-warm", "#D97B29", "#A8541C"),
        ColorToken("ph.color.alert", "#FF5D45", "#D93B26"),
        ColorToken("ph.color.danger", "#E5484D", "#C7373D"),
        ColorToken("ph.color.success", "#46C285", "#1F9D62"),
        ColorToken("ph.color.info", "#58A6FF", "#2270C4"),
        ColorToken("ph.color.live", "#3DDC84", "#1FA45B"),
        ColorToken("ph.color.simulated", "#8FA3C0", "#6B7FA0"),
        ColorToken("ph.color.route", "#4FD8EB", "#1D7FD1"),
        ColorToken("ph.color.route-alt", "#7A8794", "#8B96A0"),
        ColorToken("ph.color.water", "#182733", "#C9D8E0"),
        ColorToken("ph.color.green", "#18241D", "#DCE6D2"),
        ColorToken("ph.color.road-major", "#EDE6DC", "#FFFFFF"),
        ColorToken("ph.color.road-minor", "#4A4D53", "#D9CFC3"),
    )

    val types: List<TypeToken> = listOf(
        TypeToken("ph.type.display", 28.0, 700, -0.01),
        TypeToken("ph.type.title", 20.0, 600, 0.0),
        TypeToken("ph.type.body", 15.0, 450, 0.0),
        TypeToken("ph.type.caption", 12.5, 450, 0.01),
        TypeToken("ph.type.micro", 10.5, 500, 0.02),
    )

    private val byName = colors.associateBy { it.name }
    private val typeByName = types.associateBy { it.name }

    fun type(name: String): TypeToken =
        typeByName[name] ?: error("немає типографічного токена $name")

    fun color(name: String, polarity: Polarity): String =
        (byName[name] ?: error("немає кольорового токена $name")).value(polarity)
}

/** Ролі однієї полярності, названі так само, як у документі. */
class Palette(val polarity: Polarity) {
    private fun c(name: String) = Tokens.color(name, polarity)

    /**
     * Земляна родина ловить сутінки, сигнальні ролі — ні.
     *
     * Теплішають лише ці чотири, і цього досить: у [CartoInk] майже все —
     * `mix(щось, ground, t)`, тож настрій сам розходиться по землі, зонах,
     * касингах, тунелях і гало підписів. А `ink`, `alert`, `live`, `route`
     * стоять на місці — інакше «тривога» вночі й у сутінках була б двома
     * різними тривогами.
     */
    private fun earth(name: String) = warmed(c(name), c("ph.color.accent-warm"), polarity.warmth)

    val ground get() = earth("ph.color.ground")
    val surface get() = earth("ph.color.surface")
    val scrim get() = earth("ph.color.scrim")
    val border get() = earth("ph.color.border")
    val ink get() = c("ph.color.ink")
    val inkMuted get() = c("ph.color.ink-muted")
    val inkFaint get() = c("ph.color.ink-faint")
    val accent get() = c("ph.color.accent")
    val accentWarm get() = c("ph.color.accent-warm")
    val alert get() = c("ph.color.alert")
    val danger get() = c("ph.color.danger")
    val info get() = c("ph.color.info")
    val water get() = c("ph.color.water")
    val green get() = c("ph.color.green")
    // Світло зеленої родини: лісу вночі нема куди темніти, і його підйом іде до
    // цієї ролі — так само, як глибина води йде до `info`. Сигнальна роль, не тепліє.
    val success get() = c("ph.color.success")
    val roadMajor get() = c("ph.color.road-major")
    val roadMinor get() = c("ph.color.road-minor")
}
