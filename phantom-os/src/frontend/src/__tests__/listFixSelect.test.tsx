/**
 * list-fix C2: довгий дотик по рядку вибирає розмову. Шапка — X, «Вибрано: N»,
 * закріпити, без звуку, видалити, надіслати, ⋮ (прочитано, в архів, усі дії).
 * «Збережене» не глушиться, не архівується, не видаляється; теки не вибираються.
 * BACK і X виходять із вибору. Правий клік лишає повне меню дій.
 */
import type React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { selectionCaps } from '../components/messenger/list/listSelection';
import { useMoments } from '../services/moments';
import { useMessengerStore } from '../stores/messengerStore';
import { useSettingsStore } from '../stores/settingsStore';
import { usePhoneLayerStore } from '../stores/phoneLayerStore';
import type { Chat, UserProfile } from '../types/messenger';

const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;
const ago = (m: number) => new Date(Date.now() - m * 60_000).toISOString();

const chat = (id: string, title: string, extra: Partial<Chat> = {}): Chat =>
  ({
    id,
    title,
    type: 'dm',
    circle: 'friends',
    peerNodeId: `p_${id}`,
    messages: [],
    unreadCount: 0,
    lastSnippet: 'привіт',
    lastAt: ago(10),
    lastSelfAt: ago(20),
    ...extra,
  }) as unknown as Chat;

const SAVED = chat('chat_saved_notes', 'Збережене', { type: 'saved', circle: 'saved', peerNodeId: undefined, lastAt: ago(300) } as Partial<Chat>);
const CHATS = [
  SAVED,
  chat('olia', 'Оля Коваль', { unreadCount: 2, lastAt: ago(3) }),
  chat('yana', 'Яна Ліщук', { lastAt: ago(30) }),
  chat('marko', 'Марко Шевчук', { lastAt: ago(50), muted: true } as Partial<Chat>),
  chat('pc', 'Мій ноутбук', { mine: true } as Partial<Chat>),
  chat('old', 'Курс', { archived: true }),
];

const realRefresh = useMoments.getState().refresh;
let togglePinChat = vi.fn();
let toggleMuteChat = vi.fn();
let toggleArchiveChat = vi.fn();

beforeEach(() => {
  vi.useFakeTimers();
  localStorage.clear();
  useSettingsStore.setState({ language: 'uk' } as never);
  togglePinChat = vi.fn();
  toggleMuteChat = vi.fn();
  toggleArchiveChat = vi.fn();
  useMessengerStore.setState({ typingStatus: {}, drafts: {}, togglePinChat, toggleMuteChat, toggleArchiveChat } as never);
  useMoments.setState({ refresh: async () => undefined, list: [] } as never);
});

afterEach(() => {
  vi.useRealTimers();
  cleanup();
  useMoments.setState({ list: null, refresh: realRefresh } as never);
});

const mount = (extra: Partial<React.ComponentProps<typeof PhoneChatList>> = {}) => {
  const actions = vi.fn();
  const open = vi.fn();
  const view = render(
    <PhoneChatList chats={CHATS} currentUser={USER} onSelectChat={open} onNewChat={vi.fn()} onOpenChatActions={actions} {...extra} />,
  );
  return { ...view, actions, open };
};

const row = (id: string) => document.querySelector(`[data-chat-row="${id}"]`) as HTMLElement;
const hold = (id: string) => {
  fireEvent.touchStart(row(id), { touches: [{ clientX: 50, clientY: 50 }] });
  act(() => {
    vi.advanceTimersByTime(520);
  });
  fireEvent.touchEnd(row(id));
};
/** Дотик пальцем: touchstart → touchend → click, як на телефоні. */
const tap = (id: string) => {
  fireEvent.touchStart(row(id), { touches: [{ clientX: 50, clientY: 50 }] });
  fireEvent.touchEnd(row(id));
  fireEvent.click(row(id));
};
const bar = () => document.querySelector('[data-list-select-bar]') as HTMLElement | null;
const btn = (key: string) => document.querySelector(`[data-sel-do="${key}"]`) as HTMLButtonElement;

