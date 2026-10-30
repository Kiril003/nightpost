import { PASS_NONE } from '../stores/profilesStore';

export const PASS_GROUPS = ['personal', 'close', 'friends', 'social', 'work'] as const;
export type PassGroup = (typeof PASS_GROUPS)[number];

export function passedGroups(passGroups: string[]): PassGroup[] {
  if (passGroups.includes(PASS_NONE)) return [];
  if (passGroups.length === 0) return [...PASS_GROUPS];
  return PASS_GROUPS.filter((g) => passGroups.includes(g));
}

export function togglePass(passGroups: string[], g: PassGroup): string[] {
  const now = passedGroups(passGroups);
  const next = now.includes(g) ? now.filter((x) => x !== g) : [...now, g];
  if (next.length === 0) return [PASS_NONE];
  if (next.length === PASS_GROUPS.length) return [];
  return PASS_GROUPS.filter((x) => next.includes(x));
}
