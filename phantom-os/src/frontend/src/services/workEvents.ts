/**
 * Робота в розмові: завдання, зміни й обміни як рядки з позначкою в кінці.
 *
 * Дріт не знає нових родів карток (рід — дзеркало `PeerCard.Card` у Kotlin), тож
 * подія їде звичайним листом: перший рядок читає людина без нашого застосунку,
 * останній — `[np:рід поле=значення]` — читає веб. Стан — не окреме сховище, а
 * згортка листів розмови: те, що бачить кожен пристрій, і є правда групи.
 */
import type { Chat, Message } from '../types/messenger';
import { currentDateTag, tNow } from '../components/chat/tNow';

export const TASK_STATUSES = ['todo', 'doing', 'review', 'done'] as const;
export type TaskStatus = (typeof TASK_STATUSES)[number];

type Fields = Record<string, string>;
const TAG = /\n?\[np:([a-z-]+)((?: [a-z]+=[^\s\]]*)*)\]\s*$/;

export function withTag(line: string, kind: string, f: Fields): string {
  const body = Object.entries(f)
    .filter(([, v]) => v !== '')
    .map(([k, v]) => ` ${k}=${encodeURIComponent(v)}`)
    .join('');
  return `${line.replace(/\s*\n\s*/g, ' ').trim()}\n[np:${kind}${body}]`;
}

export function parseTag(text: string | undefined): { kind: string; f: Fields; line: string } | null {
  if (!text) return null;
  const m = TAG.exec(text);
  if (!m) return null;
  const f: Fields = {};
  for (const part of m[2].trim().split(' ')) {
    const i = part.indexOf('=');
    if (i > 0) {
      try {
        f[part.slice(0, i)] = decodeURIComponent(part.slice(i + 1));
      } catch {
        f[part.slice(0, i)] = part.slice(i + 1);
      }
    }
  }
  return { kind: m[1], f, line: text.slice(0, m.index).trim() };
}

export const newId = (p: string): string => `${p}${Date.now().toString(36).slice(-4)}${Math.random().toString(36).slice(2, 5)}`;

export interface TaskSrc {
  id: string;
  author: string;
  text: string;
  at: string;
}
export interface WorkTask {
  id: string;
  title: string;
  creatorId: string;
  assigneeId: string;
  assigneeName: string;
  due: number | null;
  src: TaskSrc | null;
  status: TaskStatus;
  at: string;
}
export interface WorkShift {
  id: string;
  who: string;
  day: string;
  from: string;
  to: string;
  role: string;
}
export type SwapState = 'offered' | 'accepted' | 'applied' | 'declined' | 'void';
export interface WorkSwap {
  id: string;
  a: string;
  b: string;
  from: string;
  to: string;
  lead: string | null;
  state: SwapState;
  at: string;
}
export interface WorkState {
  tasks: WorkTask[];
  shifts: WorkShift[];
  swaps: WorkSwap[];
  names: Record<string, string>;
  canPlan: (id: string) => boolean;
  lead: string | null;
}

const isStatus = (s: string | undefined): s is TaskStatus => !!s && (TASK_STATUSES as readonly string[]).includes(s);
const HM = /^\d{2}:\d{2}$/;
const DAY = /^\d{4}-\d{2}-\d{2}$/;

/** Рядок задачі: без позначки — той, що бачить співрозмовник без застосунку. */
export function taskLine(title: string, name: string, due: string): string {
  return `☑ ${title.trim()} → ${name}${due ? ` · ${due}` : ''}`;
}

const titleOf = (line: string): string => {
  const body = line.replace(/^☑\s*/, '');
  const i = body.lastIndexOf(' → ');
  return (i > 0 ? body.slice(0, i) : body).trim();
};

/** «5102·2e11» — коротке id замість імені (peerDirectory.shortId). */
const isShortId = (n: string | undefined): boolean => !!n && /^[\p{L}\p{N}]{4}·[\p{L}\p{N}]{4}$/u.test(n.trim());

