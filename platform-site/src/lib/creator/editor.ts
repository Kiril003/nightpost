import type { Block, Json, Page } from './arrange.ts';
import { bookMode, tileKinds, SIGNATURE_KINDS } from './arrange.ts';
import type { Look } from './looks.ts';
import { REGISTRY, type BlockSpec, type Registry } from '../blocks/registry.ts';
import { validatePageV2 } from '../blocks/validate.ts';
import { isDemoPoint, sampleBlocks } from './samples.ts';

export type EditPage = Page & { on?: string[] };
export type Size = '1x1' | '2x1' | '2x2' | '3x1';
export const SIZES: Size[] = ['1x1', '2x1', '2x2', '3x1'];
export const SURFACES = ['W', 'A', 'M', 'C'] as const;
// App and Chat read the same public page as Site; their own toggles have no consumer yet.
export const LIVE_SURFACES: readonly string[] = ['W', 'M'];

export type SurfaceState = { s: string; active: boolean; disabled: boolean };

// The map pin opens the public page, so Map is off while Site is off.
export function pageSurfaces(on: readonly string[] | undefined): SurfaceState[] {
  const cur = on ?? SURFACES;
  const site = cur.includes('W');
  return LIVE_SURFACES.map((s) => (s === 'M' ? { s, active: site && cur.includes('M'), disabled: !site } : { s, active: cur.includes(s), disabled: s === 'W' && !cur.some((x) => x !== 'W') }));
}

export function liveSurfaces(on: readonly string[] | undefined): string[] {
  return pageSurfaces(on).filter((x) => x.active).map((x) => x.s);
}

export function blockSurfaces(on: readonly string[] | undefined, shown: readonly string[]): SurfaceState[] {
  const cur = on ?? shown;
  const n = shown.filter((s) => cur.includes(s)).length;
  return shown.map((s) => ({ s, active: cur.includes(s), disabled: cur.includes(s) && n === 1 }));
}

export function isPublic(on: readonly string[] | undefined): boolean {
  return (on ?? SURFACES).includes('W');
}
export const WEEK = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'] as const;
export const CIRCLES = ['close', 'friends', 'social', 'work', 'everyone'] as const;
export const BOOK_MODES = ['book', 'request', 'hidden'] as const;
export type Circle = (typeof CIRCLES)[number];
export type BookMode = (typeof BOOK_MODES)[number];

export type Action =
  | { t: 'add'; kind: string; after?: string | null; now?: Date }
  | { t: 'move'; id: string; to: string; place: 'before' | 'after' }
  | { t: 'nudge'; id: string; by: -1 | 1 }
  | { t: 'remove'; id: string }
  | { t: 'hide'; id: string; hidden: boolean }
  | { t: 'size'; id: string; size: Size }
  | { t: 'look'; look: Look }
  | { t: 'pageOn'; surface: string; on: boolean }
  | { t: 'blockOn'; id: string; surface: string; on: boolean; allowed: string[] }
  | { t: 'duplicate'; id: string }
  | { t: 'access'; id: string; circle: Circle; mode: BookMode }
  | { t: 'field'; id: string; path: (string | number)[]; value: unknown }
  | { t: 'fields'; sets: { id: string; path: (string | number)[]; value: unknown }[]; burst?: boolean }
  | { t: 'tone'; id: string; tone: string | null }
  | { t: 'week'; days: string[]; hours: { open: string; close: string }; after?: string | null }
  | { t: 'replace'; page: EditPage };

export type History = { past: EditPage[]; now: EditPage; future: EditPage[]; key?: string };

const LIMIT = 100;

export const KEEPALIVE_MAX = 63 * 1024;

export function fitsKeepalive(page: EditPage, base: number | null): boolean {
  return new TextEncoder().encode(JSON.stringify({ page, base })).length <= KEEPALIVE_MAX;
}

export function history(page: EditPage): History {
  return { past: [], now: page, future: [] };
}

