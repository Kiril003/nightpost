package local.phantom.obsidian

/**
 * Чорнило мапи — арифметика поверх ролей, а не друга палітра.
 *
 * Токенів 23; класів землі, щаблів доріг і рангів підписів — під сотню. Різниця
 * не вигадується руками: кожен картографічний колір тут — операція над ролями
 * ([Palette]), тож зміна однієї ролі веде за собою всю родину, а нічний і
 * денний варіанти лишаються тим самим стилем, а не двома схожими.
 *
 * Полярність несуть самі операції, а не умови. [lighten] тягне до `road-major`
 * (світлий в обох варіантах). «Глибше» — це не «темніше»: над нічною землею
 * темніти вже нікуди, тож глибина йде ДАЛІ ВІД ЗЕМЛІ, у бік `info` — вдень це
 * читається як темніша синь, уночі як насиченіша. Одна формула, обидва ока.
 *
 * Те саме правило несе і `deepen`: скільки глибини додати, вирішує світлота
 * самої землі, тож денний папір отримує поправку, а нічна земля — рівний нуль.
 *
 * Дзеркало глибини — підйом: над майже чорною землею квартал, парк і вода
 * мусять СВІТЛІШАТИ, і скільки — вирішує [night], одиниця над темною землею й
 * нуль на папері. Кожен нічний важіль нижче помножений на нього, тож день не
 * знає про нього нічого.
 */
class CartoInk(private val p: Palette, private val quiet: Double = 0.0) {

    /**
     * Крок землі НАЗУСТРІЧ кольору — тиша, а не стирання.
     *
     * Мінімал прибирає гучність, а не зміст: ліс лишається лісом, просто
     * перестає бути найгучнішим на екрані. Тому це `mix` до `ground`, а не
     * `fill-opacity`, — щоб різниця між лісом і полем вижила, лише стишена.
     *
     * Прозорість зберігається окремо з тієї самої причини, що в [warmed]: тінь
     * будівлі несе альфу як частину значення, і суміш із непрозорою землею
     * зробила б її ЩІЛЬНІШОЮ — тобто мінімал додав би те, що мав прибрати.
     */
    private fun hush(c: String) =
        if (quiet <= 0.0) c else alpha(mix(c, p.ground, quiet), alphaOf(c))

    /**
     * Скільки в цій землі паперу: нуль над нічною, ~0,78 на денній.
     *
     * Та сама різниця світлот коштує біля білого набагато більше, ніж біля
     * чорного: нічний парк на 1,17:1 читається як парк, денний на тих самих
     * 1,17:1 читається як асфальт — це і був вердикт велосипедистки. Тому
     * глибина йде не «на смак», а поправкою на землю, і полярність несе сама
     * величина. Квадрат — бо втрата контрасту біля білого росте швидше за
     * світлоту; він же й гарантує, що нічний варіант лишається побайтово тим,
     * який хвалили.
     *
     * Не private: `LegibilityTest` тримає саме цей нуль — він і є доказом, що
     * ніч не зачеплено.
     */
    internal val paper = luma(p.ground).let { it * it }

    /**
     * Скільки в цій землі ночі — дзеркало [paper], але не крива, а бік: чорнило
     * світліше за землю — ніч (і сутінки, бо їхнє чорнило те саме), інакше папір.
     * Тому день — не «менше сходинки каналу», а рівно нуль: кожен нічний важіль
     * нижче стоїть як `× night`, і на папері його не існує за побудовою.
     *
     * Чому не крива від землі, як [paper]: сутінкова земля тепла, і «крок від
     * землі» (як у [away]) жене зелень у бірюзу без світла — парк виходив
     * 1,23:1 при підлозі 1,6. Підйом уночі мусить бути АБСОЛЮТНИМ: крок до
     * світлого тієї ж родини, а не подвоєння різниці з землею.
     *
     * Не private: `NightGroundLadderTest` тримає цю одиницю й цей нуль.
     */
    internal val night = if (luma(p.ground) < luma(p.ink)) 1.0 else 0.0

    private fun lighten(c: String, t: Double) = mix(c, p.roadMajor, t)

    /**
     * Глибина на вимогу паперу. Два кроки, бо вони роблять різне: [chroma] —
     * колір бере власну різницю з землею ще раз (це тримає тон, інакше зелень
     * сіріє в бетон), [shade] — притискається до чорнила (це дає вагу). Обидва
     * множаться на [paper], тож уночі це тотожність.
     */
    private fun deepen(c: String, chroma: Double, shade: Double): String =
        mix(away(c, p.ground, chroma * paper), p.ink, shade * paper)

    val ground = p.ground

