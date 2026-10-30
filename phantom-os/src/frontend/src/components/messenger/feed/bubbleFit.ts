/**
 * Час у кінці тексту тримає місце розпіркою. Коли розпірка не влазить і
 * падає на свій рядок, бульбашка лишалась на всю ширину, якої просив
 * текст разом із часом, — і справа від тексту зяяла порожнеча. Тут
 * бульбашка стискається до найширшого рядка тексту, а час стає під ним.
 */
type Box = { left: number; right: number; top: number; bottom: number };

export function fitWidth(text: readonly Box[], spacer: Box | null, padX: number): number | null {
  if (!spacer || text.length === 0) return null;
  const lastTop = Math.max(...text.map((r) => r.top));
  const lastBottom = Math.max(...text.map((r) => r.bottom ?? r.top + 20));
  if (spacer.top <= lastTop + 1 || spacer.top < lastBottom - 1) return null;
  const left = Math.min(...text.map((r) => r.left));
  const right = Math.max(...text.map((r) => r.right));
  return Math.ceil(Math.max(right - left, spacer.right - spacer.left) + padX);
}

/** Імʼя автора всередині бульбашки розширює її не більше ніж на стільки; довше — трикрапка. */
export const NAME_CAP = 160;

export function fitBubble(bubble: HTMLElement | null): void {
  if (!bubble) return;
  bubble.style.width = '';
  bubble.style.minWidth = '';
  const spacer = bubble.querySelector<HTMLElement>('[data-meta-space]');
  const p = spacer?.parentElement;
  if (!spacer || !p || typeof document.createRange !== 'function') return;
  const range = document.createRange();
  range.setStart(p, 0);
  range.setEndBefore(spacer);
  const rects = typeof range.getClientRects === 'function' ? [...range.getClientRects()].filter((r) => r.width > 0) : [];
  const cs = getComputedStyle(bubble);
  const padX =
    (parseFloat(cs.paddingLeft) || 0) + (parseFloat(cs.paddingRight) || 0) + (parseFloat(cs.borderLeftWidth) || 0) + (parseFloat(cs.borderRightWidth) || 0);
  const name = bubble.querySelector<HTMLElement>('[data-msg-sender]');
  if (name) bubble.style.minWidth = `${Math.ceil(Math.min(name.scrollWidth, NAME_CAP) + padX)}px`;
  const w = fitWidth(rects, spacer.getBoundingClientRect(), padX);
  if (w !== null) bubble.style.width = `${w}px`;
}
