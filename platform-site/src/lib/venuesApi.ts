import { BUILDER_SERVER } from './builderServer';
import { LICENSE_SERVER as ACCOUNT_SERVER } from './meta';
import { readToken, writeToken } from './account-api';
import type { VenuePage } from './venueBlocks';
import type { AiResult } from './creator/aiDraft';

/*
 * Двері сервера закладів (`platform-site/server/venues.py`, гілка S1 build 18).
 *
 * Контракт:
 *   POST /venues {name}                      → 201 Venue      (кабінет; без входу 401)
 *   GET  /venues/mine                        → {venues: Venue[]}
 *   GET  /venues/{id}                        → Venue          (чернетку бачить лише власник)
 *   PUT  /venues/{id}/page {v, blocks, published} → Venue     (чужий 403, невідомий 404)
 *   422  → {refused, at, message_uk, message_en}
 *
 * Свій виклик, а не `api()` кабінету: відмова сервера закладів несе причину в
 * `refused` і готові людські речення, а кабінетний виклик читає лише `reason`.
 * Сесія та сама — токен кабінету, бо власник закладу = обліковий запис кабінету.
 */

export interface VenueRating {
  mean: number;
  count: number;
}

export interface VenueLocation {
  lat: number;
  lon: number;
  address: string;
  /** H3 r8 — рахує сервер з lat/lon, не сайт. */
  cell: string;
  /** H3 r6 — область, якою телефон питає «поруч». */
  area: string;
}

export interface Venue {
  id: string;
  name: string;
  published: boolean;
  /** Росте з кожним збереженням сторінки; 0 — сторінки ще не було. */
  rev: number;
  page: VenuePage | null;
  /** `null` — ще ніхто не оцінив. Не нуль і не «новий заклад». */
  rating: VenueRating | null;
  /** Монета меню, якщо меню є. */
  token?: 'USDC' | 'SOL' | null;
  updated_ms?: number | null;
  menu_version?: string | null;
  location?: VenueLocation | null;
  wallet?: string | null;
  /** Лише власникові: 2 — сторінку веде редактор v2, 1 — старий редактор. */
  editor?: 1 | 2;
  /** Лише власникові: запланована публікація чернетки. */
  schedule?: Schedule | null;
}

export type Schedule = { at_ms: number; rev: number };
export type Follow = { following: boolean; followers: number };

/** Сервер відмовив сторінці: машинна причина, де саме і як сказати людині. */
export class VenueRefusal extends Error {
  constructor(
    public status: number,
    public refused: string | null,
    public at: string | null,
    public messageUk: string | null,
    public messageEn: string | null,
  ) {
    super(`venues ${status}${refused ? ` ${refused}` : ''}`);
  }
}

/**
 * Сервер, до якого ходить сайт, ще не знає закладів (`/venues` — 404 від самого
 * роутера). Це інша розмова з людиною, ніж «не знайдено ваш заклад»: двері ще
 * не відчинено, і сторінка так і каже.
 */
export class VenuesUnavailable extends Error {
  constructor() {
    super('venues not served');
  }
}

