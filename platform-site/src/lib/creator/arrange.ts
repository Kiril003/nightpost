import type { Look } from './looks.ts';

export type Json = Record<string, any>;
export type Block = { id: string; kind: string; variant?: string; hidden?: boolean; audience?: string; size?: string; tone?: string; on?: string[]; data: Json };
export type Page = { v: 2; tone?: string; look?: string; on?: string[]; blocks: Block[] };
export type BookMode = 'book' | 'request' | 'hidden';
export type Live = Json;

export type Section =
  | { type: 'hero'; block: Block }
  | { type: 'tiles'; blocks: { block: Block; wide: boolean; tall?: boolean; full?: boolean }[] }
  | { type: 'block'; block: Block; with: Block[] };

export const SIGNATURE_KINDS = [
  'id.hero', 'deal.drop', 'book.slots', 'cat.products', 'com.reviews', 'loc.message',
  'loc.meet_point', 'pay.qr', 'evt.rsvp', 'org.members', 'pay.split_cost', 'org.join',
] as const;

const TILES_BASE = ['loc.meet_point', 'loc.message', 'pay.qr', 'pay.split_cost', 'org.join', 'loc.address'];
const TILES_SHOP = [...TILES_BASE, 'deal.drop', 'com.reviews'];
const WIDE_ORDER = ['loc.message', 'deal.drop', 'pay.split_cost', 'com.reviews', 'loc.meet_point', 'org.join', 'pay.qr', 'loc.address'];
const SILENT = new Set(['pay.wallet', 'id.name']);

export function tileKinds(look: Look): string[] {
  return look === 'shop' ? TILES_SHOP : TILES_BASE;
}

const CELLS: Record<string, number> = { '1x1': 1, '2x1': 2, '2x2': 4, '3x1': 3 };

type Cell = { w: number; h: number };

/** Empty cells left when the grid packs densely, as CSS `grid-auto-flow: dense` does. */
export function holes(items: Cell[], cols: number): number {
  const taken: boolean[][] = [];
  const free = (r: number, c: number) => !taken[r]?.[c];
  let rows = 0;
  let cells = 0;
  for (const it of items) {
    const w = Math.min(it.w, cols);
    for (let r = 0, placed = false; !placed; r++) {
      for (let c = 0; c + w <= cols && !placed; c++) {
        let fits = true;
        for (let dr = 0; dr < it.h && fits; dr++) for (let dc = 0; dc < w && fits; dc++) fits = free(r + dr, c + dc);
        if (!fits) continue;
        for (let dr = 0; dr < it.h; dr++) for (let dc = 0; dc < w; dc++) (taken[r + dr] ??= [])[c + dc] = true;
        rows = Math.max(rows, r + it.h);
        cells += w * it.h;
        placed = true;
      }
    }
  }
  return rows * cols - cells;
}

function lexLess(a: number[], b: number[]): boolean {
  const i = a.findIndex((v, k) => v !== b[k]);
  return i >= 0 && a[i] < b[i];
}

function tiles(run: Block[]): Section {
  const free = run.filter((b) => !CELLS[b.size ?? '']);
  const rank = (b: Block) => {
    const i = WIDE_ORDER.indexOf(b.kind);
    return i < 0 ? WIDE_ORDER.length : i;
  };
  const n = Math.min(free.length, 8);
  const span = new Map<string, number>();
  const shape = (b: Block, cols: number): Cell =>
    b.size === '2x2' ? { w: 2, h: 2 } : b.size === '2x1' ? { w: 2, h: 1 } : b.size === '3x1' ? { w: cols, h: 1 } : { w: span.get(b.id) === 3 ? cols : span.get(b.id) ?? 1, h: 1 };
  let best: { span: Map<string, number>; score: number[] } | null = null;
  for (let code = 0; code < 3 ** n; code++) {
    span.clear();
    for (let i = 0, k = code; i < n; i++, k = Math.floor(k / 3)) if (k % 3) span.set(free[i].id, (k % 3) + 1);
    const ws = [...span.values()];
    const score = [
      holes(run.map((b) => shape(b, 3)), 3), ws.filter((w) => w === 3).length,
      holes(run.map((b) => shape(b, 2)), 2), ws.length,
      [...span.keys()].reduce((t, id) => t + rank(free.find((b) => b.id === id)!), 0),
    ];
    if (!best || lexLess(score, best.score)) best = { span: new Map(span), score };
  }
  const chosen = best?.span ?? new Map<string, number>();
  return {
    type: 'tiles',
    blocks: run.map((b) => (CELLS[b.size ?? '']
      ? { block: b, wide: b.size !== '1x1', tall: b.size === '2x2', ...(b.size === '3x1' ? { full: true } : {}) }
      : { block: b, wide: chosen.has(b.id), ...(chosen.get(b.id) === 3 ? { full: true } : {}) })),
  };
}

