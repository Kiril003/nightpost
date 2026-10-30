import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import {
  DAY_BLOCK_END,
  DAY_BLOCK_START,
  INK_DAY_OVERRIDE,
  NIGHT_TO_DAY,
  dayThemeCss,
  usedColourUtilities,
} from '../styles/dayTheme';

/**
 * Сторож денної палітри. Чотири речі, і кожна — факт, не правопис.
 *
 * 1. CSS у дереві дорівнює мапі (дзеркало контракту: одне джерело правди).
 * 2. Кожен колір, яким месенджер фарбує утилітою, має денний відповідник —
 *    інакше повториться [[phantom-dark-theme-bg-white-trap]], де в переліку
 *    забули `bg-white` і напис став 1.02:1.
 * 3. Контраст у день: чорнило на підкладці ≥ 4.5:1, виміряний за WCAG.
 * 4. Блок не перекриває класів, яких у дереві немає — стеля проти повернення
 *    декартового добутку, що коштував 5 999 мертвих правил і 400 КБ (17.09).
 */

const CSS = readFileSync(join(process.cwd(), 'src/styles/messenger.css'), 'utf8');
const MESSENGER = join(process.cwd(), 'src/components/messenger');

const walk = (dir: string): string[] =>
  readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    return statSync(p).isDirectory() ? walk(p) : p.endsWith('.tsx') ? [p] : [];
  });

const srgb = (channel: number): number => {
  const c = channel / 255;
  return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
};
const luminance = (hex: string): number => {
  const n = parseInt(hex.slice(1), 16);
  return 0.2126 * srgb((n >> 16) & 255) + 0.7152 * srgb((n >> 8) & 255) + 0.0722 * srgb(n & 255);
};
const contrast = (a: string, b: string): number => {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
};
const day = (night: string): string => {
  const value = NIGHT_TO_DAY[night];
  if (!value) throw new Error(`денного відповідника для ${night} немає`);
  return value;
};

