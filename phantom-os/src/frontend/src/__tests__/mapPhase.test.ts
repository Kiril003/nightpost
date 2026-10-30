/**
 * Мапа ПК іде за фазою доби оболонки (26.09). Оболонка ставить
 * data-theme="night" / data-phase, а палітра знала лише старі теми
 * («amber-night», «cyberdeck-cold») — уночі мапа лишалась денною.
 * І бурштин — сигнал PHANTOM, не колір доріг (спек OBSIDIAN, як на телефоні).
 */
import { describe, it, expect, afterEach } from 'vitest';
import { getMapTokens, resolvePhase } from '../components/map/mapTokens';
import { palette, dusk } from '../components/map/style/palette';

const root = document.documentElement;
afterEach(() => {
  delete root.dataset.phase;
  root.removeAttribute('data-theme');
});

function at(phase?: string, theme?: string) {
  if (phase) root.dataset.phase = phase;
  if (theme) root.setAttribute('data-theme', theme);
  return palette(getMapTokens());
}

describe('мапа за фазою доби', () => {
  it('ніч оболонки — нічна мапа (раніше лишалась денною)', () => {
    const p = at('night', 'night');
    expect(p.dark).toBe(true);
    expect(p.land).toBe('#0B0E11'); // ph.color.ground (night)
  });

  it('світанок і день — денна, сутінки — ніч, зсунута до теплого лише в землі', () => {
    expect(at('dawn', 'day').dark).toBe(false);
    // Вдень — кремова 3D-мапа ПК (рішення власника 27.09), не телефонна бумага.
    expect(at('day', 'day').land).toBe('#faf7ee');
    const d = at('dusk', 'night');
    const n = palette({ ...getMapTokens(), phase: 'night' });
    expect(d.dark).toBe(true);
    expect(d.land).not.toBe(n.land);
    expect(d.roadHi).toBe(n.roadHi);
    expect(d.ink).toBe(n.ink);
    expect(dusk(n).water).toBe(n.water);
  });

  it('без фази — зі старих тем (веб, телефон)', () => {
    expect(resolvePhase()).toBe('day');
    root.setAttribute('data-theme', 'amber-night');
    expect(resolvePhase()).toBe('night');
  });

  it('вночі й у сутінки дороги не бурштинові — бурштин там сигнал Nightpost', () => {
    const amber = /^#(F5C65E|6B5527|4A3C1D|F2A93B|C77B21)$/i;
    for (const phase of ['dusk', 'night'] as const) {
      const p = palette({ ...getMapTokens(), phase });
      for (const road of [p.roadHi, p.roadMid, p.roadLow]) expect(road, `${phase} ${road}`).not.toMatch(amber);
    }
  });
});

describe('вдень — кремова 3D-мапа ПК', () => {
  it('об\'ємні будинки: глибока тінь і світлий дах, як було', () => {
    const p = palette({ ...getMapTokens(), phase: 'day' });
    expect(p.building).toBe('#d6c5a6');
    expect(p.buildingShadow).toBe('#6b5330');
    expect(p.roofEdge).toBe('#fdf8ee');
    expect(p.dark).toBe(false);
  });
});

