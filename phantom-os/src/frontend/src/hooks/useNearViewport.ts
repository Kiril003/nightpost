/**
 * «Чи цей елемент уже близько до видимого» — для відкладеного розшифрування.
 *
 * Розмова на 600 листів із сотнею фото при відкритті кликала fetch + SHA-256
 * + AES-GCM для КОЖНОГО змонтованого вкладення, навіть того, до якого людина
 * не долистає. Тепер вкладення береться до роботи, лише коли воно в межах
 * ~одного екрана від видимої частини стрічки.
 *
 * Один IntersectionObserver на кожен прокручуваний контейнер (а не по
 * спостерігачу на бульбашку). Корінь — найближчий предок, що прокручується:
 * з неявним коренем (вікно) `rootMargin` розширював би вікно, а стрічка
 * обрізає вміст своїм overflow раніше, і «екран наперед» не спрацював би.
 *
 * Без IntersectionObserver (jsdom, старий WebView) — «близько» одразу:
 * повільніше, але нічого не губиться.
 */
import { useEffect, useState } from 'react';

/** Скільки наперед: одна висота контейнера згори й знизу. */
export const NEAR_MARGIN = '100% 0px 100% 0px';

type Entry = { io: IntersectionObserver; cbs: Map<Element, () => void> };
const byRoot = new Map<Element | null, Entry>();

/** Найближчий предок, що прокручується по вертикалі; null — вікно. */
export function scrollRootOf(el: Element): Element | null {
  let p = el.parentElement;
  while (p && p !== document.body && p !== document.documentElement) {
    const oy = getComputedStyle(p).overflowY;
    if (oy === 'auto' || oy === 'scroll' || oy === 'overlay') return p;
    p = p.parentElement;
  }
  return null;
}

/**
 * Один раз покликати `onNear`, щойно `el` опиниться в межах NEAR_MARGIN.
 * Повертає відписку.
 */
export function observeNear(el: Element, onNear: () => void): () => void {
  if (typeof IntersectionObserver === 'undefined') {
    onNear();
    return () => undefined;
  }
  const root = scrollRootOf(el);
  let entry = byRoot.get(root);
  if (!entry) {
    const cbs = new Map<Element, () => void>();
    const io = new IntersectionObserver(
      (records) => {
        for (const r of records) {
          if (!r.isIntersecting) continue;
          const cb = cbs.get(r.target);
          if (!cb) continue;
          cbs.delete(r.target);
          io.unobserve(r.target);
          cb();
        }
        // Нікого не лишилось — гасимо: відкріплений контейнер стрічки не
        // повинен жити в мапі після того, як розмову закрито.
        if (cbs.size === 0 && byRoot.get(root) === entry) {
          io.disconnect();
          byRoot.delete(root);
        }
      },
      { root, rootMargin: NEAR_MARGIN },
    );
    entry = { io, cbs };
    byRoot.set(root, entry);
  }
  const e = entry;
  e.cbs.set(el, onNear);
  e.io.observe(el);
  return () => {
    if (!e.cbs.delete(el)) return;
    e.io.unobserve(el);
    if (e.cbs.size === 0) {
      e.io.disconnect();
      if (byRoot.get(root) === e) byRoot.delete(root);
    }
  };
}

/**
 * `[ref, near]`: повісь `ref` на елемент; `near` стає true раз і назавжди,
 * щойно елемент близько. `enabled=false` — не спостерігати (вже не треба).
 */
export function useNearViewport(enabled = true): [(el: Element | null) => void, boolean] {
  const [near, setNear] = useState(() => typeof IntersectionObserver === 'undefined');
  const [el, setEl] = useState<Element | null>(null);
  useEffect(() => {
    if (near || !enabled || !el) return undefined;
    return observeNear(el, () => setNear(true));
  }, [el, near, enabled]);
  return [setEl, near];
}
