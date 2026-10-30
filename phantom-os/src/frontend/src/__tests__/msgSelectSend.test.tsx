/**
 * Вибір і надсилання (msg-x, головне подразнення власника): вибір розмов у
 * списку → «Надіслати» — кільком із переглядом кожному (list-fix); вибір листів —
 * проміжок, «схожі» й дії над усім вибраним.
 */
import { beforeAll, beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { ChatArea } from '../components/messenger/ChatArea';
import { SelectionBar } from '../components/messenger/SelectionBar';
import { ForwardMessageModal } from '../components/messenger/ForwardMessageModal';
import { RecipientPicker } from '../components/messenger/recipients/RecipientPicker';
import { useMessengerStore } from '../stores/messengerStore';
import { useSettingsStore } from '../stores/settingsStore';
import { recipientCandidates, textFor } from '../services/recipients';
import { mergeSelection, rangeIds, selectionMarkdown, similarIds } from '../services/msgSelection';
import { skipKey } from '../services/ringGuard';
import type { Chat, Message, UserProfile } from '../types/messenger';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;
const ago = (m: number) => new Date(Date.now() - m * 60_000).toISOString();

const chat = (id: string, title: string, extra: Partial<Chat> = {}): Chat =>
  ({
    id,
    title,
    type: 'dm',
    circle: 'friends',
    messages: [],
    unreadCount: 0,
    lastAt: ago(10),
    lastSelfAt: ago(20),
    ...extra,
  }) as unknown as Chat;

const CHATS = [
  chat('oksana', 'Оксана Мельник', { circle: 'close', lastAt: ago(3) }),
  chat('bohdan', 'Богдан', { circle: 'work', lastAt: ago(30) }),
  chat('dvir', 'Двір', { type: 'group', circle: 'all', lastAt: ago(50) } as Partial<Chat>),
  chat('marta', 'Марта', { pinned: true, lastAt: ago(900) }),
  chat('stranger', 'Ігор', { circle: 'all', lastSelfAt: null }),
  chat('old', 'Курс', { archived: true }),
  chat('chat_phantom_assistant', 'Nightpost', { type: 'phantom', circle: 'all' } as Partial<Chat>),
];

const msg = (id: string, extra: Partial<Message> = {}): Message =>
  ({
    id,
    senderId: 'p1',
    senderName: 'Петро',
    text: `лист ${id}`,
    type: 'text',
    timestamp: '18:00',
    sentAt: '2026-10-21T15:00:00Z',
    isSelf: false,
    status: 'sent',
    ...extra,
  }) as Message;

const FEED = [
  msg('a', { text: 'Хто завтра на збори?' }),
  msg('b', { senderId: 'p2', senderName: 'Олена', text: 'Я буду, візьму список' }),
  msg('c', { senderId: 'me', senderName: 'Кирило', isSelf: true, text: 'Буду о шостій' }),
  msg('d', { text: 'Фото двору', type: 'image', sentAt: '2026-10-22T08:00:00Z' }),
  msg('e', { text: 'Збори о шостій біля під’їзду', sentAt: '2026-10-22T09:00:00Z' }),
];

beforeEach(() => {
  localStorage.clear();
  useSettingsStore.setState({ language: 'uk' } as never);
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  useMessengerStore.setState({ selectedMessageIds: [], multiSelectMode: false, selectionAnchorId: null });
});

describe('кому надіслати: правила', () => {
  it('без запитів, архіву й помічника; закріплені першими, далі свіжі', () => {
    expect(recipientCandidates(CHATS).map((c) => c.id)).toEqual(['marta', 'oksana', 'bohdan', 'dvir']);
  });

  it('{імʼя} стає імʼям людини, а групі — нічим', () => {
    expect(textFor('Привіт, {імʼя}! Субота в силі?', CHATS[0])).toBe('Привіт, Оксана! Субота в силі?');
    expect(textFor('Привіт, {імʼя}! Субота в силі?', CHATS[2])).toBe('Привіт! Субота в силі?');
  });
});

describe('вибір у списку → «Надіслати» — кільком, перегляд кожному (list-fix: смуги людей зверху більше немає)', () => {
  const LISTED = CHATS.map((c) => ({ ...c, lastSnippet: 'привіт' }) as Chat);
  const mount = () =>
    render(
      <PhoneChatList chats={LISTED} currentUser={USER} onSelectChat={vi.fn()} onNewChat={vi.fn()} onOpenChatActions={vi.fn()} />,
    );
  const row = (id: string) => document.querySelector(`[data-chat-row="${id}"]`) as HTMLElement;
  const hold = (id: string) => {
    vi.useFakeTimers();
    fireEvent.touchStart(row(id), { touches: [{ clientX: 40, clientY: 40 }] });
    act(() => {
      vi.advanceTimersByTime(520);
    });
    fireEvent.touchEnd(row(id));
    vi.useRealTimers();
  };
  const tap = (id: string) => {
    fireEvent.touchStart(row(id), { touches: [{ clientX: 40, clientY: 40 }] });
    fireEvent.touchEnd(row(id));
    fireEvent.click(row(id));
  };

  it('довгий дотик і дотики вибирають; «Надіслати» — чипи й перегляд з імʼям кожного', () => {
    const send = vi.fn();
    useMessengerStore.setState({ sendMessage: send } as never);
    mount();
    expect(document.querySelector('[data-recipient-strip]')).toBeNull();
    expect(document.querySelector('[data-send-many]')).toBeNull();

    hold('oksana');
    tap('dvir');
    tap('bohdan');
    expect(row('oksana').getAttribute('aria-pressed')).toBe('true');
    fireEvent.click(document.querySelector('[data-sel-do="send"]')!);
    fireEvent.click(screen.getByText('Далі'));
    const dock = document.querySelector('[data-send-many]')!;
    expect([...dock.querySelectorAll('[data-pick-chip]')].map((c) => c.getAttribute('data-pick-chip'))).toEqual(['oksana', 'dvir', 'bohdan']);

    fireEvent.change(dock.querySelector('[data-send-many-text]')!, { target: { value: 'Привіт, {імʼя}! Збір о 18:00' } });
    fireEvent.click(dock.querySelector('[data-send-many-go]')!);
    const sheet = screen.getByTestId('send-many-preview');
    const preview = (id: string) => sheet.querySelector(`[data-preview-for="${id}"] [data-preview-text]`)!.textContent;
    expect(preview('oksana')).toBe('Привіт, Оксана! Збір о 18:00');
    expect(preview('dvir')).toBe('Привіт! Збір о 18:00');

    fireEvent.click(sheet.querySelector('[data-preview-drop="bohdan"]')!);
    fireEvent.click(screen.getByText('Надіслати · 2'));
    expect(send).toHaveBeenCalledTimes(2);
    expect(send).toHaveBeenCalledWith('Привіт, Оксана! Збір о 18:00', { to: 'oksana' });
    expect(send).toHaveBeenCalledWith('Привіт! Збір о 18:00', { to: 'dvir' });
    expect(document.querySelector('[data-send-many]')).toBeNull();
  });

  it('«Надіслати» відкриває повний список з тим самим вибором, і в ньому можна додати', () => {
    mount();
    hold('oksana');
    fireEvent.click(document.querySelector('[data-sel-do="send"]')!);
    const sheet = screen.getByTestId('recipient-sheet');
    expect(sheet.querySelector('[data-pick-row="oksana"]')!.getAttribute('aria-pressed')).toBe('true');
    fireEvent.click(sheet.querySelector('[data-pick-row="marta"]')!);
    expect(sheet.querySelector('[data-pick-row="marta"]')!.getAttribute('aria-pressed')).toBe('true');
  });
});

describe('список адресатів: пошук не губить вибраного', () => {
  it('вибране живе поза пошуком; сітка без кіл і чипів (аркуш «Надіслати»)', () => {
    let ids: string[] = [];
    const { rerender } = render(<RecipientPicker chats={CHATS} selected={ids} onChange={(n) => (ids = n)} />);
    const again = () => rerender(<RecipientPicker chats={CHATS} selected={ids} onChange={(n) => (ids = n)} />);
    expect(document.querySelector('[data-pick-circle]')).toBeNull();
    fireEvent.click(document.querySelector('[data-pick-row="oksana"]')!);
    again();
    fireEvent.change(document.querySelector('[data-pick-search]')!, { target: { value: 'бог' } });
    expect(document.querySelector('[data-pick-row="oksana"]')).toBeNull();
    fireEvent.click(document.querySelector('[data-pick-row="bohdan"]')!);
    again();
    expect(ids).toEqual(['oksana', 'bohdan']);
    fireEvent.change(document.querySelector('[data-pick-search]')!, { target: { value: '' } });
    expect(document.querySelector('[data-pick-row="oksana"]')!.getAttribute('aria-pressed')).toBe('true');
    expect(document.querySelectorAll('[data-pick-check]').length).toBe(2);
  });
});

describe('переслати кільком — охорона кіл для кожного окремо', () => {
  beforeEach(() => localStorage.removeItem(skipKey));

  it('ширшому колу — приховано, близькому — як є', () => {
    const go = vi.fn();
    const chats = [
      chat('from', 'Оксана', { circle: 'close', circles: ['close'] } as Partial<Chat>),
      chat('team', 'Команда', { circle: 'work', circles: ['work'] } as Partial<Chat>),
      chat('mom', 'Мама', { circle: 'close', circles: ['close'] } as Partial<Chat>),
    ];
    render(
      <ForwardMessageModal
        isOpen
        onClose={() => {}}
        chats={chats}
        currentChatId="from"
        messagesToForward={[msg('x', { text: 'Дзвони 0991234567 о 19:00' })]}
        onConfirmForward={go}
      />,
    );
    fireEvent.click(document.querySelector('[data-pick-row="team"]')!);
    fireEvent.click(document.querySelector('[data-pick-row="mom"]')!);
    fireEvent.click(screen.getByText('Надіслати · 2'));
    const guard = screen.getByTestId('forward-ring-guard');
    expect(guard.querySelector('[data-preview-for="team"]')!.textContent).toContain('▢▢▢');
    expect(guard.querySelector('[data-preview-for="mom"]')!.textContent).toContain('0991234567');
    fireEvent.click(screen.getByText(/Сховати адреси й номери/));
    expect(go).toHaveBeenCalledWith('team', { redact: true });
    expect(go).toHaveBeenCalledWith('mom');
  });
});

describe('вибір листів: правила', () => {
  it('проміжок в обидва боки, схожі за автором, родом і днем', () => {
    expect(rangeIds(FEED, 'd', 'b')).toEqual(['b', 'c', 'd']);
    expect(similarIds(FEED, FEED[0], 'sender')).toEqual(['a', 'd', 'e']);
    expect(similarIds(FEED, FEED[3], 'kind')).toEqual(['d']);
    expect(similarIds(FEED, FEED[4], 'day')).toEqual(['d', 'e']);
    expect(mergeSelection(FEED, ['e'], ['a', 'e'])).toEqual(['a', 'e']);
  });

  it('markdown: автор раз на поспіль, текст цитатою', () => {
    expect(selectionMarkdown([FEED[0], FEED[3], FEED[1]])).toBe(
      '**Петро** · 18:00\n> Хто завтра на збори?\n> Фото двору\n\n**Олена** · 18:00\n> Я буду, візьму список',
    );
  });
});

describe('смуга вибору: дії над усім вибраним', () => {
  const room = () => chat('c2', 'Двір', { type: 'group', messages: FEED } as Partial<Chat>);
  const pick = (ids: string[], anchor = ids[ids.length - 1]) =>
    useMessengerStore.setState({ selectedMessageIds: ids, multiSelectMode: true, selectionAnchorId: anchor, currentUser: USER } as never);

  it('лічильник і «Усі від Петра» додають схожі', () => {
    pick(['a']);
    render(<SelectionBar chat={room()} />);
    expect(document.querySelector('[data-sel-count]')!.textContent).toBe('1 вибрано');
    fireEvent.click(document.querySelector('[data-sel-by="sender"]')!);
    expect(useMessengerStore.getState().selectedMessageIds).toEqual(['a', 'd', 'e']);
    expect(document.querySelector('[data-sel-count]')!.textContent).toBe('3 вибрано');
  });

  it('«Задачі» — один спільний список, лист — пункт', () => {
    const sendCard = vi.fn().mockResolvedValue(null);
    useMessengerStore.setState({ sendCard } as never);
    pick(['a', 'e']);
    render(<SelectionBar chat={room()} />);
    fireEvent.click(document.querySelector('[data-sel-action="tasks"]')!);
    expect(sendCard).toHaveBeenCalledTimes(1);
    const [kind, data] = sendCard.mock.calls[0];
    expect(kind).toBe('widget:family-checklist');
    expect(data.items.map((i: { text: string }) => i.text)).toEqual(['Хто завтра на збори?', 'Збори о шостій біля під’їзду']);
    expect(useMessengerStore.getState().multiSelectMode).toBe(false);
  });

  it('«Видалити для всіх» — лише коли всі вибрані мої', () => {
    const del = vi.fn().mockResolvedValue(null);
    useMessengerStore.setState({ deleteMessage: del } as never);
    pick(['a', 'c']);
    const { unmount } = render(<SelectionBar chat={room()} />);
    fireEvent.click(document.querySelector('[data-sel-action="delete"]')!);
    expect(screen.queryByText('Видалити для всіх')).toBeNull();
    unmount();
    pick(['c']);
    render(<SelectionBar chat={room()} />);
    fireEvent.click(document.querySelector('[data-sel-action="delete"]')!);
    fireEvent.click(screen.getByText('Видалити для всіх'));
    expect(del).toHaveBeenCalledWith('c', true);
  });

  it('«Цитувати» кладе всі вибрані в одну відповідь', () => {
    const startReply = vi.fn();
    useMessengerStore.setState({ startReply } as never);
    pick(['a', 'b']);
    render(<SelectionBar chat={room()} />);
    fireEvent.click(document.querySelector('[data-sel-action="quote"]')!);
    expect(startReply).toHaveBeenCalledWith(FEED[0], 'Петро: Хто завтра на збори?\nОлена: Я буду, візьму список');
  });

  it('«Markdown» копіює вибране', () => {
    const write = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: write }, configurable: true });
    pick(['b']);
    render(<SelectionBar chat={room()} />);
    fireEvent.click(document.querySelector('[data-sel-action="copy"]')!);
    expect(write).toHaveBeenCalledWith('**Олена** · 18:00\n> Я буду, візьму список');
  });
});

