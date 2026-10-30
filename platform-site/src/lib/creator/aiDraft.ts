import type { EditPage } from './editor.ts';

export const AI_KINDS = ['book', 'shop', 'classes', 'hiring', 'delivery'] as const;
export type AiKind = (typeof AI_KINDS)[number];
export type Assistant = 'read' | 'off' | 'failed' | 'limit' | 'skipped';
export type AiResult = {
  page: EditPage;
  assistant: Assistant;
  table: { rows: number; unpriced: number; currencies: string[] };
  photos: { given: number; linked: number };
};
type Lang = 'uk' | 'en';
type Item = Record<string, unknown> & { id: string; name?: string; price?: string; was?: string; photo?: string };
type Image = { blob: string; w?: number; h?: number };

const PHOTO_KINDS = new Set(['cat.services', 'cat.products', 'book.staff']);
const ALL_DAYS = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'];
const DAY = { uk: { mon: 'пн', tue: 'вт', wed: 'ср', thu: 'чт', fri: 'пт', sat: 'сб', sun: 'нд' }, en: { mon: 'Mon', tue: 'Tue', wed: 'Wed', thu: 'Thu', fri: 'Fri', sat: 'Sat', sun: 'Sun' } } as const;

const C = {
  uk: {
    off: 'Асистент недоступний', fromTable: 'чернетка з таблиці', fromText: 'чернетка з опису',
    services: 'Послуги', products: 'Товари', prices: 'Ціни', staff: 'Майстри', hours: 'Години', deposit: 'Депозит', photos: 'Фото', place: 'Місце на мапі',
    fromList: (n: number) => `${n} з прайсу`, of: (a: number, b: number) => `${a} з ${b}`, daily: 'щодня', addAddress: 'вкажіть адресу', none: 'немає', allSet: 'усі є',
  },
  en: {
    off: 'Assistant unavailable', fromTable: 'draft from the table', fromText: 'draft from the description',
    services: 'Services', products: 'Products', prices: 'Prices', staff: 'Staff', hours: 'Hours', deposit: 'Deposit', photos: 'Photos', place: 'Map point',
    fromList: (n: number) => `${n} from the list`, of: (a: number, b: number) => `${a} of ${b}`, daily: 'daily', addAddress: 'add the address', none: 'none', allSet: 'all set',
  },
};

export function toBase64(bytes: Uint8Array): string {
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s);
}

export function askBody(o: { kind: AiKind; lang: string; text: string; table?: { name: string; bytes: Uint8Array } | null; photos: string[] }) {
  return {
    kind: o.kind,
    lang: o.lang,
    text: o.text.trim(),
    ...(o.table ? { table: { name: o.table.name, b64: toBase64(o.table.bytes) } } : {}),
    photos: o.photos,
  };
}

export function assistantNote(r: AiResult, lang: Lang): string | null {
  if (r.assistant === 'read' || r.assistant === 'skipped') return null;
  return `${C[lang].off} · ${r.table.rows > 0 ? C[lang].fromTable : C[lang].fromText}`;
}

const itemsOf = (b: EditPage['blocks'][number]): Item[] => (Array.isArray(b.data.items) ? (b.data.items as Item[]) : []);

export type Unpriced = { block: string; item: string; name: string; was: string; token: string };

export function unpriced(page: EditPage): Unpriced[] {
  return page.blocks.flatMap((b) =>
    b.kind === 'cat.services' || b.kind === 'cat.products'
      ? itemsOf(b).filter((i) => !i.price && i.was).map((i) => ({ block: b.id, item: i.id, name: String(i.name ?? ''), was: String(i.was), token: String(b.data.token ?? '') }))
      : [],
  );
}

export function unpricedLine(n: number, lang: Lang): string {
  if (lang === 'en') return `${n} ${n === 1 ? 'price' : 'prices'} not in the page currency`;
  const d = n % 10, h = n % 100;
  const word = d === 1 && h !== 11 ? 'ціна' : d >= 2 && d <= 4 && (h < 12 || h > 14) ? 'ціни' : 'цін';
  return `${n} ${word} без валюти сторінки`;
}

function mapItem(page: EditPage, block: string, item: string, f: (i: Item) => Item): EditPage {
  return {
    ...page,
    blocks: page.blocks.map((b) => (b.id !== block ? b : { ...b, data: { ...b.data, items: itemsOf(b).map((i) => (i.id === item ? f(i) : i)) } })),
  };
}

export function setPrice(page: EditPage, block: string, item: string, value: string): EditPage {
  const v = value.trim().replace(',', '.');
  const ok = /^(0|[1-9]\d{0,8})(\.\d{1,6})?$/.test(v) && /[1-9]/.test(v);
  return mapItem(page, block, item, (i) => ({ ...i, price: ok ? v : '' }));
}