describe('list-fix C2: вибір розмов у списку', () => {
  it('довгий дотик вибирає (а не відкриває меню дій); шапка «Вибрано: 1», позначка на аватарі', () => {
    const { actions, open } = mount();
    expect(bar()).toBeNull();
    hold('yana');
    expect(actions).not.toHaveBeenCalled();
    expect(open).not.toHaveBeenCalled();
    expect(bar()).toBeTruthy();
    expect(bar()!.querySelector('[data-sel-count]')!.textContent).toBe('Вибрано: 1');
    expect(row('yana').getAttribute('data-row-checked')).toBe('on');
    expect(row('yana').querySelector('[data-row-lead] [data-row-check="on"]')).toBeTruthy();
    expect(row('olia').getAttribute('data-row-checked')).toBe('off');
    expect(document.querySelector('[data-list-title]')).toBeNull();
  });

  it('дотик перемикає вибір, розмова не відкривається; останній знятий — вихід', () => {
    const { open } = mount();
    hold('yana');
    tap('olia');
    expect(open).not.toHaveBeenCalled();
    expect(bar()!.querySelector('[data-sel-count]')!.textContent).toBe('Вибрано: 2');
    tap('olia');
    tap('yana');
    expect(bar()).toBeNull();
  });

  it('теки не вибираються: на рядках тек позначки немає', () => {
    mount();
    hold('yana');
    const folders = [...document.querySelectorAll('[data-folder-row]')];
    expect(folders.length).toBeGreaterThan(0);
    for (const f of folders) expect(f.querySelector('[data-row-check]')).toBeNull();
  });

  it('«Збережене» у виборі: без звуку, видалити й в архів вимкнені, закріпити — ні', () => {
    mount({ onDeleteChat: vi.fn() });
    hold('chat_saved_notes');
    tap('yana');
    expect(row('chat_saved_notes').querySelector('[data-saved-avatar]')).toBeTruthy();
    expect(btn('delete').disabled).toBe(true);
    expect(btn('pin').disabled).toBe(false);
    fireEvent.click(btn('more'));
    expect(btn('mute').disabled).toBe(true);
    expect(btn('archive').disabled).toBe(true);
  });

  it('закріпити, без звуку, в архів, прочитано — ті самі дії сховища; після дії вибір закривається', () => {
    const read = vi.fn();
    mount({ onMarkRead: read });
    hold('yana');
    tap('olia');
    fireEvent.click(btn('pin'));
    expect(togglePinChat.mock.calls.map((c) => c[0]).sort()).toEqual(['olia', 'yana']);
    expect(bar()).toBeNull();

    hold('yana');
    tap('marko');
    expect(btn('mute')).toBeNull();
    fireEvent.click(btn('more'));
    fireEvent.click(btn('mute'));
    expect(toggleMuteChat.mock.calls.map((c) => c[0])).toEqual(['yana']);

    hold('olia');
    fireEvent.click(btn('more'));
    fireEvent.click(btn('read'));
    expect(read).toHaveBeenCalledWith(expect.objectContaining({ id: 'olia' }));

    hold('olia');
    fireEvent.click(btn('more'));
    fireEvent.click(btn('archive'));
    expect(toggleArchiveChat).toHaveBeenCalledWith('olia');
  });

  it('видалити: питає один раз і видаляє кожну вибрану', async () => {
    const del = vi.fn().mockResolvedValue(undefined);
    mount({ onDeleteChat: del });
    hold('yana');
    tap('marko');
    fireEvent.click(btn('delete'));
    const sheet = screen.getByTestId('list-delete-sheet');
    expect(sheet.textContent).toContain('Видалити розмови · 2');
    fireEvent.click(screen.getAllByText('Видалити').pop()!);
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(del.mock.calls.map((c) => c[0].id)).toEqual(['yana', 'marko']);
    expect(bar()).toBeNull();
  });

  it('без onDeleteChat кошик вимкнений', () => {
    mount();
    hold('yana');
    expect(btn('delete').disabled).toBe(true);
  });

  it('«Надіслати» відкриває вибір адресатів з вибраними розмовами, далі — смуга надсилання', () => {
    mount();
    hold('yana');
    tap('olia');
    fireEvent.click(btn('send'));
    expect(bar()).toBeNull();
    const sheet = screen.getByTestId('recipient-sheet');
    expect(sheet.querySelector('[data-pick-row="yana"]')!.getAttribute('aria-pressed')).toBe('true');
    expect(sheet.querySelector('[data-pick-row="olia"]')!.getAttribute('aria-pressed')).toBe('true');
    const dock = document.querySelector('[data-send-many]') as HTMLElement;
    expect([...dock.querySelectorAll('[data-pick-chip]')].map((c) => c.getAttribute('data-pick-chip'))).toEqual(['yana', 'olia']);
  });

  it('системний BACK і X виходять із вибору', () => {
    mount();
    hold('yana');
    const layers = usePhoneLayerStore.getState().layers;
    expect(Object.keys(layers)).toContain('list:select');
    act(() => layers['list:select']());
    expect(bar()).toBeNull();
    expect(Object.keys(usePhoneLayerStore.getState().layers)).not.toContain('list:select');

    hold('yana');
    fireEvent.click(document.querySelector('[data-sel-exit]')!);
    expect(bar()).toBeNull();
  });

  it('меню дій лишається: правий клік і «⋮ → Усі дії» однієї розмови; contextmenu від довгого дотику — ні', () => {
    const { actions } = mount();
    fireEvent.contextMenu(row('olia'));
    expect(actions).toHaveBeenCalledWith(expect.objectContaining({ id: 'olia' }));
    actions.mockClear();

    hold('yana');
    fireEvent.contextMenu(row('yana'));
    expect(actions).not.toHaveBeenCalled();
    fireEvent.click(btn('more'));
    fireEvent.click(btn('all'));
    expect(actions).toHaveBeenCalledWith(expect.objectContaining({ id: 'yana' }));
    expect(bar()).toBeNull();
  });

  it('у виборі рядок не свайпається: дій під рядком немає', () => {
    mount();
    hold('yana');
    const r = row('olia');
    fireEvent.touchStart(r, { touches: [{ clientX: 50, clientY: 50 }] });
    fireEvent.touchMove(r, { touches: [{ clientX: 160, clientY: 52 }] });
    expect(document.querySelector('[data-swipe-action]')).toBeNull();
    fireEvent.touchEnd(r);
  });
});

describe('selectionCaps', () => {
  const unread = (c: Chat) => c.unreadCount > 0;
  it('усі вже закріплені — «відкріпити»; Nightpost не видаляється', () => {
    const pinned = [chat('a', 'A', { pinned: true }), chat('b', 'B', { pinned: true })];
    expect(selectionCaps(pinned, { unread, canDelete: true }).pin).toBe('unpin');
    expect(selectionCaps([...pinned, chat('c', 'C')], { unread, canDelete: true }).pin).toBe('pin');
    const node = chat('chat_phantom_assistant', 'Nightpost', { type: 'phantom' } as Partial<Chat>);
    expect(selectionCaps([node], { unread, canDelete: true }).remove).toBe(false);
  });
});