function getIn(obj: any, path: (string | number)[]): unknown {
  return path.reduce((o, k) => (o == null ? undefined : o[k]), obj);
}

function typingKey(p: EditPage, a: Action): string | undefined {
  if (a.t === 'fields') return a.burst && a.sets.length && a.sets.every((x) => typeof x.value === 'number') ? `fields:${a.sets.map((x) => `${x.id}:${x.path.join('.')}`).join('|')}` : undefined;
  if (a.t !== 'field') return undefined;
  const key = `${a.id}:${a.path.join('.')}`;
  const b = p.blocks.find((x) => x.id === a.id);
  const was = b ? getIn(b.data, a.path) : undefined;
  const v = a.value;
  if (typeof was === 'string' && typeof v === 'string') {
    if (/\s$/.test(was) && v.length > was.length && !/\s$/.test(v)) return `${key}#${v.length}`;
    return key;
  }
  if (!plain(was) || !plain(v)) return plain(v) ? undefined : key;
  const changed = [...new Set([...Object.keys(was), ...Object.keys(v)])].filter((k) => JSON.stringify(was[k]) !== JSON.stringify(v[k]));
  return changed.length === 1 && changed[0] !== 'blob' ? `${key}.${changed[0]}` : undefined;
}

function plain(x: unknown): x is Record<string, unknown> {
  return typeof x === 'object' && x !== null && !Array.isArray(x);
}

export function apply(h: History, action: Action, reg?: Registry): History {
  const next = reduce(h.now, action, reg);
  if (next === h.now) return h;
  const key = typingKey(h.now, action);
  const base = key?.split('#')[0];
  if (key && h.key && !key.includes('#') && h.key.split('#')[0] === base && h.past.length) return { past: h.past, now: next, future: [], key: h.key };
  return { past: [...h.past, h.now].slice(-LIMIT), now: next, future: [], key };
}

export function seal(h: History): History {
  return h.key ? { ...h, key: undefined } : h;
}

export function undo(h: History): History {
  if (!h.past.length) return h;
  return { past: h.past.slice(0, -1), now: h.past[h.past.length - 1], future: [h.now, ...h.future], key: undefined };
}

export function redo(h: History): History {
  if (!h.future.length) return h;
  return { past: [...h.past, h.now], now: h.future[0], future: h.future.slice(1), key: undefined };
}

const withBlocks = (p: EditPage, blocks: Block[]): EditPage => ({ ...p, blocks });
const mapBlock = (p: EditPage, id: string, f: (b: Block) => Block): EditPage =>
  withBlocks(p, p.blocks.map((b) => (b.id === id ? f(b) : b)));

function setIn(obj: any, path: (string | number)[], value: unknown): any {
  if (!path.length) return value;
  const [k, ...rest] = path;
  const base = Array.isArray(obj) ? [...obj] : { ...(obj ?? {}) };
  const child = setIn(base[k as any], rest, value);
  if (child === undefined && !Array.isArray(base)) delete base[k as any];
  else base[k as any] = child;
  return base;
}

export function freshId(page: Page, kind: string): string {
  const stem = kind.split('.')[1].replace(/_/g, '-');
  const taken = new Set(page.blocks.map((b) => b.id));
  for (let n = page.blocks.length; ; n++) if (!taken.has(`${stem}-${n}`)) return `${stem}-${n}`;
}

function itemIds(page: Page): Set<string> {
  const out = new Set<string>();
  const walk = (v: unknown) => {
    if (Array.isArray(v)) v.forEach(walk);
    else if (v && typeof v === 'object') {
      for (const [k, x] of Object.entries(v)) {
        if (k === 'id' && typeof x === 'string') out.add(x);
        else walk(x);
      }
    }
  };
  for (const b of page.blocks) walk(b.data);
  return out;
}

/** Row ids are unique across the whole page, not per block. */
export function rowId(page: Page, stem: string): string {
  const taken = itemIds(page);
  for (let n = 1; ; n++) if (!taken.has(`${stem}-${n}`)) return `${stem}-${n}`;
}

