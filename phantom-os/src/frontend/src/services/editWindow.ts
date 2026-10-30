import type { Message } from '../types/messenger';

/** Те саме вікно, що в `PeerRevise.EDIT_WINDOW_MS`: пізнішої правки отримувач не прийме. */
export const EDIT_WINDOW_MS = 48 * 60 * 60 * 1000;

export function canStillEdit(m: Message, savedNotes: boolean, nowMs = Date.now()): boolean {
  if (savedNotes || !m.sentAt) return true;
  const at = Date.parse(m.sentAt);
  return Number.isNaN(at) || nowMs - at <= EDIT_WINDOW_MS;
}

/** Що правиться: слова — завжди, а з вкладень лише підпис фото чи файла (`PeerRevise.captionable`). */
export function editableKind(m: Message): boolean {
  if (m.media) return m.type === 'image' || m.type === 'file';
  return Boolean(m.text);
}
