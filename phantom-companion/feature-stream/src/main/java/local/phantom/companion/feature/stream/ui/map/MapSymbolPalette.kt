package local.phantom.companion.feature.stream.ui.map

// Plates and rims are THEME-INVARIANT: a dual-edge face (light plate + ink rim)
// clears 3:1 against every measured ground of both basemaps, including the
// CUSTOM provider whose ground is unknowable. Only text and line polarity vary.
//
// Ratios computed 15 Aug against measured grounds rgb(12,12,12) / #15151A /
// #1F1F24 / #4E4E57 / #0F0F13 (dark) and #F8F4F0 / rgb(158,189,255) / #D8E8C8 /
// #DCD8D2 (light) — see MapSymbolContrastTest, which reads these constants and
// re-measures on every build.
internal class MapSymbolPalette private constructor(val isDark: Boolean) {
    val plate = 0xF2F2EFE7.toInt()      // warm light plate, 95%
    val plateRim = 0xFF14161B.toInt()   // ink rim, 1.5dp
    val plateInk = 0xFF14161B.toInt()
    val textInk get() = if (isDark) 0xFFF2EFE7.toInt() else 0xFF1A1C22.toInt()
    val textHalo get() = if (isDark) 0xFF14161B.toInt() else 0xFFF7F4EC.toInt()
    val altRoute get() = if (isDark) 0xCC9AA8BC.toInt() else 0xCC4A5A70.toInt()
    val hatch get() = if (isDark) 0xA6A9B4C4.toInt() else 0xA639424F.toInt()
    val routeCasing get() = if (isDark) 0xFF04070B.toInt() else 0xFF14161B.toInt()

    // The ring that separates a marker from the ground it stands on. It was
    // Color.WHITE on both polarities: 1.09:1 on Liberty's #F8F4F0 background,
    // i.e. no ring at all in daylight. A separator has to be the OPPOSITE of
    // its ground, so it flips — ink on light, light on dark.
    val separator get() = if (isDark) 0xFFF7F4EC.toInt() else 0xFF14161B.toInt()

    // Cores that touch the ground directly, one value per polarity. The dark
    // half is what the map already shipped; the light half is new.
    val searchCore get() = if (isDark) 0xFFFF8A65.toInt() else 0xFF9A3412.toInt()
    val alertEdge get() = if (isDark) 0xFFF87171.toInt() else 0xFF991B1B.toInt()
    val transitWalk get() = if (isDark) 0xFF94A3B8.toInt() else 0xFF475569.toInt()

