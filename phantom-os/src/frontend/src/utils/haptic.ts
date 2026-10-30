/**
 * Гаптика лише через міст (біблія 05 §1.4): `PhantomBridge.haptic(kind)` →
 * рідний вібро-шаблон. `navigator.vibrate` не чіпаємо (грубий мотор), без
 * мосту (ПК) — тиша. Не частіше за раз на 50 мс для кожного виду: різні види
 * не зливаються.
 */
export type HapticKind =
  | 'tap'
  | 'send'
  | 'success'
  | 'error'
  | 'threshold'
  | 'release'
  | 'tick'
  | 'long'
  | 'confirm'
  | 'reject'
  | 'receive'
  | 'chime';

/** Легкі тіки (дотик, рядок, відкриття): серія з кількох джерел — один тік, а не тремтіння. */
const LIGHT: ReadonlySet<HapticKind> = new Set(['tap', 'tick', 'long', 'threshold', 'release', 'receive', 'chime']);
const LIGHT_GAP_MS = 120;
let lastLight: number | undefined;

const lastByKind: Partial<Record<HapticKind, number>> = {};

export function haptic(kind: HapticKind, now: number = Date.now()): boolean {
  const prev = lastByKind[kind];
  if (prev !== undefined && now - prev < 50) return false;
  const light = LIGHT.has(kind);
  if (light && lastLight !== undefined && now - lastLight >= 0 && now - lastLight < LIGHT_GAP_MS) return false;
  const bridge = typeof window !== 'undefined'
    ? (window as unknown as { PhantomBridge?: { haptic?: (t: string) => void } }).PhantomBridge
    : undefined;
  if (!bridge || typeof bridge.haptic !== 'function') return false;
  lastByKind[kind] = now;
  if (light) lastLight = now;
  try {
    bridge.haptic(kind);
    return true;
  } catch {
    return false;
  }
}

export type RingName = 'personal' | 'close' | 'friends' | 'social' | 'work';

/** Локальний малюнок вібро для вхідного за колом; наживо нічого не їде. */
export const RING_HAPTIC: Record<RingName, HapticKind | null> = {
  personal: 'long',
  close: 'confirm',
  friends: 'tick',
  social: null,
  work: 'tap',
};
