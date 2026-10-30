import { describe, it, expect, afterAll, beforeAll, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { MessengerRoot } from '../components/messenger/MessengerRoot';
import { useMessengerStore } from '../stores/messengerStore';
import { useUIStore } from '../stores/uiStore';
import type { Chat } from '../types/messenger';

const makeChat = (id: string, title: string, lastAt: string): Chat => ({
  id,
  title,
  avatar: '',
  type: 'direct',
  circle: 'friends',
  peerNodeId: `peer-${id}`,
  unreadCount: 0,
  isOnline: true,
  lastSnippet: 'Тестовий лист',
  lastAt,
  messages: [
    {
      id: `msg-${id}`,
      senderId: `peer-${id}`,
      senderName: title,
      senderAvatar: '',
      text: 'Тестовий лист',
      timestamp: '12:00',
      sentAt: lastAt,
      isSelf: false,
      type: 'text',
      status: 'sent',
    },
  ],
});

describe('pcKeyboardShortcuts: клавіатурні скорочення між 4 колонками', () => {
  beforeAll(() => {
    Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
    Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
  });

  beforeEach(() => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: 1440 });
    Object.defineProperty(window, 'innerHeight', { configurable: true, writable: true, value: 900 });
    useUIStore.setState({ deskPane: null, toasts: [] });
    useMessengerStore.setState({
      chats: [
        makeChat('chat-1', 'Марія', '2025-10-10T12:00:00.000Z'),
        makeChat('chat-2', 'Андрій', '2025-10-10T11:00:00.000Z'),
      ],
      activeChatId: 'chat-1',
      searchQuery: '',
      hydrateFromNode: async () => {},
    });
  });

  it('Ctrl+K фокусує пошук, Alt+ArrowDown переходить до другої розмови, Ctrl+2 відкриває мапу, Escape закриває deskPane без скидання activeChatId', async () => {
    render(<MessengerRoot />);

    expect(useMessengerStore.getState().activeChatId).toBe('chat-1');

    // 1. Ctrl+K відкриває пошук у PhoneChatList і фокусує поле вводу
    fireEvent.keyDown(window, { key: 'k', ctrlKey: true });
    const searchInput = screen.getByRole('searchbox');
    expect(searchInput).toBeInTheDocument();
    expect(document.activeElement).toBe(searchInput);

    // 2. Alt+ArrowDown переводить activeChatId на другу розмову
    fireEvent.keyDown(window, { key: 'ArrowDown', altKey: true });
    expect(useMessengerStore.getState().activeChatId).toBe('chat-2');

    // 3. Ctrl+2 відкриває deskPane = 'map'
    fireEvent.keyDown(window, { key: '2', ctrlKey: true });
    expect(useUIStore.getState().deskPane).toBe('map');
    await waitFor(() => {
      expect(screen.getByTestId('desk-side-pane')).toBeInTheDocument();
    }, { timeout: 10_000 });
    expect(screen.getByTestId('desk-side-pane')).toHaveAttribute('data-pane', 'map');

    // 4. Escape закриває deskPane і зберігає activeChatId
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(useUIStore.getState().deskPane).toBeNull();
    expect(screen.queryByTestId('desk-side-pane')).toBeNull();
    expect(useMessengerStore.getState().activeChatId).toBe('chat-2');
  });
});

const row = (id: string, title: string, lastAt: string, over: Partial<Chat> = {}): Chat => ({
  ...makeChat(id, title, lastAt),
  ...over,
});

