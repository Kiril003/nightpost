/**
 * Одна модель на кожну живу картку, хоч би яким родом вона приїхала.
 *
 * Список справ живе у двох родах: `widget:family-checklist` (їде дротом) і
 * старий `task-list` (лише у стрічці автора). Опитування — `widget:voting` і
 * старий `poll`. Картка малює модель, а зміна пишеться назад у ТЕ поле, з
 * якого прийшла, — інакше лист мав би дві правди.
 */
import type { FamilyChecklistItem, KanbanCard, Message, TableColumn, TableData, TimelineMilestone } from '../../../types/messenger';
import { dateTag } from '../../../i18n/dateTag';
import { resolveLocale } from '../../../i18n/useTranslation';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../../../stores/settingsStore';
import { tNow, tPlural } from '../../chat/tNow';
export interface CheckItem {
  id: string;
  text: string;
  done: boolean;
  /** Хто поставив галочку — імʼя, як його бачив той, хто ставив. */
  doneBy?: string;
  /** Мить останнього дотику до цього пункту: злиття бере найсвіжішу. */
  at?: number;
}

export interface Checklist {
  title: string;
  items: CheckItem[];
}

export interface PollOptionModel {
  id: string;
  text: string;
  voters: string[];
}

export interface Poll {
  question: string;
  options: PollOptionModel[];
  multiple: boolean;
  kind?: 'text' | 'time';
  anonymous?: boolean;
  /** Вікторина: правильний варіант. Голос у ній остаточний. */
  correct?: string;
  closed?: boolean;
}

export interface Expense {
  id: string;
  title: string;
  amount: number;
  paidBy: string;
}

export interface Expenses {
  title: string;
  currency: string;
  items: Expense[];
}

/** Роди, що малюються новою карткою (поза бурштиновою бульбашкою). */
/** Місце (заклад чи точка) і старий рахунок малюються тією ж карткою, що й решта: бульбашка гола, час — у картці. */
export const isMergedCard = (msg: Message): boolean =>
  (msg.type === 'location' && !!msg.locationData) ||
  (msg.type === 'geo:point' && !!msg.geoPoint) ||
  (msg.type === 'split-bill' && !!msg.splitBillData);

export const LIVE_CARD_KINDS: ReadonlySet<string> = new Set([
  'table',
  'task-list',
  'poll',
  'widget:family-checklist',
  'widget:voting',
  'widget:family-expense',
  // Зустріч і рахунок (збірка 18): малюють MeetupCard і BillCard.
  'widget:meetup',
  'widget:bill',
  'widget:person',
  'widget:day',
  'widget:glance',
  'widget:money',
  'widget:gift',
]);

/**
 * Показові віджети робочої ОС: живих даних у них немає, а створити їх людина
 * не може ніде. В ОБОХ збірках замість них — коротка картка (TheatreCard);
 * демо-рендерери (дошка, RACI, план-графік, «Wasm»-код, картки навчання,
 * відеоогляд, diff, SVG, подія) віднято 04.10. Виняток — `widget:canvas` на
 * компʼютері: у документа є справжній виробник («У чат» з CanvasSplitView) і
 * справжній вид (CanvasCardEmbed).
 */
export const THEATRE_CARD_KINDS: ReadonlySet<string> = new Set([
  'widget:canvas',
  'widget:kanban',
  'widget:raci',
  'widget:timeline',
  'widget:code-runner',
  'widget:study-flashcards',
  'snippet:async',
  'embed:mermaid',
  'embed:diff',
  'embed:svg',
  'webhook:event',
]);

/** Перелік із дроту: не масив — порожньо, і без дірок усередині. */
function list<T>(v: T[] | undefined | null): T[] {
  return Array.isArray(v) ? v.filter((x) => x !== null && x !== undefined) : [];
}

export function checklistOf(msg: Message): Checklist | null {
  if (msg.type === 'widget:family-checklist' && msg.familyChecklistData) {
    const d = msg.familyChecklistData;
    return {
      title: d.title ?? '',
      items: list(d.items).map((i) => ({
        id: String(i.id),
        text: String(i.text ?? ''),
        done: !!i.done,
        ...(i.done && typeof i.doneBy === 'string' && i.doneBy ? { doneBy: i.doneBy } : {}),
        ...(typeof i.at === 'number' && Number.isFinite(i.at) ? { at: i.at } : {}),
      })),
    };
  }
  if (msg.type === 'task-list' && msg.taskListData) {
    const d = msg.taskListData;
    return {
      title: d.title ?? '',
      items: list(d.tasks).map((t) => ({ id: String(t.id), text: String(t.title ?? ''), done: !!t.completed })),
    };
  }
  return null;
}

