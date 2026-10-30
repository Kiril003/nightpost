/**
 * Сонце, варіант B (власник 28.10): шість точок — ніч, світанок, ранок, полудень,
 * золота година, сутінки. Вікна за висотою сонця й напрямом (схід/захід),
 * полярність міняється на −3° зранку і на 0° увечері. Ручні День/Ніч — плоский D.
 */
import { describe, it, expect, afterEach, vi } from 'vitest';
import { STOPS } from '../styles/phantom/stops';
import { contrast } from '../styles/formTokens';
import { applyRamp, lerpHex, lookOf, parseLook, rampAt } from '../services/themeRamp';
import { sunNow, themeOfPhase } from '../services/theme';
import * as sun from '../services/sun';

const inlinePh = () =>
  Array.from(document.documentElement.style).filter((p) => p.startsWith('--ph-'));

const near = (a: string, b: string) => {
  for (let i = 0; i < 3; i++) {
    const x = parseInt(a.slice(1 + i * 2, 3 + i * 2), 16);
    const y = parseInt(b.slice(1 + i * 2, 3 + i * 2), 16);
    expect(Math.abs(x - y), `${a} vs ${b}`).toBeLessThanOrEqual(1);
  }
};
const canvasAt = (elev: number, rising: boolean, peak?: number) => rampAt(elev, rising, peak).vars?.canvas;

afterEach(() => {
  vi.restoreAllMocks();
  document.documentElement.removeAttribute('style');
  delete document.documentElement.dataset.theme;
  delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
  try {
    localStorage.clear();
  } catch {
    /* без сховища */
  }
});

describe('точки варіанта B', () => {
  it('світанок рожево-кремовий, ранок свіжий, золота медова, сутінки бурштиново-брунатні; полудень і ніч — D', () => {
    expect(STOPS.dawn.canvas).toBe('#F7E3D8');
    expect(STOPS.morning.canvas).toBe('#EDF0EA');
    expect(STOPS.golden.canvas).toBe('#F3DEB6');
    expect(STOPS.dusk.canvas).toBe('#22150F');
    expect(STOPS.day.canvas).toBe('#F6F1E7');
    expect(STOPS.night.canvas).toBe('#111214');
    expect(STOPS.dawn.accent).toBe(STOPS.day.accent);
    expect(STOPS.dusk.accent).toBe(STOPS.night.accent);
  });
});

describe('схід: ніч < −8° · −8…−3 ніч→сутінки · перемикання на −3° · світанок до +10° · ранок · полудень > +25°', () => {
  it('нижче −8° — чиста Ніч', () => {
    expect(rampAt(-8.01, true)).toMatchObject({ theme: 'night', phase: 'night', vars: null });
  });
  it('−5.5° — посередині між Ніччю і сутінками, ще темно', () => {
    const f = rampAt(-5.5, true);
    expect(f.theme).toBe('night');
    near(f.vars!.canvas, lerpHex(STOPS.night.canvas, STOPS.dusk.canvas, 0.5));
  });
  it('−3.01° — темний край біля сутінків; −3° — уже світлий світанок', () => {
    const before = rampAt(-3.01, true);
    expect(before.theme).toBe('night');
    near(before.vars!.canvas, STOPS.dusk.canvas);
    expect(rampAt(-3, true)).toMatchObject({ theme: 'day', phase: 'dawn' });
    expect(canvasAt(-3, true)).toBe(STOPS.dawn.canvas);
  });
  it('+10° — ще світанок; +12° — посередині до ранку; +18° — ранок', () => {
    expect(canvasAt(10, true)).toBe(STOPS.dawn.canvas);
    near(canvasAt(12, true)!, lerpHex(STOPS.dawn.canvas, STOPS.morning.canvas, 0.5));
    expect(rampAt(18, true)).toMatchObject({ theme: 'day', phase: 'morning' });
    expect(canvasAt(18, true)).toBe(STOPS.morning.canvas);
  });
  it('+24.99° — майже полудень; +25° — чистий D День без inline-токенів', () => {
    near(canvasAt(24.99, true)!, STOPS.day.canvas);
    expect(rampAt(25, true)).toMatchObject({ theme: 'day', phase: 'day', vars: null });
  });
});

describe('захід: полудень > +12° · +12…0 до золотої · перемикання на 0° · сутінки до −8° · −8…−14 до ночі', () => {
  it('+12° — ще День; +6° — посередині до золотої', () => {
    expect(rampAt(12, false)).toMatchObject({ theme: 'day', vars: null });
    near(canvasAt(6, false)!, lerpHex(STOPS.golden.canvas, STOPS.day.canvas, 0.5));
    expect(rampAt(5, false).phase).toBe('golden');
  });
  it('0° — світла золота година; −0.01° — темні сутінки', () => {
    expect(rampAt(0, false)).toMatchObject({ theme: 'day', phase: 'golden' });
    expect(canvasAt(0, false)).toBe(STOPS.golden.canvas);
    expect(rampAt(-0.01, false)).toMatchObject({ theme: 'night', phase: 'dusk' });
    expect(canvasAt(-0.01, false)).toBe(STOPS.dusk.canvas);
  });
  it('−8° — ще сутінки; −11° — посередині до ночі; −14° — чиста Ніч', () => {
    expect(canvasAt(-8, false)).toBe(STOPS.dusk.canvas);
    near(canvasAt(-11, false)!, lerpHex(STOPS.dusk.canvas, STOPS.night.canvas, 0.5));
    expect(rampAt(-14, false)).toMatchObject({ theme: 'night', phase: 'night', vars: null });
  });
});