export function reduceWork(chat: Pick<Chat, 'messages' | 'members' | 'ownerId'> | null | undefined, meId: string, meName = ''): WorkState {
  const names: Record<string, string> = {};
  for (const m of chat?.members ?? []) names[m.id] = m.name;
  // Імʼя-заглушка «5102·2e11» (shortId) не перекриває справжнє: моє — з профілю, чуже — з підпису листа.
  if (meId && !(meId in names)) names[meId] = meName;
  else if (meId && meName && !isShortId(meName)) names[meId] = meName;
  const lead = chat?.ownerId ?? null;
  const roleOf = (id: string) => chat?.members?.find((m) => m.id === id)?.role;
  const canPlan = (id: string) => !lead || id === lead || (!!roleOf(id) && roleOf(id) !== 'member');

  const tasks = new Map<string, WorkTask>();
  const shifts = new Map<string, WorkShift>();
  const swaps = new Map<string, WorkSwap>();
  const msgs = chat?.messages ?? [];
  const byId = new Map(msgs.map((m) => [m.id, m]));

  for (const m of msgs) {
    if (m.isDeleted || m.status === 'failed') continue;
    const tag = parseTag(m.text);
    if (!tag) continue;
    const sender = m.isSelf ? meId : m.senderId;
    if (sender && (!names[sender] || isShortId(names[sender])) && m.senderName && !isShortId(m.senderName)) names[sender] = m.senderName;
    const { f } = tag;
    if (tag.kind === 'task' && f.id && f.to && !tasks.has(f.id)) {
      const srcMsg = f.src ? byId.get(f.src) : undefined;
      tasks.set(f.id, {
        id: f.id,
        title: titleOf(tag.line),
        creatorId: sender,
        assigneeId: f.to,
        assigneeName: '',
        due: f.due && Number.isFinite(Number(f.due)) ? Number(f.due) : null,
        src: srcMsg ? { id: srcMsg.id, author: srcMsg.senderName, text: srcMsg.text ?? '', at: srcMsg.timestamp } : null,
        status: 'todo',
        at: m.timestamp,
      });
    } else if (tag.kind === 'task-status' && f.id && isStatus(f.s)) {
      const t = tasks.get(f.id);
      if (t && (sender === t.creatorId || sender === t.assigneeId || sender === lead)) t.status = f.s;
    } else if (tag.kind === 'shift' && f.id && DAY.test(f.day ?? '') && HM.test(f.from ?? '') && HM.test(f.to ?? '')) {
      if (canPlan(sender)) shifts.set(f.id, { id: f.id, who: f.who && f.who !== '-' ? f.who : '', day: f.day, from: f.from, to: f.to, role: f.role ?? '' });
    } else if (tag.kind === 'take' && f.id) {
      const s = shifts.get(f.id);
      if (s && !s.who && (!f.who || f.who === sender)) s.who = sender;
    } else if (tag.kind === 'swap' && f.id && f.a && f.b && f.to) {
      const a = shifts.get(f.a);
      const b = shifts.get(f.b);
      if (a && b && a.who === sender && b.who === f.to && sender !== f.to && !swaps.has(f.id)) {
        const needLead = lead && lead !== sender && lead !== f.to ? lead : null;
        swaps.set(f.id, { id: f.id, a: f.a, b: f.b, from: sender, to: f.to, lead: needLead, state: 'offered', at: m.timestamp });
      }
    } else if (tag.kind === 'swap-ack' && f.id && f.s) {
      const w = swaps.get(f.id);
      if (!w || w.state === 'applied' || w.state === 'declined' || w.state === 'void') continue;
      if (f.s === 'declined' && (sender === w.to || sender === w.lead)) w.state = 'declined';
      else if ((f.s === 'accepted' && sender === w.to && w.state === 'offered') || (f.s === 'approved' && sender === w.lead && w.state === 'accepted')) {
        w.state = f.s === 'accepted' && w.lead ? 'accepted' : 'applied';
        if (w.state === 'applied') {
          const a = shifts.get(w.a);
          const b = shifts.get(w.b);
          if (a && b && a.who === w.from && b.who === w.to) {
            a.who = w.to;
            b.who = w.from;
          } else w.state = 'void';
        }
      }
    }
  }
  for (const t of tasks.values()) t.assigneeName = names[t.assigneeId] || t.assigneeId;
  return { tasks: [...tasks.values()], shifts: [...shifts.values()], swaps: [...swaps.values()], names, canPlan, lead };
}

