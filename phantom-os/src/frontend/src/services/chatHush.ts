/**
 * Тиша по розмові — перемикач, який справді щось глушить.
 *
 * До 16.09 «Без звуку» в шарі групи крутив ЛОКАЛЬНИЙ `useState` і не торкався
 * навіть `chat.muted`; сам `chat.muted` писався `toggleMuteChat` і не читався
 * НІДЕ — жодного споживача в дереві. Тобто вимкнути звук було неможливо, а
 * кнопка про це мовчала.
 *
 * Тут названо рівно те, що глушиться: дзвіночок вхідного рядка
 * (`soundFx.playReceive`). Системних банерів на повідомлення веб не піднімає
 * взагалі — єдиний `notificationPrefs.show` у дереві стоїть на дзвінках
 * (`useCallAlerts`), — тож обіцяти «сповіщення вимкнено» було б обіцянкою про
 * те, чого немає.
 *
 * On the phone the shade is drawn by the app, not the web, so the muted set and
 * its end times live in the app (`/messenger/hush`) and localStorage is only a
 * cache; elsewhere localStorage is the store.
 */
import type { Chat, Message } from '../types/messenger';
import { request } from './api';
import { isPhoneShell } from '../components/messenger/phoneShell';

const KEY = 'phantom_hushed_chats';
const UNTIL_KEY = 'phantom_hushed_until';
const RULES_KEY = 'phantom_hush_rules.v1';
const TIME_RE = /^\d{1,2}:\d{2}$/;
const THREE_HOURS_MS = 3 * 60 * 60 * 1000;

export interface HushRule {
  from?: string;
  to?: string;
  plan?: boolean;
  ask?: boolean;
}

interface RuleStore {
  chats: Record<string, HushRule>;
  rings: Record<string, HushRule>;
}

const parseMinutes = (hhmm: string | undefined): number | null => {
  if (!hhmm || !TIME_RE.test(hhmm)) return null;
  const [hStr, mStr] = hhmm.split(':');
  const h = Number(hStr);
  const m = Number(mStr);
  if (!Number.isInteger(h) || !Number.isInteger(m) || h < 0 || h > 23 || m < 0 || m > 59) return null;
  return h * 60 + m;
};

const sanitizeRule = (val: unknown): HushRule | null => {
  if (!val || typeof val !== 'object' || Array.isArray(val)) return null;
  const r = val as Record<string, unknown>;
  const hasFrom = r.from !== undefined;
  const hasTo = r.to !== undefined;
  if (hasFrom || hasTo) {
    if (typeof r.from !== 'string' || typeof r.to !== 'string') return null;
    const fromM = parseMinutes(r.from);
    const toM = parseMinutes(r.to);
    if (fromM === null || toM === null || fromM === toM || r.from === r.to) return null;
  }
  const out: HushRule = {};
  if (typeof r.from === 'string') out.from = r.from;
  if (typeof r.to === 'string') out.to = r.to;
  if (typeof r.plan === 'boolean') out.plan = r.plan;
  if (typeof r.ask === 'boolean') out.ask = r.ask;
  return out;
};

const sanitizeBucket = (val: unknown): Record<string, HushRule> => {
  if (!val || typeof val !== 'object' || Array.isArray(val)) return {};
  const out: Record<string, HushRule> = {};
  for (const [k, v] of Object.entries(val as Record<string, unknown>)) {
    const clean = sanitizeRule(v);
    if (clean) out[k] = clean;
  }
  return out;
};

const parseRuleStore = (raw: string | null): RuleStore => {
  if (!raw) return { chats: {}, rings: {} };
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return { chats: {}, rings: {} };
    const obj = parsed as Record<string, unknown>;
    return {
      chats: sanitizeBucket(obj.chats),
      rings: sanitizeBucket(obj.rings),
    };
  } catch {
    return { chats: {}, rings: {} };
  }
};

const read = (): Set<string> => {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return new Set();
    const parsed: unknown = JSON.parse(raw);
    return Array.isArray(parsed) ? new Set(parsed.filter((x): x is string => typeof x === 'string')) : new Set();
  } catch {
    return new Set();
  }
};