describe('pcKeyboardShortcuts: українська розкладка', () => {
  beforeAll(() => {
    Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
    Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
  });

  beforeEach(() => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: 1440 });
    useUIStore.setState({ deskPane: null, toasts: [] });
    useMessengerStore.setState({
      chats: [makeChat('chat-1', 'Марія', '2025-10-10T12:00:00.000Z'), makeChat('chat-2', 'Андрій', '2025-10-10T11:00:00.000Z')],
      activeChatId: 'chat-1',
      searchQuery: '',
      hydrateFromNode: async () => {},
    });
  });

  it('Ctrl+К (key «л», code KeyK) відкриває пошук у списку; на Dvorak (key k, code KeyV) теж', () => {
    render(<MessengerRoot />);
    const ok = fireEvent.keyDown(window, { key: 'л', code: 'KeyK', ctrlKey: true });
    expect(ok).toBe(false);
    expect(document.querySelector('[data-search-box]')).toBeInTheDocument();
    fireEvent.keyDown(window, { key: 'Escape', code: 'Escape' });
    expect(document.querySelector('[data-search-box]')).toBeNull();
    const dvorak = fireEvent.keyDown(window, { key: 'k', code: 'KeyV', ctrlKey: true });
    expect(dvorak).toBe(false);
    expect(document.querySelector('[data-search-box]')).toBeInTheDocument();
  });

  it('Ctrl+А (key «а», code KeyF) перехоплює пошук у розмові замість пошуку браузера', () => {
    render(<MessengerRoot />);
    expect(fireEvent.keyDown(window, { key: 'а', code: 'KeyF', ctrlKey: true })).toBe(false);
  });

  it('Ctrl+ґ (code Backslash) згортає список; Ctrl+& (code Digit2, французька цифра без Shift) відкриває мапу', () => {
    render(<MessengerRoot />);
    expect(screen.getByTestId('chat-list')).toBeInTheDocument();
    fireEvent.keyDown(window, { key: 'ґ', code: 'Backslash', ctrlKey: true });
    expect(screen.queryByTestId('chat-list')).toBeNull();
    fireEvent.keyDown(window, { key: '&', code: 'Digit2', ctrlKey: true });
    expect(useUIStore.getState().deskPane).toBe('map');
  });
});

describe('pcKeyboardShortcuts: Escape знімає один шар за раз', () => {
  beforeAll(() => {
    Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
    Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
  });

  beforeEach(() => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: 1440 });
    useUIStore.setState({ deskPane: null, toasts: [] });
    useMessengerStore.setState({
      chats: [makeChat('chat-1', 'Марія', '2025-10-10T12:00:00.000Z'), makeChat('chat-2', 'Андрій', '2025-10-10T11:00:00.000Z')],
      activeChatId: 'chat-1',
      searchQuery: '',
      isMediaLightboxOpen: false,
      hydrateFromNode: async () => {},
    });
  });

  it('відкрите фото й колонка Мапа: перший Escape закриває лише фото, другий — колонку', async () => {
    render(<MessengerRoot />);
    fireEvent.keyDown(window, { key: '2', code: 'Digit2', ctrlKey: true });
    expect(useUIStore.getState().deskPane).toBe('map');
    act(() => {
      useMessengerStore.getState().openLightbox('data:image/png;base64,iVBORw0KGgo=', 'фото');
    });
    await screen.findByTestId('media-lightbox', undefined, { timeout: 10_000 });

    fireEvent.keyDown(document.body, { key: 'Escape', code: 'Escape' });
    expect(useMessengerStore.getState().isMediaLightboxOpen).toBe(false);
    expect(useUIStore.getState().deskPane).toBe('map');

    fireEvent.keyDown(document.body, { key: 'Escape', code: 'Escape' });
    expect(useUIStore.getState().deskPane).toBeNull();
  });

  it('відкрите фото й набраний пошук у списку: Escape закриває фото, запит не стирається', async () => {
    render(<MessengerRoot />);
    fireEvent.keyDown(window, { key: 'k', code: 'KeyK', ctrlKey: true });
    const input = screen.getByRole('searchbox') as HTMLInputElement;
    fireEvent.change(input, { target: { value: 'абв' } });
    act(() => {
      useMessengerStore.getState().openLightbox('data:image/png;base64,iVBORw0KGgo=', 'фото');
    });
    await screen.findByTestId('media-lightbox', undefined, { timeout: 10_000 });

    fireEvent.keyDown(document.body, { key: 'Escape', code: 'Escape' });
    expect(useMessengerStore.getState().isMediaLightboxOpen).toBe(false);
    expect((screen.getByRole('searchbox') as HTMLInputElement).value).toBe('абв');
  });
});

