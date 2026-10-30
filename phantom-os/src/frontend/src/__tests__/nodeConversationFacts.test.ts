/**
 * Факти розмови від вузла (хто писав останнім, листів за 30 днів) — міст
 * `chatFromNode` (перенесено з calmInbox.test.tsx, коли мертве правило
 * спокійного потоку пішло з дерева; сам міст живий).
 */
import { describe, it, expect } from 'vitest';
import { chatFromNode } from '../services/messengerApi';
import type { NodeConversation } from '../services/messengerApi';

const ago = (days: number) => new Date(Date.parse('2026-09-25T12:00:00Z') - days * 86_400_000).toISOString();

describe('факти розмови від вузла', () => {
  it('міст: undefined — «не каже», null — «не писали»', () => {
    const base = { id: 'x', title: 'X', kind: 'dm', pinned: false } as unknown as NodeConversation;
    expect(chatFromNode(base).lastSelfAt).toBeUndefined();
    const c = chatFromNode({ ...base, last_self_at: null, last_peer_at: ago(1), letters_30d: 5 });
    expect(c.lastSelfAt).toBeNull();
    expect(c.letters30d).toBe(5);
  });
});
