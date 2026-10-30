/**
 * Зустріч і рахунок — усі двері веба назовні (`docs/scene/MEETUP_CONTRACT.md` §10).
 *
 * Веб у телефоні не бачить сервера (CSP `connect-src 'self'`), тож усе йде
 * шляхами мосту `/messenger/...`: заклади й бали міст проксує на сервер і
 * підписує ключем апарата, позначки стану — кладе в дріт (`PH-CST:`), переказ
 * — відкриває рідний гаманець із відбитком. Тут жодна відповідь не
 * домальовується: відмова мосту стає реченням, а не «успіхом».
 */
import { request } from './api';
import { phoneBridge } from './messengerApi';
import { CANCEL_VALUE, atValue, marksToSend, type MarkOp } from './meetup/marks';
import { fromUnits } from './meetup/split';
import { isCardKind } from './composerCards';
import { addReminderRef, cancelReminder, type ReminderRef } from './letterReminders';
import { useMessengerStore } from '../stores/messengerStore';
import { tNow } from '../components/chat/tNow';
import type { MessageKey } from '../i18n';
import {
  BILL_KIND,
  MEETUP_KIND,
  isDecimal,
  isItemId,
  parseCardMarks,
  type BillData,
  type BillItem,
  type BillToken,
  type CardMarks,
  type MeetupData,
  type VenueRating,
  type VenueSnap,
} from '../types/meetup';
import type { Message } from '../types/messenger';

// ── заклади (сервер `venues.py`, через міст) ────────────────────────────────

export type VenueBlock =
  | { type: 'title'; text: string }
  | { type: 'about'; text: string }
  | { type: 'address'; text: string; lat: number; lon: number }
  | { type: 'hours'; rows: Array<{ day: string; open: string; close: string }> }
  | { type: 'menu'; token: BillToken; items: Array<{ id: string; name: string; price: string; note?: string }>; title?: string }
  | {
      type: 'event';
      id: string;
      title: string;
      starts: string;
      ends?: string;
      note?: string;
      price?: string;
      token?: BillToken;
    }
  | { type: 'wallet'; address: string };

/** `GET /messenger/venues/{id}` — сторінка закладу цілком (`venues.public_view`). */
export interface VenuePage {
  id: string;
  name: string;
  blocks: VenueBlock[];
  address: string | null;
  lat: number | null;
  lon: number | null;
  token: BillToken | null;
  menuVersion: string | null;
  wallet: string | null;
  rating: VenueRating | null;
}

const obj = (v: unknown): Record<string, unknown> | null =>
  v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null;
const str = (v: unknown): string | null => (typeof v === 'string' && v.trim() ? v : null);
const num = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : null);
const token = (v: unknown): BillToken | null => (v === 'USDC' || v === 'SOL' ? v : null);
const EVENT_ISO = /^\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])T([01]\d|2[0-3]):[0-5]\d$/;
const isEventIso = (v: unknown): v is string => typeof v === 'string' && EVENT_ISO.test(v);

export function ratingOf(v: unknown): VenueRating | null {
  const r = obj(v);
  const mean = num(r?.mean);
  const count = num(r?.count);
  return r && mean !== null && count !== null && count > 0 ? { mean, count } : null;
}

/** Рядок `/venues/near` → знімок для картки, або null (без точки на мапі зустрічі не буває). */
export function venueSnapOf(v: unknown): VenueSnap | null {
  const o = obj(v);
  if (!o) return null;
  const loc = obj(o.location);
  const id = str(o.id);
  const name = str(o.name);
  const lat = num(o.lat) ?? num(loc?.lat);
  const lon = num(o.lon) ?? num(loc?.lon);
  if (!id || !name || lat === null || lon === null) return null;
  return {
    id,
    name,
    address: str(o.address) ?? str(loc?.address) ?? '',
    lat,
    lon,
    rating: ratingOf(o.rating),
    menuVersion: str(o.menu_version) ?? num(o.menu_version),
    wallet: str(o.wallet),
  };
}