/** Зміна списку — у те саме поле, звідки він прийшов. */
export function checklistPatch(msg: Message, items: CheckItem[], by: string): Partial<Message> {
  if (msg.type === 'task-list' && msg.taskListData) {
    const before = new Map(msg.taskListData.tasks.map((t) => [t.id, t]));
    return {
      taskListData: {
        ...msg.taskListData,
        tasks: items.map((i) => ({ ...before.get(i.id), id: i.id, title: i.text, completed: i.done })),
      },
    };
  }
  const d = msg.familyChecklistData!;
  const before = new Map(list(d.items).map((i) => [i.id, i]));
  return {
    familyChecklistData: {
      ...d,
      items: items.map((i) => ({
        category: '',
        addedBy: by,
        ...before.get(i.id),
        id: i.id,
        text: i.text,
        done: i.done,
        doneBy: i.done ? i.doneBy : undefined,
        at: i.at,
      })),
    },
  };
}

/** Дотик до пункту: галочка, хто й коли. */
export function tick(items: CheckItem[], id: string, by: string, now = Date.now()): CheckItem[] {
  return items.map((x) =>
    x.id === id ? { ...x, done: !x.done, doneBy: x.done ? undefined : by, at: now } : x,
  );
}

export function checklistStatus(list: Checklist): string {
  const total = list.items.length;
  if (total === 0) return tNow('cardModel.empty');
  const done = list.items.filter((i) => i.done).length;
  if (done === total) return tNow('cardModel.allDone');
  return tNow('cardModel.doneOf', { done, total });
}

export function pollOf(msg: Message): Poll | null {
  if (msg.type === 'widget:voting' && msg.votingData) {
    const d = msg.votingData;
    const kind = (d as { kind?: unknown }).kind === 'time' ? 'time' : 'text';
    const correct = kind === 'text' && typeof d.correctOptionId === 'string' ? d.correctOptionId : undefined;
    const pick = d.anonymous && msg.senderId && Array.isArray(d.pick) ? d.pick.map(String) : null;
    return {
      question: d.question ?? '',
      options: list(d.options).map((o) => ({
        id: String(o.id),
        text: String(o.text ?? ''),
        voters: pick && !list(o.voters).length ? (pick.includes(String(o.id)) ? [msg.senderId] : []) : list(o.voters),
      })),
      multiple: kind === 'time' || (!correct && !!d.allowMultiple),
      kind,
      ...(d.anonymous ? { anonymous: true } : {}),
      ...(correct ? { correct } : {}),
      ...(d.isClosed ? { closed: true } : {}),
    };
  }
  if (msg.type === 'poll' && msg.pollData) {
    const d = msg.pollData;
    return {
      question: d.question ?? '',
      options: list(d.options).map((o) => ({ id: String(o.id), text: String(o.text ?? ''), voters: list(o.voters) })),
      multiple: !!d.allowMultiple,
      kind: 'text',
    };
  }
  return null;
}

/** Чи можу я ще голосувати: закрите — ні; у вікторині голос один і назавжди. */
export function canVote(poll: Poll, me: string): boolean {
  if (poll.closed) return false;
  if (poll.correct && poll.options.some((o) => o.voters.includes(me))) return false;
  return true;
}

/** Мій голос за варіант: повторний дотик знімає, в одиночному — переносить. */
export function vote(poll: Poll, optionId: string, me: string): Poll {
  if (!canVote(poll, me)) return poll;
  const mine = poll.options.find((o) => o.id === optionId)?.voters.includes(me);
  const multiple = poll.multiple || poll.kind === 'time';
  return {
    ...poll,
    options: poll.options.map((o) => {
      const without = o.voters.filter((v) => v !== me);
      if (o.id === optionId) return { ...o, voters: mine ? without : [...without, me] };
      return multiple ? o : { ...o, voters: without };
    }),
  };
}

