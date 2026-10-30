/** P-EVENT-CARD: рядок моста роду `event` із JSON-тілом стає карткою події, а не порожньою бульбашкою. */
import { describe, expect, it, vi } from 'vitest';

vi.mock('../services/messengerMedia', () => ({ cachedMediaUrl: () => undefined }));

import { messageFromNode, type NodeMessage } from '../services/messengerApi';
import { hasEventView, readMessageCard } from '../services/peerWire';

const eventRow = (over: Partial<NodeMessage> = {}): NodeMessage =>
  ({
    id: 'e1', conversation_id: 'g1', client_id: 'e1', seq: 1, author_id: 'marta', author_name: 'Марта', kind: 'event',
    body: JSON.stringify({ title: 'Виїзд', date: '2026-11-01', time: '07:00', location: 'Міст' }),
    ciphertext: null, transport: 'p2p', sent_at: '2026-10-29T10:00:00Z', edited_at: null, deleted_at: null,
    ...over,
  }) as unknown as NodeMessage;

describe('P-EVENT-CARD: подія з моста', () => {
  it('груповий рядок події несе eventData і малюється карткою', () => {
    const m = messageFromNode(eventRow({ fan_total: 2 } as Partial<NodeMessage>), 'me');
    expect(m.type).toBe('event');
    expect(m.eventData).toMatchObject({ title: 'Виїзд', date: '2026-11-01', time: '07:00', location: 'Міст' });
    expect(hasEventView(m)).toBe(true);
    expect(readMessageCard(m)).toBeNull();
  });

  it('подія без місця лишається карткою', () => {
    const body = JSON.stringify({ title: 'Виїзд', date: '2026-11-01', time: '07:00', location: '' });
    const m = messageFromNode(eventRow({ body }), 'me');
    expect(hasEventView(m)).toBe(true);
  });
});
