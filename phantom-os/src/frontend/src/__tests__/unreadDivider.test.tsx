import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ChatArea } from '../components/messenger/ChatArea';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message } from '../types/messenger';

const noop = () => {};
const talk = (n: number): Message[] =>
  Array.from({ length: n }, (_, i) =>
    ({
      id: `m${i}`, senderId: 'peer', senderName: 'Оксана', senderAvatar: '', timestamp: '14:32',
      type: 'text', isSelf: false, text: `лист ${i}`,
    }) as unknown as Message,
  );
const ui = (unread: number, messages: Message[]) => (
  <MemoryRouter>
    <ChatArea
      currentChat={{ id: 'c1', title: 'Оксана', avatar: '', type: 'dm', circle: 'all', pinned: false, unreadCount: unread, messages: [] } as unknown as Chat}
      messages={messages}
      currentUserId="me"
      onOpenLocation={noop} onVotePoll={noop} onPayBillShare={noop} onAddReaction={noop} onReplyMessage={noop}
      selectedMessageIds={[]} onToggleSelectMessage={noop} isSelectionMode={false} isSearching={false} onCloseSearch={noop}
    />
  </MemoryRouter>
);

describe('A3: роздільник «Нові повідомлення»', () => {
  it('стоїть над першим непрочитаним листом', () => {
    Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
    const { container } = render(ui(2, talk(5)));
    const div = container.querySelector('[data-testid="unread-divider"]') as HTMLElement;
    expect(div).not.toBeNull();
    expect(div.parentElement?.textContent).toContain('лист 3');
    expect(div.parentElement?.textContent).not.toContain('лист 2');
  });

  it('без непрочитаних роздільника нема', () => {
    Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
    const { container } = render(ui(0, talk(5)));
    expect(container.querySelector('[data-testid="unread-divider"]')).toBeNull();
  });

  it('чат уже позначено прочитаним — роздільник стоїть за кількістю на мить відкриття', () => {
    Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
    useMessengerStore.setState({ unreadAtOpen: { c1: 2 } } as never);
    const { container } = render(ui(0, talk(5)));
    const div = container.querySelector('[data-testid="unread-divider"]') as HTMLElement;
    expect(div?.parentElement?.textContent).toContain('лист 3');
    useMessengerStore.setState({ unreadAtOpen: {} } as never);
  });
});
