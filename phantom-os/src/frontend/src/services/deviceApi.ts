/**
 * Вікно «Мій пристрій» — один контракт на обох: на ПК відповідає
 * `routes_messenger_devices.py`, на телефоні — міст (`WebMessengerDevices.kt`).
 * Службові ключі (`sphere`, `level`, `verb`) тут лише для рішень; на екран
 * ідуть слова з цього файла або готові рядки з відповіді.
 */
import { currentDateTag, tNow } from '../components/chat/tNow';
import { request } from './api';
import { isPhoneShell } from '../components/messenger/phoneShell';
import { localDeviceName } from '../components/messenger/chatListFormat';
import WORDS from '../../../shared/deviceWords.json';

export type DeviceKind = 'phone' | 'pc';

export interface OwnDevice {
  id: string;
  name: string;
  kind: DeviceKind;
  /** Сітьова особа, коли пристрій її знає: так вікно знаходить свою розмову. */
  peer_id?: string;
  online: boolean;
  seen_at?: string | null;
  battery_pct?: number;
  charging?: boolean;
  place?: { lat: number; lon: number };
  /** Чи зупинено все на тому боці (коли відомо). */
  stopped?: boolean;
}

export interface DevicesState {
  stopped: boolean;
  devices: OwnDevice[];
}

export type Level = 'off' | 'see' | 'suggest' | 'ask' | 'report' | 'silent';

export interface Rung {
  sphere: string;
  title: string;
  level: Level;
  irreversible: boolean;
}

export interface LadderSide {
  editable: boolean;
  spheres: Rung[];
}

export interface Ladders {
  here: LadderSide;
  there: LadderSide | null;
}

export interface JournalLine {
  id: string;
  at: string;
  /** Службові ключі для таблиці слів (`shared/deviceWords.json`); на екран не йдуть. */
  verb?: string;
  status?: string;
  /** Запасна назва, коли дієслова в таблиці немає. */
  what: string;
  who: string;
  status_title: string;
  detail: string;
  can_answer: boolean;
}

export interface ActReply {
  ok: boolean;
  state: 'done' | 'sent' | 'asked' | 'suggested' | 'refused' | 'stopped' | 'failed' | 'offline' | 'empty';
  said: string;
}

export const LEVELS: Array<{ level: Level; title: string }> = [
  // Підписи читаються в мить показу (getter), щоб мова діяла без перезапуску.
  { level: 'off', get title() { return tNow('device.lvOff'); } },
  { level: 'see', get title() { return tNow('device.lvSee'); } },
  { level: 'suggest', get title() { return tNow('device.lvSuggest'); } },
  { level: 'ask', get title() { return tNow('device.lvAsk'); } },
  { level: 'report', get title() { return tNow('device.lvReport'); } },
  { level: 'silent', get title() { return tNow('device.lvSilent'); } },
];

export const levelTitle = (level: Level): string => LEVELS.find((l) => l.level === level)?.title ?? '';

/** Незворотне вище за «так» не піднімається — ні в списку, ні в показі. */
export const levelsFor = (r: Pick<Rung, 'irreversible'>) =>
  r.irreversible ? LEVELS.slice(0, LEVELS.findIndex((l) => l.level === 'ask') + 1) : LEVELS;

export const shownLevel = (r: Pick<Rung, 'irreversible' | 'level'>): Level =>
  r.irreversible && (r.level === 'report' || r.level === 'silent') ? 'ask' : r.level;

type Words = {
  verbs: Record<string, { done: string; tried: string }>;
  mind: Record<string, string>;
  headlines: Record<string, string>;
  done: string[];
  statuses: Record<string, string>;
  why: string[];
  acts: Record<string, string>;
  who: Record<string, string>;
  unnamed_pc: string;
  missions: Record<string, string>;
};
const W = WORDS as Words;

