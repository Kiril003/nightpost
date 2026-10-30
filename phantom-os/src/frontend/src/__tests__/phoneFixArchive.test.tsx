/** A56 10.10: «Архів» і вкладкою, і рядком у «Усі». Одні двері — вкладка. */
import { describe, expect, it } from 'vitest';
import { act } from '@testing-library/react';
import { useMessengerStore } from '../stores/messengerStore';
import { conv, mountRoot, useRootHooks } from './phoneFixRoot';

useRootHooks();

describe('8. архів — одні двері', () => {
  it('архів — рядок «Архів» унизу «Усі», не вкладка (10 §2)', async () => {
    await mountRoot([
      conv('a', 'Оксана', 'dm', { last_kind: 'text', last_snippet: 'привіт', last_at: '2026-10-10T06:00:00' }),
      conv('b', 'nina', 'dm', { last_kind: 'text', last_snippet: 'ok', last_at: '2026-10-10T05:00:00' }),
      ...['Іра', 'Тарас', 'Влад', 'Софія'].map((n) => conv(`c_${n}`, n, 'dm', { last_kind: 'text', last_snippet: 'привіт', last_at: '2026-10-10T04:00:00' })),
    ]);
    act(() => useMessengerStore.getState().toggleArchiveChat('b'));
    expect(document.querySelector('[data-filter-tab="archive"]')).toBeNull();
    expect(document.querySelector('[data-folder-row="archive"]')).toBeTruthy();
  });
});
