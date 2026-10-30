import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render } from '@testing-library/react';
import { buildFeedRows } from '../components/messenger/feed/feedModel';
import { MessageRow } from '../components/messenger/feed/MessageRow';
import { FeedActionsContext, type FeedActions } from '../components/messenger/feed/feedActions';
import { SAVED_NOTES_ID } from '../services/messengerApi';
import type { Message } from '../types/messenger';

afterEach(cleanup);

const actions = new Proxy({}, { get: () => () => {} }) as FeedActions;
const NOW = new Date('2026-10-15T15:00:00Z');

const m = (id: string, from: 'me' | 'peer', minute: number, over: Partial<Message> = {}): Message =>
  ({
    id, senderId: from, senderName: from === 'me' ? 'Я' : 'Марта', senderAvatar: '', text: `лист ${id}`,
    timestamp: `14:${String(minute).padStart(2, '0')}`, sentAt: `2026-10-15T14:${String(minute).padStart(2, '0')}:00Z`,
    type: 'text', isSelf: from === 'me', status: from === 'me' ? 'sent' : undefined, ...over,
  }) as Message;

const draw = (msgs: Message[], chatId = 'c1') =>
  buildFeedRows(msgs, 'me', NOW).map((row) => {
    const { container } = render(
      <FeedActionsContext.Provider value={actions}>
        <MessageRow
          row={row} chatId={chatId} currentUserId="me" currentUserName="Я" groupish={false} direct phone
          isSelectionMode={false} isSelected={false} isHighlighted={false} isSearchMatch={false}
          isActiveSearchMatch={false} fresh={false} expanded={false} copied={false}
        />
      </FeedActionsContext.Provider>,
    );
    const tail = container.querySelector('[data-bubble-tail]');
    const out = { id: row.msg.id, tail: tail?.getAttribute('data-bubble-tail') ?? null, tick: container.querySelector('[data-msg-tick]')?.getAttribute('data-msg-tick') ?? null };
    cleanup();
    return out;
  });

describe('хвостик бульбашки — лише на останній у черзі', () => {
  it('три свої поспіль і дві чужі: хвостик у третьої своєї та другої чужої', () => {
    const rows = draw([m('a', 'me', 30), m('b', 'me', 31), m('c', 'me', 32), m('d', 'peer', 33), m('e', 'peer', 34)]);
    expect(rows.map((r) => r.tail)).toEqual([null, null, 'self', null, 'other']);
  });

  it('пауза понад десять хвилин починає нову чергу — хвостик у кожної', () => {
    const rows = draw([m('a', 'me', 0), m('b', 'me', 20)]);
    expect(rows.map((r) => r.tail)).toEqual(['self', 'self']);
  });

  it('прозора бульбашка (один смайл) хвостика не має', () => {
    const rows = draw([m('a', 'me', 30), m('b', 'me', 31, { text: '👍' })]);
    expect(rows.map((r) => r.tail)).toEqual([null, null]);
  });
});

describe('«Збережене» — галочка біля часу', () => {
  it('нотатки поспіль за кілька хвилин — одна черга, хвостик лише в останньої', () => {
    const notes = [30, 31, 32].map((min, i) => m(`n${i}`, 'me', min, { status: undefined }));
    expect(draw(notes, SAVED_NOTES_ID).map((r) => r.tail)).toEqual([null, null, 'self']);
  });

  it('нотатка на цьому телефоні має одну галочку', () => {
    const [row] = draw([m('a', 'me', 30, { status: undefined })], SAVED_NOTES_ID);
    expect(row.tick).toBe('saved');
  });

  it('у звичайній розмові лист без статусу галочки не вигадує', () => {
    const [row] = draw([m('a', 'me', 30, { status: undefined })]);
    expect(row.tick).toBeNull();
  });
});
