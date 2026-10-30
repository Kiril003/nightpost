/**
 * Переїзд нотаток «Збереженого» з IndexedDB на вузол — один раз і без дублів.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

const importSavedNotes = vi.fn();
const loadChats = vi.fn();
vi.mock('../services/messengerApi', () => ({
  SAVED_NOTES_ID: 'chat_saved_notes',
  messengerApi: { importSavedNotes: (...a: unknown[]) => importSavedNotes(...a) },
}));
vi.mock('../services/storagePersistence', () => ({
  storagePersistence: { loadChats: () => loadChats() },
}));

import { importSavedNotesOnce, SAVED_IMPORT_FLAG, toImportRow } from '../services/savedNotesImport';

const note = (id: string, text: string) => ({
  id, senderId: 'me', senderName: 'Я', senderAvatar: '', timestamp: '18:16', sentAt: '2026-09-22T18:16:00Z',
  type: 'text', text,
});

beforeEach(() => {
  localStorage.clear();
  importSavedNotes.mockReset();
  loadChats.mockReset();
});

describe('importSavedNotesOnce', () => {
  it('шле локальні нотатки тілом {client_id, kind, body, sent_at, reply_to_id}', async () => {
    loadChats.mockResolvedValue([{ id: 'chat_saved_notes', messages: [note('m1', 'перевірка 23.09')] }]);
    importSavedNotes.mockResolvedValue({ imported: 1, skipped: 0, refused: 0 });
    expect(await importSavedNotesOnce()).toBe(true);
    expect(importSavedNotes).toHaveBeenCalledWith([
      { client_id: 'm1', kind: 'text', body: 'перевірка 23.09', sent_at: '2026-09-22T18:16:00Z', reply_to_id: null },
    ]);
    expect(localStorage.getItem(SAVED_IMPORT_FLAG)).toBe('1');
  });

  it('після успіху більше не шле нічого', async () => {
    localStorage.setItem(SAVED_IMPORT_FLAG, '1');
    expect(await importSavedNotesOnce()).toBe(true);
    expect(loadChats).not.toHaveBeenCalled();
    expect(importSavedNotes).not.toHaveBeenCalled();
  });

  it('відмова вузла не ставить позначки — наступне відкриття спробує ще', async () => {
    loadChats.mockResolvedValue([{ id: 'chat_saved_notes', messages: [note('m1', 'x')] }]);
    importSavedNotes.mockRejectedValue(new Error('404'));
    expect(await importSavedNotesOnce()).toBe(false);
    expect(localStorage.getItem(SAVED_IMPORT_FLAG)).toBeNull();
  });

  it('нічого локально — нічого не шле, але позначає як переїхале', async () => {
    loadChats.mockResolvedValue(null);
    expect(await importSavedNotesOnce()).toBe(true);
    expect(importSavedNotes).not.toHaveBeenCalled();
  });

  it('нетекстовий лист їде описом, який читає messageFromNode', () => {
    const row = toImportRow({ ...note('v1', ''), type: 'video-note', text: undefined, videoNoteData: { duration: 1 } } as never);
    expect(row?.kind).toBe('video-note');
    expect(JSON.parse(row!.body!).__msg.videoNoteData.duration).toBe(1);
  });
});
