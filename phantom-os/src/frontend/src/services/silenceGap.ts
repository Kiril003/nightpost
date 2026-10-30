export type SilenceStep = 1 | 2 | 3;
export interface SilenceGap {
  step: SilenceStep;
  unit: 'h' | 'd';
  n: number;
}

/** Додатковий вертикальний відступ над роздільником, px. */
export const SILENCE_EXTRA_PX: Record<SilenceStep, number> = { 1: 8, 2: 20, 3: 32 };

const HOUR = 3600_000;

export function silenceGap(gapMs: number): SilenceGap | null {
  if (!Number.isFinite(gapMs) || gapMs < 6 * HOUR) return null;
  const hours = Math.floor(gapMs / HOUR);
  if (hours < 24) return { step: 2, unit: 'h', n: hours };
  return { step: 3, unit: 'd', n: Math.floor(hours / 24) };
}
