/**
 * «Між вами» і «Памʼятати» в профілі людини — лише з того, що вже лежить на
 * телефоні: чек-листи з розмови та памʼять PHANTOM. Нічого не вгадуємо.
 */
import { currentDateTag, tNow, tPlural } from '../components/chat/tNow';
import type { BillToken } from '../types/meetup';
import type { Message, TaskItem } from '../types/messenger';
import type { ChatLedger } from './money/ledger';
import type { KnownFact } from './phantomSelfApi';
import type { SharedIndex } from './peerShared';
import { momentHighlights } from './moments';

export interface OpenTask {
  key: string;
  msgId: string;
  title: string;
  /** 'self' — ваша справа; рядок — імʼя виконавця; null — нічия. */
  who: 'self' | string | null;
  due: string | null;
  dueAt: number | null;
}

export interface BalanceLine {
  token: BillToken;
  units: bigint;
  dir: 'owe' | 'owed';
  pending: boolean;
}

export interface Moment {
  id: string;
  authorId?: string;
  peerId?: string;
  participants?: string[];
  createdAt?: number | string;
  at?: number | string;
}

const SELF_WORDS = /^(я|ви|мені|вам|мене)$/i;

const firstWord = (name: string): string => name.trim().split(/\s+/)[0] ?? '';

const same = (a: string, b: string): boolean => a.localeCompare(b, 'uk', { sensitivity: 'base' }) === 0;

function whoOf(assignee: string | undefined, selfName: string, peerName: string): OpenTask['who'] {
  const a = (assignee ?? '').trim();
  if (!a) return null;
  if (SELF_WORDS.test(a) || (selfName && same(a, selfName.trim()))) return 'self';
  if (same(a, peerName.trim()) || same(firstWord(a), firstWord(peerName))) return firstWord(peerName);
  return a;
}

function dueTime(due: string | undefined): number | null {
  const raw = (due ?? '').trim();
  if (!/^\d{4}-\d{2}-\d{2}/.test(raw)) return null;
  const ms = new Date(raw).getTime();
  return Number.isNaN(ms) ? null : ms;
}

/** Строк людськими словами; незрозумілий рядок лишаємо як його написали. */
export function dueWords(due: string | undefined, now = new Date()): string | null {
  const raw = (due ?? '').trim();
  if (!raw) return null;
  if (!/^\d{4}-\d{2}-\d{2}/.test(raw)) return raw;
  const t = new Date(raw);
  if (Number.isNaN(t.getTime())) return raw;
  const hasTime = /T\d{2}:\d{2}/.test(raw);
  const tag = currentDateTag();
  const time = hasTime ? t.toLocaleTimeString(tag, { hour: '2-digit', minute: '2-digit' }) : '';
  const day = new Date(t.getFullYear(), t.getMonth(), t.getDate()).getTime();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const diff = Math.round((day - today) / 86_400_000);
  const dayWord =
    diff === 0 ? tNow('between.today') : diff === 1 ? tNow('between.tomorrow') : t.toLocaleDateString(tag, { day: 'numeric', month: 'long' });
  if (time) return tNow('between.dayBy', { day: dayWord, time });
  return diff === 0 ? tNow('between.today') : tNow('between.by', { day: dayWord });
}

/** Невиконані пункти чек-листів цієї розмови, найсвіжіші — першими. */
export function openTasksIn(messages: Message[], selfName: string, peerName: string): OpenTask[] {
  const out: OpenTask[] = [];
  for (let i = messages.length - 1; i >= 0; i -= 1) {
    const m = messages[i];
    if (!m || m.isDeleted) continue;
    // Список справ, що справді їде дротом, — `widget:family-checklist`;
    // старий `task-list` лишається читатись.
    const tasks =
      m.type === 'task-list' && m.taskListData
        ? m.taskListData.tasks ?? []
        : m.type === 'widget:family-checklist' && m.familyChecklistData
          ? (m.familyChecklistData.items ?? []).map((i) => ({ id: i.id, title: i.text, completed: i.done }) as TaskItem)
          : null;
    if (!tasks) continue;
    for (const t of tasks) {
      const title = (t.title ?? '').trim();
      if (t.completed || !title) continue;
      out.push({
        key: `${m.id}:${t.id}`,
        msgId: m.id,
        title,
        who: whoOf(t.assigneeName, selfName, peerName),
        due: dueWords(t.dueDate),
        dueAt: dueTime(t.dueDate),
      });
    }
  }
  return out;
}

/** Найближча відкрита справа з датою від сьогодні. */
export function nextPlanIn(tasks: OpenTask[], now = Date.now()): OpenTask | null {
  const d = new Date(now);
  const startOfToday = Math.min(
    new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime(),
    Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()),
  );
  let best: OpenTask | null = null;
  for (const t of tasks) {
    if (t.dueAt === null || Number.isNaN(t.dueAt) || t.dueAt < startOfToday) continue;
    if (!best || t.dueAt < (best.dueAt as number)) {
      best = t;
    }
  }
  return best;
}

