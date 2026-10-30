package local.phantom.companion.core.nav

import java.util.Locale
import local.phantom.companion.core.net.map.RouteStep

enum class UkCase { INS, GEN, ACC }

private val BARE = Regex("^[А-ЯІЇЄҐA-Z0-9]{2,6}$")
private val BARE_NAMES = setOf("Сільпо")
private val COMMERCIAL = setOf(
    LandmarkKind.SUPERMARKET, LandmarkKind.SHOP, LandmarkKind.FUEL,
    LandmarkKind.PHARMACY, LandmarkKind.BANK, LandmarkKind.POST,
)

/**
 * Назва ніколи не відмінюється: або вона сама незмінна («за АТБ», «за ОККО»),
 * або відмінюється рід речі, а назва стоїть у лапках («за аптекою «Подорожник»»).
 */
fun landmarkRefUk(kind: LandmarkKind, name: String?, case: UkCase): String {
    val noun = when (case) {
        UkCase.INS -> kind.ins
        UkCase.GEN -> kind.gen
        UkCase.ACC -> kind.acc
    }
    return when {
        name == null -> noun
        kind in COMMERCIAL && (BARE.matches(name) || name in BARE_NAMES) -> name
        else -> "$noun «$name»"
    }
}

/** Коротко для банера: «за АТБ», «біля аптеки», «через арку», «у бік церкви». */
fun landmarkLeadUk(hint: StepHint): String = when (hint.relation) {
    LandmarkRelation.AFTER -> (if (hint.soon) "одразу за " else "за ") + landmarkRefUk(hint.kind, hint.name, UkCase.INS)
    LandmarkRelation.AT -> "біля " + landmarkRefUk(hint.kind, hint.name, UkCase.GEN)
    LandmarkRelation.THROUGH -> "через " + landmarkRefUk(hint.kind, hint.name, UkCase.ACC)
    LandmarkRelation.TOWARD -> "у бік " + landmarkRefUk(hint.kind, hint.name, UkCase.GEN)
}

private fun LandmarkKind.nounEn(): String = when (this) {
    LandmarkKind.BRIDGE -> "bridge"
    LandmarkKind.ARCH -> "arch"
    LandmarkKind.CHURCH -> "church"
    LandmarkKind.TEMPLE -> "temple"
    LandmarkKind.FUEL -> "fuel station"
    LandmarkKind.SUPERMARKET -> "supermarket"
    LandmarkKind.STOP -> "stop"
    LandmarkKind.MARKET -> "market"
    LandmarkKind.HOSPITAL -> "hospital"
    LandmarkKind.PHARMACY -> "pharmacy"
    LandmarkKind.POST -> "post office"
    LandmarkKind.BANK -> "bank"
    LandmarkKind.POLICE -> "police station"
    LandmarkKind.SHOP -> "shop"
}

/** «the pharmacy», «ATB», «the Rynok stop». */
fun landmarkRefEn(kind: LandmarkKind, name: String?): String = when {
    name == null -> "the ${kind.nounEn()}"
    kind in COMMERCIAL && (BARE.matches(name) || name in BARE_NAMES) -> name
    else -> "the $name ${kind.nounEn()}"
}

/** The banner line in English: «after the pharmacy», «over the bridge», «toward the church». */
fun landmarkLeadEn(hint: StepHint): String = when (hint.relation) {
    LandmarkRelation.AFTER -> (if (hint.soon) "right after " else "after ") + landmarkRefEn(hint.kind, hint.name)
    LandmarkRelation.AT -> "at " + landmarkRefEn(hint.kind, hint.name)
    LandmarkRelation.THROUGH -> (if (hint.kind == LandmarkKind.BRIDGE) "over " else "through ") + landmarkRefEn(hint.kind, hint.name)
    LandmarkRelation.TOWARD -> "toward " + landmarkRefEn(hint.kind, hint.name)
}

/** The banner's landmark line in the app language; speech keeps [landmarkLeadUk]. */
fun landmarkLead(hint: StepHint, locale: Locale = Locale.getDefault()): String =
    if (locale.language == "uk") landmarkLeadUk(hint) else landmarkLeadEn(hint)

private fun sideUk(modifier: String?): String? = when (modifier) {
    "left" -> "ліворуч"
    "right" -> "праворуч"
    "slight left" -> "плавно ліворуч"
    "slight right" -> "плавно праворуч"
    "sharp left" -> "різко ліворуч"
    "sharp right" -> "різко праворуч"
    else -> null
}

/**
 * Вказівка ведення. Без орієнтира й без звʼязки — рівно [maneuverPhraseUk],
 * бо саме ці фрази лежать у запеченому голосі й звучать миттєво.
 */