function block(b: unknown): VenueBlock | null {
  const o = obj(b);
  if (!o) return null;
  switch (o.type) {
    case 'title':
    case 'about':
      return str(o.text) ? { type: o.type, text: o.text as string } : null;
    case 'address':
      return str(o.text) && num(o.lat) !== null && num(o.lon) !== null
        ? { type: 'address', text: o.text as string, lat: o.lat as number, lon: o.lon as number }
        : null;
    case 'hours': {
      const rows = Array.isArray(o.rows) ? o.rows.map(obj).filter((r): r is Record<string, unknown> => !!r) : [];
      const clean = rows
        .filter((r) => str(r.day) && str(r.open) && str(r.close))
        .map((r) => ({ day: r.day as string, open: r.open as string, close: r.close as string }));
      return clean.length ? { type: 'hours', rows: clean } : null;
    }
    case 'menu': {
      const t = token(o.token);
      if (!t || !Array.isArray(o.items)) return null;
      const items = o.items
        .map(obj)
        .filter((i): i is Record<string, unknown> => !!i && isItemId(i.id) && !!str(i.name) && isDecimal(i.price))
        .map((i) => ({ id: i.id as string, name: i.name as string, price: i.price as string, ...(str(i.note) ? { note: i.note as string } : {}) }));
      return { type: 'menu', token: t, items, ...(str(o.title) ? { title: o.title as string } : {}) };
    }
    case 'event': {
      const id = isItemId(o.id) ? (o.id as string) : null;
      const title = str(o.title);
      const starts = isEventIso(o.starts) ? o.starts : null;
      if (!id || !title || !starts) return null;
      const ends = isEventIso(o.ends) && o.ends > starts ? o.ends : null;
      if (o.ends !== undefined && !ends) return null;
      const hasPrice = o.price !== undefined || o.token !== undefined;
      const evToken = token(o.token);
      const evPrice = isDecimal(o.price) ? (o.price as string) : null;
      if (hasPrice && (!evToken || !evPrice)) return null;
      return {
        type: 'event',
        id,
        title,
        starts,
        ...(ends ? { ends } : {}),
        ...(str(o.note) ? { note: o.note as string } : {}),
        ...(evPrice && evToken ? { price: evPrice, token: evToken } : {}),
      };
    }
    case 'wallet':
      return str(o.address) ? { type: 'wallet', address: o.address as string } : null;
    default:
      return null;
  }
}

export function venuePageOf(v: unknown): VenuePage | null {
  const o = obj(v);
  const id = str(o?.id);
  const name = str(o?.name);
  if (!o || !id || !name) return null;
  const page = obj(o.page);
  const blocks = (Array.isArray(page?.blocks) ? page!.blocks : []).map(block).filter((b): b is VenueBlock => !!b);
  const loc = obj(o.location);
  return {
    id,
    name,
    blocks,
    address: str(loc?.address),
    lat: num(loc?.lat),
    lon: num(loc?.lon),
    token: token(o.token),
    menuVersion: str(o.menu_version),
    wallet: str(o.wallet),
    rating: ratingOf(o.rating),
  };
}

/** Меню сторінки одним переліком (сервер тримає одну валюту на все меню). */
export function menuOf(page: VenuePage): { token: BillToken | null; items: BillItem[] } {
  const menus = page.blocks.filter((b): b is Extract<VenueBlock, { type: 'menu' }> => b.type === 'menu');
  return {
    token: menus[0]?.token ?? page.token,
    items: menus.flatMap((m) => m.items.map(({ id, name, price }) => ({ id, name, price }))),
  };
}

/** Сторінка → знімок для картки зустрічі (з гаманцем, якщо власник його дав). */
export function snapOfPage(page: VenuePage): VenueSnap | null {
  if (page.lat === null || page.lon === null) return null;
  return {
    id: page.id,
    name: page.name,
    address: page.address ?? '',
    lat: page.lat,
    lon: page.lon,
    rating: page.rating,
    menuVersion: page.menuVersion,
    wallet: page.wallet,
  };
}

// ── відмови мосту — словами ────────────────────────────────────────────────

const statusOf = (e: unknown): number | null =>
  e && typeof e === 'object' && typeof (e as { status?: unknown }).status === 'number' ? (e as { status: number }).status : null;

