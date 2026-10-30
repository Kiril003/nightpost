import { afterEach, beforeAll, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { ChatArea } from '../components/messenger/ChatArea';
import type { Chat } from '../types/messenger';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});
afterEach(cleanup);

const noop = () => {};
const area = (c: Chat) => (
  <MemoryRouter>
    <ChatArea
      currentChat={c} messages={[]} currentUserId="me"
      onOpenLocation={noop} onVotePoll={noop} onPayBillShare={noop} onAddReaction={noop} onReplyMessage={noop}
      selectedMessageIds={[]} onToggleSelectMessage={noop} isSelectionMode={false} isSearching={false} onCloseSearch={noop}
    />
  </MemoryRouter>
);
const chat = (over: Partial<Chat>) =>
  ({ id: 'c1', title: 'Марта', type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false, messages: [], ...over }) as unknown as Chat;

describe('порожня розмова й порожнє «Збережене» (TG-look 2)', () => {
  for (const [name, c] of [
    ['Збережене', chat({ id: 'chat_saved_notes', title: 'Збережене', type: 'saved', circle: 'saved' } as Partial<Chat>)],
    ['розмова', chat({})],
  ] as const) {
    it(`${name}: значок на плашці, дія бурштинова незалежно від акценту профілю`, () => {
      render(area(c));
      const empty = screen.getByTestId('thread-empty');
      expect(empty.querySelector('[data-empty-plate] svg')).not.toBeNull();
      const action = empty.querySelector('button')!;
      expect(action.closest('[data-accent="amber"]')).not.toBeNull();
    });
  }

  it('стоїть посередині стрічки, а не внизу біля поля вводу', () => {
    const css = readFileSync(join(__dirname, '../styles/messenger.css'), 'utf8');
    expect(css).toMatch(/\.msg-column:has\(\[data-testid=['"]thread-empty['"]\]\)\s*\{[^}]*justify-content:\s*center/);
  });
});