describe('зима: полудень — за 5° до найвищої точки дня', () => {
  it('сонце сягає лише 16.5° — о 11.5° уже D День і зранку, і ввечері', () => {
    expect(rampAt(11.5, true, 16.5)).toMatchObject({ theme: 'day', vars: null });
    expect(rampAt(11.5, false, 16.5)).toMatchObject({ theme: 'day', vars: null });
    expect(rampAt(11.5, true, 60).vars).not.toBeNull();
  });
  it('межі полярності взимку не рухаються', () => {
    expect(rampAt(-3, true, 16.5).theme).toBe('day');
    expect(rampAt(-0.01, false, 16.5).theme).toBe('night');
  });
});

describe('AA на всьому шляху сонця', () => {
  const pairs: [string, string][] = [
    ['text', 'canvas'], ['text', 'surface'], ['text-2', 'canvas'], ['text-2', 'surface'],
    ['text-3', 'canvas'], ['text-3', 'surface'], ['text-3', 'raised'], ['accent-text', 'canvas'],
    ['on-accent', 'accent'], ['on-self', 'self'], ['on-self-2', 'self'], ['danger', 'canvas'],
  ];
  for (const rising of [true, false]) {
    it(rising ? 'схід' : 'захід', () => {
      for (let e = -16; e <= 30; e += 0.5) {
        const f = rampAt(e, rising);
        const v = f.vars ?? STOPS[f.theme];
        for (const [fg, bg] of pairs) {
          expect(contrast(v[fg], v[bg]), `${e}° ${fg}/${bg}`).toBeGreaterThanOrEqual(4.5);
        }
      }
    });
  }
});

describe('міст: рідний бік і веб показують ту саму точку', () => {
  it('кадр їде рядком «від:до:частка» і читається назад', () => {
    const f = rampAt(12, true);
    expect(lookOf(f)).toBe('dawn:morning:0.500');
    expect(parseLook('dawn:morning:0.500')).toEqual(f);
    expect(parseLook('dawn:night:0.5')).toBeNull();
    expect(parseLook('noon')).toBeNull();
  });

  it('є getSunLook — веб бере кадр рідного боку, а не своє сонце', () => {
    vi.spyOn(sun, 'solarElevation').mockReturnValue(-20);
    (window as unknown as { PhantomBridge: unknown }).PhantomBridge = { getSunLook: () => 'golden:day:0.250' };
    document.documentElement.dataset.theme = 'day';
    applyRamp(new Date(), true);
    expect(document.documentElement.style.getPropertyValue('--ph-canvas')).toBe(lerpHex(STOPS.golden.canvas, STOPS.day.canvas, 0.25));
    expect(document.documentElement.dataset.sun).toBe('golden');
  });

  it('порожній кадр у мості — полюс D, а не своє сонце', () => {
    vi.spyOn(sun, 'solarElevation').mockReturnValue(-4);
    (window as unknown as { PhantomBridge: unknown }).PhantomBridge = { getSunLook: () => '', getThemePhase: () => 'day' };
    document.documentElement.dataset.theme = 'day';
    applyRamp(new Date(), true);
    expect(inlinePh()).toEqual([]);
  });

  it('фази рідного боку: сутінки темні, ранок і золота — світлі', () => {
    expect(themeOfPhase('dusk')).toBe('night');
    expect(themeOfPhase('night')).toBe('night');
    for (const p of ['dawn', 'morning', 'day', 'golden']) expect(themeOfPhase(p)).toBe('day');
  });
});

describe('applyRamp без моста (ПК)', () => {
  const setting = (elevAt: number) => (d: Date) => elevAt - (d.getTime() - T0) / 600_000;
  const T0 = Date.UTC(2026, 9, 28, 15, 0);

  it('сонце сідає, −4° — сутінки, полярність Ніч і на корені, і в sunNow', () => {
    vi.spyOn(sun, 'solarElevation').mockImplementation(setting(-4));
    document.documentElement.dataset.theme = 'night';
    applyRamp(new Date(T0), true);
    expect(document.documentElement.style.getPropertyValue('--ph-canvas')).toBe(STOPS.dusk.canvas);
    expect(sunNow(new Date(T0))).toBe('night');
  });

  it('ручна тема не лишає inline-токенів', () => {
    vi.spyOn(sun, 'solarElevation').mockImplementation(setting(-4));
    document.documentElement.dataset.theme = 'night';
    applyRamp(new Date(T0), true);
    applyRamp(new Date(T0), false);
    expect(inlinePh()).toEqual([]);
  });

  it('чужа полярність на корені — рампа не ставиться', () => {
    vi.spyOn(sun, 'solarElevation').mockImplementation(setting(-4));
    document.documentElement.dataset.theme = 'day';
    applyRamp(new Date(T0), true);
    expect(inlinePh()).toEqual([]);
  });
});
