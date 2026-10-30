/**
 * Сторінка закладу як маніфест: `{ v: 1, blocks: [...] }` із ЗАКРИТОГО словника.
 *
 * Це перший зріз «Білдера» з `docs/builder/DESIGN.md` (гілка `cloud/builder`):
 * маніфест не містить коду, лише блоки, які знає і сайт, і сервер
 * (`platform-site/server/venues.py`). Словник тримає в парі сторож
 * `server/tests/test_venue_blocks_match_site.py` — він же виконує `validatePage`
 * цього файла через Node, тож тут немає імпортів і немає синтаксису, якого Node
 * не вміє просто стерти (enum, namespace).
 *
 * Ціни — десяткові рядки, не числа: 0.1 + 0.2 у JS — не 0.3, а рахунок у чаті
 * ділить їх у мінімальних одиницях (USDC — 6 знаків, SOL — 9).
 */


export const PAGE_VERSION = 1;

/**
 * Поля кожного блоку — і лише вони: зайве поле — відмова, а не мовчазне «прийнято».
 * Необовʼязкові поля теж тут (у меню — `title`), бо сервер звіряє саме цей набір.
 *
 * Фото в словнику немає навмисно: CSP сайту (`img-src 'self' data:`) і WebView
 * месенджера не пускають чужих картинок, тож блок «фото за адресою» малювався б
 * порожньою рамкою на кожній поверхні. Коли буде куди вкласти файл — тоді й блок.
 */
export const BLOCK_FIELDS = {
  title: ['text'],
  about: ['text'],
  address: ['text', 'lat', 'lon'],
  hours: ['rows'],
  menu: ['title', 'token', 'items'],
  event: ['id', 'title', 'starts', 'ends', 'note', 'price', 'token'],
  wallet: ['address'],
} as const;

export type BlockType = keyof typeof BLOCK_FIELDS;

const REQUIRED: Record<BlockType, readonly string[]> = {
  title: ['text'],
  about: ['text'],
  address: ['text', 'lat', 'lon'],
  hours: ['rows'],
  menu: ['token', 'items'],
  event: ['id', 'title', 'starts'],
  wallet: ['address'],
};

export const TOKENS = ['USDC', 'SOL'] as const;
export type Token = (typeof TOKENS)[number];

/** Скільки знаків після крапки має монета: стільки ж, скільки її мінімальна одиниця. */
export const TOKEN_DECIMALS: Record<Token, number> = { USDC: 6, SOL: 9 };

export const DAYS = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'] as const;
export type Day = (typeof DAYS)[number];

export const LIMITS = {
  title: 80,
  about: 1000,
  address: 200,
  menuTitle: 60,
  dishName: 80,
  dishNote: 140,
  dishes: 100,
  /** На всю сторінку, у всіх розділах разом (`venues.py` `MAX_ITEMS`). */
  dishesTotal: 200,
  /** Маніфест у байтах UTF-8, як його рахує сервер (`MAX_PAGE_BYTES`). */
  pageBytes: 32 * 1024,
  hoursRows: 14,
  menus: 6,
  events: 20,
  blocks: 40,
} as const;

export interface MenuItem {
  id: string;
  name: string;
  /** Десятковий рядок без знака й без експоненти: "4.5", "6", "0.25". */
  price: string;
  note?: string;
}

export interface HoursRow {
  day: Day;
  /** "08:00"; закриття може бути після півночі: "01:00" — це вже наступна доба. */
  open: string;
  close: string;
}

export type Block =
  | { type: 'title'; text: string }
  | { type: 'about'; text: string }
  | { type: 'address'; text: string; lat: number; lon: number }
  | { type: 'hours'; rows: HoursRow[] }
  | { type: 'menu'; title?: string; token: Token; items: MenuItem[] }
  | {
      type: 'event';
      id: string;
      title: string;
      starts: string;
      ends?: string;
      note?: string;
      price?: string;
      token?: Token;
    }
  | { type: 'wallet'; address: string };

