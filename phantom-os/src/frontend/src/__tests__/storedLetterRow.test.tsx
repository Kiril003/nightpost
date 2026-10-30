import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render } from '@testing-library/react';
import { buildFeedRows } from '../components/messenger/feed/feedModel';
import { MessageRow } from '../components/messenger/feed/MessageRow';
import { FeedActionsContext, type FeedActions } from '../components/messenger/feed/feedActions';
import type { Message } from '../types/messenger';

afterEach(cleanup);

const actions = new Proxy({}, { get: () => () => {} }) as FeedActions;

const meta = (over: Partial<Message>) => {
  const msg = {
    id: 'm1', senderId: 'me', senderName: 'Я', senderAvatar: '', text: 'ти де?', timestamp: '14:32',
    sentAt: '2026-10-15T14:32:00Z', type: 'text', isSelf: true, ...over,
  } as Message;
  const [row] = buildFeedRows([msg], 'me', new Date('2026-10-15T15:00:00Z'));
  const { container } = render(
    <FeedActionsContext.Provider value={actions}>
      <MessageRow
        row={row} chatId="c1" currentUserId="me" currentUserName="Я" groupish={false} direct phone
        isSelectionMode={false} isSelected={false} isHighlighted={false} isSearchMatch={false}
        isActiveSearchMatch={false} fresh={false} expanded={false} copied={false}
      />
    </FeedActionsContext.Provider>,
  );
  return {
    stored: container.querySelector('[data-msg-tick="stored"]'),
    clock: container.querySelector('[data-msg-pending]'),
  };
};

describe('бульбашка листа, який узяв сховок', () => {
  it('галочка «надіслано», годинника немає', () => {
    const m = meta({ status: 'queued', relayState: 'stored' });
    expect(m.stored).not.toBeNull();
    expect(m.clock).toBeNull();
  });

  it('прозора бульбашка (один смайл): галочка під нею, годинника немає', () => {
    const m = meta({ status: 'queued', relayState: 'stored', text: '👍' });
    expect(m.stored).not.toBeNull();
    expect(m.clock).toBeNull();
  });

  it('лист на телефоні — годинник, без галочки', () => {
    const m = meta({ status: 'queued', relayState: 'local' });
    expect(m.stored).toBeNull();
    expect(m.clock).not.toBeNull();
  });
});