export function pollPatch(msg: Message, poll: Poll, me: string): Partial<Message> {
  const options = poll.options.map((o) => ({ id: o.id, text: o.text, votes: o.voters.length, voters: o.voters }));
  const totalVotes = options.reduce((s, o) => s + o.votes, 0);
  if (msg.type === 'poll' && msg.pollData) {
    return { pollData: { ...msg.pollData, options, totalVotes, userVotedOptionId: undefined } };
  }
  const closed = poll.closed ? { isClosed: true } : {};
  if (poll.anonymous) {
    const pick = poll.options.filter((o) => o.voters.includes(me)).map((o) => o.id);
    return {
      votingData: { ...msg.votingData!, options: options.map(({ voters: _v, ...o }) => o), totalVotes, pick, ...closed },
    };
  }
  return { votingData: { ...msg.votingData!, options, totalVotes, ...closed } };
}

/** Скільки людей голосувало: кожна людина раз, хоч би скільки варіантів обрала. */
export function votersCount(poll: Poll): number {
  return new Set(poll.options.flatMap((o) => o.voters)).size;
}

export function pollStatus(poll: Poll): string {
  const n = votersCount(poll);
  if (n === 0) return tNow('cardModel.noVotes');
  // «Голосувала 1» вгадувала б рід людини — одиницю кажемо голосом.
  return n === 1 ? tNow('cardModel.oneVote') : tNow('cardModel.voted', { n });
}

const EURO = new Set('AT BE CY DE EE ES FI FR GR HR IE IT LT LU LV MT NL PT SI SK'.split(' '));
const SIGN: Readonly<Record<string, string>> = {
  UA: '₴', US: '$', CA: '$', AU: '$', NZ: '$', GB: '£', CZ: 'Kč', PL: 'zł', CH: 'CHF', SE: 'kr', NO: 'kr', DK: 'kr',
  HU: 'Ft', RO: 'lei', MD: 'L', GE: '₾', TR: '₺', IN: '₹', JP: '¥', CN: '¥', KR: '₩', IL: '₪', BR: 'R$',
};
const ISO_SIGN: Readonly<Record<string, string>> = {
  UAH: '₴', CZK: 'Kč', EUR: '€', USD: '$', PLN: 'zł', GBP: '£',
};

let regionCountry: string | null = null;

export function setRegionCountry(c: string | null): void {
  regionCountry = c ? c.trim().toUpperCase() || null : null;
}

export function currencySign(code: string): string {
  if (!code) return '';
  const upper = code.trim().toUpperCase();
  return ISO_SIGN[upper] ?? code;
}

export function localCurrency(
  langs: readonly string[] = typeof navigator === 'undefined' ? [] : navigator.languages ?? [navigator.language],
  country: string | null = regionCountry,
): string {
  const known = country?.trim().toUpperCase();
  if (known) {
    if (EURO.has(known)) return '€';
    if (SIGN[known]) return SIGN[known];
  }
  for (const tag of langs) {
    const [lang, region] = tag.replace('_', '-').split('-');
    const r = region?.toUpperCase();
    if (r && EURO.has(r)) return '€';
    if (r && SIGN[r]) return SIGN[r];
    if (!known && lang === 'uk') return '₴';
  }
  return '';
}

export function expensesOf(msg: Message): Expenses | null {
  if (msg.type !== 'widget:family-expense' || !msg.familyExpenseData) return null;
  const d = msg.familyExpenseData;
  return {
    title: d.title ?? '',
    currency: d.currency || localCurrency(),
    items: list(d.items).map((i) => ({
      id: String(i.id),
      title: String(i.title ?? ''),
      amount: Number(i.amountUAH) || 0,
      paidBy: String(i.paidBy ?? ''),
    })),
  };
}

export function expensesPatch(msg: Message, items: Expense[]): Partial<Message> {
  const d = msg.familyExpenseData!;
  const before = new Map(list(d.items).map((i) => [i.id, i]));
  return {
    familyExpenseData: {
      ...d,
      items: items.map((i) => ({
        category: '',
        ...before.get(i.id),
        id: i.id,
        title: i.title,
        amountUAH: i.amount,
        paidBy: i.paidBy,
      })),
    },
  };
}

