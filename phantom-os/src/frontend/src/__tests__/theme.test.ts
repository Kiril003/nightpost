/**
 * Тема — Ніч або День за справжнім сонцем (рішення власника 01.10), з ручним
 * вибором поверх. Телефон: сонце рахує рідний бік; ПК: services/sun.ts.
 */
import { describe, it, expect, afterEach, vi } from 'vitest';
import {
  applyTheme,
  applyThemeNow,
  currentTheme,
  PHASE_SHIFT_MS,
  readThemeMode,
  setThemeMode,
  startThemeWatch,
  sunNow,
  themeOfPhase,
} from '../services/theme';
import { currentSunPlace, rememberSunPlace, solarElevation } from '../services/sun';

const setBridge = (getTheme?: () => unknown, getThemePhase?: () => unknown) => {
  const w = window as unknown as { PhantomBridge?: unknown };
  if (!getTheme && !getThemePhase) delete w.PhantomBridge;
  else w.PhantomBridge = { ...(getTheme ? { getTheme } : {}), ...(getThemePhase ? { getThemePhase } : {}) };
};
const push = (...args: unknown[]) =>
  (window as unknown as { __phantomTheme: (...a: unknown[]) => void }).__phantomTheme(...args);

const KYIV = { lat: 50.45, lon: 30.52 };

afterEach(() => {
  setBridge(undefined);
  localStorage.clear();
  delete document.documentElement.dataset.theme;
  delete document.documentElement.dataset.phase;
  document.documentElement.classList.remove('phase-shift');
  vi.useRealTimers();
});

describe('сонце', () => {
  it('Київ, 21 червня: опівдні сонце високо (~63°), опівночі — глибоко під обрієм', () => {
    const noon = solarElevation(new Date('2026-06-21T09:45:00Z'), KYIV); // ~12:45 за Києвом (літній час)
    expect(noon).toBeGreaterThan(60);
    expect(noon).toBeLessThan(64);
    expect(solarElevation(new Date('2026-06-21T21:00:00Z'), KYIV)).toBeLessThan(-10);
  });

  it('межа: увечері на 0° (≈ 18:33 за Києвом), зранку на −3° (≈ 06:46)', () => {
    rememberSunPlace(KYIV);
    expect(sunNow(new Date('2026-10-01T15:20:00Z'))).toBe('day'); // 18:20, золота година, +2°
    expect(sunNow(new Date('2026-10-01T15:45:00Z'))).toBe('night'); // 18:45, −2°, сутінки
    expect(sunNow(new Date('2026-10-02T03:30:00Z'))).toBe('night'); // 06:30, ще −5°
    expect(sunNow(new Date('2026-10-02T03:55:00Z'))).toBe('day'); // 06:55, світанок
  });

  it('місце: збережене — його й беремо; немає — оцінка з поясу', () => {
    rememberSunPlace({ lat: 48.92, lon: 24.71 });
    expect(currentSunPlace()).toEqual({ lat: 48.92, lon: 24.71 });
    localStorage.clear();
    const est = currentSunPlace();
    expect(Math.abs(est.lat)).toBeLessThanOrEqual(90);
    expect(Math.abs(est.lon)).toBeLessThanOrEqual(180);
  });
});

describe('тема на телефоні — від рідного боку', () => {
  it('шість фаз моста: сутінки й ніч темні, решта світлі', () => {
    expect(themeOfPhase('night')).toBe('night');
    expect(themeOfPhase('dawn')).toBe('day');
    expect(themeOfPhase('day')).toBe('day');
    expect(themeOfPhase('dusk')).toBe('night');
    expect(themeOfPhase('полудень')).toBeNull();
  });

  it('міст каже фазу — застосунок у відповідній палітрі; міст падає — ніч', () => {
    setBridge(() => 'night', () => 'golden');
    applyThemeNow();
    expect(currentTheme()).toBe('day');
    setBridge(() => {
      throw new Error('мовчить');
    });
    applyThemeNow();
    expect(currentTheme()).toBe('night');
  });

  it('поштовх на льоту: (полярність, фаза) від нового APK і (полярність) від старого', () => {
    setBridge(() => 'night');
    const stop = startThemeWatch();
    push('day', 'dawn');
    expect(currentTheme()).toBe('day');
    push('night');
    expect(currentTheme()).toBe('night');
    stop();
  });
});