export const shiftHours = (s: Pick<WorkShift, 'from' | 'to'>): number => {
  const mins = (v: string) => Number(v.slice(0, 2)) * 60 + Number(v.slice(3));
  let d = mins(s.to) - mins(s.from);
  if (d <= 0) d += 24 * 60;
  return d / 60;
};

const stamp = (day: string, hm: string): number => new Date(`${day}T${hm}:00`).getTime();

/** Години тижня й найкоротший відпочинок між змінами людини — з її справжніх змін, не вигадані. */
export function weekLoad(shifts: WorkShift[], who: string, weekDays: string[]): { hours: number; minRestH: number | null } {
  const mine = shifts.filter((s) => s.who === who && weekDays.includes(s.day)).sort((x, y) => stamp(x.day, x.from) - stamp(y.day, y.from));
  let rest: number | null = null;
  for (let i = 1; i < mine.length; i++) {
    const end = stamp(mine[i - 1].day, mine[i - 1].to) + (shiftHours(mine[i - 1]) > 0 && mine[i - 1].to <= mine[i - 1].from ? 86_400_000 : 0);
    const gap = (stamp(mine[i].day, mine[i].from) - end) / 3_600_000;
    rest = rest === null ? gap : Math.min(rest, gap);
  }
  return { hours: mine.reduce((n, s) => n + shiftHours(s), 0), minRestH: rest };
}

/** Після обміну: години і відпочинок людини, якщо `give` піде, а `get` прийде. */
export function loadAfterSwap(shifts: WorkShift[], who: string, give: WorkShift, get: WorkShift, weekDays: string[]) {
  const next = shifts.map((s) => (s.id === give.id ? { ...s, who: get.who } : s.id === get.id ? { ...s, who } : s));
  return weekLoad(next, who, weekDays);
}

export const ymd = (d: Date): string => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

export function weekOf(base: Date, offset = 0): string[] {
  const d = new Date(base.getFullYear(), base.getMonth(), base.getDate());
  d.setDate(d.getDate() - ((d.getDay() + 6) % 7) + offset * 7);
  return Array.from({ length: 7 }, (_, i) => {
    const x = new Date(d);
    x.setDate(d.getDate() + i);
    return ymd(x);
  });
}

export const isWorkGroup = (c: Pick<Chat, 'type' | 'circle'> | null | undefined): boolean =>
  !!c && (c.type === 'group' || c.type === 'channel') && c.circle === 'work';

export const workMessage = (m: Message): boolean => !!parseTag(m.text);

/** Людині в бульбашці — лише перший рядок: позначка читається вебом, не оком. */
const localShift = (name: string, f: Fields): string =>
  `${name} · ${new Date(`${f.day}T00:00`).toLocaleDateString(currentDateTag(), { weekday: 'short', day: 'numeric' })} · ${f.from}–${f.to}${f.role ? ` · ${f.role}` : ''}`;

/**
 * Рядок зміни мовою читача, а не відправника (T75): «Open shift · Mon 26» у
 * групі з англомовним телефоном. Поля позначки — правда; імʼя лишається як є.
 * Старі «Взяти» без полів дня показуються як прийшли.
 */
export function localWorkLine(tag: { kind: string; f: Fields; line: string }): string {
  const { kind, f, line } = tag;
  if (!DAY.test(f.day ?? '') || !HM.test(f.from ?? '') || !HM.test(f.to ?? '')) return line;
  if (kind === 'shift') return localShift(!f.who || f.who === '-' ? tNow('work.openShift') : line.split(' · ')[0], f);
  if (kind === 'take') {
    const at = line.indexOf(': ');
    return at > 0 ? `${tNow('work.take')}: ${localShift(line.slice(at + 2).split(' · ')[0], f)}` : line;
  }
  return line;
}

export const stripWorkTag = (text: string): string => {
  const tag = parseTag(text);
  return tag ? localWorkLine(tag) : text;
};
