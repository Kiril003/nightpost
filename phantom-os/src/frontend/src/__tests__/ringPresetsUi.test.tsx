import { afterEach, beforeAll, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ChatWallpaperPicker } from '../components/messenger/ChatWallpaperPicker';
import { ChatArea } from '../components/messenger/ChatArea';
import { DEFAULT_LOOK, useLook } from '../services/appearance';
import { RING_PRESETS_KEY, readRingPresets, useRingPresets } from '../services/ringPresets';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat } from '../types/messenger';

const noop = () => {};

const workChat = {
  id: 'c_work',
  title: 'Зміна',
  avatar: '',
  type: 'group',
  circle: 'work',
  circles: ['work'],
  unreadCount: 0,
  messages: [],
} as unknown as Chat;

const plainChat = {
  id: 'c_plain',
  title: 'Оксана',
  avatar: '',
  type: 'dm',
  circle: 'all',
  circles: [],
  unreadCount: 0,
  messages: [],
} as unknown as Chat;

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

afterEach(() => {
  cleanup();
  localStorage.clear();
  useLook.setState({ look: DEFAULT_LOOK });
  useRingPresets.setState({ presets: {} });
  useMessengerStore.setState({ chats: [] });
});

describe('налаштування кола у виборі тла і стрічці', () => {
  it('вмикає щільні бульбашки для кола «Робота» і передає data-chat-bubbles у ChatArea', () => {
    useMessengerStore.setState({ chats: [workChat, plainChat] });

    render(<ChatWallpaperPicker chatId="c_work" />);
    fireEvent.click(screen.getByText('Щільні бульбашки: «Робота»'));

    expect(useRingPresets.getState().presets.work).toEqual({ bubbles: 'compact' });
    expect(readRingPresets().work).toEqual({ bubbles: 'compact' });
    expect(localStorage.getItem(RING_PRESETS_KEY)).toContain('"compact"');

    const { container } = render(
      <MemoryRouter>
        <ChatArea
          currentChat={workChat}
          messages={[]}
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
    expect(container.querySelector('[data-chat-bubbles="compact"]')).not.toBeNull();
  });

  it('розмова без кола не показує рядків кола', () => {
    useMessengerStore.setState({ chats: [plainChat] });
    render(<ChatWallpaperPicker chatId="c_plain" />);

    expect(document.querySelector('[data-ring-rows]')).toBeNull();
  });

  it('пресет кола «Робота» не змінює щільність інших розмов', () => {
    useRingPresets.getState().setRingBubbles('work', 'compact');
    useMessengerStore.setState({ chats: [workChat, plainChat] });
    const area = (chat: Chat) => (
      <MemoryRouter>
        <ChatArea
          currentChat={chat}
          messages={[]}
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
      </MemoryRouter>
    );
    const plain = render(area(plainChat));
    expect(plain.container.querySelector('[data-chat-bubbles]')?.getAttribute('data-chat-bubbles')).toBe('comfortable');
    plain.unmount();
    const work = render(area(workChat));
    expect(work.container.querySelector('[data-chat-bubbles]')?.getAttribute('data-chat-bubbles')).toBe('compact');
  });
});
