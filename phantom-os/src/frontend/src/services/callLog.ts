/**
 * Журнал дзвінків — лише на цьому апараті. Кожен завершений чи пропущений
 * дзвінок рушія (`callEngine`) стає записом: рядок у розмові й рядок у
 * Я › Дзвінки. Набирає лише людина — кнопкою, ніколи сам.
 */
import { create } from 'zustand';
import type { CallMedia, CallPeer, CallSnapshot } from './callEngine';
import { REASON } from './callReasons';
import type { Message } from '../types/messenger';

export type CallLineKind = 'in' | 'out' | 'missed' | 'declined';

export interface CallLogEntry {
  id: string;
  kind: CallLineKind;
  media: CallMedia;
  /** Коли дзвінок скінчився, мс епохи. */
  at: number;
  durationSec: number;
  name: string;
  peerNodeId?: string;
  contactId?: string;
  callId?: string;
}

const KEY = 'phantom.callLog.v1';
export const CALL_LOG_CAP = 200;

/** Перехід рушія в `ended` — один запис; усе інше — нічого. */
export function callLineFrom(prev: CallSnapshot, next: CallSnapshot, now: number): CallLogEntry | null {
  if (next.state !== 'ended' || prev.state === 'ended' || prev.state === 'idle') return null;
  const peer: CallPeer | null = next.peer ?? prev.peer;
  if (!peer) return null;
  const startedAt = next.startedAt ?? prev.startedAt;
  const durationSec = startedAt != null ? Math.max(0, Math.round((now - startedAt) / 1000)) : 0;
  const outgoing = next.outgoing;
  const kind: CallLineKind = outgoing
    ? 'out'
    : startedAt != null
      ? 'in'
      : next.endedReason === REASON.declined
        ? 'declined'
        : 'missed';
  const callId = next.callId ?? prev.callId ?? peer.callId ?? undefined;
  return {
    id: `${callId ?? 'call'}-${now}`,
    kind,
    media: next.media,
    at: now,
    durationSec,
    name: peer.displayName,
    peerNodeId: peer.peerNodeId,
    contactId: peer.contactId,
    callId,
  };
}

const load = (): CallLogEntry[] => {
  try {
    const raw = localStorage.getItem(KEY);
    const list = raw ? (JSON.parse(raw) as unknown) : [];
    return Array.isArray(list) ? (list as CallLogEntry[]).filter((e) => e && typeof e.at === 'number') : [];
  } catch {
    return [];
  }
};

const save = (list: CallLogEntry[]) => {
  try {
    localStorage.setItem(KEY, JSON.stringify(list));
  } catch {
    /* журнал лишається в памʼяті */
  }
};

/** Найновіші зверху. */
export const newestFirst = (list: CallLogEntry[]): CallLogEntry[] => [...list].sort((a, b) => b.at - a.at);

interface CallLogState {
  entries: CallLogEntry[];
  add: (e: CallLogEntry) => void;
}

export const useCallLog = create<CallLogState>((set, get) => ({
  entries: newestFirst(load()),
  add: (e) => {
    if (get().entries.some((x) => x.id === e.id)) return;
    const entries = newestFirst([e, ...get().entries]).slice(0, CALL_LOG_CAP);
    save(entries);
    set({ entries });
  },
}));

type Engine = { subscribe: (cb: () => void) => () => void; getSnapshot: () => CallSnapshot };

export function installCallLog(engine: Engine, now: () => number = Date.now): () => void {
  let prev = engine.getSnapshot();
  return engine.subscribe(() => {
    const next = engine.getSnapshot();
    const entry = callLineFrom(prev, next, now());
    prev = next;
    if (entry) useCallLog.getState().add(entry);
  });
}

export const callLineIsFor = (e: CallLogEntry, chat: { id: string; peerNodeId?: string }): boolean =>
  (Boolean(e.peerNodeId) && (e.peerNodeId === chat.peerNodeId || e.peerNodeId === chat.id)) ||
  (Boolean(e.contactId) && e.contactId === chat.id);

export function formatCallDuration(sec: number): string {
  const s = Math.max(0, Math.round(sec));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const r = String(s % 60).padStart(2, '0');
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${r}` : `${m}:${r}`;
}

/** Рядки розмови з журналу: службові, не бульбашки. */
export function callLineRows(entries: CallLogEntry[], chat: { id: string; peerNodeId?: string }): Message[] {
  return entries
    .filter((e) => callLineIsFor(e, chat))
    .map((e) => {
      const iso = new Date(e.at).toISOString();
      return {
        id: `call-line-${e.id}`,
        senderId: e.kind === 'out' ? '' : (e.peerNodeId ?? ''),
        senderName: e.name,
        senderAvatar: '',
        timestamp: iso,
        sentAt: iso,
        type: 'system',
        callLine: { kind: e.kind, media: e.media, durationSec: e.durationSec },
      } as Message;
    });
}

/** Злиття за часом: рядки дзвінків стають між листами на своє місце. */
export function withCallLines(messages: Message[], rows: Message[]): Message[] {
  if (rows.length === 0) return messages;
  const out = [...messages];
  for (const row of [...rows].sort((a, b) => (a.sentAt ?? '').localeCompare(b.sentAt ?? ''))) {
    const at = row.sentAt ?? '';
    const i = out.findIndex((m) => (m.sentAt ?? '') > at);
    if (i < 0) out.push(row);
    else out.splice(i, 0, row);
  }
  return out;
}