describe('pcKeyboardShortcuts: Alt+↑/↓ ходить у тому порядку, який видно в списку', () => {
  const realMatchMedia = window.matchMedia;
  afterAll(() => {
    window.matchMedia = realMatchMedia;
  });
  beforeAll(() => {
    Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
    Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
  });

  beforeEach(() => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: 1440 });
    window.matchMedia = ((q: string) => ({
      matches: q.includes('min-width'),
      media: q,
      onchange: null,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    })) as unknown as typeof window.matchMedia;
    useUIStore.setState({ deskPane: null, toasts: [] });
  });

  it('Nightpost, Збережене, закріплені, решта; порожній контакт без листування пропущено', () => {
    useMessengerStore.setState({
      chats: [
        row('ghost', 'Привид', '2025-10-11T09:00:00.000Z', { messages: [], lastSnippet: undefined }),
        row('c1', 'Олена', '2025-10-10T12:00:00.000Z'),
        row('c2', 'Тарас', '2025-10-10T11:00:00.000Z'),
        row('p1', 'Закріплена', '2025-10-03T09:00:00.000Z', { pinned: true }),
        row('chat_saved_notes', 'Збережене', '2025-10-02T09:00:00.000Z', { type: 'saved', peerNodeId: undefined }),
        row('n1', 'Nightpost', '2025-10-01T09:00:00.000Z', { type: 'phantom', peerNodeId: undefined, messages: [] }),
      ],
      activeChatId: 'n1',
      searchQuery: '',
      hydrateFromNode: async () => {},
    });
    render(<MessengerRoot />);

    const shown = Array.from(screen.getByTestId('chat-list').querySelectorAll('[data-row-name]')).map((el) => el.textContent);
    expect(shown).toEqual(['Nightpost', 'Збережене', 'Закріплена', 'Олена', 'Тарас']);

    const visited: string[] = [];
    for (let i = 0; i < 6; i++) {
      fireEvent.keyDown(window, { key: 'ArrowDown', altKey: true });
      visited.push(useMessengerStore.getState().activeChatId);
    }
    expect(visited).toEqual(['chat_saved_notes', 'p1', 'c1', 'c2', 'c2', 'c2']);

    fireEvent.keyDown(window, { key: 'ArrowUp', altKey: true });
    expect(useMessengerStore.getState().activeChatId).toBe('c1');
  });

  it('якщо видимих розмов немає, Alt+↓ не відкриває невидиму', () => {
    useMessengerStore.setState({
      chats: [
        row('g1', 'Привид 1', '2025-10-11T09:00:00.000Z', { messages: [], lastSnippet: undefined }),
        row('g2', 'Привид 2', '2025-10-11T08:00:00.000Z', { messages: [], lastSnippet: undefined }),
      ],
      activeChatId: '',
      searchQuery: '',
      hydrateFromNode: async () => {},
    });
    render(<MessengerRoot />);
    fireEvent.keyDown(window, { key: 'ArrowDown', altKey: true });
    expect(useMessengerStore.getState().activeChatId).toBe('');
  });
});

describe('pcKeyboardShortcuts: Alt+↑/↓ ходить по тому, що видно під вибраною вкладкою списку', () => {
  beforeAll(() => {
    Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
    Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
  });

  beforeEach(() => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: 1440 });
    useUIStore.setState({ deskPane: null, toasts: [] });
    useMessengerStore.setState({
      chats: [
        row('a', 'Анна', '2025-10-10T12:00:00.000Z', { unreadCount: 2 }),
        row('b', 'Богдан', '2025-10-10T11:00:00.000Z'),
        row('c', 'Віра', '2025-10-10T10:00:00.000Z', { unreadCount: 1 }),
        row('d', 'Гліб', '2025-10-09T10:00:00.000Z'),
        row('e', 'Дана', '2025-10-08T10:00:00.000Z'),
        row('f', 'Євген', '2025-10-07T10:00:00.000Z'),
      ],
      activeChatId: '',
      searchQuery: '',
      hydrateFromNode: async () => {},
    });
  });

  const down = () => {
    fireEvent.keyDown(window, { key: 'ArrowDown', altKey: true });
    return useMessengerStore.getState().activeChatId;
  };

  it('вкладка «Непрочитані»: Alt+↓ не заходить у прочитаного Богдана, якого під нею не видно', () => {
    render(<MessengerRoot />);
    fireEvent.click(document.querySelector('[data-filter-tab="unread"]') as HTMLElement);
    const shown = Array.from(screen.getByTestId('chat-list').querySelectorAll('[data-phantom-list] [data-row-name]')).map((el) => el.textContent);
    expect(shown).toEqual(['Анна', 'Віра']);

    expect(down()).toBe('a');
    expect(down()).toBe('c');
  });

  it('вкладка «Усі» (без вибору) ходить усіма: Анна, Богдан, Віра', () => {
    render(<MessengerRoot />);
    expect([down(), down(), down()]).toEqual(['a', 'b', 'c']);
  });

  it('запит у пошуку списку: видно знахідки, а не розмови, тож Alt+↓ не відкриває невидиму', () => {
    render(<MessengerRoot />);
    fireEvent.keyDown(window, { key: 'k', code: 'KeyK', ctrlKey: true });
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'Богд' } });
    expect(down()).toBe('');
  });

  it('список згорнуто: нічого не вибрано вкладкою, Alt+↓ ходить порядком «Усі»', () => {
    render(<MessengerRoot />);
    fireEvent.keyDown(window, { key: '\\', code: 'Backslash', ctrlKey: true });
    expect(screen.queryByTestId('chat-list')).toBeNull();
    expect([down(), down()]).toEqual(['a', 'b']);
  });
});

