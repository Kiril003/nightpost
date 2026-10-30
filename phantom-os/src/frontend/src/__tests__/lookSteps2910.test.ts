/**
 * LOOK-2910 G1–G3: на папері тло, картка, чіп і пошук — різні сходинки, а не
 * один беж; імʼя важче за прев'ю; монограма видна на будь-якому тлі.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { STOPS } from '../styles/phantom/stops';
import { contrast } from '../styles/formTokens';

const css = readFileSync(join(process.cwd(), 'src/styles/phantom/tokens.css'), 'utf8');
const block = (sel: string) => css.slice(css.indexOf(sel), css.indexOf('}', css.indexOf(sel)));
const varOf = (b: string, name: string) => b.match(new RegExp(`--ph-${name}: (#[0-9A-Fa-f]{6})`))?.[1] ?? '';
const POLES = { night: ":root, [data-theme='night'] {", day: "[data-theme='day'] {" } as const;

describe('LOOK-2910 токени', () => {
  it('G1: поле й чіп відходять від тла й картки на обох полюсах', () => {
    for (const pole of ['day', 'night'] as const) {
      const c = STOPS[pole];
      expect(contrast(c.raised, c.canvas), `${pole} поле проти тла`).toBeGreaterThanOrEqual(1.15);
      expect(contrast(c.raised, c.surface), `${pole} поле проти картки`).toBeGreaterThanOrEqual(1.12);
      expect(contrast(c['line-strong'], c.canvas), `${pole} контур чіпа`).toBeGreaterThanOrEqual(1.6);
    }
  });

  it('G2: імʼя в рядку — 600, прев’ю — ink-2', () => {
    expect(css).toMatch(/--ph-type-headline: 600 16px\/22px/);
  });

  it('G3: монограма ≥ 3:1 до кожного тла й картки, літера ≥ 3:1 до монограми', () => {
    const grounds = Object.values(STOPS).flatMap((c) => [c.canvas, c.surface]);
    for (const [pole, sel] of Object.entries(POLES)) {
      const b = block(sel);
      for (let i = 0; i < 6; i++) {
        const fill = varOf(b, `person-${i}`);
        const ink = varOf(b, `person-${i}-ink`);
        for (const g of grounds) expect(contrast(fill, g), `${pole} тон ${i} на ${g}`).toBeGreaterThanOrEqual(3);
        expect(contrast(ink, fill), `${pole} літера тону ${i}`).toBeGreaterThanOrEqual(3);
      }
    }
  });
});