const codeOf = (e: unknown): string =>
  e && typeof e === 'object' && typeof (e as { code?: unknown }).code === 'string' ? (e as { code: string }).code : '';

/**
 * Чи це «такого шляху немає», а не «такої речі немає». Міст телефона каже
 * про відсутній шлях кодом `not_bridged` або `not_found` зі своїм реченням
 * («немає такого шляху» / «no such path»); FastAPI на ПК — голим «Not Found».
 * Будь-який інший 404 (заклад зняли, листа немає) — відповідь про річ.
 */
function routeMissing(e: unknown): boolean {
  const s = statusOf(e);
  if (s === 405 || s === 501) return true;
  if (s !== 404) return false;
  const code = codeOf(e);
  const said = e instanceof Error ? e.message.trim() : '';
  if (code === 'not_bridged') return true;
  if (said === 'Not Found') return true;
  return code === 'not_found' && /^(немає такого шляху|no such path)(:|$)/i.test(said);
}

/** Речення про відмову: старий міст без шляху — «ще не вміє»; інше (і 404 речі) — слово мосту чи сервера. */
export function refusalWords(e: unknown, whatKey: MessageKey): string {
  const what = tNow(whatKey);
  if (routeMissing(e)) return tNow('svc.meetup.refuse.noRoute', { what });
  const said = e instanceof Error && e.message && e.message !== 'Unknown error' ? e.message : '';
  if (statusOf(e) === 404) return said ? tNow('svc.meetup.refuse.notFoundSaid', { said }) : tNow('svc.meetup.refuse.notFound', { what });
  return said ? tNow('svc.meetup.refuse.failedSaid', { what, said }) : tNow('svc.meetup.refuse.failed', { what });
}

// ── двері ────────────────────────────────────────────────────────────────

/** Тіло прохання про переказ частки: людині (`peer_id`) або закладу (`to`). */
export type SharePayAsk =
  | { peer_id: string; token: BillToken; amount: string; chat_id: string; ref: string }
  | { to: string; token: BillToken; amount: string; chat_id: string; ref: string };

export const meetupApi = {
  /** Заклади біля мене. Клітинку з мого місця рахує телефон — веб координат не має. */
  near: async (): Promise<VenueSnap[]> => {
    const r = await request<{ venues?: unknown[] }>('GET', '/messenger/venues/near');
    return (Array.isArray(r?.venues) ? r.venues : []).map(venueSnapOf).filter((v): v is VenueSnap => !!v);
  },
  venue: async (id: string): Promise<VenuePage> => {
    const page = venuePageOf(await request<unknown>('GET', `/messenger/venues/${encodeURIComponent(id)}`));
    if (!page) throw new Error('venue page could not be read');
    return page;
  },
  /** Оцінка 1..5 — міст підписує її ключем апарата, як факти правди. */
  rate: async (id: string, stars: number): Promise<VenueRating | null> => {
    const r = await request<{ rating?: unknown }>('POST', `/messenger/venues/${encodeURIComponent(id)}/rate`, { stars });
    return ratingOf(r?.rating);
  },
  /** Одна позначка `PH-CST:` на картці. Відповідь — згорнуті позначки рядка, якщо міст їх дав. */
  mark: async (messageId: string, value: string, on: boolean): Promise<CardMarks | undefined> => {
    const r = await request<{ card_marks?: unknown }>('POST', `/messenger/messages/${encodeURIComponent(messageId)}/marks`, {
      value,
      on,
    });
    return parseCardMarks(r?.card_marks);
  },
  pay: (ask: SharePayAsk) => request<{ opened: boolean }>('POST', '/messenger/solana/pay', ask),
  /** «Я на місці» зі згодою: телефон бере місце, рахує клітинку закладу й підписує заявку. */
  checkin: (meetup: Pick<MeetupData, 'id' | 'venue' | 'at'>) =>
    request<unknown>('POST', '/messenger/points/checkin', { meetup_id: meetup.id, venue_id: meetup.venue.id, at_ms: meetup.at }),
  /** Перша власна підтверджена частка — сервер сам звірить підпис у мережі. */
  claimPayment: (signature: string, ref: string) =>
    request<unknown>('POST', '/messenger/points/payment', { signature, ref }),
  pointsMe: () => request<unknown>('GET', '/messenger/points/me'),
};

