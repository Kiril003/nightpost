/**
 * Вигляд D (рішення власника 17.10): папір удень, графіт уночі, один бурштин,
 * без тіней. Тема — Денна / Нічна / За сонцем; рампа сонця тримає AA на кожній
 * опорній точці, не лише на двох полюсах.
 */
import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { STOPS } from '../styles/phantom/stops';
import { contrast } from '../styles/formTokens';

const ROOT = process.cwd();
const REPO = join(ROOT, '../../..');
const TOKENS = JSON.parse(readFileSync(join(REPO, 'docs/design/system/tokens.json'), 'utf8'));
const read = (p: string) => readFileSync(join(ROOT, p), 'utf8');

describe('вигляд D: палітри', () => {
  it('день — папір A, ніч — графіт B, бурштин той самий, що в листах D', () => {
    expect(STOPS.day.canvas).toBe('#F6F1E7');
    expect(STOPS.day.surface).toBe('#FCFAF5');
    expect(STOPS.day.accent).toBe('#C27A16');
    expect(STOPS.night.canvas).toBe('#111214');
    expect(STOPS.night.surface).toBe('#1C1D21');
    expect(STOPS.night.accent).toBe('#E2A048');
  });

  it('тло розмови й обкладинка картки — токени обох тем', () => {
    const css = read('src/styles/phantom/tokens.css');
    for (const sel of [":root, [data-theme='night'] {", "[data-theme='day'] {"]) {
      const block = css.slice(css.indexOf(sel), css.indexOf('}', css.indexOf(sel)));
      for (const k of ['wall', 'wall-dot', 'cover', 'cover-line']) expect(block, `${sel} ${k}`).toMatch(new RegExp(`--ph-${k}: #`));
    }
  });

  it('текст тримає AA на всіх шести точках сонця', () => {
    for (const [stop, c] of Object.entries(STOPS)) {
      for (const bg of ['canvas', 'surface', 'raised', 'overlay']) {
        for (const fg of ['text', 'text-2', 'text-3', 'accent-text']) {
          expect(contrast(c[fg], c[bg]), `${stop} ${fg} на ${bg}`).toBeGreaterThanOrEqual(4.5);
        }
      }
      expect(contrast(c['on-accent'], c.accent), `${stop} кнопка`).toBeGreaterThanOrEqual(4.5);
      expect(contrast(c['on-accent'], c['accent-press']), `${stop} натиснута кнопка`).toBeGreaterThanOrEqual(4.5);
      expect(contrast(c['on-self-2'], c.self), `${stop} час у своїй бульбашці`).toBeGreaterThanOrEqual(4.5);
    }
  });

  it('своя бульбашка відрізняється від чужої ≥ 1.15:1, а вночі тепла, не той самий графіт', () => {
    const rgb = (h: string) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
    for (const [stop, c] of Object.entries(STOPS)) {
      expect(contrast(c.self, c.surface), `${stop} своя проти чужої`).toBeGreaterThanOrEqual(1.15);
    }
    for (const stop of ['night', 'dusk'] as const) {
      const [r, , b] = rgb(STOPS[stop].self);
      expect(r - b, `${stop}: своя бульбашка має бурштиновий відтінок`).toBeGreaterThanOrEqual(12);
    }
  });

  it('візерунок тла тихший за бульбашки: лінії вдень −30 %, уночі вдвічі', () => {
    const alpha = (h: string) => parseInt(h.slice(7, 9), 16);
    expect(alpha(TOKENS.color.night['wall-dot'].value)).toBeLessThanOrEqual(0x07);
    expect(alpha(TOKENS.color.dusk['wall-dot'].value)).toBeLessThanOrEqual(0x07);
    expect(alpha(TOKENS.color.day['wall-dot'].value)).toBeLessThanOrEqual(0x10);
    expect(alpha(TOKENS.color.golden['wall-dot'].value)).toBeLessThanOrEqual(0x10);
    const look = read('src/services/appearance.ts');
    expect(look).toMatch(/dim: \{ day: 55, night: 80 \}/);
    const kt = readFileSync(join(REPO, 'phantom-companion/app/src/main/java/local/phantom/companion/data/appearance/Appearance.kt'), 'utf8');
    expect(kt).toMatch(/val dim: Shade = Shade\(day = 55, night = 80\)/);
  });

  it('генератор перевіряє контраст на всіх точках сонця', () => {
    execFileSync('node', [join(REPO, 'docs/design/system/build-tokens.mjs')], { encoding: 'utf8' });
    const report = readFileSync(join(REPO, 'docs/design/system/build/contrast-report.md'), 'utf8');
    for (const stop of ['dusk', 'golden', 'dawn', 'morning']) expect(report).toMatch(new RegExp(`^\\| ${stop} \\|`, 'm'));
    expect(report).toMatch(/All pairs pass\./);
  });
});

describe('вигляд D: без тіней', () => {
  it('висоти обох тем — none', () => {
    for (const th of ['night', 'day']) {
      for (const v of Object.values(TOKENS.elevation[th])) expect(v).toBe('none');
    }
  });

  it('пресет гасить усі тіні Tailwind, а не лише e1–e3', () => {
    const preset = read('src/styles/phantom/tailwind-preset.ts');
    const block = /boxShadow: \{([^}]*)\}/.exec(preset)?.[1] ?? '';
    for (const k of ['DEFAULT', 'sm', 'md', 'lg', 'xl', '2xl', 'inner', 'e1', 'e2', 'e3']) {
      expect(block, k).toMatch(new RegExp(`'?${k}'?: '0 0 #0000'`));
    }
  });

  it('у компонентах немає власних тіней shadow-[…]', () => {
    let out = '';
    try {
      out = execFileSync('grep', ['-rnoE', 'shadow-\\[[^] ]*\\]', 'src/components'], { encoding: 'utf8', cwd: ROOT }).trim();
    } catch (e) {
      if ((e as { status?: number }).status !== 1) throw e;
    }
    expect(out).toBe('');
  });
});

describe('вигляд D: вибір теми', () => {
  it('Денна · Нічна · За сонцем, у цьому порядку (слово light під забороною копірайту)', () => {
    const me = read('src/components/messenger/MeSections.tsx');
    const order = ['themeDay', 'themeNight', 'themeAuto'].map((k) => me.indexOf(`t('meSections.${k}')`));
    expect(order.every((i) => i > 0)).toBe(true);
    expect([...order].sort((a, b) => a - b)).toEqual(order);
    const l2 = read('src/i18n/messages/parts/l2.ts');
    for (const s of ['"meSections.themeDay": "Денна"', '"meSections.themeNight": "Нічна"', '"meSections.themeAuto": "За сонцем"',
      '"meSections.themeDay": "Day"', '"meSections.themeNight": "Night"', '"meSections.themeAuto": "By sun"']) {
      expect(l2).toContain(s);
    }
  });
});
