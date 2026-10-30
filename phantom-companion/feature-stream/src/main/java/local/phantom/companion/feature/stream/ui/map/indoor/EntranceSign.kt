package local.phantom.companion.feature.stream.ui.map.indoor

import androidx.compose.ui.graphics.Color
import local.phantom.companion.core.geo.indoor.BuildingEntrance
import local.phantom.companion.core.geo.indoor.EntranceKind
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyph

/**
 * ЧИМ ВХІД ПОЗНАЧЕНО НА ЗЕМЛІ — знаком, а не словом.
 *
 * Кадр тестувальників 12.09: на перехресті поверх будинків стояли плашки
 * «Вхід», «Головний вхід», «Аварійний вихід» — по сім-вісім слів одночасно.
 * [EntranceLabelRule] уже звузив їх до восьми найближчих із z18, але вісім
 * ПЛАШОК — це все одно вісім прямокутників тексту на дверях, які й самі
 * завбільшки з палець. Слово тут коштує рівно стільки ж місця, як будинок
 * під ним, і не додає нічого: людина, що дивиться на двері з відстані
 * витягнутої руки, уже знає, що це двері.
 *
 * Тому знак несе РІД входу, а слово лишається рівно одному — тому, якого
 * торкнулись. Це те саме правило, за яким живуть піни місць
 * (`POIOverlay`: диск із першого зуму шару, підпис пізніше).
 *
 * Чиста функція без Compose-стану навмисно: правило перевіряється звичайним
 * JVM-тестом, а не кадром на пристрої.
 */
internal object EntranceSign {

    /**
     * Знак роду. Метро — власний силует, бо це не двері будинку, а місце
     * призначення; аварійний вихід — [PhantomGlyph.EXIT] зі стрілкою, бо його
     * шукають тоді, коли колір уже нічого не вирішує.
     */
    fun glyph(kind: EntranceKind): PhantomGlyph = when (kind) {
        EntranceKind.SUBWAY -> PhantomGlyph.METRO
        EntranceKind.EMERGENCY -> PhantomGlyph.EXIT
        EntranceKind.MAIN,
        EntranceKind.SERVICE,
        EntranceKind.GENERIC,
        EntranceKind.ACCESSIBLE,
        -> PhantomGlyph.DOOR
    }

    /**
     * Тон роду. Ті самі числа, що стояли у плашках до цієї зміни — колір був
     * єдиним, що в них працювало, тож він і лишається.
     */
    fun tint(kind: EntranceKind): Color = when (kind) {
        EntranceKind.MAIN -> Color(0xFF15803D)
        EntranceKind.SUBWAY -> Color(0xFF1D4ED8)
        EntranceKind.EMERGENCY -> Color(0xFFB91C1C)
        EntranceKind.ACCESSIBLE -> Color(0xFF0E7490)
        EntranceKind.SERVICE, EntranceKind.GENERIC -> Color(0xFF334155)
    }

    /**
     * Рід, яким вхід читається на землі.
     *
     * `wheelchair=yes` на звичайних дверях підіймає їх до безбарʼєрних — так
     * поводилась і стара плашка. Але НЕ чіпає головний вхід і метро: там рід
     * уже сказаний, і підміняти його доступністю означало б втратити те, що
     * людина шукає першим.
     */
    fun kindOf(entrance: BuildingEntrance): EntranceKind =
        if (entrance.isWheelchairAccessible &&
            (entrance.kind == EntranceKind.GENERIC || entrance.kind == EntranceKind.SERVICE)
        ) {
            EntranceKind.ACCESSIBLE
        } else {
            entrance.kind
        }
}
