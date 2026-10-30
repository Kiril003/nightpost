/**
 * Пошук по всіх розмовах одразу — точний збіг підрядка, без «розумних» підмін.
 *
 * ЩО ЗБІГАЄТЬСЯ. Рівно підрядок. Нормалізація не вгадує, що людина «мала на
 * увазі», а лише прибирає різницю, якої око не бачить:
 *  - регістр;
 *  - апостроф: ’ ʼ ‘ ` ´ ′ і ' — один знак (клавіатура телефона дає один,
 *    текст співрозмовника несе інший; «Сімʼя» мусить знаходитись за «сімʼя»);
 *  - латинські двійники кирилиці (a e o p c x y i k → а е о р с х у і к):
 *    «ppo» з латинською o і «рро» — одне й те саме для ока. Перелік — підмножина
 *    `LOOKALIKE` із `core-truth/Intake.kt`: лише ті літери, що однакові й у
 *    малому регістрі (h/b/m/t схожі на Н/В/М/Т тільки великими, а регістр ми
 *    складаємо раніше — тож вони давали б хибні збіги «так» ≈ «tak»).
 *
 * ЩО НЕ ЗЛИВАЄТЬСЯ свідомо: ґ і г, і та и, є та е, ї та і. Це різні літери
 * української, і «ґанок» не мусить знаходити «ганок». Морфології, синонімів,
 * опечаток — немає: саме цього просили («без алгоритмічних спотворень»).
 *
 * ЗГОРТАННЯ 1:1. Кожен знак стає рівно одним знаком, тож позиція збігу в
 * згорнутому рядку — це та сама позиція в оригіналі. Підсвітка не потребує
 * жодної таблиці зсувів.
 *
 * Двійник на боці телефона — `WebMessengerSearch.fold` у companion; вектори
 * в обох тестах однакові (`messageSearch.test.ts` ↔ `WebMessengerSearchTest`).
 */
import { plainText } from './plainText';
import type { Chat, Message } from '../types/messenger';
import { parseBillData } from '../types/meetup';
import { readCardMarks } from './meetup/marks';
import { cardFacts } from './cardFacts';

const APOSTROPHES = new Set(['’', 'ʼ', '‘', '`', '´', '′']);

const LOOKALIKE: Record<string, string> = {
  a: 'а', e: 'е', o: 'о', p: 'р', c: 'с', x: 'х', y: 'у', i: 'і', k: 'к',
};

/** Один знак → рівно один знак. */
export const foldChar = (ch: string): string => {
  if (APOSTROPHES.has(ch)) return "'";
  const low = ch.toLowerCase();
  // «İ» у нижньому регістрі дає ДВА знаки; тоді лишаємо як є, щоб не зсунути позиції.
  const one = low.length === 1 ? low : ch;
  return LOOKALIKE[one] ?? one;
};

// Кеш на кодову одиницю: згортання 10 тис. листів інакше впирається в toLowerCase.
const FOLD_TABLE = new Map<number, string>();

export const foldForSearch = (s: string): string => {
  let out = '';
  for (let i = 0; i < s.length; i++) {
    const code = s.charCodeAt(i);
    if (code < 0x41) {
      out += s[i];
      continue;
    }
    let f = FOLD_TABLE.get(code);
    if (f === undefined) {
      f = foldChar(s[i]);
      FOLD_TABLE.set(code, f);
    }
    out += f;
  }
  return out;
};

/** Згорнутий запит; порожній — нічого не шукати. */
export const foldQuery = (raw: string): string => foldForSearch(raw.trim().replace(/\s+/g, ' '));

// ── що з листа шукається ───────────────────────────────────────────────

export type HitField = 'text' | 'caption' | 'file';

/** Назви страв рахунку, які `meId` позначив своїми (`dish:<id>`), у порядку рахунку. */
export const claimedItemNames = (m: Message, meId: string): string[] => {
  if (m.isDeleted || !meId || !m.billData) return [];
  const bill = parseBillData(m.billData);
  if (!bill) return [];
  const { dishes } = readCardMarks(m.cardMarks);
  return bill.items.filter((it) => dishes.get(it.id)?.includes(meId)).map((it) => it.name);
};