const readUntil = (): Map<string, number> => {
  try {
    const raw = localStorage.getItem(UNTIL_KEY);
    if (!raw) return new Map();
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return new Map();
    const out = new Map<string, number>();
    for (const [k, v] of Object.entries(parsed as Record<string, unknown>)) {
      if (typeof v === 'number' && Number.isFinite(v)) out.set(k, v);
    }
    return out;
  } catch {
    return new Map();
  }
};

const readRulesRaw = (): string | null => {
  try {
    return localStorage.getItem(RULES_KEY);
  } catch {
    return null;
  }
};

export const hushPort = {
  on: (): boolean => isPhoneShell(),
  load: () => request<{ chats?: unknown }>('GET', '/messenger/hush'),
  put: (chatId: string, until: number | null, title?: string) =>
    request<unknown>('PUT', `/messenger/hush/${encodeURIComponent(chatId)}`, title ? { until, title } : { until }),
  drop: (chatId: string) => request<unknown>('DELETE', `/messenger/hush/${encodeURIComponent(chatId)}`),
  /** Rules resolved per chat (effectiveRule); an app without this route answers 404 and keeps plain mute. */
  putRules: (chats: Record<string, HushRule>) => request<unknown>('PUT', '/messenger/hush-rules', { chats }),
};

/** The chat's name rides along so the app's Settings › Notifications can list it. */
let titleOf: (chatId: string) => string | undefined = () => undefined;

/** A write the app refused must not leave the screen saying «muted»: re-read the truth. */
const tellApp = (chatId: string, row: { until: number | null } | null): void => {
  if (!hushPort.on()) return;
  const sent = row ? hushPort.put(chatId, row.until, titleOf(chatId)) : hushPort.drop(chatId);
  void sent.catch(() => chatHush.pull());
};

let hushed = read();
let untilMap = readUntil();
let lastRulesRaw = readRulesRaw();
let rules: RuleStore = parseRuleStore(lastRulesRaw);
let snapshotVersion = 0;
const listeners = new Set<() => void>();
const pulledListeners = new Set<() => void>();

const notify = (): void => {
  snapshotVersion += 1;
  listeners.forEach((fn) => fn());
};

if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
  window.addEventListener('storage', (e: StorageEvent) => {
    if (e.key === RULES_KEY || e.key === null) {
      const fromStorage = readRulesRaw();
      const nextRaw = e.newValue ?? fromStorage;
      if (nextRaw !== fromStorage) {
        try {
          if (nextRaw === null) localStorage.removeItem(RULES_KEY);
          else localStorage.setItem(RULES_KEY, nextRaw);
        } catch {
          /* ignore */
        }
      }
      lastRulesRaw = nextRaw;
      rules = parseRuleStore(nextRaw);
      notify();
    } else if (e.key === KEY) {
      hushed = read();
      notify();
    } else if (e.key === UNTIL_KEY) {
      untilMap = readUntil();
      notify();
    }
  });
}

const syncRules = (): RuleStore => {
  try {
    const raw = localStorage.getItem(RULES_KEY);
    if (raw !== lastRulesRaw) {
      lastRulesRaw = raw;
      rules = parseRuleStore(raw);
    }
  } catch {
    /* без сховища лишається поточна памʼять вкладки */
  }
  return rules;
};

const write = (): void => {
  try {
    localStorage.setItem(KEY, JSON.stringify([...hushed]));
  } catch {
    /* див. read(): без сховища тиша живе лише до закриття вкладки */
  }
};

const writeUntil = (): void => {
  try {
    localStorage.setItem(UNTIL_KEY, JSON.stringify(Object.fromEntries(untilMap)));
  } catch {
    /* без сховища живе лише до закриття вкладки */
  }
};

const writeRules = (): void => {
  try {
    const serialized = JSON.stringify(rules);
    localStorage.setItem(RULES_KEY, serialized);
    lastRulesRaw = serialized;
  } catch {
    /* без сховища правила живуть лише до закриття вкладки */
  }
};

