/**
 * Переїзд «Збереженого» зі сховища вебу на вузол.
 *
 * До 24.09 «Збережене» існувало лише у вебі (`chat_saved_notes` в IndexedDB):
 * вузол такої розмови не знав, лист отримував 404 `no_contact`, а після
 * перезапуску розмова зникала зі списку. Тепер вузол тримає її сам — першою,
 * закріпленою. Нотатки, що встигли лягти лише в IndexedDB, переносимо ОДИН раз
 * при першому відкритті; повтор безпечний (вузол відкидає вже відомі
 * `client_id`). Після успіху локальна копія — не джерело правди: стрічку
 * читаємо тільки з вузла.
 */
import type { Message } from '../types/messenger';
import { messengerApi, SAVED_NOTES_ID, type SavedNoteImport } from './messengerApi';
import { storagePersistence } from './storagePersistence';

export const SAVED_IMPORT_FLAG = 'phantom_saved_notes_imported_v1';

const readFlag = (): boolean => {
  try {
    return localStorage.getItem(SAVED_IMPORT_FLAG) === '1';
  } catch {
    return false;
  }
};

const writeFlag = (): void => {
  try {
    localStorage.setItem(SAVED_IMPORT_FLAG, '1');
  } catch {
    /* приватний режим: наступного разу повтор нічого не задублює */
  }
};

/** Лист вебу → рядок імпорту. Текст їде тілом; решта — описом, який уміє читати `messageFromNode`. */
export const toImportRow = (m: Message): SavedNoteImport | null => {
  if (m.isDeleted) return null;
  const kind = m.type || 'text';
  let body: string | null;
  if (kind === 'text') {
    body = m.text?.trim() ? m.text : null;
    if (!body) return null;
  } else {
    const {
      id: _id, senderId: _s, senderName: _n, senderAvatar: _a, timestamp: _t, sentAt: _at,
      status: _st, retryCount: _r, lastAttemptAtMs: _l, isSelf: _self, reactions: _re, ...rest
    } = m;
    body = JSON.stringify({ __msg: rest });
  }
  return {
    client_id: m.id,
    kind,
    body,
    sent_at: m.sentAt ?? null,
    reply_to_id: m.replyTo?.id ?? null,
  };
};

let inFlight: Promise<boolean> | null = null;

/** true — на вузлі вже все, що було локально (або нічого не було). */
export function importSavedNotesOnce(): Promise<boolean> {
  if (readFlag()) return Promise.resolve(true);
  if (inFlight) return inFlight;
  inFlight = (async () => {
    try {
      const local = await storagePersistence.loadChats();
      const saved = local?.find((c) => c.id === SAVED_NOTES_ID);
      const rows = (saved?.messages ?? []).map(toImportRow).filter((r): r is SavedNoteImport => r !== null);
      if (rows.length > 0) await messengerApi.importSavedNotes(rows);
      writeFlag();
      return true;
    } catch (err) {
      console.warn('[messenger] нотатки «Збереженого» не переїхали на вузол:', err);
      return false;
    } finally {
      inFlight = null;
    }
  })();
  return inFlight;
}
