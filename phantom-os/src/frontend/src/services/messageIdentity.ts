/**
 * Перечитана стрічка — ті самі обʼєкти листів, якщо листи не змінились.
 *
 * Повне перечитування розмови (`__phantomMeshChanged`, відкриття розмови,
 * «спробуй зараз») будує КОЖЕН лист наново з рядка вузла. Для React це
 * виглядало як «змінилось усе»: рядки стрічки — `React.memo`, але memo
 * порівнює посилання, і нове посилання будило всі намальовані рядки на
 * кожен вхідний лист. Тут новий лист, рівний старому за вмістом, замінюється
 * старим обʼєктом; а якщо рівні всі — повертається старий масив, і стор
 * може не чіпати стану взагалі.
 */
import type { Message } from '../types/messenger';

/** Глибока рівність для простих даних листа (JSON-подібні значення). */
export function sameData(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (typeof a !== 'object' || typeof b !== 'object' || a === null || b === null) {
    return typeof a === 'number' && typeof b === 'number' && Number.isNaN(a) && Number.isNaN(b);
  }
  if (Array.isArray(a)) {
    if (!Array.isArray(b) || a.length !== b.length) return false;
    for (let i = 0; i < a.length; i++) if (!sameData(a[i], b[i])) return false;
    return true;
  }
  if (Array.isArray(b)) return false;
  const ao = a as Record<string, unknown>;
  const bo = b as Record<string, unknown>;
  const ka = Object.keys(ao).filter((k) => ao[k] !== undefined);
  const kb = Object.keys(bo).filter((k) => bo[k] !== undefined);
  if (ka.length !== kb.length) return false;
  for (const k of ka) if (!sameData(ao[k], bo[k])) return false;
  return true;
}

/**
 * `next`, у якому кожен лист, рівний за вмістом листу з тим самим id у
 * `prev`, — це обʼєкт із `prev`. Нічого не змінилось — сам `prev`.
 */
export function keepMessageIdentity(prev: Message[], next: Message[]): Message[] {
  if (prev === next) return prev;
  const byId = new Map<string, Message>();
  for (const m of prev) if (m) byId.set(m.id, m);
  let changed = prev.length !== next.length;
  const out = next.map((m, i) => {
    const old = m ? byId.get(m.id) : undefined;
    if (old && sameData(old, m)) {
      if (prev[i] !== old) changed = true;
      return old;
    }
    changed = true;
    return m;
  });
  return changed ? out : prev;
}
