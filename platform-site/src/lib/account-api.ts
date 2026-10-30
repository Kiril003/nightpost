import { LICENSE_SERVER } from './meta';

const TOKEN_KEY = 'phantom-session';

export type Role = 'user' | 'moderator' | 'admin' | 'owner';

export const RANK: Record<Role, number> = { user: 0, moderator: 10, admin: 20, owner: 30 };

export function atLeast(role: Role, needed: Role): boolean {
  return RANK[role] >= RANK[needed];
}

export interface Account {
  id: string;
  pub_key?: string;
  email: string;
  display_name: string;
  locale: string;
  role: Role;
  created_at: string;
  unread: number;
}

export interface QrStartResponse {
  challenge_id: string;
  poll_token: string;
  qr_payload: string;
  expires_at: number;
  browser?: string;
  country?: string;
}

export interface QrPollResponse {
  status: 'pending' | 'authenticated' | 'linked';
  token?: string;
  session_id?: string;
  account?: Account;
  scope?: 'erase_cancel';
  due_at?: number;
}

export interface AuthVerifyResponse {
  token: string;
  account?: Account;
  scope?: 'erase_cancel';
  due_at?: number;
}

export interface SessionItem {
  id: string;
  created_at: string;
  expires_at: string;
  ip: string | null;
  via?: string;
  label?: string;
  current: boolean;
}

export interface Device {
  fingerprint: string;
  name: string;
  activated_at: string;
  /**
   * Остання УСПІШНА підписана звірка пристрою (`/api/validate`). `null` —
   * пристрій ще жодного разу не звірявся після активації; кабінет так і
   * каже, а не вигадує «щойно».
   */
  last_seen_at?: string | null;
}

/** Звідки ліцензія: сайт (Lemon Squeezy), Google Play, бета-доступ, видано вручну. */
export type Provider = 'lemonsqueezy' | 'play' | 'beta' | 'manual';

export interface License {
  id: string;
  tier: string;
  email: string;
  updates_until: string | null;
  revoked: boolean;
  /** Машинна причина відкликання (`refunded`, `play_canceled`…) або вільний текст. */
  revoked_reason?: string | null;
  /** Немає у старих відповідей сервера — тоді кабінет не вгадує джерело. */
  provider?: Provider | string | null;
  max_activations: number;
  created_at: string;
  devices: Device[];
  /** Рахує сервер: лише він зводить регістр і googlemail в одну скриньку. */
  can_reissue?: boolean;
}

/** Що саме ляже на диск: людина має знати це до натискання, а не після. */
export interface Build {
  platform: string;
  filename: string;
  size: number | null;
  /** З підписаної політики, і лише коли оголошене ім'я збігається з наявним. */
  version?: string;
  build?: number;
  sha256?: string;
  /**
   * ISO-8601 у UTC: коли САМЕ ЦІ байти лягли у сховище.
   *
   * Приходить не з політики, а з самого об'єкта (`Last-Modified`), і в цьому
   * весь сенс: політику перевидає кожне відкликання й кожне повернення
   * грошей, тож будь-яка дата, взята звідти, ходила б за чужими подіями.
   * `null` — сервер не дізнався; тоді картка так і каже.
   */
  published_at?: string | null;
}

export interface Me {
  account: Account;
  licenses: License[];
  staff_unread: number;
  downloads: Build[];
  /** 'licence' — лише власникам ключа; 'account' — кожному, хто ввійшов. */
  download_policy?: 'open' | 'licence' | 'account';
}

export interface Thread {
  id: string;
  subject: string;
  status: string;
  assigned_to: string | null;
  created_at: string;
  updated_at: string;
  author?: string;
  unanswered?: boolean;
  /** Остання репліка й чий вона — щоб не відкривати кожну гілку по черзі. */
  preview?: string;
  last_staff?: boolean;
}

/** Сесія входу в кабінет (`GET /api/me/sessions`). */
export interface Session {
  created_at: string;
  expires_at: string;
  ip: string | null;
  current: boolean;
}

export interface Message {
  id: string;
  staff: boolean;
  body: string;
  created_at: string;
}

export interface StaffAccount {
  id: string;
  email: string;
  display_name: string;
  role: Role;
  disabled: boolean;
  created_at: string;
  last_seen_at: string | null;
}

export interface AuditEvent {
  ts: string;
  kind: string;
  license_id: string | null;
  ip: string | null;
  detail: string;
}

export interface ModerationClaim {
  id: string;
  venue_id: string;
  venue_name: string;
  osm_type: string;
  osm_id: number;
  osm_name: string;
  osm_url: string;
  distance_m: number;
  evidence: string;
  owner: string;
  status: string;
  created_ms: number;
}

