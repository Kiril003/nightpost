export function scrubTime(p: number, bounds: readonly number[], duration: number): number {
  const n = bounds.length - 1;
  const k = Math.min(n - 1, Math.floor(p * n));
  const local = Math.min(1, p * n - k);
  const t = bounds[k] + local * (bounds[k + 1] - bounds[k]);
  const end = (Number.isFinite(duration) ? duration : bounds[n]) - 0.04;
  return Math.max(0, Math.min(t, end));
}

export function turnFrame(elapsedMs: number, totalMs: number, frames: number): number {
  return Math.min(frames, 1 + Math.floor((Math.max(0, elapsedMs) / totalMs) * (frames - 1) + 0.5));
}