describe('денна палітра', () => {
  it('CSS у дереві — точно те, що дає мапа', () => {
    const start = CSS.indexOf(DAY_BLOCK_START);
    const end = CSS.indexOf(DAY_BLOCK_END, start);
    expect(start, 'блоку дня в messenger.css немає').toBeGreaterThan(0);
    expect(CSS.slice(start, end + DAY_BLOCK_END.length)).toBe(dayThemeCss());
  });

  it('месенджер не носить жодного hex-кольору утилітою — лише токени Nightpost v1, які мають обидві теми', () => {
    const files = walk(MESSENGER);
    expect(files.length).toBeGreaterThan(50);
    const used = new Set<string>();
    for (const file of files) {
      for (const m of readFileSync(file, 'utf8').matchAll(
        /(?:bg|text|border|ring|fill|stroke|from|to|via)-\[(#[0-9A-Fa-f]{6})\]/g,
      )) {
        used.add(m[1].toUpperCase());
      }
    }
    // 01.10 тут було 30+ кольорів старої ночі, кожен із денним двійником у
    // messenger.css. Тепер розмітка говорить токенами (`text-ink`, `bg-raised`),
    // які несуть і Ніч, і День самі, тож hex-кольору в класі бути не може.
    expect([...used].sort()).toEqual([]);
    expect(Object.keys(NIGHT_TO_DAY).length).toBeGreaterThan(0);
  });

  it('удень чорнило на підкладці читається (WCAG ≥ 4.5:1)', () => {
    const surfaces = ['#121714', '#101412', '#18241D', '#141C16', '#202E25'];
    const inks = ['#F8FAF8', '#8EA093', '#F4AF25'];
    const weak: string[] = [];
    for (const s of surfaces) {
      for (const i of inks) {
        const ratio = contrast(day(i), day(s));
        if (ratio < 4.5) weak.push(`${i} на ${s}: ${ratio.toFixed(2)}:1`);
      }
    }
    expect(weak).toEqual([]);
  });

  /**
   * Той самий поріг, але не на пʼятьох вибраних парах, а на КОЖНОМУ чорнилі,
   * яке справді стоїть у дереві. Попередня перевірка брала три чорнила на
   * пʼятьох підкладках — 15 пар — і тому не бачила, що 15 кольорів служать
   * одночасно підкладкою і знаком, а мапа вміє дати їм лише одне денне
   * значення. Виміряно 17.09: 17 класів `text-[…]` давали вдень < 4.5:1,
   * шестеро — 1.04–1.35:1, тобто напис невидимий.
   */
  it('удень кожне чорнило дерева читається на своїй підкладці', () => {
    const used = usedColourUtilities();
    /** Чорнило бурштинової кнопки міряється на денному бурштині, не на ґрунті. */
    const ON_ACCENT = new Set(['#0C110D', '#0A0D0B', '#121714']);
    const GROUND = '#F4F0E6';
    const ACCENT = '#7E5608';

    const weak: string[] = [];
    for (const utility of used) {
      const m = utility.match(/^(?:hover:|focus:|active:|disabled:)?text-\[(#[0-9A-Fa-f]{6})\]$/);
      if (!m) continue;
      const night = m[1].toUpperCase();
      const dayInk = INK_DAY_OVERRIDE[night] ?? NIGHT_TO_DAY[night];
      if (!dayInk) {
        weak.push(`${utility}: денного відповідника немає`);
        continue;
      }
      const surface = ON_ACCENT.has(night) ? ACCENT : GROUND;
      const ratio = contrast(dayInk, surface);
      if (ratio < 4.5) weak.push(`${utility} → ${dayInk} на ${surface}: ${ratio.toFixed(2)}:1`);
    }
    // 04.10 (web-shell-entry): останні `text-[#…]` жили у 115 мертвих файлах
    // скла ПК (агент, місії, Поліс, розкладки) і пішли разом із ними — тепер
    // їх у дереві нуль. Тож «сторож щось прочитав» доводиться не числом
    // hex-чорнил, а тим, що сканер бачить живу родину текстових утиліт
    // (`text-white/…`); а поріг нижче лишається: нове hex-чорнило, що вдень
    // не читається, впаде тут, як і раніше.
    expect(
      [...used].some((u) => /^(?:hover:)?text-white\/\d+$/.test(u)),
      'сканер не бачить жодної текстової утиліти — сторож нічого не прочитав',
    ).toBe(true);
    expect(
      weak,
      'удень ці написи не читаються. Колір, що служить і підкладкою, і ' +
        'знаком, має отримати денне чорнило в INK_DAY_OVERRIDE — мапа ' +
        'NIGHT_TO_DAY ключована кольором, а не роллю, і дає одне значення.',
    ).toEqual([]);
  });

  it('жодне перекриття не цілить у клас, якого в дереві немає', () => {
    const used = usedColourUtilities();
    // 04.10: 115 мертвих файлів скла ПК пішли — з ~80 кольорових утиліт
    // лишилось 31 (здебільшого `white/…`); поріг — «сканер щось прочитав».
    expect(used.size, 'сканер нічого не прочитав — вимір ні про що').toBeGreaterThan(10);

    const start = CSS.indexOf(DAY_BLOCK_START);
    const block = CSS.slice(start, CSS.indexOf(DAY_BLOCK_END, start));

    // Розекрановуємо селектор назад в імʼя класу: `.hover\:bg-\[\#18241D\]:hover`
    // → `hover:bg-[#18241D]`. Беремо лише рядки-селектори блоку.
    const targets = new Set<string>();
    for (const m of block.matchAll(
      /\.((?:hover|focus|active|disabled)\\:)?(bg|text|border|ring|fill|stroke|from|to|via)-(?:\\\[\\(#[0-9A-Fa-f]{6})\\\]|white\\\/(\d+))/g,
    )) {
      const state = m[1] ? m[1].replace('\\:', ':') : '';
      targets.add(`${state}${m[2]}-${m[3] ? `[${m[3].toUpperCase()}]` : `white/${m[4]}`}`);
    }
    // 04.10: після видалення мертвого скла ПК у блоці лишилось 20 перекриттів.
    expect(targets.size, 'у блоці дня не знайдено жодного перекриття').toBeGreaterThan(10);

    const ghosts = [...targets].filter((t) => !used.has(t)).sort();
    expect(
      ghosts,
      'ці перекриття цілять у клас, якого в дереві немає: Tailwind такої ' +
        'утиліти не породжує, отже жоден елемент її не носить і правило не ' +
        'спрацює НІКОЛИ. Саме так блок дня виріс до 6 210 правил, з яких ' +
        'працювало 211. Перегенеруй: node --experimental-strip-types scripts/genDayTheme.ts',
    ).toEqual([]);
  });

  it('уночі нічого не змінилось: блок дня діє лише під data-theme=day', () => {
    const start = CSS.indexOf(DAY_BLOCK_START);
    const block = CSS.slice(start, CSS.indexOf(DAY_BLOCK_END, start));
    const selectors = block.split('\n').filter((l) => l.trim().endsWith(',') || l.trim().endsWith('{'));
    expect(selectors.filter((l) => l.includes(':root') && !l.includes("data-theme='day'"))).toEqual([]);
  });
});

/**
 * Інлайн-стилі — окремий клас: перекриття утиліт їх не дістає, тож колір там
 * має бути змінною. Сторож дивиться на файли, а не на памʼять про них.
 */
describe('інлайн-стилі не носять колір числом', () => {
  const LITERAL = /^\s*const\s+[A-Z_]+\s*=\s*['"](#[0-9A-Fa-f]{3,8}|rgba?\()/;

  it('жоден компонент месенджера не тримає колір-літерал у сталій', () => {
    const offenders: string[] = [];
    for (const file of walk(MESSENGER)) {
      const lines = readFileSync(file, 'utf8').split('\n');
      lines.forEach((line, i) => {
        if (LITERAL.test(line)) offenders.push(`${file.split('/').pop()}:${i + 1}: ${line.trim()}`);
      });
    }
    expect(offenders).toEqual([]);
  });

  /**
   * Картка дзвінка й діаграма читають колір інлайн-стилем і SVG-атрибутом,
   * тож класом їх не перефарбуєш. До 04.10 обидві теми тримали тут власні
   * числа (уночі «завершити» #C25A3A, удень #A6431F) — дві палітри поруч із
   * v1.2. Тепер кожне ім'я, яке компонент СПРАВДІ читає, означене один раз —
   * псевдонімом `--ph-*` на :root, — і блок дня його не перевизначає: токен
   * сам знає обидві теми.
   */
  const aliasesOnce = (component: string, prefix: string): void => {
    const source = readFileSync(join(MESSENGER, component), 'utf8');
    const read = [...new Set([...source.matchAll(new RegExp(`var\\((--${prefix}-[a-z-]+)\\)`, 'g'))].map((m) => m[1]))];
    const dayBlock = CSS.slice(CSS.indexOf(DAY_BLOCK_START), CSS.indexOf(DAY_BLOCK_END));
    const handWritten = CSS.slice(0, CSS.indexOf(DAY_BLOCK_START));
    const bad: string[] = [];
    for (const name of read) {
      const values = [...handWritten.matchAll(new RegExp(`${name}:\\s*([^;]+);`, 'g'))].map((m) => m[1].trim());
      if (values.length !== 1 || !/^var\(--ph-[a-z0-9-]+\)$/.test(values[0])) bad.push(`${name}: ${values.join(' | ') || 'не означено'}`);
      if (dayBlock.includes(`${name}:`)) bad.push(`${name}: блок дня перевизначає`);
    }
    expect(bad, `${component}: змінна не є єдиним псевдонімом --ph-*`).toEqual([]);
  };

  it('кожна змінна картки дзвінка — один псевдонім --ph-* для обох тем', () => {
    aliasesOnce('CallOverlay.tsx', 'call');
  });

  /**
   * Те саме для діаграми: після відмови від recharts (17.09) вона малюється
   * власним SVG, а SVG фарбується атрибутами `fill`/`stroke`.
   */
  it('кожна змінна діаграми — один псевдонім --ph-* для обох тем', () => {
    aliasesOnce('ChartEmbed.tsx', 'chart');

    // І жодна з них не лишилась числом у самому компоненті.
    const chart = readFileSync(join(MESSENGER, 'ChartEmbed.tsx'), 'utf8');
    const literals = [...chart.matchAll(/^\s*const\s+[A-Z_]+\s*=\s*'(#[0-9A-Fa-f]{3,8}|rgba?\()/gm)].map(
      (m) => m[0].trim(),
    );
    expect(literals, 'колір діаграми записаний числом — тема його не дістане').toEqual([]);
  });
});