describe('вибір теми на телефоні — на рідному боці', () => {
  it('«Я › Тема» читає й пише вибір через міст, а не в localStorage', () => {
    const set = vi.fn();
    let native = 'auto';
    (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = {
      getTheme: () => 'night',
      getThemePhase: () => 'night',
      getThemeMode: () => native,
      setThemeMode: (m: string) => {
        set(m);
        native = m;
      },
    };
    localStorage.setItem('phantom.themeMode', 'day'); // слід старого бандла
    expect(readThemeMode()).toBe('auto');
    setThemeMode('day');
    expect(set).toHaveBeenCalledWith('day');
    expect(currentTheme()).toBe('day');
    expect(readThemeMode()).toBe('day');
    expect(localStorage.getItem('phantom.themeMode')).toBe('day'); // не писали — лишився старий
    setThemeMode('auto');
    expect(set).toHaveBeenLastCalledWith('auto');
    expect(currentTheme()).toBe('night');
  });

  it('старий APK без вибору в мосту — вибір живе в localStorage, як і раніше', () => {
    setBridge(() => 'night', () => 'night');
    setThemeMode('day');
    expect(localStorage.getItem('phantom.themeMode')).toBe('day');
    expect(readThemeMode()).toBe('day');
  });
});

describe('тема на ПК — сонце над цим комп\'ютером', () => {
  it('без моста: опівдні День, опівночі Ніч, і щохвилини перепитує', () => {
    vi.useFakeTimers();
    rememberSunPlace(KYIV);
    vi.setSystemTime(new Date('2026-10-01T09:00:00Z'));
    const stop = startThemeWatch();
    expect(currentTheme()).toBe('day');
    vi.setSystemTime(new Date('2026-10-01T21:00:00Z'));
    vi.advanceTimersByTime(60_000);
    expect(currentTheme()).toBe('night');
    stop();
  });
});

describe('ручний вибір і перетікання', () => {
  it('«Завжди ніч» перекриває сонце й поштовх моста; «Як сонце» повертає сонце', () => {
    setBridge(() => 'day', () => 'day');
    const stop = startThemeWatch();
    expect(currentTheme()).toBe('day');
    setThemeMode('night');
    expect(readThemeMode()).toBe('night');
    expect(currentTheme()).toBe('night');
    push('day', 'day');
    expect(currentTheme()).toBe('night');
    setThemeMode('auto');
    expect(currentTheme()).toBe('day');
    stop();
  });

  it('перша постановка — без перетікання; зміна — клас phase-shift на 600 мс', () => {
    vi.useFakeTimers();
    applyTheme('night');
    expect(document.documentElement.classList.contains('phase-shift')).toBe(false);
    applyTheme('day');
    expect(document.documentElement.classList.contains('phase-shift')).toBe(true);
    expect(document.documentElement.dataset.phase).toBe('day');
    vi.advanceTimersByTime(PHASE_SHIFT_MS + 60);
    expect(document.documentElement.classList.contains('phase-shift')).toBe(false);
  });

  it('вхід ставить тему ДО render; MessengerRoot тримає її свіжою', async () => {
    const { readFileSync } = await import('node:fs');
    const main = readFileSync('src/main.tsx', 'utf8');
    expect(main.indexOf('applyThemeNow();')).toBeGreaterThan(-1);
    expect(main.indexOf('applyThemeNow();')).toBeLessThan(main.indexOf('createRoot('));
    expect(readFileSync('src/components/messenger/MessengerRoot.tsx', 'utf8')).toContain('startThemeWatch');
  });
});