    // Слід оператора — двостанний. Пʼять відтінків вироку поруч із бурштином
    // руйнували одиничність точки, а «TRUSTED проти DEGRADED учора» не є
    // рішенням, яке хтось ухвалює. Лишається одне: небо було живе чи вороже.
    // Нейтральна пара — та сама виміряна, що несе пішу ділянку транзиту.
    val trailClear get() = transitWalk
    // Ідентичність піна: три яруси важливості, одним тоном на кільце і на
    // гліф. Тон міряється проти ПЛАШКИ, не проти землі — кільце і гліф
    // лежать усередині неї, а від землі пін відділяє пара плашка+обід.
    // Старі значення цього не проходили: #E0A020 на плашці давало 1.76:1,
    // тобто бурштинове кільце «АЗС/метро/пошта» було майже невидиме, а
    // #8A8578 звичайного піна — 2.84:1. Денна половина глибша: удень око не
    // адаптоване, і насичений нічний тон на теплій плашці розмивається.
    //
    // 17.09 — ярус був ЛАДОМ ЛИШЕ НА ПАПЕРІ. Кожен тон мав ≥3:1 проти плашки
    // ОКРЕМО, але один проти одного вони стояли ПЕРЕВЕРНУТО: аптека #DC2626
    // давала 3.73:1 проти лиця плашки, а крамниця #5A5F66 — 4.97:1. Тобто
    // звичайне місце важило БІЛЬШЕ за критичне (удень 5.00 проти 6.46 — так
    // само навпаки), а крок «критичне/опорне» був 1.04:1 —
    // при знятому кольорі (червоний фільтр, низьке сонце, дальтонік в
    // екіпажі) ці два яруси зливались в один. Стандарт на це в дереві вже є,
    // просто до пінів його не прикладали: `each register is told apart
    // without colour at all`.
    //
    // Тепер вага = важливість, і несе її СВІТЛОТА, а не лише тон. Числа —
    // проти лиця плашки (95 % над найтемнішим, що під нею буває, #E5E2DB):
    //   ніч  критичне 7.43:1 > опорне 5.16 > звичайне 3.46 (крок 1.44 / 1.49)
    //   день критичне 8.65:1 > опорне 6.06 > звичайне 4.17 (крок 1.43 / 1.46)
    // Крок тримається і БЕЗ кольору: під дейтеранопією 1.37 / 1.39 уночі та
    // 1.37 / 1.36 удень — було 1.12 / 1.41 і 1.18 / 1.28, тобто критичне й
    // опорне для дальтоніка були одним ярусом.
    // Родини тонів не мінялись: червоне лишилось червоним, бурштин бурштином,
    // сланець сланцем — вивчений знак не переучують.
    //
    // Це не «тихіше заради краси». Корпус місць виріс із 21 335 київських
    // точок до 175 685 по Україні; у великому місті в кадрі будуть СОТНІ
    // пінів, і найгучнішим на екрані мусить лишатись те, по що йдуть у біді,
    // а не найближча крамниця. `MapPinTierContrastTest` тримає і лад, і крок.
    val pinCritical get() = if (isDark) 0xFF861B1B.toInt() else 0xFF741717.toInt()
    val pinSustain get() = if (isDark) 0xFF90490C.toInt() else 0xFF80410A.toInt()
    val pinOrdinary get() = if (isDark) 0xFF707882.toInt() else 0xFF646B74.toInt()


    companion object {
        val DARK = MapSymbolPalette(true)
        val LIGHT = MapSymbolPalette(false)
        fun resolve(isDark: Boolean) = if (isDark) DARK else LIGHT

        // The operator's own puck — the one symbol that may never go missing,
        // so it does not trust `isDark` at all. Two edges, like the plate: ink
        // touching the amber, light outside it. Ink clears 3:1 on every light
        // ground, light clears it on every dark one, so the puck is found on a
        // ground this app cannot know (CUSTOM provider) and on either basemap.
        // The amber never touches the ground and never changes: amber IS the
        // operator, and a position that changed colour with the daylight would
        // be a second symbol to learn.
        val PUCK_CORE = 0xFFE2A048.toInt()
        val PUCK_RIM = 0xFF14161B.toInt()
        val PUCK_HALO = 0xFFF7F4EC.toInt()

        /**
         * Кільце сумніву у двох полярностях, нічний hex → денний.
         *
         * Ключ — саме нічний hex, бо він приходить з [EwTrail.colorFor] і Є
         * вироком на єдиному місці виклику. Денна половина виведена проти
         * землі #F6F3EC: нічні #38BDF8 / #F59E0B давали там 1.9:1, тобто край
         * кола тримався лише заливкою, яку це рішення прибирає.
         *
         * Таблиця не декоративна: [trustHaloDash] і [trustRingAlpha]
         * ключуються через неї, тож пара, доданої сюди, автоматично отримує
         * правильний штрих. Пара, ЗАБУТА тут, малює денне кільце нічним
         * кольором — TrustRingPolarityTest падає саме на цьому.
         */
        val TRUST_RING_DAY: Map<String, String> = mapOf(
            "#38BDF8" to "#0369A1", // TRUSTED
            "#F59E0B" to "#B45309", // DEGRADED
            "#F87171" to "#B91C1C", // JAMMED / інерція
            "#EF4444" to "#991B1B", // SPOOFED
            "#64748B" to "#475569", // вироку немає
        )

        val TRUST_RING_NIGHT: Map<String, String> =
            TRUST_RING_DAY.entries.associate { (night, day) -> day to night }
    }
}
