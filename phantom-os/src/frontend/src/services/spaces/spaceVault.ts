import type { Space, SpaceEvent } from '@shared/types/space';
import type { ShiftSwap, WorkTask } from '@shared/types/work';
import { foldSpace, parseSpaceEvent } from './spaceLog';
import { mergeTask, parseTask } from '../work/taskFlow';
import { mergeSwap, parseSwap } from '../work/swapFlow';
import { stashForBackup } from '../backupStash';

/**
 * Сховище просторів і роботи на цьому пристрої — окреме для кожного профілю
 * (простір належить рівно одному профілю, 11-spaces §1). Ключі:
 * `np.spaces.v1.<profileId>` — журнали подій, `np.work.v1.<profileId>` —
 * задачі й обміни. Профіль видалено — видалено й їх.
 *
 * Тут лише локальний бік. Дріт (подія з групи → сюди) — картка 05.
 */

export const SPACES_PREFIX = 'np.spaces.v1.';
export const WORK_PREFIX = 'np.work.v1.';

interface SpacesDoc {
  v: 1;
  logs: Record<string, SpaceEvent[]>;
}

interface WorkDoc {
  v: 1;
  tasks: Record<string, WorkTask>;
  swaps: Record<string, ShiftSwap>;
}

function read(key: string): unknown {
  try {
    const raw = localStorage.getItem(key);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

function write(key: string, doc: SpacesDoc | WorkDoc): void {
  try {
    const raw = JSON.stringify(doc);
    localStorage.setItem(key, raw);
    stashForBackup(key, raw);
  } catch {
    // Квота чи недоступне сховище: стан у памʼяті лишається, нічого не вигадуємо.
  }
}

const isObj = (v: unknown): v is Record<string, unknown> =>
  v !== null && typeof v === 'object' && !Array.isArray(v);

function loadSpaces(profileId: string): SpacesDoc {
  const raw = read(SPACES_PREFIX + profileId);
  const logs: SpacesDoc['logs'] = {};
  if (isObj(raw) && raw.v === 1 && isObj(raw.logs)) {
    for (const [spaceId, list] of Object.entries(raw.logs)) {
      if (!Array.isArray(list)) continue;
      const events = list.map(parseSpaceEvent).filter((e): e is SpaceEvent => e !== null && e.spaceId === spaceId);
      if (events.length) logs[spaceId] = events;
    }
  }
  return { v: 1, logs };
}

function loadWork(profileId: string): WorkDoc {
  const raw = read(WORK_PREFIX + profileId);
  const doc: WorkDoc = { v: 1, tasks: {}, swaps: {} };
  if (!isObj(raw) || raw.v !== 1) return doc;
  if (isObj(raw.tasks)) {
    for (const t of Object.values(raw.tasks)) {
      const task = parseTask(t);
      if (task) doc.tasks[task.id] = task;
    }
  }
  if (isObj(raw.swaps)) {
    for (const s of Object.values(raw.swaps)) {
      const swap = parseSwap(s);
      if (swap) doc.swaps[swap.id] = swap;
    }
  }
  return doc;
}

/**
 * Прийняти події (з дроту чи власні). `receivedAt` ставить сховище — мить
 * отримання не є словом відправника.
 */
export function appendSpaceEvents(
  profileId: string,
  raws: readonly unknown[],
  receivedAt: number = Date.now(),
): { added: number; dropped: number } {
  const doc = loadSpaces(profileId);
  let added = 0;
  for (const raw of raws) {
    const e = parseSpaceEvent(raw);
    const log = e ? (doc.logs[e.spaceId] ??= []) : null;
    if (!e || !log || log.some((x) => x.id === e.id)) continue;
    log.push({ ...e, receivedAt });
    added += 1;
  }
  if (added) write(SPACES_PREFIX + profileId, doc);
  return { added, dropped: raws.length - added };
}

export function spaceLog(profileId: string, spaceId: string): SpaceEvent[] {
  return loadSpaces(profileId).logs[spaceId] ?? [];
}

export function spacesOf(profileId: string): Space[] {
  return Object.values(loadSpaces(profileId).logs)
    .map((log) => foldSpace(log).space)
    .filter((s): s is Space => s !== null)
    .sort((a, b) => a.createdAt - b.createdAt || (a.id < b.id ? -1 : 1));
}

export function putTask(profileId: string, raw: unknown): WorkTask | null {
  const task = parseTask(raw);
  if (!task) return null;
  const doc = loadWork(profileId);
  const prior = doc.tasks[task.id];
  const merged = prior ? mergeTask(prior, task) : task;
  doc.tasks[task.id] = merged;
  write(WORK_PREFIX + profileId, doc);
  return merged;
}

export function tasksOf(
  profileId: string,
  where: { chatId?: string; spaceId?: string; assignee?: string } = {},
): WorkTask[] {
  return Object.values(loadWork(profileId).tasks)
    .filter(
      (t) =>
        (where.chatId === undefined || t.chatId === where.chatId) &&
        (where.spaceId === undefined || t.spaceId === where.spaceId) &&
        (where.assignee === undefined || t.assignee === where.assignee),
    )
    .sort((a, b) => a.createdAt - b.createdAt || (a.id < b.id ? -1 : 1));
}

export function putSwap(profileId: string, raw: unknown): ShiftSwap | null {
  const swap = parseSwap(raw);
  if (!swap) return null;
  const doc = loadWork(profileId);
  const prior = doc.swaps[swap.id];
  const merged = prior ? mergeSwap(prior, swap) : swap;
  doc.swaps[swap.id] = merged;
  write(WORK_PREFIX + profileId, doc);
  return merged;
}

export function swapsOf(profileId: string): ShiftSwap[] {
  return Object.values(loadWork(profileId).swaps).sort((a, b) => (a.id < b.id ? -1 : 1));
}

/** Профіль видалено: його простори й робота не лишаються сиротами в сховищі. */
export function forgetProfileSpaces(profileId: string): void {
  try {
    localStorage.removeItem(SPACES_PREFIX + profileId);
    localStorage.removeItem(WORK_PREFIX + profileId);
  } catch {
    // Сховище недоступне — нічого стирати.
  }
}