// ── позначки на картці ─────────────────────────────────────────────────────

/** Позначки після [ops] — для миттєвого показу до відповіді мосту. */
export function applyMarkOps(marks: CardMarks | undefined, me: string, ops: readonly MarkOp[]): CardMarks {
  const out: Record<string, string[]> = {};
  for (const [v, a] of Object.entries(marks ?? {})) out[v] = [...a];
  for (const op of ops) {
    const holders = (out[op.value] ?? []).filter((a) => a !== me);
    if (op.on) holders.push(me);
    if (holders.length) out[op.value] = holders;
    else delete out[op.value];
  }
  return out;
}

const patchMessage = (messageId: string, fields: Partial<Message>) => {
  const st = useMessengerStore.getState();
  useMessengerStore.setState({
    chats: st.chats.map((c) =>
      c.messages.some((m) => m.id === messageId)
        ? { ...c, messages: c.messages.map((m) => (m.id === messageId ? { ...m, ...fields } : m)) }
        : c,
    ),
  });
};

/**
 * Зробити мій стан на картці [value] (`marksToSend`): кадри по одному, у
 * порядку «зняти старе → поставити нове». Показ міняється лише після того,
 * як міст узяв кадр; відмова — речення, і стан лишається тим, що був.
 * Повертає null або речення відмови.
 */
export async function setCardMark(msg: Message, me: string, value: string): Promise<string | null> {
  const ops = marksToSend(msg.cardMarks, me, value);
  if (!ops.length) return null;
  let marks = msg.cardMarks;
  for (const op of ops) {
    try {
      const folded = await meetupApi.mark(msg.id, op.value, op.on);
      marks = folded ?? applyMarkOps(marks, me, [op]);
    } catch (e) {
      if (marks !== msg.cardMarks) patchMessage(msg.id, { cardMarks: marks });
      return refusalWords(e, 'svc.meetup.what.marks');
    }
  }
  patchMessage(msg.id, { cardMarks: marks });
  return null;
}

// ── зустріч і рахунок у розмову ────────────────────────────────────────────

/** Чому картку цього роду зараз не надіслати, або null. Речення — для людини. */
export function cardKindRefusal(kind: typeof MEETUP_KIND | typeof BILL_KIND): string | null {
  if (isCardKind(kind)) return null;
  return kind === MEETUP_KIND
    ? tNow('svc.meetup.cardNo.meetup')
    : tNow('svc.meetup.cardNo.bill');
}

/**
 * Запросити: нова група «Cafe X · 19:00» з людьми і картка зустрічі в ній,
 * або картка в наявну групу [intoChat]. Повертає id розмови або кидає речення.
 */
export async function inviteMeetup(meetup: MeetupData, title: string, intoChat: string | null): Promise<string> {
  const refused = cardKindRefusal(MEETUP_KIND);
  if (refused) throw new Error(refused);
  const st = useMessengerStore.getState();
  let chatId = intoChat;
  if (!chatId) {
    chatId = await st.createGroup(title, 'friends', '', undefined, meetup.invitees.map((p) => p.id));
  } else {
    st.setActiveChat(chatId);
  }
  const said = await useMessengerStore.getState().sendCard(MEETUP_KIND, meetup);
  if (said) throw new Error(said);
  return chatId;
}

export async function sendBill(bill: BillData): Promise<void> {
  const refused = cardKindRefusal(BILL_KIND);
  if (refused) throw new Error(refused);
  const said = await useMessengerStore.getState().sendCard(BILL_KIND, bill);
  if (said) throw new Error(said);
}

/** Прохання до телефона переказати мою частку — з `chat_id` і `ref`, щоб картка переказу лягла в групу і знайшла рахунок. */
export function sharePayAsk(bill: BillData, due: bigint, chatId: string): SharePayAsk {
  const base = { token: bill.token, amount: fromUnits(due, bill.token), chat_id: chatId, ref: bill.id };
  return bill.payee.kind === 'venue' ? { to: bill.payee.address, ...base } : { peer_id: bill.payee.id, ...base };
}

