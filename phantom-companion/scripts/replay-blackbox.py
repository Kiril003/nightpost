#!/usr/bin/env python3
"""Програвач чорної скрині: поїздка, яку можна судити без дороги.

Знімати запис було чим (scripts/pull-blackbox.sh), а програти — нічим, тож
кожна скарга власника лишалась словом проти слова. Цей інструмент читає той
самий файл, що пише пристрій, відновлює хронологію і шукає в ній САМЕ ті
явища, на які скаржився власник — кожне з іменем, часом і числом.

    scripts/replay-blackbox.py _grant/records/<тека|файл> [--all]

Він нічого не вигадує: якщо в записі немає рядків потрібного типу, він так і
каже («шар маршруту не писався»), а не показує порожній розділ як «чисто».

ЧОГО ЦЕЙ ПРИЛАД НЕ БАЧИТЬ ЗА ПОБУДОВОЮ:
  1. Він НЕ переграє рушій. Він читає, що записано, і не може сказати, яким
     був би фікс за інших порогів — для цього потрібен прогін самого рушія.
  2. Час у рядку — мить ЗАПИСУ, не мить датчика. Черга скидається раз на
     секунду, тож порядок подій між каналами в межах секунди недостовірний.
  3. Втрачені рядки видно лише сумою (рядок `stat`), не поіменно: тиша в
     розділі може означати «не сталось» або «не записалось».
  4. Пороги нижче (55 м/с на телепорт, 45° на розбіжність, 12 м на
     притягування) — МОЇ, а не застосунку. Вони називають підозру, не вирок.
  5. Малювання він не бачить узагалі: згладжування стрілки живе в мапі.
"""
import sys
import os
import math
from collections import defaultdict

R = 6371000.0


def haversine(a, b):
    la1, lo1 = math.radians(a[0]), math.radians(a[1])
    la2, lo2 = math.radians(b[0]), math.radians(b[1])
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * R * math.asin(min(1.0, math.sqrt(h)))


def ang_diff(a, b):
    """Найкоротший кут між напрямками, 0..180."""
    d = abs((a - b) % 360.0)
    return d if d <= 180.0 else 360.0 - d


def num(s):
    s = s.strip()
    if s == "":
        return None
    try:
        return float(s)
    except ValueError:
        return None


def parse(paths):
    rows = []
    for p in paths:
        with open(p, encoding="utf-8", errors="replace") as f:
            for raw in f:
                parts = raw.rstrip("\n").split(",")
                if len(parts) < 2:
                    continue
                ts = num(parts[0])
                if ts is None:
                    continue
                rows.append((int(ts), parts[1], parts[2:]))
    rows.sort(key=lambda r: r[0])
    return rows


def hhmmss(ms, t0):
    s = (ms - t0) / 1000.0
    return f"{int(s // 60):02d}:{s % 60:04.1f}"


