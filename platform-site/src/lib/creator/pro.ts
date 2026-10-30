import type { Page } from './arrange.ts';
import type { Registry } from '../blocks/registry.ts';
import { BOOK_MODES, CIRCLES, LIVE_SURFACES, WEEK, canAdd, diff, sizable, type Check, type EditPage } from './editor.ts';
import type { Block } from './arrange.ts';
import { toned } from './inspector.ts';
import type { Look } from './looks.ts';
import { REGISTRY } from '../blocks/registry.ts';

export type EditorView = 'canvas' | 'pro';
export const VIEW_KEY = 'np.editor.view';

export function readView(store: Pick<Storage, 'getItem'> | null): EditorView {
  try {
    return store?.getItem(VIEW_KEY) === 'pro' ? 'pro' : 'canvas';
  } catch {
    return 'canvas';
  }
}

export function writeView(store: Pick<Storage, 'setItem'> | null, view: EditorView): void {
  try {
    store?.setItem(VIEW_KEY, view);
  } catch {
    /* the choice lives for this visit only */
  }
}

export type LayerKid = { t: 'list'; block: string; field: string; count: number } | { t: 'live'; block: string; field: string };
export type LayerNode = { t: 'block'; block: string; kind: string; hidden: boolean; kids: number };

/** Blocks with their list fields (row counts) and live, server-computed fields as children. */
export function layerTree(page: Page, reg: Registry): { node: LayerNode; kids: LayerKid[] }[] {
  return page.blocks.map((b) => {
    const spec = reg.blocks[b.kind];
    const kids: LayerKid[] = [];
    for (const [field, f] of Object.entries(spec?.fields ?? {})) {
      if (f.p === 'list' && field !== 'access' && Array.isArray(b.data?.[field])) kids.push({ t: 'list', block: b.id, field, count: b.data[field].length });
      else if (f.p === 'derived') kids.push({ t: 'live', block: b.id, field });
    }
    return { node: { t: 'block', block: b.id, kind: b.kind, hidden: !!b.hidden, kids: kids.length }, kids };
  });
}

export type PageState = 'live' | 'draft' | 'notPublic';
export type PageRow = { id: string; name: string; state: PageState; current: boolean };
type VenueLike = { id: string; name: string; published: boolean; page?: object | null };

export function pageState(v: VenueLike): PageState {
  if (!v.published) return 'draft';
  const on = (v.page as { on?: unknown } | null | undefined)?.on;
  return Array.isArray(on) && !on.includes('W') ? 'notPublic' : 'live';
}

/** The creator's pages, the open one first. */
export function pageRows(venues: readonly VenueLike[], current: string): PageRow[] {
  const rows = venues.map((v) => ({ id: v.id, name: v.name, state: pageState(v), current: v.id === current }));
  return [...rows.filter((r) => r.current), ...rows.filter((r) => !r.current)];
}

export type Readiness = { ok: true } | { ok: false; left: number };

/** Mirrors `publishable`: alt text warns, every other failed check blocks. */
export function readiness(cs: readonly Check[]): Readiness {
  const left = cs.filter((c) => !c.ok && c.key !== 'alt').length;
  return left ? { ok: false, left } : { ok: true };
}

/** Pro names the shared inspector tabs Content / Style / Visibility. */
export const PRO_TABS = ['content', 'look', 'who'] as const;

export type StylePart = 'size' | 'tone' | 'look';
export function styleParts(kind: string, look: Look): StylePart[] {
  return [...(sizable(kind, look) ? ['size' as const] : []), ...(toned(kind) ? ['tone' as const] : []), 'look'];
}

export type VisibilityPart = 'hidden' | 'on' | 'book';
export function visibilityParts(kind: string, reg: Registry = REGISTRY): VisibilityPart[] {
  const on = (reg.blocks[kind]?.surfaces ?? []).some((s) => LIVE_SURFACES.includes(s));
  return ['hidden', ...(on ? ['on' as const] : []), ...(kind === 'book.slots' ? ['book' as const] : [])];
}

export type Hours = { open: string; close: string };
export type WeekDay = { day: (typeof WEEK)[number]; hours: Hours | null };

/** Opening hours per weekday, read from the page's hours block. */
export function weekOf(page: Page): { days: WeekDay[]; simple: boolean; hours: Hours | null } {
  const rows: { days: string[]; hours: Hours }[] = page.blocks.find((b) => b.kind === 'hrs.week')?.data?.rows ?? [];
  const days = WEEK.map((day) => ({ day, hours: rows.find((r) => r.days?.includes(day))?.hours ?? null }));
  const kinds = new Set(rows.map((r) => `${r.hours?.open}-${r.hours?.close}`));
  return { days, simple: kinds.size <= 1, hours: rows[0]?.hours ?? null };
}