/** Поля листа, у яких шукаємо, — у тому порядку, в якому їх видно. */
export const searchableFields = (m: Message, meId?: string): Array<[HitField, string]> => {
  if (m.isDeleted) return [];
  const out: Array<[HitField, string]> = [];
  if (m.text) out.push(['text', plainText(m.text)]);
  if (m.voiceData?.transcript) out.push(['text', m.voiceData.transcript]);
  if (m.imageData?.caption) out.push(['caption', m.imageData.caption]);
  if (m.fileData?.name) out.push(['file', m.fileData.name]);
  if (m.locationData?.name) out.push(['text', m.locationData.name]);
  // Картка — назвою і тим, що шукатимуть: сума з монетою, місце, день. Одним рядком, щоб знахідку було видно з назвою.
  const card = cardFacts(m);
  if (card) out.push(['text', [card.title, card.sub].filter(Boolean).join(' · ')]);
  if (meId) {
    for (const name of claimedItemNames(m, meId)) {
      out.push(['text', name]);
    }
  }
  return out;
};

/** Роздільник листів у згорнутій смузі: у запиті його не буває. */
const SEP = '\u0000';

// ── індекс ─────────────────────────────────────────────────────────────

interface Block {
  /** Масив листів, з якого зібрано смугу. Інша ідентичність — смуга застаріла. */
  src: Message[];
  /** Згорнутий текст усіх листів розмови поспіль, через SEP. */
  hay: string;
  /** Початок кожного листа в `hay`. */
  starts: Int32Array;
  /** id листа — те саме посилання на рядок, що й у сторі, не копія. */
  ids: string[];
}

export interface IndexedHit {
  chatId: string;
  messageId: string;
}

/**
 * Індекс листів, що вже лежать у вебі.
 *
 * Тримає ОДНУ згорнуту смугу на розмову і масив id — оригінальних листів не
 * копіює: фрагмент для видачі береться з самого стору в мить показу, і лише
 * для тих кількох десятків листів, що видно. Пошук — нативний `indexOf` по
 * смузі, тобто лінійний прохід без алокацій на кожен лист.
 *
 * Будується поступово: [enqueue] ставить розмови в чергу, [step] згортає
 * стільки, скільки влазить у бюджет кадру, а [schedule] кличе його в простої
 * — перший кадр списку ніколи не чекає на індекс.
 */
export class MessageIndex {
  private blocks = new Map<string, Block>();
  private queue: Chat[] = [];
  private handle: number | null = null;

  constructor(readonly meId?: string) {}

  /** Скільки листів уже згорнуто — для рядка про межі пошуку. */
  get indexedMessages(): number {
    let n = 0;
    for (const b of this.blocks.values()) n += b.ids.length;
    return n;
  }

  get indexedChats(): number {
    return this.blocks.size;
  }

  get pending(): number {
    return this.queue.length;
  }

  /** Чи смуга цієї розмови свіжа. */
  isFresh(chat: Chat): boolean {
    return this.blocks.get(chat.id)?.src === chat.messages;
  }

  /** Поставити в чергу лише ті розмови, чия стрічка змінилась. Зниклі — викинути. */
  enqueue(chats: Chat[]): void {
    const alive = new Set(chats.map((c) => c.id));
    for (const id of this.blocks.keys()) if (!alive.has(id)) this.blocks.delete(id);
    const queued = new Set(this.queue.map((c) => c.id));
    this.queue = this.queue.filter((c) => alive.has(c.id));
    for (const c of chats) {
      if ((c.messages?.length ?? 0) === 0) {
        this.blocks.delete(c.id);
        continue;
      }
      if (!this.isFresh(c) && !queued.has(c.id)) this.queue.push(c);
    }
  }

  /** Згорнути одну розмову зараз. */
  indexChat(chat: Chat): void {
    const src = chat.messages ?? [];
    const starts = new Int32Array(src.length);
    const ids: string[] = [];
    const parts: string[] = [];
    let at = 0;
    for (const m of src) {
      const fields = searchableFields(m, this.meId);
      if (fields.length === 0) continue;
      const doc = foldForSearch(fields.map((f) => f[1]).join('\n'));
      starts[ids.length] = at;
      ids.push(m.id);
      parts.push(doc);
      at += doc.length + 1;
    }
    this.blocks.set(chat.id, { src, hay: parts.join(SEP), starts: starts.slice(0, ids.length), ids });
  }

