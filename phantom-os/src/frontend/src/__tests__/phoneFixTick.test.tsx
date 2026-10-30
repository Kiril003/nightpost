/**
 * A52 10.10 (_a52-got.png): вхідний лист Kyrylo в «Сьогодні» з галочкою. Стрічка
 * в памʼяті лишилась на МОЄМУ старому листі, а вузол уже казав, що останній —
 * чужий. Галочка — лише під своїм останнім листом.
 */
import { describe, expect, it } from 'vitest';
import { act, waitFor } from '@testing-library/react';
import { vi } from 'vitest';
import { messengerApi } from '../services/messengerApi';
import { useMessengerStore } from '../stores/messengerStore';
import type { Message } from '../types/messenger';
import { conv, mountRoot, previewLine, useRootHooks } from './phoneFixRoot';

useRootHooks();

describe('1. галочка — лише під своїм останнім листом', () => {
  it('вузол каже «останній — від Kyrylo», а в памʼяті ще мій старий лист: галочки немає', async () => {
    await mountRoot([
      conv('k', 'Kyrylo', 'dm', {
        unread_count: 1, last_kind: 'text', last_snippet: 'In! Let us ask Marko and Iva too',
        last_author: 'Kyrylo', last_at: '2026-10-10T07:01:00', peer_node_id: 'k-node',
      }),
    ]);
    const mine: Message = {
      id: 'm1', type: 'text', text: 'In! Where?', isSelf: true, senderId: 'me', senderName: 'Я',
      senderAvatar: '', timestamp: '14:53', sentAt: '2026-10-09T14:53:00Z', status: 'sent',
    } as Message;
    act(() =>
      useMessengerStore.setState((s) => ({ chats: s.chats.map((c) => (c.id === 'k' ? { ...c, messages: [mine] } : c)) })),
    );
    expect(previewLine('k')?.textContent).toContain('In! Let us ask Marko and Iva too');
    expect(document.querySelector('[data-chat-row="k"] [data-row-tick]')).toBeNull();
  });

  it('мій власний останній лист галочку тримає', async () => {
    await mountRoot([
      conv('o', 'Olena', 'dm', {
        last_kind: 'text', last_snippet: 'Dinner at 8?', last_author: 'me', last_at: '2026-10-10T06:00:00', peer_node_id: 'o-node',
      }),
    ]);
    const mine = {
      id: 'm2', type: 'text', text: 'Dinner at 8?', isSelf: true, senderId: 'me', senderName: 'Я',
      senderAvatar: '', timestamp: '06:00', sentAt: '2026-10-10T06:00:00Z', status: 'sent',
    } as Message;
    act(() =>
      useMessengerStore.setState((s) => ({ chats: s.chats.map((c) => (c.id === 'o' ? { ...c, messages: [mine] } : c)) })),
    );
    expect(document.querySelector('[data-chat-row="o"] [data-row-tick="sent"]')).toBeTruthy();
  });
  it('живий шлях: лист Kyrylo приїхав дельтою телефона, поки «Сьогодні» відкрите, — галочка сходить', async () => {
    await mountRoot([
      conv('k', 'Kyrylo', 'dm', {
        last_kind: 'text', last_snippet: 'In! Where?', last_author: 'me', last_at: '2026-10-10T06:59:00Z', peer_node_id: 'k-node',
      }),
    ]);
    const mine = {
      id: 'm1', type: 'text', text: 'In! Where?', isSelf: true, senderId: 'me', senderName: 'Я',
      senderAvatar: '', timestamp: '06:59', sentAt: '2026-10-10T06:59:00Z', status: 'sent',
    } as Message;
    act(() =>
      useMessengerStore.setState((s) => ({ chats: s.chats.map((c) => (c.id === 'k' ? { ...c, messages: [mine] } : c)) })),
    );
    expect(document.querySelector('[data-chat-row="k"] [data-row-tick="sent"]')).toBeTruthy();
    vi.spyOn(messengerApi, 'conversationsByIds').mockResolvedValue({
      rows: [conv('k', 'Kyrylo', 'dm', {
        unread_count: 1, last_kind: 'text', last_snippet: 'In! Let us ask Marko and Iva too',
        last_author: 'Kyrylo', last_at: '2026-10-10T07:01:00.412Z', peer_node_id: 'k-node',
      })],
      gone: [],
    });
    act(() => (window as unknown as { __phantomMeshDelta: (d: { convs: string[] }) => void }).__phantomMeshDelta({ convs: ['k'] }));
    await waitFor(() => expect(previewLine('k')?.textContent).toContain('Marko and Iva'));
    expect(document.querySelector('[data-chat-row="k"] [data-row-tick]')).toBeNull();
  });
});
