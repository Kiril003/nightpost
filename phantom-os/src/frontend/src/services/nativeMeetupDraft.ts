/**
 * Помічник телефона просить відкрити чернетку зустрічі чи рахунок —
 * `window.__phantomOpenMeetupDraft(json)`, тією ж дорогою, що
 * `__phantomOpenChat` (`nativeOpenChat.ts`).
 *
 * Помічник нічого не надсилає сам: він кладе в чернетку те, що почув
 * («збери Олену й Петра в Cafe X о 19:00»), а «Запросити» натискає людина
 * (`docs/scene/MEETUP_CONTRACT.md` §6).
 *
 * JSON: `{ people?: string[], place?: string, at?: string|number }` — чернетка
 * зустрічі; `{ split: 'claimed'|'equal' }` — «розділи рахунок»: аркуш
 * найновішого рахунку відкритої розмови, а без рахунку — складання рахунку з
 * найновішої зустрічі. Повертає, чи є що відкрити.
 */
import { useEffect, useState } from 'react';
import { useMessengerStore } from '../stores/messengerStore';
import { useUIStore } from '../stores/uiStore';
import { BILL_KIND, MEETUP_KIND, pinVenue, type VenueSnap } from '../types/meetup';
import type { Chat, Message } from '../types/messenger';
import { tNow } from '../components/chat/tNow';

/** Чернетка, відкладена на час, поки людина ставить мітку на мапі. */
export interface ParkedDraft {
  picked: string[];
  at: number | null;
  query: string;
  intoHere: boolean;
  chatId: string | null;
}

/** Скільки припаркована чернетка чекає мітку. */
export const PARK_MS = 30 * 60_000;

let parked: (ParkedDraft & { savedAt: number }) | null = null;

export function parkMeetupDraft(d: ParkedDraft | null): void {
  parked = d ? { ...d, savedAt: Date.now() } : null;
}

function takeParked(): ParkedDraft | null {
  const p = parked;
  parked = null;
  if (!p || Date.now() - p.savedAt > PARK_MS) return null;
  const { savedAt: _, ...d } = p;
  return d;
}

const VENUE_ID = /^[0-9a-f]{16}$/;

/** `{pin:{lat,lon,label?,venue?}}` з мапи, або null. */
function pinOf(x: unknown): { lat: number; lon: number; label: string; venue: string | null } | null {
  const pin = x && typeof x === 'object' ? (x as Record<string, unknown>).pin : null;
  if (!pin || typeof pin !== 'object') return null;
  const { lat, lon, label, venue } = pin as Record<string, unknown>;
  if (typeof lat !== 'number' || typeof lon !== 'number' || !Number.isFinite(lat) || !Number.isFinite(lon)) return null;
  if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
  return {
    lat,
    lon,
    label: typeof label === 'string' ? label.trim() : '',
    venue: typeof venue === 'string' && VENUE_ID.test(venue) ? venue : null,
  };
}

export type MeetupAsk =
  | { kind: 'meetup'; args?: unknown; venue?: VenueSnap | null; venueId?: string | null; chatId?: string | null; parked?: ParkedDraft | null }
  | { kind: 'bill'; messageId: string }
  | { kind: 'bill-draft'; messageId: string };

/** Прохання і його номер: кожне нове прохання — новий номер, навіть з тими самими словами. */
interface Asked {
  ask: MeetupAsk | null;
  n: number;
}

let current: Asked = { ask: null, n: 0 };
const listeners = new Set<() => void>();

export function openMeetupAsk(a: MeetupAsk | null): void {
  current = { ask: a, n: current.n + 1 };
  listeners.forEach((fn) => fn());
}

export function useMeetupAsk(): Asked {
  const [a, setA] = useState<Asked>(current);
  useEffect(() => {
    const fn = () => setA(current);
    listeners.add(fn);
    fn();
    return () => {
      listeners.delete(fn);
    };
  }, []);
  return a;
}

const parse = (raw: unknown): unknown => {
  if (typeof raw !== 'string') return raw;
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
};

/**
 * «Розділи рахунок»: найновіший рахунок відкритої розмови, інакше — її найновіша зустріч.
 * Немає ні того, ні того (з гаманця розмова може бути будь-яка або жодна) — найновіший
 * рахунок чи зустріч з усіх розмов, і ця розмова відкривається.
 */
function openSplit(): boolean {
  const store = useMessengerStore.getState();
  const live = (m: Message) => !m.isDeleted && (m.type === BILL_KIND || m.type === MEETUP_KIND);
  const newest = (msgs: Message[]) =>
    [...msgs].reverse().find((m) => live(m) && m.type === BILL_KIND) ?? [...msgs].reverse().find(live);
  const open = (m: Message) => openMeetupAsk({ kind: m.type === BILL_KIND ? 'bill' : 'bill-draft', messageId: m.id });
  const active = store.getActiveChat();
  const here = active ? newest(active.messages ?? []) : undefined;
  if (here) {
    open(here);
    return true;
  }
  // Листи розмови вантажаться, коли її відкривають, тож кандидат — і за рядком переліку (`lastKind`).
  const when = (c: Chat) => {
    const m = [...(c.messages ?? [])].reverse().find(live);
    if (m) return Date.parse(m.timestamp);
    return c.lastKind === BILL_KIND || c.lastKind === MEETUP_KIND ? Date.parse(c.lastAt ?? '') || 0 : -1;
  };
  const pick = store.chats
    .filter((c) => c.id !== active?.id)
    .map((c) => ({ c, at: when(c) }))
    .filter((x) => x.at >= 0)
    .sort((x, y) => y.at - x.at)[0]?.c;
  if (!pick) {
    useUIStore.getState().toast({ kind: 'info', message: tNow('split.nothing') });
    return false;
  }
  store.setActiveChat(pick.id);
  const tryOpen = () => {
    const hit = newest(useMessengerStore.getState().chats.find((c) => c.id === pick.id)?.messages ?? []);
    if (hit) open(hit);
    return !!hit;
  };
  if (tryOpen()) return true;
  const stop = useMessengerStore.subscribe(() => {
    if (tryOpen()) {
      stop();
      clearTimeout(timer);
    }
  });
  const timer = setTimeout(stop, SPLIT_WAIT_MS);
  return true;
}

const SPLIT_WAIT_MS = 5000;

type Win = { __phantomOpenMeetupDraft?: (json: unknown) => boolean };

export function installNativeMeetupDraft(): () => void {
  (window as unknown as Win).__phantomOpenMeetupDraft = (json: unknown) => {
    const x = parse(json);
    if (x && typeof x === 'object' && 'split' in (x as Record<string, unknown>)) return openSplit();
    const pin = pinOf(x);
    if (pin) {
      const back = takeParked();
      const name = back?.query.trim() || pin.label || tNow('meetupPin.name');
      openMeetupAsk({ kind: 'meetup', venue: pinVenue(pin.lat, pin.lon, name), venueId: pin.venue, chatId: back?.chatId ?? null, parked: back });
      return true;
    }
    openMeetupAsk({ kind: 'meetup', args: x });
    return true;
  };
  return () => {
    delete (window as unknown as Win).__phantomOpenMeetupDraft;
  };
}