const asPlatform = (platform: string) => vi.spyOn(window.navigator, 'platform', 'get').mockReturnValue(platform);

describe('pcKeyboardShortcuts: набір тексту не перемикає розмову', () => {
  beforeAll(() => {
    Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
    Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
  });

  afterEach(() => vi.restoreAllMocks());

  beforeEach(() => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: 1440 });
    useUIStore.setState({ deskPane: null, toasts: [] });
    useMessengerStore.setState({
      chats: [makeChat('chat-1', 'Марія', '2025-10-10T12:00:00.000Z'), makeChat('chat-2', 'Андрій', '2025-10-10T11:00:00.000Z')],
      activeChatId: 'chat-1',
      searchQuery: '',
      hydrateFromNode: async () => {},
    });
  });

  const composer = () => screen.getByTestId('talk-root').querySelector('textarea') as HTMLTextAreaElement;

  it.each(['ArrowDown', 'ArrowUp'])('macOS: Alt+%s у полі листа (Option+стрілка = абзац) лишає розмову й подію браузера', (arrow) => {
    asPlatform('MacIntel');
    render(<MessengerRoot />);
    const ta = composer();
    expect(ta).toBeTruthy();
    expect(fireEvent.keyDown(ta, { key: arrow, code: arrow, altKey: true })).toBe(true);
    expect(useMessengerStore.getState().activeChatId).toBe('chat-1');
  });

  it('macOS: Alt+↓ у рядку пошуку списку й у contenteditable теж не перемикає розмову', () => {
    asPlatform('MacIntel');
    render(<MessengerRoot />);
    fireEvent.keyDown(window, { key: 'k', code: 'KeyK', ctrlKey: true });
    const search = screen.getByRole('searchbox');
    expect(fireEvent.keyDown(search, { key: 'ArrowDown', code: 'ArrowDown', altKey: true })).toBe(true);

    const rich = document.createElement('div');
    rich.setAttribute('contenteditable', 'true');
    const inner = document.createElement('span');
    rich.appendChild(inner);
    document.body.appendChild(rich);
    expect(fireEvent.keyDown(inner, { key: 'ArrowDown', code: 'ArrowDown', altKey: true })).toBe(true);
    rich.remove();
    expect(useMessengerStore.getState().activeChatId).toBe('chat-1');
  });

  it('Windows і Linux: Alt+↓ з поля листа йде до наступної розмови, Alt+↑ — назад; каретка там Alt+стрілкою не рухається', () => {
    asPlatform('Linux x86_64');
    render(<MessengerRoot />);
    const ta = composer();
    expect(fireEvent.keyDown(ta, { key: 'ArrowDown', code: 'ArrowDown', altKey: true })).toBe(false);
    expect(useMessengerStore.getState().activeChatId).toBe('chat-2');
    expect(fireEvent.keyDown(ta, { key: 'ArrowUp', code: 'ArrowUp', altKey: true })).toBe(false);
    expect(useMessengerStore.getState().activeChatId).toBe('chat-1');
  });

  it('поза полем вводу Alt+↓ усе ще йде до наступної розмови', () => {
    render(<MessengerRoot />);
    const btn = document.createElement('button');
    document.body.appendChild(btn);
    expect(fireEvent.keyDown(btn, { key: 'ArrowDown', code: 'ArrowDown', altKey: true })).toBe(false);
    btn.remove();
    expect(useMessengerStore.getState().activeChatId).toBe('chat-2');
  });

  it('Ctrl-скорочення з поля листа працюють: Ctrl+2 відкриває мапу, Ctrl+K — пошук у списку', () => {
    render(<MessengerRoot />);
    const ta = composer();
    fireEvent.keyDown(ta, { key: '2', code: 'Digit2', ctrlKey: true });
    expect(useUIStore.getState().deskPane).toBe('map');
    fireEvent.keyDown(ta, { key: 'k', code: 'KeyK', ctrlKey: true });
    expect(screen.getByRole('searchbox')).toBeInTheDocument();
  });
});
