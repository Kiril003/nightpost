/**
 * Сонце, варіант B (власник 28.10): ніч → світанок → ранок → полудень зранку,
 * полудень → золота → сутінки → ніч увечері. Вікна — `SUN` у tokens.json, ті
 * самі читає рідний бік (`SunLook.at`). На телефоні кадр дає міст
 * (`getSunLook`), щоб веб і оболонка не розходились; на ПК — своє сонце.
 */
import { STOPS, STOP_THEME, SUN, type Stop } from '../styles/phantom/stops';
import { currentSunPlace, solarElevation, type SunPlace } from './sun';

export type RampTheme = 'day' | 'night';

export interface RampFrame {
  theme: RampTheme;
  from: Stop;
  to: Stop;
  t: number;
  /** Найближча точка — так її звуть фаза моста й `data-sun`. */
  phase: Stop;
  vars: Record<string, string> | null;
}

const KEYS = Object.keys(STOPS.night);
const ACCENT_KEYS = ['accent', 'accent-hover', 'accent-press', 'accent-text', 'accent-line', 'on-accent'];

const channel = (hex: string, i: number) => parseInt(hex.slice(1 + i * 2, 3 + i * 2), 16);
const hex2 = (n: number) => Math.round(n).toString(16).padStart(2, '0').toUpperCase();

export function lerpHex(a: string, b: string, t: number): string {
  const k = Math.min(1, Math.max(0, t));
  const rgb = [0, 1, 2].map((i) => hex2(channel(a, i) + (channel(b, i) - channel(a, i)) * k)).join('');
  return `#${rgb}${a.slice(7)}`;
}

const isStop = (s: string): s is Stop => Object.prototype.hasOwnProperty.call(STOPS, s);

export function frameOf(from: Stop, to: Stop, t: number): RampFrame {
  let [a, b, k] = [from, to, Math.round(Math.min(1, Math.max(0, t)) * 1000) / 1000];
  if (k <= 0 || a === b) [b, k] = [a, 0];
  else if (k >= 1) [a, k] = [b, 0];
  const pole = a === b && (a === 'day' || a === 'night');
  return {
    theme: STOP_THEME[a],
    from: a,
    to: b,
    t: k,
    phase: k < 0.5 ? a : b,
    vars: pole ? null : Object.fromEntries(KEYS.map((n) => [n, lerpHex(STOPS[a][n], STOPS[b][n], k)])),
  };
}

/** Зима: якщо сонце не сягає останньої точки + запасу, точки після перемикання стискаються до «пік − запас». */
const anchorsFor = (rising: boolean, peak: number): [number, Stop][] => {
  const side = rising ? SUN.rising : SUN.setting;
  const top = side[side.length - 1][0];
  const lightAt = side.findIndex(([, s]) => STOP_THEME[s] === 'day');
  const low = side[lightAt][0];
  const hi = Math.max(low + 1, peak - SUN.winterMargin);
  if (hi >= top) return side;
  return side.map(([d, s], i) => [i > lightAt ? low + ((d - low) * (hi - low)) / (top - low) : d, s]);
};

export function rampAt(elev: number, rising = false, peak = 90): RampFrame {
  const a = anchorsFor(rising, peak);
  if (!(elev >= a[0][0])) return frameOf(a[0][1], a[0][1], 0);
  for (let i = a.length - 2; i >= 0; i--) {
    const [d0, s0] = a[i];
    const [d1, s1] = a[i + 1];
    if (elev >= d0 && d1 > d0) return frameOf(s0, s1, elev >= d1 ? 1 : (elev - d0) / (d1 - d0));
  }
  const last = a[a.length - 1][1];
  return frameOf(last, last, 0);
}

/** Висота, напрям і найвища точка дня — рампі цього досить. */
export function sunFix(date: Date, place: SunPlace): { elev: number; rising: boolean; peak: number } {
  const elev = solarElevation(date, place);
  const rising = solarElevation(new Date(date.getTime() + 600_000), place) > elev;
  let peak = elev;
  for (let m = -720; m <= 720; m += 15) peak = Math.max(peak, solarElevation(new Date(date.getTime() + m * 60_000), place));
  return { elev, rising, peak };
}

export const frameNow = (date: Date = new Date()): RampFrame => {
  const { elev, rising, peak } = sunFix(date, currentSunPlace(date));
  return rampAt(elev, rising, peak);
};

export const lookOf = (f: RampFrame): string => `${f.from}:${f.to}:${f.t.toFixed(3)}`;

export function parseLook(raw: unknown): RampFrame | null {
  if (typeof raw !== 'string') return null;
  const [from, to, t] = raw.split(':');
  const k = Number(t);
  if (!from || !to || !isStop(from) || !isStop(to) || !Number.isFinite(k)) return null;
  if (STOP_THEME[from] !== STOP_THEME[to]) return null;
  return frameOf(from, to, k);
}

/** Телефон із кадром у мості: його кадр, а порожній кадр — плоска точка фази (полюс D). */
const bridgeLook = (): RampFrame | null => {
  if (typeof window === 'undefined') return null;
  const b = (window as unknown as { PhantomBridge?: { getSunLook?: () => unknown; getThemePhase?: () => unknown } }).PhantomBridge;
  if (!b || typeof b.getSunLook !== 'function') return null;
  try {
    const look = parseLook(b.getSunLook());
    if (look) return look;
    const phase = String(b.getThemePhase?.() ?? '');
    return isStop(phase) ? frameOf(phase, phase, 0) : frameOf('night', 'night', 0);
  } catch {
    return null;
  }
};

/** Кадр, який показує цей пристрій: міст телефона сильніший за наш розрахунок. */
export const currentFrame = (date: Date = new Date()): RampFrame => bridgeLook() ?? frameNow(date);

export function clearRamp(): void {
  if (typeof document === 'undefined') return;
  const root = document.documentElement;
  for (const k of KEYS) root.style.removeProperty(`--ph-${k}`);
  delete root.dataset.sun;
}

/**
 * Ставить токени точки на корінь. `auto` = людина лишила «Як сонце»; ручні
 * День/Ніч — плоский D. Чужий акцент лишається своїм: його ролі дає tokens.css.
 */
export function applyRamp(date: Date = new Date(), auto = true): void {
  if (typeof document === 'undefined') return;
  if (!auto) return clearRamp();
  const root = document.documentElement;
  const frame = currentFrame(date);
  const theme: RampTheme = root.dataset.theme === 'day' ? 'day' : 'night';
  if (!frame.vars || frame.theme !== theme) return clearRamp();
  const own = !root.dataset.accent || root.dataset.accent === 'amber';
  for (const [k, v] of Object.entries(frame.vars)) {
    if (!own && ACCENT_KEYS.includes(k)) root.style.removeProperty(`--ph-${k}`);
    else root.style.setProperty(`--ph-${k}`, v);
  }
  root.dataset.sun = frame.phase;
}