export interface MapNote {
  id: number;
  cell: string;
  kind: string;
  note: string;
  hour_ms: number;
  hidden: boolean;
}

export interface ModerationVenue {
  id: string;
  name: string;
  area: string | null;
  rating: { mean: number; count: number; sum: number } | null;
  hidden: boolean;
}

export interface StaffModeration {
  claims: ModerationClaim[];
  map_notes: MapNote[];
  venues?: ModerationVenue[];
}

export interface ErasureRow {
  subject: string;
  kind: 'root' | 'account';
  requested_at: number;
  due_at: number;
  cancelled_at: number | null;
  status: 'waiting' | 'overdue' | 'cancelled' | 'done';
}

export interface StaffErasures {
  grace_days: number;
  rows: ErasureRow[];
}

export interface StaffMoney {
  cluster: 'devnet' | 'mainnet-beta';
  escrow: boolean;
  bills: null;
  payment_proofs: { devnet: number; mainnet: number };
  hook: {
    webhook: { devnet: boolean; mainnet: boolean };
    auth: boolean;
    watched: { devnet: number; mainnet: number };
    last_event_age_s: number | null;
    hook_ok: number;
    hook_503: number;
    watch_503: number;
  };
}

export interface AskRow {
  day: string;
  profile: string;
  model: string;
  upstream: string;
  asks: number;
  refused: number;
  failed: number;
}

export interface StaffAssistant {
  upstream: 'vertex' | 'gemini' | 'none';
  days: AskRow[];
  fallback: { day: string; vertex_failed: number }[];
  today: { devices: number; asks: number };
}

export interface StaffSystem {
  ok: boolean;
  relay: boolean;
  started_at: string;
  uptime_s: number;
  image: string | null;
  region: string | null;
  sha: string | null;
}

export interface StaffRoles {
  counts: Record<Role, number>;
  staff: { email: string; role: Role; disabled: boolean; last_seen_at: string | null }[];
}

/** status === 0 — сервер не відповів узагалі; це інша розмова з людиною, ніж відмова.
 *  reason — машинне слово з тіла відмови (`{"reason": …}`), коли сервер його дав:
 *  один код 409 інакше не відрізняє «у вас уже є ключ» від «уже видавали». */
export class ApiError extends Error {
  constructor(
    public status: number,
    public reason?: string,
  ) {
    super(`api ${status}`);
  }
}

async function reasonOf(res: Response): Promise<string | undefined> {
  try {
    const body: unknown = await res.json();
    const reason = (body as { reason?: unknown } | null)?.reason;
    return typeof reason === 'string' ? reason : undefined;
  } catch {
    return undefined;
  }
}

export function readToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function writeToken(token: string | null) {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token);
    else localStorage.removeItem(TOKEN_KEY);
  } catch {
    /* приватний режим: сесія проживе рівно цю вкладку */
  }
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = readToken();
  let res: Response;
  try {
    res = await fetch(`${LICENSE_SERVER}${path}`, {
      ...init,
      headers: {
        ...(init.body ? { 'Content-Type': 'application/json' } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(init.headers ?? {}),
      },
    });
  } catch {
    throw new ApiError(0);
  }
  if (res.status === 401) {
    // Сесія померла на сервері — тримати мертвий токен означає показувати
    // кабінет, який на кожен запит відповідає відмовою.
    writeToken(null);
    throw new ApiError(401);
  }
  if (!res.ok) throw new ApiError(res.status, await reasonOf(res));
  return res.status === 204 ? (undefined as T) : ((await res.json()) as T);
}

export const post = <T,>(path: string, body?: unknown) =>
  api<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) });

export const patch = <T,>(path: string, body: unknown) =>
  api<T>(path, { method: 'PATCH', body: JSON.stringify(body) });

export const startQrAuth = () => post<QrStartResponse>('/api/auth/qr/start');

export const startEraseQr = () => post<QrStartResponse>('/api/auth/qr/start', { purpose: 'erase' });

/** Signed-in cabinet asks a phone to read this account's page requests; the phone does not sign in. */
export const startLinkQr = () => post<QrStartResponse>('/api/auth/qr/start', { purpose: 'link' });

export const eraseMe = () => post<{ pending: boolean; due_at: number }>('/api/me/erase');

export const cancelErase = () => post<{ cancelled: boolean }>('/api/me/erase/cancel');

export const pollQrAuth = (challenge_id: string, poll_token: string) =>
  post<QrPollResponse>('/api/auth/qr/poll', { challenge_id, poll_token });

export const revokeSession = (id: string) =>
  post<{ ok: boolean; dropped: number }>(`/api/me/sessions/${encodeURIComponent(id)}/revoke`);