/** Що сталося — минулим часом; стан — другим рядком. Одна таблиця для ПК і телефона. */
export const journalWords = (e: JournalLine): { what: string; state: string } => {
  const status = e.status ?? '';
  const words = e.verb ? W.verbs[e.verb] : undefined;
  const what =
    W.headlines[status] ?? (words ? (W.done.includes(status) ? words.done : words.tried) : e.what || e.status_title);
  const said = status in W.statuses ? W.statuses[status] : e.status_title;
  const why = W.why.includes(status) && e.detail && e.detail !== said ? e.detail : '';
  const who = W.who[e.who] ?? e.who;
  return { what, state: [who, said, why].filter(Boolean).join(' · ') };
};


/** Те саме мовою інтерфейсу. */
export const alwaysAsks = (): string => tNow('device.alwaysAsks');

/** Слово відповіді на дію: власні слова ПК (якщо дав) або слово стану з таблиці. */
export const actWords = (r: Pick<ActReply, 'state' | 'said'>): string => r.said || W.acts[r.state] || '';

/** Назва пристрою; ПК без імені зветься словом із таблиці. */
export const deviceName = (d: Pick<OwnDevice, 'name' | 'kind'>): string =>
  (d.name && localDeviceName(d.name)) || (d.kind === 'phone' ? '' : W.unnamed_pc);

/** Запис незмінної стрічки «Що я роблю» (`symbiote/feed.py`). */
export interface FeedEntry {
  n: number;
  at_iso: string;
  /** mind — PHANTOM сам; human — людина; pc / phone — тіло, що виконало. */
  who: 'mind' | 'human' | 'pc' | 'phone';
  what: string;
  status: string;
  detail: string;
  risk: string;
  hash: string;
}

/** Доручення («Кузня», `agent/forge/service.py`): ціль і скільки кроків доведено системою. */
export interface MissionLine {
  id: string;
  goal: string;
  status: string;
  tasks: number;
  done: number;
  /** Чому стоїть (моделі зайняті, «Стоп усе»), коли стоїть. */
  waiting?: string | null;
  /** Дія назовні, на яку крок чекає «так» людини. */
  ask?: { id: string; tool: string; args: Record<string, unknown>; risk: string } | null;
  error?: string | null;
}

export interface Feed {
  stopped: boolean;
  /** Доручення власника: живі всі, завершені — кілька останніх. Старий вузол їх не віддає. */
  missions?: MissionLine[];
  /** Телефон: стрічку веде ПК, а він зараз не на зв'язку. */
  offline?: boolean;
  chain: { ok: boolean; count: number; broken_at: number | null; why: string };
  entries: FeedEntry[];
}

/** Рядок стрічки людськими словами з тієї самої таблиці, що й журнал пристрою. */
export const feedWords = (e: FeedEntry): { what: string; state: string } => {
  const words = W.verbs[e.what];
  const ok = e.status === 'done' || W.done.includes(e.status);
  const what =
    W.headlines[e.status] ?? W.mind[e.what] ?? (words ? (ok ? words.done : words.tried) : e.what);
  const said =
    e.status === 'stopped_by_owner'
      ? W.statuses.stopped
      : e.status in W.statuses
        ? W.statuses[e.status]
        : ok
          ? ''
          : W.statuses.failed;
  const why = !ok && e.detail && e.detail !== said ? e.detail : '';
  return { what, state: [W.who[e.who] ?? e.who, said, why].filter(Boolean).join(' · ') };
};

/** Стан доручення людськими словами; питання — словами дії з тієї самої таблиці. */
export const missionWords = (m: MissionLine): { state: string; progress: string; ask: string } => {
  const state = W.missions[m.status] ?? m.status;
  const progress = m.tasks > 0 ? tNow('device.stepsProven', { done: m.done, tasks: m.tasks }) : '';
  const ask = m.status === 'asking' && m.ask ? (W.mind[m.ask.tool] ?? m.ask.tool) : '';
  return { state, progress, ask };
};

const run = (id: string, what: string) => `/forge/runs/${encodeURIComponent(id)}/${what}`;

const at = (id: string) => `/messenger/devices/${encodeURIComponent(id)}`;

const isObj = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);

