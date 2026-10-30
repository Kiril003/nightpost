import {
  PAGE_VERSION,
  canonicalPrice,
  dishId,
  isEventTime,
  parsePoint,
  sortBlocks,
  validatePage,
  type Block,
  type HoursRow,
  type Problem,
  type Token,
  type VenuePage,
} from '../../lib/venueBlocks';

/*
 * Чернетка редактора — форма, зручна для полів вводу, і її переклад у маніфест.
 *
 * Поля — сирі рядки, як їх набрала людина («7,50», «49.83, 18.28»): маніфест
 * складаємо лише при збереженні, і тоді ж кажемо, яке саме поле не так. Порожнє
 * необовʼязкове поле — не помилка, а відсутній блок.
 */

export interface DishDraft {
  key: string;
  /** Стабільне id страви з сервера; порожнє — нова страва, id дасть назва. */
  id: string;
  name: string;
  price: string;
  note: string;
}

export interface MenuDraft {
  key: string;
  title: string;
  items: DishDraft[];
}

export interface EventDraft {
  key: string;
  id: string;
  title: string;
  starts: string;
  ends: string;
  note: string;
  price: string;
  token?: Token;
}

export interface Draft {
  title: string;
  about: string;
  address: string;
  point: string;
  hours: HoursRow[];
  events: EventDraft[];
  token: Token;
  menus: MenuDraft[];
  wallet: string;
}

/**
 * Що не так і де: ключ поля — `title`, `point`, `address`, `price:<dishKey>`, `name:<dishKey>`,
 * `event*:<eventKey>`.
 * `missing` — поле порожнє, а його пара заповнена (адреса без точки, точка без
 * адреси); `point` — у полі щось, що не є координатами.
 */
export type FieldErrors = Record<string, 'point' | 'missing' | 'price' | 'name' | 'time'>;

/**
 * Причина, яку бачить власник: слова сервера (`Problem`) плюс дві, яких сервер
 * не скаже, бо до нього така сторінка не доходить — половина місця без другої.
 */
export type DraftProblem = Problem | 'missing_point' | 'missing_address' | 'missing_event_start';

let seq = 0;
export const newKey = () => `k${Date.now().toString(36)}${(seq++).toString(36)}`;

export const blankDish = (): DishDraft => ({ key: newKey(), id: '', name: '', price: '', note: '' });
export const blankMenu = (): MenuDraft => ({ key: newKey(), title: '', items: [blankDish()] });
export const blankEvent = (): EventDraft => ({
  key: newKey(),
  id: '',
  title: '',
  starts: '',
  ends: '',
  note: '',
  price: '',
});

export function emptyDraft(name: string): Draft {
  return {
    title: name,
    about: '',
    address: '',
    point: '',
    hours: [],
    events: [],
    token: 'USDC',
    menus: [blankMenu()],
    wallet: '',
  };
}

export function draftFromPage(page: VenuePage | null, name: string): Draft {
  if (!page) return emptyDraft(name);
  const d = emptyDraft(name);
  d.menus = [];
  for (const b of page.blocks) {
    switch (b.type) {
      case 'title':
        d.title = b.text;
        break;
      case 'about':
        d.about = b.text;
        break;
      case 'address':
        d.address = b.text;
        d.point = `${b.lat}, ${b.lon}`;
        break;
      case 'hours':
        d.hours = b.rows.map((r) => ({ ...r }));
        break;
      case 'menu':
        d.token = b.token;
        d.menus.push({
          key: newKey(),
          title: b.title ?? '',
          items: b.items.map((i) => ({ key: newKey(), id: i.id, name: i.name, price: i.price, note: i.note ?? '' })),
        });
        break;
      case 'event':
        d.events.push({
          key: newKey(),
          id: b.id,
          title: b.title,
          starts: b.starts,
          ends: b.ends ?? '',
          note: b.note ?? '',
          price: b.price ?? '',
          ...(b.token ? { token: b.token } : {}),
        });
        break;
      case 'wallet':
        d.wallet = b.address;
        break;
    }
  }
  if (d.menus.length === 0) d.menus = [blankMenu()];
  return d;
}

/**
 * Текст поля таким, яким його прийме сервер: без невидимих знаків (мʼякий
 * перенос, LRM, пробіл нульової ширини — їх приносить текст, вставлений із
 * веб-сторінки чи Word), табуляція й інші керівні — пробілом, нові рядки —
 * лише там, де їм місце («Про заклад»).
 */
export function cleanText(raw: string, multiline = false): string {
  return raw
    .replace(/\r\n?/g, '\n')
    .replace(/\p{Cf}/gu, '')
    .replace(/\p{Cc}/gu, (ch) => (multiline && ch === '\n' ? ch : ' '))
    .trim();
}

export const isBlankDish = (i: DishDraft) => !cleanText(i.name) && !i.price.trim() && !cleanText(i.note);
const isBlankEvent = (e: EventDraft) =>
  !cleanText(e.title) && !e.starts.trim() && !e.ends.trim() && !cleanText(e.note) && !e.price.trim();

export interface Built {
  page: VenuePage;
  fields: FieldErrors;
  /** Перша причина, чому сервер цю сторінку не прийме (або не дасть опублікувати). */
  problem: DraftProblem | null;
}

