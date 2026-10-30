/**
 * A56 10.10 (_a56-list2.png): «Friday pho» з двома непрочитаними — другий рядок
 * порожній. Остання — картка «Витрати», а її роду список не знав. Тепер
 * «Olena: Pho dinner · 840 ₴»; без чисел із вузла — хоча б слово роду.
 */
import { describe, expect, it } from 'vitest';
import { conv, mountRoot, previewLine, useRootHooks } from './phoneFixRoot';
import type { NodeConversation } from '../services/messengerApi';

useRootHooks();

describe('2. група: «Автор: превʼю» і для картки', () => {
  it('остання — картка витрат: «Olena: Pho dinner · 840 ₴»', async () => {
    await mountRoot([
      conv('g', 'Friday pho', 'group', {
        unread_count: 2, last_kind: 'widget:family-expense', last_snippet: null, last_author: 'Olena',
        last_at: '2026-10-10T07:03:00', last_card_title: 'Pho dinner', last_card_total: 840, last_card_currency: '₴',
      } as Partial<NodeConversation>),
    ]);
    expect(previewLine('g')?.textContent).toBe('Olena: Pho dinner · 840 ₴');
  });

  it('картка без чисел із вузла — хоча б слово роду, не порожній рядок', async () => {
    await mountRoot([
      conv('g2', 'Двір', 'group', {
        last_kind: 'widget:family-checklist', last_snippet: null, last_author: 'Марта', last_at: '2026-10-10T07:03:00',
      }),
    ]);
    expect(previewLine('g2')?.textContent).toBe('Марта: Список справ');
  });
});
