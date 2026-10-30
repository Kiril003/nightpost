/** Видалення та «В архів» із відкатом: запит на вузол іде через 5 с, до того момент лише схований. */
import { create } from 'zustand';
import { heldIds, useMoments, type Moment } from './moments';
import { useArchive } from './momentsArchive';

export const UNDO_MS = 5000;

export interface PendingRemoval {
  id: string;
  archive: boolean;
}

interface UndoState {
  pending: PendingRemoval | null;
  failed: boolean;
}

export const useMomentUndo = create<UndoState>(() => ({ pending: null, failed: false }));

let timer: ReturnType<typeof setTimeout> | null = null;
let held: { m: Moment; index: number } | null = null;

async function commit() {
  const h = held;
  const p = useMomentUndo.getState().pending;
  held = null;
  timer = null;
  if (!h || !p) return;
  try {
    await useMoments.getState().remove(h.m.id);
    if (p.archive) useArchive.getState().add(h.m);
    else useArchive.getState().forget(h.m.id);
    heldIds.delete(h.m.id);
    useMomentUndo.setState((s) => (s.pending?.id === p.id ? { pending: null } : {}));
  } catch {
    restore(h);
    useMomentUndo.setState((s) => (s.pending?.id === p.id ? { pending: null, failed: true } : { failed: true }));
  }
}

function restore(h: { m: Moment; index: number }) {
  heldIds.delete(h.m.id);
  const cur = useMoments.getState().list ?? [];
  if (cur.some((x) => x.id === h.m.id)) return;
  const next = [...cur];
  next.splice(Math.min(h.index, next.length), 0, h.m);
  useMoments.setState({ list: next });
}

export function flushRemoval(): Promise<void> {
  if (timer) clearTimeout(timer);
  return held ? commit() : Promise.resolve();
}

/** Сховати момент зараз; через UNDO_MS він піде на вузол (і в архів, якщо archive). */
export function queueRemoval(m: Moment, archive: boolean): Promise<void> {
  void flushRemoval();
  const cur = useMoments.getState().list ?? [];
  const index = Math.max(0, cur.findIndex((x) => x.id === m.id));
  held = { m, index };
  heldIds.add(m.id);
  useMoments.setState({ list: cur.filter((x) => x.id !== m.id) });
  useMomentUndo.setState({ pending: { id: m.id, archive }, failed: false });
  timer = setTimeout(() => void commit(), UNDO_MS);
  return Promise.resolve();
}

export function undoRemoval(): void {
  if (timer) clearTimeout(timer);
  timer = null;
  const h = held;
  held = null;
  if (h) restore(h);
  useMomentUndo.setState({ pending: null });
}
