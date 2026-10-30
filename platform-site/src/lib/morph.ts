import { flushSync } from 'react-dom';

const names = new WeakMap<Element, string>();
let serial = 0;
let latest: object | null = null;

/**
 * A list change goes through the browser's view transition: everything inside
 * a `data-flow` container glides to its new place, and what is added or removed
 * fades. Without support, or under reduced motion, the update runs as it
 * always did.
 */
export function morph(update: () => void): void {
  const doc = typeof document === 'undefined' ? null : document;
  if (!doc || typeof doc.startViewTransition !== 'function' || calm()) {
    update();
    return;
  }
  const root = doc.documentElement;
  root.setAttribute('data-morphing', '');
  name(doc);
  let transition;
  try {
    transition = doc.startViewTransition(() => {
      flushSync(update);
      name(doc);
    });
  } catch {
    root.removeAttribute('data-morphing');
    unname(doc);
    update();
    return;
  }
  latest = transition;
  // A skipped transition (hidden tab, a newer one starting) rejects `ready`; the update still ran.
  transition.ready.catch(() => undefined);
  transition.finished
    .catch(() => undefined)
    .then(() => {
      if (latest !== transition) return;
      latest = null;
      root.removeAttribute('data-morphing');
      unname(doc);
    });
}

/** Names live only while a transition runs, so nothing is a stacking context the rest of the time. */
function name(doc: Document): void {
  for (const el of doc.querySelectorAll<HTMLElement>('[data-flow] > *')) {
    let id = names.get(el);
    if (!id) names.set(el, (id = `vt-${serial++}`));
    el.style.setProperty('view-transition-name', id);
  }
}

function unname(doc: Document): void {
  for (const el of doc.querySelectorAll<HTMLElement>('[data-flow] > *')) el.style.removeProperty('view-transition-name');
}

function calm(): boolean {
  try {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  } catch {
    return false;
  }
}
