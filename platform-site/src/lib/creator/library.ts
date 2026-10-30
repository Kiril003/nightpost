import type { Block, Live, Page } from './arrange.ts';
import type { Look } from './looks.ts';
import { LIBRARY, canAdd, template } from './editor.ts';
import { SAMPLES, type Sample } from './samples.ts';
import { REGISTRY, type Registry } from '../blocks/registry.ts';

export const FAMS = ['sell', 'book', 'show', 'talk', 'pay', 'place'] as const;
export type Fam = (typeof FAMS)[number];
export type Chip = 'you' | Fam;

const FAMILY: Record<string, Fam> = {
  deal: 'sell', cat: 'sell',
  book: 'book', cls: 'book', evt: 'book',
  med: 'show', id: 'show', com: 'show', sta: 'show',
  org: 'talk',
  pay: 'pay', loy: 'pay',
  loc: 'place', hrs: 'place', ame: 'place',
};
const KIND_FAMILY: Record<string, Fam> = { 'loc.message': 'talk' };

export function famOf(kind: string, reg: Registry = REGISTRY): Fam | undefined {
  return KIND_FAMILY[kind] ?? FAMILY[reg.blocks[kind]?.family ?? ''];
}

/** The BLOCKS-60 kinds that have landed (_briefs/builder/BLOCKS-60.md), in table order. */
export const BLOCKS60: string[] = [
  'loc.contact', 'loc.list', 'loc.transit', 'loc.service_area', 'hrs.exceptions',
  'hrs.happy_hour', 'ame.amenities', 'ame.access', 'ame.rules', 'cat.price_table',
  'cat.featured', 'cat.specials', 'cat.rental', 'deal.offer', 'deal.first_visit',
  'deal.gift_card', 'book.packages', 'book.staff', 'book.table', 'book.waitlist',
  'book.policy', 'cls.timetable', 'cls.course', 'cls.pass', 'cls.membership',
  'evt.tickets', 'evt.series', 'evt.agenda', 'evt.lineup', 'loy.stamps',
];

export const LIB_KINDS: string[] = [...LIBRARY, ...['cat.services', 'evt.single', 'loc.address', ...BLOCKS60].filter((k) => !LIBRARY.includes(k))];

const LOOK_ORDER: Record<Look, string[]> = {
  shop: [
    'deal.drop', 'cat.products', 'id.hero', 'com.reviews', 'pay.qr', 'loc.message', 'cat.services', 'book.slots',
    'loc.meet_point', 'evt.single', 'evt.rsvp', 'pay.split_cost', 'org.members', 'org.join', 'loc.address',
  ],
  studio: [
    'id.hero', 'cat.services', 'book.slots', 'evt.single', 'evt.rsvp', 'com.reviews', 'loc.message', 'org.members',
    'cat.products', 'deal.drop', 'pay.qr', 'loc.meet_point', 'pay.split_cost', 'org.join', 'loc.address',
  ],
  journal: [
    'id.hero', 'loc.address', 'evt.single', 'com.reviews', 'loc.message', 'cat.services', 'book.slots', 'loc.meet_point',
    'org.members', 'org.join', 'evt.rsvp', 'pay.split_cost', 'cat.products', 'deal.drop', 'pay.qr',
  ],
};

const PARTNER: Record<string, string> = { 'book.slots': 'cat.services', 'deal.drop': 'cat.products', 'evt.rsvp': 'evt.single' };

export const onPage = (page: Page, kind: string) => page.blocks.filter((b) => b.kind === kind).length;

function rank(look: Look, kinds: string[]): string[] {
  const order = LOOK_ORDER[look] ?? LOOK_ORDER.shop;
  const at = (k: string) => (order.includes(k) ? order.indexOf(k) : order.length);
  return [...kinds].sort((a, b) => at(a) - at(b));
}

export function forYou(page: Page, look: Look, reg: Registry = REGISTRY, kinds: string[] = LIB_KINDS): string[] {
  const open = rank(look, kinds.filter((k) => canAdd(page, k, reg)));
  const wanted = (k: string) => (PARTNER[k] && onPage(page, PARTNER[k]) > 0 && onPage(page, k) === 0 ? 0 : onPage(page, k) > 0 ? 2 : 1);
  return open.map((k, i) => ({ k, i, w: wanted(k) })).sort((a, b) => a.w - b.w || a.i - b.i).map((x) => x.k);
}

const fold = (s: string) => s.normalize('NFKD').replace(/\p{M}/gu, '').toLocaleLowerCase().trim();

export const matches = (label: string, q: string) => fold(label).includes(fold(q));

export function shown({ page, look, chip, q, label, reg = REGISTRY, kinds = LIB_KINDS }: {
  page: Page; look: Look; chip: Chip; q: string; label: (kind: string) => string; reg?: Registry; kinds?: string[];
}): string[] {
  const query = q.trim();
  const pool = chip === 'you' ? (query ? rank(look, kinds) : forYou(page, look, reg, kinds)) : rank(look, kinds.filter((k) => famOf(k, reg) === chip));
  return query ? pool.filter((k) => matches(label(k), query)) : pool;
}

const NEEDS: Record<string, string> = { 'book.slots': 'services', 'evt.rsvp': 'event', 'deal.drop': 'products', 'cat.featured': 'from', 'book.waitlist': 'for', 'evt.tickets': 'event', 'evt.agenda': 'event' };

function pick(kind: string, lang: string): Sample | undefined {
  const has = (s: Sample) => s.page.blocks.some((b) => b.kind === kind);
  return SAMPLES.find((s) => has(s) && (s.lang ?? 'en') === lang) ?? SAMPLES.find(has);
}

/** One block from the fixtures (with the block it hangs on and the page name), else the editor's own starter. */
export function librarySample(kind: string, lang: string, look: Look, now: Date): { page: Page; live: Live } {
  const s = pick(kind, lang);
  if (!s) return { page: { v: 2, look, blocks: template(kind, { v: 2, blocks: [] }, now) }, live: {} };
  const all = s.page.blocks;
  const main = all.find((b) => b.kind === kind)!;
  const need = NEEDS[kind] ? all.find((b) => b.id === main.data[NEEDS[kind]]) : undefined;
  const name = all.find((b) => b.kind === 'id.name');
  const keep = new Set([name?.id, need?.id, main.id]);
  const blocks: Block[] = all.filter((b) => keep.has(b.id)).map((b) => ({ ...b, hidden: kind === 'evt.rsvp' && b === need, on: undefined, size: undefined }));
  return { page: { ...s.page, look, blocks }, live: s.live };
}
