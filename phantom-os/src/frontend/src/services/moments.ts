/** Моменти: власна сутність на телефоні (`/messenger/moments`, див. WebMessengerMoments.kt). Медіа — наскрізне вкладення, як у розмові. */
import { create } from 'zustand';
import { request } from './api';
import { dimsOf, messengerApi } from './messengerApi';
import { encryptForUpload, forgetMedia, loadMedia, rememberUploadedMedia } from './messengerMedia';
import { isMicroThumb, prepareImage } from './imagePrep';
import { stripMetadata } from './stripMetadata';
import type { SecureMedia } from '../types/messenger';
import { useArchive } from './momentsArchive';
import { rememberSeen, seenLocally } from './momentSeen';

export const MOMENTS_DIR = 'moments';
export const CAPTION_MAX = 280;
/** Фото й відео в одному моменті (MomentWire.MEDIA_MAX). */
export const MEDIA_MAX = 4;
/** Тони тексту на кольорі: 1..6 → --ph-person-0..5; 0 — звичайне тло. */
export const TONES = 6;
export const toneOf = (v: unknown): number => {
  const n = Number(v);
  return Number.isInteger(n) && n >= 1 && n <= TONES ? n : 0;
};
export const VIDEO_MAX_BYTES = 6 * 1024 * 1024;
export const VIDEO_MAX_MS = 15_000;
export const REACTIONS = ['❤️', '😂', '😮', '😢', '👏', '🔥'] as const;
const KNOWN_KEY = 'nightpost.moments.known';
/** Медіа моментів, переслані чи збережені: копія в розмові живе й після 24 год. */
const KEPT_KEY = 'nightpost.moments.kept';
const POLL_MS = 30_000;
/** Моменти, що чекають кінця відкату видалення: опитування їх не повертає. */
const CHOSEN_KEY = 'nightpost.moments.audienceChosen';
/** Користувач уже сам обирав аудиторію (публікацією чи в налаштуваннях): типове «Близькі» більше не підставляється. */
export const audienceChosen = (): boolean => {
  try {
    return localStorage.getItem(CHOSEN_KEY) === '1';
  } catch {
    return false;
  }
};
const markChosen = () => {
  try {
    localStorage.setItem(CHOSEN_KEY, '1');
  } catch {
    /* без сховища типове лишається Близькі */
  }
};
export const heldIds = new Set<string>();

export type MomentState = 'ready' | 'receiving' | 'absent' | 'broken';
export type Reach = 'me' | 'people' | 'circles' | 'contacts' | 'everyone';

export interface MomentMedia extends SecureMedia {
  state: MomentState;
  durationMs?: number;
}

export interface MomentViewerRow {
  id: string;
  name: string;
  atMs: number;
  emoji?: string;
}

export interface Moment {
  id: string;
  fromId: string;
  fromName: string;
  mine: boolean;
  atMs: number;
  editedMs: number;
  untilMs: number;
  keep: boolean;
  reach: Reach;
  circles: string[];
  text: string;
  /** Текст на кольорі: 0 чи нема — звичайне тло. */
  tone?: number;
  seen: boolean;
  toCount: number;
  /** Свої: кому пішов (для правки аудиторії). */
  to?: string[];
  media: MomentMedia[];
  viewers: MomentViewerRow[];
  reactions: Record<string, number>;
  myReaction: string;
}

/**
 * Кому. `everyone`/`circle` — старі форми (W2): «усі мої чати» тепер «Усі контакти», одне коло — список кіл.
 */
export type Audience =
  | { kind: 'me' }
  | { kind: 'contacts' }
  | { kind: 'everyone' }
  | { kind: 'people'; ids: string[] }
  | { kind: 'circle'; name: string; ids: string[] }
  | { kind: 'circles'; names: string[]; ids: string[] };

export interface MomentPrefs {
  audience: Audience;
  keep: boolean;
  everyone: boolean;
  muted: string[];
  /** Скільки тонів знає міст; 0 чи нема — старий телефон, тексту на кольорі немає. */
  tones?: number;
}

