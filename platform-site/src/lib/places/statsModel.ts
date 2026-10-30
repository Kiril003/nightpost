import type { VenueStats, VisitDay } from '../placesApi';

export interface VisitBar {
  day: string;
  count: number;
  ratio: number;
}

const MIN_NONZERO_RATIO = 0.08;

export function barHeights(visits: readonly VisitDay[]): VisitBar[] {
  const max = visits.reduce((m, v) => (v.count > m ? v.count : m), 0);
  return visits.map((v) => {
    if (max <= 0 || v.count <= 0) {
      return { day: v.day, count: v.count, ratio: 0 };
    }
    const raw = v.count / max;
    return {
      day: v.day,
      count: v.count,
      ratio: Math.max(MIN_NONZERO_RATIO, Math.min(1, raw)),
    };
  });
}

export function statsEmpty(stats: Pick<VenueStats, 'visits' | 'visitors'> | null | undefined): boolean {
  if (!stats) return true;
  if (stats.visitors > 0) return false;
  return stats.visits.every((v) => v.count <= 0);
}