export function timeLabel(iso: string): string {
  const ms = Date.parse(iso);
  if (!Number.isFinite(ms)) return iso;
  const raw = useSettingsStore.getState()?.values?.[LANGUAGE_SETTING_KEY];
  const tag = dateTag(resolveLocale(raw)) || 'uk-UA';
  return new Date(ms).toLocaleTimeString(tag, { hour: '2-digit', minute: '2-digit', hour12: false });
}

export function money(n: number): string {
  const raw = useSettingsStore.getState()?.values?.[LANGUAGE_SETTING_KEY];
  const tag = dateTag(resolveLocale(raw)) || 'uk-UA';
  const rounded = Math.round(n * 100) / 100;
  if (Number.isInteger(rounded)) {
    return rounded.toLocaleString(tag, { maximumFractionDigits: 0 }).replace(/\s/g, ' ');
  }
  return rounded
    .toLocaleString(tag, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
    .replace(/\s/g, ' ');
}

export function expensesStatus(e: Expenses): string {
  const n = e.items.length;
  if (n === 0) return tNow('cardModel.empty');
  const sum = e.items.reduce((s, i) => s + i.amount, 0);
  return `${n} ${tPlural(n, 'cardModel.expenses')} · ${money(sum)} ${e.currency}`.trim();
}

/** Одиниця зі стовпця «Сума (₴)» → «₴»; назва без дужок → «Сума». */
export function columnUnit(col: TableColumn): { name: string; unit: string } {
  const m = /^(.*?)\s*\(([^)]+)\)\s*$/.exec(col.label);
  return m ? { name: m[1], unit: m[2] } : { name: col.label, unit: '' };
}

/**
 * Таблиця, придатна до показу, або null. Тіло приходить із дроту від іншої
 * людини: без переліку стовпців картка впала б разом з усією стрічкою.
 */
export function tableOf(msg: Message): TableData | null {
  const t = msg.type === 'table' ? msg.tableData : undefined;
  if (!t || typeof t !== 'object' || !Array.isArray(t.columns) || t.columns.length === 0) return null;
  const columns = t.columns.filter(
    (c): c is TableColumn => !!c && typeof c.key === 'string' && typeof c.label === 'string',
  );
  if (columns.length === 0) return null;
  const rows = Array.isArray(t.rows) ? t.rows.filter((r) => r && typeof r === 'object') : [];
  return { ...t, title: typeof t.title === 'string' ? t.title : '', columns, rows };
}

export function numericColumn(t: TableData): TableColumn | undefined {
  return t.columns.find((c) => c.type === 'number');
}

export function tableStatus(t: TableData): string {
  const n = t.rows.length;
  if (n === 0) return tNow('cardModel.empty');
  const rows = `${n} ${tPlural(n, 'cardModel.rows')}`;
  const col = numericColumn(t);
  if (!col) return rows;
  const sum = t.rows.reduce((s, r) => s + (Number(r[col.key]) || 0), 0);
  const { unit } = columnUnit(col);
  return tNow('cardModel.rowsSum', { rows, sum: money(sum), unit: unit ? ` ${unit}` : '' });
}

/** Клітинка словами: число — з пробілом тисяч, порожнє — порожнє. */
export function cellText(col: TableColumn, v: unknown): string {
  if (v === null || v === undefined || v === '') return '';
  if (col.type === 'number' && Number.isFinite(Number(v))) return money(Number(v));
  return String(v);
}

