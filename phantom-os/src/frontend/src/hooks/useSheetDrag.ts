/**
 * Аркуш іде за пальцем без рендерів React і відпускається пружиною шару
 * (feel/physics). Закриття — після осідання: далі третини висоти або зі швидкістю
 * за останні 80 мс; вгору тягне гумка.
 *
 * Вхідну анімацію `.f-sheet-in` (fill both) гасимо інлайном `animation: none`:
 * вона перекриває інлайновий transform, і аркуш не рухався б за пальцем.
 */
import { useEffect, useRef, type CSSProperties, type TouchEvent } from 'react';
import {
  LAYER,
  prefersReduced,
  rb,
  runSpring,
  sheetRelease,
  velocity80,
  type Sample,
} from '../feel/physics';

export const SHEET_SPRING = 'var(--ph-spring-layer)';

interface DragSession {
  startY: number;
  y: number;
  h: number;
  sheet: HTMLElement;
  scrim: HTMLElement | null;
  samples: Sample[];
}

const nowMs = (): number =>
  typeof performance !== 'undefined' && typeof performance.now === 'function'
    ? performance.now()
    : Date.now();

export function useSheetDrag(onClose: () => void) {
  const sessionRef = useRef<DragSession | null>(null);
  const cancelSpringRef = useRef<(() => void) | null>(null);
  const timerRef = useRef<number | null>(null);

  useEffect(
    () => () => {
      cancelSpringRef.current?.();
      cancelSpringRef.current = null;
      if (timerRef.current !== null) window.clearTimeout(timerRef.current);
    },
    [],
  );

  const applyFrame = (sheet: HTMLElement, scrim: HTMLElement | null, y: number, h: number) => {
    sheet.style.animation = 'none';
    sheet.style.transform = `translateY(${y}px)`;
    sheet.style.transition = 'none';
    sheet.style.willChange = 'transform';
    if (scrim) {
      scrim.style.animation = 'none';
      scrim.style.opacity = String(1 - Math.max(0, y) / h);
    }
  };

  const clearStyles = (sheet: HTMLElement, scrim: HTMLElement | null) => {
    sheet.style.transform = '';
    sheet.style.transition = '';
    sheet.style.willChange = '';
    if (scrim) {
      scrim.style.opacity = '';
    }
  };

  const handlers = {
    onTouchStart: (e: TouchEvent<HTMLElement>) => {
      const el = e.currentTarget as HTMLElement;
      if (el.scrollTop > 0) return;
      const sheet = el.closest('[role="dialog"]') as HTMLElement | null;
      if (!sheet) return;
      if (cancelSpringRef.current) {
        cancelSpringRef.current();
        cancelSpringRef.current = null;
      }
      const scrim = sheet.previousElementSibling as HTMLElement | null;
      const h = sheet.getBoundingClientRect().height || 300;
      const clientY = e.touches[0].clientY;
      sessionRef.current = {
        startY: clientY,
        y: 0,
        h,
        sheet,
        scrim,
        samples: [{ t: nowMs(), y: 0 }],
      };
    },
    onTouchMove: (e: TouchEvent<HTMLElement>) => {
      const s = sessionRef.current;
      if (!s) return;
      const dy = e.touches[0].clientY - s.startY;
      const y = dy >= 0 ? dy : -rb(-dy, 16);
      s.y = y;
      s.samples.push({ t: nowMs(), y });
      applyFrame(s.sheet, s.scrim, y, s.h);
    },
    onTouchEnd: () => {
      const s = sessionRef.current;
      sessionRef.current = null;
      if (!s) return;
      s.samples.push({ t: nowMs(), y: s.y });
      const v = velocity80(s.samples);
      const target = sheetRelease(Math.max(0, s.y), v, s.h);

      if (prefersReduced()) {
        s.sheet.style.animation = 'none';
        s.sheet.style.transition = 'transform 150ms linear';
        s.sheet.style.transform = `translateY(${target}px)`;
        if (s.scrim) {
          s.scrim.style.animation = 'none';
          s.scrim.style.opacity = String(1 - Math.max(0, target) / s.h);
        }
        timerRef.current = window.setTimeout(() => {
          timerRef.current = null;
          if (target === s.h) {
            onClose();
          } else {
            clearStyles(s.sheet, s.scrim);
          }
        }, 150);
        return;
      }

      cancelSpringRef.current = runSpring({
        k: LAYER.k,
        zeta: LAYER.zeta,
        from: s.y,
        to: target,
        v0: v,
        onFrame: (pos) => applyFrame(s.sheet, s.scrim, pos, s.h),
        onDone: () => {
          cancelSpringRef.current = null;
          if (target === s.h) {
            onClose();
          } else {
            clearStyles(s.sheet, s.scrim);
          }
        },
      });
    },
  };

  const handlersWithCancel = { ...handlers, onTouchCancel: handlers.onTouchEnd };
  const sheetStyle: CSSProperties | undefined = undefined;
  const scrimStyle: CSSProperties | undefined = undefined;
  return { handlers: handlersWithCancel, sheetStyle, scrimStyle, dragging: false };
}
