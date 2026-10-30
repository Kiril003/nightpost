/**
 * Зустріч і рахунок — договір між вебом, мостом і сервером (`docs/scene/MEETUP_CONTRACT.md`).
 *
 * Дві нові картки на `PH-CRD:`: їхнє тіло — JSON, який складає й читає лише
 * веб; Kotlin везе його нерозібраним. Змінний стан кожної людини (іду / коли
 * буду / що я їв / лишити групу / я на місці) — НЕ в тілі картки, а позначками
 * `PH-CST:` на ній; міст згортає їх у `card_marks`.
 *
 * Тут лише форми й перевірка форми того, що приїхало з дроту. Правила — у
 * `services/meetup/*`.
 */

/** Рід і поле картки зустрічі — дзеркало майбутнього `PeerCard.Card.MEETUP`. */
export const MEETUP_KIND = 'widget:meetup' as const;
export const MEETUP_FIELD = 'meetupData' as const;
/** Рід і поле картки рахунку — дзеркало майбутнього `PeerCard.Card.BILL`. */
export const BILL_KIND = 'widget:bill' as const;
export const BILL_FIELD = 'billData' as const;
export type BillMode = 'split' | 'escrow';

/** Токени, якими можна платити частку. Ціни меню — у тому ж токені, без курсів. */
export type BillToken = 'USDC' | 'SOL';

/** Знаки після коми — USDC 6, SOL 9 (лампорти). Частки рахуються цілими одиницями. */
export const TOKEN_DECIMALS: Readonly<Record<BillToken, number>> = Object.freeze({ USDC: 6, SOL: 9 });

/** Через скільки після часу зустрічі тимчасова група ховається, якщо ніхто не натиснув «Лишити». */
export const MEETUP_TTL_MS = 24 * 3600_000;

export interface VenueRating {
  /** Середня оцінка 1..5. */
  mean: number;
  count: number;
}

/** Знімок закладу в момент запрошення — щоб картка читалась і без мережі. */
export interface VenueSnap {
  id: string;
  name: string;
  address: string;
  /** null — місце вписане рукою: точки немає, мітки на мапі теж. */
  lat: number | null;
  lon: number | null;
  /** null — ще ніхто не оцінив. Не 0 і не вигадана зірка. */
  rating: VenueRating | null;
  /**
   * Версія меню, з якої взято ціни рахунку. Сервер закладів дає її рядком
   * (16 hex від вмісту меню, `venues.py`); null — у закладу ще немає меню.
   */
  menuVersion: string | number | null;
  /** Гаманець закладу, якщо власник його опублікував; лише тоді можна «платити закладу». */
  wallet?: string | null;
}

const TYPED = 'typed:';

/** Місце, яке людина вписала сама, коли закладів поруч немає. */
export const typedVenue = (name: string): VenueSnap => ({
  id: `${TYPED}${name.trim().toLocaleLowerCase()}`,
  name: name.trim(),
  address: '',
  lat: null,
  lon: null,
  rating: null,
  menuVersion: null,
});

export const isTypedVenue = (v: Pick<VenueSnap, 'id'>): boolean => v.id.startsWith(TYPED);

const PIN = 'pin:';

/** Мітка, яку людина поставила на мапі: точка є, сторінки закладу немає. */
export const pinVenue = (lat: number, lon: number, name: string): VenueSnap => ({
  id: `${PIN}${lat.toFixed(5)},${lon.toFixed(5)}`,
  name: name.trim(),
  address: '',
  lat,
  lon,
  rating: null,
  menuVersion: null,
});

export const isPinVenue = (v: Pick<VenueSnap, 'id'>): boolean => v.id.startsWith(PIN);

export const placeVenue = (p: { lat: number; lon: number; name: string; address?: string | null }): VenueSnap => ({
  ...pinVenue(p.lat, p.lon, p.name),
  address: p.address?.trim() ?? '',
});

/** Чи є в закладу сторінка на сервері закладів (оцінка, меню, бали за «Я на місці»). */
export const hasVenuePage = (v: Pick<VenueSnap, 'id'>): boolean => !isTypedVenue(v) && !isPinVenue(v);

const onEarth = (lat: unknown, lon: unknown): boolean =>
  typeof lat === 'number' && typeof lon === 'number' && Math.abs(lat) <= 90 && Math.abs(lon) <= 180;


export interface MeetupInvitee {
  id: string;
  name: string;
}

export interface MeetupData {
  v: 1;
  id: string;
  venue: VenueSnap;
  /** Час зустрічі, мс Unix. */
  at: number;
  /** Хто кличе — peerId. Йому за замовчуванням платять частки. */
  organizer: string;
  invitees: MeetupInvitee[];
  /** Коли тимчасова група ховається; null — звичайна група, не ховається. */
  expiresAt: number | null;
  /** 2–3 часи на голосування (`slot:<i>`); перший = `at`. Обирає той, хто кличе (`lock:<i>`). */
  slots?: number[];
  /** Скільки триває, хв; null — без кінця. */
  durationMin?: number | null;
  /** Скільки місць разом з тим, хто кличе; null — без меж. */
  spots?: number | null;
  /** Можна привести ще одного (`plus:1`). */
  plusOne?: boolean;
  title?: string;
}

