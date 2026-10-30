import { useEffect, useRef, type RefObject } from 'react';

const FOCUSABLE = 'a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])';

export type TrapStart = 'first' | 'root';

export function useTrap(ref: RefObject<HTMLElement | null>, active = true, onEscape?: () => void, content?: unknown, start: TrapStart = 'first') {
  const esc = useRef(onEscape);
  esc.current = onEscape;
  useEffect(() => {
    const root = ref.current;
    if (!active || !root) return;
    const back = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const items = () => Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE)).filter((el) => el.offsetParent !== null || el === document.activeElement);
    if (!root.contains(document.activeElement)) (start === 'root' ? root : items()[0] ?? root).focus();
    const key = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && esc.current) {
        e.stopPropagation();
        esc.current();
        return;
      }
      if (e.key !== 'Tab') return;
      const list = items();
      if (!list.length) {
        e.preventDefault();
        return;
      }
      const first = list[0];
      const last = list[list.length - 1];
      const out = document.activeElement === root || !root.contains(document.activeElement);
      if (e.shiftKey && (document.activeElement === first || out)) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && (document.activeElement === last || out)) {
        e.preventDefault();
        first.focus();
      }
    };
    root.addEventListener('keydown', key);
    return () => {
      root.removeEventListener('keydown', key);
      if (back && document.contains(back)) back.focus();
    };
  }, [ref, active, start]);
  useEffect(() => {
    const root = ref.current;
    if (!active || !root || root.contains(document.activeElement)) return;
    (start === 'root' ? root : root.querySelector<HTMLElement>(FOCUSABLE) ?? root).focus();
  }, [ref, active, content, start]);
}