export interface VenuePage {
  v: typeof PAGE_VERSION;
  blocks: Block[];
}

/**
 * Машинна причина відмови. Слова ті самі, що сервер кладе в `refused` відповіді
 * 422, тож одна таблиця перекладу (`i18n/build.ts`) пояснює і свою перевірку,
 * і серверну.
 */
export type Problem =
  | 'bad_page'
  | 'bad_version'
  | 'too_many_blocks'
  | 'unknown_block'
  | 'unknown_field'
  | 'missing_field'
  | 'missing_title'
  | 'duplicate_block'
  | 'bad_text'
  | 'bad_point'
  | 'bad_hours'
  | 'bad_token'
  | 'mixed_tokens'
  | 'no_items'
  | 'too_many_items'
  | 'bad_item'
  | 'bad_item_id'
  | 'duplicate_item'
  | 'bad_price'
  | 'bad_wallet'
  | 'bad_event_time'
  | 'duplicate_event'
  | 'too_many_events'
  | 'needs_address'
  | 'not_a_list'
  | 'too_big'
  | 'too_many_venues';

export interface Verdict {
  ok: boolean;
  /** Перша знайдена причина і номер блоку, де вона сидить (`null` — сторінка в цілому). */
  problem?: Problem;
  block?: number | null;
}

const BASE58_ALPHABET = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
/** Те саме правило, що в сервера (`venues.py` `_ITEM_ID`): латиниця, цифри, «-», «_». */
const DISH_ID = /^[A-Za-z0-9_-]{1,36}$/;
const CLOCK = /^([01]\d|2[0-3]):[0-5]\d$/;
const EVENT_TIME = /^(\d{4})-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])T([01]\d|2[0-3]):[0-5]\d$/;

export function isEventTime(v: unknown): v is string {
  if (typeof v !== 'string') return false;
  const m = EVENT_TIME.exec(v);
  if (!m) return false;
  const y = Number(m[1]);
  const mo = Number(m[2]);
  const d = Number(m[3]);
  if (y < 1) return false;
  const leap = (y % 4 === 0 && y % 100 !== 0) || y % 400 === 0;
  const maxDay = mo === 2 ? (leap ? 29 : 28) : mo === 4 || mo === 6 || mo === 9 || mo === 11 ? 30 : 31;
  return d <= maxDay;
}

