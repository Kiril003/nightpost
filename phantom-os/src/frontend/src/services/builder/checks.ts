import { validatePage, PAGE_VERSION, type Block, type Problem } from './venueBlocks';

export type CheckProblem = Problem | 'no_point';

export interface Failure {
  problem: CheckProblem;
  /** Номер блоку в сторінці; null — сторінка в цілому. */
  block: number | null;
}

/**
 * Усі причини, чому сторінку не можна публікувати, а не лише перша: власник
 * лагодить за один прохід. Той самий `validatePage`, що в сервера й на сайті.
 */
export function publishFailures(blocks: Block[]): Failure[] {
  const out: Failure[] = [];
  const index = blocks.map((_, i) => i);
  let rest = [...blocks];
  for (let guard = 0; guard < 40; guard++) {
    const v = validatePage({ v: PAGE_VERSION, blocks: rest }, { publish: true });
    if (v.ok) break;
    const problem = v.problem as Problem;
    if (v.block == null) {
      out.push({ problem, block: null });
      // Причина сторінки в цілому: далі йдемо лише якщо її можна обійти.
      if (problem === 'missing_title' || problem === 'needs_address') {
        const probe = problem === 'missing_title' ? ({ type: 'title', text: 'x' } as Block) : ({ type: 'address', text: 'x', lat: 1, lon: 1 } as Block);
        rest = [...rest, probe];
        index.push(-1);
        continue;
      }
      break;
    }
    out.push({ problem, block: index[v.block] });
    rest = rest.filter((_, i) => i !== v.block);
    index.splice(v.block, 1);
  }
  blocks.forEach((b, i) => {
    if (b.type === 'address' && b.text.trim() && b.lat === 0 && b.lon === 0) out.push({ problem: 'no_point', block: i });
  });
  const seen = new Set<string>();
  return out.filter((f) => {
    const k = `${f.problem}:${f.block}`;
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });
}

export const hasPayments = (blocks: Block[]): boolean =>
  blocks.some((b) => b.type === 'wallet' || (b.type === 'menu' && b.items.length > 0) || (b.type === 'event' && !!b.price));
