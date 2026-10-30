/**
 * «Де я» в розмові з людиною — дзеркало `WebMessengerWhere` у Kotlin.
 *
 * Картка — не лист: телефон тримає стан PeerWhere, веб його лише показує.
 * Координат у вебі немає: лише рівень, близькість і час.
 */

import { request } from './api';
import { phoneBridge } from './messengerApi';
import type { Chat } from '../types/messenger';
import { tNow } from '../components/chat/tNow';

export type WherePrecision = 'district' | 'quarter' | 'exact';
export type WhereNear = 'same' | 'near' | 'far';
export type WhereWhyNot = 'no_key' | 'key_alarm' | 'not_introduced' | 'unknown';
export type StopPhase = 'live' | 'stopping' | 'shut';

export interface WhereState {
  now_ms: number;
  eligible: boolean;
  why_not: WhereWhyNot | null;
  mine: { precision: WherePrecision; until_ms: number | null; stand: boolean; meet?: boolean } | null;
  /** Показ «до зустрічі» згас сам, бо ми поруч. */
  met_at_ms?: number | null;
  theirs: {
    precision: WherePrecision;
    near: WhereNear | null;
    at_ms: number;
    expires_at_ms: number;
    stand: boolean;
  } | null;
}

/** null — до вимкнення. Інших строків міст не приймає. */
export const WHERE_SPANS: ReadonlyArray<{ minutes: number | null; label: string; hint: string }> = [
  // Підписи читаються в мить показу (getter), щоб мова діяла без перезапуску.
  { minutes: 15, get label() { return tNow('peerWhere.span15'); }, get hint() { return tNow('peerWhere.spanHintGone'); } },
  { minutes: 60, get label() { return tNow('peerWhere.span60'); }, get hint() { return tNow('peerWhere.spanHintGone'); } },
  { minutes: null, get label() { return tNow('peerWhere.spanOpen'); }, get hint() { return tNow('peerWhere.spanHintOpen'); } },
];

export const WHERE_PRECISIONS: ReadonlyArray<{ value: WherePrecision; label: string; hint: string }> = [
  { value: 'district', get label() { return tNow('peerWhere.precDistrict'); }, get hint() { return tNow('peerWhere.precDistrictHint'); } },
  { value: 'quarter', get label() { return tNow('peerWhere.precQuarter'); }, get hint() { return tNow('peerWhere.precQuarterHint'); } },
  { value: 'exact', get label() { return tNow('peerWhere.precExact'); }, get hint() { return tNow('peerWhere.precExactHint'); } },
];

export const precisionWord = (p: WherePrecision): string =>
  tNow(p === 'district' ? 'peerWhere.wordDistrict' : p === 'quarter' ? 'peerWhere.wordQuarter' : 'peerWhere.wordExact');

/** Людина, якій можна показати місце з цієї розмови, або null. */
export function wherePeerOf(chat: Pick<Chat, 'peerNodeId' | 'type' | 'title'> | undefined): { id: string; name: string } | null {
  if (!chat?.peerNodeId || !phoneBridge()) return null;
  if (chat.type === 'group' || chat.type === 'channel' || chat.type === 'saved') return null;
  return { id: chat.peerNodeId, name: chat.title };
}

const path = (peerId: string, verb?: string) =>
  `/messenger/where/${encodeURIComponent(peerId)}${verb ? `/${verb}` : ''}`;

const phases = new Map<string, StopPhase>();
const inflightStops = new Map<string, Promise<WhereState>>();
const phaseListeners = new Set<(peerId: string, phase: StopPhase) => void>();
const listeners = new Set<() => void>();
const checkers = new Set<(peerId: string) => void>();

function setStopPhase(peerId: string, phase: StopPhase): void {
  phases.set(peerId, phase);
  phaseListeners.forEach((fn) => fn(peerId, phase));
}

export function stopPhaseOf(peerId: string): StopPhase {
  return phases.get(peerId) ?? 'live';
}

export function onStopPhase(fn: (peerId: string, phase: StopPhase) => void): () => void {
  phaseListeners.add(fn);
  return () => phaseListeners.delete(fn);
}

