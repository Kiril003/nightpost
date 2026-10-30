/**
 * A56 10.10 (_a56-grp2.png): картка «Pho dinner» у групі — «1 витрата · 8…».
 * Рядок стану ділив ширину з «Відкрити» і різався трьома крапками, тож сума
 * була нечитна. Сума — у рядку стану, тож рядок стану займає всю ширину.
 */
import { afterEach, beforeAll, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ChatArea } from '../components/messenger/ChatArea';
import type { Chat, Message } from '../types/messenger';

const CHAT = {
  id: 'grp', title: 'Friday pho', avatar: '', type: 'group', circle: 'all', pinned: false, unreadCount: 0, messages: [],
} as unknown as Chat;

const expense = {
  id: 'e1', type: 'widget:family-expense', senderId: 'olena', senderName: 'Olena', senderAvatar: '', isSelf: false,
  timestamp: '07:03', text: '',
  familyExpenseData: {
    id: 'fe', title: 'Pho dinner', currency: '₴',
    items: [{ id: 'a', title: 'Pho and drinks', amountUAH: 840, category: '', paidBy: 'Olena' }],
  },
} as unknown as Message;

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});
afterEach(cleanup);

describe('10. сума картки читається завжди', () => {
  it('у стрічці групи рядок стану не ділить ряд із «Відкрити» і не обрізається', () => {
    const noop = () => {};
    render(
      <MemoryRouter>
        <ChatArea
          currentChat={CHAT}
          messages={[expense]}
          currentUserId="me"
          onOpenLocation={noop}
          onVotePoll={noop}
          onPayBillShare={noop}
          onAddReaction={noop}
          onReplyMessage={noop}
          selectedMessageIds={[]}
          onToggleSelectMessage={noop}
          isSelectionMode={false}
          isSearching={false}
          onCloseSearch={noop}
        />
      </MemoryRouter>,
    );
    const card = screen.getByTestId('card-expense');
    const meta = screen.getByTestId('card-status');
    expect(meta.textContent).toBe('1 витрата · 840 ₴');
    expect(meta.className).not.toMatch(/\btruncate\b/);
    const open = card.querySelector('[data-card-actions] button') as HTMLElement;
    expect(open.textContent).toBe('Відкрити');
    expect(open.parentElement!.contains(meta), 'стан ділить ряд із кнопкою').toBe(false);
  });
});
