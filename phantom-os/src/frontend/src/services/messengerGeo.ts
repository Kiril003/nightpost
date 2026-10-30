/**
 * Точка «я тут» у стрічці: розбір тіла кадру і ЧЕСНИЙ вік.
 *
 * Закон №3 контракту симбіозу: вік точки видимий, і в нього є числа. Свіжою
 * точка лишається 90 секунд від часу ВИМІРУ; далі — приглушений тон і підпис
 * «станом на 14:32». Точка, що приїхала зі скриньки, народжується застарілою
 * завжди: вона з минулого відносно каналу, який щойно був мертвий, — навіть
 * якщо доїхала за секунду після виміру.
 */

import type { GeoPoint, LiveLocation } from '../types/messenger';
import { clockLabel } from '../utils/clockFormat';
import { tNow } from '../components/chat/tNow';

export const FRESH_WINDOW_MS = 90_000;

const finite = (raw: unknown): number | null =>
  typeof raw === 'number' && Number.isFinite(raw) ? raw : null;

/** Тіло кадру → точка. Неповне або несхоже на координати — null, без вигадок. */
export function parseGeoPoint(body?: string | null): GeoPoint | null {
  if (!body) return null;
  let raw: unknown;
  try {
    raw = JSON.parse(body);
  } catch {
    return null;
  }
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;

  const row = raw as Record<string, unknown>;
  const lat = finite(row.lat);
  const lon = finite(row.lon);
  const at = finite(row.at);
  if (lat === null || lon === null || at === null) return null;
  if (lat < -90 || lat > 90 || lon < -180 || lon > 180 || at <= 0) return null;

  const acc = finite(row.acc);
  const label = typeof row.label === 'string' ? row.label.trim() : '';
  return {
    lat,
    lon,
    atMs: Math.round(at),
    ...(acc !== null && acc >= 0 ? { accuracyM: acc } : {}),
    ...(label ? { label: label.slice(0, 120) } : {}),
  };
}

/** Тіло для відправки. Імена полів на дроті — як у контракті. */
export function geoPointBody(point: GeoPoint): string {
  return JSON.stringify({
    lat: point.lat,
    lon: point.lon,
    at: point.atMs,
    ...(point.accuracyM !== undefined ? { acc: point.accuracyM } : {}),
    ...(point.label ? { label: point.label } : {}),
  });
}

export function liveLocationBody(session: LiveLocation): string {
  return JSON.stringify({
    session_id: session.sessionId,
    point: JSON.parse(geoPointBody(session.point)),
    started_at: session.startedAtMs,
    expires_at: session.expiresAtMs,
    revoked: Boolean(session.revoked),
  });
}

export function parseLiveLocation(body?: string | null): LiveLocation | null {
  if (!body) return null;
  try {
    const raw = JSON.parse(body) as Record<string, unknown>;
    const point = parseGeoPoint(JSON.stringify(raw.point));
    const sessionId = typeof raw.session_id === 'string' ? raw.session_id : '';
    const startedAtMs = finite(raw.started_at);
    const expiresAtMs = finite(raw.expires_at);
    if (!point || !sessionId || startedAtMs === null || expiresAtMs === null || expiresAtMs <= startedAtMs) return null;
    return { sessionId, point, startedAtMs, expiresAtMs, ...(raw.revoked === true ? { revoked: true } : {}) };
  } catch {
    return null;
  }
}

export function liveLocationActive(location: LiveLocation, nowMs = Date.now()): boolean {
  return !location.revoked && nowMs < location.expiresAtMs;
}

/**
 * Одна сесія — одна бульбашка. Кожне оновлення положення їде окремим листом
 * (так воно переживає вимкнений вузол і повтори), але людині показується лише
 * останнє: без цього година live-гео давала до 360 карток у стрічці.
 */
export function collapseLiveLocations<T extends { type: string; liveLocation?: LiveLocation }>(messages: T[]): T[] {
  const first = new Map<string, number>();
  const latest = new Map<string, LiveLocation>();
  messages.forEach((m, i) => {
    if (m.type !== 'geo:live' || !m.liveLocation) return;
    if (!first.has(m.liveLocation.sessionId)) first.set(m.liveLocation.sessionId, i);
    latest.set(m.liveLocation.sessionId, m.liveLocation);
  });
  if (first.size === 0) return messages;
  const out: T[] = [];
  messages.forEach((m, i) => {
    if (m.type !== 'geo:live' || !m.liveLocation) out.push(m);
    else if (first.get(m.liveLocation.sessionId) === i) out.push({ ...m, liveLocation: latest.get(m.liveLocation.sessionId) });
  });
  return out;
}

export interface GeoAgeInput {
  nowMs?: number;
  /** Кадр приїхав дорогою «скринька» — див. transport рядка з вузла. */
  viaMailbox?: boolean;
}

export function isStale(point: GeoPoint, { nowMs, viaMailbox }: GeoAgeInput = {}): boolean {
  if (viaMailbox) return true;
  return (nowMs ?? Date.now()) - point.atMs > FRESH_WINDOW_MS;
}

const clock = (atMs: number): string => clockLabel(new Date(atMs));

/** Підпис віку: «щойно · 14:32» або «станом на 14:32». Час — завжди виміру. */
export function ageLabel(point: GeoPoint, input: GeoAgeInput = {}): string {
  return isStale(point, input)
    ? tNow('geo.asOf', { time: clock(point.atMs) })
    : tNow('geo.justNow', { time: clock(point.atMs) });
}

export const coordsLabel = (point: GeoPoint): string =>
  `${point.lat.toFixed(5)}, ${point.lon.toFixed(5)}`;

/**
 * Куди веде «Відкрити на мапі». Мапу малює ATLAS — тут лише передача точки
 * параметрами, без жодного зовнішнього запиту по її координатах (закон №1).
 */
export function mapHref(point: GeoPoint): string {
  const params = new URLSearchParams({
    lat: String(point.lat),
    lon: String(point.lon),
    at: String(point.atMs),
  });
  if (point.label) params.set('label', point.label);
  return `/map?${params.toString()}`;
}