const STATES: MomentState[] = ['ready', 'receiving', 'absent', 'broken'];
const REACHES: Reach[] = ['me', 'people', 'circles', 'contacts', 'everyone'];

const strs = (v: unknown): string[] => (Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : []);

function readMedia(raw: unknown): MomentMedia[] {
  if (!Array.isArray(raw)) return [];
  return raw.flatMap((r): MomentMedia[] => {
    if (!r || typeof r !== 'object') return [];
    const m = r as Record<string, unknown>;
    if (typeof m.key_hex !== 'string' || typeof m.blob_id !== 'string') return [];
    const duration = Number(m.duration_ms);
    return [{
      name: String(m.name ?? ''),
      size: Number(m.size ?? 0),
      mime: String(m.mime ?? 'image/jpeg'),
      sha256: String(m.sha256 ?? ''),
      blobId: m.blob_id,
      keyHex: m.key_hex,
      nonceHex: String(m.nonce_hex ?? ''),
      state: STATES.includes(m.state as MomentState) ? (m.state as MomentState) : 'absent',
      ...dimsOf(m),
      ...(isMicroThumb(m.thumb) ? { thumb: m.thumb } : {}),
      ...(Number.isFinite(duration) && duration > 0 ? { durationMs: duration } : {}),
    }];
  });
}

export function readMoments(raw: unknown, nowMs = Date.now()): Moment[] {
  if (!Array.isArray(raw)) return [];
  return raw.flatMap((r): Moment[] => {
    if (!r || typeof r !== 'object') return [];
    const o = r as Record<string, unknown>;
    const id = typeof o.id === 'string' ? o.id : '';
    const keep = o.keep === true;
    const untilMs = Number(o.until_ms);
    if (!id || (!keep && (!Number.isFinite(untilMs) || untilMs <= nowMs))) return [];
    const media = readMedia(o.media);
    const text = String(o.text ?? '').trim();
    if (media.length === 0 && !text) return [];
    const aud = (o.audience && typeof o.audience === 'object' ? o.audience : {}) as Record<string, unknown>;
    const reach = REACHES.includes(aud.kind as Reach) ? (aud.kind as Reach) : 'people';
    const reactions: Record<string, number> = {};
    if (o.reactions && typeof o.reactions === 'object') {
      for (const [k, v] of Object.entries(o.reactions as Record<string, unknown>)) if (Number(v) > 0) reactions[k] = Number(v);
    }
    return [{
      id,
      fromId: String(o.from_id ?? ''),
      fromName: String(o.from_name ?? '').trim(),
      mine: o.mine === true,
      atMs: Number(o.at_ms) || 0,
      editedMs: Number(o.edited_ms) || 0,
      untilMs: keep ? 0 : untilMs,
      keep,
      reach,
      circles: strs(aud.circles),
      text,
      tone: toneOf(o.tone),
      seen: o.seen === true,
      toCount: Number(o.to_count) || 0,
      ...(Array.isArray(o.to) ? { to: strs(o.to) } : {}),
      media,
      viewers: Array.isArray(o.viewers)
        ? (o.viewers as Record<string, unknown>[]).flatMap((v) =>
          v && typeof v.id === 'string' ? [{ id: v.id, name: String(v.name ?? ''), atMs: Number(v.at_ms) || 0, ...(typeof v.emoji === 'string' ? { emoji: v.emoji } : {}) }] : [])
        : [],
      reactions,
      myReaction: typeof o.my_reaction === 'string' ? o.my_reaction : '',
    }];
  });
}

/** Порядок смуги: свої, потім непобачені, потім побачені; у кожній групі — свіжі першими. */
export function stripOrder(list: Moment[]): Moment[] {
  const rank = (m: Moment) => (m.mine ? 0 : m.seen ? 2 : 1);
  return [...list].sort((a, b) => rank(a) - rank(b) || b.atMs - a.atMs);
}

