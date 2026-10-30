/** Чернетки на диску: профіль → чат → текст. Пишемо з затримкою, стираємо одразу. */
const KEY = 'np.drafts.v1';
const MAX = 4000;

type Vault = Record<string, Record<string, string>>;

const timers = new Map<string, { at: ReturnType<typeof setTimeout>; run: () => void }>();

function read(): Vault {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw) as { v?: number; map?: unknown };
    if (parsed?.v !== 1 || !parsed.map || typeof parsed.map !== 'object') return {};
    const out: Vault = {};
    for (const [pid, chats] of Object.entries(parsed.map as Record<string, unknown>)) {
      if (!chats || typeof chats !== 'object') continue;
      const keep: Record<string, string> = {};
      for (const [cid, text] of Object.entries(chats as Record<string, unknown>)) {
        if (typeof text === 'string' && text) keep[cid] = text;
      }
      if (Object.keys(keep).length) out[pid] = keep;
    }
    return out;
  } catch {
    return {};
  }
}

function write(vault: Vault): void {
  try {
    if (Object.keys(vault).length) localStorage.setItem(KEY, JSON.stringify({ v: 1, map: vault }));
    else localStorage.removeItem(KEY);
  } catch {
    /* сховище недоступне: чернетка живе лише в пам'яті */
  }
}

function commit(profileId: string, chatId: string, text: string): void {
  const vault = read();
  const chats = { ...(vault[profileId] ?? {}) };
  if (text) chats[chatId] = text.slice(0, MAX);
  else delete chats[chatId];
  if (Object.keys(chats).length) vault[profileId] = chats;
  else delete vault[profileId];
  write(vault);
}

export function loadDrafts(profileId: string): Record<string, string> {
  return { ...(read()[profileId] ?? {}) };
}

export function keepDraft(profileId: string, chatId: string, text: string, delay = 300): void {
  const id = `${profileId}|${chatId}`;
  const pending = timers.get(id);
  if (pending) {
    clearTimeout(pending.at);
    timers.delete(id);
  }
  const run = () => {
    timers.delete(id);
    commit(profileId, chatId, text.trim() ? text : '');
  };
  if (!text.trim() || delay <= 0) {
    run();
    return;
  }
  timers.set(id, { at: setTimeout(run, delay), run });
}

export function flushDrafts(): void {
  for (const { at, run } of [...timers.values()]) {
    clearTimeout(at);
    run();
  }
}

export function forgetProfileDrafts(profileId: string): void {
  for (const [id, { at }] of [...timers.entries()]) {
    if (id.startsWith(`${profileId}|`)) {
      clearTimeout(at);
      timers.delete(id);
    }
  }
  const vault = read();
  if (!(profileId in vault)) return;
  delete vault[profileId];
  write(vault);
}

export const DRAFTS_KEY = KEY;
