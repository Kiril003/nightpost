export function scrubProgress(top: number, height: number, viewport: number): number {
  const run = height - viewport;
  if (run <= 0) return top <= 0 ? 1 : 0;
  return Math.min(1, Math.max(0, -top / run));
}

export function frameIndex(progress: number, count: number): number {
  if (count <= 1) return 0;
  return Math.min(count - 1, Math.max(0, Math.round(progress * (count - 1))));
}

/** `/wow/hero/f-###.webp` → f-001 … f-NNN. */
export function frameUrls(pattern: string, count: number, from = 1): string[] {
  const m = pattern.match(/#+/);
  if (!m) return [pattern];
  return Array.from({ length: count }, (_, i) => pattern.replace(m[0], String(from + i).padStart(m[0].length, '0')));
}