function reduce(p: EditPage, a: Action, reg?: Registry): EditPage {
  switch (a.t) {
    case 'add': {
      if (reg && !canAdd(p, a.kind, reg)) return p;
      const made = template(a.kind, p, a.now ?? new Date());
      const at = a.after ? p.blocks.findIndex((b) => b.id === a.after) + 1 : p.blocks.length;
      const blocks = [...p.blocks];
      blocks.splice(at <= 0 ? blocks.length : at, 0, ...made);
      return withBlocks(p, blocks);
    }
    case 'duplicate': {
      const i = p.blocks.findIndex((b) => b.id === a.id);
      if (i < 0 || (reg && !canAdd(p, p.blocks[i].kind, reg))) return p;
      const src = p.blocks[i];
      const taken = itemIds(p);
      const reId = (v: unknown): unknown => {
        if (Array.isArray(v)) return v.map(reId);
        if (!v || typeof v !== 'object') return v;
        const out: Record<string, unknown> = {};
        for (const [k, x] of Object.entries(v)) out[k] = reId(x);
        const id = out.id;
        if (typeof id === 'string' && taken.has(id)) {
          const stem = id.replace(/-\d+$/, '');
          let n = 1;
          while (taken.has(`${stem}-${n}`)) n++;
          out.id = `${stem}-${n}`;
          taken.add(`${stem}-${n}`);
        }
        return out;
      };
      const copy = { ...structuredClone(src), id: freshId(p, src.kind), data: reId(src.data) } as Block;
      const blocks = [...p.blocks];
      blocks.splice(i + 1, 0, copy);
      return withBlocks(p, blocks);
    }
    case 'move': {
      if (a.id === a.to) return p;
      const moving = p.blocks.find((b) => b.id === a.id);
      if (!moving || !p.blocks.some((b) => b.id === a.to)) return p;
      const rest = p.blocks.filter((b) => b.id !== a.id);
      const at = rest.findIndex((b) => b.id === a.to) + (a.place === 'after' ? 1 : 0);
      rest.splice(at, 0, moving);
      return rest.every((b, i) => b === p.blocks[i]) ? p : withBlocks(p, rest);
    }
    case 'nudge': {
      const i = p.blocks.findIndex((b) => b.id === a.id);
      const j = i + a.by;
      if (i < 0 || j < 0 || j >= p.blocks.length) return p;
      const blocks = [...p.blocks];
      [blocks[i], blocks[j]] = [blocks[j], blocks[i]];
      return withBlocks(p, blocks);
    }
    case 'remove': {
      if (dependents(p, a.id).length) return p;
      const blocks = p.blocks.filter((b) => b.id !== a.id).map((b) => dropRefs(b, a.id));
      return blocks.length === p.blocks.length ? p : withBlocks(p, blocks);
    }
    case 'hide':
      return mapBlock(p, a.id, (b) => ({ ...b, hidden: a.hidden }));
    case 'size':
      return mapBlock(p, a.id, (b) => ({ ...b, size: a.size }));
    case 'look':
      return p.look === a.look ? p : { ...p, look: a.look };
    case 'pageOn': {
      const cur = p.on ?? [...SURFACES];
      const next = SURFACES.filter((s) => (s === a.surface ? a.on : cur.includes(s)));
      if (!next.length) return p;
      const out: EditPage = { ...p, on: next };
      if (next.length === SURFACES.length) delete out.on;
      return out;
    }
    case 'blockOn':
      return mapBlock(p, a.id, (b) => {
        const cur: string[] = (b as Json).on ?? a.allowed;
        const next = a.allowed.filter((s) => (s === a.surface ? a.on : cur.includes(s)));
        if (!next.length) return b;
        const out: Json = { ...b, on: next };
        if (next.length === a.allowed.length) delete out.on;
        return out as Block;
      });
    case 'access':
      return mapBlock(p, a.id, (b) => ({ ...b, data: { ...b.data, access: setAccess(b.data.access, a.circle, a.mode, b.data.auto) } }));
    case 'field':
      return mapBlock(p, a.id, (b) => ({ ...b, data: setIn(b.data, a.path, a.value) }));
    case 'fields':
      return a.sets.reduce((q, s) => mapBlock(q, s.id, (b) => ({ ...b, data: setIn(b.data, s.path, s.value) })), p);
    case 'tone':
      return mapBlock(p, a.id, (b) => {
        const out: Json = { ...b };
        if (a.tone) out.tone = a.tone;
        else delete out.tone;
        return out as Block;
      });
    case 'week': {
      const days = WEEK.filter((d) => a.days.includes(d));
      const cur = p.blocks.find((b) => b.kind === 'hrs.week');
      if (!days.length) return cur ? withBlocks(p, p.blocks.filter((b) => b.kind !== 'hrs.week')) : p;
      const data = { rows: [{ days, hours: { open: a.hours.open, close: a.hours.close } }] };
      if (cur) return mapBlock(p, cur.id, (b) => ({ ...b, data: { ...b.data, ...data } }));
      if (reg && !canAdd(p, 'hrs.week', reg)) return p;
      const made: Block = { id: freshId(p, 'hrs.week'), kind: 'hrs.week', hidden: false, data };
      const at = a.after ? p.blocks.findIndex((b) => b.id === a.after) + 1 : p.blocks.length;
      const blocks = [...p.blocks];
      blocks.splice(at <= 0 ? blocks.length : at, 0, made);
      return withBlocks(p, blocks);
    }
    case 'replace':
      return a.page;
  }
  return p;
}