fun guidancePhraseUk(
    step: RouteStep,
    hint: StepHint?,
    distanceM: Int?,
    chainNext: RouteStep? = null,
    isIntermediate: Boolean = false,
    following: Pair<RouteStep, StepHint>? = null,
): String {
    if (TransitSteps.isTransit(step)) return step.roadName
    if (hint == null && chainNext == null && following == null) {
        return maneuverPhraseUk(step.type, step.modifier, step.roadName, distanceM, step.exitNumber, isIntermediate)
    }
    if (hint?.relation == LandmarkRelation.TOWARD) {
        return "Ідіть " + landmarkLeadUk(hint)
    }
    val (lead, verb) = clause(step, hint, chainNext, isIntermediate)
    val tail = following?.let { (next, nextHint) ->
        val (nl, nv) = clause(next, nextHint, null, false)
        ", а " + listOfNotNull(nl, nv).joinToString(" ")
    }.orEmpty()
    val near = distanceM == null || distanceM <= 30
    val text = when {
        near -> listOfNotNull(lead, verb).joinToString(" ")
        lead != null -> "через ${spokenDistanceUk(distanceM!!)}, $lead, $verb"
        else -> "через ${spokenDistanceUk(distanceM!!)} $verb"
    }
    return text.replaceFirstChar { it.uppercase() } + tail
}

private fun clause(
    step: RouteStep,
    hint: StepHint?,
    chainNext: RouteStep?,
    isIntermediate: Boolean,
): Pair<String?, String> {
    var verb = maneuverActionUk(step.type, step.modifier, step.exitNumber, isIntermediate)
        .replaceFirstChar { it.lowercase() }
    if (step.type == "fork") verb = verb.replace("плавно ліворуч", "лівіше").replace("плавно праворуч", "правіше")
    if (hint?.relation == LandmarkRelation.THROUGH) verb += " " + landmarkLeadUk(hint)
    val second = chainNext?.let { sideUk(it.modifier) }
    if (second != null) verb += " і одразу $second"
    verb += ontoRoadUk(if (second != null) chainNext?.roadName else step.roadName)
    val lead = hint?.takeIf { it.relation == LandmarkRelation.AFTER || it.relation == LandmarkRelation.AT }
        ?.let(::landmarkLeadUk)
    return lead to verb
}

private val FEMININE_TYPES = mapOf(
    "вулиця" to "вулицю", "площа" to "площу", "алея" to "алею", "дорога" to "дорогу",
    "набережна" to "набережну", "траса" to "трасу", "лінія" to "лінію", "просіка" to "просіку",
)
private val OTHER_TYPES = setOf(
    "проспект", "провулок", "бульвар", "майдан", "шлях", "проїзд", "узвіз", "тупик", "шосе", "міст", "спуск",
)
private val FEM_ADJ = Regex("(?:[^аоиі]на|[сцзч]ька|ня)$")
private val FEM_ADJ_AMBIGUOUS = Regex("(?:ва|[иі]на)$")

private fun femAcc(word: String): String = when {
    word.endsWith("я") -> word.dropLast(1) + "ю"
    word.endsWith("а") -> word.dropLast(1) + "у"
    else -> word
}

/**
 * «на вулицю Шевченка», «на Липову вулицю», «на проспект Свободи». Де відмінок
 * не вгадати («вулиця Садова» — прикметник чи прізвище?), назва стоїть після
 * тире в називному: табличка, а не зламане слово.
 */
fun ontoRoadUk(roadName: String?): String {
    val raw = roadName?.trim()?.replace(Regex("^вул\\.\\s*"), "вулиця ").orEmpty()
    if (raw.isEmpty() || raw.none { it.isLetter() } || '/' in raw || ';' in raw) return ""
    val words = raw.split(Regex("\\s+"))
    val lower = words.map { it.lowercase() }
    val typeIdx = lower.indexOfLast { it in FEMININE_TYPES || it in OTHER_TYPES }
    val plain = " на $raw"
    val sign = " — $raw"
    if (typeIdx < 0) {
        val first = lower.first().trimEnd('.', ',')
        return when {
            words.size == 1 && FEM_ADJ.containsMatchIn(first) -> " на ${femAcc(raw)}"
            first.endsWith("а") || first.endsWith("я") -> sign
            else -> plain
        }
    }
    val type = lower[typeIdx]
    val acc = FEMININE_TYPES[type] ?: return plain
    val before = words.subList(0, typeIdx)
    val after = words.subList(typeIdx + 1, words.size)
    if (before.any { !it.endsWith("а") && !it.endsWith("я") }) return sign
    val afterAdj = after.size == 1 && FEM_ADJ.containsMatchIn(after[0].lowercase())
    if (after.size == 1 && !afterAdj && FEM_ADJ_AMBIGUOUS.containsMatchIn(after[0].lowercase())) return sign
    if (after.size > 1 && FEM_ADJ.containsMatchIn(after.last().lowercase())) return sign
    val typeWord = if (words[typeIdx].first().isUpperCase()) acc.replaceFirstChar { it.uppercase() } else acc
    val out = before.map(::femAcc) + typeWord + (if (afterAdj) after.map(::femAcc) else after)
    return " на " + out.joinToString(" ")
}
