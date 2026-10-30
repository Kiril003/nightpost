import { supportApi } from '../services/supportApi';

export const CRASH_STORAGE_KEY = 'np.crash.pending.v1';

export interface PendingCrash {
  id: string;
  name: string;
  stackHash: string;
  appVersion: string;
  at: number;
}

const NAME_RE = /^[A-Za-z0-9_.$-]{1,64}$/;

/** Номери рядків і адреси бандла мінялись би щозбірки; без них той самий збій дає той самий хеш. */
export function hashStack(stack: string | undefined): string {
  const norm = (stack ?? '').split('\n').filter((l) => /^\s*at\s|@/.test(l)).join('\n').replace(/https?:\/\/[^\s)]+/g, '').replace(/[:\d]+/g, '#');
  let h1 = 0x811c9dc5;
  let h2 = 0x01000193;
  for (let i = 0; i < norm.length; i++) {
    const c = norm.charCodeAt(i);
    h1 = Math.imul(h1 ^ c, 0x01000193) >>> 0;
    h2 = Math.imul(h2 + c, 0x85ebca6b) >>> 0;
  }
  return h1.toString(16).padStart(8, '0') + h2.toString(16).padStart(8, '0');
}

export function appVersion(): string {
  return typeof __PHONE_BUILD__ !== 'undefined' && __PHONE_BUILD__ ? 'phone-web' : 'web';
}

export function recordCrash(error: Error): void {
  try {
    const crash: PendingCrash = {
      id: globalThis.crypto?.randomUUID?.() ?? `cr-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`,
      name: NAME_RE.test(error.name) ? error.name : 'Error',
      stackHash: hashStack(error.stack),
      appVersion: appVersion(),
      at: Date.now(),
    };
    localStorage.setItem(CRASH_STORAGE_KEY, JSON.stringify(crash));
  } catch {
    // Сховище недоступне — звіту просто не буде.
  }
}

export function readPendingCrash(): PendingCrash | null {
  try {
    const raw = JSON.parse(localStorage.getItem(CRASH_STORAGE_KEY) || 'null');
    if (!raw || typeof raw !== 'object') return null;
    const { id, name, stackHash, appVersion: v, at } = raw as Record<string, unknown>;
    if (typeof id !== 'string' || typeof name !== 'string' || typeof stackHash !== 'string' || typeof v !== 'string') return null;
    return { id, name, stackHash, appVersion: v, at: typeof at === 'number' ? at : 0 };
  } catch {
    return null;
  }
}

export function dismissCrash(): void {
  try {
    localStorage.removeItem(CRASH_STORAGE_KEY);
  } catch {
    // ignore
  }
}

export async function sendCrash(c: PendingCrash): Promise<void> {
  await supportApi.sendCrash({ id: c.id, name: c.name, stack_hash: c.stackHash, app_version: c.appVersion, at: c.at });
  dismissCrash();
}
