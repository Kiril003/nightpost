/**
 * Дошка, ролі, план-графік і навчальні картки: що в них є, словами й рядками.
 * Без пунктів — null, і стрічка лишає рядок TheatreCard («не показується»).
 */
import type { Message } from '../../../types/messenger';
import { currentDateTag, tNow, tPlural } from '../../chat/tNow';

export const arr = <T,>(v: T[] | undefined | null): T[] => (Array.isArray(v) ? v.filter((x) => x && typeof x === 'object') : []);
export const str = (v: unknown): string => (typeof v === 'string' ? v.trim() : '');

export interface Row {
  id: string;
  title: string;
  done?: boolean;
  side?: string;
  /** Пункт можна відмітити: у нього сталий id, а картка має ключ і їде правкою. */
  live?: boolean;
}

export interface Work {
  kind: 'board' | 'roles' | 'plan' | 'study';
  title: string;
  status: string;
  rows: Row[];
}

const doneOf = (done: number, total: number) => tNow('messageCard.fact.done', { done, total });
const tasks = (n: number) => `${n} ${tPlural(n, 'messageCard.fact.tasks')}`;

const dayOf = (iso: string): string => {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleDateString(currentDateTag(), { day: 'numeric', month: 'short' });
};

/** Вміст роду, або null — показати нема що. */
export function workOf(msg: Message): Work | null {
  if (msg.isDeleted) return null;
  switch (msg.type) {
    case 'widget:kanban': {
      const cols = arr(msg.kanbanData?.columns);
      // Остання колонка дошки з кількох — «зроблено»; її назву видно в кожному рядку.
      const rows = cols.flatMap((c, ci) =>
        arr(c.items).map((it, i) => ({
          id: str(it.id) || `${ci}-${i}`,
          title: str(it.title),
          done: cols.length > 1 && ci === cols.length - 1,
          side: str(c.title),
          live: cols.length > 1 && !!str(it.id) && !!str(msg.kanbanData?.id),
        })),
      ).filter((r) => r.title);
      if (!rows.length) return null;
      const order = Array.isArray(msg.kanbanData?.order) ? msg.kanbanData!.order : [];
      const at = (id: string) => (order.includes(id) ? order.indexOf(id) : order.length);
      rows.sort((a, b) => at(a.id) - at(b.id));
      const done = rows.filter((r) => r.done).length;
      return {
        kind: 'board',
        title: str(msg.kanbanData?.title) || tNow('messageCard.theatre.widget_kanban'),
        status: cols.length > 1 ? doneOf(done, rows.length) : tasks(rows.length),
        rows,
      };
    }
    case 'widget:raci': {
      const rows = arr(msg.raciData?.rows)
        .map((r, i) => ({ id: str(r.id) || String(i), title: str(r.task), side: str(r.r) }))
        .filter((r) => r.title);
      if (!rows.length) return null;
      return { kind: 'roles', title: str(msg.raciData?.title) || tNow('messageCard.theatre.widget_raci'), status: tasks(rows.length), rows };
    }
    case 'widget:timeline': {
      const rows = arr(msg.timelineData?.milestones)
        .map((m, i) => {
          const progress = Math.max(0, Math.min(100, Math.round(Number(m.progress) || 0)));
          const side =
            m.status === 'blocked'
              ? tNow('work.blocked')
              : m.status === 'in_progress'
                ? `${progress} %`
                : m.status === 'completed'
                  ? ''
                  : dayOf(str(m.dueDate));
          const live = !!str(m.id) && !!str(msg.timelineData?.id);
          return { id: str(m.id) || String(i), title: str(m.title), done: m.status === 'completed', side, live };
        })
        .filter((r) => r.title);
      if (!rows.length) return null;
      return {
        kind: 'plan',
        title: str(msg.timelineData?.title) || tNow('messageCard.theatre.widget_timeline'),
        status: doneOf(rows.filter((r) => r.done).length, rows.length),
        rows,
      };
    }
    case 'widget:study-flashcards': {
      const rows = arr(msg.studyFlashcardsData?.cards)
        .map((c, i) => ({ id: str(c.id) || String(i), title: str(c.question), side: str(c.answer) }))
        .filter((r) => r.title);
      if (!rows.length) return null;
      return { kind: 'study', title: str(msg.studyFlashcardsData?.topic) || tNow('messageCard.theatre.widget_study_flashcards'), status: '', rows };
    }
    default:
      return null;
  }
}

export const isWorkCard = (msg: Message): boolean => workOf(msg) !== null;


/**
 * Дотик до пункту: дошка переносить його в останній стовпчик («зроблено») і
 * назад у перший; план-графік ставить кроку «готово» (100 %) і знімає (0 %).
 * Кожен дотик позначено часом і людиною — так злиття версій бере найсвіжіший.
 */
export function toggleWork(msg: Message, id: string, by: string, now = Date.now()): Partial<Message> | null {
  if (msg.type === 'widget:kanban' && msg.kanbanData) {
    const cols = arr(msg.kanbanData.columns);
    if (cols.length < 2) return null;
    const from = cols.findIndex((c) => arr(c.items).some((it) => it.id === id));
    if (from < 0) return null;
    const item = arr(cols[from].items).find((it) => it.id === id)!;
    const to = from === cols.length - 1 ? 0 : cols.length - 1;
    const moved = { ...item, at: now, by };
    const order = msg.kanbanData.order ?? cols.flatMap((c) => arr(c.items).map((it) => it.id));
    return {
      kanbanData: {
        ...msg.kanbanData,
        order,
        columns: cols.map((c, ci) => ({
          ...c,
          items: ci === from ? arr(c.items).filter((it) => it.id !== id) : ci === to ? [...arr(c.items), moved] : arr(c.items),
        })),
      },
    };
  }
  if (msg.type === 'widget:timeline' && msg.timelineData) {
    const ms = arr(msg.timelineData.milestones);
    if (!ms.some((s) => s.id === id)) return null;
    return {
      timelineData: {
        ...msg.timelineData,
        milestones: ms.map((s) =>
          s.id !== id
            ? s
            : s.status === 'completed'
              ? { ...s, status: 'pending' as const, progress: 0, at: now, by }
              : { ...s, status: 'completed' as const, progress: 100, at: now, by },
        ),
      },
    };
  }
  return null;
}
