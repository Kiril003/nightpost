/**
 * Рух екрана дзвінка — спокійний і дешевий: лише `transform` і `opacity`
 * через Web Animations API. Такі анімації живуть на компоновнику, тож
 * 60 кадрів на середньому телефоні не залежать від того, чим зайнятий
 * головний потік (рушій, getStats, React). Без `animate` (jsdom, старий
 * WebView) і за «менше руху» в системі — статичний кадр, нічого не ламається.
 */

import { useEffect } from 'react';
import type { RefObject } from 'react';

const prefersLess = (): boolean => {
  try {
    return typeof window !== 'undefined' && !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
  } catch {
    return false;
  }
};

const canAnimate = (el: Element | null): el is HTMLElement =>
  !!el && typeof (el as HTMLElement).animate === 'function' && !prefersLess();

/** Повільне «дихання» обличчя, поки дзвінок ще не взяли. */
export function useBreath(ref: RefObject<HTMLElement>, on: boolean): void {
  useEffect(() => {
    const el = ref.current;
    if (!on || !canAnimate(el)) return;
    const anim = el.animate([{ transform: 'scale(1)' }, { transform: 'scale(1.035)' }], {
      duration: 2600,
      iterations: Infinity,
      direction: 'alternate',
      easing: 'cubic-bezier(0.45, 0, 0.55, 1)',
    });
    return () => anim.cancel();
  }, [ref, on]);
}

/** Поява знизу: панель кнопок, меню. `active` — увімкнути або перезапустити на новий стан. */
export function useRise(ref: RefObject<HTMLElement>, active: unknown = true, distance = 16, duration = 320): void {
  useEffect(() => {
    const el = ref.current;
    if (!active || !canAnimate(el)) return;
    const anim = el.animate(
      [
        { opacity: 0, transform: `translate3d(0, ${distance}px, 0)` },
        { opacity: 1, transform: 'translate3d(0, 0, 0)' },
      ],
      { duration, easing: 'cubic-bezier(0.2, 0.8, 0.2, 1)' },
    );
    return () => anim.cancel();
  }, [ref, active, distance, duration]);
}