/** What a circle may do with a booking block; a missing rule falls back to "everyone", then to the confirm setting. */
export function bookMode(block: Block, circle: string): BookMode {
  const rules: { circle: string; mode: BookMode }[] = block.data.access ?? [];
  const hit = rules.find((r) => r.circle === circle) ?? rules.find((r) => r.circle === 'everyone');
  return hit ? hit.mode : block.data.auto ? 'book' : 'request';
}

export const onSite = (b: { on?: string[] }) => !b.on || b.on.includes('W');

export function arrange(page: Page, look: Look): Section[] {
  const shown = page.blocks.filter((b) => !b.hidden && onSite(b) && !(b.kind === 'book.slots' && bookMode(b, 'everyone') === 'hidden'));
  const byId = new Map(shown.map((b) => [b.id, b]));
  const folded = new Map<string, Block[]>();
  const consumed = new Set<string>();
  const fold = (host: Block | undefined, guest: Block) => {
    if (!host || consumed.has(host.id)) return;
    folded.set(host.id, [...(folded.get(host.id) ?? []), guest]);
    consumed.add(guest.id);
  };
  for (const b of shown) {
    if (b.kind === 'book.slots') fold(byId.get(b.data.services), b);
    if (b.kind === 'evt.rsvp') fold(byId.get(b.data.event), b);
  }
  if (look !== 'shop') {
    for (const b of shown) {
      if (b.kind !== 'deal.drop' || CELLS[b.size ?? '']) continue;
      const products = byId.get(b.data.products);
      if (products && !consumed.has(products.id)) {
        folded.set(b.id, [...(folded.get(b.id) ?? []), products]);
        consumed.add(products.id);
      }
    }
  }

  const tileSet = new Set(tileKinds(look));
  const out: Section[] = [];
  let run: Block[] = [];
  const flush = () => {
    if (run.length >= 2 || (run.length === 1 && CELLS[run[0].size ?? ''])) out.push(tiles(run));
    else if (run.length === 1) out.push({ type: 'block', block: run[0], with: folded.get(run[0].id) ?? [] });
    run = [];
  };
  for (const b of shown) {
    if (consumed.has(b.id) || SILENT.has(b.kind)) continue;
    if (b.kind === 'loc.message' && !b.data.on) continue;
    if (b.kind === 'id.hero' && !out.some((s) => s.type === 'hero')) {
      flush();
      out.push({ type: 'hero', block: b });
      continue;
    }
    if ((tileSet.has(b.kind) || (TILES_SHOP.includes(b.kind) && CELLS[b.size ?? ''])) && !folded.has(b.id)) {
      run.push(b);
      continue;
    }
    flush();
    out.push({ type: 'block', block: b, with: folded.get(b.id) ?? [] });
  }
  flush();
  return out;
}

export function nameOf(page: Page): { name: string; tagline?: string } {
  const b = page.blocks.find((x) => x.kind === 'id.name' && !x.hidden);
  return { name: b?.data.name ?? '', tagline: b?.data.tagline };
}

export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  return ((parts[0]?.[0] ?? '') + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase();
}

export function toneOf(seed: string): number {
  let h = 0;
  for (const ch of seed) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  return (h % 6) + 1;
}
