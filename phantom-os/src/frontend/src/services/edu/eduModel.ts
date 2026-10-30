/**
 * Навчання з журналу групи. Окремого сховища немає: клас, завдання, здачі,
 * присутність і теми форуму — це листи групи, прочитані тут. Вчитель класу —
 * автор першого листа «клас»; завдання й присутність від інших ігноруються.
 */
import type { Chat } from '../../types/messenger';
import { parseEdu, type EduEvent } from './eduWire';

export interface EduItem {
  ev: EduEvent;
  body: string;
  senderId: string;
  senderName: string;
  msgId: string;
  /** Коли лист реально надіслано (з вузла); null — часу немає. */
  sentAt: number | null;
}

export interface ClassInfo {
  name: string;
  subject: string;
  teacherId: string;
  teacherName: string;
  isTeacher: boolean;
}

export interface Student {
  id: string;
  name: string;
  avatar: string;
}

export type DoneState = 'done' | 'late' | 'missing';

export interface AssignRow extends Student {
  state: DoneState;
  at?: number;
}

export interface AssignView {
  id: string;
  title: string;
  due: number | null;
  note: string;
  at: number;
  rows: AssignRow[];
  done: number;
  late: number;
  missing: number;
  /** Моя здача (для учня): стан мого рядка. */
  mine: DoneState | null;
}

export interface AttendView {
  id: string;
  lesson: string;
  at: number;
  present: string[];
  late: string[];
  absent: string[];
  unmarked: number;
  total: number;
}

export interface ReplyView {
  id: string;
  authorId: string;
  author: string;
  text: string;
  at: number;
  answer: boolean;
}

export interface TopicView {
  id: string;
  title: string;
  body: string;
  authorId: string;
  author: string;
  at: number;
  replies: ReplyView[];
  solved: boolean;
  lastAt: number;
}

export function eduItems(chat: Chat, meId: string): EduItem[] {
  const out: EduItem[] = [];
  for (const m of chat.messages) {
    const p = parseEdu(m.text);
    if (!p) continue;
    out.push({
      ev: p.ev,
      body: p.body,
      senderId: m.isSelf ? meId : m.senderId,
      senderName: m.senderName,
      msgId: m.id,
      sentAt: m.sentAt && Number.isFinite(Date.parse(m.sentAt)) ? Date.parse(m.sentAt) : null,
    });
  }
  return out;
}

export function classOf(items: EduItem[], meId: string): ClassInfo | null {
  const first = items.find((i) => i.ev.k === 'class');
  if (!first || first.ev.k !== 'class') return null;
  return {
    name: first.ev.n,
    subject: first.ev.s,
    teacherId: first.senderId,
    teacherName: first.senderName,
    isTeacher: first.senderId === meId,
  };
}

export const isClassChat = (chat: Chat, meId: string): boolean => classOf(eduItems(chat, meId), meId) !== null;

/** Учні: склад групи без вчителя. Себе додаємо, якщо вузол складу не віддав. */
export function studentsOf(chat: Chat, cls: ClassInfo, meId: string, meName: string, meAvatar: string): Student[] {
  const list: Student[] = (chat.members ?? [])
    .filter((m) => m.id !== cls.teacherId)
    .map((m) => ({ id: m.id, name: m.name, avatar: m.avatar }));
  if (!cls.isTeacher && !list.some((s) => s.id === meId)) list.push({ id: meId, name: meName, avatar: meAvatar });
  return list;
}

export function assignmentsOf(items: EduItem[], cls: ClassInfo, students: Student[], meId: string): AssignView[] {
  const views: AssignView[] = [];
  for (const it of items) {
    if (it.ev.k !== 'assign' || it.senderId !== cls.teacherId) continue;
    const ev = it.ev;
    const doneAt = new Map<string, number | null>();
    for (const d of items) {
      if (d.ev.k === 'done' && d.ev.a === ev.id && !doneAt.has(d.senderId)) doneAt.set(d.senderId, d.sentAt);
    }
    const rows: AssignRow[] = students.map((s) => {
      const at = doneAt.get(s.id);
      if (at === undefined) return { ...s, state: 'missing' as const };
      // Час листа, не слово відправника: без нього «пізно» не вгадуємо.
      return { ...s, state: ev.due !== null && at !== null && at > ev.due ? ('late' as const) : ('done' as const), ...(at !== null ? { at } : {}) };
    });
    views.push({
      id: ev.id,
      title: ev.t,
      due: ev.due,
      note: ev.note,
      at: ev.at,
      rows,
      done: rows.filter((r) => r.state === 'done').length,
      late: rows.filter((r) => r.state === 'late').length,
      missing: rows.filter((r) => r.state === 'missing').length,
      mine: rows.find((r) => r.id === meId)?.state ?? null,
    });
  }
  return views.sort((a, b) => b.at - a.at);
}

export function attendanceOf(items: EduItem[], cls: ClassInfo, students: Student[]): AttendView[] {
  const views: AttendView[] = [];
  const seen = new Set<string>();
  for (const it of items) {
    if (it.ev.k !== 'attend' || it.senderId !== cls.teacherId || seen.has(it.ev.id)) continue;
    seen.add(it.ev.id);
    const ev = it.ev;
    const marked = new Set([...ev.p, ...ev.l, ...ev.x]);
    views.push({
      id: ev.id,
      lesson: ev.lesson,
      at: ev.at,
      present: ev.p,
      late: ev.l,
      absent: ev.x,
      unmarked: students.filter((s) => !marked.has(s.id)).length,
      total: students.length,
    });
  }
  return views.sort((a, b) => b.at - a.at);
}

export function topicsOf(items: EduItem[], cls: ClassInfo): TopicView[] {
  const byId = new Map<string, TopicView>();
  for (const it of items) {
    if (it.ev.k !== 'topic' || byId.has(it.ev.id)) continue;
    byId.set(it.ev.id, {
      id: it.ev.id,
      title: it.ev.t,
      body: it.body,
      authorId: it.senderId,
      author: it.senderName,
      at: it.ev.at,
      replies: [],
      solved: false,
      lastAt: it.ev.at,
    });
  }
  const seenReply = new Set<string>();
  for (const it of items) {
    if (it.ev.k !== 'reply' || seenReply.has(it.ev.id)) continue;
    const tp = byId.get(it.ev.tp);
    if (!tp) continue;
    seenReply.add(it.ev.id);
    tp.replies.push({
      id: it.ev.id,
      authorId: it.senderId,
      author: it.senderName,
      text: it.body,
      at: it.ev.at,
      answer: false,
    });
    tp.lastAt = Math.max(tp.lastAt, it.ev.at);
  }
  for (const it of items) {
    if (it.ev.k !== 'answer') continue;
    const tp = byId.get(it.ev.tp);
    if (!tp || (it.senderId !== cls.teacherId && it.senderId !== tp.authorId)) continue;
    const r = tp.replies.find((x) => x.id === (it.ev as Extract<EduEvent, { k: 'answer' }>).r);
    if (!r) continue;
    tp.replies.forEach((x) => (x.answer = x.id === r.id));
    tp.solved = true;
  }
  return [...byId.values()].sort((a, b) => b.lastAt - a.lastAt);
}