    // Підйом нічної землі йде не до сигнальних `info`/`success` напряму — вони
    // неонові над глибоким чорнилом, — а до їхньої суміші з ink-muted: синьо-сіра
    // вода і шавлія замість кольору тривоги й «онлайн».
    private val mist = mix(p.info, p.inkMuted, 0.64)
    private val sage = mix(p.success, p.inkMuted, 0.55)

    // Вирок власника 01.09 («це схема, не мапа»): ліс був 1,25:1, парк 1,17:1
    // проти землі — зелені на нічній мапі не було. Тепер ліс 2,0:1, парк 1,8:1,
    // лука 1,4:1 — шавлією, не неоном. Удень × 0.
    val landForest = hush(mix(deepen(p.green, 0.55, 0.20), sage, 0.26 * night))
    val landGrass = hush(mix(deepen(mix(p.green, p.ground, 0.52), 0.85, 0.10), sage, 0.15 * night))
    val landPark = hush(mix(deepen(mix(p.green, p.ground, 0.24), 0.70, 0.14), sage, 0.22 * night))
    // Країна полів: farmland має власний, теплий бік зеленого — інакше поле й
    // лука зливаються в одну пляму на пів-України.
    // Уночі поле — крок до ink-faint, теплого сірого: 1,27 → 1,46:1; лишається
    // теплішим за луку і тихішим за парк.
    val landFarmland = hush(
        mix(deepen(mix(mix(p.green, p.accentWarm, 0.22), p.ground, 0.46), 0.80, 0.12), p.inkFaint, 0.15 * night),
    )
    val landRock = hush(mix(p.border, p.ground, 0.42))
    val landSand = hush(mix(p.accent, p.ground, 0.74))
    // Лід — найсвітліше, що є на землі, і вночі теж: тягнути його до `surface`
    // означало б темний льодовик, тобто не льодовик.
    val landIce = hush(lighten(p.info, 0.66))
    // Болото вночі — крок до `info`, бо болото — вода з травою: 1,17 → 1,49:1,
    // між полем і парком.
    val landWetland = hush(
        mix(deepen(mix(mix(p.water, p.green, 0.45), p.ground, 0.38), 0.75, 0.12), mist, 0.15 * night),
    )
    val wetlandHatch = hush(alpha(mix(p.water, p.green, 0.45), 0.45))

    // Квартал уночі був 1,03:1 — тобто його не було, і будинки висіли на
    // порожній землі («сірі плями»). Крок до `border` — тієї ж земляної родини,
    // що й surface: 1,26:1. Квартал видно, і він тихіший за все, що росте в ньому.
    val zoneResidential = hush(mix(mix(p.surface, p.ground, 0.46), p.border, 0.50 * night))
    val zoneIndustrial = hush(mix(mix(p.border, p.ground, 0.70), p.inkFaint, 0.10 * night))
    val zoneCivic = hush(mix(mix(mist, p.ground, 0.90), mist, 0.08 * night))

    // Вода вночі була 1,40:1 (озеро) і 1,83 (море) — не вода, а тінь на землі.
    // Тепер 3,0:1 / 3,7:1 (сутінки ≥2,5): Дніпро мусить читатись із витягнутої
    // руки, але синьо-сірим, а не синім сигналу.
    val water = mix(deepen(p.water, 0.35, 0.16), mist, 0.42 * night)
    val waterDeep = mix(deepen(mix(p.water, mist, 0.15), 0.30, 0.20), mist, 0.44 * night)
    // Похідні беруться від ЗАГЛИБЛЕНОЇ води, не від токена: берег і річка мусять
    // лишитись світлішим обідком тієї самої води, а не другою, блідішою водою.
    val waterShore = lighten(water, 0.11)
    val waterIntermittent = mix(water, p.ground, 0.42)
    val waterway = lighten(water, 0.06)

    // Стеля жили. Магістраль 16:1 проти землі вночі сліпила («яскраві вени»).
    // Верхні два щаблі притоплено в ту саму рампу: магістраль 9,6:1 проти
    // кварталу, трункова 8,7 — над primary (7,9), але без сліпучого. Проїзд
    // піднято на 0,03, щоб над піднятим кварталом лишитись ~2,6:1. Решта щаблів
    // стоїть: вони й були правильні відносно кварталу, якого не було.
    //
    // Магістраль і трункова — ледь теплі (10 % і 5 % бурштину): ранг читається
    // відтінком ще до ширини, але це не акцент — бурштин лишається за дією.
    val roadMotorway = mix(mix(p.roadMajor, p.accent, 0.10), p.roadMinor, 0.20 * night)
    val roadTrunk = mix(mix(p.roadMajor, p.accent, 0.05), p.roadMinor, 0.16 + 0.10 * night)
    val roadPrimary = mix(p.roadMajor, p.roadMinor, 0.30)
    val roadSecondary = mix(p.roadMajor, p.roadMinor, 0.46)
    val roadTertiary = mix(p.roadMajor, p.roadMinor, 0.60)
    val roadMinor = mix(p.roadMajor, p.roadMinor, 0.74)
    val roadService = mix(p.roadMajor, p.roadMinor, 0.88 - 0.03 * night)
    val roadPath = p.roadMinor