/** Одна плитка на людину: усередині — від старшого до свіжого, людей — як у [stripOrder]. */
/** [ringOf] — моє найближче коло для автора (0 = найближче); без кола — у кінці свого ряду. */
export function stripPeople(list: Moment[], ringOf: (fromId: string) => number = () => 0): Moment[][] {
  const groups = new Map<string, Moment[]>();
  for (const m of list) {
    const key = m.mine ? '' : m.fromId;
    groups.set(key, [...(groups.get(key) ?? []), m]);
  }
  const rank = (g: Moment[]) => (g[0].mine ? 0 : g.every((m) => m.seen) ? 2 : 1);
  const newest = (g: Moment[]) => Math.max(...g.map((m) => m.atMs));
  const ring = (g: Moment[]) => (g[0].mine ? 0 : ringOf(g[0].fromId));
  return [...groups.values()]
    .map((g) => [...g].sort((a, b) => a.atMs - b.atMs))
    .sort((a, b) => rank(a) - rank(b) || ring(a) - ring(b) || newest(b) - newest(a));
}

/** Тіло аудиторії для моста; старі форми зводяться до нових. */
export function audienceBody(a: Audience): { audience: { kind: Reach; circles?: string[] }; to?: string[] } {
  switch (a.kind) {
    case 'me':
      return { audience: { kind: 'me' } };
    case 'contacts':
    case 'everyone':
      return { audience: { kind: a.kind } };
    case 'people':
      return { audience: { kind: 'people' }, to: a.ids };
    case 'circle':
      return { audience: { kind: 'circles', circles: [a.name] }, to: a.ids };
    case 'circles':
      return { audience: { kind: 'circles', circles: a.names }, to: a.ids };
  }
}

function readPrefs(raw: unknown): MomentPrefs {
  const o = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>;
  const aud = (o.audience && typeof o.audience === 'object' ? o.audience : {}) as Record<string, unknown>;
  const to = strs(o.to);
  const audience: Audience =
    aud.kind === 'me' ? { kind: 'me' }
      : aud.kind === 'people' ? { kind: 'people', ids: to }
        : aud.kind === 'circles' ? { kind: 'circles', names: strs(aud.circles), ids: [] }
          : { kind: 'contacts' };
  return { audience, keep: o.keep === true, everyone: o.everyone === true, muted: strs(o.muted), tones: Number(o.tones) >= TONES ? TONES : 0 };
}

const readKnown = (): string[] => {
  try {
    const v = JSON.parse(localStorage.getItem(KNOWN_KEY) || '[]');
    return Array.isArray(v) ? v.filter((x) => typeof x === 'string') : [];
  } catch {
    return [];
  }
};

const writeKnown = (ids: string[]) => {
  try {
    localStorage.setItem(KNOWN_KEY, JSON.stringify(ids));
  } catch {
    /* сховище закрите — кеш розшифрованого просто доживе до своєї чистки */
  }
};

const readKept = (): string[] => {
  try {
    const v = JSON.parse(localStorage.getItem(KEPT_KEY) || '[]');
    return Array.isArray(v) ? v.filter((x) => typeof x === 'string') : [];
  } catch {
    return [];
  }
};

/** Перед пересиланням чи «Збереженим»: розшифроване медіа — у кеш вкладки, і чистка зниклих моментів його не чіпає. */
export async function keepMomentMedia(m: Moment): Promise<void> {
  if (m.media.length === 0) return;
  const ids = m.media.map((x) => x.blobId);
  try {
    localStorage.setItem(KEPT_KEY, JSON.stringify([...new Set([...readKept(), ...ids])]));
  } catch {
    /* сховище закрите — копія доживе, доки живий момент */
  }
  await Promise.all(m.media.map((x) => loadMedia(x).catch(() => null)));
}