/** Оплата частки є лише в застосунку на телефоні: гаманець і відбиток — там. */
export const canPayHere = (): boolean => !!phoneBridge();

// ── нагадування: одне на зустріч ───────────────────────────────────────────

const REMINDED_KEY = 'phantom_meetup_reminders';
const REMINDER_REFS_KEY = 'phantom_meetup_reminder_refs';
/** Нагадування, що ставиться зараз (два дотики поспіль), — і запас, коли сховища немає. */
const remindedMemo = new Set<string>();
const reminderRefsMemo = new Map<string, ReminderRef>();

const remindKey = (meetupId: string, at?: number): string => (at === undefined ? meetupId : `${meetupId}@${at}`);

const remindedList = (): string[] => {
  try {
    const raw: unknown = JSON.parse(localStorage.getItem(REMINDED_KEY) || '[]');
    return Array.isArray(raw) ? raw.filter((x): x is string => typeof x === 'string') : [];
  } catch {
    return [];
  }
};

const saveRemindedList = (list: string[]): void => {
  try {
    localStorage.setItem(REMINDED_KEY, JSON.stringify(list.slice(-200)));
  } catch {
    /* без сховища — памʼять сеансу */
  }
};

const readReminderRefs = (): Record<string, ReminderRef> => {
  try {
    const raw: unknown = JSON.parse(localStorage.getItem(REMINDER_REFS_KEY) || '{}');
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return {};
    const out: Record<string, ReminderRef> = {};
    for (const [k, v] of Object.entries(raw as Record<string, unknown>)) {
      const o = obj(v);
      if (o && (o.where === 'phone' || o.where === 'page') && typeof o.id === 'string') {
        out[k] = { where: o.where, id: o.id };
      }
    }
    return out;
  } catch {
    return {};
  }
};

const saveReminderRefs = (refs: Record<string, ReminderRef>): void => {
  try {
    localStorage.setItem(REMINDER_REFS_KEY, JSON.stringify(refs));
  } catch {
    /* без сховища — памʼять сеансу */
  }
};

/** Чи нагадування на цю зустріч уже стоїть (або ставиться зараз). */
export const meetupReminded = (meetupId: string, at?: number): boolean => {
  const key = remindKey(meetupId, at);
  return remindedMemo.has(key) || remindedList().includes(key);
};

/**
 * Одне нагадування за 30 хв до зустрічі — у будильник телефона, або запасом
 * сторінки. Раз на зустріч: `/messenger/reminders` не знає дублів, тож кожен
 * повторний дотик (чи картка, відкрита знову) був би ще одним будильником.
 * Друге прохання на ту саму зустріч — null.
 */
export function remindBefore(meetup: MeetupData, chatId: string, now = Date.now()): Promise<'phone' | 'page'> | null {
  const at = meetup.at - 30 * 60_000;
  const key = remindKey(meetup.id, meetup.at);
  if (at <= now || meetupReminded(meetup.id, meetup.at)) return null;
  remindedMemo.add(key);
  const d = new Date(meetup.at);
  const hhmm = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  return addReminderRef({ at, who: meetup.venue.name, text: tNow('svc.meetup.reminderText', { venue: meetup.venue.name, time: hhmm }), chatId }, now).then(
    (ref) => {
      reminderRefsMemo.set(key, ref);
      saveReminderRefs({ ...readReminderRefs(), [key]: ref });
      try {
        localStorage.setItem(REMINDED_KEY, JSON.stringify([...remindedList().filter((x) => x !== key), key].slice(-200)));
        remindedMemo.delete(key);
      } catch {
        /* без сховища — памʼять сеансу */
      }
      return ref.where;
    },
    (e: unknown) => {
      remindedMemo.delete(key);
      throw e;
    },
  );
}