def report(rows):
    if not rows:
        print("порожній запис — жодного рядка")
        return 1
    t0 = rows[0][0]
    dur = (rows[-1][0] - t0) / 1000.0
    kinds = defaultdict(int)
    for _, k, _ in rows:
        kinds[k] += 1

    print(f"── запис: {len(rows)} рядків, {dur:.0f} с ({dur/60:.1f} хв)")
    print("   типи: " + ", ".join(f"{k}={v}" for k, v in sorted(kinds.items())))

    # Чи не губив сам реєстратор рядки — інакше «нічого не сталось» може
    # означати «сталось, але не записалось».
    stats = [(t, c) for t, k, c in rows if k == "stat"]
    if stats:
        t, c = stats[-1]
        produced, written = num(c[0]), num(c[1])
        if produced is not None and written is not None and produced > written:
            print(f"   ⚠ реєстратор відкинув {int(produced - written)} рядків із {int(produced)}")

    findings = 0

    # №1 ТЕЛЕПОРТ — стрибок показаної точки, якого не пояснює швидкість.
    # Колонки guard (BlackBox.recordGuard): 0 джерело, 1-3 показана точка,
    # 4-6 зшитий фікс, 7 швидкість, 8 напрямок, 9-11 мережевий якір,
    # 12 розбіжність, 13-15 C/N0 і збіг, 16 підміна, 17 причина, 18 стан,
    # 19 чи є позиція. Зсунешся на одну — і друкуватимеш напрямок як швидкість.
    disp = [(t, num(c[1]), num(c[2]), num(c[7]) if len(c) > 7 else None,
             num(c[3]) if len(c) > 3 else None, c[0])
            for t, k, c in rows if k == "guard" and len(c) > 4]
    disp = [d for d in disp if d[1] is not None and d[2] is not None]
    jumps = []
    noise = 0
    switches = 0
    for a, b in zip(disp, disp[1:]):
        (t1, la1, lo1, v1, acc1, src1) = a
        (t2, la2, lo2, _v2, acc2, src2) = b
        dt = (t2 - t1) / 1000.0
        if dt <= 0 or dt > 10:
            continue
        d = haversine((la1, lo1), (la2, lo2))
        implied = d / dt
        # 55 м/с ≈ 200 км/год: усе вище — швидше за рух.
        if implied <= 55.0:
            continue
        # ДРУГА УМОВА, без якої прилад бреше: стрибок мусить перевищувати
        # визнану похибку ОБОХ точок. Точка з похибкою ±40 м, що «стрибнула»
        # на 30 м, нікуди не стрибала — вона тремтить у межах власного сумніву,
        # і називати це телепортом означає повторити хибне звинувачення, яке
        # цей дім уже одного разу зробив (памʼять phantom-false-spoof-accusation).
        doubt = (acc1 or 0.0) + (acc2 or 0.0)
        if d <= doubt:
            noise += 1
            continue
        if src1 != src2:
            switches += 1
        jumps.append((t1, d, implied, v1, src1, src2, doubt))
    print()
    print(f"№1 ТЕЛЕПОРТ показаної точки: {len(jumps)}"
          f"   (ще {noise} рухів швидші за 200 км/год лишились у межах власної "
          f"похибки — це тремтіння, не телепорт)")
    if switches:
        print(f"   з них {switches} — мить ПЕРЕМИКАННЯ джерела, тобто точка поїхала "
              f"до іншого свідка, а не пройшла шлях")
    for t, d, imp, v, s1, s2, doubt in jumps[:8]:
        rep = f", швидкість за фіксом {v:.1f} м/с" if v is not None else ""
        who = f", джерело {s1}→{s2}" if s1 != s2 else ""
        print(f"   {hhmmss(t, t0)}  стрибок {d:.0f} м за секунду → {imp*3.6:.0f} км/год "
              f"(визнана похибка обох: {doubt:.0f} м){rep}{who}")
    findings += len(jumps)

    # №2 БЛИМАННЯ — напрямок кидає й вертає, поки ми справді їдемо.
    fixes = [(t, num(c[0]), num(c[1]), num(c[2]), num(c[3]), c[5] if len(c) > 5 else "")
             for t, k, c in rows if k == "fix" and len(c) > 5]
    flips = []
    for a, b, c3 in zip(fixes, fixes[1:], fixes[2:]):
        if None in (a[4], b[4], c3[4]) or None in (a[3], b[3]):
            continue
        if (a[3] or 0) < 2.0:      # той, хто стоїть, не має напрямку
            continue
        d1 = ang_diff(a[4], b[4])
        d2 = ang_diff(b[4], c3[4])
        back = ang_diff(a[4], c3[4])
        # кинуло вбік і повернуло назад — саме те, що око читає як блимання
        if d1 > 45 and d2 > 45 and back < 20:
            flips.append((b[0], d1))
    print()
    print(f"№2 БЛИМАННЯ стрілки (кидок і повернення): {len(flips)}")
    for t, d in flips[:8]:
        print(f"   {hhmmss(t, t0)}  напрямок кинуло на {d:.0f}° і повернуло")
    findings += len(flips)

    # №4/№5 ЧУЖА ДОРОГА — зсув є, сходом не рахується, а рух іде впоперек.
    nav = [(t, c) for t, k, c in rows if k == "nav"]
    print()
    if not nav:
        print("№4/№5 ШАР МАРШРУТУ: у цьому записі його немає (пристрій ще не пише "
              "nav-рядки) — судити зшивання й перебудову НЕМА ПО ЧОМУ")
    else:
        glued = []
        for t, c in nav:
            off, offroute = num(c[4]), (c[5] == "true")
            rb, mb = num(c[8]), num(c[9])
            spd = num(c[10])
            if off is None or rb is None or mb is None or spd is None:
                continue
            if spd < 2.0:
                continue
            if off > 12.0 and not offroute and ang_diff(rb, mb) > 45.0:
                glued.append((t, off, ang_diff(rb, mb)))
        print(f"№4/№5 ПРИТЯГУВАННЯ до чужої дороги: {len(glued)}")
        for t, off, dv in glued[:8]:
            print(f"   {hhmmss(t, t0)}  {off:.0f} м збоку, рух під {dv:.0f}° до дороги — і це «на маршруті»")
        findings += len(glued)

        reroutes = [t for t, c in nav if len(c) > 7 and c[7] == "true"]
        if reroutes:
            print(f"   перебудов замовлено: {len(reroutes)}")

    # ВОРОТА ШВИДКОСТІ — хто відповів на маршрут і за скільки.
    routes = [(t, c) for t, k, c in rows if k == "route"]
    print()
    if not routes:
        print("ШВИДКІСТЬ МАРШРУТУ: рядків route немає — цей запис знято до того, "
              "як пристрій почав їх писати")
    else:
        by_src = defaultdict(list)
        for t, c in routes:
            ms = num(c[1])
            if ms is not None:
                by_src[c[0]].append(ms)
        print(f"ШВИДКІСТЬ МАРШРУТУ: {len(routes)} відповідей")
        for src, arr in sorted(by_src.items()):
            arr.sort()
            mid = arr[len(arr) // 2]
            print(f"   {src:8s} n={len(arr):3d}  медіана {mid:.0f} мс  "
                  f"найгірша {arr[-1]:.0f} мс")
        graph = sorted(by_src.get("GRAPH", []))
        if graph:
            over = [x for x in graph if x > 200]
            verdict = "ВОРОТА ТРИМАЮТЬСЯ" if not over else f"ВОРОТА ПРОБИТО ({len(over)} разів)"
            print(f"   ворота «перебудова < 200 мс» по графу: {verdict}")

        # НА ЧОМУ СТОЇТЬ ЧАС. Це доказ, що дорожній пакет із тегами доїхав:
        # джерело (GRAPH) однакове до й після, а основа міняється рівно з
        # однієї причини — дорога нарешті має клас.
        bases = defaultdict(int)
        for _t, c in routes:
            bases[c[4] if len(c) > 4 else ""] += 1
        if not any(bases) or list(bases) == [""]:
            print("   основа швидкості: у записі її немає — знято до того, "
                  "як пристрій почав її писати")
        else:
            named = {("невідомо (мережевий рушій)" if not k else k): v
                     for k, v in bases.items()}
            print("   основа швидкості: " +
                  ", ".join(f"{k} ×{v}" for k, v in sorted(named.items())))
            if bases.get("ASSUMED"):
                print(f"   УВАГА: {bases['ASSUMED']} маршрутів пораховано пласкою "
                      "вгадкою — пакет доріг у пристрої без тегів")

    # ДЕ ЖИВУТЬ СЕКУНДИ. Рядок prep пишеться лише при промаху кеша коридору,
    # тож його ВІДСУТНІСТЬ поруч із повільним route — теж відповідь.
    prep = [(t, c) for t, k, c in rows if k == "prep"]
    if prep:
        print()
        print(f"ПІДГОТОВКА КОРИДОРУ: {len(prep)} промахів кеша")
        for t, c in prep[:6]:
            w, rd, bd = c[0], num(c[1]), num(c[2])
            print(f"   {w:>7} доріг · читання {rd:.0f} мс · побудова {bd:.0f} мс")
        reads = sorted(num(c[1]) or 0 for _t, c in prep)
        builds = sorted(num(c[2]) or 0 for _t, c in prep)
        mid = len(reads) // 2
        print(f"   медіани: читання {reads[mid]:.0f} мс, побудова {builds[mid]:.0f} мс")
    elif routes:
        print()
        print("ПІДГОТОВКА КОРИДОРУ: жодного промаху кеша — отже час маршруту "
              "пішов не в підготовку, а в сам пошук")

    # ЛІЧИЛЬНИК ШЛЯХУ проти рушія — заради чого його й увімкнули паралельно.
    coast = [(t, c) for t, k, c in rows if k == "coast"]
    print()
    if not coast:
        print("ЛІЧИЛЬНИК ШЛЯХУ: рядків coast немає — або збірка стара, або графа "
              "поруч не було жодного разу")
    else:
        held = [c for _, c in coast if c and c[0] == "true"]
        vs = [num(c[9]) for _, c in coast if len(c) > 9 and num(c[9]) is not None]
        print(f"ЛІЧИЛЬНИК ШЛЯХУ: {len(coast)} тактів, з них тримав позицію {len(held)}")
        if vs:
            vs.sort()
            print(f"   розходження з рушієм: медіана {vs[len(vs)//2]:.0f} м, "
                  f"найбільше {vs[-1]:.0f} м")
            print("   МЕЖА: це різниця двох оцінок, а не помилка жодної з них — "
                  "хто ближчий до правди, скаже лише дорога під колесами")
        refused = defaultdict(int)
        for _, c in coast:
            if c and c[0] != "true" and len(c) > 1 and c[1]:
                refused[c[1][:40]] += 1
        for r, n in sorted(refused.items(), key=lambda kv: -kv[1])[:4]:
            print(f"   відмова «{r}»: {n}")

    # №7 КРИК ПРО ПІДМІНУ без підстав — вирок «підміна» там, де рух спокійний.
    shouts = []
    for t, k, c in rows:
        if k != "guard" or len(c) < 20:
            continue
        spoofed = c[16] == "true"
        div = num(c[12])
        if spoofed and (div is None or div < 100.0):
            shouts.append((t, div, c[17] if len(c) > 17 else ""))
    print()
    print(f"№7 ЗВИНУВАЧЕННЯ В ПІДМІНІ при малій розбіжності: {len(shouts)}")
    for t, div, reason in shouts[:8]:
        d = f"{div:.0f} м" if div is not None else "невідома"
        print(f"   {hhmmss(t, t0)}  розбіжність {d}, причина «{reason}»")
    findings += len(shouts)

    # ЧОМУ саме недовіра — розподіл причин. Без нього «часто кричить» лишається
    # враженням, а з ним видно, який рядок коду кричить найгучніше.
    reasons = defaultdict(int)
    for t, k, c in rows:
        if k == "guard" and len(c) > 17 and c[17]:
            reasons[c[17]] += 1
    rejections = defaultdict(int)
    for t, k, c in rows:
        if k == "ew" and len(c) > 6 and c[6].strip():
            rejections[c[6].strip()[:48]] += 1
    print()
    print("ПРИЧИНИ ВИРОКІВ заслону (guard.reason):")
    for r, n in sorted(reasons.items(), key=lambda kv: -kv[1])[:8]:
        print(f"   {r:22s} {n:5d}")
    if rejections:
        print("ОСТАННІ ВІДМОВИ рушія (ew.lastRejection):")
        for r, n in sorted(rejections.items(), key=lambda kv: -kv[1])[:6]:
            print(f"   {r:48s} {n:5d}")

    # №9 НЕ ПОВЕРТАЄТЬСЯ на GPS — довіра лежить, а небо вже чисте.
    trust_line = [(t, c[0]) for t, k, c in rows if k == "ew" and c]
    stuck = []
    if trust_line:
        bad_since = None
        for t, tr in trust_line:
            if tr in ("JAMMED", "SPOOFED", "DENIED", "DEAD"):
                bad_since = bad_since or t
            else:
                if bad_since is not None:
                    stuck.append((bad_since, (t - bad_since) / 1000.0))
                    bad_since = None
        if bad_since is not None:
            stuck.append((bad_since, (trust_line[-1][0] - bad_since) / 1000.0))
    # І головне за скаргою №9: чи було чисте небо ПОКИ ми йому не вірили.
    # Сире небо (рядки gps) приходить від чипа незалежно від вироку рушія, тож
    # епізод, усередині якого лежать фікси з малою похибкою, — це не «неба
    # немає», а «небо є, і ми його не беремо».
    clean_sky = [(t, num(c[2])) for t, k, c in rows
                 if k == "gps" and len(c) > 2 and num(c[2]) is not None]
    print()
    print(f"№9 ЕПІЗОДИ НЕДОВІРИ до неба: {len(stuck)}")
    for t, secs in stuck[:8]:
        tail = " (до кінця запису так і не повернулась)" if t + secs * 1000 >= rows[-1][0] else ""
        win = [(a, acc) for a, acc in clean_sky if t <= a <= t + secs * 1000]
        inside = [a for a, acc in win if acc <= 30.0]
        sky = f", фіксів усередині {len(win)}, з них чистих {len(inside)}" if win else ""
        print(f"   {hhmmss(t, t0)}  тривала {secs:.0f} с{tail}{sky}")
    long_blind = [(t, secs) for t, secs in stuck if secs > 120]
    for t, secs in long_blind:
        win = [(a, acc) for a, acc in clean_sky if t <= a <= t + secs * 1000]
        inside = [a for a, acc in win if acc <= 30.0]
        if inside:
            share = 100.0 * len(inside) / max(1, len(win))
            print(f"   ⚠ епізод на {secs:.0f} с тримався, ПОКИ надходило "
                  f"{len(inside)} фіксів із похибкою ≤30 м ({share:.0f}% від "
                  f"{len(win)} у вікні).")
            print(f"     МЕЖА ЦЬОГО ВИСНОВКУ: {len(win)} фіксів за {secs/60:.0f} хв — "
                  f"розріджено. Це підозра на «не повертається на GPS», а не вирок: "
                  f"чип міг бути глухий майже весь час.")

    print()
    print(f"── знайдено подій за скаргами: {findings}")
    return 0


def selftest():
    """Розкладка колонок — не коментар, а перевірка. Рядки нижче побудовані
    рівно так, як їх пише пристрій; якщо там щось посунеться, впаде тут, а не
    в звіті, який виглядатиме правдоподібно."""
    guard = ("1,guard,FUSED,50.1,30.1,3.2,50.2,30.2,4.0,12.5,270.0,"
             "50.3,30.3,300.0,152.17,,,,true,GATE,OK,true")
    c = guard.split(",")[2:]
    assert c[0] == "FUSED", c[0]
    assert c[1] == "50.1" and c[2] == "30.1"          # показана точка
    assert c[7] == "12.5"                              # швидкість, не напрямок
    assert c[8] == "270.0"                             # напрямок
    assert c[12] == "152.17"                           # розбіжність
    assert c[16] == "true"                             # підміна
    assert c[17] == "GATE"                             # причина
    nav = ("1,nav,50.1,30.1,50.2,30.2,25.0,false,0,true,0.0,90.0,14.0,"
           "true,5.0,UNKNOWN,3,120.0,turn,Хрещатик")
    n = nav.split(",")[2:]
    assert n[4] == "25.0"                              # бічний зсув
    assert n[5] == "false"                             # схід з маршруту
    assert n[7] == "true"                              # замовлена перебудова
    assert n[8] == "0.0" and n[9] == "90.0"            # напрямок дороги / руху
    assert n[10] == "14.0"                             # швидкість
    return True


def main(argv):
    selftest()
    if len(argv) < 2:
        print(__doc__)
        return 2
    target = argv[1]
    paths = []
    if os.path.isdir(target):
        for name in sorted(os.listdir(target)):
            if name.startswith("blackbox") and name.endswith(".log"):
                paths.append(os.path.join(target, name))
    else:
        paths = [target]
    if not paths:
        print(f"нема чого читати в {target}")
        return 2
    print(f"файли: {', '.join(os.path.basename(p) for p in paths)}")
    return report(parse(paths))


if __name__ == "__main__":
    sys.exit(main(sys.argv))
