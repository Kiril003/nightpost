import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { GlobalSearchResults } from '../components/messenger/GlobalSearch';
import { ChatArea, PHANTOM_STARTERS } from '../components/messenger/ChatArea';
import { Header } from '../components/messenger/Header';
import { SpaceHome } from '../components/messenger/spaces/SpaceHome';
import { useMessengerStore } from '../stores/messengerStore';
import * as spaceWire from '../services/spaces/spaceWire';
import { translate } from '../i18n';
import type { Chat, Message } from '../types/messenger';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const msg = (id: string, sentAt: string): Message =>
  ({ id, senderId: 'p', senderName: 'П', senderAvatar: '', timestamp: '12:00', type: 'text', text: 'привіт', isSelf: false, sentAt }) as Message;
const chat = (id: string, title: string, at: string, extra: Partial<Chat> = {}): Chat =>
  ({ id, title, type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false, messages: [msg(`${id}-m`, at)], lastAt: at, ...extra }) as unknown as Chat;
const noop = () => {};
const EMPTY = { people: [], letters: [], needle: '', settled: true, network: { kind: 'idle' } } as never;

describe('пошук без запиту: нещодавні замість порожнечі', () => {
  it('показує нещодавніх людей і групи, найсвіжіші першими, без Збереженого й без прози', () => {
    useMessengerStore.setState({
      chats: [
        chat('c-old', 'Марта', '2026-10-20T10:00:00Z'),
        chat('chat_saved_notes', 'Збережене', '2026-10-28T10:00:00Z', { type: 'saved' }),
        chat('c-new', 'Оксана', '2026-10-28T09:00:00Z'),
        chat('g1', 'Двір', '2026-10-27T09:00:00Z', { type: 'group' }),
        chat('c-arch', 'Андрій', '2026-10-28T08:00:00Z', { archived: true }),
      ],
    } as never);
    const onOpenChat = vi.fn();
    render(<GlobalSearchResults query="" found={EMPTY} onOpenChat={onOpenChat} onOpenMessage={noop} />);
    const recent = screen.getByTestId('search-recent');
    const ids = [...recent.querySelectorAll('[data-search-recent]')].map((e) => e.getAttribute('data-search-recent'));
    expect(ids).toEqual(['c-new', 'g1', 'c-old']);
    expect(recent.textContent).toContain(translate('globalSearch.recent', 'uk'));
    fireEvent.click(recent.querySelector('[data-search-recent="g1"]')!);
    expect(onOpenChat).toHaveBeenCalledWith('g1');
  });
});

const area = (c: Chat) => (
  <MemoryRouter>
    <ChatArea
      currentChat={c}
      messages={[]}
      currentUserId="me"
      onOpenLocation={noop} onVotePoll={noop} onPayBillShare={noop} onAddReaction={noop} onReplyMessage={noop}
      selectedMessageIds={[]} onToggleSelectMessage={noop} isSelectionMode={false} isSearching={false} onCloseSearch={noop}
    />
  </MemoryRouter>
);

describe('перший дотик до асистента й Збереженого: мітки, а не проза', () => {
  it('асистент: коротка мітка і 3–4 підказки, без пояснювального речення', () => {
    render(area({ id: 'np', title: 'Nightpost', type: 'phantom', circle: 'all', avatar: '', unreadCount: 0, pinned: false, messages: [] } as unknown as Chat));
    const start = screen.getByTestId('phantom-start');
    const paragraphs = [...start.querySelectorAll('p')].map((p) => p.textContent!.trim());
    expect(paragraphs).toHaveLength(1);
    expect(paragraphs[0].split(/\s+/).length).toBeLessThanOrEqual(3);
    expect(PHANTOM_STARTERS.length).toBeGreaterThanOrEqual(3);
    expect(PHANTOM_STARTERS.length).toBeLessThanOrEqual(4);
    expect(screen.getByTestId('phantom-chips').querySelectorAll('button')).toHaveLength(PHANTOM_STARTERS.length);
  });

  it('асистент: знак по центру з тим самим імʼям, що в рядку й шапці, підказки — рядки на всю ширину з іконкою 1.75, без бурштину', () => {
    render(area({ id: 'np', title: 'Nightpost', type: 'phantom', circle: 'all', avatar: '', unreadCount: 0, pinned: false, messages: [] } as unknown as Chat));
    const start = screen.getByTestId('phantom-start');
    expect(start.className.split(/\s+/)).toContain('items-center');
    expect(start.querySelector('[data-phantom-mark]')!.getAttribute('class')).toContain('w-16');
    const rows = [...screen.getByTestId('phantom-chips').querySelectorAll('button')];
    for (const b of rows) {
      const c = b.className.split(/\s+/);
      expect(c).toContain('w-full');
      expect(c).not.toContain('rounded-full');
      expect(b.querySelector('svg')?.getAttribute('stroke-width')).toBe('1.75');
    }
    expect(start.innerHTML).not.toMatch(/accent|shadow|blur|gradient/);
    expect(start.querySelector('p')!.textContent).toBe('Nightpost');
  });

  it('Збережене: порожній стан по центру, без прози в шапці', () => {
    const saved = { id: 'chat_saved_notes', title: 'Збережене', type: 'saved', circle: 'saved', avatar: '', unreadCount: 0, pinned: false, messages: [] } as unknown as Chat;
    render(area(saved));
    expect(screen.getByTestId('thread-empty').className).toContain('items-center');
    cleanup();
    useMessengerStore.setState({ chats: [saved] } as never);
    const { container } = render(
      <MemoryRouter>
        <Header
          currentChat={saved}
          currentUser={{ id: 'me', name: 'Кирило', avatar: '' } as never}
          onOpenSettings={noop} onOpenGroupDetails={noop} isSoundEnabled onToggleSound={noop} onToggleSearch={noop} isSearching={false} onBack={noop}
        />
      </MemoryRouter>,
    );
    expect(container.textContent).not.toContain('нотатки на цьому телефоні');
  });
});

describe('помилка — з кнопкою «Спробувати ще», а не тупик', () => {
  it('простір не завантажився: мітка, повтор питає ще раз, мертвого «Запросити» немає', async () => {
    const pull = vi.spyOn(spaceWire, 'pullSpace').mockRejectedValue(new Error('down'));
    useMessengerStore.setState({ chats: [chat('s1', 'Клуб', '2026-10-28T09:00:00Z', { type: 'group' })] } as never);
    render(<SpaceHome chatId="s1" />);
    await act(async () => { await Promise.resolve(); });
    const retry = await screen.findByTestId('space-retry');
    expect(screen.queryByTestId('space-invite')).toBeNull();
    const calls = pull.mock.calls.length;
    await act(async () => { fireEvent.click(retry); await Promise.resolve(); });
    expect(pull.mock.calls.length).toBe(calls + 1);
  });

  it('учасники групи не завантажились: повтор, а не речення', () => {
    const src = readFileSync(join(process.cwd(), 'src/components/messenger/GroupProfileSheet.tsx'), 'utf8');
    expect(src).toMatch(/data-testid="group-members-retry"/);
    expect(translate('groupProfileSheet.membersFailed', 'uk')).not.toMatch(/[—.]/);
  });
});