export const MAX_TITLE = 80;

export const MAX_SLOTS = 3;

export interface BillItem {
  /** Короткий id страви: [A-Za-z0-9_-], ≤36 — щоб `dish:<id>` влізло в 64 байти позначки. */
  id: string;
  name: string;
  /** Десятковий рядок у токені рахунку: "7.5", "0.25". Без округлень, без float. */
  price: string;
}

/**
 * Кому платять частки. За замовчуванням — організатору (адреса вже звірена
 * 1:1); «закладу» — лише коли сторінка закладу має гаманець.
 */
export type BillPayee =
  | { kind: 'peer'; id: string }
  | { kind: 'venue'; venueId: string; address: string };

export interface BillAsk {
  id: string;
  amount: string;
}

export interface BillEscrow {
  address: string;
}

export interface BillFile {
  sha256: string;
  name: string;
}

export interface BillData {
  v: 1;
  id: string;
  meetupId: string | null;
  venueId: string;
  title: string;
  token: BillToken;
  items: BillItem[];
  payee: BillPayee;
  people: MeetupInvitee[];
  asks?: BillAsk[];
  mode?: BillMode;
  escrow?: BillEscrow;
  file?: BillFile;
}

/**
 * Згорнуті мостом позначки `PH-CST:` на картці: значення → автори.
 * Те саме, що `PeerMark.foldAll(log, CARD_STATE)` для одного рядка.
 */
export type CardMarks = Readonly<Record<string, readonly string[]>>;

/** Одна позначка в журналі — для власного згортання (оптимістичний домальовок, тести). */
export interface CardMarkCast {
  author: string;
  value: string;
  on: boolean;
  /** Годинник автора, мс — той самий порядок, що `PeerMessage.sentAtMs`. */
  at: number;
  /** Id кадру — другий ключ порядку, як у `PeerMark.foldAll`. */
  id?: string;
}

/** Межа значення позначки в байтах UTF-8 — дзеркало `PeerMark`. */
export const MAX_MARK_BYTES = 64;

const ITEM_ID = /^[A-Za-z0-9_-]{1,36}$/;
const DECIMAL = /^(0|[1-9]\d{0,15})(\.\d{1,9})?$/;
const BASE58_ADDR = /^[1-9A-HJ-NP-Za-km-z]{32,44}$/;

export const isItemId = (s: unknown): s is string => typeof s === 'string' && ITEM_ID.test(s);
export const isDecimal = (s: unknown): s is string => typeof s === 'string' && DECIMAL.test(s);

const isObj = (x: unknown): x is Record<string, unknown> =>
  !!x && typeof x === 'object' && !Array.isArray(x);
const isStr = (x: unknown): x is string => typeof x === 'string' && x.length > 0;
const isNum = (x: unknown): x is number => typeof x === 'number' && Number.isFinite(x);

function person(x: unknown): MeetupInvitee | null {
  if (!isObj(x) || !isStr(x.id) || typeof x.name !== 'string') return null;
  return { id: x.id, name: x.name };
}

function people(x: unknown): MeetupInvitee[] | null {
  if (!Array.isArray(x)) return null;
  const out = x.map(person);
  if (out.some((p) => p === null)) return null;
  const list = out as MeetupInvitee[];
  return new Set(list.map((p) => p.id)).size === list.length ? list : null;
}

function venue(x: unknown): VenueSnap | null {
  if (!isObj(x) || !isStr(x.id) || !isStr(x.name) || typeof x.address !== 'string') return null;
  const typed = isTypedVenue({ id: x.id });
  if (typed ? x.lat !== null || x.lon !== null : !isNum(x.lat) || !isNum(x.lon)) return null;
  if (!typed && !onEarth(x.lat, x.lon)) return null;
  const mv = x.menuVersion;
  if (mv !== null && !isNum(mv) && !isStr(mv)) return null;
  let rating: VenueRating | null = null;
  if (x.rating != null) {
    const r = x.rating;
    if (!isObj(r) || !isNum(r.mean) || !isNum(r.count) || r.count < 0) return null;
    rating = { mean: r.mean, count: r.count };
  }
  const wallet = isStr(x.wallet) ? x.wallet : null;
  return { id: x.id, name: x.name, address: x.address, lat: x.lat as number | null, lon: x.lon as number | null, rating, menuVersion: mv as string | number | null, wallet };
}

