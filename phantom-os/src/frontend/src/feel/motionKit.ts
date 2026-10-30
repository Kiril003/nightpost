import { useEffect, useRef, useState } from 'react';
import { LAYER, prefersReduced, springSettleMs } from './physics';

/** Один словник руху для React-коду: числа живуть у styles/phantom/tokens.css. */
export const MOTION_VAR = {
  instant: 'var(--ph-dur-instant)',
  quick: 'var(--ph-dur-quick)',
  standard: 'var(--ph-dur-standard)',
  emphasized: 'var(--ph-dur-emphasized)',
  exit: 'var(--ph-dur-exit)',
  easeStandard: 'var(--ph-ease-standard)',
  easeDecelerate: 'var(--ph-ease-decelerate)',
  easeAccelerate: 'var(--ph-ease-accelerate)',
  springLayer: 'var(--ph-spring-layer)',
  springFlight: 'var(--ph-spring-flight)',
  springPress: 'var(--ph-spring-press)',
} as const;

/** Тривалості виходу в мс для таймерів, що чекають на CSS (дзеркало --ph-dur-*). */
export const EXIT_MS = 180;
export const REDUCED_MS = 120;
/** Аркуш і вікно виходять тією ж пружиною layer, що й заходять: стільки чекаємо до зняття. */
export const LAYER_EXIT_MS = springSettleMs(LAYER);

export const REDUCE_CLASS = 'reduce-motion';

/** Системне «менше руху» або власний перемикач застосунку (клас на html). */
export function isReducedMotion(): boolean {
  if (typeof document !== 'undefined' && document.documentElement.classList.contains(REDUCE_CLASS)) return true;
  return prefersReduced();
}

/**
 * Аркуш лишається в DOM на час виходу: `closing` дає клас `f-*-out`.
 * `skip` — закриття вже відіграло жестом (drag-dismiss), другого виходу не треба.
 */
export function useExitPresence(
  open: boolean,
  skipRef?: { current: boolean },
  exitMs: number = EXIT_MS,
): { mounted: boolean; closing: boolean } {
  const [mounted, setMounted] = useState(open);
  const prev = useRef(open);
  useEffect(() => {
    if (open) {
      prev.current = true;
      setMounted(true);
      return undefined;
    }
    if (!prev.current) return undefined;
    prev.current = false;
    if (skipRef?.current) {
      skipRef.current = false;
      setMounted(false);
      return undefined;
    }
    const id = window.setTimeout(() => setMounted(false), isReducedMotion() ? REDUCED_MS : exitMs);
    return () => window.clearTimeout(id);
  }, [open, skipRef, exitMs]);
  return { mounted: open || mounted, closing: !open && mounted };
}

/**
 * Рядки, що з'явилися ПІСЛЯ першого показу списку. Перший кадр (холодний старт)
 * не анімується — повертається порожня множина.
 */
export function useEnteringIds(ids: readonly string[]): ReadonlySet<string> {
  const seen = useRef<Set<string> | null>(null);
  const key = ids.join('\n');
  const [entering, setEntering] = useState<ReadonlySet<string>>(() => new Set());
  useEffect(() => {
    if (seen.current === null) {
      seen.current = new Set(ids);
      return undefined;
    }
    const fresh = ids.filter((id) => !seen.current!.has(id));
    ids.forEach((id) => seen.current!.add(id));
    if (fresh.length === 0) return undefined;
    setEntering(new Set(fresh));
    const t = window.setTimeout(() => setEntering(new Set()), 400);
    return () => window.clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);
  return entering;
}

/** Клас перехресного згасання вмісту вкладки: лише після зміни ключа, не на першому кадрі. */
export function useTabFade(key: string): string {
  const first = useRef(key);
  const [changed, setChanged] = useState(false);
  useEffect(() => {
    if (key !== first.current) setChanged(true);
  }, [key]);
  return changed ? 'f-tab-in' : '';
}

const REDUCE_KEY = 'ph.reduceMotion';

/** Власний перемикач застосунку «менше руху»: клас на html + пам'ять у localStorage. */
export function setAppReduceMotion(on: boolean): void {
  if (typeof document === 'undefined') return;
  document.documentElement.classList.toggle(REDUCE_CLASS, on);
  try {
    if (on) localStorage.setItem(REDUCE_KEY, '1');
    else localStorage.removeItem(REDUCE_KEY);
  } catch {
    /* сховище недоступне — клас лишається до перезапуску */
  }
}

export function initReduceMotion(): void {
  try {
    if (localStorage.getItem(REDUCE_KEY) === '1') setAppReduceMotion(true);
  } catch {
    /* немає сховища — лишається системне налаштування */
  }
}
