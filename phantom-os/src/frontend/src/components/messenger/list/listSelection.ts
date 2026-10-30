import type { Chat } from '../../../types/messenger';
import { isNodeChat, isSavedNotes } from '../chatListFormat';

/** Що можна зробити з усіма вибраними розмовами; null — дія не стосується (кнопка вимкнена). */
export interface SelectionCaps {
  pin: 'pin' | 'unpin' | null;
  mute: 'mute' | 'unmute' | null;
  archive: 'archive' | 'unarchive' | null;
  read: boolean;
  remove: boolean;
}

/**
 * «Збережене» не глушиться, не архівується й не видаляється; Nightpost не видаляється.
 * Закріплення стосується всіх, крім «Збереженого» (воно закріплене завжди).
 * Усі вже закріплені (приглушені, в архіві) — дія знімає; інакше ставить решті.
 */
export function selectionCaps(chats: Chat[], opts: { unread: (c: Chat) => boolean; canDelete: boolean }): SelectionCaps {
  if (chats.length === 0) return { pin: null, mute: null, archive: null, read: false, remove: false };
  const saved = chats.some(isSavedNotes);
  const pinnable = chats.filter((c) => !isSavedNotes(c));
  return {
    pin: pinnable.length === 0 ? null : pinnable.every((c) => c.pinned) ? 'unpin' : 'pin',
    mute: saved ? null : chats.every((c) => c.muted) ? 'unmute' : 'mute',
    archive: saved ? null : chats.every((c) => c.archived) ? 'unarchive' : 'archive',
    read: chats.some(opts.unread),
    remove: opts.canDelete && !saved && !chats.some(isNodeChat),
  };
}

/** Кому з вибраних тоді йде дія: перемикаємо лише тих, чий стан ще не той. */
export const needsToggle = (chats: Chat[], key: 'pinned' | 'muted' | 'archived', want: boolean): Chat[] =>
  chats.filter((c) => Boolean(c[key]) !== want && !(key === 'pinned' && isSavedNotes(c)));
