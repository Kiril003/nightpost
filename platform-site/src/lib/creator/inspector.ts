import type { Json } from './arrange.ts';
import type { FieldSpec } from '../blocks/registry.ts';

export type Focus = { x: number; y: number };

const unit = (n: number) => Math.round(Math.min(1, Math.max(0, Number.isFinite(n) ? n : 0.5)) * 1000) / 1000;

export function clampFocus(f: { x: number; y: number }): Focus {
  return { x: unit(f.x), y: unit(f.y) };
}

export function focusOf(image: Json | undefined): Focus {
  const f = image?.focus;
  return f && typeof f.x === 'number' && typeof f.y === 'number' ? clampFocus(f) : { x: 0.5, y: 0.5 };
}

export function focusAt(rect: { left: number; top: number; width: number; height: number }, clientX: number, clientY: number): Focus {
  if (!rect.width || !rect.height) return { x: 0.5, y: 0.5 };
  return clampFocus({ x: (clientX - rect.left) / rect.width, y: (clientY - rect.top) / rect.height });
}

const ARROWS: Record<string, [number, number]> = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] };

export function nudgeFocus(f: Focus, key: string, big = false): Focus | null {
  if (key === 'Home') return { x: 0.5, y: 0.5 };
  const d = ARROWS[key];
  if (!d) return null;
  const s = big ? 0.1 : 0.02;
  return clampFocus({ x: f.x + d[0] * s, y: f.y + d[1] * s });
}

export function objectPosition(f: Focus): string {
  return `${Math.round(f.x * 1000) / 10}% ${Math.round(f.y * 1000) / 10}%`;
}

/** Next value for a − n + stepper; undefined stays undefined on −, starts at min on +. */
export function stepValue(value: unknown, delta: -1 | 1, f: Pick<FieldSpec, 'min' | 'max' | 'step'>): number | undefined {
  const min = f.min ?? 0;
  const max = f.max ?? Number.MAX_SAFE_INTEGER;
  if (typeof value !== 'number' || !Number.isFinite(value)) return delta > 0 ? min : undefined;
  return Math.min(max, Math.max(min, value + delta * (f.step ?? 1)));
}

/** The record field the stepper drives: the first whole-number field of the record. */
export function stepperKey(record: Record<string, FieldSpec>): string | null {
  for (const [k, f] of Object.entries(record)) if (f.p === 'number' && f.int) return k;
  return null;
}

export function nameKey(record: Record<string, FieldSpec>): string | null {
  for (const k of ['name', 'label', 'title', 'text']) if (record[k]?.p === 'text') return k;
  return null;
}

/** Header count: total stock when the stepper counts stock, otherwise the number of rows. */
export function listCount(rows: Json[], key: string | null): number {
  if (key === 'stock') return rows.reduce((n, r) => n + (typeof r[key] === 'number' ? r[key] : 0), 0);
  return rows.length;
}

const empty = (v: unknown) => v === undefined || v === null || (typeof v === 'string' && !v.trim());

export function rowMissing(row: Json, record: Record<string, FieldSpec>): boolean {
  return Object.entries(record).some(([k, f]) => k !== 'id' && f.req && f.p !== 'derived' && empty(row[k]));
}

export const isTimeline = (record: Record<string, FieldSpec>) => record.circle?.vocab === 'circle' && record.opens?.p === 'datetime';

export function nextCircle(rows: Json[], circles: readonly string[]): string {
  const used = new Set(rows.map((r) => r.circle));
  return circles.find((c) => !used.has(c)) ?? circles[circles.length - 1];
}

export function stageOpen(opens: unknown, now: Date): boolean {
  if (typeof opens !== 'string' || !opens) return false;
  const t = new Date(opens).getTime();
  return Number.isFinite(t) && t <= now.getTime();
}

export const TONE_VAR: Record<string, { bg: string; ink: string }> = {
  amber: { bg: '--cx-t4', ink: '--cx-t4i' },
  sage: { bg: '--cx-t2', ink: '--cx-t2i' },
  sea: { bg: '--cx-t3', ink: '--cx-t3i' },
  iris: { bg: '--cx-t6', ink: '--cx-t6i' },
  rose: { bg: '--cx-t5', ink: '--cx-t5i' },
  graphite: { bg: '--cx-overlay', ink: '--cx-text' },
};

export const toned = (kind: string) => kind !== 'id.name';

/** Surfaces the "Also shows in" row offers: the block's own, limited to what the page is on. */
export function alsoSurfaces(blockAllowed: readonly string[], pageOn: readonly string[]): string[] {
  return blockAllowed.filter((s) => pageOn.includes(s));
}

type Set1 = { id: string; path: (string | number)[]; value: unknown };

/** The products a drop sells, with their pieces left; null when the drop names no products block. */
export function dropItems(page: { blocks: Json[] }, drop: Json): { block: Json; rows: { name: string; stock: number; image?: Json }[]; sum: number } | null {
  const block = page.blocks.find((b) => b.id === drop.data?.products && b.kind === 'cat.products');
  if (!block) return null;
  const rows = (Array.isArray(block.data?.items) ? block.data.items : []).map((r: Json) => ({
    name: typeof r.name === 'string' ? r.name : '',
    stock: typeof r.stock === 'number' ? r.stock : 0,
    image: r.image,
  }));
  return { block, rows, sum: rows.reduce((n: number, r: { stock: number }) => n + r.stock, 0) };
}

/** Pieces of one item changed in the drop: the drop total follows while it still equals the pieces, and never drops below 1. */
export function dropStockSets(page: { blocks: Json[] }, drop: Json, i: number, value: number): Set1[] {
  const items = dropItems(page, drop);
  if (!items) return [];
  const sets: Set1[] = [{ id: items.block.id, path: ['items', i, 'stock'], value }];
  const next = Math.max(1, items.sum - (items.rows[i]?.stock ?? 0) + value);
  if (drop.data?.total === Math.max(1, items.sum) && next !== drop.data.total) sets.push({ id: drop.id, path: ['total'], value: next });
  return sets;
}
