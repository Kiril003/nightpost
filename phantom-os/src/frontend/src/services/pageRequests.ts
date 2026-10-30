import { request } from './api';

export type Amount = { amount: string; token: string };

/** Замовлення з каталогу сторінки: назви, ціни й суми порахував сервер за сторінкою, не відвідувач. */
export type PageOrder = {
  lines: { id: string; name: string; qty: number; price: string }[];
  subtotal: string;
  fee: string;
  total: string;
  token: string;
  fulfil: 'pickup' | 'delivery';
  zone: string | null;
  address: string | null;
};

/** Запис на курс: курс, ціну й наступне заняття сервер узяв зі сторінки, не з відвідувача. */
export type PageEnrol = {
  course: string;
  price: Amount | null;
  sessions: number;
  next: { name: string; date: string; time: string } | null;
};

/** Запис, замовлення чи повідомлення зі сторінки власника (/p/:handle) — телефон забирає їх підписом ключа апарата. */
export type PageRequest = {
  id: string;
  venueId: string;
  venueName: string;
  kind: 'book' | 'message' | 'order' | 'enroll';
  service: string | null;
  atMs: number | null;
  atLocal: string | null;
  text: string;
  name: string;
  contact: string;
  createdMs: number;
  seen: boolean;
  staffName: string | null;
  deposit: Amount | null;
  order: PageOrder | null;
  enroll: PageEnrol | null;
};

const str = (v: unknown): string => (typeof v === 'string' ? v : '');
const num = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : null);
const AMOUNT = /^\d{1,12}(?:\.\d{1,9})?$/;
const isAmount = (v: unknown): v is string => typeof v === 'string' && AMOUNT.test(v);

function amountOf(v: unknown): Amount | null {
  const o = (v ?? {}) as Record<string, unknown>;
  return isAmount(o.amount) && str(o.token) ? { amount: o.amount, token: str(o.token) } : null;
}

function orderOf(v: unknown): PageOrder | null {
  const o = (v ?? {}) as Record<string, unknown>;
  if (!Array.isArray(o.lines) || !o.lines.length || ![o.subtotal, o.fee, o.total].every(isAmount) || !str(o.token)) return null;
  const lines: PageOrder['lines'] = [];
  for (const l of o.lines) {
    const x = (l ?? {}) as Record<string, unknown>;
    const qty = num(x.qty);
    if (!str(x.name) || qty === null || qty < 1 || !isAmount(x.price)) return null;
    lines.push({ id: str(x.id), name: str(x.name), qty, price: x.price });
  }
  const fulfil = o.fulfil === 'pickup' || o.fulfil === 'delivery' ? o.fulfil : null;
  if (!fulfil) return null;
  return {
    lines,
    subtotal: o.subtotal as string,
    fee: o.fee as string,
    total: o.total as string,
    token: str(o.token),
    fulfil,
    zone: str(o.zone) || null,
    address: str(o.address) || null,
  };
}

function enrolOf(v: unknown): PageEnrol | null {
  const o = (v ?? {}) as Record<string, unknown>;
  const sessions = num(o.sessions);
  if (!str(o.course) || sessions === null || sessions < 1) return null;
  const n = (o.next ?? {}) as Record<string, unknown>;
  const next = /^\d{4}-\d{2}-\d{2}$/.test(str(n.date)) ? { name: str(n.name), date: str(n.date), time: str(n.time) } : null;
  return { course: str(o.course), price: amountOf({ amount: o.price, token: o.token }), sessions, next };
}

export function pageRequestsOf(raw: unknown): PageRequest[] {
  const o = (raw ?? {}) as { venues?: unknown; requests?: unknown };
  const names = new Map<string, string>();
  for (const v of Array.isArray(o.venues) ? o.venues : []) {
    const id = str((v as { id?: unknown })?.id);
    if (id) names.set(id, str((v as { name?: unknown }).name));
  }
  const out: PageRequest[] = [];
  for (const r of Array.isArray(o.requests) ? o.requests : []) {
    const x = (r ?? {}) as Record<string, unknown>;
    const id = str(x.id);
    const kind = x.kind === 'book' || x.kind === 'message' || x.kind === 'order' || x.kind === 'enroll' ? x.kind : null;
    if (!/^[0-9a-f]{16}$/.test(id) || !kind) continue;
    const order = kind === 'order' ? orderOf(x.order) : null;
    if (kind === 'order' && !order) continue;
    const enroll = kind === 'enroll' ? enrolOf(x.enroll) : null;
    if (kind === 'enroll' && !enroll) continue;
    const venueId = str(x.venue_id);
    out.push({
      id,
      venueId,
      venueName: names.get(venueId) ?? '',
      kind,
      service: str(x.service) || null,
      atMs: num(x.at_ms),
      atLocal: str(x.at_local) || null,
      text: str(x.text),
      name: str(x.name),
      contact: str(x.contact),
      createdMs: num(x.created_ms) ?? 0,
      seen: num(x.picked_ms) !== null,
      staffName: str(x.staff_name) || null,
      deposit: amountOf(x.deposit),
      order,
      enroll,
    });
  }
  return out.sort((a, b) => b.createdMs - a.createdMs);
}

/** Прочитати запити; `ack` — ті, що вже показано власнику (сервер позначає їх показаними). */
export async function pullPageRequests(ack: string[] = []): Promise<PageRequest[]> {
  const r = ack.length
    ? await request<unknown>('POST', '/messenger/venues/requests', { ack: ack.slice(0, 200) })
    : await request<unknown>('GET', '/messenger/venues/requests');
  return pageRequestsOf(r);
}