/** Тіло картки зустрічі з дроту, або null — форма не та (картка тоді чесно «не читається»). */
export function parseMeetupData(x: unknown): MeetupData | null {
  if (!isObj(x) || x.v !== 1 || !isStr(x.id) || !isNum(x.at) || !isStr(x.organizer)) return null;
  const v = venue(x.venue);
  const inv = people(x.invitees);
  if (!v || !inv) return null;
  if (x.expiresAt !== null && !isNum(x.expiresAt)) return null;
  const m: MeetupData = { v: 1, id: x.id, venue: v, at: x.at, organizer: x.organizer, invitees: inv, expiresAt: x.expiresAt as number | null };
  if (Array.isArray(x.slots) && x.slots.length >= 2 && x.slots.length <= MAX_SLOTS && x.slots.every(isNum) && x.slots[0] === x.at) {
    m.slots = x.slots as number[];
  }
  if (x.durationMin === null || (isNum(x.durationMin) && x.durationMin > 0 && x.durationMin <= 24 * 60)) m.durationMin = x.durationMin as number | null;
  if (isNum(x.spots) && Number.isInteger(x.spots) && x.spots >= 2 && x.spots <= 99) m.spots = x.spots;
  if (x.plusOne === true) m.plusOne = true;
  if (typeof x.title === 'string' && x.title.trim()) m.title = x.title.trim().slice(0, MAX_TITLE);
  return m;
}

/** Тіло картки рахунку з дроту, або null. */
export function parseBillData(x: unknown): BillData | null {
  if (!isObj(x) || x.v !== 1 || !isStr(x.id) || !isStr(x.venueId) || typeof x.title !== 'string') return null;
  if (x.meetupId !== null && !isStr(x.meetupId)) return null;
  if (x.token !== 'USDC' && x.token !== 'SOL') return null;
  if (!Array.isArray(x.items)) return null;
  const items: BillItem[] = [];
  for (const it of x.items) {
    if (!isObj(it) || !isItemId(it.id) || typeof it.name !== 'string' || !isDecimal(it.price)) return null;
    const [, frac = ''] = it.price.split('.');
    if (frac.length > TOKEN_DECIMALS[x.token]) return null;
    items.push({ id: it.id, name: it.name, price: it.price });
  }
  if (new Set(items.map((i) => i.id)).size !== items.length) return null;
  const p = x.payee;
  let payee: BillPayee;
  if (isObj(p) && p.kind === 'peer' && isStr(p.id)) payee = { kind: 'peer', id: p.id };
  else if (isObj(p) && p.kind === 'venue' && isStr(p.venueId) && isStr(p.address)) {
    payee = { kind: 'venue', venueId: p.venueId, address: p.address };
  } else return null;
  const ppl = people(x.people);
  if (!ppl) return null;
  let asks: BillAsk[] | undefined;
  if (x.asks !== undefined) {
    if (!Array.isArray(x.asks) || x.asks.length === 0) return null;
    const peopleIds = new Set(ppl.map((personItem) => personItem.id));
    const seenAsks = new Set<string>();
    asks = [];
    for (const a of x.asks) {
      if (!isObj(a) || !isStr(a.id) || !peopleIds.has(a.id) || seenAsks.has(a.id) || !isDecimal(a.amount)) return null;
      const [whole, frac = ''] = a.amount.split('.');
      if (frac.length > TOKEN_DECIMALS[x.token]) return null;
      if (!/[1-9]/.test(whole + frac)) return null;
      seenAsks.add(a.id);
      asks.push({ id: a.id, amount: a.amount });
    }
  }
  const mode: BillMode | undefined = x.mode === 'split' || x.mode === 'escrow' ? x.mode : undefined;
  const escrow: BillEscrow | undefined =
    isObj(x.escrow) && isStr(x.escrow.address) && BASE58_ADDR.test(x.escrow.address)
      ? { address: x.escrow.address }
      : undefined;
  const file: BillFile | undefined =
    isObj(x.file) && typeof x.file.sha256 === 'string' && /^[0-9a-f]{64}$/.test(x.file.sha256) && typeof x.file.name === 'string' && x.file.name.length <= 80
      ? { sha256: x.file.sha256, name: x.file.name }
      : undefined;
  return {
    v: 1,
    id: x.id,
    meetupId: x.meetupId as string | null,
    venueId: x.venueId,
    title: x.title,
    token: x.token,
    items,
    payee,
    people: ppl,
    ...(asks ? { asks } : {}),
    ...(mode ? { mode } : {}),
    ...(escrow ? { escrow } : {}),
    ...(file ? { file } : {}),
  };
}

/**
 * `card_marks` з рядка мосту → форма, яку читають правила, або undefined.
 * Значення ≤ 64 байти, автори — непорожні рядки без повторів; сміття
 * випадає, а не ламає картку.
 */
export function parseCardMarks(x: unknown): CardMarks | undefined {
  if (!isObj(x)) return undefined;
  const out: Record<string, string[]> = {};
  for (const [value, authors] of Object.entries(x)) {
    if (!value || new TextEncoder().encode(value).length > MAX_MARK_BYTES || !Array.isArray(authors)) continue;
    const ids = [...new Set(authors.filter(isStr))];
    if (ids.length) out[value] = ids;
  }
  return out;
}
