import { useRef } from 'react';
import type React from 'react';
import { PRESS, prefersReduced, runSpring } from './physics';
import { haptic } from '../utils/haptic';

export interface PressOptions {
  delay: number;
  longMs?: number;
  onLong?: () => void;
  mark?: boolean;
  scale?: boolean;
}

export interface PressController {
  down(el: HTMLElement, x: number, y: number): void;
  move(x: number, y: number): void;
  up(): void;
  cancel(o?: { snap?: boolean }): void;
}

export function createPress(o: PressOptions): PressController {
  let el: HTMLElement | null = null;
  let startX = 0;
  let startY = 0;
  let downAt = 0;
  let active = false;
  let markTimer: ReturnType<typeof setTimeout> | null = null;
  let releaseMarkTimer: ReturnType<typeof setTimeout> | null = null;
  let longTimer: ReturnType<typeof setTimeout> | null = null;
  let rafId: number | null = null;
  let stopSpring: (() => void) | null = null;
  let markedAt: number | null = null;
  let currentScale = 1;
  let longFired = false;

  const nowMs = () => Date.now();

  const stopTimers = () => {
    if (markTimer !== null) {
      clearTimeout(markTimer);
      markTimer = null;
    }
    if (longTimer !== null) {
      clearTimeout(longTimer);
      longTimer = null;
    }
    if (rafId !== null && typeof cancelAnimationFrame === 'function') {
      cancelAnimationFrame(rafId);
      rafId = null;
    }
  };

  const clearMarkNow = () => {
    if (releaseMarkTimer !== null) {
      clearTimeout(releaseMarkTimer);
      releaseMarkTimer = null;
    }
    if (el) {
      el.removeAttribute('data-pressed');
    }
    markedAt = null;
  };

  const releaseMark = () => {
    if (markTimer !== null) {
      clearTimeout(markTimer);
      markTimer = null;
    }
    if (markedAt === null || !el) return;
    const target = el;
    const elapsed = nowMs() - markedAt;
    if (elapsed >= 100) {
      target.removeAttribute('data-pressed');
      markedAt = null;
    } else {
      const wait = 100 - elapsed;
      if (releaseMarkTimer !== null) clearTimeout(releaseMarkTimer);
      releaseMarkTimer = setTimeout(() => {
        releaseMarkTimer = null;
        target.removeAttribute('data-pressed');
        markedAt = null;
      }, wait);
    }
  };

  const springScaleBack = () => {
    if (!el) return;
    const target = el;
    if (stopSpring) {
      stopSpring();
      stopSpring = null;
    }
    if (currentScale === 1 || prefersReduced()) {
      currentScale = 1;
      target.style.transform = '';
      return;
    }
    const from = currentScale;
    stopSpring = runSpring({
      ...PRESS,
      from,
      to: 1,
      v0: 0,
      onFrame: (v) => {
        currentScale = v;
        target.style.transform = `scale(${v})`;
      },
      onDone: () => {
        currentScale = 1;
        target.style.transform = '';
        stopSpring = null;
      },
    });
  };

  const down = (target: HTMLElement, x: number, y: number) => {
    stopTimers();
    if (stopSpring) {
      stopSpring();
      stopSpring = null;
    }
    if (releaseMarkTimer !== null) {
      clearTimeout(releaseMarkTimer);
      releaseMarkTimer = null;
    }
    if (el && el !== target) {
      el.removeAttribute('data-pressed');
      el.style.transform = '';
    }
    el = target;
    startX = x;
    startY = y;
    downAt = nowMs();
    active = true;
    longFired = false;
    markedAt = null;
    currentScale = 1;

    if (o.mark) {
      if (o.delay <= 0) {
        el.setAttribute('data-pressed', '');
        markedAt = nowMs();
      } else {
        markTimer = setTimeout(() => {
          markTimer = null;
          if (!active || !el) return;
          el.setAttribute('data-pressed', '');
          markedAt = nowMs();
        }, o.delay);
      }
    }

    const longMs = o.longMs;
    if (typeof longMs === 'number' && longMs > 0) {
      if (o.scale && !prefersReduced() && typeof requestAnimationFrame === 'function') {
        const span = Math.max(1, longMs - 120);
        const step = () => {
          if (!active || !el || longFired) {
            rafId = null;
            return;
          }
          const dt = nowMs() - downAt;
          if (dt >= 120 && dt < longMs) {
            const p = Math.min(1, Math.max(0, (dt - 120) / span));
            currentScale = 1 - 0.02 * p;
            el.style.transform = `scale(${currentScale})`;
          }
          if (dt < longMs) {
            rafId = requestAnimationFrame(step);
          } else {
            rafId = null;
          }
        };
        rafId = requestAnimationFrame(step);
      }

      longTimer = setTimeout(() => {
        longTimer = null;
        if (!active || longFired) return;
        longFired = true;
        if (rafId !== null && typeof cancelAnimationFrame === 'function') {
          cancelAnimationFrame(rafId);
          rafId = null;
        }
        haptic('long');
        o.onLong?.();
        springScaleBack();
      }, longMs);
    }
  };

  const cancel = (c?: { snap?: boolean }) => {
    if (!active && markedAt === null && currentScale === 1 && !stopSpring) return;
    active = false;
    stopTimers();
    clearMarkNow();
    if (c?.snap) {
      if (stopSpring) {
        stopSpring();
        stopSpring = null;
      }
      currentScale = 1;
      if (el) el.style.transform = '';
      return;
    }
    springScaleBack();
  };

  const move = (x: number, y: number) => {
    if (!active) return;
    const dx = x - startX;
    const dy = y - startY;
    if (Math.hypot(dx, dy) > 8) {
      cancel();
    }
  };

  const up = () => {
    if (!active) return;
    active = false;
    stopTimers();
    releaseMark();
    springScaleBack();
  };

  return { down, move, up, cancel };
}

export function usePress(o: { delay: number; longMs?: number; onLong?: () => void }) {
  const optsRef = useRef(o);
  optsRef.current = o;
  const ctrlRef = useRef<PressController | null>(null);
  if (!ctrlRef.current) {
    ctrlRef.current = createPress({
      delay: o.delay,
      longMs: o.longMs,
      mark: true,
      scale: true,
      onLong: () => optsRef.current.onLong?.(),
    });
  }
  return {
    onPointerDown: (e: React.PointerEvent<HTMLElement>) => {
      ctrlRef.current!.down(e.currentTarget, e.clientX, e.clientY);
    },
    onPointerMove: (e: React.PointerEvent<HTMLElement>) => {
      ctrlRef.current!.move(e.clientX, e.clientY);
    },
    onPointerUp: () => {
      ctrlRef.current!.up();
    },
    onPointerCancel: () => {
      ctrlRef.current!.cancel();
    },
  };
}