export const whereApi = {
  get: (peerId: string) => request<WhereState>('GET', path(peerId)),
  show: (peerId: string, precision: WherePrecision, minutes: number | null) =>
    request<WhereState>('POST', path(peerId), { precision, minutes }).then((res) => {
      setStopPhase(peerId, 'live');
      return res;
    }),
  /** Точно, до [untilMs] або поки ми не ближче 50 м — це рахує телефон. */
  showMeet: (peerId: string, untilMs: number) =>
    request<WhereState>('POST', path(peerId), { precision: 'exact', meet: true, until_ms: untilMs, minutes: null }).then((res) => {
      setStopPhase(peerId, 'live');
      return res;
    }),
  stop: (peerId: string) => request<WhereState>('DELETE', path(peerId)),
  openMap: (peerId: string) => request<{ opened: boolean }>('POST', path(peerId, 'map')),
};

export function stopShare(peerId: string): Promise<WhereState> {
  const existing = inflightStops.get(peerId);
  if (existing) return existing;
  setStopPhase(peerId, 'stopping');
  const pending = whereApi.stop(peerId).then(
    (ack) => {
      inflightStops.delete(peerId);
      if (ack.mine == null) {
        setStopPhase(peerId, 'shut');
        listeners.forEach((fn) => fn());
      } else {
        setStopPhase(peerId, 'live');
      }
      return ack;
    },
    (err) => {
      inflightStops.delete(peerId);
      setStopPhase(peerId, 'live');
      throw err;
    },
  );
  inflightStops.set(peerId, pending);
  return pending;
}

/** Відкрити профіль людини одразу на перевірці — його тримає шапка розмови. */
export function openPeerCheck(peerId: string): void {
  checkers.forEach((fn) => fn(peerId));
}

export function onPeerCheck(fn: (peerId: string) => void): () => void {
  checkers.add(fn);
  return () => checkers.delete(fn);
}

export function onWhereChanged(fn: () => void): () => void {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

if (typeof window !== 'undefined') {
  (window as unknown as { __phantomWhereChanged?: () => void }).__phantomWhereChanged = () => {
    listeners.forEach((fn) => fn());
  };
}

export function minutesLeft(untilMs: number, now: number): string {
  const m = Math.max(1, Math.ceil((untilMs - now) / 60_000));
  if (m < 60) return tNow('peerWhere.leftMin', { m });
  const h = Math.floor(m / 60);
  const rest = m % 60;
  return rest ? tNow('peerWhere.leftHourMin', { h, r: rest }) : tNow('peerWhere.leftHour', { h });
}

export function agoWord(atMs: number, now: number): string {
  const m = Math.floor((now - atMs) / 60_000);
  if (m < 1) return tNow('peerWhere.agoNow');
  if (m < 60) return tNow('peerWhere.agoMin', { m });
  return tNow('peerWhere.agoHour', { h: Math.floor(m / 60) });
}

export function nearWord(near: WhereNear | null, precision: WherePrecision): string {
  if (near === 'same') return tNow(precision === 'district' ? 'peerWhere.sameDistrict' : 'peerWhere.sameBlock');
  if (near === 'near') return tNow('peerWhere.nearby');
  if (near === 'far') return tNow('peerWhere.far');
  return tNow('peerWhere.unknown');
}

export interface WhereLine {
  title: string;
  meta: string;
}

export function mineLine(mine: NonNullable<WhereState['mine']>, now: number): WhereLine {
  const left = mine.until_ms == null ? tNow('peerWhere.untilStopped') : minutesLeft(mine.until_ms, now);
  return { title: tNow('peerWhere.mineTitle'), meta: `${precisionWord(mine.precision)} · ${left}` };
}

export function theirsLine(name: string, theirs: NonNullable<WhereState['theirs']>, now: number): WhereLine {
  return {
    title: tNow('peerWhere.theirsTitle', { name }),
    meta: `${nearWord(theirs.near, theirs.precision)} · ${agoWord(theirs.at_ms, now)}`,
  };
}

export function whyNotSentence(why: WhereWhyNot, name: string): string {
  switch (why) {
    case 'key_alarm':
      return tNow('peerWhere.whyKeyAlarm', { name });
    case 'no_key':
    case 'not_introduced':
      return tNow('peerWhere.whyNotIntroduced', { name });
    default:
      return tNow('peerWhere.whyUnknown');
  }
}

export const whyNotLeadsToCheck = (why: WhereWhyNot | null): boolean => why === 'key_alarm';
