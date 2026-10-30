/**
 * Стрічка моментів (20-moments §3): непобачене першим, далі коло за колом
 * (Особисті → Близькі → Друзі → Знайомі → Робота → інші), всередині — свіже першим.
 * Свої — окремо після нового. Побачене — за «Старіші». Жодних балів і чужих.
 */
import type { Moment } from '../../../services/moments';
import { CIRCLES, type RealCircle } from '../todayFormat';

export type FeedFilter = 'all' | RealCircle;

export interface FeedSection {
  circle: RealCircle | null;
  posts: Moment[];
}

export interface FeedModel {
  fresh: FeedSection[];
  mine: Moment[];
  older: FeedSection[];
  /** Кола, у яких є хоч один чужий пост: лише вони стають чипами. */
  circles: RealCircle[];
}

const circleAt = (rank: number): RealCircle | null => (rank >= 0 && rank < CIRCLES.length ? CIRCLES[rank] : null);

function sections(posts: Moment[], ringOf: (fromId: string) => number): FeedSection[] {
  const by = new Map<number, Moment[]>();
  for (const m of posts) {
    const r = Math.min(ringOf(m.fromId), CIRCLES.length);
    const list = by.get(r);
    if (list) list.push(m);
    else by.set(r, [m]);
  }
  return [...by.keys()]
    .sort((a, b) => a - b)
    .map((r) => ({ circle: circleAt(r), posts: by.get(r)!.sort((a, b) => b.atMs - a.atMs) }));
}

/**
 * `freshIds` — знімок непобаченого на момент відкриття: пост, що став побаченим
 * під час гортання, не стрибає вниз до «Старіші».
 */
export function buildFeed(
  list: Moment[],
  ringOf: (fromId: string) => number,
  opts: { filter: FeedFilter; freshIds: ReadonlySet<string> },
): FeedModel {
  const theirs = list.filter((m) => !m.mine);
  const circles = CIRCLES.filter((c) => theirs.some((m) => circleAt(ringOf(m.fromId)) === c));
  const picked = opts.filter === 'all' ? theirs : theirs.filter((m) => circleAt(ringOf(m.fromId)) === opts.filter);
  return {
    fresh: sections(picked.filter((m) => opts.freshIds.has(m.id)), ringOf),
    mine: opts.filter === 'all' ? list.filter((m) => m.mine).sort((a, b) => b.atMs - a.atMs) : [],
    older: sections(picked.filter((m) => !opts.freshIds.has(m.id)), ringOf),
    circles,
  };
}

export const unseenIds = (list: Moment[]): Set<string> => new Set(list.filter((m) => !m.mine && !m.seen).map((m) => m.id));