/** Розшифроване медіа моменту, якого вже немає, не лишається в кеші вкладки — крім пересланого чи збереженого. */
function forgetGone(live: Moment[]) {
  const ids = live.flatMap((m) => m.media.map((x) => x.blobId));
  const kept = new Set(readKept());
  readKnown().filter((id) => !ids.includes(id) && !kept.has(id)).forEach(forgetMedia);
  writeKnown(ids);
}

/** Тривалість відео з метаданих; формат, якого WebView не грає, — null. */
export function videoDuration(file: File): Promise<number | null> {
  return new Promise((resolve) => {
    if (typeof document === 'undefined' || typeof URL.createObjectURL !== 'function') return resolve(null);
    const v = document.createElement('video');
    if (!v.canPlayType?.(file.type)) return resolve(null);
    const url = URL.createObjectURL(file);
    const timer = setTimeout(() => done(null), 4000);
    const done = (ms: number | null) => {
      clearTimeout(timer);
      URL.revokeObjectURL(url);
      resolve(ms);
    };
    v.preload = 'metadata';
    v.onloadedmetadata = () => done(Number.isFinite(v.duration) ? Math.round(v.duration * 1000) : null);
    v.onerror = () => done(null);
    v.src = url;
  });
}

export class MomentMediaError extends Error {}

async function sealMedia(file: File, durationOf: (f: File) => Promise<number | null>): Promise<{ wire: Record<string, unknown>; plain: File }> {
  const video = file.type.startsWith('video/');
  let plain = file;
  let dims: { width?: number; height?: number; micro?: string } = {};
  let durationMs: number | undefined;
  if (video) {
    if (file.size > VIDEO_MAX_BYTES) throw new MomentMediaError('video_too_big');
    const ms = await durationOf(file);
    if (ms === null || ms <= 0) throw new MomentMediaError('video_unreadable');
    if (ms > VIDEO_MAX_MS + 500) throw new MomentMediaError('video_too_long');
    durationMs = ms;
  } else {
    const prepared = await prepareImage(file);
    plain = prepared.file;
    dims = { width: prepared.width, height: prepared.height, micro: prepared.micro };
  }
  plain = await stripMetadata(plain);
  const sealed = await encryptForUpload(plain);
  const blob = await messengerApi.uploadFile(MOMENTS_DIR, sealed.ciphertext);
  if (!blob?.blob_id) throw new Error('no_blob');
  return {
    plain,
    wire: {
      name: plain.name || (video ? 'moment.mp4' : 'moment.jpg'),
      size: plain.size,
      mime: plain.type || (video ? 'video/mp4' : 'image/jpeg'),
      sha256: blob.sha256 || sealed.sha256,
      blob_id: blob.blob_id,
      key_hex: sealed.keyHex,
      nonce_hex: sealed.nonceHex,
      ...(dims.width && dims.height ? { width: dims.width, height: dims.height } : {}),
      ...(dims.micro ? { thumb: dims.micro } : {}),
      ...(durationMs ? { duration_ms: durationMs } : {}),
    },
  };
}

export interface MomentPatch {
  text?: string;
  audience?: Audience;
  tone?: number;
}

interface MomentsState {
  /** null — міст моментів не знає (старий телефон, вузол ПК): смуги немає. */
  list: Moment[] | null;
  /** true — міст відповів відмовою: list лишається null і смугу не резервуємо. */
  unavailable: boolean;
  prefs: MomentPrefs | null;
  refresh: () => Promise<void>;
  loadPrefs: () => Promise<MomentPrefs | null>;
  markSeen: (id: string) => Promise<void>;
  /** До MEDIA_MAX фото чи коротких відео з підписом, або лише текст (file = null) — тоді й тон. */
  post: (file: File | File[] | null, caption: string, audience: Audience, keep?: boolean, tone?: number) => Promise<Moment>;
  edit: (id: string, text: string) => Promise<Moment>;
  /** Одна правка після публікації: підпис, аудиторія, тон — що передано. */
  update: (id: string, patch: MomentPatch) => Promise<Moment>;
  /** Нова аудиторія свого моменту: хто випав, втрачає його при наступному обміні. */
  reaudience: (id: string, audience: Audience) => Promise<Moment>;
  remove: (id: string) => Promise<void>;
  react: (id: string, emoji: string) => Promise<void>;
  mute: (peerId: string, on: boolean) => Promise<void>;
  revokePeer: (peerId: string) => Promise<void>;
  savePrefs: (audience: Audience, keep: boolean) => Promise<void>;
}