export function newId(prefix: string): string {
  return `${prefix}_${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

/**
 * Ключ картки: рід і сталий id з її тіла. Правка картки — це новий лист того
 * самого ключа (дріт уже везе картки; окремого кадру «правки картки» немає),
 * і стрічка показує ОДНУ картку на місці першої з найсвіжішим вмістом.
 */
/** Дошка й план-графік малюються WorkCard, але правляться так само: новою версією ключа. */
const FOLD_KINDS: ReadonlySet<string> = new Set([...LIVE_CARD_KINDS, 'widget:kanban', 'widget:timeline']);

export function cardKey(msg: Message): string | null {
  if (!FOLD_KINDS.has(msg.type) || msg.isDeleted) return null;
  const data =
    msg.familyChecklistData ?? msg.votingData ?? msg.familyExpenseData ?? msg.tableData ?? msg.kanbanData ?? msg.timelineData ?? null;
  const id = data && typeof data === 'object' ? (data as { id?: unknown }).id : null;
  return typeof id === 'string' && id ? `${msg.type}:${id}` : null;
}

export interface CardFold {
  /** Пізніші версії — їх рядки не малюються. */
  later: ReadonlySet<string>;
  /** id першої версії → лист із найсвіжішим (для опитування — злитим) вмістом. */
  shown: ReadonlyMap<string, Message>;
}

/**
 * Голос кожної людини — з ЇЇ найсвіжішої версії опитування. Так два
 * одночасні голоси не затирають один одного: кожен пише лише свій.
 * Решта карток — остання версія (хто останній правив, той і правий).
 */
function mergePoll(versions: Message[]): Message {
  // Закрите автором — закрите назавжди: голоси після закриття не входять.
  const author = versions[0].senderId;
  const shut = versions.findIndex((v) => v.senderId === author && v.votingData?.isClosed);
  const live = shut >= 0 ? versions.slice(0, shut + 1) : versions;
  const latest = live[live.length - 1];
  const optionsOf = (m: Message) => list(m.votingData?.options);
  // У вікторині голос остаточний і для злиття: діє перша версія з голосом людини.
  const quiz = !!versions[0].votingData?.correctOptionId;
  const anon = !!versions[0].votingData?.anonymous;
  const picked = (m: Message, id: string): string[] | null =>
    anon && m.senderId === id && Array.isArray(m.votingData?.pick) ? m.votingData!.pick!.map(String) : null;
  const votedIn = (m: Message, id: string) =>
    (picked(m, id)?.length ?? 0) > 0 || optionsOf(m).some((o) => list(o.voters).includes(id));
  const byAuthor = new Map<string, Message>();
  for (const v of live) {
    if (!v.senderId) continue;
    const had = byAuthor.get(v.senderId);
    if (quiz && had && votedIn(had, v.senderId)) continue;
    byAuthor.set(v.senderId, v);
  }
  const all = new Set(live.flatMap((v) => [...optionsOf(v).flatMap((x) => list(x.voters)), ...(picked(v, v.senderId) ? [v.senderId] : [])]));
  const merged = optionsOf(latest).map((o) => {
    const voters = new Set<string>();
    for (const voter of all) {
      const own = byAuthor.get(voter) ?? latest;
      const pick = picked(own, voter);
      const theirs = optionsOf(own).find((x) => x.id === o.id);
      if (pick ? pick.includes(o.id) : theirs && list(theirs.voters).includes(voter)) voters.add(voter);
    }
    return { ...o, voters: [...voters], votes: voters.size };
  });
  // Питання й правила — від автора, з першої версії: чужий лист їх не міняє.
  const first = versions[0].votingData!;
  return {
    ...latest,
    votingData: {
      ...latest.votingData!,
      question: first.question,
      allowMultiple: first.allowMultiple,
      anonymous: first.anonymous,
      correctOptionId: first.correctOptionId,
      isClosed: shut >= 0 ? true : undefined,
      pick: undefined,
      options: merged,
      totalVotes: merged.reduce((s, o) => s + o.votes, 0),
    },
  };
}

/**
 * Склад списку — з найсвіжішої версії автора (лише він додає й прибирає
 * пункти); галочка кожного пункту — з найсвіжішого дотику будь-кого.
 */
function mergeChecklist(versions: Message[]): Message {
  const author = versions[0].senderId;
  const own = versions.filter((v) => v.senderId === author);
  const shape = own[own.length - 1] ?? versions[0];
  const newest = new Map<string, FamilyChecklistItem>();
  for (const v of versions) {
    for (const it of list(v.familyChecklistData?.items)) {
      const was = newest.get(it.id);
      if (!was || (it.at ?? 0) >= (was.at ?? 0)) newest.set(it.id, it);
    }
  }
  const latest = versions[versions.length - 1];
  return {
    ...latest,
    familyChecklistData: {
      ...shape.familyChecklistData!,
      items: list(shape.familyChecklistData?.items).map((it) => {
        const n = newest.get(it.id) ?? it;
        return { ...it, done: n.done, doneBy: n.doneBy, at: n.at };
      }),
    },
  };
}

/** Найсвіжіший дотик кожного пункту серед усіх версій. */
function newestTouch<T extends { id: string; at?: number }, C>(versions: Message[], each: (v: Message) => Array<[T, C]>) {
  const newest = new Map<string, { it: T; ctx: C }>();
  for (const v of versions) {
    for (const [it, ctx] of each(v)) {
      if (typeof it.id !== 'string' || !it.id) continue;
      const was = newest.get(it.id);
      if (!was || (it.at ?? 0) >= (was.it.at ?? 0)) newest.set(it.id, { it, ctx });
    }
  }
  return newest;
}

/** Стовпчики й пункти — від автора; стовпчик пункту — з найсвіжішого дотику будь-кого. */
function mergeKanban(versions: Message[]): Message {
  const author = versions[0].senderId;
  const own = versions.filter((v) => v.senderId === author);
  const shape = (own[own.length - 1] ?? versions[0]).kanbanData!;
  const newest = newestTouch(versions, (v) =>
    list(v.kanbanData?.columns).flatMap((c) => list(c.items).map((it) => [it, c.id] as [KanbanCard, string])),
  );
  const cols = list(shape.columns);
  const known = new Set(cols.map((c) => c.id));
  const placed = new Map<string, KanbanCard[]>(cols.map((c) => [c.id, []]));
  for (const c of cols) {
    for (const it of list(c.items)) {
      const n = newest.get(it.id);
      const target = n && known.has(n.ctx) ? n.ctx : c.id;
      placed.get(target)!.push(n ? { ...it, at: n.it.at, by: n.it.by } : it);
    }
  }
  const latest = versions[versions.length - 1];
  const first = versions[0].kanbanData!;
  const order = first.order ?? list(first.columns).flatMap((c) => list(c.items).map((it) => it.id));
  return { ...latest, kanbanData: { ...shape, order, columns: cols.map((c) => ({ ...c, items: placed.get(c.id)! })) } };
}

/** Кроки — від автора; стан і поступ кроку — з найсвіжішого дотику будь-кого. */
function mergeTimeline(versions: Message[]): Message {
  const author = versions[0].senderId;
  const own = versions.filter((v) => v.senderId === author);
  const shape = (own[own.length - 1] ?? versions[0]).timelineData!;
  const newest = newestTouch(versions, (v) => list(v.timelineData?.milestones).map((s) => [s, null] as [TimelineMilestone, null]));
  const latest = versions[versions.length - 1];
  return {
    ...latest,
    timelineData: {
      ...shape,
      milestones: list(shape.milestones).map((s) => {
        const n = newest.get(s.id)?.it;
        return n ? { ...s, status: n.status, progress: n.progress, at: n.at, by: n.by } : s;
      }),
    },
  };
}

export function foldCards(messages: Message[]): CardFold {
  const groups = new Map<string, Message[]>();
  for (const m of messages) {
    const key = m ? cardKey(m) : null;
    if (!key) continue;
    const g = groups.get(key);
    if (g) g.push(m);
    else groups.set(key, [m]);
  }
  const later = new Set<string>();
  const shown = new Map<string, Message>();
  for (const all of groups.values()) {
    if (all.length < 2) continue;
    const first = all[0];
    all.slice(1).forEach((v) => later.add(v.id));
    // Невдала правка не входить у вміст: її стану в іншої людини немає.
    const versions = [first, ...all.slice(1).filter((v) => v.status !== 'failed')];
    const latest = versions[versions.length - 1];
    const content =
      latest.type === 'widget:voting'
        ? mergePoll(versions)
        : latest.type === 'widget:family-checklist'
          ? mergeChecklist(versions)
          : latest.type === 'widget:kanban'
            ? mergeKanban(versions)
            : latest.type === 'widget:timeline'
              ? mergeTimeline(versions)
              : latest;
    // Місце, час і автор — першої версії; вміст — найсвіжіший.
    shown.set(first.id, {
      ...content,
      id: first.id,
      senderId: first.senderId,
      senderName: first.senderName,
      senderAvatar: first.senderAvatar,
      timestamp: first.timestamp,
      sentAt: first.sentAt,
      isSelf: first.isSelf,
      status: first.status,
    });
  }
  return { later, shown };
}