export function draftName(page: EditPage): string {
  return String(page.blocks.find((b) => b.kind === 'id.name')?.data.name ?? '').trim();
}

export function setName(page: EditPage, name: string): EditPage {
  return { ...page, blocks: page.blocks.map((b) => (b.kind === 'id.name' ? { ...b, data: { ...b.data, name: name.slice(0, 80) } } : b)) };
}

export type Slot = { key: string; block: string; item: string; kind: string; name: string };

export function photoSlots(page: EditPage): Slot[] {
  return page.blocks.flatMap((b) =>
    PHOTO_KINDS.has(b.kind) ? itemsOf(b).map((i) => ({ key: `${b.id}/${i.id}`, block: b.id, item: i.id, kind: b.kind, name: String(i.name ?? '') })) : [],
  );
}

export type Placed = Record<string, string | null>;

export function initialPlacement(page: EditPage, photos: string[]): Placed {
  const at = new Map<string, string>();
  for (const b of page.blocks) for (const i of itemsOf(b)) if (typeof i.photo === 'string' && !at.has(i.photo)) at.set(i.photo, `${b.id}/${i.id}`);
  return Object.fromEntries(photos.map((p) => [p, at.get(p) ?? null]));
}

export function place(placed: Placed, photo: string, slot: string | null): Placed {
  const out: Placed = {};
  for (const [p, s] of Object.entries(placed)) out[p] = slot !== null && s === slot ? null : s;
  out[photo] = slot;
  return out;
}

export function tray(placed: Placed): string[] {
  return Object.keys(placed).filter((p) => placed[p] === null);
}

export type Check = { key: string; label: string; value: string; done: boolean };

export function checklist(page: EditPage, r: AiResult, placed: Placed, lang: Lang): { rows: Check[]; done: number; total: number } {
  const t = C[lang];
  const rows: Check[] = [];
  const find = (k: string) => page.blocks.find((b) => b.kind === k);
  const cat = find('cat.services') ?? find('cat.products');
  if (cat) {
    const named = itemsOf(cat).filter((i) => String(i.name ?? '').trim()).length;
    rows.push({ key: 'items', label: cat.kind === 'cat.services' ? t.services : t.products, value: named ? (r.table.rows ? t.fromList(named) : String(named)) : t.none, done: named > 0 });
    const open = unpriced(page).length;
    if (open) rows.push({ key: 'prices', label: t.prices, value: unpricedLine(open, lang), done: false });
  }
  const staff = find('book.staff');
  if (staff) rows.push({ key: 'staff', label: t.staff, value: String(itemsOf(staff).length), done: true });
  const week = find('hrs.week');
  const range = (week?.data.rows as { days: string[]; hours: { open: string; close: string } }[] | undefined)?.[0];
  if (range) {
    const days = range.days.length === 7 && ALL_DAYS.every((d) => range.days.includes(d)) ? t.daily : range.days.map((d) => DAY[lang][d as keyof (typeof DAY)['uk']]).join(', ');
    rows.push({ key: 'hours', label: t.hours, value: `${days ? `${days} ` : ''}${range.hours.open}–${range.hours.close}`, done: true });
  } else if (find('book.slots')) rows.push({ key: 'hours', label: t.hours, value: t.none, done: false });
  const deposit = find('book.policy')?.data.deposit;
  if (typeof deposit === 'number') rows.push({ key: 'deposit', label: t.deposit, value: `${deposit}%`, done: true });
  const given = Object.keys(placed).length;
  if (given) {
    const on = given - tray(placed).length;
    rows.push({ key: 'photos', label: t.photos, value: t.of(on, given), done: on > 0 });
  }
  rows.push({ key: 'place', label: t.place, value: t.addAddress, done: false });
  return { rows, done: rows.filter((x) => x.done).length, total: rows.length };
}

export function finalPage(page: EditPage, placed: Placed, uploaded: Record<string, Image>): EditPage {
  const onSlot = new Map<string, Image>();
  for (const [p, s] of Object.entries(placed)) if (s && uploaded[p]) onSlot.set(s, uploaded[p]);
  return {
    ...page,
    blocks: page.blocks.map((b) => {
      if (!Array.isArray(b.data.items)) return b;
      const items = itemsOf(b).map(({ photo: _p, was: _w, ...i }) => {
        const img = onSlot.get(`${b.id}/${i.id}`);
        return img ? { ...i, image: { ...img, alt: '' } } : i;
      });
      return { ...b, data: { ...b.data, items } };
    }),
  };
}
