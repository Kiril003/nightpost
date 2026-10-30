/**
 * «Позначити непрочитаним» (ЧС-03): людина прочитала, але хоче повернутися —
 * і рядок у списку тримає крапку без числа, доки вона розмову не відкриє.
 *
 * Це ВИБІР людини, а не стан листування: лічильник непрочитаного рахує вузол,
 * а позначка лише каже «я ще до цього повернусь». Тому вона не вигадує число
 * (крапка, а не «1») і знімається рівно одним: людина відкрила розмову — хоч з
 * рядка, хоч з пошуку, хоч зі сповіщення (див. `watchOpenClears`), — або
 * сама сказала «Прочитано».
 *
 * Сховище МІСЦЕВЕ, як у тиші й закріпленні (chatHush, chatPins): PATCH розмови
 * знає лише назву й обличчя, а дороги «мої пристрої» для стану списку немає.
 * Отже позначка живе на цьому пристрої й переживає перезапуск, але на ПК чи
 * другий телефон власника не їде — і ми цього не обіцяємо.
 */
import { useSyncExternalStore } from 'react';

const KEY = 'phantom_unread_marks';

/** chatId → коли людина поставила позначку (мс). Час — для чесного порядку й діагностики. */
type Marks = Record<string, number>;

const read = (): Marks => {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return {};
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {};
    const out: Marks = {};
    for (const [id, v] of Object.entries(parsed as Record<string, unknown>)) {
      if (typeof v === 'number' && Number.isFinite(v)) out[id] = v;
    }
    return out;
  } catch {
    // Приватне вікно або заборонене сховище: позначка проживе лише до закриття вкладки.
    return {};
  }
};

let marks: Marks = read();
const listeners = new Set<() => void>();

/**
 * Розмови, які список бачить зараз. Позначка розмови, якої вже немає
 * (прибрали людину), не має права додавати одиницю до плашки — під нею нічого.
 * `null` — список ще не казав, і тоді йдуть усі позначки як є.
 */
let known: Set<string> | null = null;

/**
 * Плашка вкладки «Чати» живе на рідному боці й досі рахувала лише справжні
 * листи: позначене «непрочитаним» мало крапку в рядку й нуль на вкладці (A52,
 * 02.10). Міст отримує повний перелік щоразу, а не дельту — повтор нешкідливий.
 */
const pushToPhone = (): void => {
  const bridge = (globalThis as { PhantomBridge?: { call: (m: string, p: string, b: string | null) => string } }).PhantomBridge;
  if (!bridge || typeof bridge.call !== 'function') return;
  const ids = Object.keys(marks).filter((id) => !known || known.has(id));
  try {
    bridge.call('PUT', '/messenger/unread-marks', JSON.stringify({ chat_ids: ids }));
  } catch {
    /* старий застосунок без цього шляху — плашка просто без позначок */
  }
};

const commit = (next: Marks): void => {
  marks = next;
  try {
    localStorage.setItem(KEY, JSON.stringify(marks));
  } catch {
    /* див. read() */
  }
  for (const cb of listeners) cb();
  pushToPhone();
};

export const chatMarkUnread = {
  has: (chatId: string): boolean => chatId in marks,

  mark: (chatId: string, atMs: number = Date.now()): void => {
    if (!chatId || chatId in marks) return;
    commit({ ...marks, [chatId]: atMs });
  },

  clear: (chatId: string): void => {
    if (!(chatId in marks)) return;
    const next = { ...marks };
    delete next[chatId];
    commit(next);
  },

  /** Знімок для рендеру: той самий обʼєкт, доки нічого не змінилось. */
  snapshot: (): Readonly<Marks> => marks,

  subscribe: (cb: () => void): (() => void) => {
    listeners.add(cb);
    return () => {
      listeners.delete(cb);
    };
  },

  /** Перечитати сховище — те, що бачить застосунок після перезапуску. */
  reload: (): void => {
    marks = read();
    for (const cb of listeners) cb();
  },

  /** Які розмови зараз є — і віддати позначки плашці телефона. */
  syncKnown: (chatIds: Iterable<string>): void => {
    known = new Set(chatIds);
    pushToPhone();
  },

  /** Тільки для тестів. */
  reset: (): void => {
    known = null;
    commit({});
  },
};

/** Позначки для React: рядок перемальовується, щойно людина поставила чи зняла крапку. */
export const useUnreadMarks = (): Readonly<Marks> =>
  useSyncExternalStore(chatMarkUnread.subscribe, chatMarkUnread.snapshot, chatMarkUnread.snapshot);

/**
 * Відкриття знімає позначку, хоч би звідки розмову відкрили. Слухаємо одне
 * джерело правди — `activeChatId` сховища месенджера, — а не кожну кнопку:
 * сповіщення, пошук і «назад/вперед» теж ведуть через нього.
 *
 * `select` — щоб сервіс не тягнув усе сховище (і цикл імпортів) за собою.
 */
export const watchOpenClears = (
  store: { getState: () => { activeChatId?: string }; subscribe: (cb: (s: { activeChatId?: string }) => void) => () => void },
): (() => void) => {
  const open = store.getState().activeChatId;
  if (open) chatMarkUnread.clear(open);
  let last = open;
  return store.subscribe((s) => {
    const id = s.activeChatId;
    if (id === last) return;
    last = id;
    if (id) chatMarkUnread.clear(id);
  });
};