  /** Згортати з черги, доки не вичерпано бюджет; true — черга порожня. */
  step(budgetMs = 8, now: () => number = () => performance.now()): boolean {
    const until = now() + budgetMs;
    while (this.queue.length > 0) {
      const chat = this.queue.shift()!;
      this.indexChat(chat);
      if (now() >= until) break;
    }
    return this.queue.length === 0;
  }

  /** Згортати в простої браузера; `onDone` — коли черга спорожніла. */
  schedule(onDone?: () => void): void {
    if (this.handle !== null || this.queue.length === 0) return;
    const ric = (globalThis as { requestIdleCallback?: (cb: (d: { timeRemaining(): number }) => void) => number })
      .requestIdleCallback;
    const tick = (budget: number) => {
      this.handle = null;
      const done = this.step(Math.max(4, Math.min(12, budget)));
      if (done) onDone?.();
      else this.schedule(onDone);
    };
    this.handle = ric
      ? ric((d) => tick(d.timeRemaining()))
      : (setTimeout(() => tick(8), 0) as unknown as number);
  }

  cancel(): void {
    if (this.handle === null) return;
    const cic = (globalThis as { cancelIdleCallback?: (h: number) => void }).cancelIdleCallback;
    if (cic) cic(this.handle);
    clearTimeout(this.handle);
    this.handle = null;
  }

  /**
   * Листи, у яких є згорнутий [needle]. Один лист — один збіг, скільки б разів
   * слово в ньому не траплялось. У кожній розмові — від найновішого.
   */
  search(needle: string, cap = 500): IndexedHit[] {
    const out: IndexedHit[] = [];
    if (!needle) return out;
    for (const [chatId, b] of this.blocks) {
      const found: number[] = [];
      let pos = b.hay.indexOf(needle);
      while (pos >= 0) {
        const doc = upperBound(b.starts, pos) - 1;
        found.push(doc);
        const next = doc + 1 < b.ids.length ? b.starts[doc + 1] : b.hay.length;
        pos = b.hay.indexOf(needle, next);
      }
      for (let i = found.length - 1; i >= 0; i--) {
        out.push({ chatId, messageId: b.ids[found[i]] });
        if (out.length >= cap) return out;
      }
    }
    return out;
  }
}

/** Перший індекс, де starts[i] > x. */
const upperBound = (starts: Int32Array, x: number): number => {
  let lo = 0;
  let hi = starts.length;
  while (lo < hi) {
    const mid = (lo + hi) >>> 1;
    if (starts[mid] <= x) lo = mid + 1;
    else hi = mid;
  }
  return lo;
};

// ── фрагмент ───────────────────────────────────────────────────────────

export interface Fragment {
  /** Показаний шматок (може починатися з «…»). */
  text: string;
  /** Де в `text` починається збіг і скільки він займає. */
  at: number;
  len: number;
  field: HitField;
}

/** Скільки знаків показати перед збігом, щоб він не сховався за обрізом рядка. */
const LEAD = 24;
/** До цієї позиції збіг і так видно в першому рядку — лист не обрізаємо. */
const KEEP_HEAD = 40;

/**
 * Шматок поля навколо збігу. Позиція з згорнутого рядка годиться для
 * оригіналу без перерахунку — згортання 1:1.
 */
export const fragmentOf = (raw: string, needle: string, field: HitField = 'text'): Fragment | null => {
  if (!needle) return null;
  const flat = raw.replace(/\s+/g, ' ');
  const at = foldForSearch(flat).indexOf(needle);
  if (at < 0) return null;
  if (at <= KEEP_HEAD) return { text: flat, at, len: needle.length, field };
  // Обрізаємо по пробілу, щоб не рвати слово навпіл.
  let from = at - LEAD;
  const space = flat.indexOf(' ', from);
  if (space >= 0 && space < at) from = space + 1;
  return { text: '…' + flat.slice(from), at: at - from + 1, len: needle.length, field };
};

/** Фрагмент листа зі стору: перше поле, де є збіг. */
export const fragmentOfMessage = (m: Message, needle: string, meId?: string): Fragment | null => {
  for (const [field, raw] of searchableFields(m, meId)) {
    const f = fragmentOf(raw, needle, field);
    if (f) return f;
  }
  return null;
};