export function canAdd(p: Page, kind: string, reg: Registry): boolean {
  const spec = reg.blocks[kind];
  if (!spec) return false;
  const need = kind === 'evt.rsvp' && !p.blocks.some((b) => b.kind === 'evt.single') ? 2 : 1;
  return p.blocks.filter((b) => b.kind === kind).length < spec.max && p.blocks.length + need <= reg.page_rules.max_blocks;
}

const NEEDS: Record<string, string> = { 'deal.drop': 'products', 'book.slots': 'services', 'evt.rsvp': 'event' };

/** Blocks that cannot live without this one; it is removed only after them. */
export function dependents(p: Page, id: string): Block[] {
  const needs = (b: Block) => (NEEDS[b.kind] ? [NEEDS[b.kind]] : Object.entries(REGISTRY.blocks[b.kind]?.fields ?? {}).filter(([, f]) => f.p === 'ref' && f.req).map(([k]) => k));
  return p.blocks.filter((b) => b.id !== id && needs(b).some((k) => b.data[k] === id));
}

function dropRefs(b: Block, gone: string): Block {
  const data = { ...b.data };
  let changed = false;
  for (const [k, v] of Object.entries(data)) {
    if (v === gone && (k === 'primary' || k === 'secondary' || REGISTRY.blocks[b.kind]?.fields[k]?.p === 'ref')) {
      delete data[k];
      if (k === 'primary' || k === 'secondary') delete data[`${k}_label`];
      changed = true;
    }
  }
  return changed ? { ...b, data } : b;
}

export const accessOf = (block: Block, circle: Circle): BookMode => bookMode(block, circle);

function setAccess(rules: { circle: string; mode: string }[] | undefined, circle: Circle, mode: BookMode, auto: boolean): { circle: string; mode: string }[] {
  const fallback = auto ? 'book' : 'request';
  return CIRCLES.map((c) => ({
    circle: c,
    mode: c === circle ? mode : rules?.find((r) => r.circle === c)?.mode ?? rules?.find((r) => r.circle === 'everyone')?.mode ?? fallback,
  }));
}

export function sizable(kind: string, look: Look): boolean {
  return look !== 'journal' && tileKinds(look).includes(kind);
}

