/**
 * Чи забирає горизонтальний жест щось усередині пейджера: смуга з власною
 * прокруткою (позначена `data-own-swipe` чи overflow-x), якій є куди гортати. Тоді сторінку пейджер не гортає.
 */
export function ownsHorizontalSwipe(target: EventTarget | null, stop: Element): boolean {
  for (let el = target instanceof Element ? target : null; el && el !== stop; el = el.parentElement) {
    if (el.hasAttribute('data-today-page')) return false;
    if (el.scrollWidth > el.clientWidth + 1) {
      if (el.hasAttribute('data-own-swipe')) return true;
      const o = getComputedStyle(el).overflowX;
      if (o === 'auto' || o === 'scroll') return true;
    }
  }
  return false;
}

/** Зсув пейджера під пальцем: читає лише той, кому він треба, без перемалювання списку. */
export interface DriftSource {
  get: () => number | null;
  subscribe: (fn: () => void) => () => void;
  set: (v: number | null) => void;
}

export function createDrift(): DriftSource {
  let value: number | null = null;
  const subs = new Set<() => void>();
  return {
    get: () => value,
    subscribe: (fn) => {
      subs.add(fn);
      return () => subs.delete(fn);
    },
    set: (v) => {
      if (v === value) return;
      value = v;
      subs.forEach((fn) => fn());
    },
  };
}