export function balanceWith(
  ledger: { transfers: ReadonlyArray<Pick<ChatLedger['transfers'][number], 'from' | 'to' | 'token' | 'units' | 'state'>> },
  meId: string,
  peerId: string,
): BalanceLine[] {
  if (!meId || !peerId || meId === peerId) return [];
  const out: BalanceLine[] = [];
  const tokens: BillToken[] = ['USDC', 'SOL'];
  for (const token of tokens) {
    for (const pending of [false, true]) {
      let net = 0n;
      for (const tr of ledger.transfers) {
        if (tr.state === 'settled' || tr.token !== token) continue;
        if ((tr.state === 'pending') !== pending) continue;
        if (tr.from === meId && tr.to === peerId) {
          net += tr.units;
        } else if (tr.from === peerId && tr.to === meId) {
          net -= tr.units;
        }
      }
      if (net > 0n) {
        out.push({ token, units: net, dir: 'owe', pending });
      } else if (net < 0n) {
        out.push({ token, units: -net, dir: 'owed', pending });
      }
    }
  }
  return out;
}

export function unitsText(units: bigint, token: BillToken): string {
  const decimals = token === 'SOL' ? 9 : 6;
  const abs = units < 0n ? -units : units;
  const base = 10n ** BigInt(decimals);
  const whole = abs / base;
  const frac = abs % base;
  const sign = units < 0n ? '-' : '';
  if (frac === 0n) return `${sign}${whole} ${token}`;
  const fracStr = frac.toString().padStart(decimals, '0').replace(/0+$/, '');
  return `${sign}${whole}.${fracStr} ${token}`;
}

function momentTime(m: Moment): number {
  const raw = m.createdAt ?? m.at ?? 0;
  if (typeof raw === 'number') return Number.isFinite(raw) ? raw : 0;
  const ms = Date.parse(String(raw));
  return Number.isNaN(ms) ? 0 : ms;
}

function momentHas(m: Moment, id: string): boolean {
  if (!id) return false;
  if (m.authorId === id || m.peerId === id) return true;
  return Array.isArray(m.participants) && m.participants.includes(id);
}

export function sharedMomentsWith<T extends Moment>(moments: readonly T[], selfId: string, peerId: string): T[] {
  if (!selfId || !peerId) return [];
  return moments
    .filter((m) => momentHas(m, selfId) && momentHas(m, peerId))
    .sort((a, b) => momentTime(b) - momentTime(a));
}

export async function loadMoments(peerId?: string, selfId?: string): Promise<Moment[]> {
  if (!peerId) return [];
  const rows = await momentHighlights(peerId);
  return rows.map((m) => ({
    id: m.id,
    authorId: m.fromId || peerId,
    participants: selfId ? [selfId, peerId] : [peerId],
    createdAt: m.atMs,
  }));
}

export function taskMeta(task: OpenTask): string {
  const who = task.who === 'self' ? tNow('between.you') : task.who;
  return [who, task.due].filter(Boolean).join(' · ');
}

/**
 * Основи слів імені: «Оксана» ловить і «Оксани», «Оксані», «Оксаною».
 * Коротке слово (до 4 літер) — лише ціле, інакше «Ян» ловив би «Янтар».
 */
function nameStems(name: string): RegExp[] {
  return name
    .split(/\s+/)
    .map((w) => w.replace(/[^\p{L}ʼ'-]/gu, ''))
    .filter((w) => w.length >= 2)
    .map((w) => {
      const stem = w.length >= 5 ? w.slice(0, -1) : w;
      const tail = w.length >= 5 ? '\\p{L}{0,3}' : '';
      const safe = stem.replace(/[-']/g, '\\$&');
      return new RegExp(`(?<!\\p{L})${safe}${tail}(?!\\p{L})`, 'iu');
    });
}

/** Факти памʼяті, де згадано цю людину. Старі нотатки без людського речення — ні. */
export function factsAbout(facts: KnownFact[], name: string): KnownFact[] {
  const stems = nameStems(name);
  if (stems.length === 0) return [];
  return facts.filter((f) => !f.old && stems.some((re) => re.test(f.text)));
}

/** «12 фото · 3 місця» — лише непорожнє. */
export function sharedSummary(shared: SharedIndex): string {
  const parts: string[] = [];
  const n = (k: keyof SharedIndex) => shared[k].length;
  if (n('media')) parts.push(`${n('media')} ${tPlural(n('media'), 'plural.photo')}`);
  if (n('places')) parts.push(`${n('places')} ${tPlural(n('places'), 'plural.place')}`);
  if (n('files')) parts.push(`${n('files')} ${tPlural(n('files'), 'plural.file')}`);
  if (n('voice')) parts.push(`${n('voice')} ${tPlural(n('voice'), 'plural.voice')}`);
  if (n('links')) parts.push(`${n('links')} ${tPlural(n('links'), 'plural.link')}`);
  return parts.join(' · ');
}