/** Перенести час зустрічі від імені організатора і переставити нагадування, якщо воно стояло. */
export async function moveMeetup(
  msg: Message,
  me: string,
  meetup: MeetupData,
  oldAt: number,
  newAt: number,
  chatId: string,
  now = Date.now(),
): Promise<string | null> {
  if (newAt <= now) return tNow('meetupOrganiser.past');
  const said = await setCardMark(msg, me, atValue(newAt));
  if (said) return said;
  const oldKey = remindKey(meetup.id, oldAt);
  const refs = readReminderRefs();
  const ref = refs[oldKey] ?? reminderRefsMemo.get(oldKey);
  if (ref) {
    await cancelReminder(ref);
    remindedMemo.delete(oldKey);
    reminderRefsMemo.delete(oldKey);
    saveRemindedList(remindedList().filter((x) => x !== oldKey));
    const nextRefs = { ...refs };
    delete nextRefs[oldKey];
    saveReminderRefs(nextRefs);
    if (newAt - 30 * 60_000 > now) {
      await remindBefore({ ...meetup, at: newAt }, chatId, now)?.catch(() => undefined);
    }
  }
  return null;
}

export const cancelMeetup = (msg: Message, me: string): Promise<string | null> =>
  setCardMark(msg, me, CANCEL_VALUE);

/**
 * Речення про відповідь `/messenger/points/checkin` `{checked_in, awarded, waiting_for}`.
 * Числа балів клієнт не називає — їх знає лише сервер.
 */
export function checkinWords(data: unknown): string {
  const o = obj(data) ?? {};
  const awarded = Array.isArray(o.awarded) ? o.awarded.length : 0;
  const waiting = typeof o.waiting_for === 'number' ? o.waiting_for : null;
  if (awarded > 0) return tNow('svc.meetup.checkin.awarded');
  if (waiting !== null && waiting > 0) return tNow('svc.meetup.checkin.waiting');
  if (waiting === 0) return tNow('svc.meetup.checkin.alreadyCounted');
  return tNow('svc.meetup.checkin.sent');
}

// ── бали: заявка на першу оплачену частку — раз на підпис ───────────────────

const CLAIMED_KEY = 'phantom_points_payment_claims';

const claimed = (): string[] => {
  try {
    const raw: unknown = JSON.parse(localStorage.getItem(CLAIMED_KEY) || '[]');
    return Array.isArray(raw) ? raw.filter((x): x is string => typeof x === 'string') : [];
  } catch {
    return [];
  }
};

const inFlight = new Set<string>();
/** Підписи, вже заявлені за цей сеанс, коли сховища немає. */
const memo = new Set<string>();
/** Коли заявка з цим підписом востаннє не вдалась — щоб не стукати на кожен рендер. */
const failedAt = new Map<string, number>();
const RETRY_AFTER_MS = 5 * 60_000;

/** 2xx вирішує долю заявки, крім 202 `{status:"pending"}` — мережа ще не підтвердила, спитати пізніше. */
const settledClaim = (data: unknown): boolean => obj(data)?.status !== 'pending';

/**
 * Моя частка оплачена мережею → одна заявка на бали з цим підписом. Сервер
 * сам звіряє підпис і дає бали лише раз; тут — лише щоб не питати двічі.
 *
 * Надісланим підпис стає лише після відповіді сервера. Офлайн, старий міст
 * без шляху, 5xx чи «мережа ще не підтвердила» (202 `pending`) — заявка
 * лишається незробленою і повториться на пізнішому рендері картки.
 */
export function claimPaidShareOnce(signature: string, ref: string, now = Date.now()): void {
  if (!phoneBridge() || inFlight.has(signature) || memo.has(signature) || claimed().includes(signature)) return;
  const failed = failedAt.get(signature);
  if (failed !== undefined && now - failed < RETRY_AFTER_MS) return;
  inFlight.add(signature);
  const remember = () => {
    try {
      localStorage.setItem(CLAIMED_KEY, JSON.stringify([...claimed(), signature].slice(-200)));
    } catch {
      /* без сховища — памʼять до закриття сторінки */
      memo.add(signature);
    }
  };
  meetupApi.claimPayment(signature, ref).then(
    (data) => {
      inFlight.delete(signature);
      if (settledClaim(data)) {
        failedAt.delete(signature);
        remember();
      } else failedAt.set(signature, Date.now());
    },
    (e) => {
      inFlight.delete(signature);
      // 422 — остаточна відмова сервера (не ваш підпис, не цей рахунок): повтор нічого не змінить.
      if (statusOf(e) === 422) remember();
      else failedAt.set(signature, Date.now());
    },
  );
}