    // На папері дорогу малює НЕ заливка, а обводка: заливка магістралі — білий
    // на майже білому (1,13:1), і без глибокого касингу денна мережа зникала
    // цілком («проїжджа невидима», блокер гонтлету). Уночі та сама арифметика —
    // нуль: там жилу видно самою заливкою.
    // Обводка — тепла, на 1,7:1 і 3,0:1 до паперу: край вулиці, а не коричневий
    // контур навколо кожного двору.
    val roadCasing = deepen(mix(p.ground, p.border, 0.85), 0.45, 0.03)
    val roadCasingMajor = deepen(mix(mix(p.ground, p.border, 0.85), p.ink, 0.12), 0.60, 0.10)
    val roadUnpaved = mix(p.accentWarm, p.roadMinor, 0.30)
    // Приватний проїзд — не небезпека: коралові пунктири обводили кожен двір
    // і ТЦ як зону тривоги (кадр A52 06.09). Закритий проїзд — трохи тихіший
    // за службовий і пунктиром: розрив і каже «сюди не можна». Не глибше:
    // суміш із землею на 45 % дала 1,2:1 проти кварталу, тобто зникла.
    val roadClosed = mix(roadService, p.ground, 0.25)

    val rail = mix(p.inkFaint, p.ground, 0.42)
    val railTie = mix(p.inkFaint, p.ground, 0.14)
    val railService = mix(p.inkFaint, p.ground, 0.64)

    val apron = mix(p.border, p.ground, 0.76)
    val runway = mix(p.roadMinor, p.ground, 0.22)
    val taxiway = mix(p.roadMinor, p.ground, 0.48)

    val boundaryCountry = mix(p.inkFaint, p.ground, 0.24)
    val boundaryRegion = mix(p.inkFaint, p.ground, 0.56)
    val boundaryMaritime = mix(p.inkFaint, p.ground, 0.76)

    // Обрій, підважений десятою частиною чорнила. Не «border, притоплений
    // землею»: та формула давала 1.19:1 проти ґрунту, тобто на телефоні —
    // порожню землю замість кварталу (перевірено на пристрої, z18). Тепер
    // Удень 1,48:1 — будинок видно, але він ґрунт, а не предмет.
    //
    // Уночі квартал піднято, і будівля йде ще на крок до ink-muted: 2,6:1 проти
    // кварталу — підлога власника «≥2,4». Ціна: проїзд і будівля близькі за
    // світлотою; їх ділить касинг, ребро і тінь.
    private val buildingInk =
        mix(mix(mix(p.border, p.ink, 0.10), p.inkMuted, 0.26 * night), p.ground, 0.42 * paper)
    val building = hush(buildingInk)
    val buildingShadow = hush(alpha(p.scrim, 0.20))
    val buildingEdge = hush(mix(buildingInk, p.ink, 0.20))

    val labelInk = p.ink
    val labelMuted = p.inkMuted
    // Номер будинку й пік — найтихіший голос, але не невидимий: колись це було
    // 3,5:1 уночі і 2,64:1 удень. Тепер ink-faint — text-3 дизайн-системи, і він
    // сам AA в обох полярностях (5,7:1 / 5,8:1); будь-який підйом лише переганяв
    // би muted, і номер будинку ставав би гучнішим за назву району.
    val labelFaint = p.inkFaint
    val labelHalo = p.ground
    // Назва води лежить НА воді: ≥3:1 проти самої води і між muted та road на
    // ореолі, в обох полярностях (денна `mix(info, water)` колись давала 2,58:1).
    val labelWater = mix(mix(mist, p.water, 0.30), p.ink, 0.58 * night + 0.66 * paper)
    // Назва вулиці лежить НА дорозі, і вночі дорога — світла жила. Приглушити
    // її до ink-muted означало б сірий підпис на майже білому асфальті.
    val labelRoad = mix(p.ink, p.ground, 0.12)

    /** Тунель — той самий щабель, приглушений землею над ним. */
    fun tunnel(rankInk: String): String = mix(rankInk, p.ground, 0.42)

