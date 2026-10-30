import { tNow } from '../../components/chat/tNow';
import { UNNAMED } from '../../components/messenger/chatListFormat';
/**
 * Навчання: класи, завдання, присутність і форум їдуть звичайним текстовим
 * листом групи. Перші рядки — читабельний запасний текст (старий застосунок
 * показує саме його), останній рядок — мітка з даними. Нового роду кадру
 * дріт не знає, тож жодна відмова вузла ("kind_not_carried") тут неможлива.
 */

export type EduEvent =
  | { k: 'class'; n: string; s: string; at: number }
  | { k: 'assign'; id: string; t: string; due: number | null; note: string; at: number }
  | { k: 'done'; a: string; t: string; at: number }
  | { k: 'attend'; id: string; lesson: string; p: string[]; l: string[]; x: string[]; at: number }
  | { k: 'topic'; id: string; t: string; at: number }
  | { k: 'reply'; id: string; tp: string; at: number }
  | { k: 'answer'; tp: string; r: string; at: number };

export type EduKind = EduEvent['k'];

const TAG = /^⟦edu1:([A-Za-z0-9_-]+)⟧$/;
const MAX_TITLE = 120;
const MAX_NOTE = 600;
const MAX_IDS = 500;

const b64urlEncode = (s: string): string => {
  const bytes = new TextEncoder().encode(s);
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
};

const b64urlDecode = (s: string): string => {
  const pad = s.length % 4 === 0 ? '' : '='.repeat(4 - (s.length % 4));
  const bin = atob(s.replace(/-/g, '+').replace(/_/g, '/') + pad);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return new TextDecoder().decode(bytes);
};

export const eduTag = (ev: EduEvent): string => `⟦edu1:${b64urlEncode(JSON.stringify(ev))}⟧`;

/** Лист для групи: читабельні рядки, потім мітка. */
export const composeEdu = (lines: string[], ev: EduEvent): string =>
  [...lines.filter((l) => l.trim() !== ''), eduTag(ev)].join('\n');

const str = (v: unknown, max: number): string | null =>
  typeof v === 'string' && v.length <= max ? v : null;
const num = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : null);
const ids = (v: unknown): string[] | null =>
  Array.isArray(v) && v.length <= MAX_IDS && v.every((x) => typeof x === 'string' && x.length <= 128)
    ? (v as string[])
    : null;

function validate(raw: unknown): EduEvent | null {
  if (!raw || typeof raw !== 'object') return null;
  const o = raw as Record<string, unknown>;
  const at = num(o.at);
  if (at === null) return null;
  switch (o.k) {
    case 'class': {
      const n = str(o.n, MAX_TITLE);
      const s = str(o.s ?? '', MAX_TITLE);
      return n && n.trim() && s !== null ? { k: 'class', n, s, at } : null;
    }
    case 'assign': {
      const id = str(o.id, 64);
      const t = str(o.t, MAX_TITLE);
      const note = str(o.note ?? '', MAX_NOTE);
      const due = o.due === null || o.due === undefined ? null : num(o.due);
      return id && t && t.trim() && note !== null && (o.due == null || due !== null)
        ? { k: 'assign', id, t, due, note, at }
        : null;
    }
    case 'done': {
      const a = str(o.a, 64);
      const t = str(o.t ?? '', MAX_TITLE);
      return a && t !== null ? { k: 'done', a, t, at } : null;
    }
    case 'attend': {
      const id = str(o.id, 64);
      const lesson = str(o.lesson ?? '', MAX_TITLE);
      const p = ids(o.p);
      const l = ids(o.l);
      const x = ids(o.x);
      return id && lesson !== null && p && l && x ? { k: 'attend', id, lesson, p, l, x, at } : null;
    }
    case 'topic': {
      const id = str(o.id, 64);
      const t = str(o.t, MAX_TITLE);
      return id && t && t.trim() ? { k: 'topic', id, t, at } : null;
    }
    case 'reply': {
      const id = str(o.id, 64);
      const tp = str(o.tp, 64);
      return id && tp ? { k: 'reply', id, tp, at } : null;
    }
    case 'answer': {
      const tp = str(o.tp, 64);
      const r = str(o.r, 64);
      return tp && r ? { k: 'answer', tp, r, at } : null;
    }
    default:
      return null;
  }
}

export interface ParsedEdu {
  ev: EduEvent;
  /** Рядки між читабельним заголовком і міткою: текст теми чи відповіді. */
  body: string;
}

export function parseEdu(text: string | undefined | null): ParsedEdu | null {
  if (!text || !text.includes('⟦edu1:')) return null;
  const lines = text.split('\n');
  while (lines.length && lines[lines.length - 1].trim() === '') lines.pop();
  const m = TAG.exec(lines[lines.length - 1]?.trim() ?? '');
  if (!m) return null;
  let ev: EduEvent | null = null;
  try {
    ev = validate(JSON.parse(b64urlDecode(m[1])));
  } catch {
    return null;
  }
  if (!ev) return null;
  return { ev, body: lines.slice(1, -1).join('\n').trim() };
}

/** Текст листа без мітки — для рядка списку розмов і пошуку. */
export function eduReadable(text: string | undefined | null): string {
  if (!text || !text.includes('⟦edu1:')) return text ?? '';
  const parsed = parseEdu(text);
  if (!parsed) return text;
  return text.split('\n').slice(0, -1).join('\n').trim();
}

/** Рядок для списку розмов: перший читабельний рядок, мітка (навіть обрізана) не видна. */
export function eduPreview(text: string): string {
  const at = text.indexOf('⟦edu1:');
  if (at < 0) return text;
  const ev = parseEdu(text)?.ev;
  switch (ev?.k) {
    case 'class':
      return tNow('edu.say.class', { name: ev.n === UNNAMED ? ev.s || tNow('chatList.unnamed') : ev.n });
    case 'assign':
      return tNow('edu.say.assign', { title: ev.t });
    case 'done':
      return tNow('edu.say.done', { title: ev.t });
    case 'topic':
      return tNow('edu.say.topic', { title: ev.t });
    default:
      return text.slice(0, at).split('\n')[0].trim();
  }
}

/** Увесь читабельний текст листа без мітки (цитата, пошук, превʼю на ПК). */
export function eduStrip(text: string): string {
  const at = text.indexOf('⟦edu1:');
  return at < 0 ? text : text.slice(0, at).trim();
}