/** Accept Auto/Ask me flips every circle that can book between Book and Request; Hidden circles stay hidden. */
export function acceptSets(block: Block, auto: boolean): { id: string; path: string[]; value: unknown }[] {
  const sets: { id: string; path: string[]; value: unknown }[] = [{ id: block.id, path: ['auto'], value: auto }];
  const rules: { circle: string; mode: string }[] | undefined = block.data?.access;
  if (rules?.length) sets.push({ id: block.id, path: ['access'], value: rules.map((r) => (r.mode === 'hidden' ? r : { ...r, mode: auto ? 'book' : 'request' })) });
  return sets;
}

/** The full per-circle matrix in Pro. It drives the booking button only; it is not access control. */
export const PRO_CIRCLES = CIRCLES;
export const PRO_BOOK_MODES = BOOK_MODES;

/** In the editor a selected hidden block keeps its slot in the flow (drawn dimmed) instead of vanishing. */
export function ghostPage<P extends Page>(page: P, sel: string | null): P {
  const b = sel ? page.blocks.find((x) => x.id === sel) : null;
  return b?.hidden ? { ...page, blocks: page.blocks.map((x) => (x === b ? { ...x, hidden: false } : x)) } : page;
}

export type VersionChip = null | { live: false } | { live: true; v: number | null; changes: number };

export function versionChip(o: { local: boolean; published: boolean; versions: { n: number }[] | null; base: EditPage | null; page: EditPage }): VersionChip {
  if (o.local) return null;
  if (!o.published) return { live: false };
  return { live: true, v: o.versions?.[0]?.n ?? null, changes: diff(o.base, o.page).length };
}

export function nameOf(kind: string, title: string): string {
  const t = title.trim();
  return !t || t.toLowerCase() === kind.trim().toLowerCase() ? kind : `${kind} · ${t}`;
}

export const PREVIEWS = ['W', 'A', 'M', 'C'] as const;
export type Preview = (typeof PREVIEWS)[number];

export function blockShowsOn(b: Block, s: string, reg: Registry = REGISTRY): boolean {
  const allowed = reg.blocks[b.kind]?.surfaces ?? [];
  return allowed.includes(s) && ((b as Block & { on?: string[] }).on ?? allowed).includes(s);
}

export function pageFor<P extends Page & { on?: string[] }>(page: P, s: Preview, reg: Registry = REGISTRY): P {
  if (s === 'W') return page;
  const blocks = surfaceOn(page, s) ? page.blocks.filter((b) => blockShowsOn(b, s, reg)) : [];
  return blocks.length === page.blocks.length ? page : { ...page, blocks };
}

const NEXT: [string[], string][] = [
  [[], 'id.name'],
  [[], 'loc.address'],
  [['cat.services'], 'book.slots'],
  [['book.slots'], 'hrs.week'],
  [['loc.address'], 'hrs.week'],
  [['cat.services'], 'com.reviews'],
  [['cat.products'], 'com.reviews'],
  [['loc.address'], 'loc.message'],
  [['cat.products'], 'pay.qr'],
  [['cat.menu'], 'pay.qr'],
  [['loc.address'], 'loc.contact'],
  [['cat.menu'], 'hrs.happy_hour'],
  [['cat.services'], 'book.staff'],
  [['cat.products'], 'cat.featured'],
  [['book.slots'], 'book.policy'],
  [['evt.single'], 'evt.tickets'],
  [['cls.timetable'], 'cls.pass'],
];

export function suggested(page: Page, reg: Registry = REGISTRY, n = 3): string[] {
  const has = new Set(page.blocks.map((b) => b.kind));
  const placed = has.has('loc.address') || has.has('loc.meet_point');
  const out: string[] = [];
  for (const [need, kind] of NEXT) {
    if (out.length >= n) break;
    if (has.has(kind) || out.includes(kind) || !need.every((k) => has.has(k))) continue;
    if (kind === 'loc.address' && placed) continue;
    if (reg.blocks[kind] && canAdd(page, kind, reg)) out.push(kind);
  }
  return out;
}

export function surfaceOn(page: { on?: string[] }, s: Preview): boolean {
  const cur = page.on ?? [...PREVIEWS];
  return cur.includes(s) && (s !== 'M' || cur.includes('W'));
}
