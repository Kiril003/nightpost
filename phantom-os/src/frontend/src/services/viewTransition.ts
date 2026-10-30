/**
 * Рух, що пояснює простір (дизайн-система §6): мініатюра фото перетікає в
 * повноекранний перегляд; розмова на телефоні наїжджає збоку одним рухом
 * (спільні аватар і імʼя для `vt-push` вимкнено з 28.10, T7).
 *
 * Механізм — View Transitions API браузера (Chromium 111+, тобто WebView A52 і
 * Tauri на Windows/Linux із сучасним WebKitGTK не завжди): знімок «до»,
 * синхронна зміна DOM, знімок «після», а між ними браузер сам інтерполює
 * межі елементів з однаковим `view-transition-name`. Немає API або людина
 * просить менше руху — зміна стається одразу, без анімації (і без помилки).
 *
 * Тривалості й криві — у form.css (`::view-transition-*`).
 */
import { flushSync } from 'react-dom';

type Doc = Document & { startViewTransition?: (cb: () => void) => { finished: Promise<void> } };

export const prefersReducedMotion = (): boolean =>
  typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

export const canViewTransition = (): boolean =>
  typeof document !== 'undefined' && typeof (document as Doc).startViewTransition === 'function';

/**
 * Позначити елементи спільними на час переходу. `pairs` — [елемент, імʼя];
 * імена знімаються, щойно перехід скінчився, щоб не лишилось двох однакових.
 * `kind` ставить клас на <html>: `vt-push` (телефон: нова розмова їде
 * збоку), `vt-fade` (ПК: 150 мс згасання), `vt-zoom` (фото).
 */
export function withViewTransition(
  update: () => void,
  opts: { pairs?: Array<[Element | null | undefined, string]>; kind?: 'vt-push' | 'vt-fade' | 'vt-zoom' } = {},
): void {
  const doc = document as Doc;
  if (!canViewTransition() || prefersReducedMotion()) {
    update();
    return;
  }
  const tagged: HTMLElement[] = [];
  // T7 (28.10): на телефоні розмова — один рух, наїзд збоку; аватар і імʼя,
  // що летіли в шапку окремо, тестери читали як «стрибок».
  const pairs = opts.kind === 'vt-push' ? [] : (opts.pairs ?? []);
  for (const [el, name] of pairs) {
    if (el instanceof HTMLElement) {
      el.style.setProperty('view-transition-name', name);
      tagged.push(el);
    }
  }
  const root = document.documentElement;
  if (opts.kind) root.classList.add(opts.kind);
  const clean = () => {
    for (const el of tagged) el.style.removeProperty('view-transition-name');
    if (opts.kind) root.classList.remove(opts.kind);
  };
  try {
    const t = doc.startViewTransition!(() => {
      // Старі імена знімаються в знімку «після»: там те саме імʼя несе нова
      // сторона (шапка, перегляд фото), і двох однакових бути не може.
      for (const el of tagged) el.style.removeProperty('view-transition-name');
      flushSync(update);
    });
    t.finished.then(clean, clean);
  } catch {
    clean();
    update();
  }
}

/** Останнє фото, якого торкнулись у стрічці: з нього починається перегляд. */
let lastMedia: HTMLElement | null = null;
export const rememberTappedMedia = (el: EventTarget | null): void => {
  lastMedia = el instanceof HTMLImageElement || el instanceof HTMLVideoElement ? el : null;
};
export const takeTappedMedia = (): HTMLElement | null => {
  const el = lastMedia;
  lastMedia = null;
  return el && el.isConnected ? el : null;
};