async function readJson(res: Response): Promise<Record<string, unknown> | null> {
  try {
    const body: unknown = await res.json();
    return body && typeof body === 'object' ? (body as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

const str = (v: unknown): string | null => (typeof v === 'string' ? v : null);

/** The account lives on LICENSE_SERVER; a Builder-server 401 for a session the account server still honours is a server gap, not a sign-out. */
export async function accountAlive(token: string): Promise<boolean> {
  try {
    const r = await fetch(`${ACCOUNT_SERVER}/api/me`, { headers: { Authorization: `Bearer ${token}` } });
    return r.status !== 401 && r.status !== 403;
  } catch {
    return true;
  }
}

async function call<T>(path: string, init: RequestInit = {}, { collection = false } = {}): Promise<T> {
  const token = readToken();
  let res: Response;
  try {
    res = await fetch(`${BUILDER_SERVER}${path}`, {
      ...init,
      headers: {
        ...(init.body ? { 'Content-Type': 'application/json' } : {}),
        ...(init.headers as Record<string, string> | undefined),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
    });
  } catch {
    throw new VenueRefusal(0, null, null, null, null);
  }
  if (res.ok) return (res.status === 204 ? undefined : await res.json()) as T;
  const body = await readJson(res);
  if (res.status === 401 && token) {
    if (!(await accountAlive(token))) writeToken(null);
    else throw new VenueRefusal(0, null, null, null, null);
  }
  // FastAPI на невідомий шлях відповідає `{"detail": "Not Found"}`; сервер
  // закладів на «немає такого закладу» — своєю причиною. Лише перше — «дверей немає».
  if (collection && res.status === 404 && body?.detail === 'Not Found') throw new VenuesUnavailable();
  throw new VenueRefusal(
    res.status,
    str(body?.refused) ?? str(body?.reason),
    str(body?.at),
    str(body?.message_uk),
    str(body?.message_en),
  );
}

export function myVenues(): Promise<Venue[]> {
  return call<{ venues: Venue[] }>('/venues/mine', {}, { collection: true }).then((r) => r.venues);
}

export function createVenue(name: string): Promise<Venue> {
  return call<Venue>('/venues', { method: 'POST', body: JSON.stringify({ name }) }, { collection: true });
}

export function getVenue(id: string): Promise<Venue> {
  return call<Venue>(`/venues/${encodeURIComponent(id)}`);
}

/** Зберегти сторінку. `published` шлемо завжди явно: мовчання не має знімати заклад з публікації. */
export function savePage(id: string, page: VenuePage, published: boolean): Promise<Venue> {
  return call<Venue>(`/venues/${encodeURIComponent(id)}/page`, {
    method: 'PUT',
    body: JSON.stringify({ ...page, published }),
  });
}

/** Лише чернетку: опублікований заклад сервер не стирає (`published`). */
export function deleteVenue(id: string): Promise<void> {
  return call<void>(`/venues/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

export function publishVenue(id: string, page: VenuePage): Promise<Venue> {
  return savePage(id, page, true);
}

/** Опублікована сторінка у формі v2 (з `look`), як її бачить відвідувач. */
export function getPublishedPage(id: string): Promise<Venue & { page: Record<string, unknown> | null }> {
  return call(`/venues/${encodeURIComponent(id)}?page=2`);
}

/* Редактор v2: чернетка на сервері, версії, зображення (`server/venue_editor.py`). */

export type V2Page = Record<string, unknown> & { v: 2; blocks: unknown[] };
export type Dropped = { at_ms: number; dropped_ms: number; reason: string; seen: boolean };
export type Draft = { page: V2Page | null; updated_ms: number | null; schedule: Schedule | null; dropped?: Dropped[] };
export type VersionRow = { n: number; at_ms: number; look: string | null; blocks: number };

const v = (id: string) => `/venues/${encodeURIComponent(id)}`;

export function getDraft(id: string): Promise<Draft> {
  return call<Draft>(`${v(id)}/draft`);
}

/** `base` — коли ця вкладка востаннє бачила чернетку; інша вкладка чи пристрій між тим дасть 409 `stale_draft`. */
export function saveDraft(id: string, page: V2Page, base: number | null, keepalive = false): Promise<Draft> {
  return call<Draft>(`${v(id)}/draft?base=${base ?? 0}`, { method: 'PUT', body: JSON.stringify(page), keepalive });
}

export function publishV2(id: string, page: V2Page, rev: number): Promise<Venue> {
  return call<Venue>(`${v(id)}/page?rev=${rev}`, { method: 'PUT', body: JSON.stringify({ ...page, published: true }) });
}

export function listVersions(id: string): Promise<{ versions: VersionRow[]; dropped?: Dropped[] }> {
  return call(`${v(id)}/versions`);
}

/** Same-origin on purpose: the HttpOnly media cookie is SameSite=Strict and scoped to /venues/{id}/media. */
export async function openMediaSession(id: string): Promise<boolean> {
  const token = readToken();
  try {
    const r = await fetch(`/venues/${encodeURIComponent(id)}/media-session`, {
      method: 'POST',
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      credentials: 'same-origin',
    });
    return r.ok;
  } catch {
    return false;
  }
}

export function markDropsSeen(id: string): Promise<{ ok: true }> {
  return call(`${v(id)}/schedule/dropped/seen`, { method: 'POST' });
}

export function getVersion(id: string, n: number): Promise<{ n: number; at_ms: number; page: V2Page }> {
  return call(`${v(id)}/versions/${n}`);
}

/** Байти йдуть на наш сервер, а далі — у сховище; відмова (тип, вага, ліміт) — `VenueRefusal`. */
export function uploadVenueImage(venueId: string, file: Blob): Promise<{ blob: string; w?: number; h?: number }> {
  return call<{ blob: string; src: string }>(`${v(venueId)}/media`, {
    method: 'POST',
    body: file,
    headers: { 'Content-Type': file.type },
  }).then(({ blob }) => ({ blob }));
}

/** `rev` — `updated_ms` чернетки, яку бачить редактор; публікується саме вона, навіть якщо чернетку правили після. */
export function schedulePublish(id: string, atMs: number, rev: number): Promise<{ schedule: Schedule }> {
  return call(`${v(id)}/schedule`, { method: 'POST', body: JSON.stringify({ at_ms: atMs, rev }) });
}

export function cancelSchedule(id: string): Promise<{ schedule: null }> {
  return call(`${v(id)}/schedule`, { method: 'DELETE' });
}

/** Знімає сторінку з усіх публічних місць; чернетка й версії лишаються. Скасовує й заплановану публікацію. */
export function unpublishVenue(id: string): Promise<Venue> {
  return call<Venue>(`${v(id)}/unpublish`, { method: 'POST' });
}

export function followVenue(id: string, on: boolean): Promise<Follow> {
  return call<Follow>(`${v(id)}/follow`, { method: on ? 'POST' : 'DELETE' });
}

export function getFollow(id: string): Promise<Follow> {
  return call<Follow>(`${v(id)}/follow`);
}

export function getAudience(id: string): Promise<{ followers: number }> {
  return call(`${v(id)}/audience`);
}

export type Enrolment = { course: string; price: string | null; token: string; sessions: number; next: { name: string; date: string; time: string } };

export type PageRequest = {
  block: string;
  service?: string;
  at_ms?: number;
  at_local?: string;
  preferred?: boolean;
  staff?: string;
  items?: { id: string; qty: number }[];
  fulfil?: 'pickup' | 'delivery';
  zone?: string;
  address?: string;
  text?: string;
  name: string;
  contact: string;
};

export type PlacedOrder = {
  lines: { id: string; name: string; qty: number; price: string }[];
  subtotal: string;
  fee: string;
  total: string;
  token: string;
  fulfil: 'pickup' | 'delivery';
  zone: string | null;
  address: string | null;
};

/** A visitor's booking, order or message from /p/:id; the owner reads it in the app. */
export function sendPageRequest(
  venueId: string,
  body: PageRequest,
): Promise<{ id: string; at_ms: number | null; deposit?: { amount: string; token: string } | null; order?: PlacedOrder | null; enroll?: Enrolment | null }> {
  return call(`/venues/${encodeURIComponent(venueId)}/requests`, { method: 'POST', body: JSON.stringify(body) });
}

export function takenTimes(venueId: string, block: string): Promise<{ service: string | null; staff?: string | null; at_ms: number; n: number }[]> {
  return call<{ taken: { service: string | null; staff?: string | null; at_ms: number; n: number }[] }>(
    `/venues/${encodeURIComponent(venueId)}/requests/taken?block=${encodeURIComponent(block)}`,
  ).then((r) => r.taken);
}

export function stockLeft(venueId: string, block: string): Promise<Record<string, number>> {
  return call<{ left: Record<string, number> }>(
    `/venues/${encodeURIComponent(venueId)}/requests/stock?block=${encodeURIComponent(block)}`,
  ).then((r) => r.left);
}

export function seatsLeft(venueId: string, block: string): Promise<number | null> {
  return call<{ left: { seats?: number } }>(
    `/venues/${encodeURIComponent(venueId)}/requests/stock?block=${encodeURIComponent(block)}`,
  ).then((r) => (typeof r.left.seats === 'number' ? r.left.seats : null));
}

/** K1: the server reads the description with today's model for the `act` profile; the client never names a model. */
export function aiDraft(body: { kind: string; lang: string; text: string; table?: { name: string; b64: string }; photos: string[] }): Promise<AiResult> {
  return call<AiResult>('/venues/ai-draft', { method: 'POST', body: JSON.stringify(body) });
}
