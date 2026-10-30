/**
 * Закріплене людиною — те, що завжди нагорі списку, над будь-яким правилом.
 *
 * Спокійний потік (calmInbox) щось підносить, а щось згортає; щоб правило не
 * вирішувало за людину, вона мусить мати важіль сильніший за нього. Цей важіль
 * — закріплення. До 25.09 `togglePinChat` крутив поле в памʼяті: вузол телефона
 * віддає `pinned: false` для кожної розмови вдвох і групи, тож закріплене
 * зникало з першим перезавантаженням, а на телефоні кнопки не було взагалі.
 *
 * Тут лежить ВИБІР людини, а не стан: `true` — закріпила, `false` —
 * відкріпила навіть те, що вузол закріплює сам (PHANTOM). Немає запису —
 * як каже вузол. Сховище місцеве з тієї ж причини, що й тиша (chatHush):
 * PATCH розмови знає лише назву й обличчя.
 */

const KEY = 'phantom_pinned_chats';

type Choices = Record<string, boolean>;

const read = (): Choices => {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return {};
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {};
    const out: Choices = {};
    for (const [id, v] of Object.entries(parsed as Record<string, unknown>)) {
      if (typeof v === 'boolean') out[id] = v;
    }
    return out;
  } catch {
    // Приватне вікно або заборонене сховище: вибір проживе лише до закриття вкладки.
    return {};
  }
};

let choices = read();

const write = (): void => {
  try {
    localStorage.setItem(KEY, JSON.stringify(choices));
  } catch {
    /* див. read() */
  }
};

export const chatPins = {
  /** Вибір людини; undefined — вона не вибирала, чинне слово вузла. */
  choice: (chatId: string): boolean | undefined => choices[chatId],

  /** Закріплена чи ні з урахуванням вибору людини поверх слова вузла. */
  apply: (chatId: string, fromNode: boolean | undefined): boolean => choices[chatId] ?? Boolean(fromNode),

  set: (chatId: string, pinned: boolean): void => {
    choices = { ...choices, [chatId]: pinned };
    write();
  },

  /** Тільки для тестів. */
  reset: (): void => {
    choices = {};
    write();
  },
};
