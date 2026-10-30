import type { VoiceEffect } from '../hooks/useVoiceRecorder';

export interface VoiceDraft {
  chunks: Blob[];
  mime: string;
  durationMs: number;
  envelope: number[];
  savedAt: number;
}

export interface VoiceDraftStorage {
  get(key: string): Promise<VoiceDraft | null>;
  set(key: string, draft: VoiceDraft): Promise<void>;
  del(key: string): Promise<void>;
}

const DB_NAME = 'nightpost_voice_drafts_v1';
const STORE = 'drafts';

export const voiceDraftKey = (chatId: string): string => `voiceDraft:${chatId}`;

let dbPromise: Promise<IDBDatabase | null> | null = null;

function openDb(): Promise<IDBDatabase | null> {
  if (typeof indexedDB === 'undefined') return Promise.resolve(null);
  if (!dbPromise) {
    dbPromise = new Promise((resolve) => {
      try {
        const req = indexedDB.open(DB_NAME, 1);
        req.onupgradeneeded = () => {
          if (!req.result.objectStoreNames.contains(STORE)) req.result.createObjectStore(STORE);
        };
        req.onsuccess = () => resolve(req.result);
        req.onerror = () => resolve(null);
      } catch {
        resolve(null);
      }
    });
  }
  return dbPromise;
}

async function run<T>(mode: IDBTransactionMode, fn: (s: IDBObjectStore) => IDBRequest<T>): Promise<T | null> {
  const db = await openDb();
  if (!db) return null;
  return new Promise((resolve) => {
    try {
      const req = fn(db.transaction(STORE, mode).objectStore(STORE));
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => resolve(null);
    } catch {
      resolve(null);
    }
  });
}

/** Без IndexedDB усе мовчки не працює: чернетки просто немає. */
export const idbVoiceDraftStorage: VoiceDraftStorage = {
  async get(key) {
    return ((await run('readonly', (s) => s.get(key))) as VoiceDraft | null) ?? null;
  },
  async set(key, draft) {
    await run('readwrite', (s) => s.put(draft, key));
  },
  async del(key) {
    await run('readwrite', (s) => s.delete(key));
  },
};

export async function loadVoiceDraft(storage: VoiceDraftStorage, chatId: string): Promise<VoiceDraft | null> {
  try {
    const d = await storage.get(voiceDraftKey(chatId));
    return d && d.chunks.length > 0 && d.durationMs > 0 ? d : null;
  } catch {
    return null;
  }
}

export const voiceDraftBlob = (d: VoiceDraft): Blob => new Blob(d.chunks, { type: d.mime });

/** Ефекти жесту → чернетка: 'keep' зберігає знімок, 'send'/'discard' стирають. */
export async function applyVoiceDraftEffect(
  storage: VoiceDraftStorage,
  chatId: string,
  fx: VoiceEffect,
  snapshot: () => Promise<Omit<VoiceDraft, 'savedAt'> | null>,
): Promise<void> {
  try {
    if (fx === 'keep') {
      const snap = await snapshot();
      if (snap && snap.chunks.length > 0) await storage.set(voiceDraftKey(chatId), { ...snap, savedAt: Date.now() });
    } else if (fx === 'send' || fx === 'discard') {
      await storage.del(voiceDraftKey(chatId));
    }
  } catch {
    /* чернетка — бонус, запис не падає */
  }
}