let leftOutContactsIds: string[] | null = null;

export const useMoments = create<MomentsState>((set, get) => ({
  list: null,
  unavailable: false,
  prefs: null,
  refresh: async () => {
    try {
      const was = seenLocally();
      const live = readMoments(await request<unknown>('GET', '/messenger/moments'))
        .filter((m) => !heldIds.has(m.id))
        .map((m) => (!m.seen && !m.mine && was(m.id) ? { ...m, seen: true } : m));
      forgetGone(live);
      useArchive.getState().record(live);
      set({ list: live, unavailable: false });
    } catch {
      set({ list: null, unavailable: true });
    }
  },
  loadPrefs: async () => {
    try {
      const raw = readPrefs(await request<unknown>('GET', '/messenger/moments/prefs'));
      const prefs =
        leftOutContactsIds !== null &&
        raw.audience.kind === 'people' &&
        raw.audience.ids.length === leftOutContactsIds.length &&
        raw.audience.ids.every((id, i) => id === leftOutContactsIds![i])
          ? { ...raw, audience: { kind: 'contacts' } as const }
          : raw;
      set({ prefs });
      return prefs;
    } catch {
      return null;
    }
  },
  markSeen: async (id) => {
    const cur = get().list;
    rememberSeen(id, cur?.find((m) => m.id === id)?.untilMs ?? 0);
    if (cur) set({ list: cur.map((m) => (m.id === id ? { ...m, seen: true } : m)) });
    try {
      await request('POST', `/messenger/moments/${encodeURIComponent(id)}/seen`);
    } catch {
      /* мітка лишиться в цій вкладці; наступний список скаже правду */
    }
  },
  post: async (file, caption, audience, keep = false, tone = 0) => {
    leftOutContactsIds = null;
    markChosen();
    const files = (Array.isArray(file) ? file : file ? [file] : []).slice(0, MEDIA_MAX);
    const sealed: Array<{ wire: Record<string, unknown>; plain: File }> = [];
    for (const f of files) sealed.push(await sealMedia(f, videoDuration));
    const text = caption.trim().slice(0, CAPTION_MAX);
    const tn = sealed.length === 0 ? toneOf(tone) : 0;
    const body = { media: sealed.map((x) => x.wire), text, keep, ...(tn ? { tone: tn } : {}), ...audienceBody(audience) };
    const row = readMoments([await request<unknown>('POST', '/messenger/moments', body)])[0];
    if (!row) throw new Error('bad_reply');
    sealed.forEach((x, i) => {
      if (row.media[i]) rememberUploadedMedia(row.media[i].blobId, x.plain);
    });
    const cur = get().list ?? [];
    set({ list: [row, ...cur.filter((m) => m.id !== row.id)] });
    useArchive.getState().record([row]);
    return row;
  },
  edit: async (id, text) => {
    const row = readMoments([await request<unknown>('PATCH', `/messenger/moments/${encodeURIComponent(id)}`, { text: text.trim().slice(0, CAPTION_MAX) })])[0];
    if (!row) throw new Error('bad_reply');
    set({ list: (get().list ?? []).map((m) => (m.id === id ? row : m)) });
    return row;
  },
  update: async (id, patch) => {
    const body = {
      ...(patch.text !== undefined ? { text: patch.text.trim().slice(0, CAPTION_MAX) } : {}),
      ...(patch.tone !== undefined ? { tone: toneOf(patch.tone) } : {}),
      ...(patch.audience ? audienceBody(patch.audience) : {}),
    };
    if (Object.keys(body).length === 0) {
      const cur = (get().list ?? []).find((m) => m.id === id);
      if (cur) return cur;
    }
    const row = readMoments([await request<unknown>('PATCH', `/messenger/moments/${encodeURIComponent(id)}`, body)])[0];
    if (!row) throw new Error('bad_reply');
    set({ list: (get().list ?? []).map((m) => (m.id === id ? row : m)) });
    return row;
  },
  reaudience: async (id, audience) => {
    const row = readMoments([await request<unknown>('PATCH', `/messenger/moments/${encodeURIComponent(id)}`, audienceBody(audience))])[0];
    if (!row) throw new Error('bad_reply');
    set({ list: (get().list ?? []).map((m) => (m.id === id ? row : m)) });
    return row;
  },
  remove: async (id) => {
    await request('DELETE', `/messenger/moments/${encodeURIComponent(id)}`);
    set({ list: (get().list ?? []).filter((m) => m.id !== id) });
  },
  react: async (id, emoji) => {
    const before = get().list;
    if (before) set({ list: before.map((m) => (m.id === id ? { ...m, myReaction: emoji } : m)) });
    try {
      await request('POST', `/messenger/moments/${encodeURIComponent(id)}/react`, { emoji });
    } catch (e) {
      set({ list: before });
      throw e;
    }
  },
  mute: async (peerId, on) => {
    await request('POST', `/messenger/moments/mute/${encodeURIComponent(peerId)}`, { on });
    if (on) set({ list: (get().list ?? []).filter((m) => m.fromId !== peerId) });
    const prefs = get().prefs;
    if (prefs) set({ prefs: { ...prefs, muted: on ? [...new Set([...prefs.muted, peerId])] : prefs.muted.filter((x) => x !== peerId) } });
    // Вимкнені історії не лежать у списку: щоб повернути людину, їх треба перечитати.
    if (!on) await get().refresh();
  },
  savePrefs: async (audience, keep) => {
    markChosen();
    const raw = readPrefs(await request<unknown>('PUT', '/messenger/moments/prefs', { ...audienceBody(audience), keep }));
    const prev = get().prefs;
    set({ prefs: { ...raw, everyone: prev?.everyone ?? raw.everyone } });
  },
  revokePeer: async (peerId) => {
    await request('POST', `/messenger/moments/revoke/${encodeURIComponent(peerId)}`, {});
    const prefs = get().prefs;
    if (prefs && prefs.audience.kind === 'people') {
      set({
        prefs: {
          ...prefs,
          audience: { ...prefs.audience, ids: prefs.audience.ids.filter((x) => x !== peerId) },
        },
      });
    }
  },
}));

export function preserveContactsAudiencePref(ids: string[], keep: boolean): void {
  leftOutContactsIds = [...ids];
  useMoments.setState((s) => ({
    prefs: {
      ...(s.prefs ?? { everyone: false, muted: [] }),
      audience: { kind: 'contacts' },
      keep,
    },
  }));
}

/** «Залишені» моменти людини для профілю (`me` — свої). Немає моста — порожньо. */
export async function momentHighlights(author: string): Promise<Moment[]> {
  try {
    return readMoments(await request<unknown>('GET', `/messenger/moments/highlights/${encodeURIComponent(author)}`));
  } catch {
    return [];
  }
}

/** Опитування, поки смуга на екрані. */
export function watchMoments(): () => void {
  void useMoments.getState().refresh();
  const t = setInterval(() => void useMoments.getState().refresh(), POLL_MS);
  const onShow = () => {
    if (document.visibilityState === 'visible') void useMoments.getState().refresh();
  };
  document.addEventListener('visibilitychange', onShow);
  return () => {
    clearInterval(t);
    document.removeEventListener('visibilitychange', onShow);
  };
}
