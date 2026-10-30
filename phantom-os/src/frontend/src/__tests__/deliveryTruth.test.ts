import { describe, expect, it } from 'vitest';
import { messageFromNode, withReplies, type NodeMessage } from '../services/messengerApi';

// Рядки рівно такої форми міст віддав 24.09 на A52 (WebMessengerBridge,
// GET /messenger/conversations/{id}/messages) у наскрізній перевірці доставки.
const PHONE = '747aa842fd358e5f';
const PEER = 'fec726864cbf209e';

function row(over: Partial<NodeMessage>): NodeMessage {
  return {
    id: 'x',
    conversation_id: PEER,
    client_id: 'x',
    seq: 1,
    author_id: PHONE,
    author_name: '',
    kind: 'text',
    body: '',
    ciphertext: null,
    transport: null,
    reply_to_id: null,
    sent_at: '2026-09-24T16:00:35.031Z',
    edited_at: null,
    deleted_at: null,
    delivery_state: 'delivered',
    relay_state: 'acked',
    ...over,
  } as NodeMessage;
}

describe('чи дійде: слово під листом читає relay_state', () => {
  it('лист, що сховок узяв, несе stored', () => {
    const m = messageFromNode(row({ delivery_state: 'queued', relay_state: 'stored' }), 'me', PEER);
    expect(m.status).toBe('queued');
    expect(m.relayState).toBe('stored');
  });

  it('лист, якого сховок не бачив, несе local — і НЕ може зватися «лежить у сховку»', () => {
    const m = messageFromNode(row({ delivery_state: 'queued', relay_state: 'local' }), 'me', PEER);
    expect(m.status).toBe('queued');
    expect(m.relayState).toBe('local');
  });

  it('ПК не шле relay_state — поле лишається порожнім, слово старе', () => {
    const m = messageFromNode(row({ delivery_state: 'queued', relay_state: undefined }), 'me', PEER);
    expect(m.relayState).toBeUndefined();
  });
});

describe('відповідь із дроту стає цитатою', () => {
  const rows = [
    row({ id: 'a', author_id: PEER, body: 'S2a-node-to-phone-direct' }),
    row({ id: 'b', body: 'S4-phone-reply-to-S2a', reply_to_id: 'a' }),
    row({ id: 'c', author_id: PEER, body: 'S4-node-reply-to-S4phone', reply_to_id: 'b' }),
    row({ id: 'd', author_id: PEER, body: 'на стертий', reply_to_id: 'gone' }),
  ];
  const msgs = withReplies(
    rows,
    rows.map((r) => messageFromNode(r, 'me', PEER)),
    { self: 'Ви', peer: 'ПК-вузол' },
  );

  it('своя відповідь на чужий лист цитує його текст і автора', () => {
    expect(msgs[1].replyTo).toMatchObject({ id: 'a', text: 'S2a-node-to-phone-direct', senderName: 'ПК-вузол' });
  });

  it('чужа відповідь на мій лист підписана «Ви»', () => {
    expect(msgs[2].replyTo).toMatchObject({ id: 'b', text: 'S4-phone-reply-to-S2a', senderName: 'Ви' });
  });

  it('ціль поза стрічкою — цитата лишається й каже це словом', () => {
    expect(msgs[3].replyTo).toMatchObject({ id: 'gone', text: 'лист поза стрічкою' });
  });

  it('лист без відповіді цитати не отримує', () => {
    expect(msgs[0].replyTo).toBeUndefined();
  });
});
