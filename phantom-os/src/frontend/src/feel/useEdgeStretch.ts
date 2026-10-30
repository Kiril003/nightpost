import { type RefObject, useEffect, useRef } from 'react';
import {
  prefersReduced,
  runSpring,
  type Sample,
  STRETCH,
  stretchScale,
  velocity80,
} from './physics';

function attachEdgeStretch(scroller: HTMLElement, keepLayer: boolean): () => void {
  if (keepLayer) scroller.setAttribute('data-edge-layer', '');
  const promote = (node: HTMLElement) => {
    if (!keepLayer) node.style.willChange = 'transform';
  };
  let cancelSpring: (() => void) | null = null;
  let fingerDown = false;
  let activeEdge: 'top' | 'bottom' | null = null;
  let startY = 0;
  let currentScale = 1;
  let lastTouchY = 0;
  const samples: Sample[] = [];

  const stopSpring = () => {
    if (cancelSpring) {
      cancelSpring();
      cancelSpring = null;
    }
  };

  const getH = () => scroller.clientHeight || (typeof window !== 'undefined' ? window.innerHeight : 800) || 800;

  const atTop = () => scroller.scrollTop <= 0;
  const atBottom = () => scroller.scrollTop + scroller.clientHeight >= scroller.scrollHeight - 1;

  const clearNode = (node: HTMLElement | null) => {
    currentScale = 1;
    if (!node) return;
    node.style.transform = '';
    if (!keepLayer) node.style.willChange = '';
  };

  const onTouchStart = (e: TouchEvent) => {
    if (prefersReduced()) return;
    const touch = e.touches[0];
    if (!touch) return;
    stopSpring();
    fingerDown = true;
    activeEdge = null;
    startY = touch.clientY;
    lastTouchY = touch.clientY;
  };

  const onTouchMove = (e: TouchEvent) => {
    if (!fingerDown || prefersReduced()) return;
    const touch = e.touches[0];
    if (!touch) return;
    const node = scroller.firstElementChild as HTMLElement | null;
    if (!node) return;

    const y = touch.clientY;
    const dy = y - lastTouchY;
    lastTouchY = y;

    if (activeEdge === null) {
      if (atTop() && dy > 0) {
        activeEdge = 'top';
        startY = y - dy;
      } else if (atBottom() && dy < 0) {
        activeEdge = 'bottom';
        startY = y - dy;
      } else {
        return;
      }
    }

    const h = getH();
    const pull = activeEdge === 'top' ? y - startY : startY - y;

    if (pull <= 0) {
      activeEdge = null;
      clearNode(node);
      return;
    }

    const scale = stretchScale(pull, h);
    currentScale = scale;
    node.style.transformOrigin = activeEdge === 'top' ? '50% 0' : '50% 100%';
    promote(node);
    node.style.transform = `scaleY(${scale})`;
  };

  const release = () => {
    if (!fingerDown) return;
    fingerDown = false;
    const edge = activeEdge;
    activeEdge = null;

    if (prefersReduced()) return;
    const node = scroller.firstElementChild as HTMLElement | null;
    if (!node || !edge || currentScale <= 1) {
      clearNode(node);
      return;
    }

    const h = getH();
    const fromX = (currentScale - 1) * h;
    stopSpring();
    cancelSpring = runSpring({
      ...STRETCH,
      from: fromX,
      to: 0,
      v0: 0,
      onFrame: (x) => {
        const s = 1 + Math.max(0, x) / h;
        currentScale = s;
        node.style.transform = `scaleY(${s})`;
      },
      onDone: () => {
        cancelSpring = null;
        clearNode(node);
      },
    });
  };

  const onScroll = () => {
    if (prefersReduced()) return;
    const t = performance.now();
    const y = scroller.scrollTop;
    samples.push({ t, y });
    while (samples.length > 16 && t - samples[0].t > 160) {
      samples.shift();
    }

    if (fingerDown) return;
    const node = scroller.firstElementChild as HTMLElement | null;
    if (!node) return;

    const top = atTop();
    const bottom = atBottom();
    if (!top && !bottom) return;

    const v = velocity80(samples);
    if (Math.abs(v) < 0.8) return;
    if (top && v >= 0) return;
    if (bottom && v <= 0 && !top) return;

    const h = getH();
    const v0 = Math.min(0.0012, (0.5 * Math.abs(v)) / h) * h;
    samples.length = 0;
    stopSpring();
    node.style.transformOrigin = top && v < 0 ? '50% 0' : '50% 100%';
    promote(node);
    cancelSpring = runSpring({
      ...STRETCH,
      from: 0,
      to: 0,
      v0,
      onFrame: (x) => {
        const s = 1 + Math.max(0, x) / h;
        currentScale = s;
        node.style.transform = `scaleY(${s})`;
      },
      onDone: () => {
        cancelSpring = null;
        clearNode(node);
      },
    });
  };

  scroller.addEventListener('touchstart', onTouchStart, { passive: true });
  scroller.addEventListener('touchmove', onTouchMove, { passive: true });
  scroller.addEventListener('touchend', release, { passive: true });
  scroller.addEventListener('touchcancel', release, { passive: true });
  scroller.addEventListener('scroll', onScroll, { passive: true });

  return () => {
    stopSpring();
    clearNode(scroller.firstElementChild as HTMLElement | null);
    scroller.removeEventListener('touchstart', onTouchStart);
    scroller.removeEventListener('touchmove', onTouchMove);
    scroller.removeEventListener('touchend', release);
    scroller.removeEventListener('touchcancel', release);
    scroller.removeEventListener('scroll', onScroll);
    if (keepLayer) scroller.removeAttribute('data-edge-layer');
  };
}

/** keepLayer: вміст тримає власний шар постійно (див. globals.css) — без перерастру на кожен жест. */
export function useEdgeStretch(ref: RefObject<HTMLElement | null>, opts?: { keepLayer?: boolean }): void {
  const keepLayer = opts?.keepLayer === true;
  const attached = useRef<{ el: HTMLElement; detach: () => void } | null>(null);

  useEffect(() => {
    const el = ref.current;
    if (attached.current?.el === el) return;
    attached.current?.detach();
    attached.current = el ? { el, detach: attachEdgeStretch(el, keepLayer) } : null;
  });

  useEffect(
    () => () => {
      attached.current?.detach();
      attached.current = null;
    },
    [],
  );
}