const pad = (n: number) => String(n).padStart(2, '0');
function at(now: Date, days: number, hour: number): string {
  const d = new Date(now.getTime() + days * 86400000);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(hour)}:00`;
}

const firstOf = (p: Page, kind: string) => p.blocks.find((b) => b.kind === kind)?.id;
function pageTokenOf(p: Page): string {
  for (const b of p.blocks) if (typeof b.data?.token === 'string') return b.data.token;
  return 'USDC';
}

/** Starter blocks for the library. Required text stays empty: the creator writes it, nothing is invented. */
export function template(kind: string, page: Page, now: Date): Block[] {
  const id = freshId(page, kind);
  const token = pageTokenOf(page);
  const mk = (data: Json, variant?: string): Block => ({ id, kind, ...(variant ? { variant } : {}), hidden: false, data });
  switch (kind) {
    case 'id.hero':
      return [mk({ headline: '' }, 'split')];
    case 'deal.drop':
      return [mk({ title: '', products: firstOf(page, 'cat.products') ?? '', total: 10, stages: [{ id: rowId(page, 'st'), circle: 'everyone', opens: at(now, 1, 10) }] }, 'staged')];
    case 'book.slots':
      return [mk({ services: firstOf(page, 'cat.services') ?? '', slot: 60, buffer: 15, lead: 24, horizon: 30, auto: true }, 'day_strip')];
    case 'cat.products':
      return [mk({ title: '', token, items: [{ id: rowId(page, 'item'), name: '', price: '', stock: 1 }] }, 'grid')];
    case 'cat.services':
      return [mk({ title: '', token, items: [{ id: rowId(page, 'svc'), name: '', duration: 60, price: '' }] }, 'list')];
    case 'com.reviews':
      return [mk({ text: true, show: 3 }, 'summary')];
    case 'loc.message':
      return [mk({ on: true, who: 'owner' }, 'card')];
    case 'loc.meet_point':
      return [mk({ text: '' }, 'map_card')];
    case 'pay.qr':
      return [mk({ label: '', amount: true }, 'compact')];
    case 'evt.rsvp': {
      const ev = firstOf(page, 'evt.single');
      if (ev) return [mk({ event: ev, waitlist: true }, 'button_count')];
      const evId = freshId(page, 'evt.single');
      const event: Block = { id: evId, kind: 'evt.single', variant: 'card', hidden: false, data: { title: '', starts: at(now, 7, 18) } };
      const rsvp: Block = { id: freshId({ ...page, blocks: [...page.blocks, event] }, kind), kind, variant: 'button_count', hidden: false, data: { event: evId, waitlist: true } };
      return [event, rsvp];
    }
    case 'org.members':
      return [mk({ members: [{ id: rowId(page, 'm'), name: '' }] }, 'faces')];
    case 'pay.split_cost':
      return [mk({ title: '', token, total: '', shares: 2 }, 'seats')];
    case 'org.join':
      return [mk({ title: '', approve: 'owner' }, 'card')];
    case 'evt.single':
      return [mk({ title: '', starts: at(now, 7, 18) }, 'card')];
    case 'pay.wallet':
      return [mk({ address: '' }, 'address_row')];
    case 'id.name':
      return [mk({ name: '' }, 'plain')];
    case 'loc.address':
      return [mk({ text: '' }, 'map_line')];
    default:
      return [mk(starter(REGISTRY.blocks[kind], page, token), REGISTRY.blocks[kind]?.variants[0])];
  }
}

/** Registry-led starter: the page token, empty rows for required lists, required switches off and counts at their minimum. */
function starter(spec: BlockSpec | undefined, page: Page, token: string): Json {
  const data: Json = {};
  for (const [k, f] of Object.entries(spec?.fields ?? {})) {
    if (f.p === 'token') data[k] = token;
    else if (f.p === 'list' && f.req) {
      const rows: Json[] = [];
      for (let i = 0; i < (f.min ?? 1); i++) rows.push({ id: rowId({ ...page, blocks: [...page.blocks, { id: '', kind: '', data: { rows } }] }, f.of ?? 'row') });
      data[k] = rows;
    } else if (f.req && f.p === 'ref') data[k] = (f.target && firstOf(page, f.target)) || '';
    else if (f.req && f.p === 'toggle') data[k] = false;
    else if (f.req && f.p === 'number') data[k] = f.min ?? 0;
  }
  return data;
}

export const LIBRARY: string[] = [...SIGNATURE_KINDS];

export type Starter = { name: string; look: Look };

export function starterPage({ name, look }: Starter): EditPage {
  return { v: 2, look, tone: 'amber', blocks: [{ id: 'name-0', kind: 'id.name', variant: 'plain', hidden: false, data: { name } }] };
}

export type Change = { id: string; kind: string; what: 'added' | 'removed' | 'moved' | 'changed' | 'hidden' | 'shown'; detail?: string[] } | { id: 'page'; kind: 'page'; what: 'look' | 'on'; detail?: string[] };

const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);

export function diff(base: EditPage | null, next: EditPage): Change[] {
  const out: Change[] = [];
  const before = base?.blocks ?? [];
  const old = new Map(before.map((b) => [b.id, b]));
  const now = new Map(next.blocks.map((b) => [b.id, b]));
  if (base && base.look !== next.look) out.push({ id: 'page', kind: 'page', what: 'look', detail: [String(base.look ?? ''), String(next.look ?? '')] });
  if (base && !same(base.on ?? null, next.on ?? null)) out.push({ id: 'page', kind: 'page', what: 'on', detail: next.on ?? [...SURFACES] });
  const keptOld = before.filter((b) => now.has(b.id)).map((b) => b.id);
  const keptNew = next.blocks.filter((b) => old.has(b.id)).map((b) => b.id);
  for (const b of next.blocks) {
    const o = old.get(b.id);
    if (!o) {
      out.push({ id: b.id, kind: b.kind, what: 'added' });
      continue;
    }
    if (Boolean(o.hidden) !== Boolean(b.hidden)) {
      out.push({ id: b.id, kind: b.kind, what: b.hidden ? 'hidden' : 'shown' });
      continue;
    }
    const fields = Array.from(new Set([...Object.keys(o.data ?? {}), ...Object.keys(b.data ?? {})])).filter((k) => !same(o.data?.[k], b.data?.[k]));
    for (const k of ['size', 'on', 'variant', 'tone'] as const) if (!same((o as Json)[k], (b as Json)[k])) fields.push(k);
    if (fields.length) out.push({ id: b.id, kind: b.kind, what: 'changed', detail: fields.sort() });
    else if (keptOld.indexOf(b.id) !== keptNew.indexOf(b.id)) out.push({ id: b.id, kind: b.kind, what: 'moved' });
  }
  for (const b of before) if (!now.has(b.id)) out.push({ id: b.id, kind: b.kind, what: 'removed' });
  return out;
}

export type Fix = { block?: string; field?: string; add?: string };
export type Check = { key: 'name' | 'place' | 'sample' | 'valid' | 'alt' | 'wallet' | 'surfaces'; ok: boolean; fix?: Fix; refused?: string; at?: string; count?: number; blocks?: string[] };

const PAID = new Set(['cat.products', 'cat.services', 'pay.qr', 'pay.split_cost', 'deal.drop']);

/** Where a validator path like `blocks[3].data.items[0].price` points in the editor. */
export function fixFor(page: Page, at: string): Fix {
  const m = /^blocks\[(\d+)\](?:\.data\.([A-Za-z_]+))?/.exec(at);
  if (!m) return {};
  const b = page.blocks[Number(m[1])];
  return b ? { block: b.id, field: m[2] } : {};
}

function images(v: unknown, out: { alt: string }[] = []): { alt: string }[] {
  if (Array.isArray(v)) v.forEach((x) => images(x, out));
  else if (v && typeof v === 'object') {
    const o = v as Json;
    if (typeof o.blob === 'string' && 'alt' in o) out.push(o as { alt: string });
    else Object.values(o).forEach((x) => images(x, out));
  }
  return out;
}

export function checks(page: EditPage, reg: Registry): Check[] {
  const shown = page.blocks.filter((b) => !b.hidden);
  const name = shown.find((b) => b.kind === 'id.name');
  const place = shown.find((b) => b.kind === 'loc.address' || b.kind === 'loc.meet_point');
  const verdict = validatePageV2(page, reg, { publish: true });
  const out: Check[] = [];
  out.push(name?.data.name?.trim() ? { key: 'name', ok: true } : { key: 'name', ok: false, fix: name ? { block: name.id, field: 'name' } : { add: 'id.name' } });
  if (!place) out.push({ key: 'place', ok: false, fix: { add: 'loc.meet_point' } });
  else if (!place.data.point || isDemoPoint(place.data.point)) out.push({ key: 'place', ok: false, fix: { block: place.id, field: 'point' } });
  else out.push({ key: 'place', ok: true });
  const left = sampleBlocks(page);
  out.push(left.length ? { key: 'sample', ok: false, count: left.length, blocks: left, fix: { block: left[0] } } : { key: 'sample', ok: true });
  if (verdict.ok) out.push({ key: 'valid', ok: true });
  else {
    const f = fixFor(page, verdict.at);
    out.push({ key: 'valid', ok: false, refused: verdict.refused, at: verdict.at, fix: f.block ? f : out.find((c) => !c.ok)?.fix ?? f });
  }
  const noAlt = shown.filter((b) => images(b.data).some((i) => !i.alt?.trim()));
  out.push(noAlt.length ? { key: 'alt', ok: false, count: noAlt.length, fix: { block: noAlt[0].id } } : { key: 'alt', ok: true });
  const paid = shown.some((b) => PAID.has(b.kind));
  const wallet = shown.find((b) => b.kind === 'pay.wallet') ?? page.blocks.find((b) => b.kind === 'pay.wallet');
  if (paid) out.push(wallet?.data.address && !wallet.hidden ? { key: 'wallet', ok: true } : { key: 'wallet', ok: false, fix: wallet ? { block: wallet.id, field: 'address' } : { add: 'pay.wallet' } });
  return out;
}

export const publishable = (cs: Check[]) => cs.every((c) => c.ok || c.key === 'alt');

const PAGE_KEYS = new Set(['v', 'tone', 'look', 'on', 'visibility', 'invite', 'blocks']);

/** Mirrors the server draft door: an unfinished page is kept as long as its shape holds; content rules wait for publish. */
export function draftShape(page: EditPage, reg: Registry): boolean {
  const rules = reg.page_rules;
  if (new TextEncoder().encode(JSON.stringify(page)).length > rules.max_bytes) return false;
  if (Object.keys(page).some((k) => !PAGE_KEYS.has(k))) return false;
  if (page.look !== undefined && !reg.vocab.look.includes(page.look)) return false;
  if (!Array.isArray(page.blocks) || page.blocks.length > rules.max_blocks) return false;
  const ids = new Set<string>();
  for (const b of page.blocks) {
    if (typeof b.id !== 'string' || !b.data || typeof b.data !== 'object' || Array.isArray(b.data)) return false;
    if (!reg.blocks[b.kind] || ids.has(b.id)) return false;
    ids.add(b.id);
  }
  return true;
}

/** The full draft rules: used to point at the field that still needs filling in. */
export function draftVerdict(page: EditPage, reg: Registry): { ok: true } | { ok: false; refused: string; at: string; fix: Fix } {
  const v = validatePageV2(page, reg, { publish: false });
  return v.ok ? { ok: true } : { ok: false, refused: v.refused, at: v.at, fix: fixFor(page, v.at) };
}