export const inQuietHours = (rule: HushRule, nowMs: number): boolean => {
  const start = parseMinutes(rule.from);
  const end = parseMinutes(rule.to);
  if (start === null || end === null || start === end) return false;
  const d = new Date(nowMs);
  const cur = d.getHours() * 60 + d.getMinutes();
  if (start < end) return cur >= start && cur < end;
  return cur >= start || cur < end;
};

export const isDirectAsk = (
  chat: Chat,
  msg: Message,
  me: { id: string; name: string; handle?: string },
): boolean => {
  const text = msg.text ?? '';
  if (!text.includes('?')) return false;
  if (chat.type === 'dm') return true;
  if (me.name && msg.replyTo?.senderName === me.name) return true;
  const lower = text.toLowerCase();
  const bareHandle = (me.handle ?? '').trim().replace(/^@/, '').toLowerCase();
  if (bareHandle && lower.includes(`@${bareHandle}`)) return true;
  const nameLower = (me.name ?? '').trim().toLowerCase();
  if (nameLower && lower.includes(nameLower)) return true;
  return false;
};

const toPlanMs = (raw: unknown, nowMs: number): number => {
  if (typeof raw === 'number') {
    if (!Number.isFinite(raw)) return NaN;
    return nowMs >= 1e11 && raw > 0 && raw < 1e11 ? raw * 1000 : raw;
  }
  if (typeof raw === 'string') {
    const trimmed = raw.trim();
    if (!trimmed) return NaN;
    if (/^\d+(\.\d+)?$/.test(trimmed)) {
      const n = Number(trimmed);
      return nowMs >= 1e11 && n > 0 && n < 1e11 ? n * 1000 : n;
    }
    return Date.parse(trimmed);
  }
  return NaN;
};

export const planSoon = (
  chat: Chat,
  me: { id: string; name?: string; handle?: string },
  nowMs: number,
): boolean => {
  const plan = chat.plan;
  if (!plan || plan.cancelled) return false;
  const inPlan =
    plan.organizer === me.id ||
    (Array.isArray(plan.keptBy) && plan.keptBy.includes(me.id));
  if (!inPlan) return false;
  const atMs = toPlanMs(plan.at, nowMs);
  if (!Number.isFinite(atMs)) return false;
  const delta = atMs - nowMs;
  return delta >= 0 && delta <= THREE_HOURS_MS;
};

const findRingRule = (chat: Chat): HushRule | undefined => {
  if (chat.circle) {
    const byCircle = chatHush.rule('ring', chat.circle);
    if (byCircle) return byCircle;
  }
  if (Array.isArray(chat.circles)) {
    for (const c of chat.circles) {
      const byRing = chatHush.rule('ring', c);
      if (byRing) return byRing;
    }
  }
  return undefined;
};

export const effectiveRule = (chat: Chat): HushRule | undefined => {
  const ringRule = findRingRule(chat);
  const chatRule = chatHush.rule('chat', chat.id);
  if (!ringRule && !chatRule) return undefined;
  if (!ringRule) return { ...chatRule };
  if (!chatRule) return { ...ringRule };
  const merged: HushRule = { ...ringRule };
  if (chatRule.from !== undefined && chatRule.to !== undefined) {
    merged.from = chatRule.from;
    merged.to = chatRule.to;
  }
  if (chatRule.ask !== undefined) merged.ask = chatRule.ask;
  if (chatRule.plan !== undefined) merged.plan = chatRule.plan;
  return merged;
};

export const hushAllows = (
  chat: Chat,
  msg: Message,
  nowMs: number,
  me: { id: string; name: string; handle?: string },
): boolean => {
  const rule = effectiveRule(chat);
  const hasHours = Boolean(rule?.from && rule?.to);
  const fullMuteByRule = Boolean(rule && !hasHours);
  const quiet =
    chatHush.has(chat.id, nowMs) ||
    Boolean(chat.muted) ||
    fullMuteByRule ||
    Boolean(rule && hasHours && inQuietHours(rule, nowMs));
  if (!quiet) return true;
  if (rule?.ask && isDirectAsk(chat, msg, me)) return true;
  if (rule?.plan && planSoon(chat, me, nowMs)) return true;
  return false;
};