export function localIsoAt(ms = Date.now()): string {
  const d = new Date(ms);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/**
 * Адреса Solana — це 32 байти в base58. Довжини рядка мало: 44 знаки base58
 * можуть розгорнутися і в 33 байти, і сервер (`solana_pay.b58decode`) відмовить.
 */
export function isSolanaAddress(v: unknown): boolean {
  if (typeof v !== 'string' || v.length < 32 || v.length > 44) return false;
  let n = 0n;
  for (const ch of v) {
    const d = BASE58_ALPHABET.indexOf(ch);
    if (d < 0) return false;
    n = n * 58n + BigInt(d);
  }
  let bytes = 0;
  for (let m = n; m > 0n; m >>= 8n) bytes++;
  const zeros = v.length - v.replace(/^1+/, '').length;
  return bytes + zeros === 32;
}

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

/**
 * Керівні (Cc: C0, DEL, C1) і невидимі форматні знаки (Cf: мʼякий перенос,
 * пробіл нульової ширини, LRM/RLM, BOM) — те саме, що відкидає сервер (`_text`).
 * Вони приходять разом із текстом, вставленим із веб-сторінки чи Word.
 */
export const INVISIBLE = /[\p{Cc}\p{Cf}]/u;

/**
 * Непорожній після обрізання, не довший за межу, без керівних і невидимих
 * знаків (у «Про заклад» — можна з нового рядка). Довжина — у знаках, як
 * `len()` у Python, а не в UTF-16: емодзі — один знак, не два.
 * Невидимі знаки шукаємо в усьому рядку, не лише в обрізаному: `trim()` у JS і
 * `strip()` у Python зрізають з країв різне, і так сайт ніколи не пропустить те,
 * чого не пропустить сервер.
 */
function text(v: unknown, max: number, multiline = false): boolean {
  if (typeof v !== 'string') return false;
  const t = v.trim();
  if (!t || [...t].length > max) return false;
  for (const ch of v) {
    if (multiline && ch === '\n') continue;
    if (INVISIBLE.test(ch)) return false;
  }
  return true;
}

/**
 * Маніфест так, як його серіалізує сервер: `json.dumps(page, ensure_ascii=False)`
 * — з пробілом після «,» і «:», без екранування не-ASCII. Межа `too_big` — у
 * байтах саме цього рядка.
 */
function pythonJson(v: unknown): string {
  if (Array.isArray(v)) return `[${v.map(pythonJson).join(', ')}]`;
  if (isObject(v)) {
    const parts = Object.entries(v)
      .filter(([, x]) => x !== undefined)
      .map(([k, x]) => `${JSON.stringify(k)}: ${pythonJson(x)}`);
    return `{${parts.join(', ')}}`;
  }
  return JSON.stringify(v) ?? 'null';
}

export function pageBytes(page: unknown): number {
  return new TextEncoder().encode(pythonJson(page)).length;
}

/**
 * Ціна, яку прийме і сервер, і рахунок: додатний десятковий рядок, не більше
 * знаків, ніж має монета. Кома, знак, пробіл, експонента — відмова: це справа
 * редактора перетворити «7,50» на «7.5» (`canonicalPrice`), а не сервера вгадувати.
 */
export function isPrice(v: unknown, token: Token): boolean {
  if (typeof v !== 'string') return false;
  // Те саме, що `venues.py` `_price`: без нулів попереду («007»), до 9 цілих цифр.
  const m = /^(0|[1-9]\d{0,8})(?:\.(\d+))?$/.exec(v);
  if (!m) return false;
  const frac = m[2] ?? '';
  if (frac.length > TOKEN_DECIMALS[token]) return false;
  return /[1-9]/.test(m[1] + frac);
}

/** «7,50» → «7.5»; «007» → «7»; сміття → null. Рядок, а не число, від початку до кінця. */
export function canonicalPrice(raw: string, token: Token): string | null {
  const s = raw.trim().replace(',', '.');
  const m = /^(\d{1,9})(?:\.(\d*))?$/.exec(s);
  if (!m) return null;
  const whole = m[1].replace(/^0+(?=\d)/, '');
  const frac = (m[2] ?? '').replace(/0+$/, '');
  const out = frac ? `${whole}.${frac}` : whole;
  return isPrice(out, token) ? out : null;
}

function checkFields(b: Record<string, unknown>, type: BlockType): Problem | null {
  const allowed = new Set<string>(['type', ...BLOCK_FIELDS[type]]);
  if (Object.keys(b).some((k) => !allowed.has(k))) return 'unknown_field';
  if (REQUIRED[type].some((k) => !(k in b))) return 'missing_field';
  return null;
}

function checkItem(item: unknown, token: Token, seen: Set<string>): Problem | null {
  if (!isObject(item)) return 'bad_item';
  const allowed = new Set(['id', 'name', 'price', 'note']);
  if (Object.keys(item).some((k) => !allowed.has(k))) return 'unknown_field';
  if (typeof item.id !== 'string' || !DISH_ID.test(item.id)) return 'bad_item_id';
  if (seen.has(item.id)) return 'duplicate_item';
  seen.add(item.id);
  // `seen` — id з усіх розділів: межа сервера на всю сторінку, не на розділ.
  if (seen.size > LIMITS.dishesTotal) return 'too_many_items';
  if (!text(item.name, LIMITS.dishName)) return 'bad_item';
  if ('note' in item && !text(item.note, LIMITS.dishNote)) return 'bad_text';
  if (!isPrice(item.price, token)) return 'bad_price';
  return null;
}

function checkBlock(
  b: Record<string, unknown>,
  type: BlockType,
  ctx: { token?: Token; ids: Set<string>; eventIds: Set<string> },
): Problem | null {
  switch (type) {
    case 'title':
      return text(b.text, LIMITS.title) ? null : 'bad_text';
    case 'about':
      return text(b.text, LIMITS.about, true) ? null : 'bad_text';
    case 'address': {
      if (!text(b.text, LIMITS.address)) return 'bad_text';
      const { lat, lon } = b;
      const okLat = typeof lat === 'number' && Number.isFinite(lat) && lat >= -90 && lat <= 90;
      const okLon = typeof lon === 'number' && Number.isFinite(lon) && lon >= -180 && lon <= 180;
      return okLat && okLon ? null : 'bad_point';
    }
    case 'hours': {
      if (!Array.isArray(b.rows) || b.rows.length === 0 || b.rows.length > LIMITS.hoursRows) return 'bad_hours';
      for (const row of b.rows) {
        if (!isObject(row) || Object.keys(row).some((k) => k !== 'day' && k !== 'open' && k !== 'close')) return 'bad_hours';
        if (!DAYS.includes(row.day as Day)) return 'bad_hours';
        if (typeof row.open !== 'string' || !CLOCK.test(row.open)) return 'bad_hours';
        if (typeof row.close !== 'string' || !CLOCK.test(row.close)) return 'bad_hours';
      }
      return null;
    }
    case 'menu': {
      const token = b.token;
      if (token !== 'USDC' && token !== 'SOL') return 'bad_token';
      // Рахунок у чаті ведеться однією монетою: меню в двох — це два рахунки.
      if (ctx.token && ctx.token !== token) return 'mixed_tokens';
      ctx.token = token;
      if ('title' in b && !text(b.title, LIMITS.menuTitle)) return 'bad_text';
      if (!Array.isArray(b.items) || b.items.length === 0) return 'no_items';
      if (b.items.length > LIMITS.dishes) return 'too_many_items';
      for (const item of b.items) {
        const p = checkItem(item, token, ctx.ids);
        if (p) return p;
      }
      return null;
    }
    case 'event': {
      if (typeof b.id !== 'string' || !DISH_ID.test(b.id)) return 'bad_item_id';
      if (ctx.eventIds.has(b.id)) return 'duplicate_event';
      ctx.eventIds.add(b.id);
      if (!text(b.title, LIMITS.title)) return 'bad_text';
      if (!isEventTime(b.starts)) return 'bad_event_time';
      if ('ends' in b && (!isEventTime(b.ends) || b.ends <= b.starts)) return 'bad_event_time';
      if ('note' in b && !text(b.note, LIMITS.dishNote)) return 'bad_text';
      if ('price' in b || 'token' in b) {
        if (!('price' in b) || !('token' in b)) return 'missing_field';
        if (b.token !== 'USDC' && b.token !== 'SOL') return 'bad_token';
        if (!isPrice(b.price, b.token)) return 'bad_price';
      }
      return null;
    }
    case 'wallet':
      return isSolanaAddress(b.address) ? null : 'bad_wallet';
  }
}

/**
 * Чи прийме сервер цю сторінку. Та сама відповідь, що дасть `PUT /venues/{id}/page`,
 * — але до мережі: власник бачить причину біля поля, а не 422 після кнопки.
 * `publish` — ще й чи можна її ОПУБЛІКУВАТИ: без адреси заклад не знайдеться «поруч».
 */
export function validatePage(page: unknown, opts: { publish?: boolean } = {}): Verdict {
  if (!isObject(page) || !Array.isArray(page.blocks)) return { ok: false, problem: 'bad_page', block: null };
  if (page.v !== PAGE_VERSION) return { ok: false, problem: 'bad_version', block: null };
  const blocks = page.blocks as unknown[];
  if (blocks.length > LIMITS.blocks) return { ok: false, problem: 'too_many_blocks', block: null };
  if (pageBytes(page) > LIMITS.pageBytes) return { ok: false, problem: 'too_big', block: null };

  const count = new Map<string, number>();
  const ctx: { token?: Token; ids: Set<string>; eventIds: Set<string> } = { ids: new Set(), eventIds: new Set() };
  for (let i = 0; i < blocks.length; i++) {
    const b = blocks[i];
    if (!isObject(b) || typeof b.type !== 'string' || !Object.prototype.hasOwnProperty.call(BLOCK_FIELDS, b.type)) {
      return { ok: false, problem: 'unknown_block', block: i };
    }
    const type = b.type as BlockType;
    const n = (count.get(type) ?? 0) + 1;
    count.set(type, n);
    if (type === 'event' && n > LIMITS.events) return { ok: false, problem: 'too_many_events', block: i };
    if (type === 'menu' ? n > LIMITS.menus : type !== 'event' && n > 1) return { ok: false, problem: 'duplicate_block', block: i };
    const problem = checkFields(b, type) ?? checkBlock(b, type, ctx);
    if (problem) return { ok: false, problem, block: i };
  }
  if (!count.has('title')) return { ok: false, problem: 'missing_title', block: null };
  if (opts.publish && !count.has('address')) return { ok: false, problem: 'needs_address', block: null };
  return { ok: true };
}

/** Порядок блоків на сторінці: такий, у якому людина читає заклад. */
export const READING_ORDER: BlockType[] = ['title', 'about', 'address', 'hours', 'event', 'menu', 'wallet'];

export function sortBlocks(blocks: Block[]): Block[] {
  return [...blocks].sort((a, b) => READING_ORDER.indexOf(a.type) - READING_ORDER.indexOf(b.type));
}

const LATIN: Record<string, string> = {
  а: 'a', б: 'b', в: 'v', г: 'h', ґ: 'g', д: 'd', е: 'e', є: 'ie', ж: 'zh', з: 'z', и: 'y', і: 'i', ї: 'i',
  й: 'i', к: 'k', л: 'l', м: 'm', н: 'n', о: 'o', п: 'p', р: 'r', с: 's', т: 't', у: 'u', ф: 'f', х: 'kh',
  ц: 'ts', ч: 'ch', ш: 'sh', щ: 'shch', ь: '', ю: 'iu', я: 'ia', ы: 'y', э: 'e', ё: 'e', ъ: '',
};

/**
 * Назва страви → коротке стабільне id для рахунку (`dish:<id>` у позначках чату).
 * Кирилиця — латинкою (Борщ → borshch), бо id ходить у позначці до 64 байтів.
 */
export function dishId(name: string, taken: Set<string>): string {
  const base =
    [...name.toLowerCase()]
      .map((ch) => LATIN[ch] ?? ch)
      .join('')
      .normalize('NFKD')
      .replace(/[\u0300-\u036f]/g, '')
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 24) || 'dish';
  let id = base;
  for (let n = 2; taken.has(id); n++) id = `${base}-${n}`;
  return id;
}

/** «49.8347, 18.2820» або «49.8347 18.2820» — так їх віддає будь-яка мапа при копіюванні точки. */
export function parsePoint(raw: string): { lat: number; lon: number } | null {
  const m = /^\s*(-?\d{1,2}(?:\.\d+)?)\s*[,;\s]\s*(-?\d{1,3}(?:\.\d+)?)\s*$/.exec(raw);
  if (!m) return null;
  const lat = Number(m[1]);
  const lon = Number(m[2]);
  if (!(lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180)) return null;
  return { lat, lon };
}
