export const LAYER = { k: 400, zeta: 0.86 };
export const STRETCH = { k: 608, zeta: 0.98 };
export const SNAP = { k: 180, zeta: 0.75 };
export const PRESS = { k: 700, zeta: 0.9 };

export function rb(x: number, d: number): number {
  if (d <= 0) return 0;
  if (x < 0) return -rb(-x, d);
  return (1 - 1 / ((0.55 * x) / d + 1)) * d;
}

export function stretchScale(pull: number, h: number): number {
  if (pull <= 0 || h <= 0) return 1;
  return 1 + Math.min(0.06, (0.25 * rb(pull, h)) / h);
}

export type Sample = { t: number; y: number };

export function velocity80(samples: readonly Sample[]): number {
  if (samples.length < 2) return 0;
  const last = samples[samples.length - 1];
  let firstIdx = samples.length - 1;
  for (let i = 0; i < samples.length; i += 1) {
    if (last.t - samples[i].t <= 80) {
      firstIdx = i;
      break;
    }
  }
  if (firstIdx >= samples.length - 1) return 0;
  const first = samples[firstIdx];
  const dt = last.t - first.t;
  if (dt <= 0) return 0;
  return (last.y - first.y) / dt;
}

export function springAt(
  k: number,
  zeta: number,
  x0: number,
  v0: number,
  tMs: number,
): { x: number; v: number } {
  const t = tMs / 1000;
  const v0s = v0 * 1000;
  const w0 = Math.sqrt(k);

  if (Math.abs(zeta - 1) < 1e-6) {
    const c1 = x0;
    const c2 = v0s + w0 * x0;
    const e = Math.exp(-w0 * t);
    const x = e * (c1 + c2 * t);
    const v = e * (c2 - w0 * (c1 + c2 * t));
    return { x, v: v / 1000 };
  }

  if (zeta < 1) {
    const wd = w0 * Math.sqrt(1 - zeta * zeta);
    const c1 = x0;
    const c2 = (v0s + zeta * w0 * x0) / wd;
    const e = Math.exp(-zeta * w0 * t);
    const cos = Math.cos(wd * t);
    const sin = Math.sin(wd * t);
    const x = e * (c1 * cos + c2 * sin);
    const v = e * (-zeta * w0 * (c1 * cos + c2 * sin) + wd * (-c1 * sin + c2 * cos));
    return { x, v: v / 1000 };
  }

  const sq = Math.sqrt(zeta * zeta - 1);
  const r1 = -w0 * (zeta - sq);
  const r2 = -w0 * (zeta + sq);
  const c1 = (v0s - r2 * x0) / (r1 - r2);
  const c2 = x0 - c1;
  const e1 = Math.exp(r1 * t);
  const e2 = Math.exp(r2 * t);
  const x = c1 * e1 + c2 * e2;
  const v = c1 * r1 * e1 + c2 * r2 * e2;
  return { x, v: v / 1000 };
}

/**
 * Час осідання пружини з 0 до 1 зі спокою: остання мить, коли до цілі далі
 * за 0.1 %. Та сама міра, що в build-tokens.mjs, — тож це і є --ph-spring-*-ms.
 */
export function springSettleMs(s: { k: number; zeta: number }): number {
  let settle = 0;
  for (let t = 0; t < 3000; t += 1) if (Math.abs(springAt(s.k, s.zeta, 1, 0, t).x) > 0.001) settle = t;
  return settle;
}

export function runSpring(o: {
  k: number;
  zeta: number;
  from: number;
  to: number;
  v0: number;
  onFrame: (x: number) => void;
  onDone?: () => void;
  raf?: (cb: FrameRequestCallback) => number;
  caf?: (id: number) => void;
  now?: () => number;
}): () => void {
  const raf = o.raf ?? ((cb: FrameRequestCallback) => window.requestAnimationFrame(cb));
  const caf = o.caf ?? ((id: number) => window.cancelAnimationFrame(id));
  const now = o.now ?? (() => performance.now());

  const start = now();
  const x0 = o.from - o.to;
  let cancelled = false;
  let frameId = 0;

  const step: FrameRequestCallback = () => {
    if (cancelled) return;
    const elapsed = Math.max(0, now() - start);
    const { x, v } = springAt(o.k, o.zeta, x0, o.v0, elapsed);
    if (Math.abs(x) < 0.5 && Math.abs(v) < 0.02) {
      o.onFrame(o.to);
      cancelled = true;
      o.onDone?.();
      return;
    }
    o.onFrame(o.to + x);
    frameId = raf(step);
  };

  frameId = raf(step);

  return () => {
    if (cancelled) return;
    cancelled = true;
    caf(frameId);
  };
}

export function rowRelease(x: number, v: number, leadW: number, trailW: number): number {
  if (x === 0) return 0;
  const sideW = x > 0 ? leadW : trailW;
  const target = x > 0 ? leadW : -trailW;
  const sameSign = (x > 0 && v > 0) || (x < 0 && v < 0);
  const absV = Math.abs(v);
  const absX = Math.abs(x);

  if (sameSign && absV > 0.3 && absX > 16) {
    return sideW > 0 ? target : 0;
  }
  if (absV > 0.3) {
    return 0;
  }
  if (absX > 56) {
    return sideW > 0 ? target : 0;
  }
  return 0;
}

export function sheetRelease(dy: number, v: number, h: number): number {
  return dy > h / 3 || v > 0.5 ? h : 0;
}

export function pageRelease(dx: number, v: number, w: number): -1 | 0 | 1 {
  const absDx = Math.abs(dx);
  if (dx < 0 && (absDx > 0.2 * w || (v < -0.35 && absDx > 16))) return 1;
  if (dx > 0 && (absDx > 0.2 * w || (v > 0.35 && absDx > 16))) return -1;
  return 0;
}

export function prefersReduced(): boolean {
  if (typeof document !== 'undefined' && document.documentElement.classList.contains('reduce-motion')) return true;
  return (
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  );
}