export const chatHush = {
  has: (chatId: string, nowMs: number = Date.now()): boolean => {
    if (!hushed.has(chatId)) return false;
    const until = untilMap.get(chatId);
    if (until !== undefined && nowMs >= until) {
      hushed.delete(chatId);
      untilMap.delete(chatId);
      write();
      writeUntil();
      return false;
    }
    return true;
  },

  until: (chatId: string, nowMs: number = Date.now()): number | null => {
    if (!chatHush.has(chatId, nowMs)) return null;
    return untilMap.get(chatId) ?? null;
  },

  set: (chatId: string, quiet: boolean): void => {
    if (quiet) hushed.add(chatId);
    else hushed.delete(chatId);
    untilMap.delete(chatId);
    write();
    writeUntil();
    notify();
    tellApp(chatId, quiet ? { until: null } : null);
  },

  setFor: (chatId: string, untilMs: number | null): void => {
    hushed.add(chatId);
    if (untilMs === null) {
      untilMap.delete(chatId);
    } else {
      untilMap.set(chatId, untilMs);
    }
    write();
    writeUntil();
    notify();
    tellApp(chatId, { until: untilMs });
  },

  /** Every muted chat still in force, soonest end first, «until unmuted» last. */
  list: (nowMs: number = Date.now()): Array<{ id: string; until: number | null }> =>
    [...hushed]
      .filter((id) => chatHush.has(id, nowMs))
      .map((id) => ({ id, until: untilMap.get(id) ?? null }))
      .sort((a, b) => (a.until ?? Infinity) - (b.until ?? Infinity)),

  /** Phone: take the app's muted set as the truth. */
  pull: async (): Promise<void> => {
    if (!hushPort.on()) return;
    let rows: Record<string, unknown>;
    try {
      const res = await hushPort.load();
      rows = res && typeof res.chats === 'object' && res.chats ? (res.chats as Record<string, unknown>) : {};
    } catch {
      return;
    }
    const nextSet = new Set<string>();
    const nextUntil = new Map<string, number>();
    for (const [id, v] of Object.entries(rows)) {
      if (v === null) nextSet.add(id);
      else if (typeof v === 'number' && Number.isFinite(v)) {
        nextSet.add(id);
        nextUntil.set(id, v);
      }
    }
    hushed = nextSet;
    untilMap = nextUntil;
    write();
    writeUntil();
    notify();
    pulledListeners.forEach((fn) => fn());
  },

  nameWith: (fn: (chatId: string) => string | undefined): void => {
    titleOf = fn;
  },

  /** Fires only when the app's set replaced the local one (not on rule edits). */
  onPulled: (listener: () => void): (() => void) => {
    pulledListeners.add(listener);
    return () => {
      pulledListeners.delete(listener);
    };
  },

  rule: (scope: 'chat' | 'ring', key: string): HushRule | undefined => {
    const store = syncRules();
    const bucket = scope === 'chat' ? store.chats : store.rings;
    return bucket[key];
  },

  setRule: (scope: 'chat' | 'ring', key: string, rule: HushRule | null): void => {
    const store = syncRules();
    const bucket = scope === 'chat' ? store.chats : store.rings;
    if (rule === null) {
      delete bucket[key];
      writeRules();
      notify();
      return;
    }
    if (rule.from !== undefined && rule.from === rule.to) return;
    const clean = sanitizeRule(rule);
    if (!clean) return;
    bucket[key] = clean;
    writeRules();
    notify();
  },

  subscribe: (listener: () => void): (() => void) => {
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  },

  version: (): number => snapshotVersion,

  /** Тільки для тестів: повернути сховище до чистого стану. */
  reset: (): void => {
    hushed = new Set();
    untilMap = new Map();
    rules = { chats: {}, rings: {} };
    write();
    writeUntil();
    writeRules();
    notify();
  },
};