export function pageFromDraft(d: Draft, opts: { publish?: boolean } = {}): Built {
  const fields: FieldErrors = {};
  const blocks: Block[] = [];
  const title = cleanText(d.title);
  if (title) blocks.push({ type: 'title', text: title });
  else fields.title = 'missing';
  const about = cleanText(d.about, true);
  if (about) blocks.push({ type: 'about', text: about });

  // Адреса і точка — один блок: половину без другої сервер не прийме, а мовчки
  // викинути половину означало б загубити те, що людина набрала.
  const address = cleanText(d.address);
  const rawPoint = d.point.trim();
  const point = rawPoint ? parsePoint(rawPoint) : null;
  if (rawPoint && !point) fields.point = 'point';
  else if (address && !rawPoint) fields.point = 'missing';
  if (rawPoint && !address) fields.address = 'missing';
  if (address && point) blocks.push({ type: 'address', text: address, lat: point.lat, lon: point.lon });

  if (d.hours.length) blocks.push({ type: 'hours', rows: d.hours.map((r) => ({ ...r })) });

  const events = (d.events ?? []).filter((e) => !isBlankEvent(e));
  const takenEvents = new Set<string>(events.map((e) => e.id).filter(Boolean));
  const usedEvents = new Set<string>();
  for (const e of events) {
    const evTitle = cleanText(e.title);
    if (!evTitle) fields[`eventTitle:${e.key}`] = 'name';
    const starts = e.starts.trim();
    if (!starts) fields[`eventStarts:${e.key}`] = 'missing';
    else if (!isEventTime(starts)) fields[`eventStarts:${e.key}`] = 'time';
    const ends = e.ends.trim();
    if (ends && (!isEventTime(ends) || (isEventTime(starts) && ends <= starts))) {
      fields[`eventEnds:${e.key}`] = 'time';
    }
    const rawPrice = e.price.trim();
    const evToken = e.token ?? d.token;
    const price = rawPrice ? canonicalPrice(rawPrice, evToken) : null;
    if (rawPrice && !price) fields[`eventPrice:${e.key}`] = 'price';
    let id = e.id;
    if (!id || usedEvents.has(id)) {
      // Дата в id: нова подія з тією ж назвою не підхоплює скасування й записи старої.
      const base = dishId(evTitle, new Set()).slice(0, 13).replace(/-+$/, '');
      id = dishId(isEventTime(starts) ? `${base}-${starts.slice(0, 10)}` : base, takenEvents);
      takenEvents.add(id);
    }
    usedEvents.add(id);
    const note = cleanText(e.note);
    blocks.push({
      type: 'event',
      id,
      title: evTitle,
      starts,
      ...(ends ? { ends } : {}),
      ...(note ? { note } : {}),
      ...(rawPrice ? { price: price ?? rawPrice, token: evToken } : {}),
    });
  }

  const taken = new Set<string>(d.menus.flatMap((m) => m.items.map((i) => i.id).filter(Boolean)));
  const used = new Set<string>();
  for (const m of d.menus) {
    const items = m.items.filter((i) => !isBlankDish(i));
    if (!items.length) continue;
    const out = items.map((i) => {
      const price = canonicalPrice(i.price, d.token);
      if (!price) fields[`price:${i.key}`] = 'price';
      if (!cleanText(i.name)) fields[`name:${i.key}`] = 'name';
      let id = i.id;
      if (!id || used.has(id)) {
        id = dishId(cleanText(i.name), taken);
        taken.add(id);
      }
      used.add(id);
      const note = cleanText(i.note);
      return { id, name: cleanText(i.name), price: price ?? i.price.trim(), ...(note ? { note } : {}) };
    });
    const menuTitle = cleanText(m.title);
    blocks.push({ type: 'menu', ...(menuTitle ? { title: menuTitle } : {}), token: d.token, items: out });
  }

  if (d.wallet.trim()) blocks.push({ type: 'wallet', address: d.wallet.trim() });

  const page: VenuePage = { v: PAGE_VERSION, blocks: sortBlocks(blocks) };
  const verdict = validatePage(page, opts);
  const values = Object.values(fields);
  const problem: DraftProblem | null =
    fields.point === 'point'
      ? 'bad_point'
      : fields.point === 'missing'
        ? 'missing_point'
        : fields.address === 'missing'
          ? 'missing_address'
          : Object.entries(fields).some(([k, v]) => k.startsWith('eventStarts:') && v === 'missing')
            ? 'missing_event_start'
            : values.includes('time')
              ? 'bad_event_time'
              : values.includes('price')
                ? 'bad_price'
                : values.includes('name')
                  ? 'bad_item'
                  : verdict.ok
                    ? null
                    : (verdict.problem ?? 'bad_page');
  return { page, fields, problem };
}

/** Інша монета меню не міняє ціну події, яка вже стоїть: така подія тримає свою монету. */
export function withToken(d: Draft, token: Token): Draft {
  const events = (d.events ?? []).map((e) => (e.price.trim() && !e.token ? { ...e, token: d.token } : e));
  return { ...d, token, events };
}