describe('стрічка у виборі: дотик по листу вибирає, Shift — проміжок, протяг — проміжок', () => {
  const area = (selected: string[], onToggle = vi.fn(), onSelect = vi.fn(), anchor: string | null = null) =>
    render(
      <MemoryRouter>
        <ChatArea
          currentChat={chat('c2', 'Двір', { type: 'group', messages: FEED } as Partial<Chat>)}
          messages={FEED}
          currentUserId="me"
          onOpenLocation={() => {}}
          onVotePoll={() => {}}
          onPayBillShare={() => {}}
          onAddReaction={() => {}}
          onReplyMessage={() => {}}
          selectedMessageIds={selected}
          onToggleSelectMessage={onToggle}
          onSelectMessages={onSelect}
          selectionAnchorId={anchor}
          isSelectionMode={selected.length > 0}
          isSearching={false}
          onCloseSearch={() => {}}
        />
      </MemoryRouter>,
    );

  it('дотик по рядку перемикає вибір', () => {
    const toggle = vi.fn();
    area(['a'], toggle);
    fireEvent.click(screen.getByText('Буду о шостій'));
    expect(toggle).toHaveBeenCalledWith('c');
  });

  it('Shift+дотик вибирає проміжок від останнього', () => {
    const select = vi.fn();
    area(['b'], vi.fn(), select, 'b');
    fireEvent.click(document.querySelector('[data-msg-id="e"] [data-select-box]')!, { shiftKey: true });
    expect(select).toHaveBeenCalledWith(['b', 'c', 'd', 'e']);
  });

  it('протяг по позначках вибирає все, над чим пройшов палець', () => {
    const select = vi.fn();
    area(['a'], vi.fn(), select, 'a');
    const target = document.querySelector('[data-msg-id="d"]')!;
    const prev = document.elementFromPoint;
    document.elementFromPoint = () => target as Element;
    try {
      fireEvent.pointerDown(document.querySelector('[data-msg-id="b"] [data-select-box]')!, { button: 0 });
      act(() => {
        window.dispatchEvent(new MouseEvent('pointermove', { clientX: 10, clientY: 300 }));
        window.dispatchEvent(new MouseEvent('pointerup'));
      });
    } finally {
      document.elementFromPoint = prev;
    }
    expect(select).toHaveBeenLastCalledWith(['a', 'b', 'c', 'd']);
  });
});
