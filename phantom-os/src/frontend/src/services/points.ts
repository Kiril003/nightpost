/**
 * Власні бали у вебі — те саме, що рідний `PointsPanel` (ProfilePoints.kt):
 * читаємо `/messenger/points/me` (міст підписує ключем апарата) і показуємо.
 * Число балів знає лише сервер; веб його не рахує й не домальовує.
 *
 * Стани як у рідного `PointsLoad`: сервера чи ключа немає — розділу немає;
 * сервер не відповів — «немає звʼязку», а не «0»; балів ще немає — порожньо.
 */
import { useEffect, useState } from 'react';
import type { MessageKey } from '../i18n';
import { meetupApi } from './meetupApi';
import { isPhoneShell } from '../components/messenger/phoneShell';

export interface PointsEvent {
  kind: string;
  points: number;
  atMs: number;
}

export interface PointsView {
  total: number;
  events: PointsEvent[];
}

export type PointsLoad =
  | { kind: 'loading' }
  | { kind: 'shown'; view: PointsView }
  | { kind: 'offline' }
  | { kind: 'hidden' };

const whole = (v: unknown): number | null => (typeof v === 'number' && Number.isInteger(v) ? v : null);

/** Відповідь сервера → вигляд, найновіші зверху; `null`, якщо це не те, що він обіцяв. */
export function parsePoints(data: unknown): PointsView | null {
  if (!data || typeof data !== 'object' || Array.isArray(data)) return null;
  const o = data as Record<string, unknown>;
  const total = whole(o.total);
  if (total === null || !Array.isArray(o.events)) return null;
  const events: PointsEvent[] = [];
  for (const e of o.events) {
    if (!e || typeof e !== 'object') return null;
    const r = e as Record<string, unknown>;
    const points = whole(r.points);
    const atMs = whole(r.at);
    if (typeof r.kind !== 'string' || points === null || atMs === null) return null;
    events.push({ kind: r.kind, points, atMs });
  }
  return { total, events: events.reverse() };
}

/** Коди мосту, після яких балів у цій збірці просто немає (не «помилка мережі»). */
const HIDDEN_CODES = new Set(['not_enabled', 'no_key', 'bad_body', 'not_found']);

export function pointsLoadOfError(e: unknown): PointsLoad {
  const err = e as { status?: unknown; code?: unknown } | null;
  const code = typeof err?.code === 'string' ? err.code : '';
  if (HIDDEN_CODES.has(code) || err?.status === 404) return { kind: 'hidden' };
  return { kind: 'offline' };
}

export async function loadPoints(read: () => Promise<unknown> = meetupApi.pointsMe): Promise<PointsLoad> {
  if (!isPhoneShell()) return { kind: 'hidden' };
  try {
    const view = parsePoints(await read());
    return view ? { kind: 'shown', view } : { kind: 'offline' };
  } catch (e) {
    return pointsLoadOfError(e);
  }
}

export function usePoints(): PointsLoad {
  const [load, setLoad] = useState<PointsLoad>(() => (isPhoneShell() ? { kind: 'loading' } : { kind: 'hidden' }));
  useEffect(() => {
    let live = true;
    void loadPoints().then((l) => {
      if (live) setLoad(l);
    });
    return () => {
      live = false;
    };
  }, []);
  return load;
}

export const pointsKindKey = (kind: string): MessageKey =>
  kind === 'meetup.happened'
    ? 'points.kind.meetup'
    : kind === 'payment.first'
      ? 'points.kind.payment'
      : kind === 'invite.joined'
        ? 'points.kind.invite'
        : 'points.kind.other';
