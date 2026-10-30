import type { CircleInfo } from './circlesApi';

export interface RingMoveInput {
  before: string[];
  after: string[];
  circles: CircleInfo[];
  liveWhere: boolean;
  momentsNaming: (ringId: string) => number;
}

export interface RingMoveDiff {
  loses: Array<'location' | 'moments'>;
  momentsLost: number;
  gains: Array<'location' | 'moments'>;
}

export function ringMoveDiff(i: RingMoveInput): RingMoveDiff {
  const byId = new Map(i.circles.map((c) => [c.id, c]));
  const hasLocationDefault = (id: string) => byId.get(id)?.locationDefault === true;
  const hasMomentsDefault = (id: string) => byId.get(id)?.momentsDefault === true;

  const hadLoc = i.before.some(hasLocationDefault);
  const hasLoc = i.after.some(hasLocationDefault);

  const hadMom = i.before.some(hasMomentsDefault);
  const hasMom = i.after.some(hasMomentsDefault);

  const afterSet = new Set(i.after);
  const removed = [...new Set(i.before)].filter((id) => !afterSet.has(id) && byId.has(id));
  const momentsLost = hasMom ? 0 : removed.reduce((sum, id) => sum + Math.max(0, i.momentsNaming(id)), 0);

  const loses: Array<'location' | 'moments'> = [];
  const gains: Array<'location' | 'moments'> = [];

  if (hadLoc && !hasLoc && i.liveWhere) loses.push('location');
  if (!hadLoc && hasLoc) gains.push('location');

  if (momentsLost > 0) loses.push('moments');
  if (!hadMom && hasMom) gains.push('moments');

  return { loses, momentsLost, gains };
}
