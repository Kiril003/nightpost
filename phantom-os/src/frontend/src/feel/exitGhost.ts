/**
 * Вихід аркуша, якого батько вже зняв з дерева (`{open && <Sheet open …/>}`).
 * React прибирає вузол одразу, тож CSS-виходу немає. Перед зникненням беремо
 * знімок шару і граємо на ньому ту саму пружину layer униз, потім прибираємо.
 * Знімок німий для читача екрана й без ролей; заслінка ковтає дотик (T46).
 * Без Web Animations (старий рушій, тести) знімка немає — аркуш просто зникає.
 */
import { LAYER_EXIT_MS, REDUCED_MS, isReducedMotion } from './motionKit';

const FALLBACK_EASE = 'cubic-bezier(0.2, 0, 0, 1)';

function springEase(): string {
  try {
    const v = getComputedStyle(document.documentElement).getPropertyValue('--ph-spring-layer').trim();
    return v || FALLBACK_EASE;
  } catch {
    return FALLBACK_EASE;
  }
}

function mute(root: HTMLElement): void {
  for (const el of [root, ...Array.from(root.querySelectorAll<HTMLElement>('*'))]) {
    el.removeAttribute('role');
    el.removeAttribute('aria-modal');
    el.removeAttribute('data-testid');
    el.removeAttribute('id');
    if (el.hasAttribute('tabindex') || el.matches('a,button,input,select,textarea')) el.setAttribute('tabindex', '-1');
  }
  root.setAttribute('aria-hidden', 'true');
  root.setAttribute('data-exit-ghost', '');
}

export function playExitGhost(layer: HTMLElement | null): HTMLElement | null {
  if (!layer || !layer.isConnected || typeof layer.animate !== 'function') return null;
  const ghost = layer.cloneNode(true) as HTMLElement;
  mute(ghost);
  const sheet = ghost.querySelector<HTMLElement>('[data-kit="sheet-frame"]');
  const scrim = ghost.querySelector<HTMLElement>('.f-scrim');
  for (const el of [sheet, scrim]) if (el) el.style.animation = 'none';
  if (scrim) scrim.style.pointerEvents = 'auto';
  document.body.appendChild(ghost);

  const reduced = isReducedMotion();
  const timing: KeyframeAnimationOptions = reduced
    ? { duration: REDUCED_MS, easing: 'linear', fill: 'forwards' }
    : { duration: LAYER_EXIT_MS, easing: springEase(), fill: 'forwards' };
  const done = () => ghost.remove();
  try {
    const runs: Animation[] = [];
    if (sheet) runs.push(sheet.animate(reduced ? [{ opacity: 1 }, { opacity: 0 }] : [{ transform: 'none' }, { transform: 'translateY(100%)' }], timing));
    if (scrim) runs.push(scrim.animate([{ opacity: 1 }, { opacity: 0 }], timing));
    if (runs.length === 0) {
      done();
      return null;
    }
    Promise.all(runs.map((a) => a.finished)).then(done, done);
    // Запасний прибиральник: шкала стоїть (прихована вкладка) — знімок не висить; на паузі (знімання кадрів) — чекає.
    const sweep = () => (runs.some((a) => a.playState === 'paused') ? window.setTimeout(sweep, 200) : done());
    window.setTimeout(sweep, (timing.duration as number) + 200);
  } catch {
    done();
    return null;
  }
  return ghost;
}