/** Стрічка не тієї форми (старий вузол, міст без ручки) — «немає стрічки», а не падіння Профілю. */
export const feedOf = (raw: unknown): Feed | null => {
  if (!isObj(raw) || !Array.isArray(raw.entries) || !isObj(raw.chain)) return null;
  return {
    ...(raw as unknown as Feed),
    stopped: raw.stopped === true,
    entries: raw.entries.filter((e): e is FeedEntry => isObj(e) && typeof e.what === 'string'),
    missions: Array.isArray(raw.missions) ? (raw.missions.filter(isObj) as unknown as MissionLine[]) : undefined,
  };
};

export const deviceApi = {
  list: () => request<DevicesState>('GET', '/messenger/devices'),
  /** Профіль › PHANTOM: стрічка і «Стоп усе» — на ПК вузол, на телефоні міст. */
  feed: async (): Promise<Feed> => {
    const feed = feedOf(await request<unknown>('GET', '/messenger/devices/feed'));
    if (!feed) throw new Error('feed: unexpected shape');
    return feed;
  },
  stopAll: (on: boolean) => request<{ stopped: boolean }>('POST', '/messenger/devices/stop', { on }),
  /** Доручення: керує вузол (ПК); пауза, далі, скасувати, «так»/«ні» на дію назовні. */
  mission: (id: string, what: 'pause' | 'resume' | 'cancel') => request<MissionLine>('POST', run(id, what)),
  answerMission: (id: string, yes: boolean) => request<MissionLine>('POST', run(id, 'answer'), { yes }),
  act: (id: string, verb: string, text = '') => request<ActReply>('POST', `${at(id)}/act`, { verb, text }),
  ladder: (id: string) => request<Ladders>('GET', `${at(id)}/ladder`),
  setRung: (id: string, sphere: string, level: Level) =>
    request<Ladders>('PATCH', `${at(id)}/ladder`, { sphere, level }),
  journal: (id: string) => request<{ stopped: boolean; entries: JournalLine[] }>('GET', `${at(id)}/journal`),
  answer: (id: string, entryId: string, yes: boolean) =>
    request<{ ok: boolean; said: string }>('POST', `${at(id)}/journal/${encodeURIComponent(entryId)}/answer`, { yes }),
  stop: (id: string, on: boolean) => request<{ stopped: boolean }>('POST', `${at(id)}/stop`, { on }),
};

/** Який пристрій стоїть за розмовою: за сітьовою особою, а коли її нема — єдиний. */
export const deviceFor = (devices: OwnDevice[], peerNodeId?: string): OwnDevice | null => {
  if (peerNodeId) {
    const exact = devices.find((d) => d.peer_id === peerNodeId);
    if (exact) return exact;
  }
  return devices.length === 1 ? devices[0] : null;
};

/** Тут — телефон чи компʼютер: від цього залежать слова й дії. */
export const hereIsPhone = (): boolean => isPhoneShell();

export const seenWord = (iso: string | null | undefined, nowMs = Date.now()): string => {
  if (!iso) return '';
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return '';
  const min = Math.max(0, Math.round((nowMs - t) / 60_000));
  if (min < 1) return tNow('device.seenNow');
  if (min < 60) return tNow('device.seenMin', { n: min });
  const d = new Date(t);
  const today = new Date(nowMs);
  const tag = currentDateTag();
  const hm = d.toLocaleTimeString(tag, { hour: '2-digit', minute: '2-digit' });
  if (d.toDateString() === today.toDateString()) return tNow('device.seenToday', { hm });
  return tNow('device.seenDate', { date: d.toLocaleDateString(tag, { day: 'numeric', month: 'long' }) });
};

export const lineTime = (iso: string, nowMs = Date.now()): string => {
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return '';
  const d = new Date(t);
  const tag = currentDateTag();
  const hm = d.toLocaleTimeString(tag, { hour: '2-digit', minute: '2-digit' });
  return d.toDateString() === new Date(nowMs).toDateString()
    ? hm
    : `${d.toLocaleDateString(tag, { day: 'numeric', month: 'short' })}, ${hm}`;
};