    /** Кожен колір, який компілятор має право покласти в стиль. Тест звіряє з
     *  цим набором усе, що потрапило у випечений JSON. */
    val all: Set<String> by lazy {
        val ranks = listOf(
            roadMotorway, roadTrunk, roadPrimary, roadSecondary,
            roadTertiary, roadMinor, roadService, roadPath,
        )
        buildSet {
            addAll(
                listOf(
                    ground, landForest, landGrass, landPark, landFarmland,
                    landRock, landSand, landIce, landWetland, wetlandHatch,
                    zoneResidential, zoneIndustrial, zoneCivic,
                    water, waterDeep, waterShore, waterIntermittent, waterway,
                    roadCasing, roadCasingMajor, roadUnpaved, roadClosed,
                    rail, railTie, railService, apron, runway, taxiway,
                    boundaryCountry, boundaryRegion, boundaryMaritime,
                    building, buildingShadow, buildingEdge,
                    labelInk, labelMuted, labelFaint, labelHalo, labelWater, labelRoad,
                ),
            )
            addAll(ranks)
            addAll(ranks.map(::tunnel))
        }
    }
}

/* ── Арифметика ──────────────────────────────────────────────────────────── */

private data class Rgba(val r: Int, val g: Int, val b: Int, val a: Double)

/** Лінійна суміш у sRGB. Не «правильно» колориметрично — але передбачувано, і
 *  саме те, що очікує око, коли дивиться на дві сусідні заливки. */
internal fun mix(a: String, b: String, t: Double): String {
    require(t in 0.0..1.0) { "частка суміші поза 0..1: $t" }
    val x = parse(a)
    val y = parse(b)
    return format(
        Rgba(
            lerp(x.r, y.r, t),
            lerp(x.g, y.g, t),
            lerp(x.b, y.b, t),
            x.a + (y.a - x.a) * t,
        ),
    )
}

internal fun alpha(color: String, a: Double): String = format(parse(color).copy(a = a))

/**
 * Крок ВІД [from], а не до нього: колір бере власну різницю з землею ще раз.
 *
 * Саме це відрізняє «глибше» від «темніше». Підмішування чорнила тягне все до
 * нейтралі й гасить тон — сосна стає бетоном; крок від землі підсилює рівно ту
 * різницю, якою цей колір і є. Вихід за межі каналу зрізає [format].
 */
internal fun away(color: String, from: String, k: Double): String {
    val c = parse(color)
    val g = parse(from)
    fun ch(a: Int, b: Int) = Math.round(a + (a - b) * k).toInt()
    return format(Rgba(ch(c.r, g.r), ch(c.g, g.g), ch(c.b, g.b), c.a))
}

/** Відносна яскравість WCAG — та сама, якою міряються контрасти в токенах. */
internal fun luma(color: String): Double {
    val c = parse(color)
    fun lin(v: Int): Double {
        val x = v / 255.0
        return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * lin(c.r) + 0.7152 * lin(c.g) + 0.0722 * lin(c.b)
}

/**
 * Сутінковий зсув ролі до теплого акценту.
 *
 * Прозорість зберігається навмисно: `scrim` несе альфу як частину значення, і
 * підмішування непрозорого акценту без цього зробило б скрим ще й щільнішим —
 * тобто змінило б не настрій, а глибину затемнення.
 */
internal fun warmed(role: String, warm: String, t: Double): String =
    if (t <= 0.0) role else alpha(mix(role, warm, t), alphaOf(role))

internal fun alphaOf(color: String): Double = parse(color).a

private fun lerp(a: Int, b: Int, t: Double) = Math.round(a + (b - a) * t).toInt()

private val RGBA = Regex("""rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*([0-9.]+)\s*)?\)""")

private fun parse(raw: String): Rgba {
    if (raw.startsWith("#")) {
        val hex = raw.substring(1)
        require(hex.length == 6) { "очікується #RRGGBB, отримано $raw" }
        return Rgba(
            hex.substring(0, 2).toInt(16),
            hex.substring(2, 4).toInt(16),
            hex.substring(4, 6).toInt(16),
            1.0,
        )
    }
    val m = RGBA.matchEntire(raw.trim()) ?: error("нерозпізнаний колір: $raw")
    return Rgba(
        m.groupValues[1].toInt(),
        m.groupValues[2].toInt(),
        m.groupValues[3].toInt(),
        m.groupValues[4].takeIf { it.isNotEmpty() }?.toDouble() ?: 1.0,
    )
}

private fun format(c: Rgba): String {
    val r = c.r.coerceIn(0, 255)
    val g = c.g.coerceIn(0, 255)
    val b = c.b.coerceIn(0, 255)
    if (c.a >= 0.999) return "#%02X%02X%02X".format(r, g, b)
    return "rgba($r,$g,$b,${number(Math.round(c.a * 1000.0) / 1000.0)})"
}
